/**
 * ESP32-S3 BLE → USB HID Keyboard Bridge
 *
 * The device advertises over BLE as "ESP32-KB".
 * A client writes UTF-8 text to the BLE characteristic; the ESP32
 * immediately types that text out through its native USB HID interface.
 *
 * Arduino IDE board settings (ESP32-S3 Zero / Waveshare ESP32-S3 Zero):
 *   Board          : "ESP32S3 Dev Module"  (or your specific board)
 *   USB Mode       : "USB-OTG (TinyUSB)"
 *   USB CDC on Boot: "Disabled"            ← important when using TinyUSB HID
 *   Flash Size     : 4MB (or as per your module)
 *   PSRAM          : Disabled (Zero has no PSRAM)
 *
 * Libraries required (all available via Arduino Library Manager or bundled):
 *   - ESP32 Arduino core ≥ 2.0.14  (provides USB.h / USBHIDKeyboard.h)
 *   - ESP32 BLE Arduino             (provides BLEDevice.h etc.)
 *   - Adafruit NeoPixel             (provides Adafruit_NeoPixel.h)
 */

#include "USB.h"
#include "USBHIDKeyboard.h"
#include "USBHIDMouse.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "keytypes.h"

// NVS — for persistent per-device identity (name + PSK)
#include <Preferences.h>

// mbedTLS — bundled with ESP32 Arduino core via ESP-IDF
#include <mbedtls/ecdh.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>

// ESP-IDF system — for esp_restart() used in fatal crypto error handling
#include <esp_system.h>

// Adafruit NeoPixel — for the onboard WS2812 RGB LED (GPIO10)
#include <Adafruit_NeoPixel.h>

// ── BLE identifiers ──────────────────────────────────────────────────────────
// Keep these in sync with the desktop script.
// DEVICE_NAME is no longer a compile-time constant — it is loaded from NVS at
// boot (see deviceName[] below).  The default below is used on first flash.
#define DEFAULT_DEVICE_NAME     "ESP32-KB"
#define SERVICE_UUID            "12340000-1234-1234-1234-123456789abc"
#define CHARACTERISTIC_UUID     "12340001-1234-1234-1234-123456789abc"
#define LAYOUT_CHAR_UUID        "12340002-1234-1234-1234-123456789abc"  // write "en-US" or "en-GB"
#define OS_CHAR_UUID            "12340003-1234-1234-1234-123456789abc"  // write "macos" or "other"
#define PUBKEY_CHAR_UUID        "12340004-1234-1234-1234-123456789abc"  // read: ESP32's 32-byte X25519 public key
#define PUBKEY_SIG_CHAR_UUID    "12340005-1234-1234-1234-123456789abc"  // read: HMAC-SHA256(PSK, pubkey)
#define READY_CHAR_UUID         "12340006-1234-1234-1234-123456789abc"  // read: 0x01=ready to receive, 0x00=busy typing
#define DELAY_CHAR_UUID         "12340007-1234-1234-1234-123456789abc"  // write: [uint16 minDelay][uint16 maxDelay] big-endian, in ms
#define RAW_CHAR_UUID           "12340008-1234-1234-1234-123456789abc"  // write: encrypted raw HID event [1-byte type][optional data]
#define PROVISION_CHAR_UUID     "12340009-1234-1234-1234-123456789abc"  // write: HMAC(curPSK, payload) + [name_len:1][name][new_psk:32]
#define MOUSE_CHAR_UUID         "1234000A-1234-1234-1234-123456789abc"  // write: raw (unencrypted) mouse event [1-byte type][payload]
#define MOUSE_EN_CHAR_UUID      "1234000B-1234-1234-1234-123456789abc"  // write: HMAC(PSK, [0x00|0x01]) to disable/enable mouse

// Encrypted packet layout: [32 mac_pubkey][12 nonce][ciphertext][16 GCM tag]
#define CRYPTO_OVERHEAD 60   // 32 + 12 + 16

// ── Onboard WS2812 LED ───────────────────────────────────────────────────────
#define LED_PIN   21   // GPIO21 — onboard WS2812
#define LED_COUNT  1

// ── Default PSK (used on first flash; overwritten by provisioning) ────────────
// Both sides must start with the same bytes.
// After first provisioning, NVS holds the real per-device PSK and this is
// never consulted again unless the firmware is reflashed.
static const uint8_t DEFAULT_PSK[32] = {
    0xa3, 0xf1, 0xc8, 0xe2, 0xb5, 0xd4, 0x07, 0x96,
    0x12, 0xfe, 0x3a, 0x8b, 0xc9, 0xe0, 0x5d, 0x7f,
    0x41, 0x62, 0xab, 0x90, 0xc8, 0xe3, 0xd5, 0xf6,
    0x17, 0x28, 0x4a, 0x9b, 0x3c, 0x06, 0xe1, 0xf2
};

// ── Runtime identity — loaded from NVS at boot ───────────────────────────────
// 32-char max name + null terminator.  Mutable: provisioning writes here then
// saves to NVS and restarts.
static uint8_t PSK[32];           // active PSK (loaded from NVS or DEFAULT_PSK)
static char    deviceName[33];    // active BLE name (loaded from NVS or DEFAULT_DEVICE_NAME)

// Preferences handle — opened read/write in loadIdentity(), read-only thereafter.
static Preferences prefs;

// ── Globals declared early (used by loadIdentity) ────────────────────────────
USBHIDKeyboard Keyboard;
USBHIDMouse    Mouse;

// Mouse feature toggle — persisted to NVS key "mouse"; toggled via MOUSE_EN_CHAR.
// When false, the MOUSE_CHAR characteristic accepts but silently drops all packets.
static volatile bool mouseEnabled = false;

/**
 * Load device identity (name + PSK) from NVS.  Falls back to compiled-in
 * defaults when the keys are absent (i.e. first flash).
 */
static void loadIdentity() {
    prefs.begin("bt-kb", /*readOnly=*/false);

    // PSK
    if (prefs.isKey("psk")) {
        prefs.getBytes("psk", PSK, 32);
    } else {
        memcpy(PSK, DEFAULT_PSK, 32);
    }

    // Device name
    if (prefs.isKey("name")) {
        String n = prefs.getString("name", DEFAULT_DEVICE_NAME);
        strncpy(deviceName, n.c_str(), 32);
        deviceName[32] = '\0';
    } else {
        strncpy(deviceName, DEFAULT_DEVICE_NAME, 32);
        deviceName[32] = '\0';
    }

    // Mouse enable
    mouseEnabled = prefs.getBool("mouse", false);

    prefs.end();
}

