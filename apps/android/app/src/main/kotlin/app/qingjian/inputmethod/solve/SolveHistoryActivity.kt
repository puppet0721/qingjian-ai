package app.qingjian.inputmethod.solve

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.settings.page.PageActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 搜题历史页：按时间倒序列出键盘上搜过的题；点一条看全文与当时的截图，可复制答案，可整页清空。
 * 记录在键盘侧搜到答案时写入（[SolveHistory]），这里只读。
 */
class SolveHistoryActivity : PageActivity() {
    override val title = R.string.page_solve_history

    /** 异步读文件：首帧给「读取中」，读完 rebuild。null 是还没读到。 */
    private var entries: List<SolveHistory.Entry>? = null

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Thread {
            val loaded = SolveHistory.load(this)
            runOnUiThread {
                entries = loaded
                rebuild()
            }
        }.start()
    }

    override fun content(): List<View> {
        val loaded = entries ?: return listOf(spaced(ui.card(ui.row(getString(R.string.solve_history_loading), null))))
        if (loaded.isEmpty()) return listOf(spaced(ui.card(ui.note(R.string.solve_history_empty))))
        return buildList {
            add(spaced(clearCard(loaded.size)))
            loaded.forEach { add(entryCard(it)) }
        }
    }

    private fun clearCard(count: Int) = ui.card(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(4))
            addView(
                ui.text(getString(R.string.solve_history_count, count), 13.5f, ui.colors.secondary),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            addView(
                ui.text(getString(R.string.solve_history_clear), 14f, ui.colors.brand, bold = true).apply {
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(4), ui.dp(12))
                    setOnClickListener { confirmClear() }
                },
            )
        },
    )

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.solve_history_clear_confirm))
            .setPositiveButton(getString(R.string.solve_history_clear)) { _, _ ->
                SolveHistory.clear(this)
                entries = emptyList()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun entryCard(entry: SolveHistory.Entry) = spaced(
        ui.card(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16))
                addView(ui.text(formatTime(entry.time), 12f, ui.colors.secondary))
                addView(
                    ui.text(entry.question.ifEmpty { getString(R.string.solve_history_shot_only) }, 15f, ui.colors.text, bold = true)
                        .apply {
                            setPadding(0, ui.dp(6), 0, 0)
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                )
                addView(
                    ui.text(entry.answer, 13f, ui.colors.secondary).apply {
                        setPadding(0, ui.dp(8), 0, 0)
                        maxLines = 5
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
                SolveHistory.shotFile(this@SolveHistoryActivity, entry)?.let { file ->
                    addView(
                        ImageView(this@SolveHistoryActivity).apply {
                            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeFile(file.path, options)?.let { bitmap -> setImageBitmap(bitmap) }
                            adjustViewBounds = true
                            maxHeight = ui.dp(110)
                        },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(10) },
                    )
                }
                setOnClickListener { showDetail(entry) }
            },
        ),
    )

    /** 全文弹窗：题目、截图（有就放）、完整答案（可选中、可滚），顺手给个「复制答案」。 */
    private fun showDetail(entry: SolveHistory.Entry) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8))
            addView(sectionLabel(R.string.solve_history_question_label))
            addView(fullText(entry.question.ifEmpty { getString(R.string.solve_history_shot_only) }))
            SolveHistory.shotFile(this@SolveHistoryActivity, entry)?.let { file ->
                addView(
                    ImageView(this@SolveHistoryActivity).apply {
                        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeFile(file.path, options)?.let { bitmap -> setImageBitmap(bitmap) }
                        adjustViewBounds = true
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(12) },
                )
            }
            addView(sectionLabel(R.string.solve_history_answer_label).apply { setPadding(0, ui.dp(16), 0, 0) })
            addView(fullText(entry.answer))
        }
        AlertDialog.Builder(this)
            .setTitle(formatTime(entry.time))
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton(R.string.solve_history_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.solve_history_answer_label), entry.answer),
                )
                Toast.makeText(this, R.string.solve_history_copied, Toast.LENGTH_SHORT).show()
            }
            // 删单条：清空只能整份清，想删掉某一条不想要的题得能单独删
            .setNeutralButton(R.string.solve_history_delete) { _, _ -> confirmDelete(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 删之前问一句（删了就找不回来了）。 */
    private fun confirmDelete(entry: SolveHistory.Entry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.solve_history_delete_confirm)
            .setPositiveButton(R.string.solve_history_delete) { _, _ ->
                SolveHistory.delete(this, entry)
                // 乐观更新：先把这个从列表里拿掉，避免等文件写完再刷
                entries = entries?.filterNot { it.time == entry.time && it.question == entry.question }
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionLabel(text: Int) = ui.text(getString(text), 13f, ui.colors.brand, bold = true)

    private fun fullText(value: String) = ui.text(value, 14.5f, ui.colors.text).apply {
        setLineSpacing(0f, 1.25f)
        setTextIsSelectable(true)
    }

    private fun formatTime(time: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))
}
