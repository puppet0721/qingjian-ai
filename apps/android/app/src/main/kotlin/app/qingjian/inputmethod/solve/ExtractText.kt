package app.qingjian.inputmethod.solve

import android.content.Context
import android.graphics.Bitmap
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.cloud.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 文字提取（OCR）：把截图交给云端大模型的 vision 接口，只让它把画面里的文字原样抄出来，
 * 不解释、不总结。与搜题共用「云端大模型」那份配置，但走单独的提示词与请求（不走 SolveClient，
 * 免得两边的流式状态互相打断）。
 */
object ExtractText {
    /** 提取结果回调：[text] 是识别出的文字（可能为空），[error] 非 null 是失败原因。都在主线程。 */
    fun from(
        context: Context,
        shot: Bitmap,
        onDone: (text: String?, error: String?) -> Unit,
    ) {
        val config = CloudConfig.load(context)
        if (!config.ready) {
            onDone(null, "先在「键盘设置 → 云端大模型」里填好接口和密钥")
            return
        }
        val apiKey = SecretStore.load(context).orEmpty()
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", PROMPT))
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "把这张图里的文字提取出来。"))
                                .put(
                                    JSONObject().put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUrl(shot))),
                                ),
                        ),
                    ),
            )
            .put("stream", false)
            .put("max_tokens", 2048)
        Thread {
            var result: String? = null
            var failure: String? = null
            try {
                result = request(config.baseUrl, apiKey, body)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            val text = result
            val error = failure
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(text, error) }
        }.start()
    }

    private fun request(baseUrl: String, apiKey: String, body: JSONObject): String {
        val base = baseUrl.trimEnd('/')
        val call = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        call.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = call.responseCode
        if (code != 200) {
            val detail = runCatching { call.errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw RuntimeException("HTTP $code${detail?.take(200)?.let { "：$it" }.orEmpty()}")
        }
        val payload = BufferedReader(InputStreamReader(call.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        val message = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return if (message == null || message.isNull("content")) "" else message.optString("content").trim()
    }

    /** 截图压成 PNG 转 base64；太宽先缩到 1080。 */
    private fun dataUrl(shot: Bitmap): String {
        val scaled = if (shot.width <= MAX_WIDTH) shot
        else Bitmap.createScaledBitmap(shot, MAX_WIDTH, shot.height * MAX_WIDTH / shot.width, true)
        val png = java.io.ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.PNG, 0, it) }.toByteArray()
        return "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
    }

    private const val TIMEOUT_MS = 90_000
    private const val MAX_WIDTH = 1080

    /** 只抄字：不解释、不补全、不翻译。 */
    private const val PROMPT =
        "你是一个 OCR 文字提取工具。把图片里出现的所有文字原样提取出来，保持原有的换行与顺序，" +
            "不要翻译、不要解释、不要总结、不要加任何自己的话。只输出提取到的文字本身。" +
            "如果图里没有文字，只输出「（没有识别到文字）」。" +
            "忽略状态栏、输入法键盘、按钮等界面元素上的字。"
}
