package app.qingjian.inputmethod.solve

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 大模型回答的轻 Markdown 排版：标题加粗放大、列表加圆点、**粗体** 与 `等宽`、分割线画短横。
 * 没配对上的记号原样保留（数学式子里的乘号别吞），表格这类复杂语法按原样显示。
 * 搜题条与悬浮搜题页共用这一份。
 */
object MarkdownLite {
    private val HEADER = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^[-*+]\\s+(.+)$")
    private val DIVIDER = Regex("^[-*_]{3,}$")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    fun format(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        for (raw in markdown.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                out.append('\n')
                continue
            }
            val start = out.length
            val header = HEADER.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                DIVIDER.matches(line) -> out.append("──────")
                header != null -> {
                    appendInline(out, header.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val scale = when (header.groupValues[1].length) {
                        1 -> 1.3f
                        2 -> 1.18f
                        else -> 1.08f
                    }
                    out.setSpan(RelativeSizeSpan(scale), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet != null -> {
                    out.append("• ")
                    appendInline(out, bullet.groupValues[1])
                }
                else -> appendInline(out, line)
            }
            out.append('\n')
        }
        return out
    }

    /** 行内语法：**粗体** 与 `等宽`。 */
    private fun appendInline(out: SpannableStringBuilder, value: String) {
        var last = 0
        for (hit in INLINE.findAll(value)) {
            out.append(value, last, hit.range.first)
            val bold = hit.groupValues[1]
            val code = hit.groupValues[2]
            val pieceStart = out.length
            out.append(if (bold.isNotEmpty()) bold else code)
            if (bold.isNotEmpty()) out.setSpan(StyleSpan(Typeface.BOLD), pieceStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else out.setSpan(TypefaceSpan("monospace"), pieceStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = hit.range.last + 1
        }
        out.append(value, last, value.length)
    }
}
