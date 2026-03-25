# bluetooth-to-keyboard-input-esp32-s3-zero

Send text from your desktop (macOS, Linux, or Windows) to an **ESP32-S3 Zero** over BLE; the ESP32 types it out
as a **USB HID keyboard** on whichever machine it is plugged into.

```
desktop script ──BLE (encrypted)──► ESP32-S3 Zero ──USB HID──► any computer
```

Supports a simple scripting language inspired by Ducky Script. See the Readme file [script.md](script.md) for details.

Supports setting custom USB hid properties like vendor ID, product ID etc. that you can see with `ioreg -p IOUSB -l -w 0 | grep -A 20 -B 20 '"USB Product Name" = "b"'` on macOS.

Supports mouse input (move, click, scroll) in addition to keyboard.

---

## Project layout

```
bluetooth-input/
├── firmware/
│   ├── firmware.ino   ← Arduino sketch for the ESP32-S3
│   └── keytypes.h     ← shared types (KeyLayout, KeyOS, KeyEntry)
└── desktop/
    ├── send_ble.py      ← Python BLE client (macOS, Linux, Windows)
    ├── script_runner.py ← ducky-script parser (used by send_ble.py --script)
    ├── provision.py     ← first-time setup, re-provisioning, and factory reset
    ├── list_devices.py  ← scan and display nearby BLE devices
    ├── requirements.txt
    └── tests.sh         ← automated and manual test suite
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

4. Install the **Adafruit NeoPixel** library:
   **Sketch → Include Library → Manage Libraries** → search *NeoPixel* → install
   **Adafruit NeoPixel** by Adafruit.

   The firmware drives the onboard WS2812 RGB LED on **GPIO21**. It blinks red
   while idle, blue while a BLE client is connected, and green while typing.

5. Open `firmware/firmware.ino`, select the correct port, and click **Upload**.
   > On first flash you may need to hold **BOOT** while pressing **RESET** to
   > enter download mode.

6. After flashing, **unplug and replug** the USB cable. The ESP32 now enumerates
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

## 3 — Manage device identity

Each physical device has a BLE name and a PSK stored in its flash (NVS). On
first flash both are the compiled-in defaults (`ESP32-KB` / hardcoded PSK).
Use the tools below to assign unique identities and keep track of them.

### Discover nearby devices

```bash
python list_devices.py
```

Scans for 10 seconds and prints a colour-coded table of every BLE device in
range:

```
St  Name             Address                                RSSI  Alias
──  ───────────────  ────────────────────────────────────  ─────  ──────
✓   ESP32-KB-office  4CB2…A32C                               -53  office
⚠   ESP32-KB         E854…310A                               -78
●   ESP32-KB-home2   AB12…FF01                               -82
✗   AirPods          75E7…A377                               -67
```

| Symbol | Colour | Meaning |
|--------|--------|---------|
| `✓` | green  | Configured in `devices.json` with a custom PSK |
| `⚠` | yellow | Using the factory default PSK — provision it! |
| `●` | cyan   | Has the ESP32-KB service UUID but is not in your config |
| `✗` | dim    | Unrelated Bluetooth device |

Use `--timeout SECONDS` to scan longer (default: 10 s).

Add `--read-version` (or `-V`) to also connect to each ESP32-KB device and
display its firmware version. This adds a short connection per device (~2–3 s
each) and requires no PSK:

```bash
python list_devices.py --read-version
```

```
St  Name             Address           RSSI  Alias        Version
──  ───────────────  ────────────────  ─────  ───────────  ───────
✓   ESP32-KB-office  4CB2…A32C          -53  office       v1.1
⚠   ESP32-KB         E854…310A          -78               v1.1
```

### First-time provisioning

Omit `--device` when the device is still using factory defaults:

```bash
python provision.py --new-alias office-desk --new-name "ESP32-KB-office"
```

This will:
1. Connect to the device advertising as `ESP32-KB` (using the factory default PSK).
2. Generate a new random 32-byte PSK.
3. Write the provisioning characteristic — the ESP32 saves the new name and PSK
   to flash (NVS) and restarts, advertising as `ESP32-KB-office`.
4. Save the alias, BLE name, and PSK to `~/.bluetooth-input/devices.json`.

### Re-provisioning (change name or PSK on an existing device)

Always supply `--device` when targeting an already-provisioned device:

```bash
python provision.py --device office-desk --new-alias office-desk --new-name "ESP32-KB-office"
```

The `--device` flag selects which entry in `devices.json` to authenticate with.
The device restarts automatically and the config is updated in place.

### USB identity

Each device exposes a configurable USB descriptor identity (VID, PID, manufacturer string, serial number).
Pass any combination of the flags below during first-time provisioning **or** re-provisioning:

| Flag | Default | Description |
|------|---------|-------------|
| `--usb-vid HEX` | `0x303A` | USB Vendor ID (Espressif default) |
| `--usb-pid HEX` | `0x1001` | USB Product ID |
| `--usb-manufacturer TEXT` | `ESP32-S3` | Manufacturer string (max 64 chars) |
| `--usb-serial TEXT` | *(empty)* | Serial number string (max 64 chars) |

Examples:

```bash
# First-time provisioning with custom USB identity
python provision.py --new-alias office-desk --new-name "ESP32-KB-office" \
    --usb-vid 0x1234 --usb-pid 0x5678 \
    --usb-manufacturer "ACME Corp" --usb-serial "SN-001"

