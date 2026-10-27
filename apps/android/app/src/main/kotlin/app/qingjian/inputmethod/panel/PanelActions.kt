package app.qingjian.inputmethod.panel

import app.qingjian.inputmethod.core.store.PhraseItem
import app.qingjian.inputmethod.settings.Settings

/** 面板里能做的事，由输入法服务实现。 */
interface PanelActions {
    fun openPanel(kind: PanelKind)

    /** 回到键盘。 */
    fun closePanel()

    /** 面板里点了一个符号或表情：组句中先顶首选再上屏。 */
    fun commitText(text: String)

    fun deleteBackward()

    /** 候选展开里点了第 [index] 个候选。 */
    fun selectCandidate(index: Int)

    /** 清空组句（候选展开里的「重输」）。 */
    fun clearComposition()

    /** 回到键盘并进入拖动调高度。 */
    fun startResize()

    fun openSettings()

    fun switchInputMethod()

    /** 选了拼音键盘：回到中文并换成这个布局。 */
    fun chooseLayout(layout: Settings.Layout)

    /** 选了英文 26 键：切到英文（全键盘），拼音布局不动，中英键切回来还是它。 */
    fun chooseEnglish()

    /** 选了日语罗马音（测试版：假名 + 小词表汉字）。 */
    fun chooseJapanese()

    /** 现在是不是英文，键盘选择里给它打勾。 */
    val english: Boolean

    val japanese: Boolean

    /** 就地改一个开关设置（[Settings] 里的键）。 */
    fun setToggle(key: String, on: Boolean)

    /** 拖「球透明度」滑杆中的实时预览：只改运行态（悬浮球立刻变），不落盘不打断面板。 */
    fun previewBallOpacity(percent: Int)

    /** 滑杆松手：把最终值落盘（1–100）。 */
    fun setBallOpacity(percent: Int)

    /** 滑杆此刻该显示的值：拖动中是预览值，平时是落盘值。 */
    val shownBallOpacity: Int

    /** 悬浮球闲置多少秒缩成贴边小箭头（0 = 不收起）。子页里点「自动收起」循环换档。 */
    val ballIdleSeconds: Int

    /** 换下一档自动收起时间（0 → 3 → 5 → 10 → 30 → 0）。 */
    fun cycleBallIdle()

    /** 功能栏的自定义顺序定下来了（动作名列表，可含重复的名字不会）：存设置并让键盘工具栏就地生效。 */
    fun setToolbarOrder(names: List<String>)

    /** 编辑面板的四个动作，都作用在当前输入框上：全选、复制选区、剪切选区、粘贴剪贴板。 */
    fun selectAll()
    fun copySelection()
    fun cutSelection()
    fun paste()

    /**
     * 选字模式没开时：[delta]（±1）移动光标；开了：把选区末端逐字左移/右移（像按住 Shift 的方向键）。
     */
    fun moveSelectionEnd(delta: Int)

    /** 选字模式开关：开=锚点落在当前光标处（←→ 改为扩选）；关=选区收拢到末端，←→ 回到移动光标。 */
    fun toggleSelectionMode()

    /** 现在是否处于选字模式（「选中」键点亮用）。 */
    val editSelecting: Boolean

    /** 剪贴板里点了一张图（图片文件路径）：发进输入框，输入框不收图片就放回系统剪贴板。 */
    fun commitImage(path: String)

    /** 剪贴板里点了「拆词」。 */
    fun openPieces(text: String)

    /** 功能面板里点了「AI 搜题」：收面板、开搜题条。 */
    fun openSolve()

    /** 功能面板里点了「截屏搜题」：收面板、开搜题条并发起截屏。 */
    fun openSolveShot()

    /** 功能面板里点了「提取文字」：收面板、截一屏走 OCR，结果放搜题条答案区。 */
    fun extractText()

    /** 功能面板里点了「语音问答」：收面板、开搜题条并开始听。 */
    fun voiceAsk()

    /** 拆词：按引擎的语言模型切成小块。 */
    fun textPieces(text: String): List<String>

    /** 编辑常用语：[phrase] 为 null 是新加一条，放进 [group]。 */
    fun editPhrase(phrase: PhraseItem?, group: String)

    /** 常用语改过：带输入码的重新挂进候选。 */
    fun phrasesChanged()

    /** 拆词选着的字放进输入框，处在编辑中（组合文字，带下划线）；空串收掉。 */
    fun composeText(text: String)

    /** 拆词确认：编辑中的字定下来上屏。 */
    fun finishComposeText()

    /** 放进系统剪贴板（拆词的「复制」）。 */
    fun copyToClipboard(text: String)

    fun toast(message: String)

    /** 剪贴板最多存多少条（设置里的）。 */
    val clipboardLimit: Int
}
