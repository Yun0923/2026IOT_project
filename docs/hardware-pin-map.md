# Arduino Hardware Pin Map

## Components
- Arduino Uno
- Load Cell
- HX711
- LCD 16x2
- Button
- Raspberry Pi

## Pin Connections

| Component | Arduino Pin | Role |
|---|---|---|
| HX711 DT | D3 | Load cell data |
| HX711 SCK | D2 | Load cell clock |
| Button | D9 | Base weight setting |
| LCD RS | D10 | LCD control |
| LCD E | D8 | LCD enable |
| LCD D4 | D4 | LCD data |
| LCD D5 | D5 | LCD data |
| LCD D6 | D6 | LCD data |
| LCD D7 | D7 | LCD data |

## Serial Communication
Arduino communicates with Raspberry Pi through USB Serial at 9600 baud.
