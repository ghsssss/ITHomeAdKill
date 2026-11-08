# ITHomeAdKill — IT之家去广告 Xposed 模块

针对 IT之家 Android 版（包名 `com.ruanmei.ithome`）的 LSPosed 模块。

## 原理

分析发现 IT之家的信息流广告**不走第三方广告 SDK 客户端渲染**（穿山甲/优量汇/
京东联盟等 SDK 均未参与广告展示，实测封锁 SDK 类加载零命中），而是由自家广告
引擎下发普通数据卡片。因此本模块采用**视图级拦截**，共三条规则：

1. **角标广告卡**：广告卡片角标是 `id/tv_small_tags` 的 TextView（普通新闻该
   控件显示时间戳），文字为"广告"时向上找到 RecyclerView 的直接子项（卡片根
   `ShadowLayout`）高度置零。同机制顺带处理"置顶"卡。
2. **事件日历卡**：标题 `id/tv_title` 为"事件日历"的卡片，同样高度置零。
3. **WebView 推广卡**：信息流内嵌 WebView 的推广位（如"无门槛红包"倒计时卡），
   卡片根为 `id/ll_web`，WebView 挂载后整卡高度置零。

## 使用步骤

1. 从 [Releases](https://github.com/ghsssss/ITHomeAdKill/releases) 下载 APK 安装，
   或用 Android Studio 打开项目自行编译
2. LSPosed 管理器 → 模块 → 启用「IT之家去广告」，作用域勾选 IT之家
3. 强制停止 IT之家后重新打开

## 查看拦截日志

LSPosed 管理器 → 日志，过滤 `ITHomeAdKill`，可看到每次卡片隐藏记录，
例如 `已隐藏[广告]卡片(高度置零)`。

## 进阶排查（如果还有漏网广告）

1. 用 `adb shell uiautomator dump` 抓取广告所在页面，找到广告卡片特征
   （角标文字、卡片根 resource-id）
2. 在 `MainHook.kt` 的 `hookAdCardView` / `hookWebViewPromo` 中补充匹配规则
3. 开屏广告、阅读页广告等场景按同样思路扩展

## 注意

- minSdk 26，需 LSPosed（原版 Xposed 不支持新版 Android）
- 隐藏触发词（广告/置顶/事件日历）为精确/前缀匹配，正常新闻不受影响

## 免责声明

本项目仅供技术学习与研究交流使用，不得用于任何商业用途。本项目不修改、不
分发 IT之家 App 本体，不存储任何用户数据。使用者应于下载后 24 小时内自行
删除，因使用本项目产生的任何问题由使用者自行承担。若本项目侵犯了您的合法
权益，请通过 [Issues](https://github.com/ghsssss/ITHomeAdKill/issues) 联系，
确认后将在第一时间停止维护并删除相关内容。
