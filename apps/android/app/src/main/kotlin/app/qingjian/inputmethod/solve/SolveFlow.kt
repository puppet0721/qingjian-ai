package app.qingjian.inputmethod.solve

import android.content.Context
import android.graphics.Bitmap

/**
 * AI 搜题的流程：搜题条里的题目（可带截图）交给 [SolveClient] 发云端（OpenAI 兼容，与快捷翻译同一份配置），
 * 增量写回搜题条流式显示；搜到答案顺手记进 [SolveHistory]；[stop] 作废在飞的请求。
 * 答案不进输入框——搜题是查资料。
 */
class SolveFlow(private val context: Context, private val client: SolveClient) {

    /** 开着的搜题条；状态字与答案写到它上面。 */
    var bar: SolveBar? = null

    /** 已截屏待搜题。截屏授权那会儿搜题条会被收起，回来重开时接着用；收起搜题条就清。 */
    var shot: Bitmap? = null

    /** 题目发了：清空旧答案，开始等回调。 */
    fun ask(question: String) {
        stop()
        bar?.beginAsk()
        // 从发出到最后一个字收完的总耗时（含失败换下一套的时间）
        val startedAt = System.currentTimeMillis()
        val sent = client.ask(
            question, shot,
            onDelta = { delta -> bar?.appendAnswer(delta) },
            onDone = { result, error ->
                val elapsed = System.currentTimeMillis() - startedAt
                when {
                    error != null -> {
                        bar?.elapsedMs = null
                        bar?.fail("搜题失败：${error.take(40)}")
                    }
                    result.isNullOrEmpty() -> bar?.fail("没有收到答案")
                    else -> {
                        bar?.finishAsk(elapsed)
                        SolveHistory.record(context, question, result, shot)
                    }
                }
            },
        )
        if (!sent) bar?.fail("先在「键盘设置 → 云端大模型」里填好接口和密钥")
    }

    /** 作废在飞的请求。 */
    fun stop() = client.cancel()
}
