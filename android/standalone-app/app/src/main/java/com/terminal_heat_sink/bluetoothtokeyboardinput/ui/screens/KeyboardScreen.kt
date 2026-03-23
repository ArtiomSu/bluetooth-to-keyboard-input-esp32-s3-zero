package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

// ── Key definitions ───────────────────────────────────────────────────────────

private sealed class Key {
    /** A character key — same HID code regardless of shift; labels change. */
    data class Char(val lower: String, val upper: String, val hid: Int, val w: Float = 1f) : Key()
    /** A sticky-toggle modifier (Shift, Ctrl, Alt …). */
    data class Mod(val label: String, val mask: Int, val w: Float = 1.5f) : Key()
    /** A function key with a fixed label and HID code (Enter, Bksp, arrows …). */
    data class Func(val label: String, val hid: Int, val w: Float = 1f) : Key()
}

private val ROW_FN: List<Key> = listOf(
    Key.Func("F1",  0x3A), Key.Func("F2",  0x3B), Key.Func("F3",  0x3C),
    Key.Func("F4",  0x3D), Key.Func("F5",  0x3E), Key.Func("F6",  0x3F),
    Key.Func("F7",  0x40), Key.Func("F8",  0x41), Key.Func("F9",  0x42),
    Key.Func("F10", 0x43), Key.Func("F11", 0x44), Key.Func("F12", 0x45),
    Key.Func("Ins", 0x49, 1.2f),
    Key.Func("PgUp", 0x4B, 1.2f),
    Key.Func("PgDn", 0x4E, 1.2f),
    Key.Func("Home", 0x4A, 1.2f),
    Key.Func("End",  0x4D, 1.2f),
)

private val ROW0: List<Key> = listOf(
    Key.Func("Esc",  0x29, 1.2f),
    Key.Char("`","~",  0x35), Key.Char("1","!",0x1E), Key.Char("2","@",0x1F),
    Key.Char("3","#",  0x20), Key.Char("4","$",0x21), Key.Char("5","%",0x22),
    Key.Char("6","^",  0x23), Key.Char("7","&",0x24), Key.Char("8","*",0x25),
    Key.Char("9","(",  0x26), Key.Char("0",")",0x27),
    Key.Char("-","_",  0x2D), Key.Char("=","+",0x2E),
    Key.Func("⌫",    0x2A, 1.6f),
)

private val ROW1: List<Key> = listOf(
    Key.Func("Tab",  0x2B, 1.5f),
    Key.Char("q","Q",0x14), Key.Char("w","W",0x1A), Key.Char("e","E",0x08),
    Key.Char("r","R",0x15), Key.Char("t","T",0x17), Key.Char("y","Y",0x1C),
    Key.Char("u","U",0x18), Key.Char("i","I",0x0C), Key.Char("o","O",0x12),
    Key.Char("p","P",0x13),
    Key.Char("[","{", 0x2F), Key.Char("]","}",0x30),
    Key.Char("\\","|",0x31, 1.3f),
)

private val ROW2: List<Key> = listOf(
    Key.Func("Caps", 0x39, 1.6f),
    Key.Char("a","A",0x04), Key.Char("s","S",0x16), Key.Char("d","D",0x07),
    Key.Char("f","F",0x09), Key.Char("g","G",0x0A), Key.Char("h","H",0x0B),
    Key.Char("j","J",0x0D), Key.Char("k","K",0x0E), Key.Char("l","L",0x0F),
    Key.Char(";",":", 0x33), Key.Char("'","\"",0x34),
    Key.Func("⏎",   0x28, 2.1f),
)

private val ROW3: List<Key> = listOf(
    Key.Mod("⇧", BleConstants.MOD_LSHIFT, 2.1f),
    Key.Char("z","Z",0x1D), Key.Char("x","X",0x1B), Key.Char("c","C",0x06),
    Key.Char("v","V",0x19), Key.Char("b","B",0x05), Key.Char("n","N",0x11),
    Key.Char("m","M",0x10),
    Key.Char(",","<",0x36), Key.Char(".",">",0x37), Key.Char("/","?",0x38),
    Key.Func("Del", 0x4C, 1.2f),
    Key.Mod("⇧", BleConstants.MOD_LSHIFT, 2.1f),
)

private val ROW4: List<Key> = listOf(
    Key.Mod("Ctrl", BleConstants.MOD_LCTRL, 1.6f),
    Key.Mod("Alt",  BleConstants.MOD_LALT,  1.3f),
    Key.Func("Space", 0x2C, 5.0f),
    Key.Mod("Alt",  BleConstants.MOD_LALT,  1.3f),
    Key.Func("←", 0x50), Key.Func("↑", 0x52),
    Key.Func("↓", 0x51), Key.Func("→", 0x4F),
)

private val KEYBOARD_ROWS = listOf(ROW_FN, ROW0, ROW1, ROW2, ROW3, ROW4)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardScreen(viewModel: BleViewModel) {
    val context = LocalContext.current
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Force landscape for the duration of this screen
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val saved = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                saved ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    // Track which modifier masks are currently toggled ON (= held on firmware)
    var activeMods by remember { mutableStateOf(setOf<Int>()) }
    val shiftActive = BleConstants.MOD_LSHIFT in activeMods

    fun onModTap(mask: Int) {
        val wasOn = mask in activeMods
        activeMods = if (wasOn) activeMods - mask else activeMods + mask
        viewModel.toggleModifier(mask, !wasOn)
    }

    fun onKeyTap(hid: Int) = viewModel.tapKey(hid)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            KEYBOARD_ROWS.forEach { row ->
                KeyRow(
                    keys = row,
                    shiftActive = shiftActive,
                    activeMods = activeMods,
                    onModTap = ::onModTap,
                    onKeyTap = ::onKeyTap,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

// ── Row ───────────────────────────────────────────────────────────────────────

@Composable
private fun KeyRow(
    keys: List<Key>,
    shiftActive: Boolean,
    activeMods: Set<Int>,
    onModTap: (Int) -> Unit,
    onKeyTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEach { key ->
            when (key) {
                is Key.Char -> KeyButton(
                    label = if (shiftActive) key.upper else key.lower,
                    modifier = Modifier.weight(key.w).fillMaxHeight(),
                    onClick = { onKeyTap(key.hid) },
                )
                is Key.Func -> KeyButton(
                    label = key.label,
                    modifier = Modifier.weight(key.w).fillMaxHeight(),
                    isFunctional = true,
                    onClick = { onKeyTap(key.hid) },
                )
                is Key.Mod -> KeyButton(
                    label = key.label,
                    modifier = Modifier.weight(key.w).fillMaxHeight(),
                    isActive = key.mask in activeMods,
                    isFunctional = true,
                    onClick = { onModTap(key.mask) },
                )
            }
        }
    }
}

// ── Key button ────────────────────────────────────────────────────────────────

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isFunctional: Boolean = false,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isActive     -> MaterialTheme.colorScheme.primary
        isFunctional -> MaterialTheme.colorScheme.secondaryContainer
        else         -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isActive     -> MaterialTheme.colorScheme.onPrimary
        isFunctional -> MaterialTheme.colorScheme.onSecondaryContainer
        else         -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = if (label.length > 2) 9.sp else 11.sp,
                fontWeight = if (isFunctional || isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
