package app.qingjian.inputmethod.core

import android.content.Context
import java.io.File

/**
 * 随包数据从 APK assets 解到私有目录：`.qj` 要 mmap，必须是真文件。装了新包才重解。
 *
 * 按需解压：六种学习语言的释义表（`glossary-*.qj`，共约 65 MB）只解当前在用的那一张，
 * 其余的等用户真切换学习语言时再解（[ensureGlossary]）。数据区因此从约 199 MB 降到约 149 MB。
 */
internal object BundledData {
    private const val ASSET_DIR = "data"

    /** 解出来的目录；App 页面只读里面的小表（等级表），没解过就当没有。 */
    fun dir(context: Context) = File(context.filesDir, "data")

    /**
     * 必需的随包数据 + 当前学习语言的释义表。
     * 装过新包（lastUpdateTime 变了）就把整目录重来一遍，因为用户可能换了学习语言。
     */
    fun ensure(context: Context): File {
        val target = dir(context)
        val stamp = File(target, ".stamp")
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return target

        target.deleteRecursively()
        copy(context, ASSET_DIR, target)
        ensureGlossary(context, appLanguage(context))
        stamp.writeText(version)
        return target
    }

    /**
     * 把某门学习语言的释义表解出来（已存在就直接返回）。
     * 用户在设置里换学习语言时调，那时才需要这张表。
     */
    fun ensureGlossary(context: Context, language: String): File? {
        val name = "glossary-$language.qj"
        val target = File(dir(context), name)
        if (target.isFile) return target
        val asset = "$ASSET_DIR/$name"
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.getOrNull()
    }

    /** 当前学习语言的代码（en / ja ...）；读不到就当英语。 */
    private fun appLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("learning_language", null)?.takeIf { it.isNotBlank() } ?: "en"
    }

    /** 释义表不进首轮解压（走 ensureGlossary 按语言解），其余都要。 */
    private fun isAlwaysNeeded(name: String): Boolean =
        !(name.startsWith("glossary-") && name.endsWith(".qj"))

    /** assets 里列得出子项的是目录（领域词库在 dicts/ 下），递归拷。 */
    private fun copy(context: Context, asset: String, target: File) {
        target.mkdirs()
        for (name in context.assets.list(asset).orEmpty()) {
            val path = "$asset/$name"
            if (context.assets.list(path).orEmpty().isNotEmpty()) {
                copy(context, path, File(target, name))
                continue
            }
            if (!isAlwaysNeeded(name)) continue
            context.assets.open(path).use { input ->
                File(target, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
