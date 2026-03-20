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
) {
    val pskBytes: ByteArray get() = pskHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
