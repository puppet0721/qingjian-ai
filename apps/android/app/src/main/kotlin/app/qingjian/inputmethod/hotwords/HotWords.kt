package app.qingjian.inputmethod.hotwords

import android.content.Context
import app.qingjian.inputmethod.core.BundledData
import app.qingjian.inputmethod.core.Native
import app.qingjian.inputmethod.settings.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络热词库：定期从 [SogouPopularDict](https://github.com/ASC8384/SogouPopularDict) 拉搜狗网络流行新词，
 * 导入成青简的一本词库（出现在「词库」页里，可开关）。
 *
 * 那份数据**正好提供 Rime `.dict.yaml`**，而青简的 `qingjian-dictionary` 本来就能解析它
 * （`import/rime.rs`），所以这里只做「下载 → 丢给 Native.dictionaries 导入」，不需要任何格式转换。
 * 查询键也是现成的：青简的音节查询一律不带声调（`tones.rs`），Rime 的无调拼音直接能用。
 *
 * 默认**关闭**：数据来自搜狗输入法词库，版权归属不明确，让用户自己决定要不要下。
 */
object HotWords {
    /** 导入后这本词库的 id（取自文件名），也是挂在设置里开关用的名字。 */
    const val DICT_ID = "sogou-popular"

    /** 用户看到的词库名（Rime 头里的 `name:` 优先，这里只是兜底）。 */
    const val DICT_NAME = "网络流行新词"

    private const val PREFS = "hot_words"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL = "interval_days"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val KEY_WORD_COUNT = "word_count"
    private const val KEY_VERSION = "version"

    /** 下载来的文件名：决定导入后的词库 id，别乱改。 */
    private const val FILE_NAME = "$DICT_ID.dict.yaml"

    /**
     * 下载源：主源 + 镜像。国内直连 GitHub raw 常失败，jsDelivr 与 ghproxy 通常可达。
     * 按顺序试，第一个成功的就用。
     *
     * 用**当前版**（约 7800 条、156 KB）而不是累积版（16 万条、5 MB）：累积版把历年热词堆在一起，
     * 里面大半是「一万个名字」这种普通词组，装了反而拖慢词库。
     */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/ASC8384/SogouPopularDict/main/data/luna_pinyin.sogoupopular.current.dict.yaml",
        "https://cdn.jsdelivr.net/gh/ASC8384/SogouPopularDict@main/data/luna_pinyin.sogoupopular.current.dict.yaml",
        "https://ghproxy.net/https://raw.githubusercontent.com/ASC8384/SogouPopularDict/main/data/luna_pinyin.sogoupopular.current.dict.yaml",
    )

    /** 版本信息（词条数、更新时间），与词库文件分开拿；拿不到不影响导入。 */
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/ASC8384/SogouPopularDict/main/data/version_info.json"

    /**
     * 给热词补的词频。
     *
     * 那份 Rime 文件只有 `词\t拼音` 两列，青简会把缺省的词频当 1——低到打不出来
     * （同音常用词是几万到几十万）。补一个中等的值：不至于压过常用词，但用户真打这个词时能出。
     */
    private const val HOT_WORD_WEIGHT = "500"

    /** 可选的更新周期（天）。 */
    val INTERVAL_CHOICES = intArrayOf(3, 7, 30)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun intervalDays(context: Context) =
        prefs(context).getInt(KEY_INTERVAL, 7).coerceAtLeast(1)

    fun setIntervalDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, days.coerceAtLeast(1)).apply()
    }

    /** 上次成功更新的时间戳（毫秒）；从没更新过为 0。 */
    fun lastUpdate(context: Context) = prefs(context).getLong(KEY_LAST_UPDATE, 0L)

    /** 上次导入的词条数；0 表示没记过。 */
    fun wordCount(context: Context) = prefs(context).getInt(KEY_WORD_COUNT, 0)

    /** 上次拿到的数据版本号（那份仓库的 version 字段）。 */
    fun version(context: Context) = prefs(context).getInt(KEY_VERSION, 0)

    /** 这本词库是不是已经挂着（导入过且在启用列表里）。 */
    fun isMounted(context: Context) =
        File(filesDir(context), "$DICT_ID.qj").isFile

    /** 到更新时间了吗（开着 + 过了周期 + 至少装过一次）。 */
    fun isDue(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val last = lastUpdate(context)
        if (last == 0L) return true
        val interval = intervalDays(context) * 24L * 60L * 60L * 1000L
        return System.currentTimeMillis() - last >= interval
    }

    /** 同一时刻只允许一个更新在跑（App 启动、词库页、切前台可能同时触发）。 */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 到期就静默更新一次，App 打开时调。
     *
     * 自己开后台线程、自己防并发；**不弹任何东西**——用户没在看这一页，
     * 更新成功就悄悄生效，下次打词库页能看到新的时间与条数。
     */
    fun autoUpdateIfDue(context: Context) {
        if (!isDue(context)) return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            runCatching { update(app, force = false) }
            running.set(false)
        }.apply { isDaemon = true }.start()
    }

    private fun filesDir(context: Context) = File(context.filesDir, "user/dicts")

    /**
     * 下载并导入。**阻塞**，调用方自己放后台线程。
     *
     * [force] 为 true 时不管开着没开着都更新（「立即更新」按钮走这条）；
     * 否则只在开着、且到了周期时才动手。
     *
     * 返回一句给用户看的结果。
     */
    fun update(context: Context, force: Boolean = true): String {
        if (!force && !isDue(context)) return "还没到更新时间"

        val cache = File(context.cacheDir, "hotwords").apply { mkdirs() }
        val target = File(cache, FILE_NAME)

        var downloaded = false
        var lastError: String? = null
        for (source in SOURCES) {
            val error = download(source, target)
            if (error == null) {
                downloaded = true
                break
            }
            lastError = error
        }
        if (!downloaded) return "下载失败：${lastError ?: "网络不通"}"

        // 内容太短肯定不是词库（被劫持/404 页面），别把垃圾导进去
        if (target.length() < 4096) {
            target.delete()
            return "下载失败：拿到的内容不像词库"
        }

        // 补词频再导：原文件只有「词\t拼音」，青简会当 1，热词就打不出来了
        addWeights(target)

        // 导入前先把旧的删掉：同 id 重复导入会改写，但先删更干净
        File(filesDir(context), "$DICT_ID.qj").delete()

        val request = JSONObject().put("import", target.absolutePath).toString()
        val response = Native.dictionaries(
            BundledData.dir(context).path, File(context.filesDir, "user").path, request,
        ) ?: return "导入失败：引擎没响应"

        val message = runCatching { JSONObject(response).optString("message") }
            .getOrNull().orEmpty()

        // 导入没成（词条数为 0 / 格式不对）：报原文，别写"已更新"
        if (!File(filesDir(context), "$DICT_ID.qj").isFile) {
            return message.ifEmpty { "导入失败" }
        }

        // 记一笔：时间、词条数
        val count = countWords(target)
        val editor = prefs(context).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .putInt(KEY_WORD_COUNT, count)
        // 版本号是附带信息：拿不到就保留上一次的，别把 0 写进去（那个请求有时会超时）
        val fresh = fetchVersion()
        if (fresh > 0) editor.putInt(KEY_VERSION, fresh)
        editor.apply()

        // 挂上这本词库 + 让引擎重挂（与词库页的开关同一套设置键）
        enableDomain(context)
        target.delete()
        return if (count > 0) "已更新「$DICT_NAME」，$count 条" else message.ifEmpty { "已更新" }
    }

    /** 从数据流里数词条（`---` 头之后、非空非注释的行数）。 */
    private fun countWords(file: File): Int = runCatching {
        var inBody = false
        var count = 0
        file.forEachLine { raw ->
            val line = raw.trim()
            if (!inBody) {
                if (line == "...") inBody = true
                return@forEachLine
            }
            if (line.isNotEmpty() && !line.startsWith("#") && line.contains('\t')) count++
        }
        count
    }.getOrDefault(0)

    /**
     * 就地改两处再导：
     * 1. `name:` 换成人看的「网络流行新词」——词库页显示的是这个字段，不改就是
     *    `luna_pinyin.sogoupopular.current`；
     * 2. 数据行补词频：`词\t拼音` → `词\t拼音\t500`。
     * 头部（`---` 到 `...`）与注释结构保持原样，Rime 解析器照样认。
     */
    private fun addWeights(file: File) {
        runCatching {
            val text = file.readText()
            val out = StringBuilder(text.length + text.length / 8)
            var inBody = false
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd()
                if (!inBody) {
                    if (line.trim() == "...") inBody = true
                    // 顺手把名字换掉（只动 name: 那一行）
                    if (line.trim().startsWith("name:")) {
                        out.append("name: ").append(DICT_NAME).append('\n')
                    } else {
                        out.append(line).append('\n')
                    }
                    return@forEach
                }
                // 正文里：已经有第三列的不动，只有两列的补上
                if (line.isBlank() || line.startsWith("#") || !line.contains('\t')) {
                    out.append(line).append('\n')
                } else {
                    val tabs = line.count { it == '\t' }
                    out.append(line)
                    if (tabs < 2) out.append('\t').append(HOT_WORD_WEIGHT)
                    out.append('\n')
                }
            }
            file.writeText(out.toString())
        }
    }

    /** 把本词库加进启用列表，并让引擎重挂（与词库页的开关写法一致）。 */
    private fun enableDomain(context: Context) {
        val settings = Settings.preferences(context)
        val current = settings.getStringSet(Settings.KEY_DOMAINS, emptySet()).orEmpty()
        if (DICT_ID !in current) {
            settings.edit().putStringSet(Settings.KEY_DOMAINS, current + DICT_ID).apply()
        }
        // 词库页据此知道要重挂
        settings.edit().putLong(Settings.KEY_DICTIONARIES_STAMP, System.currentTimeMillis()).apply()
    }

    /** 取一次远端版本号（拿不到就返回上次的值，不影响主流程）。 */
    private fun fetchVersion(): Int = runCatching {
        val text = open(VERSION_URL)?.use { it.readBytes().decodeToString() } ?: return 0
        JSONObject(text).optInt("version", 0)
    }.getOrDefault(0)

    /** 下载到 [target]；成功返回 null，失败返回一句原因。 */
    private fun download(url: String, target: File): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "qingjian")
        }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        connection.disconnect()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun open(url: String) = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "qingjian")
        }.inputStream
    }.getOrNull()
}
