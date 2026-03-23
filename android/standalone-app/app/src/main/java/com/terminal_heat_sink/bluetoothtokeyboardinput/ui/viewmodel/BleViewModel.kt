package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleManager
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.ConnectionState
import com.terminal_heat_sink.bluetoothtokeyboardinput.crypto.CryptoManager
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceConfig
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceRepository
import com.terminal_heat_sink.bluetoothtokeyboardinput.script.ScriptContext
import com.terminal_heat_sink.bluetoothtokeyboardinput.script.ScriptRepository
import com.terminal_heat_sink.bluetoothtokeyboardinput.script.runScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    private var cryptoManager: CryptoManager? = null
    var activeDevice: DeviceConfig? = null
        private set

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    val repository = DeviceRepository(application)
    val scriptRepository = ScriptRepository(application)

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    // Mouse enabled — read from firmware on every connect; false until confirmed.
    private val _mouseEnabled = MutableStateFlow(false)
    val mouseEnabled: StateFlow<Boolean> = _mouseEnabled.asStateFlow()

    // Accumulated deltas for in-flight mouse moves — merged into the next send.
    private var mouseMovePending = false
    private var pendingMoveX = 0
    private var pendingMoveY = 0
    private var mouseScrollPending = false
    private var pendingScrollV = 0

    // Device config selected by the user from the saved-devices list, used during scan/connect.
    // null means "new device" (use default PSK).
    private val _pendingConfig = MutableStateFlow<DeviceConfig?>(null)
    val pendingConfig: StateFlow<DeviceConfig?> = _pendingConfig.asStateFlow()

    fun selectDeviceConfig(config: DeviceConfig?) {
        _pendingConfig.value = config
    }

    // Settings applied to the current connection
    private val _layout = MutableStateFlow("en-US")
    val layout: StateFlow<String> = _layout.asStateFlow()

    private val _targetOs = MutableStateFlow("other")
    val targetOs: StateFlow<String> = _targetOs.asStateFlow()

    fun startScan() = bleManager.startScan()
    fun stopScan()  = bleManager.stopScan()

    fun setPermissionDenied() {
        _statusMessage.value = "Bluetooth permission denied. Grant it in app settings."
    }

    fun connectToDevice(device: BluetoothDevice, config: DeviceConfig) {
        val crypto = CryptoManager(config.pskBytes)
        cryptoManager = crypto
        activeDevice = config
        _mouseEnabled.value = false  // reset until confirmed by firmware read
        viewModelScope.launch {
            try {
                _isBusy.value = true
                bleManager.connect(device, crypto)
                // Apply saved settings before signalling Connected so the UI is never
                // interactive with stale firmware state, and user-triggered Apply calls
                // cannot race with these post-connect writes.
                bleManager.setLayout(config.layout, crypto)
                bleManager.setOs(config.targetOs, crypto)
                bleManager.setDelay(config.minHoldMs, config.maxHoldMs, config.minGapMs, config.maxGapMs, crypto)
                _layout.value = config.layout
                _targetOs.value = config.targetOs
                // Read mouse-enabled state before making UI interactive.
                _mouseEnabled.value = bleManager.readMouseEnabled()
                // Only now tell the UI the device is ready.
                bleManager.markReady()
                _statusMessage.value = "Connected to ${config.bleName}"
            } catch (e: Exception) {
                // Ensure GATT is closed and state is clean if settings application fails
                // after a successful BLE connect (e.g. write timeout mid-config).
                bleManager.disconnect()
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun connectToDefaultDevice(device: BluetoothDevice) =
        connectToDevice(device, repository.getDefaultDeviceOrStored())

    fun disconnect() = bleManager.disconnect()

    /**
     * Fire-and-forget single key tap. Does NOT set isBusy so the keyboard UI stays responsive.
     * Multiple rapid taps queue on the BleManager's opMutex and are sent in order.
     */
    fun tapKey(hidCode: Int) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                bleManager.sendKeyTap(hidCode, crypto)
            } catch (e: Exception) {
                _statusMessage.value = "Key error: ${e.message}"
            }
        }
    }

    /** Toggle a sticky modifier on the firmware side. */
    fun toggleModifier(mask: Int, active: Boolean) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                if (active) bleManager.sendModDown(mask, crypto)
                else bleManager.sendModUp(mask, crypto)
            } catch (e: Exception) {
                _statusMessage.value = "Modifier error: ${e.message}"
            }
        }
    }

    fun sendText(text: String) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                bleManager.sendText(text, crypto)
            } catch (e: Exception) {
                _statusMessage.value = "Send error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun runScript(scriptText: String) {
        val crypto = cryptoManager ?: return
        val device = activeDevice
        viewModelScope.launch {
            try {
                _isBusy.value = true
                val ctx = if (device != null) {
                    ScriptContext(device.minHoldMs, device.maxHoldMs, device.minGapMs, device.maxGapMs)
                } else ScriptContext()
                runScript(scriptText, bleManager, crypto, ctx)
                // Restore saved settings — script commands like SET_MIN_DELAY may have
                // changed them on the ESP32 for the duration of the script.
                if (device != null) {
                    bleManager.setLayout(device.layout, crypto)
                    bleManager.setOs(device.targetOs, crypto)
                    bleManager.setDelay(device.minHoldMs, device.maxHoldMs, device.minGapMs, device.maxGapMs, crypto)
                }
                _statusMessage.value = "Script completed"
            } catch (e: Exception) {
                _statusMessage.value = "Script error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setLayout(layout: String) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                bleManager.setLayout(layout, crypto)
                _layout.value = layout
                activeDevice?.let { dev ->
                    repository.save(dev.copy(layout = layout))
                    activeDevice = dev.copy(layout = layout)
                }
            } catch (e: Exception) {
                _statusMessage.value = "Layout error: ${e.message}"
            }
        }
    }

    fun setTargetOs(os: String) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                bleManager.setOs(os, crypto)
                _targetOs.value = os
                activeDevice?.let { dev ->
                    repository.save(dev.copy(targetOs = os))
                    activeDevice = dev.copy(targetOs = os)
                }
            } catch (e: Exception) {
                _statusMessage.value = "OS error: ${e.message}"
            }
        }
    }

    fun setDelay(minHoldMs: Int, maxHoldMs: Int, minGapMs: Int, maxGapMs: Int) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                bleManager.setDelay(minHoldMs, maxHoldMs, minGapMs, maxGapMs, crypto)
                activeDevice?.let { dev ->
                    val updated = dev.copy(
                        minHoldMs = minHoldMs, maxHoldMs = maxHoldMs,
                        minGapMs = minGapMs, maxGapMs = maxGapMs
                    )
                    repository.save(updated)
                    activeDevice = updated
                }
            } catch (e: Exception) {
                _statusMessage.value = "Delay error: ${e.message}"
            }
        }
    }

    fun provision(newBleName: String, newPskHex: String, newAlias: String,
                  usbVid: Int, usbPid: Int, usbManufacturerName: String, usbSerialNumber: String,
                  onProvisioned: () -> Unit) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                val newPsk = newPskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                bleManager.provision(newBleName, newPsk, usbVid, usbPid, usbManufacturerName, usbSerialNumber, crypto)
                // Save updated config; device will reboot with the new identity
                val updated = DeviceConfig(
                    alias              = newAlias,
                    bleName            = newBleName,
                    pskHex             = newPskHex,
                    layout             = activeDevice?.layout     ?: "en-US",
                    targetOs           = activeDevice?.targetOs   ?: "other",
                    usbVid             = usbVid,
                    usbPid             = usbPid,
                    usbManufacturerName = usbManufacturerName,
                    usbSerialNumber    = usbSerialNumber,
                )
                repository.save(updated)
                activeDevice?.alias?.let { old ->
                    if (old != newAlias) repository.delete(old)
                }
                activeDevice = null
                cryptoManager = null
                onProvisioned()
            } catch (e: Exception) {
                _statusMessage.value = "Provision error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun factoryReset(onComplete: () -> Unit) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                bleManager.factoryReset(crypto)
                // Device reverts to default name/PSK — remove the saved config
                activeDevice?.alias?.let { repository.delete(it) }
                activeDevice = null
                cryptoManager = null
                _statusMessage.value = "Factory reset sent. Device is rebooting to defaults."
                onComplete()
            } catch (e: Exception) {
                _statusMessage.value = "Factory reset error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    // ── Mouse ───────────────────────────────────────────────────────────

    /**
     * Relative mouse movement. If a send is already in-flight, accumulate the delta
     * so it is included in the next send rather than lost.
     */
    fun sendMouseMove(dx: Int, dy: Int) {
        pendingMoveX = (pendingMoveX + dx).coerceIn(-127, 127)
        pendingMoveY = (pendingMoveY + dy).coerceIn(-127, 127)
        if (pendingMoveX == 0 && pendingMoveY == 0) return
        if (mouseMovePending) return
        mouseMovePending = true
        val sendX = pendingMoveX; pendingMoveX = 0
        val sendY = pendingMoveY; pendingMoveY = 0
        viewModelScope.launch {
            try { bleManager.sendMouseMove(sendX, sendY) }
            catch (_: Exception) { }
            finally { mouseMovePending = false }
        }
    }

    fun sendMouseScroll(vertical: Int, horizontal: Int) {
        pendingScrollV = (pendingScrollV + vertical).coerceIn(-127, 127)
        if (pendingScrollV == 0) return
        if (mouseScrollPending) return
        mouseScrollPending = true
        val sendV = pendingScrollV; pendingScrollV = 0
        viewModelScope.launch {
            try { bleManager.sendMouseScroll(sendV, 0) }
            catch (_: Exception) { }
            finally { mouseScrollPending = false }
        }
    }

    fun sendMouseButtonDown(buttons: Int) {
        try { bleManager.sendMouseButtonDown(buttons) } catch (_: Exception) { }
    }

    fun sendMouseButtonUp(buttons: Int) {
        try { bleManager.sendMouseButtonUp(buttons) } catch (_: Exception) { }
    }

    fun sendMouseClick(buttons: Int) {
        try { bleManager.sendMouseClick(buttons) } catch (_: Exception) { }
    }

    /** Toggle mouse support on the firmware. Device reboots; disconnect handler navigates away. */
    fun setMouseEnabled(enabled: Boolean) {
        val crypto = cryptoManager ?: return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                bleManager.setMouseEnabled(enabled, crypto)
                _statusMessage.value = "Mouse ${if (enabled) "enabled" else "disabled"}. Device is restarting…"
            } catch (e: Exception) {
                _statusMessage.value = "Mouse toggle error: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
