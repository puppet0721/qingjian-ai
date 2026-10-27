package app.qingjian.inputmethod.core.strip

import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONObject

/**
 * Rust 渲染器画好的一帧候选条：位图 + 分界 + 可点区域。[candidatesTop] 以上是拼音行，壳钉在视口上；
 * [emoji] 里的候选渲染器画不出彩色 emoji（Android 13 起是 COLRv1），壳在 [wordRect] 上自己画。
 *
 * 这一帧每次敲键都要建一遍，所以命中区用数组 + 预建索引：
 * [candidateAt] / [wordRect] 原来每次遍历整个 List（wordRect 还嵌套 filter），候选多时是白花的开销。
 */
class StripImage(
    val bitmap: Bitmap,
    val candidatesTop: Int,
    /** 主题底色（ARGB），位图没铺满的地方用它填。 */
    val background: Int,
    val hits: List<StripHit>,
    val emoji: Set<Int>,
) {
    /** 只含「词格」的命中区（`sense == null`），按候选下标索引；[candidateAt] 只扫这些。 */
    private val cells: Array<StripHit> = hits.filter { it.sense == null }.toTypedArray()

    /** 候选下标 -> 它的词格矩形；[wordRect] 直接查，不再线性找。 */
    private val cellByCandidate: Array<StripHit?> by lazy {
        val max = (cells.maxOfOrNull { it.candidate } ?: -1) + 1
        if (max <= 0) return@lazy arrayOfNulls(0)
        arrayOfNulls<StripHit>(max).also { table ->
            for (cell in cells) {
                if (cell.candidate in table.indices && table[cell.candidate] == null) {
                    table[cell.candidate] = cell
                }
            }
        }
    }

    /** 候选下标 -> 该候选所有命中区里最小的 top（译文的顶边）；算一次存着，[wordRect] 直接查。 */
    private val noteTops: IntArray by lazy {
        val max = (hits.maxOfOrNull { it.candidate } ?: -1) + 1
        if (max <= 0) return@lazy IntArray(0)
        IntArray(max) { Int.MAX_VALUE }.also { tops ->
            for (hit in hits) {
                if (hit.sense == null) continue
                if (hit.candidate in tops.indices) {
                    val top = hit.rect.top.toInt()
                    if (top < tops[hit.candidate]) tops[hit.candidate] = top
                }
            }
        }
    }

    /** 横坐标 [x]（位图像素）落在哪个候选上，没有为 -1；不看纵坐标，按在拼音行上也算正下方那个候选。 */
    fun candidateAt(x: Float): Int {
        for (hit in cells) {
            if (x >= hit.rect.left && x < hit.rect.right) return hit.candidate
        }
        return -1
    }

    /** 第 [index] 个候选的词占的那块：整格去掉下面的译文。 */
    fun wordRect(index: Int): RectF? {
        val cell = cellByCandidate.getOrNull(index)?.rect ?: return null
        val top = noteTops.getOrNull(index)?.takeIf { it != Int.MAX_VALUE }?.toFloat() ?: cell.bottom
        return RectF(cell.left, cell.top, cell.right, top)
    }

    companion object {
        fun parse(meta: JSONObject, bitmap: Bitmap): StripImage {
            val hits = meta.getJSONArray("hits")
            val emoji = meta.getJSONArray("emoji")
            return StripImage(
                bitmap = bitmap,
                candidatesTop = meta.getInt("candidates_top"),
                background = meta.getLong("background").toInt(),
                hits = List(hits.length()) { i ->
                    val hit = hits.getJSONObject(i)
                    val x = hit.getDouble("x").toFloat()
                    val y = hit.getDouble("y").toFloat()
                    StripHit(
                        candidate = hit.getInt("candidate"),
                        sense = if (hit.isNull("sense")) null else hit.getInt("sense"),
                        rect = RectF(
                            x, y,
                            x + hit.getDouble("width").toFloat(),
                            y + hit.getDouble("height").toFloat(),
                        ),
                    )
                },
                emoji = List(emoji.length(), emoji::getInt).toSet(),
            )
        }
    }
}
