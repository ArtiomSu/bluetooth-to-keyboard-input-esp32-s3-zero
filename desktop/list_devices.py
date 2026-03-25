#!/usr/bin/env python3
"""
list_devices.py — Scan for nearby BLE devices and identify ESP32-KB bridges.

Status column legend:
  ✓  Device is configured in ~/.bluetooth-input/devices.json
  ●  Has the ESP32-KB service UUID but is not in your config
     (could be another user's device, or an unregistered one of yours)
  ✗  Not an ESP32-KB device

Usage:
    python list_devices.py [--timeout SECONDS]
"""

import argparse
import asyncio
import os
import sys
from pathlib import Path

_here = Path(__file__).parent
if str(_here) not in sys.path:
    sys.path.insert(0, str(_here))

from bleak import BleakClient, BleakScanner
from send_ble import SERVICE_UUID, load_config, _DEFAULT_PSK_HEX, _DEFAULT_DEVICE_NAME

SCAN_TIMEOUT_DEFAULT = 10.0
FIRMWARE_VER_CHAR_UUID = "1234000C-1234-1234-1234-123456789abc"

# ── ANSI colour helpers ───────────────────────────────────────────────────────
def _vt_supported() -> bool:
    """Return True if the terminal is known to support ANSI escape codes."""
    if not sys.stdout.isatty():
        return False
    if os.environ.get("NO_COLOR"):
        return False
    if sys.platform == "win32":
        # Try to enable VT processing (Windows 10 1511+).
        # Falls back gracefully on older Windows where the flag is ignored.
        try:
            import ctypes
            import ctypes.wintypes
            kernel32 = ctypes.windll.kernel32
            ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
            handle = kernel32.GetStdHandle(-11)  # STD_OUTPUT_HANDLE
            mode = ctypes.wintypes.DWORD()
            if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
                return bool(kernel32.SetConsoleMode(
                    handle, mode.value | ENABLE_VIRTUAL_TERMINAL_PROCESSING))
        except Exception:
            pass
        return False
    return True

_USE_COLOR = _vt_supported()

def _c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _USE_COLOR else text

def green(t):  return _c("32",    t)
def yellow(t): return _c("33",    t)
def cyan(t):   return _c("36",    t)
def dim(t):    return _c("2",     t)
def bold(t):   return _c("1",     t)

# Status → colour function
_STATUS_COLOR = {
    "✓": green,
    "⚠": yellow,
    "●": cyan,
    "✗": dim,
}


async def read_firmware_version(address: str) -> str:
    """Connect briefly to a device, read its firmware version, then disconnect."""
    try:
        async with BleakClient(address, timeout=6.0) as client:
            data = await client.read_gatt_char(FIRMWARE_VER_CHAR_UUID)
            if len(data) >= 2:
                return f"v{data[0]}.{data[1]}"
    except Exception:
        pass
    return "?"


async def scan(timeout: float, read_version: bool) -> None:
    print(f"Scanning for {timeout:.0f}s…\n")

    cfg = load_config()
    devices_cfg: dict = cfg.get("devices", {})

    # Build lookup: ble_name → alias  (and address → alias for robustness)
    name_to_alias: dict[str, str] = {
        v["ble_name"]: k for k, v in devices_cfg.items()
    }

    discovered: dict[str, tuple] = {}  # address → (device, adv_data)

    def callback(device, adv_data):
        discovered[device.address] = (device, adv_data)

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(timeout)
    await scanner.stop()

    if not discovered:
        print("No BLE devices found.")
        return

    # Categorise
    rows = []
    for addr, (device, adv) in sorted(discovered.items(), key=lambda x: x[1][1].rssi or -999, reverse=True):
        name = device.name or "(unknown)"
        rssi = adv.rssi if adv.rssi is not None else "—"

        svc_uuids = [str(u).lower() for u in (adv.service_uuids or [])]
        is_ours = SERVICE_UUID.lower() in svc_uuids

        alias = name_to_alias.get(name)
        if alias:
            entry = devices_cfg[alias]
            is_default_psk = entry.get("psk", "").lower() == _DEFAULT_PSK_HEX.lower()
            status = "⚠" if is_default_psk else "✓"
        elif is_ours:
            # Name still matches the factory default → very likely using the default PSK
            status = "⚠" if name == _DEFAULT_DEVICE_NAME else "●"
        else:
            status = "✗"

        rows.append((status, name, addr, rssi, alias or ""))

    # Optionally connect to each ESP32-KB device and read firmware version
    versions: dict[str, str] = {}  # address → version string
    if read_version:
        our_addrs = [addr for status, _, addr, _, _ in rows if status in ("✓", "⚠", "●")]
        if our_addrs:
            print(f"Reading firmware version from {len(our_addrs)} device(s)…")
            for addr in our_addrs:
                versions[addr] = await read_firmware_version(addr)
            print()

    # Column widths
    w_name  = max(len(r[1]) for r in rows)
    w_name  = max(w_name, 4)
    w_addr  = max(len(r[2]) for r in rows)
    w_alias = max((len(r[4]) for r in rows), default=0)
    w_alias = max(w_alias, 5)
    w_ver = max((len(v) for v in versions.values()), default=0)
    w_ver = max(w_ver, 7) if versions else 0  # 7 = len("Version")

    ver_hdr = f"  {'Version':<{w_ver}}" if w_ver else ""
    sep_ver = f"  {'─'*w_ver}" if w_ver else ""
    sep = f"{'─'*2}  {'─'*w_name}  {'─'*w_addr}  {'─'*5}  {'─'*w_alias}{sep_ver}"
    hdr = bold(f"{'St'}  {'Name':<{w_name}}  {'Address':<{w_addr}}  {'RSSI':>5}  {'Alias':<{w_alias}}{ver_hdr}")
    print(hdr)
    print(dim(sep))
    for status, name, addr, rssi, alias in rows:
        color = _STATUS_COLOR.get(status, lambda t: t)
        rssi_str = f"{rssi:>4} " if isinstance(rssi, int) else f"{'—':>5}"
        ver_str = f"  {versions[addr]:<{w_ver}}" if w_ver and addr in versions else (f"  {'':>{w_ver}}" if w_ver else "")
        line = f"{status}   {name:<{w_name}}  {addr:<{w_addr}}  {rssi_str}  {alias:<{w_alias}}{ver_str}"
        print(color(line))

    print()
    print(
        f"Legend:  {green('✓')} configured (custom PSK)   "
        f"{yellow('⚠')} default PSK (provision me!)   "
        f"{cyan('●')} ESP32-KB service (PSK unknown)   "
        f"{dim('✗')} unrelated device"
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Scan for nearby BLE devices and identify ESP32-KB bridges.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=SCAN_TIMEOUT_DEFAULT,
        metavar="SECONDS",
        help=f"How long to scan (default: {SCAN_TIMEOUT_DEFAULT:.0f}s)",
    )
    parser.add_argument(
        "--read-version", "-V",
        action="store_true",
        default=False,
        help="After scanning, connect to each ESP32-KB device and read its firmware version (adds ~2-3s per device)",
    )
    args = parser.parse_args()
    asyncio.run(scan(args.timeout, args.read_version))


if __name__ == "__main__":
    main()
