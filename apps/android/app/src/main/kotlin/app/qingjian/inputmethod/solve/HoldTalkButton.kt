package app.qingjian.inputmethod.solve

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.AppColors
import app.qingjian.inputmethod.theme.Palette

/**
 * 「按住说」按钮：自绘，自己处理 DOWN/MOVE/UP。
 *
 * 用自绘 View 而不是 `TextView + setOnTouchListener`——后者在某些父容器里会被点击判定吃掉，
 * 按下去收不到 DOWN，长按说话就永远开不了。
 *
 * 从 [FloatingBallService] 里提出来（原来是内部类）：它只依赖主题色和三个回调，
 * 跟 Service 的状态没有关系，留在那个 1700 行的大文件里纯属碍事。
 */
class HoldTalkButton(context: Context, private val colors: AppColors) : View(context) {
    var onPress: () -> Unit = {}
    var onMove: (Float) -> Unit = {}
    var onRelease: (Boolean) -> Unit = {}

    private val ink = Ink(Palette.of(context), resources.displayMetrics.density)
    private val bg = RectF()
    private var downRawY = 0f
    private var downRawX = 0f
    private var moved = false

    /** 按下时按钮文字变「松手搜索」：由外面控制。 */
    var label: String = "按住说"
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (ink.measure("松手搜索", 14f, bold = true) + ink.dp(28f)).toInt()
        val h = ink.dp(38f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        bg.set(0f, 0f, width.toFloat(), height.toFloat())
        ink.round(canvas, bg, 12f, colors.brand)
        ink.text(canvas, label, 14f, colors.onBrand, width / 2f, height / 2f, bold = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                downRawX = event.rawX
                moved = false
                label = "松手搜索"
                invalidate()
                onPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (kotlin.math.abs(event.rawY - downRawY) > 6f || kotlin.math.abs(event.rawX - downRawX) > 6f)) {
                    moved = true
                }
                if (moved) onMove(event.rawY - downRawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                label = "按住说"
                invalidate()
                onRelease(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                label = "按住说"
                invalidate()
                onRelease(true)
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
