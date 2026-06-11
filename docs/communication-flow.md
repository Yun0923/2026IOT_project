# Communication Flow

## Android App <-> Raspberry Pi

The Android app communicates with the Raspberry Pi Flask server through HTTP API requests.

Main API examples:
- POST /alarm/start
- GET /records
- GET /

The app sends alarm start requests and retrieves saved drink records.

## Raspberry Pi <-> Arduino

The Raspberry Pi communicates with Arduino through USB Serial.

Main serial messages:
- ALARM_START
- BASE_SET,450.0
- CUP_LIFTED
- CUP_RETURNED
- DRINK_AMOUNT,170.0
- DRINK_SUCCESS,170.0
- DRINK_NOT_ENOUGH,80.0

## Result Flow

Android App
→ Raspberry Pi Flask Server
→ Arduino
→ Load Cell Measurement
→ Arduino Result Judgment
→ Raspberry Pi Record Save
→ Android App Record Display
