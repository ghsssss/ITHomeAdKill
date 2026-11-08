# ITHomeAdKill — IT之家去广告 Xposed 模块

针对 IT之家 Android 版（包名 `com.ruanmei.ithome`）的 LSPosed 模块。

## 原理

第一版采用通用性最强的方案：Hook 应用的 `ClassLoader.loadClass`，
命中广告 SDK 包名前缀（穿山甲、优量汇、快手联盟、百度等 18 家）的类直接抛
`ClassNotFoundException`，让广告 SDK 无法初始化、广告请求发不出去。
App 内的广告调用一般包在 try/catch 里，会静默走"加载失败"路径，不影响正常使用。

该方案不依赖 App 的混淆类名，IT之家更新版本后大概率依然有效。

## 使用步骤

1. 用 Android Studio 打开 `ITHomeAdKill` 目录（首次会自动同步依赖）
2. `Build > Build APK(s)` 生成 `app-debug.apk` 并安装
3. 打开 LSPosed 管理器 → 模块 → 启用「IT之家去广告」，作用域勾选 IT之家
4. 强制停止 IT之家后重新打开

## 查看拦截日志

LSPosed 管理器 → 日志，过滤 `ITHomeAdKill`，能看到每次被封锁的 SDK 类名。

## 进阶排查（如果还有漏网广告）

1. 把 `MainHook.kt` 里 `DEBUG_LOG_ACTIVITIES` 改为 `true`，重新编译安装，
   日志里会打印每个打开的 Activity 类名——开屏广告一般是名字含
   `Splash`/`Ad` 的 Activity
2. 用 jadx 反编译 IT之家 APK，搜索 `TTAd`、`GDT`、`AdView` 等关键字，
   把漏掉的 SDK 前缀加进 `blockedPrefixes` 数组
3. 若是信息流里的原生广告（不走独立 SDK 或类名被完全混淆），下一步可引入
   DexKit 按特征定位广告容器类后 `setVisibility(GONE)`，这是第二期的活

## 注意

- `ClassNotFoundException` 若导致 App 闪退（个别 SDK 在关键路径被硬引用），
  从 `blockedPrefixes` 里删掉对应前缀即可定位问题 SDK
- minSdk 26，需 LSPosed（原版 Xposed 不支持新版 Android）
