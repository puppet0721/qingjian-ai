package app.qingjian.inputmethod.settings.page

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.cloud.ModelProfile
import app.qingjian.inputmethod.cloud.ModelProfiles
import app.qingjian.inputmethod.cloud.ModelTester

/**
 * 模型配置管理：同一份接口现在可以存好几套，随时切换当前用哪套。
 *
 * 点一行 = 切到那套；右侧「编辑」改名称/地址/模型/密钥；「删除」连密钥一起清。
 * 密钥输入框留空表示不改（编辑时不重填密钥）。
 */
class ModelProfilesActivity : PageActivity() {
    override val title = R.string.page_model_profiles

    /** 每套配置的测试状态（页面重建后仍在，方便多套逐个点完一起看）。 */
    private val testStates = mutableMapOf<String, TestState>()

    private sealed class TestState {
        data object Testing : TestState()
        data class Ok(val message: String) : TestState()
        data class Fail(val message: String) : TestState()
    }

    override fun content(): List<View> {
        val list = ModelProfiles.all(this)
        val activeId = ModelProfiles.active(this)?.id
        val rows = mutableListOf<View>()
        if (list.isEmpty()) {
            rows += ui.note(R.string.model_none)
        } else {
            list.forEachIndexed { i, profile ->
                if (i > 0) rows += ui.divider()
                rows += profileRow(profile, profile.id == activeId)
            }
        }
        return listOf(
            ui.card(*rows.toTypedArray()),
            spaced(ui.action(R.string.model_add, null, ui.colors.brand) { edit(null) }, 16),
            ui.note(R.string.model_note),
        )
    }

