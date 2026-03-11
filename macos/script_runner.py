#!/usr/bin/env python3
"""
script_runner.py — Parse and execute ducky-script-like script files for the
ESP32-S3 BLE keyboard bridge.

The script language is documented in script.md at the root of the repository.

This module is imported lazily by send_ble.py (--script flag) and can also be
used directly from other tools that already hold an open BleakClient.

Circular-import note
--------------------
This file imports helpers from send_ble.py.  send_ble.py must NOT import this
module at the top level — it should do a lazy ``from script_runner import …``
inside the function that needs it so that send_ble is fully initialised before
script_runner begins importing from it.
"""

import asyncio
from bleak import BleakClient

# Imported from the companion send_ble module.  All of these are plain
# functions / constants with no side-effects at import time.
from send_ble import (
    send_string,
    send_key_tap,
    send_mod_down,
    send_mod_up,
    send_mod_clear,
    set_layout,
    set_os,
    set_delay,
    SPECIAL_KEYS,
    MODIFIER_KEYS,
    SUPPORTED_LAYOUTS,
    SUPPORTED_OS,
)


class ScriptError(Exception):
    """Raised for syntax or semantic errors encountered while parsing a script."""


# ── Modifier event map ────────────────────────────────────────────────────────
# Maps bare modifier-event tokens to (direction, bitmask).
# direction: "down" → send_mod_down, "up" → send_mod_up
_MODIFIER_EVENTS: dict[str, tuple[str, int]] = {
    "SHIFT_DOWN":  ("down", MODIFIER_KEYS["SHIFT"]),
    "SHIFT_UP":    ("up",   MODIFIER_KEYS["SHIFT"]),
    "ALT_DOWN":    ("down", MODIFIER_KEYS["ALT"]),
    "ALT_UP":      ("up",   MODIFIER_KEYS["ALT"]),
    "CTRL_DOWN":   ("down", MODIFIER_KEYS["CTRL"]),
    "CTRL_UP":     ("up",   MODIFIER_KEYS["CTRL"]),
    "GUI_DOWN":    ("down", MODIFIER_KEYS["GUI"]),
    "GUI_UP":      ("up",   MODIFIER_KEYS["GUI"]),
    "ALTGR_DOWN":  ("down", MODIFIER_KEYS["ALTGR"]),
    "ALTGR_UP":    ("up",   MODIFIER_KEYS["ALTGR"]),
}


# ── Single command executor ───────────────────────────────────────────────────

async def _execute_command(
    client: BleakClient,
    esp32_pubkey: bytes,
    cmd: str,
    rest: str,
    ctx: dict,
    lineno: int,
) -> None:
    """Execute a single already-parsed script command.

    *ctx* is a mutable dict with keys ``min_delay`` and ``max_delay`` that
    SET_MIN_DELAY / SET_MAX_DELAY update in place so subsequent commands see
    the new values.
    """
    if cmd == "STRING":
        await send_string(client, rest, esp32_pubkey)

    elif cmd == "STRINGLN":
        await send_string(client, rest + "\n", esp32_pubkey)

    elif cmd == "DELAY":
        try:
            ms = int(rest)
            if ms < 0:
                raise ValueError
        except ValueError:
            raise ScriptError(
                f"Line {lineno}: DELAY expects a non-negative integer, got {rest!r}"
            )
        await asyncio.sleep(ms / 1000)

    elif cmd == "RELEASE_ALL":
        await send_mod_clear(client, esp32_pubkey)

    elif cmd in _MODIFIER_EVENTS:
        direction, mask = _MODIFIER_EVENTS[cmd]
        if direction == "down":
            await send_mod_down(client, mask, esp32_pubkey)
        else:
            await send_mod_up(client, mask, esp32_pubkey)

    elif cmd in SPECIAL_KEYS:
        await send_key_tap(client, SPECIAL_KEYS[cmd], esp32_pubkey)

    elif cmd == "SET_LAYOUT":
        if rest not in SUPPORTED_LAYOUTS:
            raise ScriptError(
                f"Line {lineno}: Unknown layout {rest!r}. "
                f"Supported: {', '.join(SUPPORTED_LAYOUTS)}"
            )
        await set_layout(client, rest)

    elif cmd == "SET_OS":
        if rest not in SUPPORTED_OS:
            raise ScriptError(
                f"Line {lineno}: Unknown OS {rest!r}. "
                f"Supported: {', '.join(SUPPORTED_OS)}"
            )
        await set_os(client, rest)

    elif cmd == "SET_MIN_DELAY":
        try:
            ms = int(rest)
            if ms < 0:
                raise ValueError
        except ValueError:
            raise ScriptError(
                f"Line {lineno}: SET_MIN_DELAY expects a non-negative integer, got {rest!r}"
            )
        ctx["min_delay"] = ms
        await set_delay(client, ctx["min_hold_delay"], ctx["max_hold_delay"], ctx["min_delay"], ctx["max_delay"])

    elif cmd == "SET_MAX_DELAY":
        try:
            ms = int(rest)
            if ms < 0:
                raise ValueError
        except ValueError:
            raise ScriptError(
                f"Line {lineno}: SET_MAX_DELAY expects a non-negative integer, got {rest!r}"
            )
        ctx["max_delay"] = ms
        await set_delay(client, ctx["min_hold_delay"], ctx["max_hold_delay"], ctx["min_delay"], ctx["max_delay"])

    elif cmd == "SET_MIN_DELAY_HOLD":
        try:
            ms = int(rest)
            if ms < 0:
                raise ValueError
        except ValueError:
            raise ScriptError(
                f"Line {lineno}: SET_MIN_DELAY_HOLD expects a non-negative integer, got {rest!r}"
            )
        ctx["min_hold_delay"] = ms
        await set_delay(client, ctx["min_hold_delay"], ctx["max_hold_delay"], ctx["min_delay"], ctx["max_delay"])

    elif cmd == "SET_MAX_DELAY_HOLD":
        try:
            ms = int(rest)
            if ms < 0:
                raise ValueError
        except ValueError:
            raise ScriptError(
                f"Line {lineno}: SET_MAX_DELAY_HOLD expects a non-negative integer, got {rest!r}"
            )
        ctx["max_hold_delay"] = ms
        await set_delay(client, ctx["min_hold_delay"], ctx["max_hold_delay"], ctx["min_delay"], ctx["max_delay"])

    else:
        raise ScriptError(f"Line {lineno}: Unknown command {cmd!r}")


