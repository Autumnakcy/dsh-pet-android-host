# DSH 桌宠悬浮窗（Android 宿主）

把 [dsh-pet] 的**桌面端渲染页原样搬进 Android 悬浮窗**的宿主 App：
不透明、可拖动、右键菜单、气泡、余额、对话……业务逻辑全在 dsh-pet 本体里，
这个仓库只做最难也最脏的那一层 —— **窗口、触摸、坐标**。

- 包名 `com.dshmobile.pethost`
- 版本 **6.11**（versionCode 46）
- 无 Gradle、无第三方依赖，一个 `build.sh` 直接出 APK
- 在 OxygenOS/ColorOS + Android 16 上验证

---

## 它做了什么

| 层 | 做法 |
|---|---|
| 渲染 | 一个 `WebView` 加载 dsh-pet 的 `runtime/electron-helper` 桌面页，页面一行不改 |
| 窗口 | 浮动 `WindowManager` 窗口；默认**不透明**（可触摸），菜单/气泡时按需长大 |
| 触摸 | 触摸窗收到原始坐标 → 通过 `evaluateJavascript` 转成页面里的 pointer 事件；拖拽/抛掷/菜单级联全部沿用上游 |
| 坐标 | 页面 CSS px ↔ 物理 px（`dpr`）；窗口真实位置每 90ms 推给页面（`window.__petHostWinXY`），不用 `window.screenX`（这个 WebView 里恒为 0） |
| 对话 | 界面是页面里的原版 dsh-pet 弹窗；键盘由**透明 Activity** 托住（悬浮窗拿不到 IME），输入经桥回灌 |
| 横竖屏 | `onConfigurationChanged` 重读屏幕/系统栏尺寸，经 `applyDeskGeometry()` + `relayout()` 热推给页面，不重载 |
| 自启动 | `BOOT_COMPLETED` 接收器 + 控制面板开关；ROM 那层「自启动权限」由用户手动开（系统不提供读写 API） |
| 面板 | 原生控制面板：权限状态实时显示、一键跳授权；背景图从相册选（拷进 App 私有目录，60% 压暗层 + 半透明卡片保证白字可读） |

## 两种窗口模型

- **模式 B（默认，`board`）**：窗口 = 她身体 + 6px 容差，跟着她走；菜单/气泡出现时临时放大。
  好处是平时只挡那一小块；代价是窗口尺寸在变，极端情况下会有 1~2 帧错位。
- **模式 A（无障碍，`a11y`）**：`TYPE_ACCESSIBILITY_OVERLAY` 全屏显示窗（穿透、不会被 ROM 压暗）
  \+ 独立触摸窗。窗口尺寸恒定 ⇒ 物理上不可能回弹。需要在系统设置里给本 App 开无障碍服务
  （只借窗口权限，不读屏幕内容）。

切换方式：控制面板，或改 `/sdcard/.dsh-phone/pet-tune.json` 的 `win.mode`（`board` / `a11y`）。

## 目录

```
build.sh                        一键构建（javac → d8 → aapt → zipalign → apksigner）
app/AndroidManifest.xml         minSdk 28 / targetSdk 28
app/src/com/dshmobile/pethost/
  PetService.java               核心：两个悬浮窗、Bridge、几何、调参循环、横竖屏
  PetA11y.java                  模式 A 的无障碍服务（只借窗口权限）
  MainActivity.java             控制面板（开关/权限状态/自定义背景/自启动）
  ChatInputActivity.java        隐形输入代理（2dp 透明 Activity，托住输入法）
  BootReceiver.java / Boot.java 开机自启动 + 开关落盘
  Shot.java                     截屏诊断（可选）
app/assets/host-shim.js         页面补丁：手势转发 / 命中区上报 / 窗口收窄 / 探针 / DIAG
app/assets/host-extra.js        页面补丁：菜单文案与点击、藏悬停名字气泡、禁 Ctrl+左键
app/assets/pet-config.jsonc     dsh-pet 用户层配置（首启 PUT 一次）
docs/NOTES.md                   踩坑笔记：为什么这样写、别再退回哪些写法
```

