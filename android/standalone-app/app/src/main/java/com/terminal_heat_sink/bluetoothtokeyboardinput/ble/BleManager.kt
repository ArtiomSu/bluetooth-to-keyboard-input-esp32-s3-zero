package com.terminal_heat_sink.bluetoothtokeyboardinput.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CCCD_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_DELAY_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_LAYOUT_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_OS_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_PROVISION_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_PUBKEY_SIG_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_PUBKEY_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_RAW_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_READY_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_TEXT_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_MOUSE_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.CHAR_MOUSE_EN_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.ECIES_OVERHEAD
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.EVENT_KEY_TAP
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.MOUSE_EVENT_BUTTON_CLICK
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.MOUSE_EVENT_BUTTON_DOWN
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.MOUSE_EVENT_BUTTON_UP
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.MOUSE_EVENT_MOVE
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.MOUSE_EVENT_SCROLL
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.EVENT_MOD_CLEAR
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.EVENT_MOD_DOWN
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.EVENT_MOD_UP
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.READY_TIMEOUT_MS
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.SERVICE_UUID
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants.TARGET_MTU
import com.terminal_heat_sink.bluetoothtokeyboardinput.crypto.CryptoManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class ScanResult(val devices: List<ScannedDevice>) : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class ScannedDevice(val name: String, val address: String, val device: BluetoothDevice)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var negotiatedMtu = 23   // BLE default; updated after requestMtu
    private var esp32PubkeyBytes: ByteArray? = null

    // Per-operation pending completions — only one BLE operation is in flight at a time
    private val opMutex = Mutex()
    private var pendingConnect     = CompletableDeferred<Unit>()
    private var pendingMtu         = CompletableDeferred<Int>()
    private var pendingServiceDisc = CompletableDeferred<Unit>()
    private var pendingRead        = CompletableDeferred<ByteArray>()
    private var pendingWrite       = CompletableDeferred<Unit>()

    // Completion counter channel for READY notifications from firmware
    private val readyChannel = Channel<Int>(Channel.UNLIMITED)
    private var lastCompletion = 0

    private val scannedDevices = mutableListOf<ScannedDevice>()

    // ── Scanning ─────────────────────────────────────────────────────────────

    fun startScan() {
        scannedDevices.clear()
        _connectionState.value = ConnectionState.Scanning
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth LE not available")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
    }

    fun stopScan() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.ScanResult(scannedDevices.toList())
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val name = result.device.name ?: return
            if (scannedDevices.none { it.address == result.device.address }) {
                scannedDevices.add(ScannedDevice(name, result.device.address, result.device))
                _connectionState.value = ConnectionState.ScanResult(scannedDevices.toList())
            }
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    suspend fun connect(device: BluetoothDevice, crypto: CryptoManager) {
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        try {
            pendingConnect = CompletableDeferred()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            withTimeout(20_000L) { pendingConnect.await() }
            // Services already discovered inside gattCallback
            requestMtuAndWait()
            // Request minimum connection interval (~7.5ms) for low-latency mouse input
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            subscribeToReady()
            val pubkey = readCharacteristic(CHAR_PUBKEY_UUID)
            val sig    = readCharacteristic(CHAR_PUBKEY_SIG_UUID)
            esp32PubkeyBytes = crypto.verifyPubkey(pubkey, sig)
            crypto.resetCounter()
            // Do NOT emit Connected here — the ViewModel calls markReady() after applying
            // all saved settings, so the UI only becomes interactive once fully configured.
        } catch (e: Exception) {
            closeGatt()
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            throw e
        }
    }

    /** Called by the ViewModel after all saved settings have been pushed to the device. */
    fun markReady() {
        _connectionState.value = ConnectionState.Connected
    }

    fun disconnect() {
        // Send the BLE disconnect request. The GATT callback's onConnectionStateChange
        // will fire with STATE_DISCONNECTED, at which point we call close() and clean up.
        // Calling close() here synchronously can prevent the disconnect from reaching the device.
        val g = gatt
        if (g != null) {
            g.disconnect()
            // close() is called in onConnectionStateChange when STATE_DISCONNECTED arrives.
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        esp32PubkeyBytes = null
        lastCompletion = 0
    }

    // ── MTU ───────────────────────────────────────────────────────────────────

    private suspend fun requestMtuAndWait() {
        pendingMtu = CompletableDeferred()
        gatt?.requestMtu(TARGET_MTU)
        negotiatedMtu = withTimeout(5_000L) { pendingMtu.await() }
    }

    // ── Service discovery ─────────────────────────────────────────────────────

    private suspend fun subscribeToReady() {
        val char = requireChar(CHAR_READY_UUID)
        gatt?.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID) ?: return
        pendingWrite = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(descriptor)
        }
        withTimeout(5_000L) { pendingWrite.await() }
    }

    // ── Read / Write helpers ──────────────────────────────────────────────────

    private suspend fun readCharacteristic(uuid: java.util.UUID): ByteArray {
        val char = requireChar(uuid)
        return opMutex.withLock {
            pendingRead = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.readCharacteristic(char)
            } else {
                @Suppress("DEPRECATION")
                gatt?.readCharacteristic(char)
            }
            withTimeout(10_000L) { pendingRead.await() }
        }
    }

    private suspend fun writeCharacteristic(uuid: java.util.UUID, value: ByteArray) {
        val char = requireChar(uuid)
        opMutex.withLock {
            pendingWrite = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(char)
            }
            withTimeout(10_000L) { pendingWrite.await() }
        }
    }

    private fun requireChar(uuid: java.util.UUID): BluetoothGattCharacteristic {
        val service = gatt?.getService(SERVICE_UUID)
            ?: throw IllegalStateException("BLE service not found. Are you connected?")
        return service.getCharacteristic(uuid)
            ?: throw IllegalStateException("Characteristic $uuid not found")
    }

    // ── Wait for READY notification ───────────────────────────────────────────

    private suspend fun waitForReady(targetCompletion: Int) {
        try {
            withTimeout(READY_TIMEOUT_MS) {
                while (lastCompletion < targetCompletion) {
                    val value = readyChannel.receive()
                    if (value > lastCompletion) lastCompletion = value
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("Timed out waiting for firmware to finish typing chunk $targetCompletion")
        }
    }

    // ── High-level send API ───────────────────────────────────────────────────

    suspend fun sendText(text: String, crypto: CryptoManager) {
        val pubkey = esp32PubkeyBytes ?: throw IllegalStateException("Not connected")
        val maxWrite = negotiatedMtu - 3
        val maxPlain = minOf(448, maxWrite - ECIES_OVERHEAD).coerceAtLeast(1)
        val raw = text.toByteArray(Charsets.UTF_8)
        val chunks = utf8Chunks(raw, maxPlain)
        val baseline = lastCompletion
        chunks.forEachIndexed { i, chunk ->
            val targetCompletion = baseline + i + 1
            val packet = crypto.encryptPayload(chunk, pubkey)
            writeCharacteristic(CHAR_TEXT_UUID, packet)
            waitForReady(targetCompletion)
        }
    }

    suspend fun sendKeyTap(hidKeycode: Int, crypto: CryptoManager) =
        sendRawEvent(byteArrayOf(EVENT_KEY_TAP, hidKeycode.toByte()), crypto)

    suspend fun sendModDown(modMask: Int, crypto: CryptoManager) =
        sendRawEvent(byteArrayOf(EVENT_MOD_DOWN, modMask.toByte()), crypto)

    suspend fun sendModUp(modMask: Int, crypto: CryptoManager) =
        sendRawEvent(byteArrayOf(EVENT_MOD_UP, modMask.toByte()), crypto)

    suspend fun sendModClear(crypto: CryptoManager) =
        sendRawEvent(byteArrayOf(EVENT_MOD_CLEAR), crypto)

    private suspend fun sendRawEvent(payload: ByteArray, crypto: CryptoManager) {
        val pubkey = esp32PubkeyBytes ?: throw IllegalStateException("Not connected")
        val targetCompletion = lastCompletion + 1
        val packet = crypto.encryptPayload(payload, pubkey)
        writeCharacteristic(CHAR_RAW_UUID, packet)
        waitForReady(targetCompletion)
    }

    suspend fun setLayout(layout: String, crypto: CryptoManager) {
        writeCharacteristic(CHAR_LAYOUT_UUID, crypto.hmacWrap(layout.toByteArray()))
    }

    suspend fun setOs(targetOs: String, crypto: CryptoManager) {
        writeCharacteristic(CHAR_OS_UUID, crypto.hmacWrap(targetOs.toByteArray()))
    }

    suspend fun setDelay(
        minHoldMs: Int, maxHoldMs: Int, minGapMs: Int, maxGapMs: Int,
        crypto: CryptoManager
    ) {
        val payload = crypto.buildDelayPayload(minHoldMs, maxHoldMs, minGapMs, maxGapMs)
        writeCharacteristic(CHAR_DELAY_UUID, crypto.hmacWrap(payload))
    }

    suspend fun provision(newBleName: String, newPsk: ByteArray, crypto: CryptoManager) {
        val pubkey = esp32PubkeyBytes ?: throw IllegalStateException("Not connected")
        val nameBytes = newBleName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..32) { "BLE name must be 1–32 bytes" }
        require(newPsk.size == 32) { "PSK must be 32 bytes" }
        // inner = [name_len:1][name:name_len][new_psk:32]
        val inner = ByteArray(1 + nameBytes.size + 32)
        inner[0] = nameBytes.size.toByte()
        nameBytes.copyInto(inner, 1)
        newPsk.copyInto(inner, 1 + nameBytes.size)
        // Wire format: ECIES_encrypt(counter + HMAC-SHA256(currentPSK, inner) + inner)
        val hmacWrapped = crypto.hmacWrap(inner)
        val packet = crypto.encryptPayload(hmacWrapped, pubkey)
        try {
            writeCharacteristic(CHAR_PROVISION_UUID, packet)
        } catch (_: Exception) {
            // The ESP32 calls esp_restart() before sending the GATT write response,
            // so the write will throw a disconnect error — this is expected and means success.
        }
    }

    /**
     * Factory-reset the device: sends ECIES_encrypt(hmac_wrap(b"\x00")).
     * name_len=0 signals the firmware to restore defaults and reboot.
     * The device will revert to its default BLE name and default PSK.
     */
    suspend fun factoryReset(crypto: CryptoManager) {
        val pubkey = esp32PubkeyBytes ?: throw IllegalStateException("Not connected")
        // inner = b"\x00"  (name_len = 0 → factory reset signal)
        val inner = byteArrayOf(0x00)
        val hmacWrapped = crypto.hmacWrap(inner)
        val packet = crypto.encryptPayload(hmacWrapped, pubkey)
        try {
            writeCharacteristic(CHAR_PROVISION_UUID, packet)
        } catch (_: Exception) {
            // ESP32 reboots before ACKing — expected.
        }
    }

    // ── Mouse (raw, unencrypted) ──────────────────────────────────────────────

    /**
     * Write-without-response for low-latency mouse events.
     * No ACK callback exists for WRITE_TYPE_NO_RESPONSE, so no mutex needed —
     * the GATT layer queues these internally and they do not conflict with
     * opMutex-guarded operations.
     */
    private fun writeCharacteristicNoResponse(uuid: java.util.UUID, value: ByteArray) {
        val char = requireChar(uuid)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(char)
        }
    }

    /** Read the mouse-enabled flag from the firmware (CHAR_MOUSE_EN_UUID). */
    suspend fun readMouseEnabled(): Boolean = try {
        val value = readCharacteristic(CHAR_MOUSE_EN_UUID)
        value.isNotEmpty() && value[0] != 0.toByte()
    } catch (_: Exception) {
        false  // Characteristic not found (old firmware) — default disabled
    }

    fun sendMouseMove(dx: Int, dy: Int) =
        writeCharacteristicNoResponse(
            CHAR_MOUSE_UUID,
            byteArrayOf(MOUSE_EVENT_MOVE.toByte(), dx.toByte(), dy.toByte())
        )

    fun sendMouseScroll(vertical: Int, horizontal: Int) =
        writeCharacteristicNoResponse(
            CHAR_MOUSE_UUID,
            byteArrayOf(MOUSE_EVENT_SCROLL.toByte(), vertical.toByte(), horizontal.toByte())
        )

    fun sendMouseButtonDown(buttons: Int) =
        writeCharacteristicNoResponse(
            CHAR_MOUSE_UUID,
            byteArrayOf(MOUSE_EVENT_BUTTON_DOWN.toByte(), buttons.toByte())
        )

    fun sendMouseButtonUp(buttons: Int) =
        writeCharacteristicNoResponse(
            CHAR_MOUSE_UUID,
            byteArrayOf(MOUSE_EVENT_BUTTON_UP.toByte(), buttons.toByte())
        )

    fun sendMouseClick(buttons: Int) =
        writeCharacteristicNoResponse(
            CHAR_MOUSE_UUID,
            byteArrayOf(MOUSE_EVENT_BUTTON_CLICK.toByte(), buttons.toByte())
        )

    /**
     * Authenticated toggle of mouse support on the firmware.
     * Firmware saves to NVS and calls esp_restart(), so the write ACK will never arrive.
     */
    suspend fun setMouseEnabled(enabled: Boolean, crypto: CryptoManager) {
        val payload = crypto.hmacWrap(byteArrayOf(if (enabled) 0x01 else 0x00))
        try {
            writeCharacteristic(CHAR_MOUSE_EN_UUID, payload)
        } catch (_: Exception) {
            // Device reboots before ACKing — expected.
        }
    }

    // ── UTF-8 safe chunking ───────────────────────────────────────────────────

    private fun utf8Chunks(data: ByteArray, maxBytes: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < data.size) {
            var end = (start + maxBytes).coerceAtMost(data.size)
            if (end < data.size) {
                // Walk back to a UTF-8 codepoint boundary (continuation bytes are 0x80–0xBF)
                while (end > start && (data[end].toInt() and 0xC0) == 0x80) end--
            }
            chunks.add(data.copyOfRange(start, end))
            start = end
        }
        return chunks
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                // This fires both on a clean disconnect() and on unexpected drops.
                closeGatt()
                if (!pendingConnect.isCompleted) {
                    pendingConnect.completeExceptionally(
                        RuntimeException("Connection failed with status $status")
                    )
                }
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingConnect.complete(Unit)
            } else {
                pendingConnect.completeExceptionally(
                    RuntimeException("Service discovery failed with status $status")
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingMtu.complete(mtu)
            } else {
                pendingMtu.complete(negotiatedMtu) // fall back to current value
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value ?: ByteArray(0)
            onCharacteristicReadCompat(status, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onCharacteristicReadCompat(status, value)
        }

        private fun onCharacteristicReadCompat(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingRead.complete(value)
            } else {
                pendingRead.completeExceptionally(RuntimeException("Read failed with status $status"))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite.complete(Unit)
            } else {
                pendingWrite.completeExceptionally(RuntimeException("Write failed with status $status"))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite.complete(Unit)
            } else {
                pendingWrite.completeExceptionally(RuntimeException("Descriptor write failed: $status"))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            onCharacteristicChangedCompat(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onCharacteristicChangedCompat(value)
        }

        private fun onCharacteristicChangedCompat(value: ByteArray) {
            if (value.isEmpty()) return
            val counter = value[0].toInt() and 0xFF
            if (counter > 0) {
                readyChannel.trySend(counter)
            }
        }
    }
}
