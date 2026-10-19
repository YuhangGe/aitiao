package me.geekabe.aitiao

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志收集器
 *
 * 同时在 Logcat 输出和内存中保留，供日志页面展示。
 * 所有对 [entries] 的修改都切回主线程，避免 Compose 状态在后台线程被并发修改。
 */
object LogCollector {

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    private val nextId = AtomicLong(0)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val entries = mutableStateListOf<Entry>()

    fun i(tag: String, msg: String) = log("I", tag, msg)

    fun w(tag: String, msg: String) = log("W", tag, msg)

    fun e(tag: String, msg: String) = log("E", tag, msg)

    private fun log(level: String, tag: String, msg: String) {
        when (level) {
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
            else -> Log.i(tag, msg)
        }
        val entry = Entry(
            id = nextId.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            add(entry)
        } else {
            mainHandler.post { add(entry) }
        }
    }

    private fun add(entry: Entry) {
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }
}
