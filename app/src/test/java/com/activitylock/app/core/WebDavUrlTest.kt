package com.activitylock.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 地址拼装。
 *
 * 这里出错的代价很具体：用户填 `https://dav.jianguoyun.com/dav/`（带尾斜杠）或
 * `/activitylock/x.json`（带首斜杠）都是常识写法，拼错一次就是 404，
 * 而 404 在界面上会被读成「云端还没有备份」——最难查的那种「看着正常其实是坏的」。
 */
class WebDavUrlTest {

    @Test
    fun trailingSlashAndLeadingSlashCollapse() {
        val c = WebDavSync.Cfg(
            base = "https://dav.jianguoyun.com/dav/",
            path = "/activitylock/backup.json"
        )
        assertEquals("https://dav.jianguoyun.com/dav/activitylock/backup.json", c.remoteUrl())
    }

    @Test
    fun noTrailingSlashStillCorrect() {
        val c = WebDavSync.Cfg(base = "https://dav.jianguoyun.com/dav", path = "a/b.json")
        assertEquals("https://dav.jianguoyun.com/dav/a/b.json", c.remoteUrl())
    }

    @Test
    fun blankPathFallsBackToDefault() {
        val c = WebDavSync.Cfg(base = "https://nc.example.com/remote.php/dav/files/u", path = "  ")
        assertEquals(
            "https://nc.example.com/remote.php/dav/files/u/" + WebDavSync.DEFAULT_PATH,
            c.remoteUrl()
        )
    }

    @Test
    fun spacesInBaseAreTrimmedSoPastedUrlsWork() {
        val c = WebDavSync.Cfg(base = "  https://x.example.com/dav  ", path = "k.json")
        assertEquals("https://x.example.com/dav/k.json", c.remoteUrl())
    }

    @Test
    fun nonAsciiPathIsEncoded() {
        val c = WebDavSync.Cfg(base = "https://x.example.com/dav", path = "我的/备份 文件.json")
        val url = c.remoteUrl()
        assertFalse("URL 里不能出现原始中文", url.contains("我的"))
        assertFalse("空格必须编码成 %20 而不是 +", url.contains("+"))
        assertTrue(url.startsWith("https://x.example.com/dav/"))
        assertTrue(url.endsWith(".json"))
    }

    @Test
    fun schemeChecks() {
        assertTrue(WebDavSync.Cfg(base = "https://a/b").schemeOk)
        assertTrue(WebDavSync.Cfg(base = "http://a/b").schemeOk)
        assertTrue(WebDavSync.Cfg(base = "http://a/b").insecure)
        assertFalse(WebDavSync.Cfg(base = "https://a/b").insecure)
        assertFalse(WebDavSync.Cfg(base = "ftp://a/b").schemeOk)
        assertFalse(WebDavSync.Cfg(base = "").schemeOk)
        assertFalse(WebDavSync.Cfg(base = "").filled)
    }
}
