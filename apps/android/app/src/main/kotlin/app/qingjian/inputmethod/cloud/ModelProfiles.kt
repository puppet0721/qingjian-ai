package app.qingjian.inputmethod.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份模型配置：名字（给人看的）+ OpenAI 兼容的地址与模型名。密钥单独加密存，见 [ModelProfiles]。
 */
data class ModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
)

/**
 * 多份模型配置的存取：可以同时存几套（DeepSeek、商汤、自建网关…），随时切换当前用哪套。
 *
 * 存储：
 * - `cloud` 那份 prefs 里的 [KEY_LIST] 存整个列表的 JSON；
 * - [KEY_ACTIVE] 记当前选中的 id；
 * - 每份配置的密钥单独加密，键名 `key_cipher_<id>`（复用 [SecretStore] 的 AES-GCM）。
 *
 * **兼容旧版**：老版本只有一份配置（`base_url` / `model` / `api_key_cipher`）。第一次读列表时
 * 如果发现没有 [KEY_LIST] 但有旧的 `base_url`，就把它包成一条「默认配置」并设为当前，
 * 用户升级后原来的设置不会丢。
 */
object ModelProfiles {
    private const val KEY_LIST = "profiles"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_CIPHER_PREFIX = "key_cipher_"

    /** 一套失败要不要自动换下一套（默认开；关掉就只用当前选中的那套）。 */
    private const val KEY_FAILOVER = "failover"

    /** 快速模式：请求里带 `reasoning_effort: none`，让思考型模型别先想一大段（默认开）。 */
    private const val KEY_FAST_MODE = "fast_mode"

    /** 旧版单配置的键名，迁移时读。 */
    private const val LEGACY_BASE_URL = "base_url"
    private const val LEGACY_MODEL = "model"
    private const val LEGACY_CIPHER = "api_key_cipher"

    private fun prefs(context: Context) = SecretStore.prefs(context)

    /** 全部配置；没配过时返回空列表。 */
    fun all(context: Context): List<ModelProfile> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_LIST, null) ?: return migrateLegacy(context)
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                ModelProfile(
                    id = item.getString("id"),
                    name = item.optString("name").ifBlank { "未命名" },
                    baseUrl = item.optString("baseUrl"),
                    model = item.optString("model"),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** 当前选中的那份；没有选中或选了已删的就返回第一份。 */
    fun active(context: Context): ModelProfile? {
        val list = all(context)
        if (list.isEmpty()) return null
        val activeId = prefs(context).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 请求失败要不要自动换下一套配置（默认开）。 */
    fun failoverEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FAILOVER, true)

    fun setFailover(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAILOVER, on).apply()
    }

    /**
     * 快速模式：在请求里带 `reasoning_effort: none`，让思考型模型（商汤 6.8、o 系列等）
     * 不要先想一大段再回答。默认开——思考阶段服务端不吐正文，流式也救不了首字延迟。
     */
    fun fastModeEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FAST_MODE, true)

    fun setFastMode(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAST_MODE, on).apply()
    }

    /**
     * 新增或更新一份配置。[apiKey] 传 null 表示"密钥不动"（编辑别的字段时用）；
     * 传空串表示清掉密钥。
     */
    fun save(context: Context, profile: ModelProfile, apiKey: String?) {
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        writeList(context, list)

        // 第一份自动设为当前，省得用户还要点一下
        if (prefs(context).getString(KEY_ACTIVE, null) == null) setActive(context, profile.id)

        if (apiKey != null) {
            if (apiKey.isEmpty()) {
                prefs(context).edit().remove(KEY_CIPHER_PREFIX + profile.id).apply()
            } else {
                prefs(context).edit()
                    .putString(KEY_CIPHER_PREFIX + profile.id, SecretStore.seal(apiKey.trim()))
                    .apply()
            }
        }
    }

    /** 删一份配置（连带它那份密钥）。删的是当前选中时，自动落到第一份。 */
    fun delete(context: Context, id: String) {
        val list = all(context).filterNot { it.id == id }
        writeList(context, list)
        prefs(context).edit().remove(KEY_CIPHER_PREFIX + id).apply()
        if (prefs(context).getString(KEY_ACTIVE, null) == id) {
            list.firstOrNull()?.let { setActive(context, it.id) }
                ?: prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
    }

    /**
     * 把某份配置往上（[delta] = -1）或往下（+1）挪一位，到头就不动。
     *
     * 列表顺序**就是故障转移的尝试顺序**（当前选中的排第一，其余按列表顺序），
     * 所以这个顺序是有意义的设置项，得让用户能调。
     */
    fun move(context: Context, id: String, delta: Int) {
        val list = all(context).toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        writeList(context, list)
    }

    /**
     * 发起云端请求时的尝试顺序：**当前选中的排第一**，其余按列表顺序依次往后；
     * 只收「地址 + 模型 + 密钥」都齐的（缺密钥试了也是 401）。
     * 关掉「失败自动换下一套」时只返回当前那套。
     *
     * 搜题和提取文字都走这里——两边各写一套的话，很容易漏掉某一边（之前
     * 提取文字就还在读旧密钥槽，结果 401）。
     */
    fun chain(context: Context): List<Pair<ModelProfile, String>> {
        val all = all(context)
        if (all.isEmpty()) return emptyList()
        val activeId = active(context)?.id
        val ordered = if (failoverEnabled(context)) {
            all.sortedBy { if (it.id == activeId) 0 else 1 }   // 稳定排序：其余保持原顺序
        } else {
            all.filter { it.id == activeId }.ifEmpty { all.take(1) }
        }
        return ordered.mapNotNull { profile ->
            if (profile.baseUrl.isBlank() || profile.model.isBlank()) return@mapNotNull null
            val key = keyOf(context, profile.id) ?: return@mapNotNull null
            profile to key
        }
    }

    /** 某份配置的密钥明文；没有或解不开为 null。 */
    fun keyOf(context: Context, id: String): String? =
        prefs(context).getString(KEY_CIPHER_PREFIX + id, null)?.let(SecretStore::open)

    /** 设置页显示用：只露末尾 4 位。 */
    fun keyHintOf(context: Context, id: String): String? =
        keyOf(context, id)?.takeIf { it.length > 4 }?.let { "····${it.takeLast(4)}" }

    /** 新建一份配置（id 用时间戳，够用且不会撞）。 */
    fun newProfile(name: String, baseUrl: String, model: String) = ModelProfile(
        id = System.currentTimeMillis().toString(),
        name = name.ifBlank { model.ifBlank { "新配置" } },
        baseUrl = baseUrl.trim(),
        model = model.trim(),
    )

    private fun writeList(context: Context, list: List<ModelProfile>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("model", p.model),
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /**
     * 把老版本的单份配置迁成一条记录。
     * 只在"完全没有列表、但有旧地址"时做一次，返回迁移结果（也可能是空）。
     */
    private fun migrateLegacy(context: Context): List<ModelProfile> {
        val prefs = prefs(context)
        val legacyUrl = prefs.getString(LEGACY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val legacyModel = prefs.getString(LEGACY_MODEL, null)?.takeIf { it.isNotBlank() } ?: CloudConfig.DEFAULT_MODEL
        val profile = newProfile("默认配置", legacyUrl, legacyModel)
        // 老密钥也搬过来，不然用户升级后要重新填一遍
        prefs.getString(LEGACY_CIPHER, null)?.let {
            prefs.edit().putString(KEY_CIPHER_PREFIX + profile.id, it).apply()
        }
        writeList(context, listOf(profile))
        setActive(context, profile.id)
        return listOf(profile)
    }
}
