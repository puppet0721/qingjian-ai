package app.qingjian.inputmethod.solve

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette

/**
 * 按住说话时的浮层提示（微信那套）：浮在页面中间偏上，显示"松手搜索"与实时识别到的文字；
 * **手指下滑**超过 [CANCEL_DISTANCE] 就进入"松开取消"，✕ 亮起来。
 *
 * 它不是独立窗口，而是挂在搜题页里的一个覆盖层（[View]），由搜题页按手指位置摆。
 * 触摸事件不在这里收——按住的手指在按钮上，移动与抬起仍归按钮处理（[update] 只改状态）。
 */
@SuppressLint("ViewConstructor")
class VoiceHoldOverlay(context: Context, private val palette: Palette) : View(context) {
    private val ink = Ink(palette, resources.displayMetrics.density)
    private val bubble = RectF()

    /** 实时识别到的文字（可能还是空的）。 */
    var text: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 手指已经下滑到取消区：✕ 亮起、提示变"松开取消"。 */
    var cancelling = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 说完了正在等结果（松手之后、出答案之前）。 */
    var recognizing = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ink.dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        // 整块圆角气泡：底色比页面深一点，压住下面的答案区
        bubble.set(ink.dp(16f), ink.dp(8f), width - ink.dp(16f), height - ink.dp(8f))
        ink.round(canvas, bubble, 16f, if (cancelling) CANCEL_BG else palette.surface)

        // 左边：话筒 / ✕
        val iconCx = bubble.left + ink.dp(36f)
        if (cancelling) {
            ink.fill.color = CANCEL_RED
            canvas.drawCircle(iconCx, cy, ink.dp(20f), ink.fill)
            ink.icon(canvas, app.qingjian.inputmethod.panel.base.PanelIcons.Icon.CLEAR, iconCx, cy, 22f, 0xFFFFFFFF.toInt())
        } else {
            ink.fill.color = palette.brand
            canvas.drawCircle(iconCx, cy, ink.dp(20f), ink.fill)
            ink.icon(canvas, app.qingjian.inputmethod.panel.base.PanelIcons.Icon.MIC, iconCx, cy, 22f, palette.onBrand)
        }

        // 右边：状态 + 实时文字
        val left = iconCx + ink.dp(30f)
        val right = bubble.right - ink.dp(14f)
        val title = when {
            recognizing -> "识别中…"
            cancelling -> "松开取消"
            text.isEmpty() -> "松手搜索"
            else -> "松手搜索"
        }
        ink.textStart(canvas, title, 15f, if (cancelling) CANCEL_RED else palette.text, left, cy - ink.dp(10f), right)
        val hint = when {
            recognizing -> "正在识别你说的话"
            cancelling -> "手指移回按钮可继续"
            text.isEmpty() -> "说出你的问题"
            else -> text
        }
        ink.textStart(canvas, hint, 13f, palette.gloss, left, cy + ink.dp(12f), right)
    }

    companion object {
        const val HEIGHT_DP = 74f

        /** 手指下滑超过这个距离就进入取消态（像素，按 density 换算前）。 */
        const val CANCEL_DISTANCE_DP = 90f

        /** 取消态的气泡底与红字。 */
        const val CANCEL_BG = 0x33E5484D
        const val CANCEL_RED = 0xFFE5484D.toInt()
    }
}
