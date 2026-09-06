# 应用锁 ActivityLock

<div align="center">

**一款借助 Shizuku 的 Android 界面锁定工具**

锁定任意应用的任意界面 · 屏蔽信息流推荐 · 保持其他功能可用

</div>

---

## 这是什么

「应用锁」可以禁用/拦截**任意应用中的任意界面（Activity）**——比如短视频 App 的首页推荐流、朋友圈时间线、微博闪屏页——被锁定的界面将**无法启动**，而应用的其他功能完全不受影响。

典型用途：**自律工具**。锁住推荐流，但保留搜索功能——想用 App 时从搜索进入，避免被推荐内容无限占用时间。

> 例：锁定小红书首页后，点桌面图标不会有任何反应；通过搜索页进入小红书，其他功能照常使用。

## 功能

- 🔍 **全局搜索**：按 App 名称 / 包名 / Activity 类名实时搜索，搜到即可锁定
- 🔒 **双引擎锁定**：优先组件禁用（`pm disable-user`），在 HyperOS 等受限系统上自动切换为**系统级启动拦截**（`IActivityController`），全程免 Root
- ⭐ **收藏夹**：锁定记录集中管理，支持备注、解锁与删除分离、状态看板与一键修复
- 🕐 **锁定历史**：所有操作留痕，收藏夹删掉的条目也能从历史找回并一键重新锁定
- 🪟 **Activity 查询**（可选）：无障碍悬浮条实时显示当前界面的包名/类名，点击复制，帮你找到想锁的目标
- 📛 **解锁验证**：可选的自律模式——解锁前强制倒计时或输入密码，防止冲动解锁
- 🎛 **快捷磁贴**：控制中心「全部上锁」「全部解锁」两个磁贴
- 🔗 **URL Scheme 库**：常用 App 深链查询、复制与测试
- 🌗 Material 3 UI，支持深色模式 / 主题图标

## 下载

前往 [Releases](../../releases) 下载最新 APK 安装即可。

## 使用前提

1. 安装 [Shizuku](https://shizuku.rikka.app/zh-hans/) 并启动（无线调试配对，或电脑 adb 启动）
2. 打开本 App → 设置 → 连接 → 申请授权
3. 到「应用」页搜索目标界面并锁定

### 小米 / HyperOS 用户

- 无需开启「USB 调试（安全设置）」，系统级拦截模式不受该限制
- HyperOS 封锁了 shell 修改组件状态的能力（`Shell cannot change component state`），App 会自动使用拦截模式，效果一致
- 手机重启后，打开一次本 App 即可恢复拦截进程

## 工作原理

1. **组件禁用模式**（大多数设备）：通过 Shizuku 以 shell 身份执行 `pm disable-user --user 0 包名/类名`，由系统持久化禁用该 Activity
2. **系统级拦截模式**（HyperOS 等受限系统）：通过 Shizuku 启动 shell 身份的常驻进程，向 `IActivityManager` 注册 `IActivityController`——Android 为自动化测试预留的全局启动控制器。此后系统每次启动 Activity 都会先询问该控制器，命中锁定清单的启动请求被直接拒绝

两种模式由 App 自动选择，也可在界面状态中查看当前使用的模式。

### 注意事项

- 启动控制器**系统全局唯一**：与其他同类工具（以及 monkey 测试）互斥，后注册者生效
- 拦截模式下若 Watcher 进程被系统回收，App 会在下次启动时自动重新注册（每 30 秒也会自愈重试）
- 个别应用会检测组件状态并自行恢复，这类应用更适合使用拦截模式

## 构建

```bash
git clone https://github.com/<你的用户名>/ActivityLock.git
cd ActivityLock
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 与 Android SDK 34（或直接使用 Android Studio 打开）。推送 tag `v*` 会触发 GitHub Actions 自动构建并发布 Release。

## 项目结构

```
app/src/main/java/com/activitylock/app/
├─ MainActivity.kt              # 导航 + 底部三栏
├─ UnlockGateActivity.kt        # 磁贴全解锁的自律验证页
├─ core/
│  ├─ LockEngine.kt             # 锁定引擎（pm → 拦截 自动降级）
│  ├─ ShizukuManager.kt         # Shizuku 命令封装
│  ├─ AmsController.kt          # 拦截模式控制（spawn/watcher/规则同步）
│  ├─ watcher/WatcherMain.java  # shell 常驻进程（ActivityController 注册）
│  ├─ InspectorService.kt       # 无障碍 Activity 查询悬浮条
│  ├─ LockTileService.kt        # 快捷磁贴 ×2
│  ├─ RuleStore.kt / HistoryStore.kt / VerifyGate.kt
│  └─ ...
└─ ui/screens/                  # Compose 界面
```

## Roadmap

- [x] M1 组件枚举 + Shizuku 双模式锁定
- [x] M2 全局搜索 / 收藏夹 / 备注 / 状态自检
- [x] M3 Activity 查询悬浮条 / URL Scheme 库 / 锁定历史
- [x] M4 快捷磁贴 / 解锁验证（自律模式）
- [ ] M5 离开目标应用后自动恢复锁定
- [ ] 规则导出与导入（换机迁移）
- [ ] 英文界面

## 贡献

欢迎 Issue 与 PR。提交前请运行 `./gradlew :app:assembleDebug` 确保可构建。

## 许可

[GPL-3.0](LICENSE)
