package app.qingjian.inputmethod.solve

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.cloud.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * AI 搜题的云端问答：用「云端大模型」同一份配置（[CloudConfig]，OpenAI 兼容接口），
 * 把题目（文字，或文字加截屏）发出去，流式回收一段 Markdown 答案。
 * 截图走 OpenAI 兼容的 vision 格式（image_url 带 base64 data URL），能不能解图取决于配的模型认不认图。
 * 请求在后台线程，增量与结果经主线程 [Handler] 回调；[cancel] 作废在飞的请求（回调不再发）。
 */
class SolveClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    /** 在飞的请求；[cancel] 后回调作废。 */
    private var call: HttpURLConnection? = null
    @Volatile private var cancelled = false

    /**
     * 发起搜题。[shot] 是截屏（可为 null，纯文字搜题）。
     * 每段增量调 [onDelta]、收尾调 [onDone]（都在主线程；[error] 为 null 是成功）。
     * 返回是否真的发出去了（云端没配好返回 false）。
     */
    fun ask(question: String, shot: Bitmap?, onDelta: (String) -> Unit, onDone: (answer: String?, error: String?) -> Unit): Boolean {
        val config = CloudConfig.load(context)
        if (!config.ready) return false
        cancelled = false
        val apiKey = SecretStore.load(context).orEmpty()
        val body = buildBody(config.model, question, shot)
        Thread {
            val answer = StringBuilder()
            var failure: String? = null
            try {
                request(config, apiKey, body) { delta ->
                    answer.append(delta)
                    handler.post { if (!cancelled) onDelta(delta) }
                }
            } catch (e: Exception) {
                if (!cancelled) failure = e.message ?: e.javaClass.simpleName
            }
            val result = if (failure == null) answer.toString().trim().ifEmpty { null } else null
            if (!cancelled) handler.post { onDone(result, failure) }
        }.start()
        return true
    }

    private fun request(config: CloudConfig, apiKey: String, body: JSONObject, onDelta: (String) -> Unit) {
        val base = config.baseUrl.trimEnd('/')
        val call = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        this.call = call
        call.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = call.responseCode
        if (code != 200) {
            val detail = runCatching { call.errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw RuntimeException("HTTP $code${detail?.take(200)?.let { "：$it" }.orEmpty()}")
        }
        val payload = call.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        // 流式回包：一行一个 data: {...}，取增量拼答案
        var streamed = false
        for (line in payload.lines()) {
            if (cancelled) throw InterruptedException()
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val delta = runCatching { deltaOf(data) }.getOrNull().orEmpty()
            if (delta.isNotEmpty()) {
                streamed = true
                onDelta(delta)
            }
        }
        if (!streamed) {
            // 有些兼容网关不理 stream=true，回的是普通 JSON：按整包解一次
            val whole = runCatching {
                val message = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                if (message == null || message.isNull("content")) "" else message.optString("content")
            }.getOrNull().orEmpty()
            if (whole.isNotEmpty()) onDelta(whole)
        }
    }

    /**
     * 一段流式增量里的文本。content 是显式 null（很多网关先发一块 role-only 的）时返回空串：
     * org.json 的 optString 会把 JSON null 读成字面量 "null"，必须先 isNull 拦掉。
     */
    private fun deltaOf(data: String): String {
        val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        if (delta == null || delta.isNull("content")) return ""
        return delta.optString("content")
    }

    /** 组请求体：纯文字是普通 message；带截屏是 vision 的 content 数组。 */
    private fun buildBody(model: String, question: String, shot: Bitmap?): JSONObject {
        val user = JSONObject().put("role", "user")
        if (shot == null) {
            user.put("content", question)
        } else {
            user.put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", question.ifBlank { "请解答图中的题目" }))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(shot)))),
            )
        }
        return JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", SYSTEM_PROMPT),
                ).put(user),
            )
            .put("stream", true)
            .put("max_tokens", 2048)
    }

    /** 截图压成 PNG 转 base64；太宽的先缩到 1080，别把 base64 撑得没边。 */
    private fun dataUrl(shot: Bitmap): String {
        val scaled = if (shot.width <= MAX_SHOT_WIDTH) shot
        else Bitmap.createScaledBitmap(shot, MAX_SHOT_WIDTH, shot.height * MAX_SHOT_WIDTH / shot.width, true)
        val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 0, it) }.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
    }

    /** 作废在飞的请求；之后的回调不再发。 */
    fun cancel() {
        cancelled = true
        call?.disconnect()
        call = null
    }

    private companion object {
        const val TIMEOUT_MS = 90_000
        const val MAX_SHOT_WIDTH = 1080

        /** 解题要求：给答案与步骤，别啰嗦；截图里的键盘、状态栏别当题目。 */
        const val SYSTEM_PROMPT =
            "你是一个解题助手。用户发来一道题目（文字，或是一张屏幕截图），请直接给出解答：" +
                "先一行给出最终答案，再分步写出简要的解题过程。格式用 Markdown，公式尽量写 plain text。" +
                "如果是截图，题目通常在画面上部，忽略状态栏、输入法、对话框等与题目无关的界面元素。" +
                "如果题目是选择题，指出选哪一项并说明理由。如果题目信息不全或有错，说明缺什么。" +
                "不要写与题目无关的内容。"
    }
}
