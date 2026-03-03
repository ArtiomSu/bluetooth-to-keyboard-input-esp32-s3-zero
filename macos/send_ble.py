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

Security:
    Every payload is encrypted with ECIES (X25519 + AES-256-GCM) before
    transmission.  The ESP32 generates a fresh X25519 keypair on every boot
    and publishes the public key as a BLE characteristic.  The script reads
    that key, generates its own ephemeral keypair, derives a shared AES-256
    key via ECDH + HKDF-SHA256, and encrypts with AES-256-GCM.  A random
    12-byte nonce is used per message.  The GCM authentication tag prevents
    any tampering in transit.

Requires:
    pip install bleak cryptography
"""

import argparse
import asyncio
import hashlib
import hmac
import os
import struct
import sys
from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# ── Must match firmware constants ───────────────────────────────────────────────
DEVICE_NAME         = "ESP32-KB"
SERVICE_UUID        = "12340000-1234-1234-1234-123456789abc"
CHARACTERISTIC_UUID = "12340001-1234-1234-1234-123456789abc"
LAYOUT_CHAR_UUID    = "12340002-1234-1234-1234-123456789abc"
OS_CHAR_UUID        = "12340003-1234-1234-1234-123456789abc"
PUBKEY_CHAR_UUID    = "12340004-1234-1234-1234-123456789abc"  # read: ESP32's 32-byte X25519 pubkey
PUBKEY_SIG_CHAR_UUID = "12340005-1234-1234-1234-123456789abc" # read: HMAC-SHA256(PSK, pubkey)

# ── Pre-shared key for public-key authentication ──────────────────────────────
# Must match PSK[] in firmware.ino exactly.
# Generate your own: python3 -c "import os; print(os.urandom(32).hex())"
PSK = bytes.fromhex(
    "a3f1c8e2b5d4079612fe3a8bc9e05d7f"
    "4162ab90c8e3d5f617284a9b3c06e1f2"
)

SUPPORTED_LAYOUTS = ("en-US", "en-GB")
SUPPORTED_OS      = ("other", "macos")

SCAN_TIMEOUT  = 15.0   # seconds to wait while scanning
# ECIES overhead: 32 (mac pubkey) + 12 (nonce) + 16 (GCM tag) + 4 (counter prefix) = 64
ECIES_OVERHEAD = 64
# Conservative upper bound assuming MTU=512. The real limit is checked at send
# time against the *negotiated* MTU: client.mtu_size - 3 - ECIES_OVERHEAD.
MAX_PLAINTEXT = 448   # 512 − 64

# Per-session monotonic counter. Starts at 1, increments each send.
# Firmware rejects any packet whose counter ≤ the last accepted value,
# so a captured packet cannot be replayed within the same connection.
_send_counter = 0


def _next_counter() -> int:
    global _send_counter
    _send_counter += 1
    return _send_counter


def _reset_counter() -> None:
    global _send_counter
    _send_counter = 0


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


async def get_esp32_pubkey(client: BleakClient) -> bytes:
    """Read and authenticate the ESP32's ephemeral X25519 public key.

    Verifies HMAC-SHA256(PSK, pubkey) against the signature characteristic.
    Raises RuntimeError if the signature is wrong — indicating a MITM attack.
    """
    pubkey = bytes(await client.read_gatt_char(PUBKEY_CHAR_UUID))
    sig    = bytes(await client.read_gatt_char(PUBKEY_SIG_CHAR_UUID))
    if len(pubkey) != 32:
        raise RuntimeError(f"Unexpected public key length: {len(pubkey)} bytes (expected 32)")
    if len(sig) != 32:
        raise RuntimeError(f"Unexpected signature length: {len(sig)} bytes (expected 32)")
    expected = hmac.new(PSK, pubkey, hashlib.sha256).digest()
    if not hmac.compare_digest(expected, sig):
        raise RuntimeError(
            "Public key authentication FAILED — possible MITM attack. "
            "Verify the PSK matches firmware and retry."
        )
    print(f"[Crypto] ESP32 public key verified: {pubkey.hex()}")
    return pubkey


def encrypt_payload(plaintext: bytes, esp32_pubkey_bytes: bytes) -> bytes:
    """ECIES-encrypt *plaintext* for the ESP32.

    Actual payload = [4-byte big-endian counter][plaintext], so the firmware
    can reject replayed packets.
    Packet layout: [32 mac_pubkey][12 nonce][ciphertext + 16-byte GCM tag]
    Total overhead over raw text: 64 bytes (60 ECIES + 4 counter).
    """
    counter_prefix = struct.pack(">I", _next_counter())
    inner          = counter_prefix + plaintext

    esp32_pub     = X25519PublicKey.from_public_bytes(esp32_pubkey_bytes)
    mac_priv      = X25519PrivateKey.generate()
    mac_pub_bytes = mac_priv.public_key().public_bytes_raw()   # 32 bytes
    shared_secret = mac_priv.exchange(esp32_pub)               # 32 bytes

    aes_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=None,   # RFC 5869: defaults to HashLen zero bytes
        info=b"",
    ).derive(shared_secret)

    nonce          = os.urandom(12)
    ciphertext_tag = AESGCM(aes_key).encrypt(nonce, inner, None)
    return mac_pub_bytes + nonce + ciphertext_tag


def hmac_wrap(value: bytes) -> bytes:
    """Prepend HMAC-SHA256(PSK, value) so the firmware can authenticate the write."""
    tag = hmac.new(PSK, value, hashlib.sha256).digest()  # 32 bytes
    return tag + value


async def set_layout(client: BleakClient, layout: str) -> None:
    """Tell the ESP32 which keyboard layout the host is using."""
    await client.write_gatt_char(LAYOUT_CHAR_UUID, hmac_wrap(layout.encode()), response=True)
    print(f"[BLE] Layout set to: {layout}")


async def set_os(client: BleakClient, target_os: str) -> None:
    """Tell the ESP32 which OS the target machine is running."""
    await client.write_gatt_char(OS_CHAR_UUID, hmac_wrap(target_os.encode()), response=True)
    print(f"[BLE] OS set to: {target_os}")


async def send_string(client: BleakClient, text: str, esp32_pubkey: bytes) -> None:
    """Encrypt *text* with ECIES and send it to the ESP32."""
    raw = text.encode("utf-8")
    if len(raw) > MAX_PLAINTEXT:
        raise ValueError(
            f"Text too long ({len(raw)} bytes); max {MAX_PLAINTEXT} bytes per send "
            f"(conservative limit; actual MTU is {client.mtu_size} bytes)."
        )
    packet = encrypt_payload(raw, esp32_pubkey)
    # BLE attribute writes are capped at MTU − 3 bytes. Enforce this here before
    # writing so we get a clear error rather than a silent rejection from the stack.
    max_write = client.mtu_size - 3
    if len(packet) > max_write:
        raise ValueError(
            f"Encrypted packet ({len(packet)} B) exceeds negotiated MTU write limit "
            f"({max_write} B = MTU {client.mtu_size} − 3). "
            f"Shorten the text (max ~{max_write - ECIES_OVERHEAD} bytes) or "
            "ensure a larger MTU is negotiated."
        )
    await client.write_gatt_char(CHARACTERISTIC_UUID, packet, response=True)
    print(f"[BLE] Sent ({len(raw)} B plaintext → {len(packet)} B encrypted, MTU limit {max_write} B): {text!r}")


async def interactive_mode(client: BleakClient, esp32_pubkey: bytes, enter: bool = False) -> None:
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
        await send_string(client, text, esp32_pubkey)


async def main(one_shot_text: str | None = None, layout: str = "en-US", target_os: str = "other", enter: bool = False) -> None:
    device = await find_device()
    _reset_counter()  # fresh counter for every new connection

    async with BleakClient(device) as client:
        print(f"[BLE] Connected  (MTU: {client.mtu_size} bytes)")

        # Read ESP32's ephemeral X25519 public key — used to encrypt every payload.
        # The key changes on every reboot, so forward secrecy is built in.
        esp32_pubkey = await get_esp32_pubkey(client)

        await set_layout(client, layout)
        await set_os(client, target_os)

        if one_shot_text is not None:
            text = one_shot_text + ("\n" if enter else "")
            await send_string(client, text, esp32_pubkey)
        else:
            await interactive_mode(client, esp32_pubkey, enter=enter)

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
        asyncio.run(main(one_shot_text=one_shot, layout=args.layout, target_os=args.os, enter=args.enter))
    except RuntimeError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
