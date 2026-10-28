package app.qingjian.inputmethod.cloud

import android.content.Context
import org.json.JSONObject

/**
 * 云端大模型的配置（OpenAI 兼容接口，快捷翻译、释义兜底与云联想用）：地址与模型是明文设置，
 * 密钥在 [SecretStore] 加密存。
 *
 * 现在是**多份配置**的形态：[ModelProfiles] 里可以存好几套，这里始终代表"当前选中的那一套"。
 * [toJson] 是交给 Rust 的那份（含解好的密钥），只在调 JNI 时临时拼，不留存。
 */
class CloudConfig(val baseUrl: String, val model: String, private val apiKey: String?) {
    /** 三样都有才算配好。 */
    val ready get() = baseUrl.isNotBlank() && model.isNotBlank() && !apiKey.isNullOrBlank()

    /** 设置页显示用：只露末尾 4 位。 */
    val keyHint: String? get() = apiKey?.takeIf { it.length > 4 }?.let { "····${it.takeLast(4)}" }

    /** [predict] 时 Rust 侧连云联想与整句补全一起开，否则只做显式的翻译。 */
    fun toJson(predict: Boolean = false): String = JSONObject()
        .put("base_url", baseUrl)
        .put("model", model)
        .put("api_key", apiKey.orEmpty())
        .put("predict", predict)
        .toString()

    companion object {
        /** 与 PC 的 `PredictConfig` 缺省一致。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"

        /** 字段名（改配置时用）。 */
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"

        /** 当前选中的那套配置；一份都没配时返回缺省值（未就绪）。 */
        fun load(context: Context): CloudConfig {
            val profile = ModelProfiles.active(context)
            if (profile != null) {
                return CloudConfig(profile.baseUrl, profile.model, ModelProfiles.keyOf(context, profile.id))
            }
            // 一份都没有：给个占位，界面显示缺省地址；用户一填就会生成第一份配置
            return CloudConfig(DEFAULT_BASE_URL, DEFAULT_MODEL, null)
        }

        /**
         * 改当前选中那套配置的某个字段（`base_url` / `model`）。
         * 还没有任何配置时，先把缺省值落成第一份再改，免得用户第一次输入就丢。
         */
        fun set(context: Context, key: String, value: String) {
            val trimmed = value.trim()
            val current = ensureProfile(context)
            val updated = when (key) {
                KEY_BASE_URL -> current.copy(baseUrl = trimmed.ifEmpty { DEFAULT_BASE_URL })
                KEY_MODEL -> current.copy(model = trimmed.ifEmpty { DEFAULT_MODEL })
                else -> current
            }
            ModelProfiles.save(context, updated, apiKey = null)
        }

        /** 存当前选中那套配置的密钥。 */
        fun setKey(context: Context, apiKey: String) {
            ModelProfiles.save(context, ensureProfile(context), apiKey = apiKey)
        }

        /** 清掉当前选中那套配置的密钥。 */
        fun clearKey(context: Context) {
            ModelProfiles.active(context)?.let { ModelProfiles.save(context, it, apiKey = "") }
        }

        /** 有就返回当前那份，没有就建一份缺省的并设为当前。 */
        private fun ensureProfile(context: Context): ModelProfile {
            ModelProfiles.active(context)?.let { return it }
            val created = ModelProfiles.newProfile("默认配置", DEFAULT_BASE_URL, DEFAULT_MODEL)
            ModelProfiles.save(context, created, apiKey = null)
            ModelProfiles.setActive(context, created.id)
            return created
        }
    }
}
