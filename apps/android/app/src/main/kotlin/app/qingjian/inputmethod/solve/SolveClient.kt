package app.qingjian.inputmethod.solve

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.cloud.ModelProfiles
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
     * 返回是否真的发出去了（一套配置都没配好返回 false）。
     *
     * **失败自动换下一套**：按 [modelChain] 的顺序逐个试，一套失败（HTTP 错误、超时、返回空）
     * 就用下一套，直到有用完或用户取消。一旦已经吐出过内容就不再切换——那会把两家的答案拼在一起。
     */
    fun ask(question: String, shot: Bitmap?, onDelta: (String) -> Unit, onDone: (answer: String?, error: String?) -> Unit): Boolean {
        val chain = modelChain()
        if (chain.isEmpty()) return false
        cancelled = false
        Thread {
            var lastError: String? = null
            var tried = 0
            for (target in chain) {
                if (cancelled) return@Thread
                tried++
                lastError = tryOne(target, question, shot, onDelta, onDone)
                if (lastError == null) return@Thread          // 成功，onDone 已经发过
                if (lastError == PARTIAL) {
                    // 已经吐了一半内容：当成成功收尾，别再换模型重来
                    return@Thread
                }
            }
            val tail = if (tried > 1) "（已试过 $tried 套配置）" else ""
            if (!cancelled) handler.post { onDone(null, (lastError ?: "没有收到答案") + tail) }
        }.start()
        return true
    }

    /** [tryOne] 的返回值：成功时是 null；[PARTIAL] 表示已经出了内容、不能再换。 */
    private val PARTIAL = "\u0000partial"

    /**
     * 用一套配置试一次，成功就直接回调 [onDone] 并返回 null；失败返回原因。
     * 中途已经收到增量的话，把半截答案当结果收尾并返回 [PARTIAL]（不切换下一个）。
     */
    private fun tryOne(
        target: Pair<CloudConfig, String>,
        question: String,
        shot: Bitmap?,
        onDelta: (String) -> Unit,
        onDone: (answer: String?, error: String?) -> Unit,
    ): String? {
        val (config, apiKey) = target
        val answer = StringBuilder()
        // 快速模式（关思考）：每次现读，用户改了设置立刻生效
        val fast = ModelProfiles.fastModeEnabled(context)
        return try {
            request(config, apiKey, buildBody(config.model, question, shot, fast)) { delta ->
                answer.append(delta)
                handler.post { if (!cancelled) onDelta(delta) }
            }
            val result = answer.toString().trim().ifEmpty { null }
            if (result == null) {
                "「${config.model}」没有返回内容"
            } else {
                if (!cancelled) handler.post { onDone(result, null) }
                null
            }
        } catch (e: InterruptedException) {
            null   // 用户取消，不算失败，也不再往下试
        } catch (e: Exception) {
            if (answer.isNotEmpty() && !cancelled) {
                // 已经吐了半截：把拿到的当答案，别让用户看到的内容凭空消失
                handler.post { onDone(answer.toString().trim(), null) }
                PARTIAL
            } else {
                "${config.model}：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * 尝试顺序交给 [ModelProfiles.chain]（当前选中排第一，其余按列表顺序；关掉开关就只试一套）。
     * 这里只负责把 profile 折成请求要的 `(CloudConfig, key)`。
     */
    private fun modelChain(): List<Pair<CloudConfig, String>> =
        ModelProfiles.chain(context).map { (profile, key) ->
            CloudConfig(profile.baseUrl, profile.model, key) to key
        }

    /**
     * 发一次请求。带了 `reasoning_effort` 却被网关拒（400）时，**去掉它再试一次**——
     * 有的 OpenAI 兼容层不认这个字段，宁可慢一点也不能让它把搜题弄挂。
     */
    private fun request(config: CloudConfig, apiKey: String, body: JSONObject, onDelta: (String) -> Unit) {
        android.util.Log.d(
            "QJSolve",
            "→ ${modelOf(body)} @${config.baseUrl} " +
                "reasoning_effort=${body.optString("reasoning_effort", "无")} " +
                "thinking=${if (body.has("thinking")) "disabled" else "无"}",
        )
        try {
            send(config, apiKey, body, onDelta)
        } catch (e: HttpFailure) {
            if (e.code == 400 && body.has("reasoning_effort")) {
                val retry = JSONObject(body.toString()).apply { remove("reasoning_effort") }
                send(config, apiKey, retry, onDelta)
            } else {
                throw e
            }
        }
    }

    /** 带 HTTP 状态码的失败，好让上面判断能不能降级重试。 */
    private class HttpFailure(val code: Int, message: String) : RuntimeException(message)

    private fun send(config: CloudConfig, apiKey: String, body: JSONObject, onDelta: (String) -> Unit) {
        val base = config.baseUrl.trimEnd('/')
        val call = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        this.call = call
        // 分段计时：能分清慢在「上传」「等首个响应」「等服务端吐字」还是「收尾」
        val sentAt = System.currentTimeMillis()
        call.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val uploadedAt = System.currentTimeMillis()
        val code = call.responseCode
        val headersAt = System.currentTimeMillis()
        if (code != 200) {
            val detail = runCatching { call.errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw HttpFailure(code, "HTTP $code${detail?.take(200)?.let { "：$it" }.orEmpty()}")
        }
        // **必须逐行读**：原来用 readText() 会阻塞到整个响应结束才返回，
        // 等于把流式退化成一次性——用户要干等到全部生成完才看到答案（这就是"出答案好慢"）。
        // readLine() 一有数据就返回，第一个字到了就能显示。
        var streamed = false
        var firstDeltaAt = 0L
        var firstChunkAt = 0L
        val wholePayload = StringBuilder()
        call.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                if (cancelled) throw InterruptedException()
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching { deltaOf(data) }.getOrNull().orEmpty()
                    if (delta.isNotEmpty()) {
                        if (!streamed) firstDeltaAt = System.currentTimeMillis()
                        streamed = true
                        onDelta(delta)
                    }
                } else if (!streamed) {
                    // 不是流式回包时先攒着（有些兼容网关不理 stream=true，整包发普通 JSON）
                    wholePayload.append(line)
                }
            }
        }
        if (!streamed) {
            val whole = runCatching {
                val message = JSONObject(wholePayload.toString())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                if (message == null || message.isNull("content")) "" else message.optString("content")
            }.getOrNull().orEmpty()
            if (whole.isNotEmpty()) onDelta(whole)
        }
        // 分段耗时：上传 / 等响应头 / 等首个数据 / 等到第一个字 / 全部读完
        val doneAt = System.currentTimeMillis()
        android.util.Log.d(
            "QJSolve",
            "${modelOf(body)} 上传${uploadedAt - sentAt}ms " +
                "响应头${headersAt - uploadedAt}ms " +
                "首个数据${(firstChunkAt.takeIf { it > 0 } ?: doneAt) - headersAt}ms " +
                "首个字${(firstDeltaAt.takeIf { it > 0 } ?: doneAt) - headersAt}ms " +
                "读完${doneAt - sentAt}ms streamed=$streamed",
        )
    }

    /** 从请求体里取模型名（只为日志好认）。 */
    private fun modelOf(body: JSONObject): String = body.optString("model")

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
    /** 组请求体：纯文字是普通 message；带截屏是 vision 的 content 数组。 */
    private fun buildBody(model: String, question: String, shot: Bitmap?, fast: Boolean): JSONObject {
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
            .apply {
                // 快速模式：让思考型模型别先想一大段再答。
                // 思考阶段服务端不吐正文，流式压不掉这段延迟。
                // 两种写法都带上——各家兼容层认的不一样：
                //   reasoning_effort 是 OpenAI 标准（商汤 SDK 示例也是它）
                //   thinking.type   是商汤/GLM/Claude 兼容层的写法
                // 网关不认的会回 400，request() 会逐个去掉重试。
                if (fast) {
                    put("reasoning_effort", "none")
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
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

        /** 连 TCP 最多等这么久：地址不通就别耗着。 */
        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * 两个数据块之间最多等这么久。流式输出时块间隔很短，只有「服务端憋着不说话」才会触发。
         *
         * 原来统一 90 秒：一次挂住就要等 90 秒才轮到下一套配置，三套就是 270 秒，
         * 故障转移等于没有。现在 30 秒还没吐第一个字就断开、换下一套。
         */
        const val READ_TIMEOUT_MS = 30_000
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
