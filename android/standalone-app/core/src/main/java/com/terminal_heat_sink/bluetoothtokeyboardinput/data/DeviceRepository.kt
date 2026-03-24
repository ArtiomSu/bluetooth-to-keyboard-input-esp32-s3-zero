package com.terminal_heat_sink.bluetoothtokeyboardinput.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants

/**
 * Persists saved devices using EncryptedSharedPreferences so PSKs are
 * never stored in plaintext on-device.
 */
class DeviceRepository(context: Context) {
    private val gson = Gson()

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bluetooth_input_devices",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val listType = object : TypeToken<List<DeviceConfig>>() {}.type

    fun getAll(): List<DeviceConfig> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return gson.fromJson(json, listType) ?: emptyList()
    }

    fun save(device: DeviceConfig) {
        val devices = getAll().toMutableList()
        val idx = devices.indexOfFirst { it.alias == device.alias }
        if (idx >= 0) devices[idx] = device else devices.add(device)
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
    }

    fun delete(alias: String) {
        val devices = getAll().filter { it.alias != alias }
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
    }

    fun getDefaultDevice(): DeviceConfig = DeviceConfig(
        alias = "default",
        bleName = BleConstants.DEFAULT_DEVICE_NAME,
        pskHex = BleConstants.DEFAULT_PSK_HEX,
    )

    /** Returns the stored "default" entry if the user has customised it, else the factory defaults. */
    fun getDefaultDeviceOrStored(): DeviceConfig =
        getAll().firstOrNull { it.alias == "default" } ?: getDefaultDevice()

    companion object {
        private const val KEY_DEVICES = "devices"
    }
}
