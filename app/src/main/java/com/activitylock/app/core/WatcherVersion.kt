package com.activitylock.app.core

/**
 * Watcher 版本单一来源。
 * WatcherMain（Java）与 AmsController 都从这里取值——
 * 此前两处各自维护常量，多次升级时漏改一处导致 Watcher 永不换代。
 *
 * 变更记录：
 * - 22 → 23（v1.28.0）：WatcherMain 新增「组件|uri」目标格式 + 自我跳转豁免窗口
 *   （SELF_JUMP_UNTIL / SELF_JUMP_WINDOW_MS）。**改了 WatcherMain 行为就必须抬这个数**，
 *   否则正常升级时 isRunning() 为 true、旧进程不会被替换，新格式会静默失效
 *   （只有手动 kill 掉 watcher 才会换上新代码）。
 */
object WatcherVersion {
    const val VERSION = 23
}