// ── Globals ───────────────────────────────────────────────────────────────────
// (Keyboard, Mouse, and mouseEnabled are declared above loadIdentity().)

// ── Onboard WS2812 RGB LED ───────────────────────────────────────────────────
Adafruit_NeoPixel led(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);

// LED state — idle=red blink, connected=blue blink, typing=green blink.
// uint8_t underlying type gives single-instruction reads/writes on ESP32.
enum LedState : uint8_t { LED_IDLE = 0, LED_CONNECTED, LED_TYPING };
static volatile LedState ledState     = LED_IDLE;
static volatile bool     bleConnected = false;

// Shared ring-buffer between BLE callback (core 0) and loop() (core 1).
// portMUX_TYPE + taskENTER/EXIT_CRITICAL coordinate across both cores;
// noInterrupts()/interrupts() only mask the *calling* core and are insufficient.
#define QUEUE_SIZE 8
#define MAX_STR    512

static portMUX_TYPE queueMux = portMUX_INITIALIZER_UNLOCKED;

// Tagged queue entries allow text, special keys, and modifier events to share
// a single ordered queue so relative ordering is always preserved.
enum QEntryType : uint8_t {
    QENTRY_STRING    = 0,  // typeString(data)
    QENTRY_KEY_TAP   = 1,  // pressRaw(data[0]) with active modifiers, then releaseAll + reapply
    QENTRY_MOD_DOWN  = 2,  // activeModifiers |= data[0]; applyModifiers()
    QENTRY_MOD_UP    = 3,  // activeModifiers &= ~data[0]; releaseAll; applyModifiers()
    QENTRY_MOD_CLEAR = 4,  // activeModifiers = 0; releaseAll()
};
struct QEntry { QEntryType type; char data[MAX_STR]; };

static QEntry   queue[QUEUE_SIZE];
static uint8_t  qHead = 0, qTail = 0;
static volatile bool qFull = false;

inline bool queueEmpty() { return (qHead == qTail) && !qFull; }

// Active keyboard layout (set via BLE layout characteristic)
static volatile KeyLayout activeLayout = LAYOUT_US;
// Active target OS (set via BLE OS characteristic)
static volatile KeyOS activeOS = OS_OTHER;

// ── App-layer crypto state (X25519 + AES-256-GCM) ────────────────────────────
static mbedtls_ecp_group        ecGroup;   // Curve25519 group parameters
static mbedtls_mpi              ecPrivKey; // our private scalar
static mbedtls_ecp_point        ecPubKeyPt;// our public point
static mbedtls_ctr_drbg_context ctr_drbg;
static mbedtls_entropy_context  ecEntropy;
static uint8_t                  ecPubKeyBytes[32];  // our X25519 public key (32 raw bytes)
static uint8_t                  ecPubKeySig[32];    // HMAC-SHA256(PSK, ecPubKeyBytes)

// Global pointers to characteristics that are updated at runtime.
// Set once in setup() after the characteristics are created.
static BLECharacteristic *gPubKeyChar    = nullptr;
static BLECharacteristic *gPubKeySigChar = nullptr;
static BLECharacteristic *gReadyChar     = nullptr;  // 0x01=ready, 0x00=busy typing

// Replay-attack counter: reset to 0 on every new connection.
// Each decrypted packet must carry a strictly increasing value.
static volatile uint32_t lastSeenCounter = 0;

// Chunk completion counter: incremented in loop() after every chunk is fully typed.
// Sent as the notify value so the client can distinguish "this chunk's done"
// from a stale notification belonging to the previous chunk.
static volatile uint8_t gCompletions = 0;

// Per-keystroke delay ranges (milliseconds).  Two independent ranges:
//   Hold  — how long the key is physically held down before release.
//   Gap   — the pause after the key is released before the next event.
// When min == max the value is used directly; when they differ, each keystroke
// picks a random value in [min, max] using the ESP32 hardware RNG.
// Default 20 ms for both maintains the previous fixed behaviour.
static volatile uint16_t minHoldDelay      = 20;
static volatile uint16_t maxHoldDelay      = 20;
static volatile uint16_t minKeystrokeDelay = 20;
static volatile uint16_t maxKeystrokeDelay = 20;

// Return a hold delay in ms, randomised within [minHoldDelay, maxHoldDelay].
static uint16_t keystrokeHoldDelay() {
    uint16_t lo = minHoldDelay;
    uint16_t hi = maxHoldDelay;
    if (lo >= hi) return lo;
    return lo + (uint16_t)(esp_random() % ((uint32_t)(hi - lo) + 1));
}

// Return a gap delay in ms, randomised within [minKeystrokeDelay, maxKeystrokeDelay].
static uint16_t keystrokeDelay() {
    uint16_t lo = minKeystrokeDelay;
    uint16_t hi = maxKeystrokeDelay;
    if (lo >= hi) return lo;
    return lo + (uint16_t)(esp_random() % ((uint32_t)(hi - lo) + 1));
}

// ── Modifier state ──────────────────────────────────────────────────────────────────
// Bitmask of script-held modifier keys. Written only by loop() (core 1) so no
// volatile needed. Re-pressed by applyModifiers() after every key release so
// STRING typing and KEY_TAP both see the correct modifier state.
#define MOD_LSHIFT  0x01   // Left Shift
#define MOD_LALT    0x02   // Left Alt
#define MOD_LCTRL   0x04   // Left Ctrl
#define MOD_LGUI    0x08   // Left GUI (Win / Cmd)
#define MOD_RALT    0x10   // Right Alt (AltGr)
static uint8_t activeModifiers = 0;

static void applyModifiers() {
    if (activeModifiers & MOD_LSHIFT) Keyboard.press(KEY_LEFT_SHIFT);
    if (activeModifiers & MOD_LALT)   Keyboard.press(KEY_LEFT_ALT);
    if (activeModifiers & MOD_LCTRL)  Keyboard.press(KEY_LEFT_CTRL);
    if (activeModifiers & MOD_LGUI)   Keyboard.press(KEY_LEFT_GUI);
    if (activeModifiers & MOD_RALT)   Keyboard.press(KEY_RIGHT_ALT);
}

