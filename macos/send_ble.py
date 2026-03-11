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
READY_CHAR_UUID     = "12340006-1234-1234-1234-123456789abc"  # read: 0x01=ready, 0x00=busy typing
DELAY_CHAR_UUID     = "12340007-1234-1234-1234-123456789abc"  # write: [uint16 minMs BE][uint16 maxMs BE]
RAW_CHAR_UUID       = "12340008-1234-1234-1234-123456789abc"  # write: encrypted raw HID event

# ── Pre-shared key for public-key authentication ──────────────────────────────
# Must match PSK[] in firmware.ino exactly.
# Generate your own: python3 -c "import os; print(os.urandom(32).hex())"
PSK = bytes.fromhex(
    "a3f1c8e2b5d4079612fe3a8bc9e05d7f"
    "4162ab90c8e3d5f617284a9b3c06e1f2"
)

# ── Modifier bitmasks — must match MOD_* defines in firmware.ino ───────────────────
MOD_LSHIFT = 0x01   # Left Shift
MOD_LALT   = 0x02   # Left Alt
MOD_LCTRL  = 0x04   # Left Ctrl
MOD_LGUI   = 0x08   # Left GUI (Win key / Cmd)
MOD_RALT   = 0x10   # Right Alt (AltGr)

MODIFIER_KEYS: dict[str, int] = {
    "SHIFT": MOD_LSHIFT,
    "ALT":   MOD_LALT,
    "CTRL":  MOD_LCTRL,
    "GUI":   MOD_LGUI,
    "ALTGR": MOD_RALT,
}

# ── Special key HID usage IDs — must match K_* defines in firmware.ino ───────────
SPECIAL_KEYS: dict[str, int] = {
    "ENTER":       0x28,
    "ESCAPE":      0x29,
    "BACKSPACE":   0x2A,
    "TAB":         0x2B,
    "SPACE":       0x2C,
    "CAPSLOCK":    0x39,
    "F1":          0x3A,  "F2":  0x3B,  "F3":  0x3C,  "F4":  0x3D,
    "F5":          0x3E,  "F6":  0x3F,  "F7":  0x40,  "F8":  0x41,
    "F9":          0x42,  "F10": 0x43,  "F11": 0x44,  "F12": 0x45,
    "PRINTSCREEN": 0x46,
    "SCROLLLOCK":  0x47,
    "INSERT":      0x49,
    "HOME":        0x4A,
    "PAGEUP":      0x4B,
    "DELETE":      0x4C,
    "DEL":         0x4C,
    "END":         0x4D,
    "PAGEDOWN":    0x4E,
    "RIGHTARROW":  0x4F,
    "LEFTARROW":   0x50,
    "DOWNARROW":   0x51,
    "UPARROW":     0x52,
    "NUMLOCK":     0x53,
    "MENU":        0x65,
}

SUPPORTED_LAYOUTS = ("en-US", "en-GB")
SUPPORTED_OS      = ("other", "macos")

SCAN_TIMEOUT  = 15.0   # seconds to wait while scanning
READY_TIMEOUT = 120.0  # max seconds to wait for firmware to finish typing
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


# Ready-state: firmware sends a monotonically increasing completion counter after
# each chunk is fully typed (0x01 after chunk 1, 0x02 after chunk 2, ...).
# The 0x00 value signals busy (firmware received a chunk and is typing it).
# Using a counter instead of a binary flag means stale notifications from a
# previous chunk carry the old counter value and are correctly ignored.
_last_completion: int = 0   # last completion counter received from firmware
_notify_event: asyncio.Event | None = None  # set whenever any notify arrives


def _on_ready_notify(sender: int, data: bytearray) -> None:
    """Notification handler for the READY characteristic.

    The firmware sends 0x00 when it starts typing a chunk, and an incrementing
    counter value (1, 2, 3, …) when it finishes each chunk.
    """
    global _last_completion, _notify_event
    if not data:
        return
    val = data[0]
    if val > 0:
        # Only advance — ignore any value ≤ last seen (handles duplicates/reorder).
        if val > _last_completion:
            _last_completion = val
    # Wake up anyone waiting in wait_for_ready, they'll re-check the counter.
    if _notify_event is not None:
        _notify_event.set()


async def wait_for_ready(target: int) -> None:
    """Wait until the firmware reports it has completed *target* chunks.

    The firmware sends an incrementing byte (1 after chunk 1, 2 after chunk 2,
    …) so each notification is unique.  A stale notification from the previous
    chunk carries a value < target and is silently ignored by the while loop.
    """
    global _last_completion, _notify_event
    if _notify_event is None:
        raise RuntimeError("Ready notifications not subscribed — call start_notify first")

    deadline = asyncio.get_event_loop().time() + READY_TIMEOUT
    while _last_completion < target:
        _notify_event.clear()  # re-arm before the check so we don’t miss a notify
        if _last_completion >= target:  # re-check after clear (avoid TOCTOU)
            break
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            raise RuntimeError(
                f"Timed out after {READY_TIMEOUT:.0f}s waiting for firmware to finish typing chunk {target}."
            )
        try:
            await asyncio.wait_for(_notify_event.wait(), timeout=min(remaining, 5.0))
        except asyncio.TimeoutError:
            pass  # loop back and re-check; also guards against missed notifications


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


