package app.qingjian.inputmethod.solve

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜题历史：搜到答案就存一条（时间、题目、答案；截图搜题另存一张压缩图），JSON 放 filesDir。
 * 键盘侧写入、App 的搜题历史页读取（同一进程、同一份文件）；只留最近 [MAX] 条，纯 Kotlin 不动引擎。
 * 两次搜题间隔秒级，写读竞争顶多丢一条，不加锁。
 */
object SolveHistory {
    /** 一条搜题记录；[shot] 是截图文件名（filesDir/solve_shots/ 下），纯文字搜题为 null。 */
    data class Entry(val time: Long, val question: String, val answer: String, val shot: String?)

    private const val MAX = 100
    private const val ANSWER_MAX = 8000
    private const val QUESTION_MAX = 300
    private const val SHOT_WIDTH = 720

    private fun file(context: Context) = File(context.filesDir, "solve_history.json")
    private fun shotsDir(context: Context) = File(context.filesDir, "solve_shots")

    fun load(context: Context): List<Entry> = runCatching {
        val array = JSONArray(file(context).readText())
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Entry(
                item.getLong("time"),
                item.optString("q"),
                item.optString("a"),
                item.optString("shot").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    /** 写入放后台线程（搜题回调在主线程）；新的排最前，超 [MAX] 条丢最旧的。 */
    fun record(context: Context, question: String, answer: String, shot: Bitmap?) {
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val entry = JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("q", question.take(QUESTION_MAX))
                    .put("a", answer.take(ANSWER_MAX))
                    .put("shot", shot?.let { saveShot(appContext, it) }.orEmpty())
                val all = mutableListOf(entry)
                load(appContext).forEach { old ->
                    all += JSONObject()
                        .put("time", old.time)
                        .put("q", old.question)
                        .put("a", old.answer)
                        .put("shot", old.shot.orEmpty())
                }
                file(appContext).writeText(JSONArray(all.take(MAX)).toString())
            }
        }.start()
    }

    /** 清空记录与截图文件；页面上有确认框，这里直接删。 */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        Thread {
            runCatching {
                file(appContext).delete()
                shotsDir(appContext).deleteRecursively()
            }
        }.start()
    }

    fun shotFile(context: Context, entry: Entry): File? =
        entry.shot?.let { File(shotsDir(context), it).takeIf(File::exists) }

    /** 截图压成宽 [SHOT_WIDTH] 的 JPEG；原图只在内存里活这一次，不落盘。 */
    private fun saveShot(context: Context, shot: Bitmap): String {
        val scaled = if (shot.width <= SHOT_WIDTH) shot
        else Bitmap.createScaledBitmap(shot, SHOT_WIDTH, shot.height * SHOT_WIDTH / shot.width, true)
        val name = "shot_${System.currentTimeMillis()}.jpg"
        File(shotsDir(context).apply { mkdirs() }, name).outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        return name
    }
}