// ── Layout / keycode tables ───────────────────────────────────────────────────
// Raw HID usage codes (physical key positions, layout-independent)
#define K_SPACE   0x2C
#define K_1       0x1E
#define K_2       0x1F
#define K_3       0x20
#define K_4       0x21
#define K_5       0x22
#define K_6       0x23
#define K_7       0x24
#define K_8       0x25
#define K_9       0x26
#define K_0       0x27
#define K_MINUS   0x2D
#define K_EQUAL   0x2E
#define K_LBRACK  0x2F
#define K_RBRACK  0x30
#define K_BSLASH  0x31
#define K_NUHS    0x32   // ISO extra key: # (unshifted) on UK layout
#define K_SEMI    0x33
#define K_QUOTE   0x34
#define K_GRAVE   0x35
#define K_COMMA   0x36
#define K_DOT     0x37
#define K_SLASH   0x38
#define K_NUBS    0x64   // ISO extra key: \ (unshifted) on UK layout
// Special / navigation keys — HID usage IDs, layout-independent
#define K_ENTER       0x28
#define K_ESCAPE      0x29
#define K_BACKSPACE   0x2A
#define K_TAB         0x2B
#define K_CAPSLOCK    0x39
#define K_F1          0x3A
#define K_F2          0x3B
#define K_F3          0x3C
#define K_F4          0x3D
#define K_F5          0x3E
#define K_F6          0x3F
#define K_F7          0x40
#define K_F8          0x41
#define K_F9          0x42
#define K_F10         0x43
#define K_F11         0x44
#define K_F12         0x45
#define K_PRINTSCREEN 0x46
#define K_SCROLLLOCK  0x47
#define K_INSERT      0x49
#define K_HOME        0x4A
#define K_PAGEUP      0x4B
#define K_DELETE      0x4C
#define K_END         0x4D
#define K_PAGEDOWN    0x4E
#define K_RIGHT       0x4F
#define K_LEFT        0x50
#define K_DOWN        0x51
#define K_UP          0x52
#define K_NUMLOCK     0x53
#define K_MENU        0x65
// Letters a-z = 0x04-0x1D

/**
 * Map a Unicode codepoint → KeyEntry for the given layout + OS.
 * Covers ASCII printable range (0x20-0x7E) + £ (U+00A3) for en-GB.
 * {hidKey, shift, alt}  — alt = Option on macOS, AltGr on others.
 */
static KeyEntry lookupKey(uint32_t cp, KeyLayout layout, KeyOS os) {
    // Letters are the same across all layouts
    if (cp >= 'a' && cp <= 'z') return {(uint8_t)(0x04 + (cp - 'a')), false, false};
    if (cp >= 'A' && cp <= 'Z') return {(uint8_t)(0x04 + (cp - 'A')), true,  false};
    // Digits
    if (cp >= '1' && cp <= '9') return {(uint8_t)(0x1E + (cp - '1')), false, false};
    if (cp == '0') return {K_0, false, false};

    // ── Layout-specific symbols ───────────────────────────────────────────
    if (layout == LAYOUT_GB) {
        // macOS "British" layout: Option+3 = #, Shift+3 = £, Shift+2 = @
        // Other OSes (Windows/Linux/Android) use Shift+3 = # via K_NUHS.
        switch (cp) {
            case ' ':  return {K_SPACE,  false, false};
            case '!':  return {K_1,      true,  false};
            case '"':  return {K_QUOTE,  true,  false};
            case '#':  return (os == OS_MACOS)
                           ? KeyEntry{K_3,     false, true }   // macOS: Option+3
                           : KeyEntry{K_NUHS,  false, false};  // other: HID 0x32
            case '$':  return {K_4,      true,  false};
            case '%':  return {K_5,      true,  false};
            case '&':  return {K_7,      true,  false};
            case '\''  : return {K_QUOTE, false, false};
            case '(':  return {K_9,      true,  false};
            case ')':  return {K_0,      true,  false};
            case '*':  return {K_8,      true,  false};
            case '+':  return {K_EQUAL,  true,  false};
            case ',':  return {K_COMMA,  false, false};
            case '-':  return {K_MINUS,  false, false};
            case '.':  return {K_DOT,    false, false};
            case '/':  return {K_SLASH,  false, false};
            case ':':  return {K_SEMI,   true,  false};
            case ';':  return {K_SEMI,   false, false};
            case '<':  return {K_COMMA,  true,  false};
            case '=':  return {K_EQUAL,  false, false};
            case '>':  return {K_DOT,    true,  false};
            case '?':  return {K_SLASH,  true,  false};
            case '@':  return {K_2,      true,  false};
            case '[':  return {K_LBRACK, false, false};
            case '\\'  : return {K_BSLASH, false, false};
            case ']':  return {K_RBRACK, false, false};
            case '^':  return {K_6,      true,  false};
            case '_':  return {K_MINUS,  true,  false};
            case '`':  return {K_GRAVE,  false, false};
            case '{':  return {K_LBRACK, true,  false};
            case '|':  return {K_BSLASH, true,  false};
            case '}':  return {K_RBRACK, true,  false};
            case '~':  return (os == OS_MACOS)
                           ? KeyEntry{K_GRAVE, true,  false}  // macOS British: Shift+` = ~
                           : KeyEntry{K_NUHS,  true,  false}; // other: Shift+ISO key 0x32 = ~
            case 0x00A3: return {K_3,    true,  false};  // £ = Shift+3
            case 0x20AC: return {K_2,    false, true };  // € = Alt/Option+2
            case '\n': return {0x28, false, false};
            case '\t': return {0x2B, false, false};
            default:   return {0, false, false};
        }
    }

    // ── en-US (default) ───────────────────────────────────────────────────
    switch (cp) {
        case ' ':  return {K_SPACE,  false, false};
        case '!':  return {K_1,      true,  false};
        case '"':  return {K_QUOTE,  true,  false};
        case '#':  return {K_3,      true,  false};
        case '$':  return {K_4,      true,  false};
        case '%':  return {K_5,      true,  false};
        case '&':  return {K_7,      true,  false};
        case '\''  : return {K_QUOTE, false, false};
        case '(':  return {K_9,      true,  false};
        case ')':  return {K_0,      true,  false};
        case '*':  return {K_8,      true,  false};
        case '+':  return {K_EQUAL,  true,  false};
        case ',':  return {K_COMMA,  false, false};
        case '-':  return {K_MINUS,  false, false};
        case '.':  return {K_DOT,    false, false};
        case '/':  return {K_SLASH,  false, false};
        case ':':  return {K_SEMI,   true,  false};
        case ';':  return {K_SEMI,   false, false};
        case '<':  return {K_COMMA,  true,  false};
        case '=':  return {K_EQUAL,  false, false};
        case '>':  return {K_DOT,    true,  false};
        case '?':  return {K_SLASH,  true,  false};
        case '@':  return {K_2,      true,  false};
        case '[':  return {K_LBRACK, false, false};
        case '\\'  : return {K_BSLASH, false, false};
        case ']':  return {K_RBRACK, false, false};
        case '^':  return {K_6,      true,  false};
        case '_':  return {K_MINUS,  true,  false};
        case '`':  return {K_GRAVE,  false, false};
        case '{':  return {K_LBRACK, true,  false};
        case '|':  return {K_BSLASH, true,  false};
        case '}':  return {K_RBRACK, true,  false};
        case '~':  return {K_GRAVE,  true,  false};
        case '\n': return {0x28, false, false};
        case '\t': return {0x2B, false, false};
        default:   return {0, false, false};
    }
}

