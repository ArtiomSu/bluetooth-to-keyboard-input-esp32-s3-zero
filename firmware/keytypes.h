#pragma once

// Keyboard layout (host machine's input source)
typedef enum { LAYOUT_US, LAYOUT_GB } KeyLayout;

// Target operating system — affects modifier keys for some characters
// (Windows / Linux / Android all behave the same; macOS differs)
typedef enum { OS_OTHER, OS_MACOS } KeyOS;

// A single key press: raw HID usage code + modifiers
struct KeyEntry {
    uint8_t hidKey;
    bool    shift;
    bool    alt;    // Option key on macOS, AltGr on others
};
