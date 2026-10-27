package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import app.qingjian.inputmethod.candidates.Toolbar
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.panel.base.Tabs
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.roundToInt

/**
 * 点工具栏 logo 进的功能面板：「常用功能」是图标宫格（长按可上功能栏的图标能拖进功能栏排位置），
 * 「键盘设置」是就地改的开关；右上角进完整设置页。整块圆角卡片垫在键盘底色上。
 */
@SuppressLint("ViewConstructor")
class FunctionPanel(
    context: Context,
    palette: Palette,
    actions: PanelActions,
    headerHeight: Float,
    private val settings: Settings,
) : PanelView(context, palette, actions, headerHeight) {
    /** 一格功能；[glyph] 不为 null 时画字不画图标，[on] 是开关类功能开着（品牌色加对勾，同键盘选择），
     *  [toolbar] 不为 null 表示长按它可以拖进键盘的功能栏排位置。 */
    private class Feature(
        val icon: Icon?,
        val label: String,
        val glyph: String? = null,
        val on: Boolean = false,
        val toolbar: Toolbar.Action? = null,
        val run: PanelActions.() -> Unit,
    )

    private class Toggle(val key: String, val label: String, val note: String, val on: Boolean)

    // —— 编辑功能栏的状态：长按拾起一枚图标，拖进上面的功能栏副本排位，松手落位并持久化 ——
    // draft 非空就是编辑中：宫格让位给编辑视图。拖拽中的移动与抬起由 onTouchEvent 接着收。
    private var draft: MutableList<Toolbar.Action>? = null
    private var pick: Toolbar.Action? = null
    private var pickX = 0f
    private var pickY = 0f
    private var dropAt = -1
    private var liftedFromBar = false

    private val stripRect = RectF()
    private val doneRect = RectF()
    private val stripScroll = scrollable(true) { x, y -> draft != null && stripRect.contains(x, y) }

    // 「球透明度」滑杆（悬浮球子页里）：点一下跳到该值，按住拖动实时改，松手落盘。
    private val opacityRect = RectF()
    private var adjustingOpacity = false

    // 「自动收起」行（悬浮球子页里）：点一下循环换档位（不收起 / 3 / 5 / 10 / 30 秒）。
    private val idleRowRect = RectF()

    // 悬浮球子页：宫格点「悬浮球」进来，里面是球开关与透明度滑杆；返回键先退回宫格。
    private var ballPage = false

    private val features = listOf(
        Feature(Icon.EMOJI, "表情", toolbar = Toolbar.Action.EMOJI) { openPanel(PanelKind.EMOJI) },
        Feature(Icon.SYMBOLS, "符号", toolbar = Toolbar.Action.SYMBOLS) { openPanel(PanelKind.SYMBOLS) },
        Feature(Icon.KEYBOARD, "切换键盘", toolbar = Toolbar.Action.KEYBOARDS) { openPanel(PanelKind.KEYBOARDS) },
        Feature(Icon.SEARCH, "AI 搜题", toolbar = Toolbar.Action.SOLVE) { openSolve() },
        Feature(Icon.CAMERA, "截屏搜题", toolbar = Toolbar.Action.SOLVE_SHOT) { openSolveShot() },
        Feature(Icon.COPY, "提取文字") { extractText() },
        Feature(Icon.MIC, "语音问答") { voiceAsk() },
        Feature(Icon.BALL, "悬浮球", on = settings.floatingBall) { ballPage = true; invalidate() },
        Feature(null, "答题模式", glyph = "答", on = settings.solveOverlay) {
            setToggle(Settings.KEY_SOLVE_OVERLAY, !settings.solveOverlay)
        },
        Feature(null, "繁体", glyph = "繁", on = settings.traditional) {
            setToggle(Settings.KEY_TRADITIONAL, !settings.traditional)
        },
        Feature(Icon.PEN, "编辑面板", toolbar = Toolbar.Action.EDIT) { openPanel(PanelKind.EDIT) },
        Feature(Icon.RESIZE, "键盘高度", toolbar = Toolbar.Action.RESIZE) { startResize() },
        Feature(Icon.GLOBE, "切换输入法", toolbar = Toolbar.Action.SWITCH) { switchInputMethod() },
        Feature(Icon.SETTINGS, "设置") { openSettings() },
    )

    private val toggles
        get() = listOf(
            Toggle(Settings.KEY_HAPTIC, "按键震动", "", settings.haptic),
            Toggle(Settings.KEY_POPUP, "按键气泡", "按下字母时在上面放大显示", settings.keyPopup),
            Toggle(Settings.KEY_FULLSCREEN, "全面屏适配", "手势条那一截不留白，两个角各放一个键", settings.fullscreenAdapt),
            Toggle(Settings.KEY_KEEP_CHINESE, "换语言时保留中文", "开着时总在中文和选的语言之间切", settings.keepChinese),
            Toggle(Settings.KEY_PRIVACY, "隐私模式", "开着时录屏、截屏拍不到键盘，画面里键盘位置是黑的；打字不受影响", settings.privacy),
        )

    private val tabs = Tabs(ink, scrollable(true) { x, y -> y < headerHeight && x > ink.dp(BACK_WIDTH_DP) }, Tabs.Style.UNDERLINE)
        .apply {
            labels = listOf("常用功能", "键盘设置")
            selected = lastTab
        }

    private val card = RectF()
    private val knob = RectF()

    private val columns = 5

    /** 功能栏副本里一枚图标的横槽宽（与真工具栏一致：一屏 5 枚）。 */
    private fun stripSlot() = stripRect.width() / Toolbar.VISIBLE_SLOTS

    override fun onDraw(canvas: Canvas) {
        // 悬浮球子页没有页签（表头画返回 + 标题），宫格与键盘设置才用 tabs
        drawHeader(canvas, if (ballPage) null else tabs, Icon.SETTINGS)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.board)
        when {
            draft != null -> drawEditor(canvas)
            ballPage -> drawBallPage(canvas)
            tabs.selected == 0 -> drawFeatures(canvas)
            else -> drawToggles(canvas)
        }
    }

    private fun cellWidth() = card.width() / columns

    /** 宫格行高：按行数平分卡片高度（功能多了自动变矮，不会把最后一行挤掉），上限 86dp。 */
    private fun cellHeight(): Float {
        val rows = ((features.size + columns - 1) / columns).coerceAtLeast(1)
        return minOf(ink.dp(86f), card.height() / rows)
    }

    /** 常用功能宫格：按行数自适应行高，一屏放得下全部功能。 */
    private fun drawFeatures(canvas: Canvas) {
        features.forEachIndexed { i, feature ->
            val cx = card.left + cellWidth() * (i % columns + 0.5f)
            val cy = card.top + ink.dp(8f) + cellHeight() * (i / columns + 0.5f)
            if (cy - cellHeight() / 2 > card.bottom) return@forEachIndexed
            val color = if (feature.on) palette.brand else palette.text
            feature.icon?.let { ink.icon(canvas, it, cx, cy - ink.dp(12f), 30f, color) }
            feature.glyph?.let { ink.text(canvas, it, 24f, color, cx, cy - ink.dp(12f), bold = true) }
            if (feature.on) drawBadge(canvas, cx + ink.dp(18f), cy - ink.dp(26f))
            val label = if (feature.on) palette.brand else palette.gloss
            ink.text(canvas, feature.label, 13f, label, cx, cy + ink.dp(20f), maxWidth = cellWidth() - ink.dp(4f))
        }
    }

    /** 透明度滑杆上的一点换算成百分比（1..100）。 */
    private fun opacityAt(x: Float): Int {
        val fraction = ((x - opacityRect.left) / opacityRect.width()).coerceIn(0f, 1f)
        return (1 + (fraction * 99).roundToInt()).coerceIn(1, 100)
    }

    /**
     * 悬浮球子页：球开关 + 透明度滑杆 + 自动收起，三行。
     * 行距按卡片实际高度自适应——键盘高度被调矮时也压得下，不至于最后一行被裁掉。
     */
    private fun drawBallPage(canvas: Canvas) {
        val left = card.left + ink.dp(20f)
        val right = card.right - ink.dp(20f)
        // 三行分掉卡片高度：开关行占 1 份、透明度行 1.35 份、自动收起行 1 份
        val usable = card.height() - ink.dp(12f)
        val unit = (usable / 3.35f).coerceIn(ink.dp(44f), ink.dp(74f))

        // 第 1 行：开关
        val switchCy = card.top + ink.dp(8f) + unit * 0.5f
        drawLeft(canvas, "悬浮球", 16f, palette.text, left, switchCy - ink.dp(9f))
        drawLeft(canvas, "任意界面上常驻一颗可拖动的球", 12f, palette.gloss, left, switchCy + ink.dp(11f))
        drawSwitch(canvas, card.right - ink.dp(44f), switchCy, settings.floatingBall)
        ink.line(canvas, left, switchCy + unit * 0.46f, right, switchCy + unit * 0.46f, palette.faint)

        // 第 2 行：透明度滑杆
        val rowCy = switchCy + unit
        drawLeft(canvas, "透明度", 16f, palette.text, left, rowCy - ink.dp(9f))
        drawLeft(canvas, "拖动实时生效", 12f, palette.gloss, left, rowCy + ink.dp(11f))
        val trackLeft = left
        val trackRight = card.right - ink.dp(66f)
        val trackCy = rowCy + unit * 0.52f
        opacityRect.set(trackLeft, trackCy - ink.dp(10f), trackRight, trackCy + ink.dp(10f))
        val shown = actions.shownBallOpacity
        val fraction = (shown - 1) / 99f
        ink.round(canvas, opacityRect, 5f, palette.functionalKey)
        val fillRect = RectF(opacityRect.left, opacityRect.top, opacityRect.left + opacityRect.width() * fraction, opacityRect.bottom)
        if (fillRect.width() > ink.dp(5f)) ink.round(canvas, fillRect, 5f, palette.brand)
        val thumbX = opacityRect.left + opacityRect.width() * fraction
        ink.fill.color = palette.surface
        canvas.drawCircle(thumbX, trackCy, ink.dp(12f), ink.fill)
        ink.fill.color = palette.brand
        canvas.drawCircle(thumbX, trackCy, ink.dp(9f), ink.fill)
        ink.text(canvas, "$shown%", 15f, palette.text, trackRight + ink.dp(33f), trackCy, bold = true)
        ink.line(canvas, left, rowCy + unit * 0.92f, right, rowCy + unit * 0.92f, palette.faint)

        // 第 3 行：自动收起（整行可点，循环换档；0 = 不收起）
        val idleCy = rowCy + unit * 1.35f
        idleRowRect.set(left, idleCy - unit * 0.45f, right, idleCy + unit * 0.45f)
        drawLeft(canvas, "自动收起", 16f, palette.text, left, idleCy - ink.dp(9f))
        drawLeft(canvas, "闲置后缩成屏幕边的小箭头", 12f, palette.gloss, left, idleCy + ink.dp(11f))
        val seconds = actions.ballIdleSeconds
        val idleLabel = if (seconds <= 0) "不收起" else "${seconds}秒"
        ink.text(canvas, idleLabel, 15f, if (seconds > 0) palette.brand else palette.gloss, card.right - ink.dp(48f), idleCy, bold = true)
    }

    /** 拖动滑杆：实时推进预览值（球与滑杆同步变），松手才落盘。 */
    private fun adjustOpacityTo(x: Float) {
        val value = opacityAt(x)
        if (value == actions.shownBallOpacity) return
        actions.previewBallOpacity(value)
        invalidate()
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float) {
        ink.fill.color = palette.brand
        canvas.drawCircle(cx, cy, ink.dp(7.5f), ink.fill)
        ink.icon(canvas, Icon.CHECK, cx, cy, 13f, palette.onBrand)
    }

    /** 功能面板里的「AI 搜题」：走 [PanelActions.openSolve]，由服务开搜题条。 */
    private fun openSolve() {
        actions.closePanel()
        actions.openSolve()
    }

    /** 功能面板里的「截屏搜题」：走 [PanelActions.openSolveShot]，由服务开搜题条并发起截屏。 */
    private fun openSolveShot() {
        actions.closePanel()
        actions.openSolveShot()
    }

    /** 功能面板里的「提取文字」：截一屏走 OCR，结果放进搜题条答案区。 */
    private fun extractText() {
        actions.closePanel()
        actions.extractText()
    }

    /** 功能面板里的「语音问答」：开搜题条并开始听。 */
    private fun voiceAsk() {
        actions.closePanel()
        actions.voiceAsk()
    }

    private fun rowHeight() = card.height() / toggles.size

    private fun drawToggles(canvas: Canvas) {
        toggles.forEachIndexed { i, toggle ->
            val top = card.top + rowHeight() * i
            val cy = top + rowHeight() / 2
            val left = card.left + ink.dp(20f)
            if (toggle.note.isEmpty()) {
                drawLeft(canvas, toggle.label, 16f, palette.text, left, cy)
            } else {
                drawLeft(canvas, toggle.label, 16f, palette.text, left, cy - ink.dp(10f))
                drawLeft(canvas, toggle.note, 12f, palette.gloss, left, cy + ink.dp(12f))
            }
            drawSwitch(canvas, card.right - ink.dp(44f), cy, toggle.on)
            if (i > 0) ink.line(canvas, left, top, card.right - ink.dp(20f), top, palette.faint)
        }
    }

    private fun drawLeft(canvas: Canvas, value: String, sp: Float, color: Int, left: Float, cy: Float) {
        val width = ink.measure(value, sp)
        ink.text(canvas, value, sp, color, left + width / 2, cy)
    }

    private fun drawSwitch(canvas: Canvas, cx: Float, cy: Float, on: Boolean) {
        knob.set(cx - ink.dp(22f), cy - ink.dp(13f), cx + ink.dp(22f), cy + ink.dp(13f))
        ink.round(canvas, knob, 13f, if (on) palette.brand else palette.functionalKey)
        val kx = if (on) cx + ink.dp(9f) else cx - ink.dp(9f)
        ink.fill.color = palette.surface
        canvas.drawCircle(kx, cy, ink.dp(10f), ink.fill)
    }

    override fun onTap(x: Float, y: Float) {
        if (draft != null) {
            // 编辑中：只认「完成」，别的位置不响应，免得误触功能
            if (doneRect.contains(x, y)) exitEditing()
            return
        }
        if (ballPage) {
            when {
                // 子页：开关行整行可点，滑杆在 onTouchEvent 的 DOWN 里已经拦掉
                y < headerHeight -> ballPage = false
                y < card.top + ink.dp(112f) -> actions.setToggle(Settings.KEY_FLOATING_BALL, !settings.floatingBall)
            }
            return
        }
        when {
            isRightIcon(x, y) -> actions.openSettings()
            y < headerHeight -> tabs.indexAt(x)?.let {
                tabs.selected = it
                lastTab = it
            }

            !card.contains(x, y) -> Unit
            tabs.selected == 0 -> {
                val row = ((y - card.top - ink.dp(8f)) / cellHeight()).toInt()
                val column = ((x - card.left) / cellWidth()).toInt()
                val index = row * columns + column
                if (index in features.indices) features[index].run.invoke(actions)
            }

            else -> toggles.getOrNull(((y - card.top) / rowHeight()).toInt())?.let {
                actions.setToggle(it.key, !it.on)
            }
        }
    }

    // —— 编辑功能栏 ——

    /** 长按：宫格里可上功能栏的图标被拾起（进编辑态）；编辑中则从功能栏副本或图标池里再拾一枚。 */
    override fun onHold(x: Float, y: Float, rawX: Float) {
        if (y > card.bottom || y < headerHeight) return
        if (draft == null) {
            if (tabs.selected != 0) return
            val row = ((y - card.top - ink.dp(8f)) / cellHeight()).toInt()
            val column = ((x - card.left) / cellWidth()).toInt()
            val feature = features.getOrNull(row * columns + column) ?: return
            val action = feature.toolbar ?: return
            draft = Toolbar.parse(
                Settings.preferences(context).getString(Settings.KEY_TOOLBAR, null),
            ).toMutableList()
            draft!!.remove(action)
            pick = action
            liftedFromBar = false
        } else {
            when {
                stripRect.contains(x, y) -> {
                    val index = ((x - stripRect.left + stripScroll.offset) / stripSlot()).toInt()
                        .coerceIn(0, (draft?.size ?: 1) - 1)
                    pick = draft?.removeAt(index)
                    liftedFromBar = true
                }
                else -> poolCells().firstOrNull { it.first.contains(x, y) }?.let { (_, action) -> pick = action }
            }
            if (pick == null) return
            liftedFromBar = false
        }
        pickX = x
        pickY = y
        dropAt = -1
        Haptics.tap(context)
        invalidate()
    }

    /** 拖着走：跟手，并算出悬在功能栏上的插入位。 */
    private fun trackPick(event: MotionEvent) {
        pickX = event.x
        pickY = event.y
        dropAt = if (stripRect.contains(pickX, pickY)) {
            ((pickX - stripRect.left + stripScroll.offset) / stripSlot()).roundToInt()
                .coerceIn(0, draft?.size ?: 0)
        } else {
            -1
        }
    }

    /** 松手落位：拖进功能栏就插到那个位置，拖出去就是移除；每次落位都存进设置并让工具栏就地生效。 */
    private fun dropPick() {
        val action = pick ?: return
        val draft = this.draft ?: return
        if (dropAt >= 0) draft.add(dropAt.coerceIn(0, draft.size), action)
        actions.setToolbarOrder(draft.map(Toolbar.Action::name))
        pick = null
        dropAt = -1
        liftedFromBar = false
    }

    private fun exitEditing() {
        draft = null
        pick = null
        dropAt = -1
        invalidate()
    }

    /** 编辑视图：上面是功能栏副本（拖到这里排位），下面是还没放进去的图标池，右下角「完成」。 */
    private fun drawEditor(canvas: Canvas) {
        val draft = this.draft ?: return
        ink.text(canvas, "长按图标拖进功能栏，拖出即移除", 12f, palette.gloss, card.centerX(), card.top + ink.dp(22f))
        stripRect.set(card.left + ink.dp(12f), card.top + ink.dp(38f), card.right - ink.dp(12f), card.top + ink.dp(94f))
        ink.round(canvas, stripRect, 12f, palette.surface)
        val slot = stripSlot()
        val clip = canvas.save()
        canvas.clipRect(stripRect)
        draft.forEachIndexed { i, action ->
            ink.icon(canvas, action.icon, stripRect.left + slot * (i + 0.5f) - stripScroll.offset, stripRect.centerY(), 26f, palette.text)
        }
        if (dropAt >= 0) {
            val x = stripRect.left + slot * dropAt - stripScroll.offset
            ink.fill.color = palette.brand
            canvas.drawRect(x - ink.dp(1.5f), stripRect.top + ink.dp(6f), x + ink.dp(1.5f), stripRect.bottom - ink.dp(6f), ink.fill)
        }
        canvas.restoreToCount(clip)
        stripScroll.max = (slot * draft.size - stripRect.width()).coerceAtLeast(0f)

        val rest = Toolbar.POOL.filter { it !in draft }
        val poolTop = stripRect.bottom + ink.dp(30f)
        ink.text(canvas, "可用图标", 12f, palette.gloss, card.centerX(), poolTop)
        poolCells(rest).forEach { (rect, action) ->
            ink.round(canvas, rect, 12f, palette.functionalKey)
            ink.icon(canvas, action.icon, rect.centerX(), rect.centerY(), 24f, palette.text)
            ink.text(canvas, label(action), 11f, palette.gloss, rect.centerX(), rect.bottom + ink.dp(14f), maxWidth = rect.width() + ink.dp(8f))
        }
        doneRect.set(card.right - ink.dp(108f), card.bottom - ink.dp(52f), card.right - ink.dp(12f), card.bottom - ink.dp(14f))
        ink.round(canvas, doneRect, 12f, palette.brand)
        ink.text(canvas, "完成", 15f, palette.onBrand, doneRect.centerX(), doneRect.centerY(), bold = true)
        if (pick != null) drawGhost(canvas)
    }

    /** 没放进功能栏的图标池：一行五枚，位置与命中共用同一份计算。 */
    private fun poolCells(rest: List<Toolbar.Action> = Toolbar.POOL.filter { draft?.contains(it) != true }): List<Pair<RectF, Toolbar.Action>> {
        val cellW = card.width() / columns
        val top = stripRect.bottom + ink.dp(48f)
        return rest.mapIndexed { i, action ->
            val cx = card.left + cellW * (i % columns + 0.5f)
            val cy = top + (i / columns) * ink.dp(76f)
            RectF(cx - ink.dp(30f), cy, cx + ink.dp(30f), cy + ink.dp(56f)) to action
        }
    }

    /** 拖在手里的一枚：画在手指处，盖在所有内容上。 */
    private fun drawGhost(canvas: Canvas) {
        val rect = RectF(pickX - ink.dp(30f), pickY - ink.dp(28f), pickX + ink.dp(30f), pickY + ink.dp(28f))
        ink.fill.alpha = 235
        ink.round(canvas, rect, 12f, palette.functionalKey)
        ink.fill.alpha = 255
        ink.icon(canvas, pick!!.icon, pickX, pickY, 26f, palette.text)
    }

    private fun label(action: Toolbar.Action) = when (action) {
        Toolbar.Action.EMOJI -> "表情"
        Toolbar.Action.SYMBOLS -> "符号"
        Toolbar.Action.KEYBOARDS -> "切换键盘"
        Toolbar.Action.SOLVE -> "AI 搜题"
        Toolbar.Action.SOLVE_SHOT -> "截屏搜题"
        Toolbar.Action.SWITCH -> "切换输入法"
        Toolbar.Action.RESIZE -> "键盘高度"
        Toolbar.Action.CLIPBOARD -> "剪贴板"
        Toolbar.Action.TRANSLATE -> "翻译"
        Toolbar.Action.VOICE -> "语音"
        else -> ""
    }

    /** 长按拾起后这根手指的移动与抬起归这里：跟手重画，抬起落位；透明度滑杆拖动优先。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 滑杆：子页里按下去落在槽上就进入调值（DOWN 先于基类手势判定，拖动全程归这里）
        if (event.actionMasked == MotionEvent.ACTION_DOWN && draft == null &&
            ballPage && opacityRect.contains(event.x, event.y)
        ) {
            adjustingOpacity = true
        }
        if (adjustingOpacity) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    adjustOpacityTo(event.x)
                    return true
                }
                MotionEvent.ACTION_DOWN -> {
                    adjustOpacityTo(event.x)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    adjustingOpacity = false
                    actions.setBallOpacity(opacityAt(event.x))
                    return true
                }
            }
        }
        // 「自动收起」行：按下即循环换档（子页里，别让它落到基类的点击逻辑上）
        if (event.actionMasked == MotionEvent.ACTION_DOWN && draft == null &&
            ballPage && idleRowRect.contains(event.x, event.y)
        ) {
            Haptics.tap(context)
            actions.cycleBallIdle()
            invalidate()
            return true
        }
        val handled = super.onTouchEvent(event)
        if (pick != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    trackPick(event)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    trackPick(event)
                    dropPick()
                    invalidate()
                    return true
                }
            }
        }
        return handled
    }

    /** 编辑中按返回先退编辑；悬浮球子页返回先回宫格；再按才是关面板。 */
    override fun onBack() {
        when {
            draft != null -> exitEditing()
            ballPage -> {
                ballPage = false
                invalidate()
            }
            else -> super.onBack()
        }
    }

    private companion object {
        /** 上次停在哪一页；改开关会重建键盘，回来时还在这页。 */
        var lastTab = 0
    }
}