/**
 * Press and release a single key using its raw HID keycode.
 * Uses pressRaw() which directly injects the HID usage code into the
 * report, bypassing any ASCII/keycode lookup tables in the library.
 */
static void pressRawKey(uint8_t hidKey, bool shift, bool alt) {
    applyModifiers();          // re-press any script-held modifiers first
    if (shift) Keyboard.press(KEY_LEFT_SHIFT);
    if (alt)   Keyboard.press(KEY_LEFT_ALT);
    Keyboard.pressRaw(hidKey);
    delay(keystrokeHoldDelay());  // hold duration
    Keyboard.releaseAll();
    delay(keystrokeDelay());      // gap before next event
    applyModifiers();          // restore script-held modifiers after release
}

/**
 * Decode UTF-8 input and type each character using the active layout table.
 */
static void typeString(const char *str) {
    const uint8_t *p = (const uint8_t *)str;
    KeyLayout layout = activeLayout;
    KeyOS     os     = activeOS;

    while (*p) {
        uint32_t cp;

        // UTF-8 decode
        if (*p < 0x80) {
            cp = *p++;
        } else if ((*p & 0xE0) == 0xC0 && p[1]) {
            cp  = (*p++ & 0x1F) << 6;
            cp |= (*p++ & 0x3F);
        } else if ((*p & 0xF0) == 0xE0 && p[1] && p[2]) {
            cp  = (*p++ & 0x0F) << 12;
            cp |= (*p++ & 0x3F) << 6;
            cp |= (*p++ & 0x3F);
        } else {
            p++;  // skip invalid byte
            continue;
        }

        KeyEntry k = lookupKey(cp, layout, os);
        if (k.hidKey != 0) {
            pressRawKey(k.hidKey, k.shift, k.alt);
        }
        // unmapped codepoints are silently skipped
    }
}

// ── Crypto helpers ───────────────────────────────────────────────────────────

/**
 * Generate a fresh X25519 keypair and recompute the HMAC signature.
 * Called once at boot and again on every new BLE connection, so each
 * session uses a unique private key (forward secrecy per connection).
 */
static void regenKeypair() {
    // Generate a fresh X25519 keypair. Any failure here is fatal: the device
    // cannot operate safely without a valid keypair, so restart immediately.
    int ret = mbedtls_ecp_gen_keypair(&ecGroup, &ecPrivKey, &ecPubKeyPt,
                                       mbedtls_ctr_drbg_random, &ctr_drbg);
    if (ret != 0) {
        // RNG failure or curve not initialised — cannot produce a safe key.
        esp_restart();
    }

    size_t pubOutLen = 0;
    ret = mbedtls_ecp_point_write_binary(&ecGroup, &ecPubKeyPt, MBEDTLS_ECP_PF_COMPRESSED,
                                          &pubOutLen, ecPubKeyBytes, sizeof(ecPubKeyBytes));
    if (ret != 0 || pubOutLen != 32) {
        // Curve25519 u-coordinate must be exactly 32 bytes.
        esp_restart();
    }

    // ecPubKeySig = HMAC-SHA256(PSK, ecPubKeyBytes)
    const mbedtls_md_info_t *mdInfo = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    mbedtls_md_context_t mdCtx;
    mbedtls_md_init(&mdCtx);
    ret = mbedtls_md_setup(&mdCtx, mdInfo, 1 /* HMAC */);
    if (ret != 0) { mbedtls_md_free(&mdCtx); esp_restart(); }
    ret = mbedtls_md_hmac_starts(&mdCtx, PSK, sizeof(PSK));
    if (ret != 0) { mbedtls_md_free(&mdCtx); esp_restart(); }
    ret = mbedtls_md_hmac_update(&mdCtx, ecPubKeyBytes, 32);
    if (ret != 0) { mbedtls_md_free(&mdCtx); esp_restart(); }
    ret = mbedtls_md_hmac_finish(&mdCtx, ecPubKeySig);
    mbedtls_md_free(&mdCtx);
    if (ret != 0) { esp_restart(); }

    // Update BLE characteristics if they have been created already
    if (gPubKeyChar)    gPubKeyChar->setValue(ecPubKeyBytes, 32);
    if (gPubKeySigChar) gPubKeySigChar->setValue(ecPubKeySig, 32);
}

/**
 * Verify a PSK-HMAC-authenticated write.
 * Expected write format: [32-byte HMAC-SHA256(PSK, value)][value]
 * Returns a pointer to the value (data+32) on success, nullptr on failure.
 * *valueLen is set to the value length on success.
 */
static const uint8_t *verifyPskHmac(const uint8_t *data, size_t dataLen, size_t *valueLen) {
    if (dataLen <= 32) return nullptr;
    const uint8_t *mac   = data;
    const uint8_t *value = data + 32;
    *valueLen = dataLen - 32;

    uint8_t expected[32];
    const mbedtls_md_info_t *mdInfo = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mdInfo, 1 /* HMAC */);
    mbedtls_md_hmac_starts(&ctx, PSK, sizeof(PSK));
    mbedtls_md_hmac_update(&ctx, value, *valueLen);
    mbedtls_md_hmac_finish(&ctx, expected);
    mbedtls_md_free(&ctx);

    // Constant-time comparison to prevent timing attacks
    uint8_t diff = 0;
    for (int i = 0; i < 32; i++) diff |= (mac[i] ^ expected[i]);
    return (diff == 0) ? value : nullptr;
}

/**
 * Minimal HKDF-SHA256 (RFC 5869) with no salt and no info, output 32 bytes.
 * Compatible with Python's cryptography.hazmat HKDF(SHA256, length=32, salt=None, info=b"").
 */
