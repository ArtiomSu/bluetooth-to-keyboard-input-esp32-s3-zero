# Android support

This contains two projects:

- `standalone-app` — a modern Material Design app that replicates all features of the desktop Python app with a proper mobile UI.
- `keepass-2-android-plugin` — a plugin for KeePass2Android that uses the ESP32 bridge to type credentials into the target machine. This is a primary use case.

---

## Standalone app

### Features to implement

- **BLE scan + connect** — scan for nearby ESP32-KB devices and connect
- **Send text** — type or paste text and send it to the connected device
- **Script editor + runner** — write and run Ducky-style scripts (full parity with `script_runner.py`)
- **Device management** — saved device list with alias, BLE name, and PSK (equivalent to `~/.bluetooth-input/devices.json`)
- **Provisioning flow** — first-time setup and re-provisioning (set BLE name + PSK)
- **Settings** — keyboard layout (en-US / en-GB), target OS (other / macOS), hold/gap delay tuning

### Technical notes

- Written in Kotlin with Material Design 3 components
- BLE handled via Android's `BluetoothLeScanner` / `BluetoothGatt` APIs — note Android BLE has OEM-specific quirks; connection and MTU handling must be robust
- **Encryption**: must re-implement the X25519 + AES-256-GCM + HMAC-PSK scheme from `send_ble.py` — use the **Tink** library or BouncyCastle; this is the trust anchor and must match the firmware exactly
- PSKs stored in Android Keystore (not plaintext SharedPreferences)
- Android 12+ requires `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions; background scanning is restricted and should be avoided
- Share Target intent — register as a target for the standard Android Share sheet so any app can share text directly to the ESP32

### Build

- Gradle with Kotlin DSL
- Minimum API: TBD (suggest API 26 / Android 8.0 for solid BLE support)

---

## KeePass2Android plugin

KeePass2Android supports a plugin API that allows third-party apps to extend its autofill behaviour. The plugin will:

1. Receive credentials (username + password fields) from KeePass2Android via the plugin API
2. Connect to the configured ESP32-KB device
3. Type the credentials on the target machine using the BLE bridge (with optional TAB between username and password, and optional ENTER to submit)

### Technical notes

- Uses the KeePass2Android plugin API (Intent-based communication)
- Shares the BLE + encryption logic with the standalone app — extract this into a shared library module (`core/`) so both apps use the same implementation
- Plugin settings (device alias, field order, submit on enter) configurable from within KeePass2Android or the standalone app
- Must handle the case where no device is connected — prompt the user to connect via the standalone app or provide a mini connection UI inline