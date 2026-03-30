# Android support

This contains two projects:

- `standalone-app` — a modern Material Design app that replicates all features of the desktop Python app with a proper mobile UI.
- ~~`keepass-2-android-plugin` — a plugin for KeePass2Android that uses the ESP32 bridge to type credentials into the target machine. This is a primary use case.~~

---

## Standalone app

### Features

- **BLE scan + connect** — scan for nearby ESP32-KB devices and connect
- **Send text** — type or paste text and send it to the connected device
- **Script editor + runner** — write and run Ducky-style scripts (full parity with `script_runner.py`)
- **Share Target** — share text from any app to the ESP32-KB via the Android Share sheet
- **Trackpad** — control mouse movement, clicks, and scroll from the app (unique feature not available in the desktop app (yet))
- **Device management** — saved device list with alias, BLE name, and PSK (equivalent to `~/.bluetooth-input/devices.json`)
- **Provisioning flow** — first-time setup and re-provisioning (set BLE name + PSK)
- **Settings** — keyboard layout (en-US / en-GB), target OS (other / macOS), hold/gap delay tuning
  > **Note:** When targeting Android, always set layout to **en-US** and OS to **other**. Also set the
  > physical keyboard layout to **English (US)** in Android itself: **Settings → General Management →
  > Keyboard list and default → Physical keyboard → select your keyboard → English (US)**. Android decodes
  > HID scan codes using its internal US keymap regardless of the keyboard language setting, so `en-GB`
  > mappings do not work and `£`/`€` cannot be typed via HID at all.

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

- Uses the KeePass2Android plugin API
- Shares the BLE + encryption logic with the standalone app — extract this into a shared library module (`core/`) so both apps use the same implementation
- Plugin settings (device alias, field order, submit on enter) configurable from within KeePass2Android or the standalone app
- Must handle the case where no device is connected — prompt the user to connect via the standalone app or provide a mini connection UI inline
  
### Known blockers (shelved see keepass branch)

The plugin was partially implemented (broadcast-receiver architecture, access-token negotiation, `openEntry` callback all confirmed working in logcat) but could not be made fully functional due to two hard constraints imposed by KeePass2Android:

1. **Hardcoded package-name whitelist.** K2A uses `PackageManager.queryIntentActivities` / `queriesPackages` to discover plugins. The list is compiled into K2A's APK and cannot be changed without forking K2A:
   ```
   keepass2android.plugin.keyboardswap2
   keepass2android.AncientIconSet
   keepass2android.plugin.qr          ← our plugin must impersonate this slot
   it.andreacioni.kp2a.plugin.keelink
   com.inputstick.apps.kp2aplugin
   com.dropbox.android
   ```
   Working around this requires setting `applicationId = "keepass2android.plugin.qr"` while keeping the Kotlin `namespace` as our own package. This is a naming collision hack — it would prevent distribution through any app store and breaks if the legitimate QR plugin is installed.

2. **Entry-action buttons never appear.** Even after the access token is negotiated and `openEntry` fires correctly, K2A does not display the action buttons added via `addEntryAction`. K2A calls `context.createPackageContext(pluginPackage, 0).resources` and resolves the icon resource ID against the plugin's own APK. Using drawable resources from within the plugin APK (`R.drawable.*`) is the correct approach, but the buttons still do not appear in the entry overflow menu — the exact cause was not isolated before the effort was abandoned.

**Conclusion:** The K2A plugin API is underdocumented and the app has hardcoded partner-package restrictions that make third-party plugin development impractical without forking K2A.