static void hkdfSha256(const uint8_t *ikm, size_t ikm_len, uint8_t out[32]) {
    const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, info, 1 /* HMAC */);

    // Extract: PRK = HMAC-SHA256(salt=0x00*32, IKM)
    uint8_t salt[32] = {};
    uint8_t prk[32];
    mbedtls_md_hmac_starts(&ctx, salt, sizeof(salt));
    mbedtls_md_hmac_update(&ctx, ikm, ikm_len);
    mbedtls_md_hmac_finish(&ctx, prk);

    // Expand: OKM = HMAC-SHA256(PRK, 0x01)  [single block, 32 bytes]
    uint8_t ctr = 0x01;
    mbedtls_md_hmac_starts(&ctx, prk, 32);
    mbedtls_md_hmac_update(&ctx, &ctr, 1);
    mbedtls_md_hmac_finish(&ctx, out);
    memset(prk, 0, sizeof(prk));  // PRK is derived secret — zero after expansion

    mbedtls_md_free(&ctx);
}

/**
 * Decrypt an ECIES packet written to the text characteristic.
 * Packet layout: [32 mac_pubkey][12 nonce][ciphertext][16 GCM tag]
 * Plaintext layout: [4-byte big-endian counter][UTF-8 text]
 * Returns true and fills out/outLen (text only, counter stripped) on success.
 * Returns false on bad length, wrong GCM tag, or replay (counter ≤ last seen).
 */
static bool decryptPayload(const uint8_t *pkt, size_t pktLen, char *out, size_t &outLen) {
    if (pktLen < (size_t)CRYPTO_OVERHEAD + 4) return false;  // need at least counter
    size_t cipherLen = pktLen - CRYPTO_OVERHEAD;
    // cipherLen bytes of plaintext + 1 null byte must fit in MAX_STR.
    // Using > MAX_STR - 1 makes the intent explicit: reject anything that won't fit.
    if (cipherLen > MAX_STR - 1) return false;

    const uint8_t *macPubBytes = pkt;
    const uint8_t *nonce       = pkt + 32;
    const uint8_t *ciphertext  = pkt + 44;
    const uint8_t *tag         = pkt + pktLen - 16;

    // 1. Parse sender's ephemeral public key from raw 32-byte X coordinate
    mbedtls_ecp_point peerPub;
    mbedtls_mpi       sharedMpi;
    mbedtls_ecp_point_init(&peerPub);
    mbedtls_mpi_init(&sharedMpi);
    // Curve25519 point is encoded as just the 32-byte u-coordinate
    int ret = mbedtls_ecp_point_read_binary(&ecGroup, &peerPub, macPubBytes, 32);
    if (ret != 0) { mbedtls_ecp_point_free(&peerPub); mbedtls_mpi_free(&sharedMpi); return false; }

    // 2. ECDH: shared secret = ecPrivKey * peerPub  (result is the u-coordinate)
    ret = mbedtls_ecdh_compute_shared(&ecGroup, &sharedMpi, &peerPub, &ecPrivKey,
                                       mbedtls_ctr_drbg_random, &ctr_drbg);
    mbedtls_ecp_point_free(&peerPub);
    if (ret != 0) { mbedtls_mpi_free(&sharedMpi); return false; }

    uint8_t sharedBytes[32] = {};
    mbedtls_mpi_write_binary(&sharedMpi, sharedBytes, 32);
    mbedtls_mpi_free(&sharedMpi);
    // mbedTLS writes MPIs in big-endian; X25519 (RFC 7748) is little-endian.
    // Reverse so the shared secret matches Python's exchange() output.
    for (int i = 0; i < 16; i++) {
        uint8_t tmp = sharedBytes[i];
        sharedBytes[i] = sharedBytes[31 - i];
        sharedBytes[31 - i] = tmp;
    }

    // 3. HKDF-SHA256 → 32-byte AES key
    uint8_t aesKey[32];
    hkdfSha256(sharedBytes, 32, aesKey);
    memset(sharedBytes, 0, sizeof(sharedBytes));  // ECDH shared secret no longer needed

    // 4. AES-256-GCM decrypt + verify authentication tag
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, aesKey, 256);
    ret = mbedtls_gcm_auth_decrypt(&gcm, cipherLen,
                                    nonce, 12,
                                    nullptr, 0,
                                    tag, 16,
                                    ciphertext, (uint8_t *)out);
    mbedtls_gcm_free(&gcm);
    memset(aesKey, 0, sizeof(aesKey));  // AES key no longer needed — zero before any return
    if (ret != 0) return false;

    out[cipherLen] = '\0';

    // 5. Replay check: extract big-endian counter from first 4 bytes of plaintext
    if (cipherLen < 4) return false;
    uint32_t counter = ((uint32_t)(uint8_t)out[0] << 24)
                     | ((uint32_t)(uint8_t)out[1] << 16)
                     | ((uint32_t)(uint8_t)out[2] <<  8)
                     | ((uint32_t)(uint8_t)out[3]);
    if (counter <= lastSeenCounter) return false;  // replay or out-of-order — drop
    lastSeenCounter = counter;

    // Strip the 4-byte counter prefix — caller gets only the text
    outLen = cipherLen - 4;
    memmove(out, out + 4, outLen + 1);  // includes null terminator
    return true;
}

// ── BLE provisioning characteristic callback ────────────────────────────────
// Wire format: ECIES_encrypt(counter + HMAC-SHA256(currentPSK, inner) + inner)
//   where inner = [name_len : 1 byte][name : name_len bytes (1–32)][new_psk : 32 bytes]
//              or [0x00] for factory reset
//
// Two-layer protection:
//   • ECIES  — confidentiality (new PSK never travels in the clear) + replay counter
//   • HMAC   — PSK authentication (only someone with the current PSK can provision)
class ProvisionCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String raw = pChar->getValue();
        if ((size_t)raw.length() < (size_t)CRYPTO_OVERHEAD) return;

        // 1. ECIES decrypt — also checks the per-session replay counter.
        char plaintext[MAX_STR];
        size_t plainLen = 0;
        if (!decryptPayload((const uint8_t *)raw.c_str(), raw.length(), plaintext, plainLen)) return;

        // 2. Verify PSK-HMAC on the decrypted payload.
        size_t valueLen = 0;
        const uint8_t *payload = verifyPskHmac(
            (const uint8_t *)plaintext, plainLen, &valueLen);
        if (!payload) return;  // bad HMAC — reject silently

        // payload: [name_len:1][name:name_len][new_psk:32]
        // Special case: name_len == 0 means factory reset — clear NVS and reboot.
        if (valueLen >= 1 && payload[0] == 0) {
            prefs.begin("bt-kb", /*readOnly=*/false);
            prefs.clear();
            prefs.end();
            esp_restart();
        }
        if (valueLen < 1 + 1 + 32) return;  // too short
        uint8_t nameLen = payload[0];
        if (nameLen < 1 || nameLen > 32) return;  // invalid name length
        if (valueLen != (size_t)(1 + nameLen + 32)) return;  // length mismatch

        const uint8_t *newNameBytes = payload + 1;
        const uint8_t *newPsk       = payload + 1 + nameLen;

        // Persist to NVS
        prefs.begin("bt-kb", /*readOnly=*/false);
        char nameBuf[33] = {};
        memcpy(nameBuf, newNameBytes, nameLen);
        prefs.putString("name", nameBuf);
        prefs.putBytes("psk", newPsk, 32);
        prefs.end();

        // Restart so the new BLE name and PSK take effect cleanly.
        // The BLE stack does not support renaming a live advertisement.
        esp_restart();
    }
};

