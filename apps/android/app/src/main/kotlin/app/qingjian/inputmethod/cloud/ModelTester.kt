package app.qingjian.inputmethod.cloud

import app.qingjian.inputmethod.core.Native
import org.json.JSONObject

/**
 * 测一套配置通不通：交给 Rust 侧的 [Native.cloudTest]（与设置页那个「测试连接」同一套实现）。
 *
 * 为什么不用手写 HTTP：Rust 那边已经处理了各家网关的差异——比如思考型模型
 * （商汤 `sensenova-6.8-flash-lite`）在小 `max_tokens` 下正文为空、内容落在 reasoning 里，
 * 手写判「content 为空就失败」会误报。Rust 返回 `{"ok":bool,"message":"人话"}`，直接用它的结论。
 *
 * 结果在后台线程回调，UI 自己 post 回主线程。
 */
object ModelTester {
    sealed class Result {
        /** [message] 是 Rust 给的那句话，本身就带耗时，如「连上了：xxx，用时 2018 毫秒」。 */
        data class Ok(val message: String) : Result()
        data class Fail(val message: String) : Result()
    }

    fun test(baseUrl: String, model: String, apiKey: String, onResult: (Result) -> Unit) {
        Thread {
            val result = try {
                if (baseUrl.isBlank()) throw RuntimeException("地址为空")
                if (model.isBlank()) throw RuntimeException("模型名为空")
                if (apiKey.isBlank()) throw RuntimeException("还没填密钥")
                val payload = CloudConfig(baseUrl, model, apiKey).toJson()
                val raw = Native.cloudTest(payload) ?: throw RuntimeException("引擎没响应")
                val json = JSONObject(raw)
                val message = json.optString("message").ifBlank {
                    if (json.optBoolean("ok")) "连上了" else "测试失败"
                }
                if (json.optBoolean("ok")) Result.Ok(message) else Result.Fail(message)
            } catch (e: Throwable) {
                Result.Fail(e.message ?: e.javaClass.simpleName)
            }
            onResult(result)
        }.apply { isDaemon = true }.start()
    }
}
