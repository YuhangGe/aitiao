package me.geekabe.aitiao

import android.content.Context
import android.content.SharedPreferences

/**
 * 大模型配置参数
 */
data class AiConfig(
    val modelId: String = "",
    val apiKey: String = "",
    val baseUrl: String = ""
)

/**
 * 大模型配置的持久化存储
 */
object AiSettings {

    private const val PREFS_NAME = "aitiao_settings"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存配置 */
    fun save(context: Context, config: AiConfig) {
        prefs(context).edit().apply {
            putString(KEY_MODEL_ID, config.modelId)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_BASE_URL, config.baseUrl)
            apply()
        }
    }

    /** 读取配置，未保存的字段回退到 BuildConfig 默认值（来自 .env 或 CI） */
    fun load(context: Context): AiConfig {
        val p = prefs(context)
        return AiConfig(
            modelId = p.getString(KEY_MODEL_ID, BuildConfig.AITIAO_MODEL_ID) ?: BuildConfig.AITIAO_MODEL_ID,
            apiKey = p.getString(KEY_API_KEY, BuildConfig.AITIAO_API_KEY) ?: BuildConfig.AITIAO_API_KEY,
            baseUrl = p.getString(KEY_BASE_URL, BuildConfig.AITIAO_BASE_URL) ?: BuildConfig.AITIAO_BASE_URL
        )
    }
}
