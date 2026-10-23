# ============================================================
# 应用锁 · R8 混淆规则
#
# 本工程无 Gradle/AndroidX 之外的第三方框架，反射点集中在：
#   1. kotlinx.serialization（@Serializable 数据类靠生成的 serializer 反射）
#   2. Shizuku（provider / binder 回调）
#   3. Compose 运行时（lambda 稳定性）
#   4. Android 组件（Activity/Service/Receiver/Provider 由 AGP 自动 keep）
# ============================================================

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 保留所有 @Serializable 类的合成 serializer 与 Companion
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留本工程的数据模型（字段名即 JSON key，混淆后会读不出旧文件）
-keep class com.activitylock.app.core.** { *; }

# ---------- Watcher：系统级拦截引擎（★ 必须保留，否则 block 模式全线失效）----------
# WatcherMain 是 shell 侧 app_process 的入口类，只被 AmsController 以**字符串**形式
# 拼进命令：nohup app_process /system/bin com.activitylock.app.watcher.WatcherMain
# R8 的静态分析看不到这条字符串引用，会判定整个 watcher 包是「无用代码」并删除。
# 后果：release 包里 shell 启动 Watcher 时 ClassNotFoundException → 依赖它的功能全部失效：
#   · block 模式（组件禁用被 HyperOS 限制时的兜底拦截）
#   · 跳转深链（点桌面图标直达搜索页）
#   · 专注会话的整包拦截 / 限额超额的整包拦截 / 定时计划
# 实测确认：未加本规则时，R8 的 mapping.txt 中完全没有 watcher 包的条目（即被整包删除）。
# 注意 core.** 的规则覆盖不到这里（watcher 是并列的另一个包）。
-keep class com.activitylock.app.watcher.** { *; }

# ---------- android.app.IActivityController（★ 必须保持原类名）----------
# app/src/main/aidl/android/app/IActivityController.aidl 会在 APK 内生成
# android.app.IActivityController（及 $Stub / $Stub$Proxy）。
# WatcherMain 注册系统级启动控制器时，要用 IActivityController.class 去反射匹配
# framework 里的 IActivityManager.setActivityController(IActivityController, boolean)。
# 这个类名必须与系统 framework 完全一致，绝不能被 R8 重命名。
#
# 实测（未加本规则时）：R8 把它改名为 android.app.c，于是反射查找失败 ——
#   /data/local/tmp/al_watcher.log:
#     FATAL register: java.lang.NoSuchMethodException:
#       android.app.IActivityManager.setActivityController [interface android.app.c, boolean]
#   → WatcherMain 走到 System.exit(2) 直接退出 →
#     系统级拦截（block 模式 / 跳转深链 / 专注会话整包拦截 / 限额整包拦截）全部失效。
# 这是独立于「watcher 包被删」的第二个 R8 陷阱，两者都会让拦截引擎失效。
-keep class android.app.IActivityController { *; }
-keep class android.app.IActivityController$* { *; }

# ---------- Shizuku ----------
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---------- Kotlin 元数据 / 协程 ----------
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# ---------- Compose ----------
-dontwarn androidx.compose.**

# ---------- 通用 ----------
# 保留枚举的 values/valueOf（序列化会用到）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
