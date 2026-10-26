package com.activitylock.app.core

import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 自动锁闭的「待锁表」持久化 —— v1.29.0 修 S9 / S10。
 *
 * ## 为什么必须有这个文件
 *
 * 三张待锁表（解锁后 / 退出后 / 锁屏后）原本全是**纯内存**：
 * ```
 * AutoRelock.pendingRelock        = mutableStateOf<Map<String, Long>>   // 内存
 * AutoRelockService.exitPending      = HashMap<String, Long>            // 内存
 * AutoRelockService.screenOffPending = HashMap<String, Long>            // 内存
 * ```
 * 服务是 `START_STICKY`，被系统回收后会重建 —— 但表是空的。
 * 于是「延迟越长、撞上进程回收的概率越高、越必然失效」：
 * 5 分钟档基本能锁上，30 分钟档几乎不生效。用户感知就是「设 30 分钟没用」。
 *
 * ## 计时怎么表达
 *
 * 统一写成「已累计 + 当前段起点」，一条公式覆盖两种语义：
 * ```
 * 已过时长 = accMs + (since > 0 ? now - since : 0)
 * ```
 *  - **解锁后 / 退出后**：`accMs = 0`、`since = 记账时刻`，一路走不停（不需要暂停）。
 *  - **锁屏后**：熄屏时 `since = 熄屏时刻`；亮屏时 `accMs += (now - since)` 且 `since = 0`
 *    —— 这就是 S10：亮屏只**暂停**、不清零，语义从「连续熄屏 N 分钟」改成「累计熄屏 N 分钟」。
 *    原来的实现是亮屏就整批丢弃，用户中途看一眼时间，30 分钟永远到不了。
 *
 * ## 跨重启怎么办
 *
 * `SystemClock.elapsedRealtime()` 开机从 0 开始，重启后旧值毫无意义。
 * 所以每条记录额外存 `boot`（开机标识 = wall - elapsed）和 `wall`：
 *  - 同一次开机（标识差 < 2 分钟）→ 用 elapsed 续算，**不受用户改时间影响**；
 *  - 跨了重启 → 判为已到期（`accMs` 灌满），恢复后立即锁回。
 *    这是有意的保守取舍：重启完手机、之前临时解锁的界面本来就该全部锁上，
 *    「宁可早锁」在这里没有副作用，比「静默漏锁」好得多。
 */
@Serializable
data class PendingRec(
    /** 目标键：`pkg/cls`；退出链路为包级（cls 为空）。 */
    val key: String,
    /** 归属链路，取 [AutoRelockStore.LINK_UNLOCK] / [AutoRelockStore.LINK_EXIT] / [AutoRelockStore.LINK_SCREEN_OFF]。 */
    val lane: Int = AutoRelockStore.LINK_UNLOCK,
    /** 已累计计入的时长（毫秒）。 */
    val accMs: Long = 0L,
    /** 当前计时段起点（elapsedRealtime）；0 = 暂停中（仅锁屏链路会用）。 */
    val since: Long = 0L,
    /** 记账时刻的 wall clock，跨重启兜底与排障用。 */
    val wall: Long = 0L,
    /** 记账时刻的开机标识，用来判断是否跨了重启。 */
    val boot: Long = 0L,
) {
    /** 至今已计入的时长。 */
    fun elapsedMs(now: Long): Long =
        accMs + if (since > 0L) (now - since).coerceAtLeast(0L) else 0L

    /** 该链路是否已经达到延迟。 */
    fun isDue(now: Long, delaySec: Int): Boolean =
        delaySec >= 0 && elapsedMs(now) >= delaySec * 1000L
}

/** 三张表的整体快照，一次读写，避免三份文件各写各的导致互相覆盖。 */
@Serializable
data class PendingSnapshot(
    val unlock: List<PendingRec> = emptyList(),
    val exit: List<PendingRec> = emptyList(),
    val screenOff: List<PendingRec> = emptyList(),
    val v: Int = 1,
)

/**
 * 开机标识工具。
 *
 * `wall - elapsed` 在同一次开机内是常数（误差在秒级），跨重启会跳一个不小的量。
 * 不追求绝对准确，只用来区分「这次开机」和「上次开机」。
 */
object BootClock {

    /** 当前开机标识。 */
    fun mark(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * 记录是否属于「同一次开机」。
     *
     * 容差 2 分钟：`elapsedRealtime` 含深度休眠时间，而 `currentTimeMillis` 不会因为
     * NTP 校时大幅跳变（`SystemClock` 本身也会吸收小步调整），正常误差远小于此。
     * 超出容差一律按跨重启处理 —— 也就是「立即锁回」。
     */
    fun sameBoot(saved: Long): Boolean {
        if (saved <= 0L) return false
        val diff = mark() - saved
        return diff > -120_000L && diff < 120_000L
    }
}

/**
 * 待锁表的落盘读写。
 *
 * 不分文件、同一次写整体快照：三张表的写入时机高度重叠（tick 里经常一起变），
 * 分开写既多 IO 又容易出现「A 段写过、B 段还是旧的」的半新半旧状态。
 */
class RelockPendingStore(filesDir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(filesDir, "relock_pending.json")

    @Synchronized
    fun load(): PendingSnapshot = runCatching {
        if (file.exists()) json.decodeFromString<PendingSnapshot>(file.readText())
        else PendingSnapshot()
    }.getOrDefault(PendingSnapshot())

    @Synchronized
    fun save(snap: PendingSnapshot) {
        runCatching { file.writeText(json.encodeToString(snap)) }
    }

    /** 读-改-写，供只关心其中一张表的调用方使用。 */
    @Synchronized
    fun update(block: (PendingSnapshot) -> PendingSnapshot) {
        save(block(load()))
    }

    /** 三张表全空时删掉文件，避免留下无意义的小文件。 */
    @Synchronized
    fun clearIfEmpty() {
        runCatching {
            val s = load()
            if (s.unlock.isEmpty() && s.exit.isEmpty() && s.screenOff.isEmpty()) {
                file.delete()
            }
        }
    }
}
