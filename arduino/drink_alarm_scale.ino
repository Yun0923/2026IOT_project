#include "HX711.h"
#include <LiquidCrystal.h>

#define DT_PIN 3
#define SCK_PIN 2
#define BUTTON_PIN 9

HX711 scale;
LiquidCrystal lcd(10, 8, 4, 5, 6, 7);

float scale_factor = -429.6;

const float DRINK_SUCCESS_GRAM = 150.0;
const float MIN_BASE_WEIGHT = 100.0;
const float CUP_LIFT_WEIGHT = 30.0;
const float RETURN_MIN_WEIGHT = 50.0;
const float STABLE_RANGE = 5.0;

const unsigned long STABLE_TIME = 1500;
const unsigned long STATUS_INTERVAL = 1000;
const unsigned long BUTTON_DELAY = 150;

float baseWeight = 0.0;
float currentWeight = 0.0;
float finalWeight = 0.0;
float returnCheckWeight = 0.0;

unsigned long returnStartTime = 0;
unsigned long lastStatusTime = 0;
unsigned long lastButtonTime = 0;

bool checkingReturn = false;
int lastButtonState = HIGH;

enum State {
  WAIT_ALARM,
  WAIT_BASE,
  WAIT_LIFT,
  WAIT_RETURN,
  CHECK_RESULT,
  SUCCESS_STATE
};

State currentState = WAIT_ALARM;

float readWeight() {
  float w = scale.get_units(5);

  if (w > -1.0 && w < 1.0) {
    w = 0.0;
  }

  return w;
}

bool isButtonPressed() {
  int currentButtonState = digitalRead(BUTTON_PIN);
  bool pressed = false;

  if (lastButtonState == HIGH && currentButtonState == LOW) {
    if (millis() - lastButtonTime > BUTTON_DELAY) {
      pressed = true;
      lastButtonTime = millis();
    }
  }

  lastButtonState = currentButtonState;
  return pressed;
}

void lcdPrint(String line1, String line2) {
  lcd.clear();

  lcd.setCursor(0, 0);
  lcd.print(line1);

  lcd.setCursor(0, 1);
  lcd.print(line2);
}

void resetValues() {
  baseWeight = 0.0;
  currentWeight = 0.0;
  finalWeight = 0.0;
  returnCheckWeight = 0.0;
  returnStartTime = 0;
  checkingReturn = false;
}

void startAlarm() {
  resetValues();

  Serial.println("ALARM_STARTED");
  Serial.println("Put cup with water on scale");
  Serial.println("Press Button to set base weight");

  lcdPrint("Alarm On", "Press Button");

  currentState = WAIT_BASE;
}

void goWaitingAlarm() {
  resetValues();

  Serial.println("WAITING_ALARM");

  lcdPrint("Waiting Alarm", "From Raspberry");

  currentState = WAIT_ALARM;
}

void handleSerialCommand() {
  if (Serial.available() > 0) {
    String command = Serial.readStringUntil('\n');
    command.trim();

    if (command == "ALARM_START") {
      startAlarm();
    } else if (command == "RESET") {
      goWaitingAlarm();
    } else if (command == "TARE") {
      lcdPrint("Tare", "Keep Empty");
      delay(1000);
      scale.tare();
      Serial.println("TARE_COMPLETE");
      lcdPrint("Waiting Alarm", "From Raspberry");
    }
  }
}

void printStatus() {
  if (millis() - lastStatusTime < STATUS_INTERVAL) {
    return;
  }

  lastStatusTime = millis();

  Serial.print("[STATE] ");

  if (currentState == WAIT_ALARM) {
    Serial.print("WAIT_ALARM");
  } else if (currentState == WAIT_BASE) {
    Serial.print("WAIT_BASE");
  } else if (currentState == WAIT_LIFT) {
    Serial.print("WAIT_LIFT");
  } else if (currentState == WAIT_RETURN) {
    Serial.print("WAIT_RETURN");
  } else if (currentState == CHECK_RESULT) {
    Serial.print("CHECK_RESULT");
  } else if (currentState == SUCCESS_STATE) {
    Serial.print("SUCCESS");
  }

  Serial.print(" | Current: ");
  Serial.print(currentWeight, 1);
  Serial.print(" g | Base: ");
  Serial.print(baseWeight, 1);
  Serial.print(" g | Drink: ");
  Serial.print(baseWeight - currentWeight, 1);
  Serial.println(" g");
}