async def set_delay(
    client: BleakClient,
    min_hold_ms: int,
    max_hold_ms: int,
    min_ms: int,
    max_ms: int,
) -> None:
    """Set the per-keystroke hold and gap delay ranges on the ESP32.

    Hold delay  — how long a key is physically held before release.
    Gap delay   — the pause after release before the next event.

    When min == max the value is used directly; when they differ, the firmware
    picks a random delay in [min, max] ms per keystroke using its hardware RNG.
    """
    # Payload: [uint16 minHold BE][uint16 maxHold BE][uint16 minGap BE][uint16 maxGap BE]
    value = struct.pack(">HHHH", min_hold_ms, max_hold_ms, min_ms, max_ms)
    await client.write_gatt_char(DELAY_CHAR_UUID, hmac_wrap(value), response=True)
    hold_str = str(min_hold_ms) if min_hold_ms == max_hold_ms else f"{min_hold_ms}\u2013{max_hold_ms}"
    gap_str  = str(min_ms)      if min_ms      == max_ms      else f"{min_ms}\u2013{max_ms}"
    print(f"[BLE] Keystroke delay: hold={hold_str} ms, gap={gap_str} ms")


async def _send_raw_event(client: BleakClient, event_type: int, payload: bytes, esp32_pubkey: bytes) -> None:
    """Encrypt and send a single raw HID event; waits for the firmware to process it."""
    global _last_completion
    target = _last_completion + 1
    if _notify_event is not None:
        _notify_event.clear()
    packet = encrypt_payload(bytes([event_type]) + payload, esp32_pubkey)
    await client.write_gatt_char(RAW_CHAR_UUID, packet, response=True)
    await wait_for_ready(target)


async def send_key_tap(client: BleakClient, hid_keycode: int, esp32_pubkey: bytes) -> None:
    """Press and release *hid_keycode* with the firmware's current active modifiers."""
    await _send_raw_event(client, 0x01, bytes([hid_keycode]), esp32_pubkey)


async def send_mod_down(client: BleakClient, mod_mask: int, esp32_pubkey: bytes) -> None:
    """Hold one or more modifier keys (OR *mod_mask* into active modifiers)."""
    await _send_raw_event(client, 0x02, bytes([mod_mask]), esp32_pubkey)


async def send_mod_up(client: BleakClient, mod_mask: int, esp32_pubkey: bytes) -> None:
    """Release one or more modifier keys (AND NOT *mod_mask* from active modifiers)."""
    await _send_raw_event(client, 0x03, bytes([mod_mask]), esp32_pubkey)


async def send_mod_clear(client: BleakClient, esp32_pubkey: bytes) -> None:
    """Release all held modifier keys."""
    await _send_raw_event(client, 0x04, b"", esp32_pubkey)


def _utf8_chunks(data: bytes, max_bytes: int):
    """Split UTF-8 encoded bytes into chunks of ≤ max_bytes without splitting codepoints."""
    start = 0
    while start < len(data):
        end = start + max_bytes
        if end >= len(data):
            yield data[start:]
            break
        # Walk back to a codepoint boundary — continuation bytes are 0x80–0xBF.
        while end > start and (data[end] & 0xC0) == 0x80:
            end -= 1
        if end == start:
            raise ValueError(f"Codepoint too large to fit in chunk size {max_bytes}")
        yield data[start:end]
        start = end


async def send_string(client: BleakClient, text: str, esp32_pubkey: bytes) -> None:
    """Encrypt *text* with ECIES and send it to the ESP32.

    Automatically splits text that exceeds the BLE MTU into multiple writes.
    Each chunk is independently encrypted. An inter-chunk delay is added so the
    firmware has time to type the previous chunk before the next one arrives
    (each keypress takes ~10 ms on the firmware side).
    """
    max_write = client.mtu_size - 3
    max_plain = min(MAX_PLAINTEXT, max_write - ECIES_OVERHEAD)
    if max_plain <= 0:
        raise ValueError(f"Negotiated MTU ({client.mtu_size}) is too small to send encrypted data.")

    raw = text.encode("utf-8")
    chunks = list(_utf8_chunks(raw, max_plain))
    total = len(chunks)

    global _last_completion
    baseline = _last_completion  # snapshot before first chunk

    for i, chunk in enumerate(chunks):
        target_completion = baseline + i + 1  # this chunk must be the (target)th completed
        if _notify_event is not None:
            _notify_event.clear()  # arm before write so we don’t miss the 0x00
        packet = encrypt_payload(chunk, esp32_pubkey)
        await client.write_gatt_char(CHARACTERISTIC_UUID, packet, response=True)
        if total > 1:
            print(f"[BLE] Chunk {i + 1}/{total} ({len(chunk)} B plaintext \u2192 {len(packet)} B encrypted)")
        # Wait for the firmware to finish typing before sending the next chunk
        # or (for the last chunk) before disconnecting.
        await wait_for_ready(target_completion)

    label = f"{len(raw)} B in {total} chunk{'s' if total > 1 else ''}"
    #print(f"[BLE] Sent {label} (MTU {client.mtu_size} B, {max_plain} B/chunk): {text!r}")
    print(f"[BLE] Sent {label} (MTU {client.mtu_size} B, {max_plain} B/chunk)")


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


