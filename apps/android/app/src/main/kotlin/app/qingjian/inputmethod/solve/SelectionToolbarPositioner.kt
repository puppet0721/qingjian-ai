package app.qingjian.inputmethod.solve

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 算选区在 TextView 内部的矩形（TextView 局部坐标）。
 *
 * 用 [android.text.Layout.getSelectionPath] 而不是只看起止点的水平位置：多行选区每一行宽度不同，
 * 路径的整体包围盒才是"选区覆盖的范围"。返回的坐标还要加上 TextView 的 padding 才是内容坐标。
 */
internal fun selectionRectInView(textView: TextView): RectF? {
    val start = textView.selectionStart
    val end = textView.selectionEnd
    if (start < 0 || end < 0 || start == end) return null
    val layout = textView.layout ?: return null
    val text = textView.text ?: return null
    val from = minOf(start, end)
    val to = maxOf(start, end)
    if (to > text.length) return null

    val path = Path()
    layout.getSelectionPath(from, to, path)
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (bounds.isEmpty) return null
    bounds.offset(
        textView.totalPaddingLeft.toFloat(),
        textView.extendedPaddingTop.toFloat(),
    )
    return bounds
}

/**
 * 把「复制选中 / 全选」那条操作条摆到选区旁边，别压着正文。
 *
 * 规则（按优先级）：
 * 1. 选区**上方**放得下就放上方（离选区 10dp，避开手指和选择手柄）；
 * 2. 上方不够放**下方**；
 * 3. 上下都不够（选区占了半屏以上）就贴到答案区底部——此时无论放哪都会压字，
 *    放底部至少不挡选区的起点。
 *
 * 移动只用 [View.setTranslationX] / [View.setTranslationY]，**绝不改 LayoutParams**：
 * 拖选择手柄时这个回调会高频触发，改布局参数会让整棵树重新测量，肉眼就是抖。
 * 另外位移小于 2dp 就不动，进一步压掉细微抖动。
 *
 * 操作条必须是 [overlayRoot] 的直接子 View 且左上角对齐（[android.view.Gravity.TOP] or START），
 * 否则 translation 会叠在原来的 gravity 偏移上，算出来的位置会偏。
 */
class SelectionToolbarPositioner(
    private val overlayRoot: FrameLayout,
    private val answerContainer: View,
    private val answerView: TextView,
    private val toolbar: View,
) {
    private val density = overlayRoot.resources.displayMetrics.density

    private fun dp(value: Int): Float = (value * density).roundToInt().toFloat()

    private val rootLoc = IntArray(2)
    private val textLoc = IntArray(2)
    private val visible = Rect()

    /** 选区变了就调一次（已 post 到下一帧）。 */
    fun updatePosition() {
        if (!answerView.isAttachedToWindow) return
        val selection = selectionRectInView(answerView) ?: return
        if (!answerContainer.getGlobalVisibleRect(visible)) return

        // 操作条平时是 GONE，GONE 的 View 不会被布局，也就拿不到宽高。
        // 这里按需量一次（只量不改布局参数），否则第一次选中永远算不出位置、条子不出现。
        if (toolbar.width <= 0 || toolbar.height <= 0) {
            toolbar.measure(
                View.MeasureSpec.makeMeasureSpec(overlayRoot.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        val tw = (if (toolbar.width > 0) toolbar.width else toolbar.measuredWidth).toFloat()
        val th = (if (toolbar.height > 0) toolbar.height else toolbar.measuredHeight).toFloat()
        if (tw <= 0f || th <= 0f) return

        overlayRoot.getLocationInWindow(rootLoc)
        answerView.getLocationInWindow(textLoc)

        // 答案区的可见范围（换算到 overlayRoot 的坐标系）
        val areaLeft = (visible.left - rootLoc[0]).toFloat()
        val areaTop = (visible.top - rootLoc[1]).toFloat()
        val areaRight = (visible.right - rootLoc[0]).toFloat()
        val areaBottom = (visible.bottom - rootLoc[1]).toFloat()

        // 选区在 overlayRoot 坐标系里的位置（TextView 局部 -> 窗口 -> overlayRoot）
        val selLeft = textLoc[0] - rootLoc[0] + selection.left
        val selTop = textLoc[1] - rootLoc[1] + selection.top
        val selBottom = textLoc[1] - rootLoc[1] + selection.bottom
        val selWidth = selection.width()

        val gap = dp(10)

        // 水平：居中于选区，再夹进答案区
        var x = selLeft + (selWidth - tw) / 2f
        x = x.coerceIn(areaLeft, (areaRight - tw).coerceAtLeast(areaLeft))

        // 垂直：上 -> 下 -> 底部兜底
        val above = selTop - th - gap
        val below = selBottom + gap
        val y = when {
            above >= areaTop -> above
            below + th <= areaBottom -> below
            else -> (areaBottom - th).coerceAtLeast(areaTop)
        }

        moveTo(x, y)
        if (toolbar.visibility != View.VISIBLE) toolbar.visibility = View.VISIBLE
    }

    /** 位移小于 [threshold] 就不动，省掉拖手柄时的无意义重绘。 */
    private fun moveTo(x: Float, y: Float) {
        val threshold = dp(2)
        if (abs(toolbar.translationX - x) > threshold) toolbar.translationX = x
        if (abs(toolbar.translationY - y) > threshold) toolbar.translationY = y
    }
}