# Change only the serial number on an existing device (other USB fields kept from stored config)
python provision.py --device office-desk --new-alias office-desk --new-name "ESP32-KB-office" \
    --usb-serial "SN-002"
```

When re-provisioning, any `--usb-*` flag you omit keeps its **currently stored** value from `devices.json`.

The USB identity takes effect after the ESP32 restarts following provisioning.
`firmwareVersion` is hardcoded in the firmware and cannot be changed over the air.

### Factory reset

Wipes the NVS on the device (name and PSK revert to firmware defaults) and
removes the entry from `devices.json`:

```bash
python provision.py --device office-desk --factory-reset
```

After the reset the device advertises as `ESP32-KB` again and the factory
default PSK is active.

> **Note:** Reflashing the firmware does **not** erase NVS — the stored name
> and PSK survive a normal upload. Use `--factory-reset` or enable
> **Tools → Erase All Flash Before Sketch Upload** in the Arduino IDE if you
> want a clean slate after reflashing.

### Config file format

`~/.bluetooth-input/devices.json`:

```json
{
  "devices": {
    "office-desk": {
      "ble_name": "ESP32-KB-office",
      "psk": "a3f1c8e2...",
      "usb_vid": 12346,
      "usb_pid": 4097,
      "usb_manufacturer": "ESP32-S3",
      "usb_serial": ""
    },
    "home-pc": {
      "ble_name": "ESP32-KB-home",
      "psk": "b4e2d9f1...",
      "usb_vid": 4660,
      "usb_pid": 22136,
      "usb_manufacturer": "ACME Corp",
      "usb_serial": "SN-001"
    }
  }
}
```

VID and PID are stored as decimal integers in JSON (e.g. `0x303A` → `12346`).

> **If you don't provision**, `send_ble.py` (when run without `--device`)
> connects to whatever device responds to the default name `ESP32-KB` using
> the factory PSK. This is only safe for personal use on a trusted network.

---

## 4 — Run the desktop script

### Install dependencies

```bash
cd desktop
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

> **macOS** may ask for Bluetooth permission the first time you run the script.
> Allow it in **System Settings → Privacy & Security → Bluetooth**.
>
> **Linux** requires BlueZ (`sudo apt install bluez`) and your user in the `bluetooth` group
> (`sudo usermod -aG bluetooth $USER`, then re-login), or run with `sudo`.
>
> **Windows** requires no special setup — WinRT Bluetooth is used automatically.

### Specify your keyboard layout and target OS

Two flags control how symbols are typed. Set them to match the **target machine** (the one the ESP32 is plugged into).

