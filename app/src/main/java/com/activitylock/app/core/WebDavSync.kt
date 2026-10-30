package com.activitylock.app.core

import android.content.Context
import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WebDAV 云端同步（坚果云 / Nextcloud / 群晖 / InfiniCLOUD 等通用协议，无第三方 SDK）。
 *
 * ★ 为什么不引 OkHttp：本 App 全程离线，网络只有「上传/下载一个 JSON」这一件事，
 *   `HttpURLConnection` 足够，少一个依赖就少一份体积与供应链风险。
 *
 * ★ 凭证不参与备份：`webdav` 这组 SharedPreferences **故意不在** [BackupStore] 的
 *   白名单里。备份文件会被导出到网盘、微信、U 盘，把云盘密码塞进去等于把钥匙
 *   和锁一起寄出去。换机后重填一次云盘密码，成本极低。
 *
 * ★ 所有方法都是阻塞的，**只能在 IO 线程调用**（主线程会抛 NetworkOnMainThread）。
 */
object WebDavSync {

    private const val TAG = "WebDav"
    private const val PREF = "webdav"
    const val DEFAULT_PATH = "activitylock/backup.json"

    /** 面向用户的同步异常。 */
    class SyncException(message: String) : Exception(message)

    data class Cfg(
        val base: String = "",
        val user: String = "",
        val pass: String = "",
        val path: String = DEFAULT_PATH,
    ) {
        val baseClean: String get() = base.trim().trimEnd('/')
        val pathClean: String get() = path.trim().trimStart('/').ifBlank { DEFAULT_PATH }
        val filled: Boolean get() = baseClean.isNotBlank()
        val schemeOk: Boolean get() = baseClean.startsWith("https://") || baseClean.startsWith("http://")
        /** http 会明文传输账号密码，UI 需要警示。 */
        val insecure: Boolean get() = baseClean.startsWith("http://")

        fun remoteUrl(): String =
            baseClean + "/" + pathClean.split('/').filter { it.isNotBlank() }.joinToString("/") { enc(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------ 凭证

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): Cfg {
        val p = prefs(ctx)
        return Cfg(
            base = p.getString("base", "") ?: "",
            user = p.getString("user", "") ?: "",
            pass = p.getString("pass", "") ?: "",
            path = p.getString("path", DEFAULT_PATH) ?: DEFAULT_PATH,
        )
    }

    fun save(ctx: Context, cfg: Cfg) {
        prefs(ctx).edit()
            .putString("base", cfg.baseClean)
            .putString("user", cfg.user.trim())
            .putString("pass", cfg.pass)
            .putString("path", cfg.pathClean)
            .commit()
    }

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong("last_sync", 0L)

    private fun markSynced(ctx: Context) {
        prefs(ctx).edit().putLong("last_sync", System.currentTimeMillis()).commit()
    }

    // ------------------------------------------------------------ 连接

    private fun openConn(cfg: Cfg, method: String, url: String = cfg.remoteUrl()): HttpURLConnection {
        if (!cfg.schemeOk) throw SyncException("服务器地址要以 https:// 或 http:// 开头")
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Throwable) {
            throw SyncException("地址格式不对：${e.message ?: "无法解析"}")
        }
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.instanceFollowRedirects = true
        if (cfg.user.isNotBlank() || cfg.pass.isNotBlank()) {
            val token = Base64.encodeToString(
                "${cfg.user.trim()}:${cfg.pass}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            c.setRequestProperty("Authorization", "Basic $token")
        }
        c.setRequestProperty("User-Agent", "ActivityLock/${com.activitylock.app.BuildConfig.VERSION_NAME}")
        return c
    }

    private fun toSyncError(e: Throwable): SyncException = when (e) {
        is SyncException -> e
        is javax.net.ssl.SSLException -> SyncException("证书校验失败，确认服务器地址是否正确")
        is java.net.UnknownHostException -> SyncException("找不到服务器，检查地址与网络")
        is java.net.SocketTimeoutException -> SyncException("连接超时，检查网络")
        is java.net.ConnectException -> SyncException("无法连接服务器，检查地址与网络")
        else -> SyncException("网络错误：${e.message ?: e::class.java.simpleName}")
    }

    /**
     * 连通性测试。
     *
     * 直接对目标文件发 GET：200 = 通且云端已有备份；404 = 通但还没有备份；
     * 401/403 = 账号密码或权限不对。比 PROPFIND 简单且更贴近真实用途。
     */
    fun test(cfg: Cfg): String {
        val body = download(cfg)
        return if (body == null) "连接正常，云端还没有备份"
        else "连接正常，云端已有备份（${body.toByteArray(Charsets.UTF_8).size} 字节）"
    }

    /** 下载；云端没有该文件时返回 null。 */
    fun download(cfg: Cfg): String? {
        var c: HttpURLConnection? = null
        try {
            c = openConn(cfg, "GET")
            return when (val code = c.responseCode) {
                200, 203 -> c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                404, 410 -> null
                401 -> throw SyncException("账号或密码不对")
                403 -> throw SyncException("没有权限（检查应用密码或目录权限）")
                else -> throw SyncException("读取失败：HTTP $code")
            }
        } catch (e: Throwable) {
            throw toSyncError(e)
        } finally {
            c?.disconnect()
        }
    }

    /** 上传（覆盖云端同名文件）。 */
    fun upload(ctx: Context, cfg: Cfg, text: String) {
        ensureDirs(cfg)
        var c: HttpURLConnection? = null
        try {
            c = openConn(cfg, "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = text.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            when (val code = c.responseCode) {
                in 200..299 -> markSynced(ctx)
                401 -> throw SyncException("账号或密码不对")
                403 -> throw SyncException("没有写入权限")
                409 -> throw SyncException("目标目录不存在且无法创建")
                else -> throw SyncException("上传失败：HTTP $code")
            }
        } catch (e: Throwable) {
            throw toSyncError(e)
        } finally {
            c?.disconnect()
        }
    }

    /**
     * 逐级创建远端目录。
     *
     * 多数 WebDAV 服务不会自动创建父目录（坚果云就不会）。已存在时返回 405/301，
     * 禁止 MKCOL 的服务器返回 403 —— 这些都不该让上传直接失败，所以只吞掉异常，
     * 真正的成败由随后的 PUT 判定。
     */
    private fun ensureDirs(cfg: Cfg) {
        val segs = cfg.pathClean.split('/').filter { it.isNotBlank() }
        if (segs.size <= 1) return
        var acc = ""
        for (d in segs.dropLast(1)) {
            acc += "/" + enc(d)
            val url = cfg.baseClean + acc + "/"
            var c: HttpURLConnection? = null
            try {
                c = openConn(cfg, "MKCOL", url)
                c.responseCode
            } catch (e: Throwable) {
                Log.w(TAG, "MKCOL $url failed: ${e.message}")
            } finally {
                c?.disconnect()
            }
        }
    }
}
