#!/usr/bin/env python3
"""provision.py — Set the BLE name, PSK, and USB identity on an ESP32-S3 keyboard bridge.

Usage:
    # First-time setup (device still uses factory defaults):
    python provision.py --new-alias office-desk --new-name "ESP32-KB-office"

    # Re-provision an already-configured device:
    python provision.py --device office-desk --new-alias office-desk --new-name "ESP32-KB-office"

    # Set custom USB VID/PID and manufacturer name:
    python provision.py --device office-desk --new-alias office-desk \\
        --new-name "ESP32-KB-office" \\
        --usb-vid 0x1234 --usb-pid 0x5678 --usb-manufacturer "ACME Corp" --usb-serial "SN-001"

    After provisioning the ESP32 restarts automatically and advertises under
    the new name.  The config file (~/.bluetooth-input/devices.json) is
    updated with the new alias, BLE name, PSK, and USB identity.

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
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Import shared helpers from send_ble.  When run as __main__ we register this
# module under its real name so send_ble's imports resolve to the same object.
import sys as _sys
_sys.modules.setdefault("provision", _sys.modules["__main__"])

# Patch sys.path so sibling-directory imports work when run from anywhere.
_here = Path(__file__).parent
if str(_here) not in sys.path:
    sys.path.insert(0, str(_here))

from send_ble import (
    PROVISION_CHAR_UUID,
    PUBKEY_CHAR_UUID,
    PUBKEY_SIG_CHAR_UUID,
    READY_CHAR_UUID,
    SCAN_TIMEOUT,
    load_config,
    save_config,
    resolve_device,
    find_device,
    get_esp32_pubkey,
    encrypt_payload,
    hmac_wrap,
    _reset_counter,
    _last_completion,
    _notify_event,
    _on_ready_notify,
    PSK as _PSK_sentinel,          # imported for side-effect; we set the global below
    _DEFAULT_DEVICE_NAME,
    _DEFAULT_PSK_HEX,
)
import send_ble as _send_ble_module


# Default USB identity — mirrors firmware defaults.
_DEFAULT_USB_VID = 0x303A
_DEFAULT_USB_PID = 0x1001
_DEFAULT_USB_MANUFACTURER = "ArtiomSu"
_DEFAULT_USB_SERIAL       = ""


async def provision(
    current_ble_name: str,
    current_psk: bytes,
    new_name: str,
    new_psk: bytes,
    usb_vid: int = _DEFAULT_USB_VID,
    usb_pid: int = _DEFAULT_USB_PID,
    usb_manufacturer: str = _DEFAULT_USB_MANUFACTURER,
    usb_serial: str = _DEFAULT_USB_SERIAL,
) -> None:
    """Connect to *current_ble_name*, authenticate with *current_psk*, and
    write the provisioning characteristic to update the name, PSK, and USB identity."""

    # Override the module-level PSK so hmac_wrap() uses the right key.
    _send_ble_module.PSK = current_psk

    print(f"[BLE] Scanning for '{current_ble_name}' (up to {SCAN_TIMEOUT:.0f}s)…")
    device = await BleakScanner.find_device_by_name(current_ble_name, timeout=SCAN_TIMEOUT)
    if device is None:
        raise RuntimeError(
            f"Device '{current_ble_name}' not found. "
            "Make sure the ESP32 is powered on and advertising."
        )
    print(f"[BLE] Found: {device.name}  ({device.address})")

    _send_ble_module._reset_counter()

    async with BleakClient(device) as client:
        print(f"[BLE] Connected  (MTU: {client.mtu_size} bytes)")

        # Subscribe to ready notifications (required by get_esp32_pubkey flow).
        _send_ble_module._last_completion = 0
        _send_ble_module._notify_event = asyncio.Event()
        await client.start_notify(READY_CHAR_UUID, _on_ready_notify)

        # Authenticate ESP32's pubkey — also confirms we have the correct PSK.
        esp32_pubkey = await get_esp32_pubkey(client)

        # Build provisioning payload:
        #   [name_len:1][name][new_psk:32][vid:2 BE][pid:2 BE][mfr_len:1][mfr][serial_len:1][serial]
        name_bytes  = new_name.encode("utf-8")
        mfr_bytes   = usb_manufacturer.encode("utf-8")[:64]
        serial_bytes = usb_serial.encode("utf-8")[:64]
        if not 1 <= len(name_bytes) <= 64:
            raise ValueError(f"New BLE name must be 1–64 bytes (got {len(name_bytes)})")
        payload = (
            bytes([len(name_bytes)]) + name_bytes
            + new_psk
            + struct.pack(">HH", usb_vid & 0xFFFF, usb_pid & 0xFFFF)
            + bytes([len(mfr_bytes)]) + mfr_bytes
            + bytes([len(serial_bytes)]) + serial_bytes
        )

        # Two-layer protection:
        #   HMAC  — authenticates with current PSK (only holder can provision)
        #   ECIES — encrypts so the new PSK / USB identity never travels in the clear
        packet = encrypt_payload(hmac_wrap(payload), esp32_pubkey)

        print(
            f"[Provision] Sending name={new_name!r}, psk={new_psk.hex()}, "
            f"VID=0x{usb_vid:04X}, PID=0x{usb_pid:04X}, "
            f"manufacturer={usb_manufacturer!r}, serial={usb_serial!r}"
        )
        try:
            await client.write_gatt_char(PROVISION_CHAR_UUID, packet, response=True)
        except BleakError:
            # The ESP32 calls esp_restart() before sending the GATT write response.
            pass
        print("[Provision] Write accepted — ESP32 is restarting…")

    # The ESP32 restarts on receipt of a valid provisioning write; the BLE
    # connection drops immediately, which is expected.


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Provision (or re-provision) an ESP32-S3 keyboard bridge.",
    )
    parser.add_argument(
        "--device",
        metavar="ALIAS",
        default=None,
        help="Alias of the device to provision in ~/.bluetooth-input/devices.json. "
             "Omit if only one device is configured, or for first-time setup (uses factory defaults).",
    )
    parser.add_argument(
        "--new-alias",
        default=None,
        metavar="ALIAS",
        help="Alias to save under in devices.json (e.g. 'office-desk').",
    )
    parser.add_argument(
        "--new-name",
        default=None,
        metavar="NAME",
        help="New BLE advertisement name for the ESP32 (1–32 bytes UTF-8).",
    )
    parser.add_argument(
        "--new-psk",
        metavar="HEX",
        default=None,
        help="New 32-byte PSK as 64 hex characters. "
             "If omitted, a fresh random PSK is generated.",
    )
    parser.add_argument(
        "--usb-vid",
        metavar="HEX_OR_INT",
        default=None,
        help="USB Vendor ID for the device (e.g. 0x1234 or 4660). "
             f"Default: 0x{_DEFAULT_USB_VID:04X} (Espressif).",
    )
    parser.add_argument(
        "--usb-pid",
        metavar="HEX_OR_INT",
        default=None,
        help=f"USB Product ID (e.g. 0x5678 or 22136). Default: 0x{_DEFAULT_USB_PID:04X}.",
    )
    parser.add_argument(
        "--usb-manufacturer",
        metavar="STRING",
        default=None,
        help=f"USB manufacturer name string. Default: '{_DEFAULT_USB_MANUFACTURER}'.",
    )
    parser.add_argument(
        "--usb-serial",
        metavar="STRING",
        default=None,
        help="USB serial number string. Default: empty (not shown in OS).",
    )
    parser.add_argument(
        "--factory-reset",
        action="store_true",
        help="Erase NVS on the device (name + PSK revert to firmware defaults) and remove "
             "its entry from devices.json.  Mutually exclusive with --new-alias/--new-name.",
    )
    args = parser.parse_args()

    if args.factory_reset and (args.new_alias or args.new_name):
        parser.error("--factory-reset cannot be combined with --new-alias or --new-name")
    if not args.factory_reset and (not args.new_alias or not args.new_name):
        parser.error("--new-alias and --new-name are required unless --factory-reset is used")

    # Resolve current identity.
    # Unlike send_ble.py, we do NOT auto-select the single configured device
    # when --device is omitted: omitting --device means "this is a new device
    # that isn't in the config yet, use factory defaults to connect to it".
    # To re-provision an existing device, always pass --device ALIAS.
    if args.device is not None:
        try:
            current_ble_name, current_psk = resolve_device(args.device)
        except (RuntimeError, KeyError) as e:
            print(f"[ERROR] {e}", file=sys.stderr)
            sys.exit(1)
    else:
        current_ble_name = _DEFAULT_DEVICE_NAME
        current_psk = bytes.fromhex(_DEFAULT_PSK_HEX)

    # ── Factory reset path ────────────────────────────────────────────────────
    if args.factory_reset:
        async def _factory_reset(ble_name: str, psk: bytes) -> None:
            _send_ble_module.PSK = psk
            device = await find_device(ble_name)
            async with BleakClient(device) as client:
                _send_ble_module._reset_counter()
                _send_ble_module._last_completion = 0
                _send_ble_module._notify_event = asyncio.Event()
                await client.start_notify(READY_CHAR_UUID, _on_ready_notify)
                # Factory-reset payload: ECIES_encrypt(hmac_wrap(b"\x00"))
                # name_len=0 signals factory reset to the firmware.
                esp32_pubkey = await get_esp32_pubkey(client)
                packet = encrypt_payload(hmac_wrap(b"\x00"), esp32_pubkey)
                print("[Provision] Sending factory-reset command…")
                try:
                    await client.write_gatt_char(PROVISION_CHAR_UUID, packet, response=True)
                except BleakError:
                    pass  # firmware rebooted before sending ACK — expected
                print("[Provision] Factory reset accepted — device is rebooting to defaults.")

        try:
            asyncio.run(_factory_reset(current_ble_name, current_psk))
        except (RuntimeError, ValueError) as e:
            print(f"[ERROR] {e}", file=sys.stderr)
            sys.exit(1)

        # Remove entry from config (device will advertise under DEFAULT_DEVICE_NAME again)
        cfg = load_config()
        if args.device and args.device in cfg.get("devices", {}):
            del cfg["devices"][args.device]
            save_config(cfg)
            from send_ble import CONFIG_PATH
            print(f"[Config] Removed '{args.device}' from {CONFIG_PATH}")
        return

    # ── Normal provisioning path ──────────────────────────────────────────────
    if args.new_psk is not None:
        try:
            new_psk = bytes.fromhex(args.new_psk)
        except ValueError:
            print("[ERROR] --new-psk must be exactly 64 hex characters.", file=sys.stderr)
            sys.exit(1)
        if len(new_psk) != 32:
            print("[ERROR] --new-psk must be exactly 64 hex characters (32 bytes).", file=sys.stderr)
            sys.exit(1)
    else:
        new_psk = os.urandom(32)
        print(f"[Provision] Generated new PSK: {new_psk.hex()}")

    # Resolve USB identity (use stored values when re-provisioning, override with CLI args)
    stored = load_config().get("devices", {}).get(args.device or "", {})
    def _resolve_usb_int(arg_val: str | None, stored_key: str, default: int) -> int:
        if arg_val is not None:
            try:
                return int(arg_val, 0)  # accepts 0x1234 or decimal
            except ValueError:
                print(f"[ERROR] Invalid USB id value: {arg_val!r}", file=sys.stderr)
                sys.exit(1)
        return stored.get(stored_key, default)

    usb_vid          = _resolve_usb_int(args.usb_vid, "usb_vid", _DEFAULT_USB_VID)
    usb_pid          = _resolve_usb_int(args.usb_pid, "usb_pid", _DEFAULT_USB_PID)
    usb_manufacturer = args.usb_manufacturer if args.usb_manufacturer is not None \
                       else stored.get("usb_manufacturer", _DEFAULT_USB_MANUFACTURER)
    usb_serial       = args.usb_serial if args.usb_serial is not None \
                       else stored.get("usb_serial", _DEFAULT_USB_SERIAL)

    new_name = args.new_name

    try:
        asyncio.run(provision(
            current_ble_name=current_ble_name,
            current_psk=current_psk,
            new_name=new_name,
            new_psk=new_psk,
            usb_vid=usb_vid,
            usb_pid=usb_pid,
            usb_manufacturer=usb_manufacturer,
            usb_serial=usb_serial,
        ))
    except (RuntimeError, ValueError) as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)

    # Update config file
    cfg = load_config()
    cfg.setdefault("devices", {})[args.new_alias] = {
        "ble_name": new_name,
        "psk": new_psk.hex(),
        "usb_vid": usb_vid,
        "usb_pid": usb_pid,
        "usb_manufacturer": usb_manufacturer,
        "usb_serial": usb_serial,
    }
    # If the alias changed, remove the old entry
    if args.device and args.device != args.new_alias and args.device in cfg["devices"]:
        del cfg["devices"][args.device]
    save_config(cfg)
    from send_ble import CONFIG_PATH
    print(f"[Config] Saved device '{args.new_alias}' → {new_name!r} in {CONFIG_PATH}")


if __name__ == "__main__":
    main()
