package com.terminal_heat_sink.bluetoothtokeyboardinput.script

import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleManager
import com.terminal_heat_sink.bluetoothtokeyboardinput.crypto.CryptoManager
import kotlinx.coroutines.delay

class ScriptError(message: String) : Exception(message)

// Maps modifier event tokens to (isDown, modMask)
private val MODIFIER_EVENTS: Map<String, Pair<Boolean, Int>> = mapOf(
    "SHIFT_DOWN"  to (true  to BleConstants.MOD_LSHIFT),
    "SHIFT_UP"    to (false to BleConstants.MOD_LSHIFT),
    "ALT_DOWN"    to (true  to BleConstants.MOD_LALT),
    "ALT_UP"      to (false to BleConstants.MOD_LALT),
    "CTRL_DOWN"   to (true  to BleConstants.MOD_LCTRL),
    "CTRL_UP"     to (false to BleConstants.MOD_LCTRL),
    "GUI_DOWN"    to (true  to BleConstants.MOD_LGUI),
    "GUI_UP"      to (false to BleConstants.MOD_LGUI),
    "ALTGR_DOWN"  to (true  to BleConstants.MOD_RALT),
    "ALTGR_UP"    to (false to BleConstants.MOD_RALT),
)

data class ScriptContext(
    var minHoldMs: Int = 20,
    var maxHoldMs: Int = 20,
    var minGapMs:  Int = 20,
    var maxGapMs:  Int = 20,
)

suspend fun runScript(
    scriptText: String,
    ble: BleManager,
    crypto: CryptoManager,
    initialContext: ScriptContext = ScriptContext(),
) {
    val ctx = initialContext.copy()
    val lines = scriptText.lines()

    for ((index, rawLine) in lines.withIndex()) {
        val lineno = index + 1
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val spaceIdx = line.indexOf(' ')
        val cmd  = if (spaceIdx < 0) line else line.substring(0, spaceIdx)
        val rest = if (spaceIdx < 0) "" else line.substring(spaceIdx + 1)

        executeCommand(cmd, rest, lineno, ctx, ble, crypto)
    }
}

private suspend fun executeCommand(
    cmd: String,
    rest: String,
    lineno: Int,
    ctx: ScriptContext,
    ble: BleManager,
    crypto: CryptoManager,
) {
    when (cmd) {
        "REM" -> { /* comment, ignore */ }

        "STRING" -> ble.sendText(rest, crypto)

        "STRINGLN" -> ble.sendText(rest + "\n", crypto)

        "DELAY" -> {
            val ms = rest.trim().toIntOrNull()?.takeIf { it >= 0 }
                ?: throw ScriptError("Line $lineno: DELAY expects a non-negative integer, got '$rest'")
            delay(ms.toLong())
        }

        "RELEASE_ALL" -> ble.sendModClear(crypto)

        "SET_LAYOUT" -> {
            if (rest !in BleConstants.SUPPORTED_LAYOUTS) {
                throw ScriptError(
                    "Line $lineno: Unknown layout '$rest'. " +
                    "Supported: ${BleConstants.SUPPORTED_LAYOUTS.joinToString()}"
                )
            }
            ble.setLayout(rest, crypto)
        }

        "SET_OS" -> {
            if (rest !in BleConstants.SUPPORTED_OS) {
                throw ScriptError(
                    "Line $lineno: Unknown OS '$rest'. " +
                    "Supported: ${BleConstants.SUPPORTED_OS.joinToString()}"
                )
            }
            ble.setOs(rest, crypto)
        }

        "SET_MIN_DELAY" -> {
            ctx.minGapMs = parseNonNegativeInt(rest, lineno, cmd)
            ble.setDelay(ctx.minHoldMs, ctx.maxHoldMs, ctx.minGapMs, ctx.maxGapMs, crypto)
        }

        "SET_MAX_DELAY" -> {
            ctx.maxGapMs = parseNonNegativeInt(rest, lineno, cmd)
            ble.setDelay(ctx.minHoldMs, ctx.maxHoldMs, ctx.minGapMs, ctx.maxGapMs, crypto)
        }

        "SET_MIN_DELAY_HOLD" -> {
            ctx.minHoldMs = parseNonNegativeInt(rest, lineno, cmd)
            ble.setDelay(ctx.minHoldMs, ctx.maxHoldMs, ctx.minGapMs, ctx.maxGapMs, crypto)
        }

        "SET_MAX_DELAY_HOLD" -> {
            ctx.maxHoldMs = parseNonNegativeInt(rest, lineno, cmd)
            ble.setDelay(ctx.minHoldMs, ctx.maxHoldMs, ctx.minGapMs, ctx.maxGapMs, crypto)
        }

        in MODIFIER_EVENTS -> {
            val (isDown, mask) = MODIFIER_EVENTS.getValue(cmd)
            if (isDown) ble.sendModDown(mask, crypto) else ble.sendModUp(mask, crypto)
        }

        in BleConstants.SPECIAL_KEYS -> {
            ble.sendKeyTap(BleConstants.SPECIAL_KEYS.getValue(cmd), crypto)
        }

        else -> throw ScriptError("Line $lineno: Unknown command '$cmd'")
    }
}

private fun parseNonNegativeInt(value: String, lineno: Int, cmd: String): Int {
    return value.trim().toIntOrNull()?.takeIf { it >= 0 }
        ?: throw ScriptError("Line $lineno: $cmd expects a non-negative integer, got '$value'")
}
