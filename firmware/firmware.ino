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

// mbedTLS — bundled with ESP32 Arduino core via ESP-IDF
#include <mbedtls/ecdh.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>

// ESP-IDF system — for esp_restart() used in fatal crypto error handling
#include <esp_system.h>

// ── BLE identifiers ──────────────────────────────────────────────────────────
// Keep these in sync with the macOS script.
#define DEVICE_NAME             "ESP32-KB"
#define SERVICE_UUID            "12340000-1234-1234-1234-123456789abc"
#define CHARACTERISTIC_UUID     "12340001-1234-1234-1234-123456789abc"
#define LAYOUT_CHAR_UUID        "12340002-1234-1234-1234-123456789abc"  // write "en-US" or "en-GB"
#define OS_CHAR_UUID            "12340003-1234-1234-1234-123456789abc"  // write "macos" or "other"
#define PUBKEY_CHAR_UUID        "12340004-1234-1234-1234-123456789abc"  // read: ESP32's 32-byte X25519 public key
#define PUBKEY_SIG_CHAR_UUID    "12340005-1234-1234-1234-123456789abc"  // read: HMAC-SHA256(PSK, pubkey)
#define READY_CHAR_UUID         "12340006-1234-1234-1234-123456789abc"  // read: 0x01=ready to receive, 0x00=busy typing

// Encrypted packet layout: [32 mac_pubkey][12 nonce][ciphertext][16 GCM tag]
#define CRYPTO_OVERHEAD 60   // 32 + 12 + 16

// ── Pre-shared key for public-key authentication ──────────────────────────────
// Both sides must have the same bytes. Generate your own and keep it secret:
//   python3 -c "import os; print(os.urandom(32).hex())"
// Then update PSK here AND PSK in send_ble.py.
static const uint8_t PSK[32] = {
    0xa3, 0xf1, 0xc8, 0xe2, 0xb5, 0xd4, 0x07, 0x96,
    0x12, 0xfe, 0x3a, 0x8b, 0xc9, 0xe0, 0x5d, 0x7f,
    0x41, 0x62, 0xab, 0x90, 0xc8, 0xe3, 0xd5, 0xf6,
    0x17, 0x28, 0x4a, 0x9b, 0x3c, 0x06, 0xe1, 0xf2
};

// ── Globals ───────────────────────────────────────────────────────────────────
USBHIDKeyboard Keyboard;

// Shared ring-buffer between BLE callback (core 0) and loop() (core 1).
// portMUX_TYPE + taskENTER/EXIT_CRITICAL coordinate across both cores;
// noInterrupts()/interrupts() only mask the *calling* core and are insufficient.
#define QUEUE_SIZE 8
#define MAX_STR    512

static portMUX_TYPE queueMux = portMUX_INITIALIZER_UNLOCKED;
static char     queue[QUEUE_SIZE][MAX_STR];
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
            memcpy(queue[qTail], plaintext, plainLen + 1);
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

// ── BLE server callbacks ──────────────────────────────────────────────────────
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *pServer) override {
        // Fresh keypair for every connection — renders captured traffic from
        // previous sessions undecryptable even if the old private key leaks.
        regenKeypair();
        // Reset replay counter so the new session starts fresh at 1
        lastSeenCounter = 0;
        // Reset completion counter and signal ready for the new session.
        gCompletions = 0;
        uint8_t c0 = 0;
        if (gReadyChar) gReadyChar->setValue(&c0, 1);
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
        char buf[MAX_STR];
        if (!empty) {
            memcpy(buf, queue[qHead], MAX_STR);
            qHead = (qHead + 1) % QUEUE_SIZE;
            qFull = false;
        }
        taskEXIT_CRITICAL(&queueMux);

        if (empty) break;
        typed = true;
        typeString(buf);
        delay(5);  // small gap between back-to-back sends
    }
    // Queue is now empty and all typing is done — notify client it can send next chunk.
    // Increment the completion counter so the client can distinguish this notification
    // from any stale notification belonging to the previous chunk.
    if (typed && gReadyChar) {
        gCompletions++;
        uint8_t c = gCompletions;  // copy volatile before passing to setValue
        gReadyChar->setValue(&c, 1);
        gReadyChar->notify();
    }
    delay(20);
}
