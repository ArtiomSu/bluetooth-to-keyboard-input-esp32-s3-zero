# bluetooth-input-esp32-s3

Send text from macOS to an **ESP32-S3 Zero** over BLE; the ESP32 types it out
as a **USB HID keyboard** on whichever machine it is plugged into.

```
macOS script ──BLE──► ESP32-S3 Zero ──USB HID──► any computer
```

---

## Project layout

```
bluetooth-input/
├── firmware/
│   └── firmware.ino   ← Arduino sketch for the ESP32-S3
└── macos/
    ├── send_ble.py    ← Python BLE client for macOS
    └── requirements.txt
```

---

## 1 — Flash the ESP32-S3

### Arduino IDE setup

1. Add the ESP32 board package if you haven't already:
   **File → Preferences → Additional board manager URLs**
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
   Then **Tools → Board → Boards Manager** → search *esp32* → install **esp32 by Espressif Systems** ≥ 2.0.14.

2. Select the board:
   **Tools → Board → ESP32 Arduino → ESP32S3 Dev Module**
   (The Waveshare ESP32-S3 Zero is compatible with this generic board entry.)

3. Set these board options in the **Tools** menu:

   | Option | Value |
   |--------|-------|
   | USB Mode | **USB-OTG (TinyUSB)** |
   | USB CDC on Boot | **Disabled** |
   | Flash Size | 4MB (QIO) |
   | Partition Scheme | Default 4MB with spiffs |
   | PSRAM | Disabled |

4. Open `firmware/firmware.ino`, select the correct port, and click **Upload**.
   > On first flash you may need to hold **BOOT** while pressing **RESET** to
   > enter download mode.

5. After flashing, **unplug and replug** the USB cable. The ESP32 now enumerates
   as a USB HID keyboard **and** starts BLE advertising as `ESP32-KB`.

---

## 2 — Update the firmware

After the initial flash, re-flashing is straightforward:

1. **Enter download mode** — hold the **BOOT** button on the ESP32-S3 Zero,
   then press and release **RESET** (keep holding BOOT), then release BOOT.
   The device re-enumerates as a serial/JTAG download port.

2. In Arduino IDE, reselect the correct port under **Tools → Port** if it
   changed (it often does when switching from HID mode to download mode).

3. Click **Upload**. The IDE will compile and flash automatically.

4. After upload completes, press **RESET** once (or unplug/replug) to boot
   into the new firmware.

> **Tip:** If the port disappears entirely after flashing (because the device
> now enumerates only as a HID keyboard), simply hold BOOT + tap RESET again
> to re-enter download mode — the serial port will reappear.

---

## 3 — Run the macOS script

### Install dependencies

```bash
cd macos
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

> macOS may ask for Bluetooth permission the first time you run the script.
> Allow it in **System Settings → Privacy & Security → Bluetooth**.

### One-shot (send a single string and exit)

```bash
python send_ble.py "Hello, World!"
```

### Interactive mode (keep connection open)

```bash
python send_ble.py
```

Type a line and press Return to send it. Press Return on a blank line or
Ctrl-C to disconnect.

---

## How it works

1. **BLE GATT server** — the ESP32 exposes one writable characteristic:
   - Service UUID: `12340000-1234-1234-1234-123456789abc`
   - Characteristic UUID: `12340001-1234-1234-1234-123456789abc`

2. The macOS script scans for a device named `ESP32-KB`, connects, and writes
   the UTF-8-encoded string to the characteristic.

3. The ESP32 receives the data in a callback, queues it, and in `loop()` calls
   `Keyboard.print()` to type each character through USB HID.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Device not found during scan | Confirm the ESP32 is powered and the USB cable supports data (not charge-only). Check it enumerates as a keyboard in **System Information → USB**. |
| Characters typed incorrectly | The HID keyboard defaults to **US layout**. Change the layout in the Arduino sketch if needed. |
| macOS Bluetooth permission error | Go to **System Settings → Privacy & Security → Bluetooth** and enable access for Terminal / your app. |
| Upload fails | Hold **BOOT** + press **RESET** to enter download mode before uploading. |
| `write_gatt_char` raises MTU error | Reduce `CHUNK_SIZE` in `send_ble.py` to `100` and retry. |
