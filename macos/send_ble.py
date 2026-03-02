#!/usr/bin/env python3
"""
send_ble.py — macOS BLE client for the ESP32-S3 keyboard bridge.

Usage:
    # Interactive mode (defaults to en-US layout, generic OS):
    python send_ble.py

    # UK layout on macOS:
    python send_ble.py --layout en-GB --os macos

    # One-shot:
    python send_ble.py --layout en-GB --os macos 'Hello, World *###£$@'

    Supported layouts : en-US  en-GB
    Supported OS      : other (Windows/Linux/Android)  macos

Requires:
    pip install bleak
"""

import argparse
import asyncio
import sys
from bleak import BleakClient, BleakScanner

# ── Must match firmware constants ───────────────────────────────────────────────
DEVICE_NAME         = "ESP32-KB"
SERVICE_UUID        = "12340000-1234-1234-1234-123456789abc"
CHARACTERISTIC_UUID = "12340001-1234-1234-1234-123456789abc"
LAYOUT_CHAR_UUID    = "12340002-1234-1234-1234-123456789abc"
OS_CHAR_UUID        = "12340003-1234-1234-1234-123456789abc"

SUPPORTED_LAYOUTS = ("en-US", "en-GB")
SUPPORTED_OS      = ("other", "macos")

SCAN_TIMEOUT = 15.0   # seconds to wait while scanning
CHUNK_SIZE   = 512    # max bytes per BLE write (must be ≤ negotiated MTU − 3)


async def find_device():
    """Scan for the ESP32 by name and return the BLE device object."""
    print(f"[BLE] Scanning for '{DEVICE_NAME}' (up to {SCAN_TIMEOUT:.0f}s)…")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=SCAN_TIMEOUT)
    if device is None:
        raise RuntimeError(
            f"Device '{DEVICE_NAME}' not found. "
            "Make sure the ESP32 is powered on and advertising."
        )
    print(f"[BLE] Found: {device.name}  ({device.address})")
    return device


async def set_layout(client: BleakClient, layout: str) -> None:
    """Tell the ESP32 which keyboard layout the host is using."""
    await client.write_gatt_char(LAYOUT_CHAR_UUID, layout.encode(), response=True)
    print(f"[BLE] Layout set to: {layout}")


async def set_os(client: BleakClient, os: str) -> None:
    """Tell the ESP32 which OS the target machine is running."""
    await client.write_gatt_char(OS_CHAR_UUID, os.encode(), response=True)
    print(f"[BLE] OS set to: {os}")


async def send_string(client: BleakClient, text: str) -> None:
    """Send *text* to the ESP32, splitting into chunks if necessary."""
    raw = text.encode("utf-8")
    for offset in range(0, len(raw), CHUNK_SIZE):
        chunk = raw[offset : offset + CHUNK_SIZE]
        await client.write_gatt_char(CHARACTERISTIC_UUID, chunk, response=True)
    print(f"[BLE] Sent ({len(raw)} bytes): {text!r}")


async def interactive_mode(client: BleakClient, enter: bool = False) -> None:
    """Keep the connection open and read strings from stdin."""
    suffix = "" if not enter else "  (+Enter)"
    print(f"[BLE] Connected. Enter text to type{suffix}, or Ctrl-C / blank line to quit.\n")
    loop = asyncio.get_running_loop()
    while True:
        try:
            # Run blocking input() in a thread so the event loop stays alive.
            text = await loop.run_in_executor(None, input, "> ")
        except (EOFError, KeyboardInterrupt):
            break
        if text == "":
            break
        if enter:
            text += "\n"
        await send_string(client, text)


async def main(one_shot_text: str | None = None, layout: str = "en-US", os: str = "other", enter: bool = False) -> None:
    device = await find_device()

    async with BleakClient(device) as client:
        print(f"[BLE] Connected  (MTU: {client.mtu_size} bytes)")

        await set_layout(client, layout)
        await set_os(client, os)

        if one_shot_text is not None:
            text = one_shot_text + ("\n" if enter else "")
            await send_string(client, text)
        else:
            await interactive_mode(client, enter=enter)

    print("[BLE] Disconnected.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Send text to ESP32 BLE keyboard bridge.")
    parser.add_argument(
        "--layout",
        default="en-US",
        choices=SUPPORTED_LAYOUTS,
        help="Keyboard layout of the target machine (default: en-US)",
    )
    parser.add_argument(
        "--os",
        default="other",
        choices=SUPPORTED_OS,
        help="OS of the target machine: 'macos' or 'other' (Win/Linux/Android) (default: other)",
    )
    parser.add_argument(
        "--enter",
        action="store_true",
        help="Press Enter after sending the text",
    )
    parser.add_argument(
        "text",
        nargs="*",
        help="Text to send (omit for interactive mode)",
    )
    args = parser.parse_args()
    one_shot = " ".join(args.text) if args.text else None
    try:
        asyncio.run(main(one_shot_text=one_shot, layout=args.layout, os=args.os, enter=args.enter))
    except RuntimeError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
