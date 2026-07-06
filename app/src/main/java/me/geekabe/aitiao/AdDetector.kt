package me.geekabe.aitiao

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * 广告识别结果
 */
data class AdResult(
    val isAd: Boolean,
    val skipX: Int = 0,
    val skipY: Int = 0,
    val error: String? = null
)

/**
 * 大模型广告识别客户端
 *
 * 使用腾讯云 TokenHub 的 OpenAI 兼容接口（Vision 模型）分析截图，
 * 判断是否为开屏广告页，并定位跳过按钮坐标。
 */
object AdDetector {

    private const val TIMEOUT_MS = 12_000

    /** 送给大模型的 system prompt */
    private val SYSTEM_PROMPT = """
你是一个手机开屏广告检测器。请严格分析这张手机截图：

1. 判断当前页面是否是开屏广告（splash ad）页面
2. 如果是开屏广告，找到"跳过"按钮的精确中心坐标，以相对位置表示（0.0 到 1.0 之间的小数，相对于截图的宽度和高度）

注意：
- 跳过按钮通常在屏幕右上角或右下角区域
- 按钮上可能有"跳过"、"skip"、"关闭"文字，或者有倒计时数字（如"跳过 3"）
- skip_x 范围 0.0~1.0，表示按钮在水平方向上的相对位置（0.0 最左，1.0 最右）
- skip_y 范围 0.0~1.0，表示按钮在垂直方向上的相对位置（0.0 最上，1.0 最下）

请严格只返回以下 JSON 格式，不要包含任何其他文字、代码块标记或解释：
{"is_ad": true/false, "skip_x": 0.0~1.0的小数, "skip_y": 0.0~1.0的小数}

如果无法确定，返回：
{"is_ad": false, "skip_x": 0.0, "skip_y": 0.0}
""".trimIndent()

    /**
     * 检测截图中是否包含开屏广告
     *
     * @param bitmap       屏幕截图（已缩放至 1dp=1px 的版本，用于上传）
     * @param screenWidth  实际屏幕物理像素宽度（用于将相对坐标转回绝对像素）
     * @param screenHeight 实际屏幕物理像素高度
     * @param config       大模型配置（modelId, apiKey, baseUrl）
     * @return AdResult 识别结果
     */
    fun detectAd(bitmap: Bitmap, screenWidth: Int, screenHeight: Int, config: AiConfig): AdResult {
        // 1. 将 Bitmap 压缩为 JPEG 并 Base64 编码
        val base64Image =
            bitmapToBase64(bitmap) ?: return AdResult(isAd = false, error = "截图编码失败")

        // 2. 检查配置完整性
        if (config.apiKey.isBlank()) {
            return AdResult(isAd = false, error = "API Key 未配置")
        }
        if (config.modelId.isBlank()) {
            return AdResult(isAd = false, error = "Model ID 未配置")
        }

        // 3. 构建 OpenAI Vision API 请求体
        val requestBody = buildRequestBody(config.modelId, base64Image)

        // 4. 发送请求
        val baseUrl = config.baseUrl.ifBlank { "https://tokenhub.tencentmaas.com/v1" }
        val apiUrl = "${baseUrl.trimEnd('/')}/chat/completions"
        val responseJson = postRequest(apiUrl, config.apiKey, requestBody)

        // 5. 解析响应（将相对坐标转为绝对像素坐标）
        return parseResponse(responseJson, screenWidth, screenHeight)
    }

    /** Bitmap → JPEG(quality=70) → Base64 字符串 */
    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val bytes = baos.toByteArray()
            baos.close()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** 构建 OpenAI Chat Completions 请求体 JSON */
    private fun buildRequestBody(modelId: String, base64Image: String): JSONObject {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                })
            })
        }

        val message = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }

        return JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply { put(message) })
            put("max_tokens", 512)
            put("temperature", 0.1)
        }
    }

    /** 发送 HTTP POST 请求，返回响应体字符串 */
    private fun postRequest(urlString: String, apiKey: String, body: JSONObject): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }

            // 写入请求体
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            // 读取响应
            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val responseText = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (responseCode !in 200..299) {
                android.util.Log.e("AdDetector", "API error $responseCode: $responseText")
                return null
            }

            responseText
        } catch (e: Exception) {
            android.util.Log.e("AdDetector", "Request failed: ${e.message}")
            null
        }
    }

    /** 从大模型响应中解析 AdResult，将相对坐标转为绝对像素坐标 */
    private fun parseResponse(responseJson: String?, screenWidth: Int, screenHeight: Int): AdResult {
        if (responseJson.isNullOrBlank()) {
            return AdResult(isAd = false, error = "大模型无响应")
        }

        return try {
            // 解析 OpenAI Chat Completions 响应
            val root = JSONObject(responseJson)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return AdResult(isAd = false, error = "响应中无 choices")
            }

            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content", "")
                ?: ""
            if (content.isBlank()) {
                return AdResult(isAd = false, error = "大模型返回空内容")
            }

            // 从内容中提取 JSON
            extractAdResultJson(content, screenWidth, screenHeight)
        } catch (e: Exception) {
            AdResult(isAd = false, error = "响应解析异常: ${e.message}")
        }
    }

    /**
     * 从大模型返回的文本中提取 JSON 并解析为 AdResult。
     *
     * 大模型可能返回：
     * - 纯 JSON: {"is_ad": true, ...}
     * - Markdown 代码块: ```json\n{...}\n```
     * - 带前后文字: 分析后返回 {...}
     */
    private fun extractAdResultJson(content: String, screenWidth: Int, screenHeight: Int): AdResult {
        // 策略1: 直接尝试将整段内容解析为 JSON
        try {
            val json = JSONObject(content.trim())
            return jsonToAdResult(json, screenWidth, screenHeight)
        } catch (_: Exception) {
            // 不是纯 JSON，继续尝试
        }

        // 策略2: 查找 markdown 代码块中的 JSON
        val codeBlockPattern = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        val codeBlockMatcher = codeBlockPattern.matcher(content)
        while (codeBlockMatcher.find()) {
            try {
                val json = JSONObject(codeBlockMatcher.group(1).trim())
                return jsonToAdResult(json, screenWidth, screenHeight)
            } catch (_: Exception) {
                // 继续尝试下一个
            }
        }

        // 策略3: 查找内容中所有 { 到 } 的 JSON 对象
        val jsonPattern = Pattern.compile("\\{[^{}]*\"is_ad\"\\s*:\\s*(?:true|false)[^}]*\\}")
        val jsonMatcher = jsonPattern.matcher(content)
        while (jsonMatcher.find()) {
            try {
                val json = JSONObject(jsonMatcher.group().trim())
                return jsonToAdResult(json, screenWidth, screenHeight)
            } catch (_: Exception) {
                // 继续尝试下一个
            }
        }

        return AdResult(isAd = false, error = "未能从响应中解析 JSON: ${content.take(200)}")
    }

    /** 将 JSON 对象转换为 AdResult，将相对坐标 (0.0~1.0) 转为绝对像素坐标 */
    private fun jsonToAdResult(json: JSONObject, screenWidth: Int, screenHeight: Int): AdResult {
        val isAd = json.optBoolean("is_ad", false)
        val relativeX = json.optDouble("skip_x", 0.0).toFloat()
        val relativeY = json.optDouble("skip_y", 0.0).toFloat()
        val skipX = (relativeX * screenWidth).toInt()
        val skipY = (relativeY * screenHeight).toInt()
        Log.i("XXX", "xxx $skipX, $skipY, $screenWidth, $screenHeight")
        return AdResult(isAd = isAd, skipX = skipX, skipY = skipY)
    }
}
