package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@Composable
fun TrackpadScreen(viewModel: BleViewModel) {
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Main touch surface ────────────────────────────────────────────
            TrackpadSurface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                onMove   = { dx, dy  -> viewModel.sendMouseMove(dx, dy) },
                onScroll = { v, h    -> viewModel.sendMouseScroll(v, h) },
                onTap    = {           viewModel.sendMouseClick(BleConstants.MOUSE_LEFT) },
            )

            // ── Button row ────────────────────────────────────────────────────
            MouseButtonRow(
                viewModel = viewModel,
                modifier  = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

// ── Touch surface ─────────────────────────────────────────────────────────────

@Composable
private fun TrackpadSurface(
    modifier: Modifier = Modifier,
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (vertical: Int, horizontal: Int) -> Unit,
    onTap: () -> Unit,
) {
    // Scroll sensitivity: pixels of two-finger drag per one scroll unit sent to firmware.
    val scrollSensitivity = 8f

    Surface(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Wait for first touch down
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    var moved   = false
                    var scrolling = false
                    var accX    = 0f
                    var accY    = 0f
                    var scrollAcc = 0f

                    // Track until all fingers lift
                    while (true) {
                        val event   = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) {
                            // All fingers lifted — check for tap
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!moved && !scrolling && elapsed < 300L) {
                                onTap()
                            }
                            break
                        }

                        when {
                            pressed.size == 1 && !scrolling -> {
                                val delta = pressed[0].positionChange()
                                accX += delta.x
                                accY += delta.y
                                val dx = accX.toInt()
                                val dy = accY.toInt()
                                if (dx != 0 || dy != 0) {
                                    moved = true
                                    onMove(
                                        dx.coerceIn(-127, 127),
                                        dy.coerceIn(-127, 127),
                                    )
                                    accX -= dx
                                    accY -= dy
                                }
                            }
                            pressed.size >= 2 -> {
                                scrolling = true
                                val avgDy = pressed
                                    .map { it.positionChange().y }
                                    .average()
                                    .toFloat()
                                scrollAcc += avgDy / scrollSensitivity
                                val sv = scrollAcc.toInt()
                                if (sv != 0) {
                                    // Negate: finger drags down → content scrolls up = positive scroll
                                    onScroll(-sv.coerceIn(-127, 127), 0)
                                    scrollAcc -= sv
                                }
                            }
                        }

                        event.changes.forEach { it.consume() }
                    }
                }
            }
        },
        color  = MaterialTheme.colorScheme.surfaceVariant,
        shape  = MaterialTheme.shapes.large,
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Drag · Two fingers to scroll · Tap to click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

// ── Button row ────────────────────────────────────────────────────────────────

@Composable
private fun MouseButtonRow(
    viewModel: BleViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MouseButton("◀",     BleConstants.MOUSE_BACK,    viewModel, Modifier.weight(1f))
        MouseButton("Left",  BleConstants.MOUSE_LEFT,    viewModel, Modifier.weight(2f), primary = true)
        MouseButton("Mid",   BleConstants.MOUSE_MIDDLE,  viewModel, Modifier.weight(1f))
        MouseButton("Right", BleConstants.MOUSE_RIGHT,   viewModel, Modifier.weight(2f))
        MouseButton("▶",     BleConstants.MOUSE_FORWARD, viewModel, Modifier.weight(1f))
    }
}

@Composable
private fun MouseButton(
    label: String,
    buttonMask: Int,
    viewModel: BleViewModel,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }

    val containerColor = when {
        pressed && primary -> MaterialTheme.colorScheme.primary
        pressed            -> MaterialTheme.colorScheme.secondary
        primary            -> MaterialTheme.colorScheme.primaryContainer
        else               -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        pressed && primary -> MaterialTheme.colorScheme.onPrimary
        pressed            -> MaterialTheme.colorScheme.onSecondary
        primary            -> MaterialTheme.colorScheme.onPrimaryContainer
        else               -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.small)
            .background(containerColor)
            // Hold button down until finger lifts — enables drag-select
            .pointerInput(buttonMask) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        viewModel.sendMouseButtonDown(buttonMask)
                        tryAwaitRelease()
                        viewModel.sendMouseButtonUp(buttonMask)
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}