// ── BLE delay characteristic callback ───────────────────────────────────────
class DelayCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String raw = pChar->getValue();
        size_t valueLen = 0;
        const uint8_t *value = verifyPskHmac(
            (const uint8_t *)raw.c_str(), raw.length(), &valueLen);
        // Payload: [uint16 minHold BE][uint16 maxHold BE][uint16 minGap BE][uint16 maxGap BE]
        if (!value || valueLen != 8) return;  // need exactly 4 × uint16 — drop
        uint16_t minH = ((uint16_t)value[0] << 8) | value[1];  // big-endian
        uint16_t maxH = ((uint16_t)value[2] << 8) | value[3];
        uint16_t minD = ((uint16_t)value[4] << 8) | value[5];
        uint16_t maxD = ((uint16_t)value[6] << 8) | value[7];
        if (minH > maxH || minD > maxD) return;  // nonsensical ranges — drop
        minHoldDelay      = minH;
        maxHoldDelay      = maxH;
        minKeystrokeDelay = minD;
        maxKeystrokeDelay = maxD;
    }
};

// ── BLE OS characteristic callback ──────────────────────────────────────────
class OSCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String raw = pChar->getValue();
        size_t valueLen = 0;
        const uint8_t *value = verifyPskHmac(
            (const uint8_t *)raw.c_str(), raw.length(), &valueLen);
        if (!value) return;  // bad HMAC — drop
        String val((const char *)value, valueLen);
        activeOS = (val == "macos") ? OS_MACOS : OS_OTHER;
    }
};

// ── BLE layout characteristic callback ───────────────────────────────────────
class LayoutCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String raw = pChar->getValue();
        size_t valueLen = 0;
        const uint8_t *value = verifyPskHmac(
            (const uint8_t *)raw.c_str(), raw.length(), &valueLen);
        if (!value) return;  // bad HMAC — drop
        String val((const char *)value, valueLen);
        activeLayout = (val == "en-GB") ? LAYOUT_GB : LAYOUT_US;
    }
};

// ── BLE text characteristic callback ─────────────────────────────────────────
class CharacteristicCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if ((size_t)val.length() < (size_t)CRYPTO_OVERHEAD) return;

        // Decrypt ECIES packet → plaintext
        char plaintext[MAX_STR];
        size_t plainLen = 0;
        if (!decryptPayload((const uint8_t *)val.c_str(), val.length(), plaintext, plainLen)) {
            return;  // bad tag or malformed — drop silently
        }
        if (plainLen == 0) return;

        bool queued = false;
        taskENTER_CRITICAL(&queueMux);
        if (!qFull) {
            queue[qTail].type = QENTRY_STRING;
            memcpy(queue[qTail].data, plaintext, plainLen + 1);
            qTail = (qTail + 1) % QUEUE_SIZE;
            if (qTail == qHead) qFull = true;
            queued = true;
        }
        // if qFull, drop silently — consumer is too slow
        taskEXIT_CRITICAL(&queueMux);

        // Signal busy via notify so the client receives the state change immediately
        // without polling. Notify is push-based and bypasses any read-cache on the host.
        if (queued && gReadyChar) {
            static const uint8_t BUSY = 0x00;
            gReadyChar->setValue(&BUSY, 1);
            gReadyChar->notify();
        }
    }
};

// ── BLE raw HID event characteristic callback ───────────────────────────────────
class RawEventCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if ((size_t)val.length() < (size_t)CRYPTO_OVERHEAD) return;

        char plaintext[MAX_STR];
        size_t plainLen = 0;
        if (!decryptPayload((const uint8_t *)val.c_str(), val.length(), plaintext, plainLen)) return;
        if (plainLen < 1) return;  // need at least the event type byte

        QEntry entry;
        uint8_t evType = (uint8_t)plaintext[0];
        switch (evType) {
            case 0x01:  // KEY_TAP: [type][HID keycode]
                if (plainLen < 2) return;
                entry.type    = QENTRY_KEY_TAP;
                entry.data[0] = plaintext[1];
                break;
            case 0x02:  // MOD_DOWN: [type][modifier bitmask]
                if (plainLen < 2) return;
                entry.type    = QENTRY_MOD_DOWN;
                entry.data[0] = plaintext[1];
                break;
            case 0x03:  // MOD_UP: [type][modifier bitmask]
                if (plainLen < 2) return;
                entry.type    = QENTRY_MOD_UP;
                entry.data[0] = plaintext[1];
                break;
            case 0x04:  // MOD_CLEAR: [type] only
                entry.type    = QENTRY_MOD_CLEAR;
                entry.data[0] = 0;
                break;
            default: return;  // unknown event type — drop
        }

        bool queued = false;
        taskENTER_CRITICAL(&queueMux);
        if (!qFull) {
            queue[qTail] = entry;
            qTail = (qTail + 1) % QUEUE_SIZE;
            if (qTail == qHead) qFull = true;
            queued = true;
        }
        taskEXIT_CRITICAL(&queueMux);

        if (queued && gReadyChar) {
            static const uint8_t BUSY = 0x00;
            gReadyChar->setValue(&BUSY, 1);
            gReadyChar->notify();
        }
    }
};

