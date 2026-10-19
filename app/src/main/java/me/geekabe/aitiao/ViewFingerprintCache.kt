package me.geekabe.aitiao

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class AdPageFingerprint(
    val fingerprint: String,
    val isAdPage: Boolean,
    val skipXRatio: Float,
    val skipYRatio: Float,
    val createdAt: Long,
    val lastUsedAt: Long,
    val versionCode: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    /** 这条缓存是否来自大模型确认（而不是猜测） */
    val confirmedByAi: Boolean
)

/**
 * 广告页指纹缓存
 *
 * - 指纹不再只看前 10 个 resource-id，而是活动类 + 有限节点遍历的多信号哈希
 * - 空指纹不读不写，避免整个应用共用一个 key
 * - 缓存带应用版本、屏幕尺寸、density 和时间戳，尺寸/版本变化时自动失效
 * - 正缓存 30 天，负缓存 1 天，每包最多 50 条
 *
 * 持久化到 SharedPreferences（JSON 格式），按包名索引。
 */
object ViewFingerprintCache {

    private const val TAG = "FingerprintCache"
    private const val PREFS_NAME = "ad_fingerprints"
    private const val SCHEMA_VERSION = 4
    private const val MAX_PER_PACKAGE = 50
    private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
    private const val MIN_SIGNALS = 3
    private const val MAX_SIGNALS = 40
    private const val MAX_DEPTH = 30

    private val DIGITS = Regex("\\d+")
    private val WHITESPACE = Regex("\\s+")
    private const val HEX = "0123456789abcdef"

    // 内存缓存：key 是 packageName，value 是对应的指纹列表
    private val cache: MutableMap<String, MutableList<AdPageFingerprint>> = mutableMapOf()

    // ─── 指纹存取 ────────────────────────────────────────────────

    fun get(packageName: String, fingerprint: String, context: Context): AdPageFingerprint? {
        if (fingerprint.isBlank()) return null
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        val found = list.find { it.fingerprint == fingerprint } ?: return null

        val dm = context.resources.displayMetrics
        val now = System.currentTimeMillis()
        if (!isFingerprintValid(
                found,
                now,
                appVersionCode(context, packageName),
                dm.widthPixels,
                dm.heightPixels,
                dm.densityDpi
            )
        ) {
            list.remove(found)
            saveToPrefs(packageName, list, context)
            return null
        }
        return found
    }

    fun getAll(packageName: String, context: Context): List<AdPageFingerprint> {
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        return list.toList()
    }

    fun put(
        packageName: String,
        fingerprint: String,
        isAdPage: Boolean,
        skipXRatio: Float,
        skipYRatio: Float,
        context: Context,
        confirmedByAi: Boolean
    ) {
        if (fingerprint.isBlank()) return

        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        // 覆盖同 fingerprint 的旧记录
        list.removeAll { it.fingerprint == fingerprint }

        val dm = context.resources.displayMetrics
        val now = System.currentTimeMillis()
        list.add(
            AdPageFingerprint(
                fingerprint = fingerprint,
                isAdPage = isAdPage,
                skipXRatio = skipXRatio.coerceIn(0f, 1f),
                skipYRatio = skipYRatio.coerceIn(0f, 1f),
                createdAt = now,
                lastUsedAt = now,
                versionCode = appVersionCode(context, packageName),
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                densityDpi = dm.densityDpi,
                confirmedByAi = confirmedByAi
            )
        )

        // 简单 LRU：超出上限时淘汰最久未写入的一条
        while (list.size > MAX_PER_PACKAGE) {
            val oldest = list.indices.minByOrNull { list[it].lastUsedAt } ?: 0
            list.removeAt(oldest)
        }
        saveToPrefs(packageName, list, context)
    }

    fun remove(packageName: String, context: Context) {
        cache.remove(packageName)
        prefs(context).edit { remove(packageName) }
    }

    fun removeFingerprint(packageName: String, fingerprint: String, context: Context) {
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        if (!list.removeAll { it.fingerprint == fingerprint }) return
        saveToPrefs(packageName, list, context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从 SharedPreferences 加载 JSON 数组并反序列化 */
    private fun loadFromPrefs(
        packageName: String,
        context: Context
    ): MutableList<AdPageFingerprint> {
        val jsonStr = prefs(context).getString(packageName, null)
            ?: return mutableListOf()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<AdPageFingerprint>()
            for (i in 0 until jsonArray.length()) {
                fingerprintFromJson(jsonArray.optJSONObject(i))?.let { list.add(it) }
            }
            // 防御性去重：旧数据或异常数据里可能出现重复 fingerprint
            list.distinctBy { it.fingerprint }.toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "加载指纹失败: $packageName", e)
            mutableListOf()
        }
    }