void setup() {
  Serial.begin(9600);
  Serial.setTimeout(50);

  pinMode(BUTTON_PIN, INPUT_PULLUP);

  lcd.begin(16, 2);
  lcdPrint("Initializing", "Scale...");

  Serial.println("Initializing the scale");
  Serial.println("Do not put anything on the load cell.");

  scale.begin(DT_PIN, SCK_PIN);
  scale.set_scale(scale_factor);

  delay(1000);
  scale.tare();

  Serial.println("Tare complete");
  Serial.println("ARDUINO_READY");
  Serial.println("Send ALARM_START");

  lcdPrint("Waiting Alarm", "From Raspberry");

  currentState = WAIT_ALARM;
}

void loop() {
  handleSerialCommand();

  if (currentState == WAIT_ALARM) {
    return;
  }

  currentWeight = readWeight();

  if (currentState == WAIT_BASE) {
    if (isButtonPressed()) {
      if (currentWeight >= MIN_BASE_WEIGHT) {
        baseWeight = currentWeight;

        Serial.print("BASE_SET,");
        Serial.println(baseWeight, 1);

        lcdPrint("Base Set", String(baseWeight, 1) + " g");
        delay(1000);

        lcdPrint("Drink Water", "Lift Cup");
        currentState = WAIT_LIFT;
      } else {
        Serial.print("INVALID_BASE_WEIGHT,");
        Serial.println(currentWeight, 1);

        lcdPrint("Put Cup First", "Then Press Btn");
        delay(1200);

        lcdPrint("Alarm On", "Press Button");
      }
    }
  }

  else if (currentState == WAIT_LIFT) {
    if (currentWeight <= CUP_LIFT_WEIGHT) {
      Serial.println("CUP_LIFTED");

      lcdPrint("Cup Lifted", "Drink Return");
      delay(800);

      checkingReturn = false;
      currentState = WAIT_RETURN;
    }
  }

  else if (currentState == WAIT_RETURN) {
    if (currentWeight >= RETURN_MIN_WEIGHT) {
      if (checkingReturn == false) {
        checkingReturn = true;
        returnCheckWeight = currentWeight;
        returnStartTime = millis();
      } else {
        float diff = currentWeight - returnCheckWeight;

        if (diff < 0) {
          diff = diff * -1;
        }

        if (diff <= STABLE_RANGE) {
          if (millis() - returnStartTime >= STABLE_TIME) {
            finalWeight = currentWeight;

            Serial.println("CUP_RETURNED");
            Serial.println("CHECKING_WEIGHT");

            lcdPrint("Checking", "Keep Still");
            delay(800);

            currentState = CHECK_RESULT;
          }
        } else {
          checkingReturn = false;
        }
      }
    } else {
      checkingReturn = false;
    }
  }

  else if (currentState == CHECK_RESULT) {
    float drinkAmount = baseWeight - finalWeight;

    Serial.print("BASE_WEIGHT,");
    Serial.println(baseWeight, 1);

    Serial.print("FINAL_WEIGHT,");
    Serial.println(finalWeight, 1);

    Serial.print("DRINK_AMOUNT,");
    Serial.println(drinkAmount, 1);

    if (drinkAmount >= DRINK_SUCCESS_GRAM) {
      Serial.print("DRINK_SUCCESS,");
      Serial.println(drinkAmount, 1);

      lcdPrint("Success", "Drank " + String(drinkAmount, 0) + " ml");
      currentState = SUCCESS_STATE;
    } else {
      Serial.print("DRINK_NOT_ENOUGH,");
      Serial.println(drinkAmount, 1);

      lcdPrint("Not Enough", "Drink More");
      delay(1500);

      lcdPrint("Drink More", "Lift Cup");
      checkingReturn = false;
      currentState = WAIT_LIFT;
    }
  }

  else if (currentState == SUCCESS_STATE) {
    delay(3000);
    goWaitingAlarm();
  }

  printStatus();
}
