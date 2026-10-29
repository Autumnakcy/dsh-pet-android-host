# 踩坑笔记

这份文档记录「为什么这么写」和「别再退回哪些写法」。很多条都是真机上测出来、并且有反例的，
改之前先看一眼，能省一晚上。

---

## 1. 坐标模型

- 页面 CSS px ↔ 物理 px 用 `dpr`（本机 3.5）。宿主往页面里推的几何一律是**物理像素**。
- `window.screenX / screenY` 在这个 WebView 里**恒为 0**，不能用来算窗口位置。
  真实位置由宿主每 90ms 通过 `window.__petHostWinXY = [x, y]` 推给页面。
- `Bridge.setBounds(x, y, w, h, boxX, boxY, size, ...)`：`x/y/w/h` 是**窗口矩形**（物理像素）；
  `boxX/boxY` 是「包围盒在工作区里的坐标」，**不是窗口内边距** —— 早期当内边距用，结果命中区整片偏左下。
- dsh-pet 的模型：`窗口 = size + margin.l + margin.r`，`margin` **允许为负数**
  （负 margin 就是「把窗口裁到只剩想留的那条」，收窄窗口全靠它）。

## 2. 触摸与命中

- 页面自报 `.pet-hit` 矩形（`setBody`）+ 可点控件矩形（`setInputRegions`），宿主据此铺触摸窗。
- 菜单项如果只是 `<span>`，而展开子面板靠 `mouseenter`（**不冒泡**），事件打在 span 上就永远收不到。
  解法：落点先 `closest('.dsh-pet-menu-item')`，并注入
  `.dsh-pet-menu-item>* { pointer-events: none }`，让整行吃事件。
- 菜单是一次性重建的：用 `MutationObserver` + 兜底定时器盯，别只挂一次。

## 3. 两种窗口模型

- **模式 B（窗口跟着她跑）**：窗口尺寸随她身体/气泡变化。简单，但尺寸和画布偏移可能撕到不同帧，
  快速开关菜单时能看到 2~3 帧回弹；菜单按「窗口 ∩ 工作区」夹取，窗口半途改大小会让菜单跑到屏幕外。
- **模式 A（无障碍层）**：`TYPE_ACCESSIBILITY_OVERLAY` 的全屏显示窗**即使 `FLAG_NOT_TOUCHABLE` 也不会被压暗**，
  触摸交给独立触摸窗 ⇒ 窗口尺寸恒定，回弹在物理上不可能发生。
  代价：需要用户开无障碍服务；另外要有自检（12s 内页面既没心跳也没报身体矩形 → 自动退回模式 B），
  否则一旦页面没起来，用户看到的就是一层盖满屏幕的透明玻璃。

## 4. ROM 的坑（ColorOS / Android 16）

- 带 `FLAG_NOT_TOUCHABLE` 的 `TYPE_APPLICATION_OVERLAY` 窗口会被合成层压暗约 **19%**
  （同一块白：可触摸 255，穿透 208；`lp.alpha` / `View.alpha` 全是 1，压暗不在我们代码里）。
  想要「不透明」就必须「可触摸」——这是默认模型选「可触摸」的根本原因。
- `TYPE_PHONE(2002)` / `TYPE_SYSTEM_ALERT(2003)` 在 Android 16 对普通 App 直接 `permission denied`。
- 全屏普通悬浮窗可能被判成「遮挡」，弹「可能造成部分操作无响应」。
- `targetSdk 28` 时 `WindowInsets.getInsets()` 走 legacy 口径，**返回全 0**；
  无条件采信会让「状态栏高度」变成 0，宠物能走进状态栏底下。**六个数全 0 就别采信**，退回 `dimen` 口径。

## 5. 横竖屏

- 屏幕尺寸只在 `onCreate` 量一次，是早期最大的疏忽：旋转后页面拿到的仍是竖屏工作区（1272×2603），
  宠物只能在一条竖带里走，右侧不可达、下侧出屏。
- 修法：`onConfigurationChanged` → 重读尺寸 → `applyDeskGeometry({hull, areas, workAreas, panels, primaryIndex})`
  （物理像素）+ `sprites[i].relayout()`。这是桌面端 `petBridge.onDisplays` 的同一条入口，**不用重载页面**。
- 系统栏 insets 偶尔比 configuration 回调晚一拍：隔 700ms 再读一次、再推一次。

## 6. WebView 媒体控件

dsh-pet 的动画是 `<video>`。换源到首帧真正合成之间，WebView 会画
`::-webkit-media-controls-start-playback-button`（暗色矩形 + ▶）。
它不是 DOM 节点，`shadowRoot` 也拿不到，**只有 CSS 伪元素能关**。

两个坑：
1. 一条规则里逗号分隔的选择器列表，**任意一个不被识别，整条规则会被静默丢弃** ——
   每个 `::-webkit-media-controls-*` 必须单独一条规则；
2. 只靠事件（`loadstart`/`loadeddata`）拼时序一定漏：`src` 赋值到事件派发之间有约 20ms 空窗。
   现在的做法是**逐帧判定 `readyState`**：前台那块 <2 就 `opacity:0`，≥2 硬切显示；
   新的没就绪时让旧的继续顶着（没有空档，也不会露出没画面的矩形）。

## 7. 悬停提示在触摸端会「卡住」

`.pet-tooltip`（宠物名字气泡）是给鼠标 hover 用的。手机上触摸结束后指针仍「停」在她身上，
收不到 `pointerleave`，于是气泡常亮、还跟着她走。

只注入一条 CSS 是不够的（实测出现过「补丁标志位是 1、样式却不在文档里」）。稳妥做法是三层：
CSS + 元素内联三连 `!important` + 定时自愈。**凡是指针悬停驱动的 UI，搬到触摸端都要逐个检查。**

## 8. 对话与输入法

- 悬浮窗默认 `FLAG_NOT_FOCUSABLE`，里面的 WebView 拿不到系统输入法；
  改成可聚焦又会触发 ROM 的遮挡警告。
- 现在的做法：界面仍是页面里的原版 dsh-pet 弹窗（同一份 CSS/DOM），
  键盘由**2dp 全透明的 Activity** 托住，`TextWatcher` 里把草稿回灌给页面，回车走页面的发送逻辑。
- 代价：卡片里没有闪烁光标（真正的编辑框是那个隐形 EditText）。

## 9. 调试通道（可选，自己用时才需要）

宿主内置一个极简 HTTP 调试口（默认 `127.0.0.1:17999`）：`/dump` 看状态、`/eval` 在页面里求值。
页面侧还有「每 3s 主动来拉」的 `pageCmd` 通道（写进调参 JSON），用来跑固定探针 ——
页面有 CSP 禁 `eval`，探针只能预置。
