# 青简 Qingjian · AI 增强版（qingjian-ai）

<p align="center">
  <img src="assets/icon/qingjian-mark.svg" alt="青简竹简图标" height="96">
</p>

> 本仓库是 [qingjian-team/qingjian](https://github.com/qingjian-team/qingjian) 的 fork，在原版「好好输入，顺便多认识一个词」的基础上，为 **Android 端**加入一系列 AI 与效率增强功能。上游的全部能力（整句输入、候选译词、语言学习、多平台）原样保留。

## 与上游的不同（AI 增强功能）

以下功能均为本 fork 新增，全部在 Android 端落地：

| 功能 | 说明 |
|---|---|
| 🔍 **AI 搜题** | 键盘工具栏与悬浮球都能发起：输入题目（或截屏带图），走 OpenAI 兼容接口流式出答案，轻 Markdown 排版、可滚动可复制；搜题历史自动落盘 |
| 📷 **截屏搜题** | 复用系统投屏授权（同意一次后续静默截屏），自动裁剪、直接发搜，答案流式渲染——对着屏幕上的题目一按就出答案 |
| ⚪ **悬浮球** | 任意界面常驻一颗可拖动的小球：点开菜单「搜题 / 截屏搜题 / 收起」；键盘没开也能搜题；透明度 1–100% 无级可调（键盘「常用功能 → 悬浮球」二级页内滑杆实时生效） |
| 🛡 **隐私模式** | 一键开关：开启后录屏/截屏里键盘位置是黑的（FLAG_SECURE），实际打字不受影响 |
| 📝 **答题模式** | 搜题条浮在当前页面上方，不再把页面顶上去；录屏里拍到的是弹窗后面的内容 |
| 🕘 **搜题历史** | App 内查看历史搜题与截图，上限 100 条 |
| ✂️ **功能栏自定义** | 长按功能面板宫格里的图标，拖进键盘功能栏排位置，顺序持久化 |
| ⌨️ **编辑键面板** | 七键改版：← 选中 → 全选 复制 剪切 粘贴；「选中」是开关，按下后 ←→ 逐字扩选 |
| 📄 **提取文字（OCR）** | 悬浮球菜单「提取文字」截一屏，走 vision 接口把画面里的文字原样抄出来（不翻译不总结）；键盘「常用功能」也有入口 |
| 🎙 **语音问答** | 悬浮球菜单或键盘「常用功能」点「语音问答」，说一句题目就自动发搜出答案，不用打字 |
| 📐 **搜题页无极调节** | 悬浮搜题页底部两条滑杆：窗口大小 50–100%、页面不透明度 30–100%，拖动即时生效 |

## 下载

- **Android（本 fork 的主要增强平台）**：见下方「从源码构建」；上游的 [GitHub Releases](https://github.com/qingjian-team/qingjian/releases) 提供 macOS / Windows / Linux / Android 官方包（不含本 fork 的增强功能）。

## 从源码构建（Android）

需要 JDK 17+、Android SDK（platform 36）与 Rust 工具链（cargo + cargo-ndk）：

```bash
git clone https://github.com/puppet0721/qingjian-ai.git
cd qingjian-ai/apps/android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Gradle 会自动跑 `cargo ndk` 编译 Rust core（arm64-v8a）。装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次使用：系统设置里启用「青简」并切换为当前输入法 → 打开青简 App 按引导配置 → 在「键盘设置 → 云端大模型」填入你的 OpenAI 兼容接口地址、模型名与 API Key（AI 搜题/截屏搜题共用这份配置，密钥存于 Android Keystore）。

## 配置 AI 服务

AI 功能使用 OpenAI 兼容接口（如 DeepSeek、OpenAI、本地 vLLM 等）：

1. 打开青简 App → 键盘设置 → 云端大模型
2. 填入 base_url（如 `https://api.deepseek.com`）、模型名、API Key
3. 键盘工具栏点 🔍 即可开始搜题；悬浮球菜单的「截屏搜题」也用同一份配置

密钥经 Android Keystore 加密存储，不上传、不进日志。

## 隐私

- 打字、词库、学习数据全部留在本机，与上游一致
- AI 搜题只把你主动发起的题目（文字/截图）发给**你自己配置的** AI 服务商，不经过任何中间服务器
- 隐私模式开启时，录屏与截屏拍不到键盘内容

## 与上游同步

本 fork 基于 upstream `main`（当前 `9c0d447`）。同步上游：

```bash
git remote add upstream https://github.com/qingjian-team/qingjian.git
git fetch upstream && git merge upstream/main
```

## 许可

与上游一致：[GPL-3.0-or-later](LICENSE)。项目名称与 logo 不包含在代码授权中；随包数据有各自的来源与许可，见[数据来源清单](docs/design/landscape.md)。

---

### 上游 README

原版青简的完整介绍（多语言、下载渠道、数据与隐私说明、参与开发）见：

- 简体中文 | [繁體中文](docs/i18n/README.zh-Hant.md) | [English](docs/i18n/README.en.md) | [日本語](docs/i18n/README.ja.md) | [Español](docs/i18n/README.es.md) | [Tiếng Việt](docs/i18n/README.vi.md) | [Deutsch](docs/i18n/README.de.md)
- 官方网站：https://qingjian.app