`assets/host-shim.js` 会被宿主在 `shouldInterceptRequest()` 里拦截后直接下发，
所以**装哪个 APK 就跑哪一版补丁**，和服务器上那份无关。

## 构建

依赖（都在 `PATH` 里）：

- JDK 17（`javac` / `java` / `keytool`）
- Android build-tools：`aapt`、`zipalign`、`apksigner`
- `android.jar`（API 34 编译用）与 `r8.jar`（D8）

```bash
export ANDROID_JAR=/path/to/android.jar    # 默认 dl/android.jar
export R8_JAR=/path/to/r8.jar              # 默认 dl/r8.jar
bash build.sh
# → out/pethost.apk
```

签名：仓库**不带** `keystore.jks`；第一次构建会自动生成一把本地调试钥（口令写在脚本里）。
要发布请用自己的正式签名，并且**永远不要把私钥提交进公开仓库**。

## 运行前提

1. **悬浮窗权限**（`SYSTEM_ALERT_WINDOW`）—— 控制面板里一键跳转授权；
2. 可选：**无障碍服务**（模式 A 才需要）；
3. 可选：**自启动** —— 控制面板「开机自动启动」开关 + 跳系统自启动管理页；
   ColorOS 上后者在「手机管家 → 应用自启动管理」，不给的话重启后她不会自己回来。

## 和 dsh-pet 的关系

本仓库只有 **Android 宿主**。页面、动画、菜单、余额、碎碎念、对话都来自 dsh-pet
（`dsh-pet` 的 `runtime/electron-helper/`），宿主通过 `window.PetHost` 这个桥把
「窗口级鼠标」换成「宿主转发的手势」。没有 dsh-pet 的页面，这个 App 只是一个空窗。

## 已知坑

见 [docs/NOTES.md](docs/NOTES.md)。几条最容易再踩的：

- 带 `FLAG_NOT_TOUCHABLE` 的 `TYPE_APPLICATION_OVERLAY` 窗口在 ColorOS 上会被压暗约 19%
  （所以默认模型是「可触摸、不透明」）；
- **无障碍服务不能由 App 自己打开**——只能跳设置页让用户点，所以面板做的是「跳对的那一页 + 回显状态」；
- 从相册选来的 `content://` 授权不能长期持有（撤销/重启就失效），**要立刻把字节拷进自己的私有目录**再解码；
- 大图必须先 `inJustDecodeBounds` 量尺寸、按 2 的幂降采样 + `RGB_565` 再解码，几千万像素的相机原图直接 `decodeStream` 会 OOM；
- `targetSdk 28` 时 `WindowInsets.getInsets()` 走 legacy 口径、**返回全 0**，别直接采信；
- WebView 的 `::-webkit-media-controls-*` 伪元素必须**一条规则一条**写，逗号列表里有一个不识别，整条会被静默丢弃；
- `element.textContent` 会把 `display:none` 的文字也算进去，判等要取标题那一段。

## 更新日志

- **6.11** 控制面板：无障碍权限独立成一个按钮（跳对的那一页 + 实时回显开关状态）；
  面板背景可换成相册里的图（存 App 私有目录，60% 压暗 + 半透明卡片）。
- **6.10** 修「有概率变透明」：建窗与重建共用 `renderWindowFlags()`，模式 B 恒为可触摸；
  「三个 WindowManager 都摘不掉」的日志刷屏也一起治了。
- **6.9** 修「关掉再打开就多一只 / 叠着静止影子」；面板「重启悬浮窗」改走同实例 `recreateWindows()`。
- **6.7 / 6.8** 修「有时两个她 / 有时半透明」；模式 A 自检不再因页面没加载误降级到模式 B。
- **6.6** 首个公开版本：透明 Activity 托住输入法、横竖屏热切换、开机自启动。
- 更早的 6.1–6.5 见 git 历史与 Release 说明。
