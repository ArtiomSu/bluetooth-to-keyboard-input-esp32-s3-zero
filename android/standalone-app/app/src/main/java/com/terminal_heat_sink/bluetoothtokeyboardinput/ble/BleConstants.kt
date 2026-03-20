package com.terminal_heat_sink.bluetoothtokeyboardinput.ble

import java.util.UUID

// Must match firmware constants in firmware.ino
object BleConstants {
    val SERVICE_UUID: UUID        = UUID.fromString("12340000-1234-1234-1234-123456789abc")
    val CHAR_TEXT_UUID: UUID      = UUID.fromString("12340001-1234-1234-1234-123456789abc")
    val CHAR_LAYOUT_UUID: UUID    = UUID.fromString("12340002-1234-1234-1234-123456789abc")
    val CHAR_OS_UUID: UUID        = UUID.fromString("12340003-1234-1234-1234-123456789abc")
    val CHAR_PUBKEY_UUID: UUID    = UUID.fromString("12340004-1234-1234-1234-123456789abc")
    val CHAR_PUBKEY_SIG_UUID: UUID = UUID.fromString("12340005-1234-1234-1234-123456789abc")
    val CHAR_READY_UUID: UUID     = UUID.fromString("12340006-1234-1234-1234-123456789abc")
    val CHAR_DELAY_UUID: UUID     = UUID.fromString("12340007-1234-1234-1234-123456789abc")
    val CHAR_RAW_UUID: UUID       = UUID.fromString("12340008-1234-1234-1234-123456789abc")
    val CHAR_PROVISION_UUID: UUID = UUID.fromString("12340009-1234-1234-1234-123456789abc")

    // Standard BLE descriptor for enabling notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_DEVICE_NAME = "ESP32-KB"
    const val DEFAULT_PSK_HEX =
        "a3f1c8e2b5d4079612fe3a8bc9e05d7f" +
        "4162ab90c8e3d5f617284a9b3c06e1f2"

    // ECIES overhead: 32 (ephemeral pubkey) + 12 (nonce) + 16 (GCM tag) + 4 (counter) = 64
    const val ECIES_OVERHEAD = 64
    const val TARGET_MTU = 512
    const val READY_TIMEOUT_MS = 120_000L

    // Raw event type bytes (must match firmware)
    const val EVENT_KEY_TAP: Byte    = 0x01
    const val EVENT_MOD_DOWN: Byte   = 0x02
    const val EVENT_MOD_UP: Byte     = 0x03
    const val EVENT_MOD_CLEAR: Byte  = 0x04

    // Modifier bitmasks (must match MOD_* in firmware.ino)
    const val MOD_LSHIFT = 0x01
    const val MOD_LALT   = 0x02
    const val MOD_LCTRL  = 0x04
    const val MOD_LGUI   = 0x08
    const val MOD_RALT   = 0x10

    // Special key HID usage IDs (must match K_* in firmware.ino)
    val SPECIAL_KEYS: Map<String, Int> = mapOf(
        "ENTER"       to 0x28,
        "ESCAPE"      to 0x29,
        "BACKSPACE"   to 0x2A,
        "TAB"         to 0x2B,
        "SPACE"       to 0x2C,
        "CAPSLOCK"    to 0x39,
        "F1"          to 0x3A, "F2"  to 0x3B, "F3"  to 0x3C, "F4"  to 0x3D,
        "F5"          to 0x3E, "F6"  to 0x3F, "F7"  to 0x40, "F8"  to 0x41,
        "F9"          to 0x42, "F10" to 0x43, "F11" to 0x44, "F12" to 0x45,
        "PRINTSCREEN" to 0x46,
        "SCROLLLOCK"  to 0x47,
        "INSERT"      to 0x49,
        "HOME"        to 0x4A,
        "PAGEUP"      to 0x4B,
        "DELETE"      to 0x4C,
        "DEL"         to 0x4C,
        "END"         to 0x4D,
        "PAGEDOWN"    to 0x4E,
        "RIGHTARROW"  to 0x4F,
        "LEFTARROW"   to 0x50,
        "DOWNARROW"   to 0x51,
        "UPARROW"     to 0x52,
        "NUMLOCK"     to 0x53,
        "MENU"        to 0x65,
    )

    val SUPPORTED_LAYOUTS = listOf("en-US", "en-GB")
    val SUPPORTED_OS      = listOf("other", "macos")
}
