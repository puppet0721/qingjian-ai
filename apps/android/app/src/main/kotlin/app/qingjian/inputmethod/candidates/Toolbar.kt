package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.Settings.Companion.KEY_TOOLBAR
import app.qingjian.inputmethod.theme.Palette

/**
 * 不在组句时候选条的样子：左边 logo 进功能面板、右边收起键盘，两头固定、各隔一道竖线，宽度一样；
 * 中间一排图标一屏正好放 [VISIBLE_SLOTS] 个，多出来的横着滑出来。
 * 中间那排的图标与顺序可以在功能面板里自定义（见 FunctionPanel 的答题编辑），存在
 * [Settings.KEY_TOOLBAR] 里；没设置过用 [DEFAULT_ORDER]，认不得的名字丢掉、清空了回缺省。
 */
class Toolbar(context: Context, private val palette: Palette, private val density: Float, private val barHeight: Float) {
    enum class Action(val icon: Icon) {
        FUNCTIONS(Icon.SETTINGS),
        EMOJI(Icon.EMOJI),
        KEYBOARDS(Icon.KEYBOARD),
        VOICE(Icon.MIC),
        SYMBOLS(Icon.SYMBOLS),
        CLIPBOARD(Icon.CLIPBOARD),
        TRANSLATE(Icon.TRANSLATE),
        SOLVE(Icon.SEARCH),
        SOLVE_SHOT(Icon.CAMERA),
        SWITCH(Icon.GLOBE),
        RESIZE(Icon.RESIZE),
        EDIT(Icon.PEN),
        HIDE_KEYBOARD(Icon.HIDE),
    }

    private val logo = context.getDrawable(R.drawable.ic_brand)
    private val logoSlot = 62f * density
    private val hideSlot = 62f * density
    private var middle = build(context)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = palette.faint }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardRect = RectF()

    /** 中间那排滑过去的距离（像素）。 */
    var offset = 0f
        private set

    private fun middleSlot(viewWidth: Int) = (viewWidth - logoSlot - hideSlot) / VISIBLE_SLOTS

    fun maxOffset(viewWidth: Int) = (middleSlot(viewWidth) * middle.size - (viewWidth - logoSlot - hideSlot)).coerceAtLeast(0f)

    fun scrollTo(value: Float, viewWidth: Int) {
        offset = value.coerceIn(0f, maxOffset(viewWidth))
    }

    /** [x] 落在中间那排上（可滑的区域）。 */
    fun inMiddle(x: Float, viewWidth: Int) = x >= logoSlot && x <= viewWidth - hideSlot

    /** 设置里存的名字还原成动作；认不得的丢掉，一个不剩或没设置过回 [DEFAULT_ORDER]。 */
    private fun build(context: Context) =
        parse(Settings.preferences(context).getString(KEY_TOOLBAR, null)).map { it to it.icon }

    /** 功能栏顺序改了就地换：不动别的视图。 */
    fun reload(context: Context) {
        middle = build(context)
    }

    /** [x] 落在中间那排上时点中的动作；功能栏被清空时返回 null（点了不做事）。 */
    fun actionAt(x: Float, viewWidth: Int): Action? = when {
        x < logoSlot -> Action.FUNCTIONS
        x > viewWidth - hideSlot -> Action.HIDE_KEYBOARD
        middle.isEmpty() -> null
        else -> middle[((x - logoSlot + offset) / middleSlot(viewWidth)).toInt().coerceIn(0, middle.size - 1)].first
    }

    /** 浮在键盘上方的一块圆角卡片，底下一道与按键同样的底影，和键盘分出层次。 */
    private fun drawCard(canvas: Canvas, viewWidth: Int) {
        val radius = 14 * density
        cardRect.set(6 * density, 6 * density, viewWidth - 6 * density, barHeight - 4 * density)
        card.color = palette.keyShadow
        cardRect.offset(0f, density)
        canvas.drawRoundRect(cardRect, radius, radius, card)
        cardRect.offset(0f, -density)
        card.color = palette.surface
        canvas.drawRoundRect(cardRect, radius, radius, card)
    }

    fun draw(canvas: Canvas, viewWidth: Int) {
        val cy = barHeight / 2
        drawCard(canvas, viewWidth)
        // 与 Windows 托盘同一张品牌标
        val logoSize = (30 * density).toInt()
        val logoCentre = (logoSlot / 2).toInt()
        logo?.setBounds(
            logoCentre - logoSize / 2, (cy - logoSize / 2).toInt(),
            logoCentre + logoSize / 2, (cy + logoSize / 2).toInt(),
        )
        logo?.draw(canvas)

        stroke.color = palette.gloss
        val slot = middleSlot(viewWidth)
        val divider = viewWidth - hideSlot
        canvas.save()
        canvas.clipRect(logoSlot, 0f, divider, barHeight)
        middle.forEachIndexed { i, (_, icon) ->
            PanelIcons.draw(canvas, icon, logoSlot + slot * (i + 0.5f) - offset, cy, 26 * density, stroke)
        }
        canvas.restore()
        line.strokeWidth = density
        canvas.drawLine(logoSlot, cy - 12 * density, logoSlot, cy + 12 * density, line)
        canvas.drawLine(divider, cy - 12 * density, divider, cy + 12 * density, line)
        PanelIcons.draw(canvas, Icon.HIDE, viewWidth - hideSlot / 2, cy, 24 * density, stroke)
    }

    companion object {
        const val VISIBLE_SLOTS = 5f

        /** 没自定义过时的功能栏顺序。 */
        val DEFAULT_ORDER = listOf(Action.EMOJI, Action.KEYBOARDS, Action.VOICE, Action.SYMBOLS, Action.CLIPBOARD, Action.TRANSLATE, Action.SOLVE, Action.RESIZE)

        /** 能放进功能栏的全部动作（自定义时的图标池）。 */
        val POOL = Action.entries.filter { it != Action.FUNCTIONS && it != Action.HIDE_KEYBOARD }

        /** 设置里存的名字还原成动作；认不得的丢掉，一个不剩或没设置过回 [DEFAULT_ORDER]。 */
        fun parse(saved: String?): List<Action> {
            val list = saved?.split(',')?.mapNotNull { name -> Action.entries.firstOrNull { it.name == name } }.orEmpty()
            return list.ifEmpty { DEFAULT_ORDER }
        }
    }
}
