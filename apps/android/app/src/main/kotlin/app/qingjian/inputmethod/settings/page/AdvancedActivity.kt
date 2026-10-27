package app.qingjian.inputmethod.settings.page

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.Engine
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * 高级页（PC 设置程序的「高级」改成手机的样子）：不露文件路径，只有「导出数据」「导入数据」与「清空学习数据」。
 * 导出用系统的「存到哪里」（SAF `ACTION_CREATE_DOCUMENT`）存一个 zip，打包哪些文件由 Rust 侧定；
 * 导入用系统的文件选择（`ACTION_OPEN_DOCUMENT`）读回一个 zip，**覆盖**同名学习数据；
 * 清空问两次。PC 的配置文件编辑与输入日志开关不做。
 */
class AdvancedActivity : PageActivity() {
    override val title = R.string.page_advanced

    /** 正在写压缩包（后台线程），行上显示「导出中…」。 */
    private var exporting = false

    /** 正在读压缩包（后台线程），行上显示「导入中…」。 */
    private var importing = false

    override fun content(): List<View> = listOf(
        ui.sectionTitle(R.string.section_data),
        ui.card(
            ui.action(R.string.export_title, getString(if (exporting) R.string.export_running else R.string.export_value)) {
                if (!exporting && !importing) pickTarget()
            },
            ui.note(R.string.export_note),
            ui.divider(),
            ui.action(R.string.import_title, getString(if (importing) R.string.import_running else R.string.import_value)) {
                if (!exporting && !importing) pickImport()
            },
            ui.note(R.string.import_note),
            ui.divider(),
            ui.action(R.string.clear_learning_title, null, DANGER) { chooseParts() },
            ui.note(R.string.clear_learning_note),
        ),
    )

    private fun pickTarget() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "qingjian-data-${LocalDate.now()}.zip")
        startActivityForResult(intent, CREATE_ZIP)
    }

    /** 选一个备份 zip：`ACTION_OPEN_DOCUMENT` 不用申请存储权限，系统文件选择器会给临时读授权。 */
    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        startActivityForResult(intent, OPEN_ZIP)
    }

    @Deprecated("Activity 的老接口，这页没有别的结果要收")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = data?.data
        if (resultCode != RESULT_OK || target == null) return
        when (requestCode) {
            CREATE_ZIP -> export(target)
            OPEN_ZIP -> confirmImport(target)
        }
    }

    /** 导出：先落盘、再后台打包。 */
    private fun export(target: Uri) {
        Engine.flushAll()
        val files = Engine.learningFiles(this)
        exporting = true
        rebuild()
        thread(name = "qingjian-export") {
            val message = runCatching { writeZip(target, files) }
                .fold({ getString(R.string.export_done, it) }, { getString(R.string.export_failed) })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                exporting = false
                rebuild()
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 导入前先问一次：覆盖不可撤销。 */
    private fun confirmImport(target: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(R.string.import_confirm)
            .setPositiveButton(R.string.import_title) { _, _ -> startImport(target) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(DANGER)
    }

    /**
     * 导入：后台把 zip 读成字节，交 [Engine.importLearning] 覆盖写回（它自己负责卸引擎、写完重装）。
     * 结果种在 UI 线程收尾——重装引擎是异步的，这里不用等它。
     */
    private fun startImport(target: Uri) {
        importing = true
        rebuild()
        thread(name = "qingjian-import") {
            val outcome = runCatching {
                val bytes = contentResolver.openInputStream(target)?.use { it.readBytes() }
                    ?: error("读不了这个文件")
                Engine.importLearning(this, bytes)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                importing = false
                rebuild()
                val message = outcome.fold(
                    onSuccess = { result ->
                        when {
                            result.error != null -> getString(R.string.import_failed)
                            result.written == 0 -> getString(R.string.import_nothing)
                            else -> getString(R.string.import_done, result.written)
                        }
                    },
                    onFailure = { getString(R.string.import_failed) },
                )
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 文件平铺在压缩包根上；读不到的跳过。返回写进去几个。 */
    private fun writeZip(target: Uri, files: List<Pair<String, java.io.File>>): Int {
        val output = contentResolver.openOutputStream(target, "wt") ?: error("打不开导出位置")
        var written = 0
        ZipOutputStream(output).use { zip ->
            for ((name, file) in files) {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                zip.write(bytes)
                zip.closeEntry()
                written++
            }
        }
        return written
    }

    /** 第一次：勾清哪几样（缺省全勾）；第二次：确认不能恢复。 */
    private fun chooseParts() {
        val checked = booleanArrayOf(true, true)
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_learning_title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.clear_habits), getString(R.string.clear_vocabulary)), checked,
            ) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.clear_next) { _, _ ->
                if (checked.any { it }) confirm(checked[0], checked[1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(habits: Boolean, vocabulary: Boolean) {
        val parts = listOfNotNull(
            getString(R.string.clear_habits_short).takeIf { habits },
            getString(R.string.clear_vocabulary_short).takeIf { vocabulary },
        ).joinToString("和")
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_confirm, parts))
            .setPositiveButton(R.string.clear_do) { _, _ ->
                val removed = Engine.clearLearning(this, habits, vocabulary)
                val message = if (removed < 0) R.string.clear_failed else R.string.clear_done
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(DANGER)
    }

    private companion object {
        const val CREATE_ZIP = 1
        const val OPEN_ZIP = 2

        /** 清空这类不能撤销的操作用红字。 */
        const val DANGER = 0xFFE5484D.toInt()
    }
}
