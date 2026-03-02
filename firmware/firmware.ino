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

// ── BLE identifiers ──────────────────────────────────────────────────────────
// Keep these in sync with the macOS script.
#define DEVICE_NAME         "ESP32-KB"
#define SERVICE_UUID        "12340000-1234-1234-1234-123456789abc"
#define CHARACTERISTIC_UUID "12340001-1234-1234-1234-123456789abc"

// ── Globals ───────────────────────────────────────────────────────────────────
USBHIDKeyboard Keyboard;

// Shared ring-buffer between BLE callback (ISR-like context) and loop()
#define QUEUE_SIZE 8
#define MAX_STR    512

static char     queue[QUEUE_SIZE][MAX_STR];
static uint8_t  qHead = 0, qTail = 0;
static volatile bool qFull = false;

inline bool queueEmpty() { return (qHead == qTail) && !qFull; }

// ── BLE characteristic callback ───────────────────────────────────────────────
class CharacteristicCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override {
        String val = pChar->getValue();
        if (val.length() == 0) return;

        if (qFull) return;  // drop if buffer is full (shouldn't happen in practice)

        size_t len = min((size_t)val.length(), (size_t)(MAX_STR - 1));
        memcpy(queue[qTail], val.c_str(), len);
        queue[qTail][len] = '\0';
        qTail = (qTail + 1) % QUEUE_SIZE;
        if (qTail == qHead) qFull = true;
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
    while (!queueEmpty()) {
        Keyboard.print(queue[qHead]);
        qHead = (qHead + 1) % QUEUE_SIZE;
        qFull = false;
        delay(5);  // small gap between back-to-back sends
    }
    delay(10);
}
