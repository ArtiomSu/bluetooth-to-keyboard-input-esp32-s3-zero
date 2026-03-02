#!/usr/bin/env python3
"""
send_ble.py — macOS BLE client for the ESP32-S3 keyboard bridge.

Usage:
    # Interactive mode (keep connection open, type multiple strings):
    python send_ble.py

    # One-shot: send a string from the command line and exit:
    python send_ble.py "Hello, World!"

Requires:
    pip install bleak
"""

import asyncio
import sys
from bleak import BleakClient, BleakScanner

# ── Must match firmware constants ─────────────────────────────────────────────
DEVICE_NAME         = "ESP32-KB"
SERVICE_UUID        = "12340000-1234-1234-1234-123456789abc"
CHARACTERISTIC_UUID = "12340001-1234-1234-1234-123456789abc"

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


async def send_string(client: BleakClient, text: str) -> None:
    """Send *text* to the ESP32, splitting into chunks if necessary."""
    raw = text.encode("utf-8")
    for offset in range(0, len(raw), CHUNK_SIZE):
        chunk = raw[offset : offset + CHUNK_SIZE]
        await client.write_gatt_char(CHARACTERISTIC_UUID, chunk, response=True)
    print(f"[BLE] Sent ({len(raw)} bytes): {text!r}")


async def interactive_mode(client: BleakClient) -> None:
    """Keep the connection open and read strings from stdin."""
    print("[BLE] Connected. Enter text to type, or Ctrl-C / blank line to quit.\n")
    loop = asyncio.get_running_loop()
    while True:
        try:
            # Run blocking input() in a thread so the event loop stays alive.
            text = await loop.run_in_executor(None, input, "> ")
        except (EOFError, KeyboardInterrupt):
            break
        if text == "":
            break
        await send_string(client, text)


async def main(one_shot_text: str | None = None) -> None:
    device = await find_device()

    async with BleakClient(device) as client:
        print(f"[BLE] Connected  (MTU: {client.mtu_size} bytes)")

        if one_shot_text is not None:
            await send_string(client, one_shot_text)
        else:
            await interactive_mode(client)

    print("[BLE] Disconnected.")


if __name__ == "__main__":
    arg_text = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else None
    try:
        asyncio.run(main(one_shot_text=arg_text))
    except RuntimeError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
