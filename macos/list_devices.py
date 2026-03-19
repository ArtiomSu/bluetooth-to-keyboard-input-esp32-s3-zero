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

from bleak import BleakScanner
from send_ble import SERVICE_UUID, load_config, _DEFAULT_PSK_HEX, _DEFAULT_DEVICE_NAME

SCAN_TIMEOUT_DEFAULT = 10.0

# ── ANSI colour helpers ───────────────────────────────────────────────────────
_USE_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None

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


async def scan(timeout: float) -> None:
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

    # Column widths
    w_name  = max(len(r[1]) for r in rows)
    w_name  = max(w_name, 4)
    w_addr  = max(len(r[2]) for r in rows)
    w_alias = max((len(r[4]) for r in rows), default=0)
    w_alias = max(w_alias, 5)

    sep = f"{'─'*2}  {'─'*w_name}  {'─'*w_addr}  {'─'*5}  {'─'*w_alias}"
    hdr = bold(f"{'St'}  {'Name':<{w_name}}  {'Address':<{w_addr}}  {'RSSI':>5}  {'Alias':<{w_alias}}")
    print(hdr)
    print(dim(sep))
    for status, name, addr, rssi, alias in rows:
        color = _STATUS_COLOR.get(status, lambda t: t)
        rssi_str = f"{rssi:>4} " if isinstance(rssi, int) else f"{'—':>5}"
        line = f"{status}   {name:<{w_name}}  {addr:<{w_addr}}  {rssi_str}  {alias}"
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
    args = parser.parse_args()
    asyncio.run(scan(args.timeout))


if __name__ == "__main__":
    main()