    /** 一行配置：左边名称 + 地址·模型 + 测试结果，右边「测试 / 编辑」。点整行切换。 */
    private fun profileRow(profile: ModelProfile, active: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(12))
            isClickable = true
            setOnClickListener {
                ModelProfiles.setActive(this@ModelProfilesActivity, profile.id)
                rebuild()
            }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = profile.name
            setTextColor(if (active) ui.colors.brand else ui.colors.text)
            textSize = 15f
        })
        column.addView(TextView(this).apply {
            text = "${profile.baseUrl}  ·  ${profile.model}"
            setTextColor(ui.colors.secondary)
            textSize = 12f
            setPadding(0, ui.dp(3), 0, 0)
        })
        // 测试结果就地显示在这一行下面，不用去别处找
        testStates[profile.id]?.let { state ->
            column.addView(TextView(this).apply {
                text = when (state) {
                    is TestState.Testing -> getString(R.string.model_testing)
                    is TestState.Ok -> getString(R.string.model_test_ok, state.message)
                    is TestState.Fail -> getString(R.string.model_test_fail, state.message)
                }
                setTextColor(
                    when (state) {
                        is TestState.Testing -> ui.colors.secondary
                        is TestState.Ok -> ui.colors.brand
                        is TestState.Fail -> 0xFFE5484D.toInt()
                    },
                )
                textSize = 11.5f
                setPadding(0, ui.dp(4), 0, 0)
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (active) {
            row.addView(TextView(this).apply {
                text = getString(R.string.model_in_use)
                setTextColor(ui.colors.brand)
                textSize = 12f
                setPadding(ui.dp(8), 0, ui.dp(2), 0)
            })
        }
        // 上下移：列表顺序就是故障转移的尝试顺序，得让用户能调
        val order = ModelProfiles.all(this)
        val position = order.indexOfFirst { it.id == profile.id }
        row.addView(orderButton("↑", position > 0) {
            ModelProfiles.move(this@ModelProfilesActivity, profile.id, -1)
            rebuild()
        })
        row.addView(orderButton("↓", position in 0 until order.lastIndex) {
            ModelProfiles.move(this@ModelProfilesActivity, profile.id, +1)
            rebuild()
        })
        val testing = testStates[profile.id] is TestState.Testing
        row.addView(TextView(this).apply {
            text = getString(if (testing) R.string.model_testing_short else R.string.model_test)
            setTextColor(if (testing) ui.colors.secondary else ui.colors.brand)
            textSize = 13f
            setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
            isEnabled = !testing
            setOnClickListener { if (!testing) runTest(profile) }
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.model_edit)
            setTextColor(ui.colors.secondary)
            textSize = 13f
            setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
            setOnClickListener { edit(profile) }
        })
        return row
    }

    /** 上下移的小箭头；到头了变淡且不可点。 */
    private fun orderButton(label: String, enabled: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) ui.colors.secondary else ui.colors.faint)
        setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        isEnabled = enabled
        if (enabled) setOnClickListener { action() }
    }

    /** 测一套：后台发一条最小请求，回来把结果显示在该行下面。 */
    private fun runTest(profile: ModelProfile) {
        val key = ModelProfiles.keyOf(this, profile.id)
        if (key.isNullOrBlank()) {
            testStates[profile.id] = TestState.Fail(getString(R.string.model_key_empty))
            rebuild()
            return
        }
        testStates[profile.id] = TestState.Testing
        rebuild()
        ModelTester.test(profile.baseUrl, profile.model, key) { result ->
            runOnUiThread {
                testStates[profile.id] = when (result) {
                    is ModelTester.Result.Ok -> TestState.Ok(result.message)
                    is ModelTester.Result.Fail -> TestState.Fail(result.message)
                }
                rebuild()
            }
        }
    }

    /** 添加（[profile] 为空）或编辑一份配置。 */
    private fun edit(profile: ModelProfile?) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val nameField = field(if (profile == null) "" else profile.name, getString(R.string.model_name_hint))
        val urlField = field(
            profile?.baseUrl ?: "", getString(R.string.model_base_url_hint),
            inputType = InputType.TYPE_TEXT_VARIATION_URI,
        )
        val modelField = field(
            profile?.model ?: "", getString(R.string.model_id_hint),
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        val keyField = field(
            "", getString(R.string.model_key_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        fun label(textRes: Int) = TextView(this).apply {
            text = getString(textRes)
            setTextColor(ui.colors.secondary)
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        listOf(
            R.string.model_name to nameField,
            R.string.cloud_base_url to urlField,
            R.string.cloud_model to modelField,
            R.string.cloud_key to keyField,
        ).forEach { (labelRes, input) ->
            form.addView(label(labelRes))
            form.addView(input)
        }
        // 内容多了（键盘弹起）也滚得动
        val scroll = ScrollView(this).apply { addView(form) }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (profile == null) R.string.model_add else R.string.model_edit)
            .setView(scroll)
            .setPositiveButton(R.string.model_save, null)
            .setNegativeButton(R.string.model_cancel, null)
        if (profile != null) {
            builder.setNeutralButton(R.string.model_delete, null)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            // 保存：自己拦下来，校验不过就不关窗（系统按钮默认会直接关）
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlField.text.toString().trim()
                val model = modelField.text.toString().trim()
                if (url.isEmpty() || model.isEmpty()) {
                    toast(getString(R.string.model_base_url_hint))
                    return@setOnClickListener
                }
                val target = profile ?: ModelProfiles.newProfile("", url, model)
                val saved = target.copy(
                    name = nameField.text.toString().trim().ifBlank { model },
                    baseUrl = url,
                    model = model,
                )
                val key = keyField.text.toString()
                // 新增时必须给密钥；编辑时留空表示不改
                if (profile == null && key.isBlank()) {
                    toast(getString(R.string.model_key_hint))
                    return@setOnClickListener
                }
                ModelProfiles.save(this, saved, apiKey = key.ifBlank { null })
                dialog.dismiss()
                rebuild()
            }
            // 删除：二次确认，删的是当前这套时列表会自动落到第一份
            if (profile != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.model_delete_confirm, profile.name))
                        .setPositiveButton(R.string.model_delete) { _, _ ->
                            ModelProfiles.delete(this, profile.id)
                            dialog.dismiss()
                            rebuild()
                        }
                        .setNegativeButton(R.string.model_cancel, null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    /** 一个带提示的单行输入框。 */
    private fun field(value: String, hintText: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            setText(value)
            setHint(hintText)
            setHintTextColor(ui.colors.secondary)
            setTextColor(ui.colors.text)
            textSize = 14f
            this.inputType = inputType
            setSingleLine(true)
        }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}
