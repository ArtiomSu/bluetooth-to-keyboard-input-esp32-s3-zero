package com.terminal_heat_sink.bluetoothtokeyboardinput.script

import android.content.Context
import java.io.File

class ScriptRepository(context: Context) {

    private val dir = File(context.filesDir, "scripts").also { it.mkdirs() }

    fun getAll(): List<String> =
        dir.listFiles { f -> f.extension == "txt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun load(name: String): String =
        try { File(dir, "$name.txt").readText() } catch (_: Exception) { "" }

    fun save(name: String, content: String) {
        File(dir, "$name.txt").writeText(content)
    }

    fun delete(name: String) {
        File(dir, "$name.txt").delete()
    }
}