| Flag | Options | Default | Purpose |
|------|---------|---------|----------|
| `--device` | alias string | *(none)* | Device alias from `devices.json`. Omit to connect to the default-named `ESP32-KB` device with the factory PSK. |
| `--layout` | `en-US`, `en-GB` | `en-US` | Keyboard input source / locale |
| `--os` | `other`, `macos` | `other` | OS of the target machine |
| `--enter` | *(flag, no value)* | off | Press Enter after the text |
| `--min-delay` | integer ms | `20` | Minimum gap delay after each key release |
| `--max-delay` | integer ms | `20` | Maximum gap delay — if different from `--min-delay`, each keystroke uses a random delay in that range |
| `--min-delay-hold` | integer ms | `20` | Minimum key hold duration |
| `--max-delay-hold` | integer ms | `20` | Maximum key hold duration |
> **`--os macos`** is needed when the target is a Mac running the British layout,
> because macOS uses **Option+3** for `#` whereas Windows/Linux use a
> dedicated ISO key (HID 0x32).
>
> `other` covers Windows, Linux, and Android. When targeting Android, always
> use `--layout en-US` and set the physical keyboard layout to **English (US)**
> in Android itself (**Settings → General Management → Keyboard list and default
> → Physical keyboard → select your keyboard → English (US)**). Android decodes
> HID scan codes with its internal US keymap regardless of the keyboard language
> setting, so `en-GB` mappings do not work and `£`/`€` cannot be typed via HID
> at all.

### One-shot (send a single string and exit)

```bash
# US layout, any OS (defaults, single device)
python send_ble.py "Hello, World!"

# Specific device by alias
python send_ble.py --device office-desk "Hello, World!"

# UK layout on a Mac, press Enter at the end
python send_ble.py --device office-desk --layout en-GB --os macos --enter 'Hello, World *###£$@'

# UK layout on Windows / Linux
python send_ble.py --device office-desk --layout en-GB --os other 'Hello, World *###£$@'

# Android — use en-US layout (Android always decodes HID as US)
python send_ble.py --device office-desk --layout en-US --os other --enter 'Hello, Android!'

# Random per-keystroke delay between 50 and 150 ms (mimics human typing speed)
python send_ble.py --min-delay 50 --max-delay 150 "Hello, World!"

# Run a ducky-script file
python send_ble.py --script my_script.txt

# Run a script with UK layout on macOS and a slow initial typing speed
python send_ble.py --layout en-GB --os macos --min-delay 80 --max-delay 120 --script my_script.txt
```

Long strings are automatically split into chunks of up to 448 bytes and sent
sequentially. The script waits for the ESP32 to finish typing each chunk before
sending the next, so there is no practical length limit.

> **Note:** Applications that buffer a full line before processing (raw terminal
> prompts, `cat`, etc.) may drop characters beyond their internal buffer limit
> (~1024 chars on macOS). Use an application that reads keystrokes directly —
> vim, a browser input field, a password prompt — and any length works fine.

### Interactive mode (keep connection open)

```bash
# UK layout on a Mac — each line is submitted with Enter
python send_ble.py --layout en-GB --os macos --enter

# US layout, any OS
python send_ble.py
```

Type a line and press Return to send it. Press Return on a blank line or
Ctrl-C to disconnect.

### Running the test suite

`desktop/tests.sh` exercises the full send path and keyboard layouts. Run it
from the `desktop/` directory (the venv must already exist — see **Install
dependencies** above):

```bash
cd desktop
bash tests.sh
```

You will see a menu:

```
1) Automatic tests (448 and 896 chars)
2) Manual tests (1344 and 5000 chars)
3) Full UK keyboard test (make sure you have the UK layout set in your OS)
4) Full US keyboard test (make sure you have the US layout set in your OS)
5) Script tests
```

**Automatic tests (options 1, 3, 4)** send text via BLE and confirm the
received characters match the expected pattern via stdin — no manual steps
required. Run them with the terminal focused.

**Manual tests (option 2)** send longer strings (1 344 and 5 000 chars). They
prompt you to open vim (`vim /tmp/t.text +startinsert`) in another terminal,
give you 5 seconds to get ready, then send the payload. After you save and
quit vim (`:wq`), the script reads the file back and checks it.

---

## How it works

### BLE GATT characteristics

The ESP32 exposes characteristics under service `12340000-1234-1234-1234-123456789abc`:

| UUID suffix | Direction | Purpose |
|-------------|-----------|---------|
| `...0001...` | write | Encrypted text payload (ECIES) |
| `...0002...` | write | Layout selection — `"en-US"` or `"en-GB"` (HMAC-authenticated) |
| `...0003...` | write | OS selection — `"macos"` or `"other"` (HMAC-authenticated) |
| `...0004...` | read | ESP32's ephemeral X25519 public key (32 bytes) |
| `...0005...` | read | `HMAC-SHA256(PSK, pubkey)` — proves the key is genuine |
| `...0006...` | read/notify | Chunk-completion counter — firmware notifies after each chunk is typed |
| `...0007...` | write | Keystroke delays — `[uint16 minHold][uint16 maxHold][uint16 minGap][uint16 maxGap]` big-endian ms (HMAC-authenticated) |
| `...0008...` | write | Raw HID event — encrypted with ECIES; payload: `[1-byte type][optional 1-byte data]`. Types: `0x01` KEY_TAP, `0x02` MOD_DOWN, `0x03` MOD_UP, `0x04` MOD_CLEAR |
| `...0009...` | write | Provisioning — `HMAC(currentPSK, inner)` where `inner = [name_len:1][name][new_psk:32][vid:2 BE][pid:2 BE][mfr_len:1][mfr][serial_len:1][serial]`; `name_len=0` triggers factory reset; ESP32 saves to NVS and restarts |
| `...000A...` | write | Raw (unencrypted) mouse event — `[1-byte type][payload]` |
| `...000B...` | read/write | Mouse-enable toggle — `HMAC(PSK, [0x00\|0x01])`; read returns current state; write triggers NVS save and device restart |
| `...000C...` | read | Firmware version — 2 bytes big-endian (e.g. `0x01 0x01` = v1.1); no authentication required |

### Connection flow

1. Script scans for the device BLE name and connects.
2. Subscribes to BLE Notify on the ready characteristic (`...0006...`).
3. Reads the ESP32's ephemeral X25519 public key and verifies its HMAC signature
   against the PSK. Aborts if the signature is wrong (MITM protection).
4. Writes the layout and OS selections (each prefixed with `HMAC-SHA256(PSK, value)`).
5. For each chunk of text:
   - Encrypts with ECIES: generate ephemeral X25519 keypair → ECDH → HKDF-SHA256 → AES-256-GCM.
   - Prepends a 4-byte monotonic counter inside the ciphertext (replay attack prevention).
   - Writes the encrypted packet to `...0001...`.
   - Waits for the firmware to notify an incremented completion counter before
     sending the next chunk.

### Security properties

| Property | Mechanism |
|----------|-----------|
| Confidentiality | AES-256-GCM per message |
| Integrity / authenticity | GCM authentication tag |
| Forward secrecy | Fresh X25519 keypair generated on every BLE connection |
| MITM protection | `HMAC-SHA256(PSK, pubkey)` verified before trusting the key |
| Replay attacks | Per-session monotonic counter checked by firmware; reset on reconnect |
| Unauthenticated control writes | Layout/OS writes carry `HMAC-SHA256(PSK, value)` |

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Device not found during scan | Confirm the ESP32 is powered and the USB cable supports data (not charge-only). Check it enumerates as a keyboard in **System Information → USB**. |
| `list_devices.py` shows `⚠` for a provisioned device | The stored PSK in `devices.json` is still the factory default. Run `provision.py --device ALIAS --new-alias ALIAS --new-name NAME` to assign a real PSK. |
| Public key authentication FAILED | PSK mismatch — the device's stored PSK differs from what `devices.json` (or the factory default) says. Re-provision or factory-reset the device. |
| Reflashing didn't clear the old name/PSK | NVS is in a separate flash partition and survives normal uploads. Use `provision.py --factory-reset` or enable **Erase All Flash Before Sketch Upload** in Arduino IDE. |
| `#` types as `£` on a Mac | Add `--os macos`; macOS uses Option+3 for `#` on the British layout. |
| `#` types as `£` on Windows/Linux | Use `--os other` (the default); HID 0x32 produces `#` there. |
| `@` and `"` are swapped | Make sure `--layout en-GB` is set to match the target machine's input source. |
| `£` is skipped or missing | Use `--layout en-GB`; `£` is only in the GB keycode table. |
| Long strings are cut off | Use vim or a browser field as the target instead of a raw terminal prompt (macOS terminal line buffer caps at ~1024 chars). |
| macOS Bluetooth permission error | Go to **System Settings → Privacy & Security → Bluetooth** and enable access for Terminal / your app. |
| Upload fails | Hold **BOOT** + press **RESET** to enter download mode before uploading. |
| Timed out waiting for firmware ready | The ESP32 is still typing the previous chunk. Increase `READY_TIMEOUT` in `send_ble.py` if sending very long strings. |