// ── BLE mouse event characteristic callback ──────────────────────────────────
// Packets are raw (unencrypted) for low-latency operation.
// Packet format: [1-byte type][payload]
//   0x10  MOVE:         [int8 dx][int8 dy]
//   0x11  SCROLL:       [int8 vertical][int8 horizontal]
//   0x12  BUTTON_DOWN:  [uint8 buttons]   pressing, held until BUTTON_UP
//   0x13  BUTTON_UP:    [uint8 buttons]
//   0x14  BUTTON_CLICK: [uint8 buttons]   press + immediate release
// Button bitmask: MOUSE_LEFT=0x01  MOUSE_RIGHT=0x02  MOUSE_MIDDLE=0x04
//                 MOUSE_BACK=0x08  MOUSE_FORWARD=0x10
class MouseCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        if (!mouseEnabled) return;
        String val = pChar->getValue();
        const uint8_t *data = (const uint8_t *)val.c_str();
        size_t len = val.length();
        if (len < 1) return;
        switch (data[0]) {
            case 0x10:  // MOVE: [int8 dx][int8 dy]
                if (len < 3) return;
                Mouse.move((int8_t)data[1], (int8_t)data[2], 0, 0);
                break;
            case 0x11:  // SCROLL: [int8 vertical][int8 horizontal]
                if (len < 3) return;
                Mouse.move(0, 0, (int8_t)data[1], (int8_t)data[2]);
                break;
            case 0x12:  // BUTTON_DOWN: [uint8 buttons]
                if (len < 2) return;
                Mouse.press(data[1]);
                break;
            case 0x13:  // BUTTON_UP: [uint8 buttons]
                if (len < 2) return;
                Mouse.release(data[1]);
                break;
            case 0x14:  // BUTTON_CLICK: [uint8 buttons]
                if (len < 2) return;
                Mouse.click(data[1]);
                break;
            default: return;  // unknown event type — drop
        }
    }
};

// ── BLE mouse-enable characteristic callback ─────────────────────────────────
// Write HMAC(PSK, 0x00) to disable mouse; HMAC(PSK, 0x01) to enable.
// The setting is persisted to NVS immediately so it survives power cycles.
class MouseEnCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String raw = pChar->getValue();
        size_t valueLen = 0;
        const uint8_t *value = verifyPskHmac(
            (const uint8_t *)raw.c_str(), raw.length(), &valueLen);
        if (!value || valueLen != 1) return;  // bad HMAC or wrong payload size — drop
        mouseEnabled = (value[0] != 0);
        prefs.begin("bt-kb", /*readOnly=*/false);
        prefs.putBool("mouse", (bool)mouseEnabled);
        prefs.end();
        // USB descriptors are fixed at enumeration time — restart so the host
        // sees the updated descriptor (keyboard-only or keyboard+mouse).
        esp_restart();
    }
};

// ── BLE server callbacks ──────────────────────────────────────────────────────
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *pServer) override {
        // Stop advertising immediately so no second client can connect while
        // this session is active. Standard BLE normally halts advertising on
        // connection, but calling this explicitly closes the race window that
        // exists if onDisconnect() already restarted advertising for a brief
        // link-loss before the new connection completed.
        BLEDevice::stopAdvertising();

        // Drop any queued text from a previous session so stale chunks are
        // not typed on behalf of the new client.
        taskENTER_CRITICAL(&queueMux);
        qHead = qTail = 0;
        qFull = false;
        taskEXIT_CRITICAL(&queueMux);

        // Release any modifier keys held from the previous session.
        activeModifiers = 0;
        Keyboard.releaseAll();

        // Fresh keypair for every connection — renders captured traffic from
        // previous sessions undecryptable even if the old private key leaks.
        regenKeypair();
        bleConnected = true;
        ledState = LED_CONNECTED;
        // Reset replay counter so the new session starts fresh at 1
        lastSeenCounter = 0;
        // Reset completion counter and signal ready for the new session.
        gCompletions = 0;
        uint8_t c0 = 0;
        if (gReadyChar) gReadyChar->setValue(&c0, 1);
    }
    void onDisconnect(BLEServer *pServer) override {
        bleConnected = false;
        ledState = LED_IDLE;
        // Restart advertising so the host can reconnect
        BLEDevice::startAdvertising();
    }
};

