package me.geekabe.aitiao

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * 应用内日志收集器
 *
 * 同时在 Logcat 输出和内存中保留，供日志页面展示。
 */
object LogCollector {

    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 500

    val entries = mutableStateListOf<Entry>()

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add(Entry(System.currentTimeMillis(), "I", tag, msg))
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        add(Entry(System.currentTimeMillis(), "W", tag, msg))
    }

    private fun add(entry: Entry) {
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }
}
