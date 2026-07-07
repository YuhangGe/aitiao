package me.geekabe.aitiao

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit


data class AdPageFingerprint(
    val viewIdFingerprint: String,
    val isAdPage: Boolean,
    val skipX: Int,
    val skipY: Int
)

/**
 * 广告页指纹缓存
 *
 * 持久化到 SharedPreferences（JSON 格式），按包名索引。
 */
object ViewFingerprintCache {

    private const val TAG = "FingerprintCache"
    private const val PREFS_NAME = "ad_fingerprints"

    // 内存缓存：key 是 packageName，value 是对应的指纹列表
    private val cache: MutableMap<String, MutableList<AdPageFingerprint>> = mutableMapOf()

    // ─── 指纹存取 ────────────────────────────────────────────────

    fun get(packageName: String, viewIdFingerprint: String, context: Context): AdPageFingerprint? {
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        return list.find { it.viewIdFingerprint == viewIdFingerprint }
    }

    fun getAll(packageName: String, context: Context): List<AdPageFingerprint> {
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        return list.toList()
    }

    fun put(packageName: String, viewIdFingerprint: String, fingerprint: AdPageFingerprint, context: Context) {
        val list = cache.getOrPut(packageName) {
            loadFromPrefs(packageName, context)
        }
        // 覆盖同 viewIdFingerprint 的旧记录
        list.removeAll { it.viewIdFingerprint == viewIdFingerprint }
        list.add(fingerprint)
        saveToPrefs(packageName, list, context)
    }

    fun remove(packageName: String, context: Context) {
        cache.remove(packageName)
        prefs(context).edit { remove(packageName) }
    }

    fun removeFingerprint(packageName: String, viewIdFingerprint: String, context: Context) {
        val list = cache[packageName] ?: return
        list.removeAll { it.viewIdFingerprint == viewIdFingerprint }
        saveToPrefs(packageName, list, context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从 SharedPreferences 加载 JSON 数组并反序列化 */
    private fun loadFromPrefs(packageName: String, context: Context): MutableList<AdPageFingerprint> {
        val jsonStr = prefs(context).getString(packageName, null) ?: return mutableListOf()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<AdPageFingerprint>()
            for (i in 0 until jsonArray.length()) {
                list.add(fingerprintFromJson(jsonArray.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "加载指纹失败: $packageName", e)
            mutableListOf()
        }
    }

    /** 将指纹列表序列化为 JSON 数组并写入 SharedPreferences */
    private fun saveToPrefs(packageName: String, list: List<AdPageFingerprint>, context: Context) {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(fingerprintToJson(it)) }
        prefs(context).edit { putString(packageName, jsonArray.toString()) }
    }

    // ─── 指纹构建 ────────────────────────────────────────────────

    fun fromNoAd(viewIdFingerprint: String): AdPageFingerprint {
        return AdPageFingerprint(viewIdFingerprint, false, 0, 0)
    }
    fun fromAd(viewIdFingerprint: String, skipX: Int, skipY: Int): AdPageFingerprint {
        return AdPageFingerprint(viewIdFingerprint, true, skipX, skipY)
    }

    // ─── 辅助方法 ────────────────────────────────────────────────

    fun collectViewIdFingerprint(node: AccessibilityNodeInfo): String {
        val viewIds = mutableListOf<String>()
        collectViewIds(node, viewIds)
        return viewIds.joinToString("-")
    }
    /** 递归收集前10个所有非空 resource-id */
    private fun collectViewIds(node: AccessibilityNodeInfo, ids: MutableList<String>): Boolean {
        val id = node.viewIdResourceName
        if (!id.isNullOrBlank() && id != "null") {
            ids.add(id)
            if (ids.size >= 10) return false
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!collectViewIds(child, ids)) {
                return false
            }
        }
        return true
    }

    // ─── JSON 序列化 ─────────────────────────────────────────────

    private fun fingerprintToJson(f: AdPageFingerprint): JSONObject {
        return JSONObject().apply {
            put("viewIdFingerprint", f.viewIdFingerprint)
            put("isAdPage", f.isAdPage)
            put("skipX", f.skipX)
            put("skipY", f.skipY)
        }
    }

    private fun fingerprintFromJson(json: JSONObject): AdPageFingerprint {
        return AdPageFingerprint(
            viewIdFingerprint = json.getString("viewIdFingerprint"),
            isAdPage = json.getBoolean("isAdPage"),
            skipX = json.getInt("skipX"),
            skipY = json.getInt("skipY")
        )
    }
}