async def main(one_shot_text: str | None = None, layout: str = "en-US", target_os: str = "other", enter: bool = False, min_delay: int = 20, max_delay: int = 20, min_hold_delay: int = 20, max_hold_delay: int = 20, script_path: str | None = None) -> None:
    device = await find_device()
    _reset_counter()  # fresh counter for every new connection

    async with BleakClient(device) as client:
        print(f"[BLE] Connected  (MTU: {client.mtu_size} bytes)")

        # Subscribe to ready notifications before doing anything else.
        # The firmware pushes a monotonically increasing completion counter
        # after each chunk is fully typed.
        global _last_completion, _notify_event
        _last_completion = 0
        _notify_event = asyncio.Event()
        await client.start_notify(READY_CHAR_UUID, _on_ready_notify)

        # Read ESP32's ephemeral X25519 public key — used to encrypt every payload.
        # The key changes on every reboot, so forward secrecy is built in.
        esp32_pubkey = await get_esp32_pubkey(client)

        await set_layout(client, layout)
        await set_os(client, target_os)
        await set_delay(client, min_hold_delay, max_hold_delay, min_delay, max_delay)

        if script_path is not None:
            # Lazy import avoids a circular dependency: script_runner imports
            # from this module, so we must not import it at the top level.
            # When running as __main__, register ourselves under 'send_ble' so
            # that script_runner's `from send_ble import …` gets the same module
            # object (and the same globals, including _notify_event) rather than
            # importing a fresh copy.
            import sys
            sys.modules.setdefault("send_ble", sys.modules["__main__"])
            from script_runner import run_script, ScriptError
            try:
                await run_script(
                    client, esp32_pubkey, script_path,
                    initial_min_delay=min_delay,
                    initial_max_delay=max_delay,
                    initial_min_hold_delay=min_hold_delay,
                    initial_max_hold_delay=max_hold_delay,
                )
            except ScriptError as e:
                raise RuntimeError(str(e))
        elif one_shot_text is not None:
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
        "--script",
        metavar="FILE",
        help="Path to a ducky-script file to execute (see script.md)",
    )
    parser.add_argument(
        "--enter",
        action="store_true",
        help="Press Enter after sending the text (ignored when --script is used)",
    )
    parser.add_argument(
        "--min-delay",
        type=int,
        default=20,
        metavar="MS",
        help="Minimum per-keystroke delay in ms (default: 20)",
    )
    parser.add_argument(
        "--max-delay",
        type=int,
        default=20,
        metavar="MS",
        help="Maximum gap delay in ms after each key release (default: 20). "
             "If different from --min-delay, each keystroke uses a random delay in that range.",
    )
    parser.add_argument(
        "--min-delay-hold",
        type=int,
        default=20,
        metavar="MS",
        help="Minimum hold duration in ms for each key press (default: 20).",
    )
    parser.add_argument(
        "--max-delay-hold",
        type=int,
        default=20,
        metavar="MS",
        help="Maximum hold duration in ms for each key press (default: 20). "
             "If different from --min-delay-hold, each keystroke uses a random hold duration.",
    )
    parser.add_argument(
        "text",
        nargs="*",
        help="Text to send (omit for interactive mode)",
    )
    args = parser.parse_args()
    one_shot = " ".join(args.text) if args.text else None
    if args.script and one_shot:
        parser.error("--script and positional text are mutually exclusive")
    if args.min_delay < 0 or args.max_delay < 0:
        parser.error("--min-delay and --max-delay must be non-negative")
    if args.min_delay > args.max_delay:
        parser.error("--min-delay must be ≤ --max-delay")
    if args.min_delay_hold < 0 or args.max_delay_hold < 0:
        parser.error("--min-delay-hold and --max-delay-hold must be non-negative")
    if args.min_delay_hold > args.max_delay_hold:
        parser.error("--min-delay-hold must be \u2264 --max-delay-hold")
    try:
        asyncio.run(main(
            one_shot_text=one_shot,
            layout=args.layout,
            target_os=args.os,
            enter=args.enter,
            min_delay=args.min_delay,
            max_delay=args.max_delay,
            min_hold_delay=args.min_delay_hold,
            max_hold_delay=args.max_delay_hold,
            script_path=args.script,
        ))
    except RuntimeError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