    /** 将指纹列表序列化为 JSON 数组并写入 SharedPreferences */
    private fun saveToPrefs(
        packageName: String,
        list: List<AdPageFingerprint>,
        context: Context
    ) {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(fingerprintToJson(it)) }
        prefs(context).edit { putString(packageName, jsonArray.toString()) }
    }

    // ─── 指纹构建 ────────────────────────────────────────────────

    /**
     * 收集页面指纹：屏幕尺寸/density + Activity 类 + 前 40 个有意义的节点的
     * class/id/text/desc/位置。
     *
     * 屏幕尺寸是指纹的一部分，所以横屏/竖屏、折叠态/展开态会得到不同的 key，
     * 不会拿一个方向的缓存坐标去点另一个方向。
     *
     * 对数字做归一化（"跳过 3" 与 "跳过 2" 视为同一个指纹），避免倒计时导致
     * "AI 返回时页面已变化"的误判。信号少于 3 个时返回空串，调用方不得缓存。
     */
    fun collectFingerprint(
        node: AccessibilityNodeInfo,
        activityClass: String?,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int
    ): String {
        val sb = StringBuilder(
            fingerprintHeader(activityClass, screenWidth, screenHeight, densityDpi)
        )
        var signals = 0

        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (signals >= MAX_SIGNALS || depth > MAX_DEPTH) return

            val id = current.viewIdResourceName
            val text = current.text?.toString()?.let(::normalize)
            val desc = current.contentDescription?.toString()?.let(::normalize)
            val cls = current.className?.toString().orEmpty()

            val hasId = !id.isNullOrBlank() && id != "null"
            if (hasId || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                val rect = Rect()
                current.getBoundsInScreen(rect)
                sb.append('|').append(cls).append(':').append(id).append(':')
                    .append(text).append(':').append(desc).append(':')
                    .append(rect.left).append(',').append(rect.top)
                signals++
            }

            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(node, 0)
        if (signals < MIN_SIGNALS) return ""
        return sha256(sb.toString()).take(16)
    }

    /** 指纹头：屏幕配置 + Activity，横竖屏/折叠态天然分成不同 key。 */
    internal fun fingerprintHeader(
        activityClass: String?,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int
    ): String = "${screenWidth}x${screenHeight}@${densityDpi}|${activityClass ?: ""}"

    private fun normalize(value: String): String =
        value.replace(DIGITS, "#")
            .replace(WHITESPACE, " ")
            .trim()
            .take(24)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            sb.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    // ─── 有效性判断 ──────────────────────────────────────────────

    /**
     * 指纹是否仍然有效：目标应用版本、屏幕尺寸、density 必须一致，且未超过 TTL。
     */
    internal fun isFingerprintValid(
        fingerprint: AdPageFingerprint,
        now: Long,
        versionCode: Long,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int
    ): Boolean {
        if (fingerprint.versionCode != versionCode) return false
        if (fingerprint.screenWidth != screenWidth ||
            fingerprint.screenHeight != screenHeight ||
            fingerprint.densityDpi != densityDpi
        ) {
            return false
        }
        if (fingerprint.createdAt <= 0L || fingerprint.createdAt > now) return false

        val ttl = if (fingerprint.isAdPage) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
        return now - fingerprint.createdAt <= ttl
    }

    private fun appVersionCode(context: Context, packageName: String): Long {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (e: Exception) {
            0L
        }
    }

    // ─── JSON 序列化 ─────────────────────────────────────────────

    private fun fingerprintToJson(f: AdPageFingerprint): JSONObject {
        return JSONObject().apply {
            put("schema", SCHEMA_VERSION)
            put("fingerprint", f.fingerprint)
            put("isAdPage", f.isAdPage)
            put("skipXRatio", f.skipXRatio.toDouble())
            put("skipYRatio", f.skipYRatio.toDouble())
            put("createdAt", f.createdAt)
            put("lastUsedAt", f.lastUsedAt)
            put("versionCode", f.versionCode)
            put("screenWidth", f.screenWidth)
            put("screenHeight", f.screenHeight)
            put("densityDpi", f.densityDpi)
            put("confirmedByAi", f.confirmedByAi)
        }
    }

    /** 旧版本（schema 不匹配或缺字段）的记录直接丢弃，下次重新识别。 */
    private fun fingerprintFromJson(json: JSONObject?): AdPageFingerprint? {
        if (json == null) return null
        if (json.optInt("schema", 0) != SCHEMA_VERSION) return null

        val fingerprint = json.optString("fingerprint")
            .ifBlank { json.optString("viewIdFingerprint") }
        if (fingerprint.isBlank()) return null

        val createdAt = json.optLong("createdAt", 0L)
        if (createdAt <= 0L) return null

        return AdPageFingerprint(
            fingerprint = fingerprint,
            isAdPage = json.optBoolean("isAdPage", false),
            skipXRatio = json.optDouble("skipXRatio", 0.0).toFloat(),
            skipYRatio = json.optDouble("skipYRatio", 0.0).toFloat(),
            createdAt = createdAt,
            lastUsedAt = json.optLong("lastUsedAt", createdAt),
            versionCode = json.optLong("versionCode", 0L),
            screenWidth = json.optInt("screenWidth", 0),
            screenHeight = json.optInt("screenHeight", 0),
            densityDpi = json.optInt("densityDpi", 0),
            confirmedByAi = json.optBoolean("confirmedByAi", false)
        )
    }
}
