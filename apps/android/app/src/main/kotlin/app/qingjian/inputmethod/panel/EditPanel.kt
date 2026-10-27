package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.theme.Palette

/**
 * 编辑面板：功能栏「编辑」键点开的紧凑一条，七枚小键分两组：
 * [←] [选中] [→] —— 选字组：平时 ←→ 移动光标；按「选中」（点亮）后锚点落在光标处，
 * ←→ 改为逐字扩选（同 Shift+方向键）；再按一次「选中」收拢选区退出，←→ 回到移动光标。
 * [全选] [复制] [剪切] [粘贴] —— 操作组：全选框住全部并进入扩选（←→ 可微调），
 * 复制/剪切/粘贴走输入连接的上下文菜单动作。动作后面板留着不关，方便连做几步。
 */
@SuppressLint("ViewConstructor")
class EditPanel(
    context: Context,
    palette: Palette,
    actions: PanelActions,
    headerHeight: Float,
) : PanelView(context, palette, actions, headerHeight) {
    /** 一个小键：[arrow] 的键面只画箭头不写字；[toggle] 的键在选字模式开着时点亮。 */
    private class Key(
        val icon: PanelIcons.Icon,
        val label: String,
        val arrow: Boolean = false,
        val toggle: Boolean = false,
        val run: PanelActions.() -> Unit,
    )

    private val keys = listOf(
        Key(PanelIcons.Icon.ARROW_LEFT, "", arrow = true) { moveSelectionEnd(-1) },
        Key(PanelIcons.Icon.PEN, "选中", toggle = true) { toggleSelectionMode() },
        Key(PanelIcons.Icon.ARROW_RIGHT, "", arrow = true) { moveSelectionEnd(1) },
        Key(PanelIcons.Icon.SELECT_ALL, "全选") { selectAll() },
        Key(PanelIcons.Icon.COPY, "复制") { copySelection() },
        Key(PanelIcons.Icon.CUT, "剪切") { cutSelection() },
        Key(PanelIcons.Icon.CLIPBOARD, "粘贴") { paste() },
    )

    private val card = RectF()
    private val keyRects = mutableListOf<Pair<RectF, Key>>()

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, null)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.board)

        keyRects.clear()
        val margin = ink.dp(10f)
        val gap = ink.dp(6f)
        val keyW = (card.width() - margin * 2 - gap * (keys.size - 1)) / keys.size
        val keyH = ink.dp(64f)
        val top = card.top + ink.dp(32f)
        keys.forEachIndexed { i, key ->
            val rect = RectF(card.left + margin + i * (keyW + gap), top, card.left + margin + i * (keyW + gap) + keyW, top + keyH)
            keyRects += rect to key
            val lit = key.toggle && actions.editSelecting
            ink.round(canvas, rect, 12f, if (lit) palette.brand else palette.functionalKey)
            val iconColor = if (lit) palette.onBrand else palette.text
            val cy = if (key.arrow) rect.centerY() else rect.centerY() - ink.dp(9f)
            ink.icon(canvas, key.icon, rect.centerX(), cy, if (key.arrow) 20f else 18f, iconColor)
            if (!key.arrow) {
                ink.text(canvas, key.label, 10f, if (lit) palette.onBrand else palette.gloss, rect.centerX(), rect.bottom - ink.dp(15f))
            }
            // 选字组与操作组之间一道竖线，分出两组
            if (i == 2) {
                val lineX = rect.right + gap / 2
                ink.line(canvas, lineX, rect.top + ink.dp(10f), lineX, rect.bottom - ink.dp(10f), palette.faint)
            }
        }
        ink.text(
            canvas, "←→ 移动光标；按「选中」后 ←→ 逐字扩选；全选框全部",
            12f, palette.gloss, card.centerX(), top + keyH + ink.dp(30f),
        )
    }

    override fun onTap(x: Float, y: Float) {
        keyRects.firstOrNull { it.first.contains(x, y) }?.let { (_, key) ->
            Haptics.tap(context)
            key.run.invoke(actions)
        }
    }
}