// ── LED task ─────────────────────────────────────────────────────────────────
// Runs on its own FreeRTOS task so the LED keeps blinking even while
// typeString() is blocking on per-key delays.
static void ledTask(void *) {
    bool on = false;
    for (;;) {
        on = !on;
        uint32_t color = 0;
        if (on) {
            switch (ledState) {
                case LED_IDLE:      color = led.Color(255,   0,   0); break;  // red
                case LED_CONNECTED: color = led.Color(  0,   0, 255); break;  // blue
                case LED_TYPING:    color = led.Color(  0, 255,   0); break;  // green
            }
        }
        led.setPixelColor(0, color);
        led.show();
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    // --- Load per-device identity from NVS (name + PSK) ---
    loadIdentity();

    // --- WS2812 LED ---
    led.begin();
    led.setBrightness(50);
    led.clear();
    led.show();
    xTaskCreate(ledTask, "led", 2048, nullptr, 1, nullptr);

    // --- USB HID keyboard (+ mouse if enabled) ---
    // Mouse.begin() must be called before USB.begin() to include the mouse
    // HID descriptor in the USB device descriptor presented to the host.
    // When mouse is disabled the host only sees a keyboard.
    //
    // setInterval(1) sets bInterval=1ms in the HID endpoint descriptor,
    // giving the USB host a 1000 Hz polling rate instead of the default ~4ms (250 Hz).
    // This is the maximum rate for USB Full Speed interrupt endpoints.
    USB.productName(deviceName);      // shown in OS device manager
    USB.manufacturerName("ESP32-S3");
    Keyboard.begin();
    if (mouseEnabled) Mouse.begin();
    USB.begin();

    // --- BLE GATT server ---
    BLEDevice::init(deviceName);
    BLEDevice::setMTU(517);  // request large MTU for longer strings

    // --- App-layer crypto: initialise RNG + Curve25519 group ---
    mbedtls_entropy_init(&ecEntropy);
    mbedtls_ctr_drbg_init(&ctr_drbg);
    mbedtls_ctr_drbg_seed(&ctr_drbg, mbedtls_entropy_func, &ecEntropy, nullptr, 0);
    mbedtls_ecp_group_init(&ecGroup);
    mbedtls_mpi_init(&ecPrivKey);
    mbedtls_ecp_point_init(&ecPubKeyPt);
    mbedtls_ecp_group_load(&ecGroup, MBEDTLS_ECP_DP_CURVE25519);
    regenKeypair();  // generate first keypair (characteristics not yet created; ptrs are null)

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic *pChar = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_WRITE |      // write with response
        BLECharacteristic::PROPERTY_WRITE_NR     // write without response (faster)
    );
    pChar->setCallbacks(new CharacteristicCallbacks());

    // Public-key characteristic: read-only, exposes ESP32's X25519 public key (32 bytes)
    BLECharacteristic *pPubKey = pService->createCharacteristic(
        PUBKEY_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ
    );
    pPubKey->setValue(ecPubKeyBytes, 32);
    gPubKeyChar = pPubKey;  // store so onConnect() can update it

    // Public-key signature: HMAC-SHA256(PSK, pubkey) — lets clients verify the key is genuine
    BLECharacteristic *pPubKeySig = pService->createCharacteristic(
        PUBKEY_SIG_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ
    );
    pPubKeySig->setValue(ecPubKeySig, 32);
    gPubKeySigChar = pPubKeySig;  // store so onConnect() can update it

    // Layout characteristic — write "en-US" or "en-GB" to switch layout
    BLECharacteristic *pLayout = pService->createCharacteristic(
        LAYOUT_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_READ
    );
    pLayout->setCallbacks(new LayoutCallbacks());
    pLayout->setValue("en-US");  // default readable value

    // OS characteristic — write "macos" or "other"
    BLECharacteristic *pOS = pService->createCharacteristic(
        OS_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_READ
    );
    pOS->setCallbacks(new OSCallbacks());
    pOS->setValue("other");  // default readable value

    // Delay characteristic — write 4 bytes: [uint16 minMs BE][uint16 maxMs BE]
    // When min == max, every keystroke uses that fixed delay.
    // When min < max, each keystroke picks a random delay in [min, max] ms.
    BLECharacteristic *pDelay = pService->createCharacteristic(
        DELAY_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ
    );
    pDelay->setCallbacks(new DelayCallbacks());
    // [uint16 minHold BE][uint16 maxHold BE][uint16 minGap BE][uint16 maxGap BE] — 20/20/20/20 ms
    static const uint8_t INIT_DELAY[8] = {0x00, 0x14, 0x00, 0x14, 0x00, 0x14, 0x00, 0x14};
    pDelay->setValue((uint8_t *)INIT_DELAY, 8);

    // Raw HID event characteristic — write encrypted raw key / modifier events.
    // Payload (after ECIES decrypt + counter strip): [1-byte type][optional 1-byte data]
    BLECharacteristic *pRaw = pService->createCharacteristic(
        RAW_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRaw->setCallbacks(new RawEventCallbacks());

    // Provisioning characteristic — authenticated name+PSK update.
    // Write-only (no read): leaking the current name/PSK over BLE would be
    // a security regression since the channel isn't encrypted at the GATT layer.
    BLECharacteristic *pProvision = pService->createCharacteristic(
        PROVISION_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pProvision->setCallbacks(new ProvisionCallbacks());

    // Ready characteristic: firmware notifies client on state change.
    // 0x01 = ready to receive next chunk; 0x00 = busy typing.
    // PROPERTY_NOTIFY allows the firmware to push state changes instead of
    // the client polling, which avoids BLE read-cache races on macOS.
    BLECharacteristic *pReady = pService->createCharacteristic(
        READY_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pReady->addDescriptor(new BLE2902());  // required for notifications
    static const uint8_t INIT_RDY = 0x01;
    pReady->setValue(&INIT_RDY, 1);
    gReadyChar = pReady;

    // Mouse event characteristic — raw (unencrypted) write-only.
    // Accepts events regardless of mouseEnabled; the callback does the gating.
    BLECharacteristic *pMouse = pService->createCharacteristic(
        MOUSE_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    pMouse->setCallbacks(new MouseCallbacks());

    // Mouse-enable characteristic — authenticated toggle, persisted to NVS.
    // Read value reflects current state: 0x00 = disabled, 0x01 = enabled.
    BLECharacteristic *pMouseEn = pService->createCharacteristic(
        MOUSE_EN_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_READ
    );
    pMouseEn->setCallbacks(new MouseEnCallbacks());
    static uint8_t initMouseEn = mouseEnabled ? 0x01 : 0x00;
    pMouseEn->setValue(&initMouseEn, 1);

    pService->start();

    // Advertise
    BLEAdvertising *pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();
}

// ── Loop ──────────────────────────────────────────────────────────────────────
void loop() {
    // Drain the queue and type each pending string
    bool typed = false;
    while (true) {
        taskENTER_CRITICAL(&queueMux);
        bool empty = queueEmpty();
        QEntry entry;
        if (!empty) {
            entry = queue[qHead];
            qHead = (qHead + 1) % QUEUE_SIZE;
            qFull = false;
        }
        taskEXIT_CRITICAL(&queueMux);

        if (empty) break;
        typed = true;
        ledState = LED_TYPING;
        switch (entry.type) {
            case QENTRY_STRING:
                typeString(entry.data);
                break;
            case QENTRY_KEY_TAP: {
                uint8_t hidKey = (uint8_t)entry.data[0];
                // macOS requires lock keys (CapsLock, NumLock, ScrollLock) to be
                // held for ~150 ms before it registers the toggle.  Enforce a
                // minimum 200 ms hold for these keys regardless of keystrokeDelay.
                bool isLockKey = (hidKey == K_CAPSLOCK ||
                                  hidKey == K_NUMLOCK  ||
                                  hidKey == K_SCROLLLOCK);
                uint32_t holdMs = keystrokeHoldDelay();
                if (isLockKey && holdMs < 200) holdMs = 200;
                applyModifiers();
                Keyboard.pressRaw(hidKey);
                delay(holdMs);
                Keyboard.releaseAll();
                delay(keystrokeDelay());  // gap before next event
                applyModifiers();  // re-press any held modifiers after release
                break;
            }
            case QENTRY_MOD_DOWN:
                activeModifiers |= (uint8_t)entry.data[0];
                applyModifiers();
                break;
            case QENTRY_MOD_UP:
                activeModifiers &= ~(uint8_t)entry.data[0];
                Keyboard.releaseAll();
                applyModifiers();  // re-press remaining held modifiers
                break;
            case QENTRY_MOD_CLEAR:
                activeModifiers = 0;
                Keyboard.releaseAll();
                break;
        }
        delay(5);  // small gap between back-to-back events
    }
    // Queue is now empty and all typing is done — notify client it can send next chunk.
    // Increment the completion counter so the client can distinguish this notification
    // from any stale notification belonging to the previous chunk.
    if (typed) {
        ledState = bleConnected ? LED_CONNECTED : LED_IDLE;
        if (gReadyChar) {
            gCompletions++;
            uint8_t c = gCompletions;  // copy volatile before passing to setValue
            gReadyChar->setValue(&c, 1);
            gReadyChar->notify();
        }
    }
    delay(20);
}
