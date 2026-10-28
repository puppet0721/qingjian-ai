package app.qingjian.inputmethod.solve

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.Toast
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.editor.FloatingEditor
import app.qingjian.inputmethod.theme.Palette

/**
 * AI 搜题条：浮在键盘上方，上半是题目输入框（键盘打的字进这里，左边还有相机键），下半是答案区。
 * 「搜题」发起请求，答案流式追加、可上下滚动，Markdown 排成可读的样子；「收起」关闭。
 * 截屏搜题：相机键截一屏（[onShot]），缩略图摆进题目框（点掉重截），搜题时连同题目一起发云端。
 * 答案不进外面的输入框——搜题是查资料，不是替用户打字。
 */
@SuppressLint("ViewConstructor")
class SolveBar(
    context: Context,
    private val palette: Palette,
    private val onAsk: (String) -> Unit,
    private val onShot: () -> Unit,
    private val onClearShot: () -> Unit,
    private val onClose: () -> Unit,
) : android.view.View(context), FloatingEditor {
    private val ink = Ink(palette, resources.displayMetrics.density)
    private val text = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
    private val questionBox = RectF()
    private val answerBox = RectF()
    private val askButton = RectF()
    private val cameraButton = RectF()
    private val shotThumb = RectF()
    private val copyButton = RectF()
    private val answerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val questionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    /** 待搜的屏幕截图；null 是没有。缩略图点掉时清空。 */
    var shot: Bitmap? = null
        set(value) {
            field = value
            layout = null
            invalidate()
        }

    /** 状态字（搜题中…、失败了、密钥没配）；画在答案区。 */
    var status: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 这次搜题从发出到收完的耗时（毫秒）；没搜过或不显示为 null。 */
    var elapsedMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    /** [status] 是失败提示：红字。 */
    var failed = false
        set(value) {
            field = value
            invalidate()
        }

    /** 流式攒的答案原文（Markdown）。 */
    private val answerBuf = StringBuilder()

    /** 排好版的答案；流式期间攒 200ms 才重排一次，别每个字都排一遍。 */
    private var formatted: CharSequence = ""
    private var layout: StaticLayout? = null

    /** 答案区滚动位置（像素）。 */
    private var scroll = 0f
    private var lastFlushAt = 0L
    private var flushScheduled = false

    private var lastY = 0f
    private var dragging = false

    val asking: Boolean get() = status == ASKING

    override val enterLabel: String get() = "搜题"

    override val connection: InputConnection = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = text

        override fun commitText(value: CharSequence, newCursorPosition: Int): Boolean =
            super.commitText(value.take((MAX_QUESTION - text.length).coerceAtLeast(0)), newCursorPosition).also { changed() }

        override fun setComposingText(value: CharSequence, newCursorPosition: Int): Boolean =
            super.setComposingText(value, newCursorPosition).also { changed() }

        override fun finishComposingText(): Boolean = super.finishComposingText().also { changed() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { changed() }
    }

    /** Composer 不在组句时的键：删除删字，回车发起搜题。 */
    override fun onKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                val end = Selection.getSelectionEnd(text).coerceAtLeast(0)
                if (end > 0) text.delete(Character.offsetByCodePoints(text, end, -1), end)
                changed()
            }
            KeyEvent.KEYCODE_ENTER -> ask()
        }
    }

    private fun changed() {
        invalidate()
    }

    /** 回车或点搜题：只有截图没文字也行；搜题中再点按钮是收起（onTouchEvent 里走 [onClose]）。 */
    private fun ask() {
        if (asking) return
        val question = text.toString().trim()
        if (question.isEmpty() && shot == null) return
        onAsk(question)
    }

    /** 开始搜题：清旧答案、状态进「搜题中」。 */
    fun beginAsk() {
        status = ASKING
        failed = false
        answerBuf.setLength(0)
        formatted = ""
        scroll = 0f
        layout = null
        invalidate()
    }

    /** 流式追加一段答案；攒 [FLUSH_MS] 刷一次。 */
    fun appendAnswer(delta: String) {
        answerBuf.append(delta)
        val now = SystemClock.uptimeMillis()
        if (now - lastFlushAt >= FLUSH_MS) flush()
        else if (!flushScheduled) {
            flushScheduled = true
            postDelayed({
                flushScheduled = false
                flush()
            }, FLUSH_MS)
        }
    }

    /** 答案收完了：最后刷一次、状态收掉，并记下这次花了多久。 */
    fun finishAsk(elapsedMs: Long = 0L) {
        flush()
        status = null
        this.elapsedMs = elapsedMs.takeIf { it > 0 }
    }

    /** 搜题失败或没配好：红字状态。 */
    fun fail(message: String) {
        status = message
        failed = true
    }

    /** 提取文字中：答案区显示「正在识别文字…」。 */
    fun showExtracting() {
        status = EXTRACTING
        failed = false
        answerBuf.setLength(0)
        formatted = ""
        scroll = 0f
        layout = null
        invalidate()
    }

    /** 提取到的文字：原样铺进答案区（可滚动、可复制），不当作搜题答案。 */
    fun showExtracted(text: String) {
        status = null
        failed = false
        answerBuf.setLength(0)
        answerBuf.append(text)
        formatted = text
        scroll = 0f
        layout = null
        invalidate()
    }

    /** 语音问答：条里开听，听到的文字进题目框并发搜。 */
    fun startVoice() {
        if (asking) return
        // 键盘侧直接从服务拿引擎（同进程、同主线程），有就优先用青简自己的云端识别
        val engine = app.qingjian.inputmethod.service.QingjianInputMethodService.current?.engineForVoice()
        val asker = VoiceAsk(context, engine)
        status = if (asker.usingSystem()) "正在听…（系统识别）" else "正在听…说完点「搜题」"
        failed = false
        invalidate()
        asker.listen(
            onState = { state ->
                status = state
                invalidate()
            },
            onHeard = { heard ->
                // 识别结果替换题目框内容（语音问答是"重新问一句"），随后直接发搜
                text.clear()
                text.append(heard)
                Selection.setSelection(text, text.length)
                status = null
                invalidate()
                ask()
            },
            onError = { message ->
                status = message
                failed = true
                invalidate()
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            },
            onNeedsFinish = { finishVoice -> voiceFinish = finishVoice },
        )
    }

    /** 引擎识别要显式收尾（点「搜题」时调）。 */
    private var voiceFinish: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ink.dp(HEIGHT_DP).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout = null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.board)
        val pad = ink.dp(10f)
        // 上半：题目框
        val buttonWidth = ink.dp(64f)
        questionBox.set(pad, pad, width - pad, pad + ink.dp(QUESTION_DP))
        ink.round(canvas, questionBox, 14f, palette.surface)
        // 右上角按钮：搜题（没题没图时是「收起」，搜题中是「…」也当收起）
        askButton.set(questionBox.right - buttonWidth - ink.dp(8f), questionBox.top + ink.dp(11f), questionBox.right - ink.dp(8f), questionBox.top + ink.dp(47f))
        val label = when {
            asking -> "…"
            text.isEmpty() && shot == null -> "收起"
            else -> "搜题"
        }
        ink.round(canvas, askButton, 12f, if (asking) palette.functionalKey else palette.brand)
        ink.text(canvas, label, 15f, if (asking) palette.gloss else palette.onBrand, askButton.centerX(), askButton.centerY(), bold = true)

        // 左上角：没截图时是相机键（截屏搜题），截了图是缩略图（点掉重截）
        val image = shot
        var textLeft = questionBox.left + ink.dp(12f)
        if (image == null) {
            cameraButton.set(questionBox.left + ink.dp(6f), questionBox.centerY() - ink.dp(18f), questionBox.left + ink.dp(42f), questionBox.centerY() + ink.dp(18f))
            ink.icon(canvas, PanelIcons.Icon.CAMERA, cameraButton.centerX(), cameraButton.centerY(), 22f, palette.gloss)
            textLeft = cameraButton.right + ink.dp(10f)
        } else {
            val thumbHeight = ink.dp(42f)
            val thumbWidth = (thumbHeight * image.width / image.height).coerceIn(ink.dp(28f), ink.dp(76f))
            shotThumb.set(questionBox.left + ink.dp(6f), questionBox.centerY() - thumbHeight / 2, questionBox.left + ink.dp(6f) + thumbWidth, questionBox.centerY() + thumbHeight / 2)
            clip.rewind()
            clip.addRoundRect(shotThumb, ink.dp(8f), ink.dp(8f), Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(image, null, shotThumb, bitmapPaint)
            canvas.restore()
            val badgeX = shotThumb.right - ink.dp(2f)
            val badgeY = shotThumb.top + ink.dp(2f)
            ink.fill.color = palette.board
            canvas.drawCircle(badgeX, badgeY, ink.dp(7f), ink.fill)
            ink.line(canvas, badgeX - ink.dp(3f), badgeY - ink.dp(3f), badgeX + ink.dp(3f), badgeY + ink.dp(3f), palette.text)
            ink.line(canvas, badgeX - ink.dp(3f), badgeY + ink.dp(3f), badgeX + ink.dp(3f), badgeY - ink.dp(3f), palette.text)
            textLeft = shotThumb.right + ink.dp(10f)
        }

        // 题目文字（多行区域里竖排放不下就留末尾）
        val questionRight = askButton.left - ink.dp(8f)
        val questionText = text.toString()
        if (questionText.isEmpty()) {
            val placeholder = if (image == null) "输入题目，AI 给答案和解题步骤" else "已截屏，可直接搜题或补充文字"
            ink.textStart(canvas, placeholder, 14.5f, palette.faint, textLeft + ink.dp(2f), questionBox.centerY(), questionRight)
            drawCursor(canvas, textLeft, questionBox.centerY())
        } else {
            var shown = questionText
            val maxLines = 2
            var lines = StaticLayout.Builder
                .obtain(shown, 0, shown.length, questionPaint, (questionRight - textLeft).toInt().coerceAtLeast(1))
                .setMaxLines(maxLines)
                .build()
            if (lines.lineCount > maxLines || lines.getLineTop(maxLines.coerceAtMost(lines.lineCount)) > QUESTION_TEXT_DP * ink.density) {
                while (shown.length > 1) {
                    shown = "…" + shown.drop(1)
                    lines = StaticLayout.Builder
                        .obtain(shown, 0, shown.length, questionPaint, (questionRight - textLeft).toInt().coerceAtLeast(1))
                        .setMaxLines(maxLines)
                        .build()
                    if (lines.lineCount <= maxLines) break
                }
            }
            canvas.save()
            canvas.translate(textLeft, questionBox.top + ink.dp(8f))
            lines.draw(canvas)
            canvas.restore()
            drawCursor(
                canvas,
                textLeft + lines.getLineWidth(lines.lineCount - 1) + ink.dp(1f),
                (questionBox.top + ink.dp(8f) + (lines.getLineBottom(lines.lineCount - 1) + lines.getLineTop(lines.lineCount - 1)) / 2f),
            )
        }

        // 下半：答案区
        val answerTop = questionBox.bottom + ink.dp(6f)
        answerBox.set(pad, answerTop, width - pad, height - ink.dp(6f))
        ink.round(canvas, answerBox, 14f, palette.surface)
        canvas.save()
        canvas.clipRect(answerBox)
        val statusText = status
        when {
            formatted.isNotEmpty() -> {
                val paint = layout ?: buildAnswerLayout().also { layout = it }
                canvas.translate(answerBox.left + ink.dp(12f), answerBox.top + ink.dp(10f) - scroll)
                paint.draw(canvas)
            }
            asking -> {
                val dots = ".".repeat(((SystemClock.uptimeMillis() / 400) % 4).toInt())
                ink.text(canvas, "正在解题$dots", 13.5f, palette.gloss, answerBox.centerX(), answerBox.centerY())
                postInvalidateDelayed(400) // 让点点动起来；搜题结束就不再排这个分支
            }
            failed && statusText != null ->
                ink.textStart(canvas, statusText, 13f, FAILURE, answerBox.left + ink.dp(12f), answerBox.centerY(), answerBox.right - ink.dp(12f))
            statusText != null ->
                ink.textStart(canvas, statusText, 13f, palette.gloss, answerBox.left + ink.dp(12f), answerBox.centerY(), answerBox.right - ink.dp(12f))
            else -> ink.text(canvas, "答案会显示在这里", 13.5f, palette.faint, answerBox.centerX(), answerBox.centerY())
        }
        // 耗时：收完后在答案区左下角标一下（小字，压在空白处，不挡正文）
        if (formatted.isNotEmpty()) {
            elapsedMs?.let { ms ->
                val label = if (ms < 10_000) "用时 %.1f 秒".format(ms / 1000f) else "用时 %.0f 秒".format(ms / 1000f)
                ink.textStart(
                    canvas, label, 10f, palette.faint,
                    answerBox.left + ink.dp(12f), answerBox.bottom - ink.dp(9f), answerBox.width() - ink.dp(24f),
                )
            }
        }
        canvas.restore()
        // 复制：答案出来后摆在答案区右上角
        if (formatted.isNotEmpty()) {
            val copyWidth = ink.measure(COPY_LABEL, 12f)
            copyButton.set(answerBox.right - ink.dp(12f) - copyWidth - ink.dp(12f), answerBox.top + ink.dp(6f), answerBox.right - ink.dp(12f), answerBox.top + ink.dp(26f))
            ink.round(canvas, copyButton, 9f, palette.surface)
            ink.text(canvas, COPY_LABEL, 12f, palette.gloss, copyButton.centerX(), copyButton.centerY())
        }
    }

    private fun buildAnswerLayout(): StaticLayout {
        val width = (answerBox.width() - ink.dp(24f)).toInt().coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(formatted, 0, formatted.length, answerPaint, width)
            .build()
    }

    private fun maxScroll(): Float {
        val built = layout ?: return 0f
        return (built.height - answerBox.height() + ink.dp(20f)).coerceAtLeast(0f)
    }

    private fun drawCursor(canvas: Canvas, x: Float, cy: Float) {
        ink.fill.color = palette.brand
        canvas.drawRect(x, cy - ink.dp(9f), x + ink.dp(1.5f), cy + ink.dp(9f), ink.fill)
    }

    private fun flush() {
        lastFlushAt = SystemClock.uptimeMillis()
        // 一直在看底部就跟着长；用户往上翻过就不拽
        val atBottom = scroll >= maxScroll() - ink.dp(2f)
        formatted = MarkdownLite.format(answerBuf.toString())
        val fresh = buildAnswerLayout()
        layout = fresh
        if (atBottom) scroll = (fresh.height - answerBox.height() + ink.dp(20f)).coerceAtLeast(0f)
        invalidate()
    }

    private fun copyAnswer() {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("答案", answerBuf.toString()))
        Toast.makeText(context.applicationContext, "答案已复制", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                dragging = formatted.isNotEmpty() && answerBox.contains(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                scroll = (scroll - (event.y - lastY)).coerceIn(0f, maxScroll())
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                when {
                    askButton.contains(x, y) -> when {
                        asking -> onClose()
                        text.isEmpty() && shot == null -> onClose()
                        else -> ask()
                    }
                    shot != null && shotThumb.contains(x, y) -> {
                        shot = null
                        onClearShot()
                    }
                    cameraButton.contains(x, y) -> onShot()
                    formatted.isNotEmpty() && copyButton.contains(x, y) -> copyAnswer()
                }
            }
        }
        return true
    }

    private companion object {
        const val HEIGHT_DP = 132f
        const val QUESTION_DP = 58f
        const val QUESTION_TEXT_DP = 40f
        const val MAX_QUESTION = 300
        const val FLUSH_MS = 200L
        const val ASKING = "搜题中…"
        const val EXTRACTING = "正在识别文字…"
        const val COPY_LABEL = "复制"

        /** 失败提示的红字，与快捷翻译同一色。 */
        const val FAILURE = 0xFFD93B3B.toInt()
    }

    init {
        answerPaint.textSize = 14f * ink.density
        answerPaint.color = palette.text
        questionPaint.textSize = 14.5f * ink.density
        questionPaint.color = palette.text
    }
}