# ── Public entry point ────────────────────────────────────────────────────────

async def run_script(
    client: BleakClient,
    esp32_pubkey: bytes,
    script_path: str,
    initial_min_delay: int = 20,
    initial_max_delay: int = 20,
    initial_min_hold_delay: int = 20,
    initial_max_hold_delay: int = 20,
) -> None:
    """Parse and execute *script_path* on the connected ESP32.

    Parameters
    ----------
    client                  Connected BleakClient.
    esp32_pubkey            Verified 32-byte X25519 public key used to encrypt
                            every outbound payload.
    script_path             Path to the script file to execute.
    initial_min_delay       Gap delay lower bound to start with (ms).
                            Inherited from --min-delay; overridable with SET_MIN_DELAY.
    initial_max_delay       Gap delay upper bound to start with (ms).
                            Inherited from --max-delay; overridable with SET_MAX_DELAY.
    initial_min_hold_delay  Hold duration lower bound to start with (ms).
                            Inherited from --min-delay-hold; overridable with SET_MIN_DELAY_HOLD.
    initial_max_hold_delay  Hold duration upper bound to start with (ms).
                            Inherited from --max-delay-hold; overridable with SET_MAX_DELAY_HOLD.
    """
    ctx: dict = {
        "min_delay":      initial_min_delay,
        "max_delay":      initial_max_delay,
        "min_hold_delay": initial_min_hold_delay,
        "max_hold_delay": initial_max_hold_delay,
    }

    try:
        with open(script_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except OSError as e:
        raise ScriptError(f"Cannot open script file {script_path!r}: {e}")

    # Track the previous executable command for REPEAT support.
    # REPEAT itself and REM/blank lines never become the new "previous".
    prev_cmd: str | None = None
    prev_rest: str | None = None

    for lineno, raw_line in enumerate(lines, 1):
        stripped = raw_line.strip()

        # Skip blank lines silently.
        if not stripped:
            continue

        parts = stripped.split(" ", 1)
        cmd = parts[0].upper()
        rest = parts[1] if len(parts) > 1 else ""

        # Comments — never become the previous command.
        if cmd == "REM":
            continue

        # REPEAT n — re-execute the previous command n more times.
        if cmd == "REPEAT":
            if prev_cmd is None:
                raise ScriptError(f"Line {lineno}: REPEAT with no previous command")
            try:
                n = int(rest)
                if n < 1:
                    raise ValueError
            except ValueError:
                raise ScriptError(
                    f"Line {lineno}: REPEAT expects a positive integer, got {rest!r}"
                )
            print(f"[Script] Line {lineno}: REPEAT {n}×  {prev_cmd}"
                  + (f" {prev_rest}" if prev_rest else ""))
            for _ in range(n):
                await _execute_command(
                    client, esp32_pubkey, prev_cmd, prev_rest or "", ctx, lineno
                )
            # REPEAT does not replace the previous command.
            continue

        print(f"[Script] Line {lineno}: {cmd}" + (f" {rest}" if rest else ""))
        await _execute_command(client, esp32_pubkey, cmd, rest, ctx, lineno)
        prev_cmd = cmd
        prev_rest = rest
