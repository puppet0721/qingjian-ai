package app.qingjian.inputmethod.candidates

import android.content.Context
import org.json.JSONObject

/**
 * emoji 联想表：中文词 -> 最多 2 个 emoji，随包在 `assets/data/emoji-map.json`。
 *
 * 只在壳里用（候选条尾部画芯片），不进引擎、不参与排序学习——纯粹是"打了这个词，
 * 顺手给你两个相关的表情"。表里有几百条常用单字与双字词，命中不到就不联想。
 * 懒加载：第一次用到才读，读完常驻。
 */
object EmojiMap {
    /** 最多给几个：候选条尾部塞两枚就到头了，再多会挤掉候选。 */
    const val MAX_SUGGESTIONS = 2

    private var table: Map<String, List<String>>? = null

    /** 读表；读不出来就退化成空表（不影响打字）。 */
    fun load(context: Context) {
        if (table != null) return
        table = runCatching {
            val json = context.assets.open("data/emoji-map.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            buildMap {
                for (key in root.keys()) {
                    // `_comment` 之类的说明字段不是词条
                    if (key.startsWith("_")) continue
                    val array = root.optJSONArray(key) ?: continue
                    val emojis = buildList {
                        for (i in 0 until array.length()) {
                            array.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                    if (emojis.isNotEmpty()) put(key, emojis.take(MAX_SUGGESTIONS))
                }
            }
        }.getOrElse { emptyMap() }
    }

    /**
     * 某个词联想出的 emoji（最多 [MAX_SUGGESTIONS] 个）。
     * [topCandidate] 是当前首选词：只有它命中才联想，别的候选不联想。
     */
    fun forWord(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        return table?.get(word) ?: emptyList()
    }
}
