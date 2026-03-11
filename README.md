# bluetooth-to-keyboard-input-esp32-s3-zero

Send text from macOS to an **ESP32-S3 Zero** over BLE; the ESP32 types it out
as a **USB HID keyboard** on whichever machine it is plugged into.

```
macOS script ──BLE (encrypted)──► ESP32-S3 Zero ──USB HID──► any computer
```

Also supports a simple scripting language inspired by Ducky Script. See the Readme file [script.md](script.md) for details.

---

## Project layout

```
bluetooth-input/
├── firmware/
│   ├── firmware.ino   ← Arduino sketch for the ESP32-S3
│   └── keytypes.h     ← shared types (KeyLayout, KeyOS, KeyEntry)
└── macos/
    ├── send_ble.py    ← Python BLE client for macOS
    ├── script_runner.py ← ducky-script parser (used by send_ble.py --script)
    ├── requirements.txt
    └── tests.sh       ← automated and manual test suite
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

## 3 — Change the pre-shared key (important before production use)

Both sides share a 32-byte PSK used to authenticate the ESP32's public key and
to authenticate layout/OS writes. The repository ships with a placeholder key —
**you must replace it** before deploying.

Generate a new key:

```bash
python3 -c "import os; print(os.urandom(32).hex())"
```

Update both locations with the same value:

- **`firmware/firmware.ino`** — find `static const uint8_t PSK[32]` and replace
  the byte array with your key (split the hex string into pairs, prefix each
  with `0x`, separate with commas).
- **`macos/send_ble.py`** — find `PSK = bytes.fromhex(...)` and replace the hex
  string with your key.

Re-flash the firmware after changing it.

---

## 4 — Run the macOS script

### Install dependencies

```bash
cd macos
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

> macOS may ask for Bluetooth permission the first time you run the script.
> Allow it in **System Settings → Privacy & Security → Bluetooth**.

### Specify your keyboard layout and target OS

Two flags control how symbols are typed. Set them to match the **target machine** (the one the ESP32 is plugged into).

| Flag | Options | Default | Purpose |
|------|---------|---------|---------|
| `--layout` | `en-US`, `en-GB` | `en-US` | Keyboard input source / locale |
| `--os` | `other`, `macos` | `other` | OS of the target machine |
| `--enter` | *(flag, no value)* | off | Press Enter after the text || `--min-delay` | integer ms | `20` | Minimum per-keystroke delay |
| `--max-delay` | integer ms | `20` | Maximum per-keystroke delay — if different from `--min-delay`, each keystroke uses a random delay in that range |
> **`--os macos`** is needed when the target is a Mac running the British layout,
> because macOS uses **Option+3** for `#` whereas Windows/Linux/Android use a
> dedicated ISO key (HID 0x32).
>
> `other` covers Windows, Linux, and Android — they all behave identically.

### One-shot (send a single string and exit)

```bash
# US layout, any OS (defaults)
python send_ble.py "Hello, World!"

# UK layout on a Mac, press Enter at the end
python send_ble.py --layout en-GB --os macos --enter 'Hello, World *###£$@'

# UK layout on Windows / Linux
python send_ble.py --layout en-GB --os other 'Hello, World *###£$@'

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

`macos/tests.sh` exercises the full send path and keyboard layouts. Run it
from the `macos/` directory (the venv must already exist — see **Install
dependencies** above):

```bash
cd macos
bash tests.sh
```

You will see a menu:

```
1) Automatic tests (448 and 896 chars)
2) Manual tests (1344 and 5000 chars)
3) Full UK keyboard test (make sure you have the UK layout set in your OS)
4) Full US keyboard test (make sure you have the US layout set in your OS)
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

The ESP32 exposes six characteristics under service `12340000-1234-1234-1234-123456789abc`:

| UUID suffix | Direction | Purpose |
|-------------|-----------|---------|
| `...0001...` | write | Encrypted text payload (ECIES) |
| `...0002...` | write | Layout selection — `"en-US"` or `"en-GB"` (HMAC-authenticated) |
| `...0003...` | write | OS selection — `"macos"` or `"other"` (HMAC-authenticated) |
| `...0004...` | read | ESP32's ephemeral X25519 public key (32 bytes) |
| `...0005...` | read | `HMAC-SHA256(PSK, pubkey)` — proves the key is genuine |
| `...0006...` | read/notify | Chunk-completion counter — firmware notifies after each chunk is typed |
| `...0007...` | write | Keystroke delay — `[uint16 minMs][uint16 maxMs]` big-endian (HMAC-authenticated); each keystroke uses a random delay drawn from this range |
| `...0008...` | write | Raw HID event — encrypted with ECIES; payload: `[1-byte type][optional 1-byte data]`. Types: `0x01` KEY_TAP (HID keycode), `0x02` MOD_DOWN (bitmask), `0x03` MOD_UP (bitmask), `0x04` MOD_CLEAR |

### Connection flow

1. Script scans for `ESP32-KB` and connects.
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
| Public key authentication FAILED | PSK mismatch — make sure `PSK` in `send_ble.py` exactly matches `PSK[32]` in `firmware.ino` and re-flash. |
| `#` types as `£` on a Mac | Add `--os macos`; macOS uses Option+3 for `#` on the British layout. |
| `#` types as `£` on Windows/Linux | Use `--os other` (the default); HID 0x32 produces `#` there. |
| `@` and `"` are swapped | Make sure `--layout en-GB` is set to match the target machine's input source. |
| `£` is skipped or missing | Use `--layout en-GB`; `£` is only in the GB keycode table. |
| Long strings are cut off | Use vim or a browser field as the target instead of a raw terminal prompt (macOS terminal line buffer caps at ~1024 chars). |
| macOS Bluetooth permission error | Go to **System Settings → Privacy & Security → Bluetooth** and enable access for Terminal / your app. |
| Upload fails | Hold **BOOT** + press **RESET** to enter download mode before uploading. |
| Timed out waiting for firmware ready | The ESP32 is still typing the previous chunk. Increase `READY_TIMEOUT` in `send_ble.py` if sending very long strings. |
