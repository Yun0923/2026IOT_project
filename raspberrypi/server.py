from flask import Flask, jsonify, request
import csv
import os
import glob
import time
import threading
from datetime import datetime, timezone, timedelta

try:
    import serial
except ImportError:
    serial = None


app = Flask(__name__)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(BASE_DIR, "drink_records.csv")

BAUD_RATE = 9600

arduino = None
serial_lock = threading.Lock()
alarm_lock = threading.Lock()

# ==============================
# 스마트폰 시간 동기화용 설정
# ==============================
KST = timezone(timedelta(hours=9))

time_offset_ms = 0
time_sync_lock = threading.Lock()


alarm_state = {
    "alarm_active": False,
    "result": "IDLE",
    "amount_ml": 0,
    "raw_message": "",
    "scheduled_time": "",
    "started_at": "",
    "completed_at": ""
}


def update_time_offset_from_client(client_time_ms):
    global time_offset_ms

    try:
        client_time_ms = int(client_time_ms)
    except Exception:
        return False

    server_time_ms = int(time.time() * 1000)

    with time_sync_lock:
        time_offset_ms = client_time_ms - server_time_ms

    print(f"[INFO] Time synced. offset_ms={time_offset_ms}")
    return True


def get_synced_now_string():
    with time_sync_lock:
        offset = time_offset_ms

    synced_epoch_ms = int(time.time() * 1000) + offset
    synced_dt = datetime.fromtimestamp(synced_epoch_ms / 1000, tz=KST)

    return synced_dt.strftime("%Y-%m-%d %H:%M:%S")


def add_cors_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    return response


@app.after_request
def after_request(response):
    return add_cors_headers(response)


def ensure_csv_exists():
    if not os.path.exists(CSV_PATH):
        with open(CSV_PATH, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "scheduled_time",
                "started_at",
                "completed_at",
                "result",
                "amount_ml",
                "raw_message"
            ])


def find_arduino_port():
    candidates = []

    candidates.extend(glob.glob("/dev/ttyACM*"))
    candidates.extend(glob.glob("/dev/ttyUSB*"))
    candidates.extend(glob.glob("/dev/serial/by-id/*"))

    if not candidates:
        return None

    return candidates[0]


def connect_arduino():
    global arduino

    if serial is None:
        print("[ERROR] pyserial is not installed.")
        return None

    if arduino is not None and arduino.is_open:
        return arduino

    port = find_arduino_port()

    if port is None:
        print("[ERROR] Arduino serial port not found.")
        return None

    try:
        print(f"[INFO] Connecting Arduino on {port}")
        arduino = serial.Serial(port, BAUD_RATE, timeout=1)

        # Serial 연결 시 아두이노가 리셋되므로 setup()이 끝날 시간을 줌
        time.sleep(2.5)

        arduino.reset_input_buffer()
        print("[INFO] Arduino connected.")
        return arduino

    except Exception as e:
        print(f"[ERROR] Failed to connect Arduino: {e}")
        arduino = None
        return None


def write_record(scheduled_time, started_at, completed_at, result, amount_ml, raw_message):
    ensure_csv_exists()

    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            scheduled_time,
            started_at,
            completed_at,
            result,
            amount_ml,
            raw_message
        ])


