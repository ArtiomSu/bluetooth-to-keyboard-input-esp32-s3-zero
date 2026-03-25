package com.terminal_heat_sink.bluetoothtokeyboardinput.data

data class DeviceConfig(
    val alias: String,
    val bleName: String,
    val pskHex: String,
    val layout: String = "en-US",
    val targetOs: String = "other",
    val minHoldMs: Int = 20,
    val maxHoldMs: Int = 20,
    val minGapMs: Int = 20,
    val maxGapMs: Int = 20,
    val usbVid: Int = 0x303A,
    val usbPid: Int = 0x1001,
    val usbManufacturerName: String = "ArtiomSu",
    val usbSerialNumber: String = "",
    val firmwareVersion: String? = null,
) {
    val pskBytes: ByteArray get() = pskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
