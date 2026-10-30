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
 * - 23 → 24（v1.29.0）：reloadRules() 修 S2 —— 规则缓存改为「解析成功后」才更新。
 *   旧进程会把半截 JSON 记成"已应用"，之后永不重试，表现为「改了设置不生效」。
 *   不抬版本的话，升级后旧进程还在，这个修复等于没打。
 *
 * - 24 → 25（v1.32.2）：
 *   ① 额度说明页需要知道「这次是谁被拦了」——buildTargetIntent / startViaAm
 *      两个通路都给 QuotaGate 补 extra(pkg)，否则多对象超额只能罗列全部；
 *   ② 新增独立**心跳文件**（每 60 秒写一次时间戳）。真机实测：watcher 进程活着、
 *      日志却在某个时刻彻底停写（tick 卡死），拦截还在用旧规则跑，表现为
 *      「改了设置永不生效且毫无提示」。App 侧看门狗据此重启它。
 *
 * - 25 → 26（v1.34.0）：到点跳转「当日状态」落盘（/data/local/tmp/al_session_state.json）。
 *   SESSION_JUMPS（当日已跳转次数）与 SESSION_EXHAUSTED（当日已收走）原本纯内存，
 *   watcher 进程被杀重建后计数归零、「当日收走」失效——当天可无限重刷。
 *   现在状态变更即标脏、每 20 秒随统计一起落盘，重启后 loadSessionState() 恢复
 *   （跨天自动丢弃）。瞬时态（SESSION_ACC / LASTJUMP / LEAVE）不落盘，
 *   它们基于 elapsedRealtime，跨进程没有意义。
 */
object WatcherVersion {
    const val VERSION = 26
}