def read_records():
    ensure_csv_exists()

    records = []

    with open(CSV_PATH, "r", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            records.append(row)

    return records


def wait_for_arduino_result():
    global alarm_state

    print("[INFO] Arduino result listener started.")

    while True:
        with alarm_lock:
            if not alarm_state["alarm_active"]:
                print("[INFO] Alarm is no longer active. Listener stopped.")
                break

        ser = connect_arduino()

        if ser is None:
            with alarm_lock:
                alarm_state["result"] = "SERIAL_ERROR"
                alarm_state["raw_message"] = "Arduino serial connection failed"
            time.sleep(1)
            continue

        try:
            if ser.in_waiting > 0:
                line = ser.readline().decode("utf-8", errors="ignore").strip()

                if not line:
                    continue

                print(f"[ARDUINO] {line}")

                if line.startswith("DRINK_SUCCESS"):
                    parts = line.split(",")

                    amount_ml = 0
                    if len(parts) >= 2:
                        try:
                            amount_ml = int(float(parts[1]))
                        except ValueError:
                            amount_ml = 0

                    completed_at = get_synced_now_string()

                    with alarm_lock:
                        alarm_state["alarm_active"] = False
                        alarm_state["result"] = "SUCCESS"
                        alarm_state["amount_ml"] = amount_ml
                        alarm_state["raw_message"] = line
                        alarm_state["completed_at"] = completed_at

                        scheduled_time = alarm_state["scheduled_time"]
                        started_at = alarm_state["started_at"]

                    write_record(
                        scheduled_time=scheduled_time,
                        started_at=started_at,
                        completed_at=completed_at,
                        result="SUCCESS",
                        amount_ml=amount_ml,
                        raw_message=line
                    )

                    print("[INFO] Drink success recorded.")
                    break

                elif line.startswith("DRINK_NOT_ENOUGH"):
                    parts = line.split(",")

                    amount_ml = 0
                    if len(parts) >= 2:
                        try:
                            amount_ml = int(float(parts[1]))
                        except ValueError:
                            amount_ml = 0

                    with alarm_lock:
                        alarm_state["result"] = "NOT_ENOUGH"
                        alarm_state["amount_ml"] = amount_ml
                        alarm_state["raw_message"] = line

                    print("[INFO] Drink amount not enough. Alarm remains active.")

            time.sleep(0.2)

        except Exception as e:
            print(f"[ERROR] Serial read error: {e}")

            try:
                if arduino is not None:
                    arduino.close()
            except Exception:
                pass

            time.sleep(1)


@app.route("/", methods=["GET"])
def index():
    return jsonify({
        "status": "running",
        "message": "Raspberry Pi Drink Alarm Server"
    })


@app.route("/records", methods=["GET"])
def records():
    return jsonify(read_records())


@app.route("/time/sync", methods=["POST", "OPTIONS"])
def sync_time():
    if request.method == "OPTIONS":
        return jsonify({"status": "ok"})

    data = request.get_json(silent=True) or {}
    client_time_ms = data.get("client_time_ms")

    if client_time_ms is None:
        return jsonify({
            "status": "error",
            "message": "client_time_ms is required"
        }), 400

    success = update_time_offset_from_client(client_time_ms)

    if not success:
        return jsonify({
            "status": "error",
            "message": "invalid client_time_ms"
        }), 400

    return jsonify({
        "status": "synced",
        "server_record_time": get_synced_now_string()
    })


@app.route("/alarm/start", methods=["POST", "OPTIONS"])
def alarm_start():
    global alarm_state

    if request.method == "OPTIONS":
        return jsonify({"status": "ok"})

    with alarm_lock:
        if alarm_state["alarm_active"]:
            return jsonify({
                "status": "already_running",
                "message": "Alarm is already active.",
                "alarm": alarm_state
            })

    ser = connect_arduino()

    if ser is None:
        with alarm_lock:
            alarm_state["alarm_active"] = False
            alarm_state["result"] = "SERIAL_ERROR"
            alarm_state["raw_message"] = "Arduino serial connection failed"

        return jsonify({
            "status": "error",
            "message": "Arduino serial connection failed."
        }), 500

    data = request.get_json(silent=True) or {}
    scheduled_time = data.get("scheduled_time", "APP")

    client_time_ms = data.get("client_time_ms")
    if client_time_ms is not None:
        update_time_offset_from_client(client_time_ms)

    started_at = get_synced_now_string()

    try:
        with serial_lock:
            ser.reset_input_buffer()
            ser.write(b"ALARM_START\n")
            ser.flush()

        with alarm_lock:
            alarm_state = {
                "alarm_active": True,
                "result": "WAITING",
                "amount_ml": 0,
                "raw_message": "",
                "scheduled_time": scheduled_time,
                "started_at": started_at,
                "completed_at": ""
            }

        listener = threading.Thread(target=wait_for_arduino_result, daemon=True)
        listener.start()

        print("[INFO] ALARM_START sent to Arduino.")

        return jsonify({
            "status": "started",
            "message": "ALARM_START sent to Arduino.",
            "alarm": alarm_state
        })

    except Exception as e:
        print(f"[ERROR] Failed to send ALARM_START: {e}")

        with alarm_lock:
            alarm_state["alarm_active"] = False
            alarm_state["result"] = "SEND_ERROR"
            alarm_state["raw_message"] = str(e)

        return jsonify({
            "status": "error",
            "message": "Failed to send ALARM_START.",
            "detail": str(e)
        }), 500


@app.route("/alarm/status", methods=["GET"])
def alarm_status():
    with alarm_lock:
        current_state = dict(alarm_state)

    return jsonify(current_state)


@app.route("/alarm/reset", methods=["POST", "OPTIONS"])
def alarm_reset():
    global alarm_state

    if request.method == "OPTIONS":
        return jsonify({"status": "ok"})

    with alarm_lock:
        alarm_state = {
            "alarm_active": False,
            "result": "IDLE",
            "amount_ml": 0,
            "raw_message": "",
            "scheduled_time": "",
            "started_at": "",
            "completed_at": ""
        }

    return jsonify({
        "status": "reset",
        "message": "Alarm state reset."
    })


if __name__ == "__main__":
    ensure_csv_exists()
    app.run(host="0.0.0.0", port=5000, debug=False, use_reloader=False)
