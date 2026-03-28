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
    stopRequested: () -> Boolean = { false },
) {
    val ctx = initialContext.copy()
    val lines = scriptText.lines()

    // ── Pass 1: collect FUNCTION definitions ─────────────────────────────────
    // Stores function name → list of (lineno, cmd, rest) triples.
    val functions = mutableMapOf<String, List<Triple<Int, String, String>>>()
    var collectingFunc: String? = null
    val funcBody = mutableListOf<Triple<Int, String, String>>()

    for ((index, rawLine) in lines.withIndex()) {
        val lineno = index + 1
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val spaceIdx = line.indexOf(' ')
        val fCmd = if (spaceIdx < 0) line else line.substring(0, spaceIdx)
        val fRest = if (spaceIdx < 0) "" else line.substring(spaceIdx + 1)

        if (collectingFunc != null) {
            when (fCmd) {
                "FUNCTION" -> throw ScriptError("Line $lineno: Nested FUNCTION definitions are not allowed")
                "END_FUNCTION" -> {
                    functions[collectingFunc!!] = funcBody.toList()
                    collectingFunc = null
                    funcBody.clear()
                }
                else -> funcBody.add(Triple(lineno, fCmd, fRest))
            }
        } else when (fCmd) {
            "FUNCTION" -> {
                val name = fRest.trim()
                if (name.isEmpty()) throw ScriptError("Line $lineno: FUNCTION requires a name")
                collectingFunc = name
                funcBody.clear()
            }
            "END_FUNCTION" -> throw ScriptError("Line $lineno: END_FUNCTION without matching FUNCTION")
        }
    }
    if (collectingFunc != null) {
        throw ScriptError("FUNCTION '$collectingFunc' has no matching END_FUNCTION")
    }

    // ── Pass 2: execute ───────────────────────────────────────────────────────
    // Track the previous executable command so REPEAT can re-run it.
    // REM, blank lines, FUNCTION blocks, and REPEAT itself never update these.
    var prevCmd: String? = null
    var prevRest: String? = null
    var inFuncDef = false

    for ((index, rawLine) in lines.withIndex()) {
        // Check before starting each command so the current one always completes fully.
        if (stopRequested()) return
        val lineno = index + 1
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val spaceIdx = line.indexOf(' ')
        val cmd  = if (spaceIdx < 0) line else line.substring(0, spaceIdx)
        val rest = if (spaceIdx < 0) "" else line.substring(spaceIdx + 1)

        // Comments — never become the previous command.
        if (cmd == "REM") continue

        // Skip over FUNCTION definition blocks (already collected in pass 1).
        if (cmd == "FUNCTION") { inFuncDef = true; continue }
        if (cmd == "END_FUNCTION") { inFuncDef = false; continue }
        if (inFuncDef) continue

        // REPEAT n — re-execute the previous command n more times.
        if (cmd == "REPEAT") {
            if (prevCmd == null) throw ScriptError("Line $lineno: REPEAT with no previous command")
            val n = rest.trim().toIntOrNull()?.takeIf { it >= 1 }
                ?: throw ScriptError("Line $lineno: REPEAT expects a positive integer, got '$rest'")
            for (i in 1..n) {
                if (stopRequested()) return
                if (prevCmd == "CALL") {
                    val body = functions[prevRest]
                        ?: throw ScriptError("Line $lineno: Unknown function '$prevRest'")
                    executeBody(body, functions, ctx, ble, crypto, stopRequested)
                } else {
                    executeCommand(prevCmd!!, prevRest ?: "", lineno, ctx, ble, crypto)
                }
            }
            // REPEAT does not replace the previous command.
            continue
        }

        // CALL — invoke a named function.
        if (cmd == "CALL") {
            val funcName = rest.trim()
            if (funcName.isEmpty()) throw ScriptError("Line $lineno: CALL requires a function name")
            val body = functions[funcName]
                ?: throw ScriptError("Line $lineno: Unknown function '$funcName'")
            executeBody(body, functions, ctx, ble, crypto, stopRequested)
            prevCmd = cmd
            prevRest = funcName
            continue
        }

        executeCommand(cmd, rest, lineno, ctx, ble, crypto)
        prevCmd = cmd
        prevRest = rest
    }
}

/**
 * Execute a sequence of (lineno, cmd, rest) triples with full REPEAT and CALL support.
 *
 * Carries its own prevCmd / prevRest state so REPEAT works correctly inside
 * function bodies.  Called recursively for nested CALLs.
 */
private suspend fun executeBody(
    commands: List<Triple<Int, String, String>>,
    functions: Map<String, List<Triple<Int, String, String>>>,
    ctx: ScriptContext,
    ble: BleManager,
    crypto: CryptoManager,
    stopRequested: () -> Boolean,
) {
    var prevCmd: String? = null
    var prevRest: String? = null

    for ((lineno, cmd, rest) in commands) {
        if (stopRequested()) return
        if (cmd == "REM") continue

        if (cmd == "REPEAT") {
            if (prevCmd == null) throw ScriptError("Line $lineno: REPEAT with no previous command")
            val n = rest.trim().toIntOrNull()?.takeIf { it >= 1 }
                ?: throw ScriptError("Line $lineno: REPEAT expects a positive integer, got '$rest'")
            for (i in 1..n) {
                if (stopRequested()) return
                if (prevCmd == "CALL") {
                    val body = functions[prevRest]
                        ?: throw ScriptError("Line $lineno: Unknown function '$prevRest'")
                    executeBody(body, functions, ctx, ble, crypto, stopRequested)
                } else {
                    executeCommand(prevCmd!!, prevRest ?: "", lineno, ctx, ble, crypto)
                }
            }
            continue
        }

        if (cmd == "CALL") {
            val funcName = rest.trim()
            if (funcName.isEmpty()) throw ScriptError("Line $lineno: CALL requires a function name")
            val body = functions[funcName]
                ?: throw ScriptError("Line $lineno: Unknown function '$funcName'")
            executeBody(body, functions, ctx, ble, crypto, stopRequested)
            prevCmd = cmd
            prevRest = funcName
            continue
        }

        executeCommand(cmd, rest, lineno, ctx, ble, crypto)
        prevCmd = cmd
        prevRest = rest
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
