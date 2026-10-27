package app.qingjian.inputmethod.solve

import android.content.Context
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.voice.EngineRecognizer
import app.qingjian.inputmethod.voice.VoiceRecognizer

/**
 * 语音问答的「听」这一段：优先用青简自己的识别方（[EngineRecognizer]，走「语音识别」页配的
 * 火山引擎等云端服务），没有才退到系统的 [SpeechRecognizer]。
 *
 * **为什么不能只用系统识别**：国内多数手机没有 Google 服务，`SpeechRecognizer.isRecognitionAvailable`
 * 直接返回 false，语音问答就废了。青简的引擎识别是自己接云端，不依赖系统。
 *
 * 与输入法内 [app.qingjian.inputmethod.voice.VoiceInput] 的区别：那边要复用引擎的上屏链路（边说边进输入框），
 * 这里只要"听出一句话"的结果，所以独立一份、走同一套 [VoiceRecognizer] 接口。
 */
class VoiceAsk(private val context: Context, private val engine: Engine?) {

    private var recognizer: VoiceRecognizer? = null
    private var system: SpeechRecognizer? = null
    private var stopped = false

    private companion object {
        /** 说完之后多久没新内容就自动收尾（引擎识别不会自己判断静音止）。 */
        const val AUTO_FINISH_MS = 2500L
    }

    /** 有引擎识别就用它；没有才看系统识别。 */
    fun available(): Boolean = engine?.voiceAvailable == true || SpeechRecognizer.isRecognitionAvailable(context)

    /** 现在走的是哪条：给提示文案用。 */
    fun usingSystem(): Boolean = engine?.voiceAvailable != true

    /**
     * 听一句话：说完回调 [onHeard]；[onState] 报过程（"正在听…"、部分结果），[onError] 报错。都在主线程。
     * [onFinishRequest] 由调用方在"该收尾了"时调（点完成 / 说完自动结束）。
     */
    fun listen(
        onState: (String) -> Unit,
        onHeard: (String) -> Unit,
        onError: (String) -> Unit,
        /** 需要"手动收尾"的识别方（引擎的要显式 finish）；系统识别自己会在静音后结束。 */
        onNeedsFinish: ((stop: () -> Unit) -> Unit)? = null,
    ) {
        cancel()
        stopped = false
        val engineAvailable = engine?.voiceAvailable == true
        if (engineAvailable) listenWithEngine(onState, onHeard, onError, onNeedsFinish)
        else listenWithSystem(onState, onHeard, onError)
    }

    // —— 引擎识别（推荐）——

    private fun listenWithEngine(
        onState: (String) -> Unit,
        onHeard: (String) -> Unit,
        onError: (String) -> Unit,
        onNeedsFinish: ((stop: () -> Unit) -> Unit)?,
    ) {
        val engine = engine ?: return
        val picked = EngineRecognizer(engine)
        recognizer = picked
        picked.start(object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) {
                if (!stopped && text.isNotEmpty()) {
                    // 还在说：把自动收尾的计时往后推
                    armAutoFinish()
                    onState(text)
                }
            }

            override fun onFinal(text: String) {
                if (stopped) return
                stopped = true
                recognizer = null
                if (text.isBlank()) onError("没听清，再说一次") else onHeard(text)
            }

            override fun onError(message: String) {
                if (stopped) return
                stopped = true
                recognizer = null
                onError(message)
            }

            override fun onLevel(level: Float) = Unit
        })
        onState("正在听…说完点「搜题」")
        // 引擎识别要显式收尾：把这个动作交给页面（用户点「搜题」时调）
        onNeedsFinish?.invoke { runCatching { picked.finish() } }
        // 另外自动收尾：引擎识别不会自己判断"说完了"，靠静音计时（每段部分结果重置）
        val autoFinish = Runnable {
            if (!stopped) runCatching { picked.finish() }
        }
        autoFinishHandle = autoFinish
        armAutoFinish()
    }

    /** 每收到一段部分结果就重置静音计时（页面调）。 */
    fun noteSpeech() = armAutoFinish()

    private var autoFinishHandle: Runnable? = null

    private fun armAutoFinish() {
        val handle = autoFinishHandle ?: return
        handler.removeCallbacks(handle)
        handler.postDelayed(handle, AUTO_FINISH_MS)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // —— 系统识别（兜底）——

    private fun listenWithSystem(
        onState: (String) -> Unit,
        onHeard: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("没有可用的语音识别：去「我的 → 语音识别」配一个（火山引擎），或用带语音服务的系统")
            return
        }
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        system = speech
        speech.setRecognitionListener(SystemListener(onState, onHeard, onError))
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { speech.startListening(intent) }.onFailure {
            stopped = true
            releaseSystem()
            onError("启动语音识别失败：${it.message?.take(40)}")
        }
    }

    /** 系统识别的回调：与 [android.speech.RecognitionListener] 一一对应。 */
    private inner class SystemListener(
        private val onState: (String) -> Unit,
        private val onHeard: (String) -> Unit,
        private val onError: (String) -> Unit,
    ) : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = onState("正在听…")
        override fun onBeginningOfSpeech() = onState("正在听…")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = onState("识别中…")

        override fun onError(error: Int) {
            if (stopped) return
            stopped = true
            onError(errorText(error))
            releaseSystem()
        }

        override fun onResults(results: android.os.Bundle?) {
            if (stopped) return
            stopped = true
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            releaseSystem()
            if (text.isEmpty()) onError("没听清，再说一次") else onHeard(text)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) onState(text)
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    /** 丢掉这一段，之后不再回调。 */
    fun cancel() {
        stopped = true
        autoFinishHandle?.let { handler.removeCallbacks(it) }
        autoFinishHandle = null
        runCatching { recognizer?.cancel() }
        recognizer = null
        releaseSystem()
    }

    private fun releaseSystem() {
        system?.let { runCatching { it.destroy() } }
        system = null
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络出错"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "识别失败（$error）"
    }
}
