/**
 * ESP32-S3 BLE → USB HID Keyboard Bridge
 *
 * The device advertises over BLE as "ESP32-KB".
 * A macOS client writes UTF-8 text to the BLE characteristic; the ESP32
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
 */

#include "USB.h"
#include "USBHIDKeyboard.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "keytypes.h"

// ── BLE identifiers ──────────────────────────────────────────────────────────
// Keep these in sync with the macOS script.
#define DEVICE_NAME             "ESP32-KB"
#define SERVICE_UUID            "12340000-1234-1234-1234-123456789abc"
#define CHARACTERISTIC_UUID     "12340001-1234-1234-1234-123456789abc"
#define LAYOUT_CHAR_UUID        "12340002-1234-1234-1234-123456789abc"  // write "en-US" or "en-GB"
#define OS_CHAR_UUID            "12340003-1234-1234-1234-123456789abc"  // write "macos" or "other"

// ── Globals ───────────────────────────────────────────────────────────────────
USBHIDKeyboard Keyboard;

// Shared ring-buffer between BLE callback (ISR-like context) and loop()
#define QUEUE_SIZE 8
#define MAX_STR    512

static char     queue[QUEUE_SIZE][MAX_STR];
static uint8_t  qHead = 0, qTail = 0;
static volatile bool qFull = false;

inline bool queueEmpty() { return (qHead == qTail) && !qFull; }

// Active keyboard layout (set via BLE layout characteristic)
static volatile KeyLayout activeLayout = LAYOUT_US;
// Active target OS (set via BLE OS characteristic)
static volatile KeyOS activeOS = OS_OTHER;

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
            case '~':  return {K_NUHS,   true,  false};
            case 0x00A3: return {K_3,    true,  false};  // £ = Shift+3
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
    if (shift) Keyboard.press(KEY_LEFT_SHIFT);
    if (alt)   Keyboard.press(KEY_LEFT_ALT);
    Keyboard.pressRaw(hidKey);
    delay(5);
    Keyboard.releaseAll();
    delay(5);
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

// ── BLE OS characteristic callback ──────────────────────────────────────────
class OSCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if (val.length() == 0) return;
        activeOS = (val == "macos") ? OS_MACOS : OS_OTHER;
    }
};

// ── BLE layout characteristic callback ───────────────────────────────────────
class LayoutCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if (val.length() == 0) return;
        if (val == "en-GB") {
            activeLayout = LAYOUT_GB;
        } else {
            activeLayout = LAYOUT_US;  // default / fallback
        }
    }
};

// ── BLE text characteristic callback ─────────────────────────────────────────
class CharacteristicCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if (val.length() == 0) return;

        size_t len = min((size_t)val.length(), (size_t)(MAX_STR - 1));

        noInterrupts();
        if (!qFull) {
            memcpy(queue[qTail], val.c_str(), len);
            queue[qTail][len] = '\0';
            qTail = (qTail + 1) % QUEUE_SIZE;
            if (qTail == qHead) qFull = true;
        }
        // if qFull, drop silently — consumer is too slow
        interrupts();
    }
};

// ── BLE server callbacks (optional: track connection state) ───────────────────
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *pServer) override {
        // Optionally blink LED or log
    }
    void onDisconnect(BLEServer *pServer) override {
        // Restart advertising so the host can reconnect
        BLEDevice::startAdvertising();
    }
};

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    // --- USB HID keyboard ---
    Keyboard.begin();
    USB.begin();

    // --- BLE GATT server ---
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(517);  // request large MTU for longer strings

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic *pChar = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_WRITE |      // write with response
        BLECharacteristic::PROPERTY_WRITE_NR     // write without response (faster)
    );
    pChar->setCallbacks(new CharacteristicCallbacks());

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
    while (true) {
        noInterrupts();
        bool empty = queueEmpty();
        char buf[MAX_STR];
        if (!empty) {
            memcpy(buf, queue[qHead], MAX_STR);
            qHead = (qHead + 1) % QUEUE_SIZE;
            qFull = false;
        }
        interrupts();

        if (empty) break;
        typeString(buf);
        delay(5);  // small gap between back-to-back sends
    }
    delay(10);
}
