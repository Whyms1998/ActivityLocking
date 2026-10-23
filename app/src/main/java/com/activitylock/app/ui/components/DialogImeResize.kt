package com.activitylock.app.ui.components

import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 让**当前 Dialog** 的 window 在输入法弹出时收缩（SOFT_INPUT_ADJUST_RESIZE）。
 *
 * ★★ 必须调用在 `Dialog { ... }` / `AlertDialog(text = { ... })` 的**内容 lambda 内部**！
 *   在 Dialog 外面调用时，`LocalView.current` 拿到的是 **Activity** 的视图，
 *   `view.parent` 不是 `DialogWindowProvider`（而是 Window 的 content FrameLayout 或 DecorView）
 *   → window 解析为 null → **静默 no-op**。
 *
 *   v1.14.0 就踩了这个：调用点写在 `Dialog(...)` 之前，看似加了修复，
 *   实测窗口 `mAttrs={... sim={adjust=pan}}` 根本没变，键盘照样盖住「确认解锁」。
 *   （真机证据：按钮 y=2420..2487，键盘顶 y≈1714 → 完全被遮挡。）
 *
 * ★ 验证方法（真机，别只看截图）：
 *   `adb shell dumpsys window windows` → 找对话框那条
 *   `Window #n ... ty=APPLICATION ... fl=DIM_BEHIND` → 看 `mAttrs={... sim={adjust=resize} ...}`。
 *   显示 `adjust=pan` 就是没生效。
 *
 * 注意：`DialogProperties.decorFitsSystemWindows` 必须保持 **true** ——
 *   Android 11+ 对 `false` 的 window 会忽略 adjustResize，
 *   且 Compose Dialog 在 `false` 时还有"输入法不弹出"的已知问题。
 */
@Composable
fun DialogImeResize() {
    val view = LocalView.current
    SideEffect {
        val window = resolveDialogWindow(view)
        if (window == null) {
            Log.w(TAG, "DialogImeResize: 未找到 DialogWindow —— 调用点必须在 Dialog 内容里！" +
                " view=${view.javaClass.simpleName} parent=${view.parent?.javaClass?.name}")
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            Log.i(TAG, "DialogImeResize: softInputMode -> ${window.attributes.softInputMode}")
        }
    }
}

/**
 * 把**当前 Dialog** 做成「贴底弹层」，并保证键盘弹出时整张面板随之上移。
 *
 * 用法（必须成对）：
 * ```
 * Dialog(..., properties = DialogProperties(usePlatformDefaultWidth = false)) {
 *     Box(Modifier.fillMaxWidth()) {          // ★ 只能 fillMaxWidth，绝不要 fillMaxSize
 *         DialogBottomSheet()                 // ★ 必须在内容 lambda 内部
 *         Surface(...) { ... }
 *     }
 * }
 * ```
 *
 * ★★ 为什么不能 `fillMaxSize`（v1.23.0 真机踩到，症状：点数字框后整张弹层消失）：
 *   `fillMaxSize` 会把 Dialog 窗口撑成**全屏高**（窗口高度由内容 wrap 决定）。
 *   键盘弹出时 `ADJUST_RESIZE` 从**底部**收缩窗口（实测 frame 由 2535 变成 1580），
 *   但 Compose 内容**不会跟着重新测量**，贴底的面板仍按 2535 排版 →
 *   整张面板（含「确定」）掉到窗口可视区之外，被直接裁掉 —— 用户看到弹层"消失"。
 *   改成只 wrap 面板高度后，窗口比键盘上方的剩余空间还矮，
 *   系统可以把**整扇窗**上移到键盘之上；再配合 `gravity = BOTTOM` 即始终贴底。
 *
 * ★ 代价：窗口不再覆盖全屏，所以「点遮罩关闭」不再由本窗口接管
 *   （§8.9 的遮罩观感仍由 Dialog 主题的 `DIM_BEHIND` 提供）。返回键仍可关闭。
 */
@Composable
fun DialogBottomSheet() {
    val view = LocalView.current
    SideEffect {
        val window = resolveDialogWindow(view)
        if (window == null) {
            Log.w(TAG, "DialogBottomSheet: 未找到 DialogWindow —— 调用点必须在 Dialog 内容里！")
        } else {
            window.setGravity(Gravity.BOTTOM)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            Log.i(TAG, "DialogBottomSheet: gravity=BOTTOM softInputMode=${window.attributes.softInputMode}")
        }
    }
}

/** 沿 ViewParent 链上溯，找到实现 [DialogWindowProvider] 的宿主（Compose 的 DialogWrapper）。 */
private fun resolveDialogWindow(view: View): Window? {
    var p: ViewParent? = view.parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = (p as? View)?.parent
    }
    return null
}

private const val TAG = "AlkIme"
