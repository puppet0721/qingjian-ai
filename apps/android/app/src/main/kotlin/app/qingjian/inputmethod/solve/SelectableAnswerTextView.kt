package app.qingjian.inputmethod.solve

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * 能报告「选区变了」的 TextView。
 *
 * 为什么要这个类：`TextView` 没有公开的选区变化监听器，而悬浮窗里又不弹系统的文本选择工具栏
 * （`ActionMode` 在 `TYPE_APPLICATION_OVERLAY` 窗口里出不来），所以只能自己盯着选区、
 * 自己给操作条。系统唯一的钩子就是 `onSelectionChanged(selStart, selEnd)`，它是 protected，
 * 必须继承才能覆盖。
 *
 * **回调里只更新状态，别做重布局**：拖手柄时这个方法会被高频调用，在里面重建视图会闪。
 */
class SelectableAnswerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    /** 选区变了（[start]、[end] 相等表示没有选中）。 */
    var onSelection: ((start: Int, end: Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }
}
