package com.activitylock.app.core

/**
 * 常用 App 的搜索页深链预设。
 * pkgHints 用于在锁定某 App 的桌面入口时自动推荐跳转目标。
 * 来源：真机验证 + pawaovo/moment-app-configs（89% 实测通过率）+ 社区公开深链。
 */
data class SchemePreset(
    val name: String,
    val uri: String,
    val pkgHints: List<String> = emptyList(),
)

object SchemePresets {

    val all = listOf(
        // —— 核心信息流类 ——
        // 小红书必须带 keyword 参数才会路由到独立搜索页，否则落在首页容器
        SchemePreset("小红书 · 搜索", "xhsdiscover://search/result?keyword=", listOf("com.xingin.matrix")),
        // ★ 真机验证（2026-10-26）：jike://page.jk/search
        //   → com.ruguoapp.jike/.bu.search.ui.SearchActivity（独立搜索框，COLD 508ms）
        //   走 .bu.route.RouteActivity 统一路由，authority 唯一定位，不会弹选择器。
        SchemePreset("即刻 · 搜索", "jike://page.jk/search", listOf("com.ruguoapp.jike")),
        // ★ 真机验证（2026-10-26）酷安 —— 三种写法逐一试过，只有第三种成立：
        //   ① 隐式 coolmarket://com.coolapk.market/search
        //      → 弹系统选择器。该 scheme 同时注册在 MainActivity〔带 authority + path〕
        //        和 AppLinkActivity〔只有 scheme、无任何限制〕，隐式 Intent 双匹配。
        //   ② 纯组件 com.coolapk.market/.view.search.SuperSearchActivity
        //      → 仅 WARM 时有效（103ms）；COLD 会漂移回 MainActivity，视觉落首页信息流。
        //   ③ 显式组件 + data（下面这条）
        //      → 落 SuperSearchActivity，COLD 1942ms ✅
        //   「组件|uri」这种写法由 WatcherMain.buildTargetIntent 解析。
        SchemePreset("酷安 · 搜索",
            "com.coolapk.market/.view.main.MainActivity|coolmarket://com.coolapk.market/search",
            listOf("com.coolapk.market")),
        // ★ 真机验证（2026-10-13）：
        //   snssdk1128://search?keyword=  → SearchResultActivity（搜索页，可输入）
        //   snssdk1128://search/trending  → BulletContainerActivity（抖音热榜）
        //   旧版把 trending 标成「搜索」是错的，点开只会进热榜。
        SchemePreset("抖音 · 搜索", "snssdk1128://search?keyword=", listOf("com.ss.android.ugc.aweme")),
        SchemePreset("抖音 · 热榜", "snssdk1128://search/trending", listOf("com.ss.android.ugc.aweme")),
        SchemePreset("哔哩哔哩 · 搜索", "bilibili://search?keyword=", listOf("tv.danmaku.bili")),
        SchemePreset("快手 · 搜索", "kwai://search?keyword=", listOf("com.smile.gifmaker")),
        SchemePreset("今日头条 · 搜索", "snssdk143://search?keyword=", listOf("com.ss.android.article.news")),
        SchemePreset("微博 · 搜索", "sinaweibo://searchall?q=", listOf("com.sina.weibo")),
        SchemePreset("微博轻享版 · 搜索", "weibointernational://searchall?q=", listOf("com.weico.international")),
        SchemePreset("知乎 · 搜索", "zhihu://search?query=", listOf("com.zhihu.android")),
        SchemePreset("豆瓣 · 搜索", "douban://douban.com/search?q=", listOf("com.douban.frodo")),
        // —— 电商购物类 ——
        SchemePreset("淘宝 · 搜索", "taobao://s.taobao.com/search?q=", listOf("com.taobao.taobao")),
        SchemePreset("天猫 · 搜索", "tmall://page.tm/search?q=", listOf("com.tmall.wireless")),
        SchemePreset("京东 · 搜索", "openjd://virtual?params={\"des\":\"productList\",\"keyWord\":\"\",\"from\":\"search\",\"category\":\"jump\"}", listOf("com.jingdong.app.mall")),
        SchemePreset("拼多多 · 搜索", "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=", listOf("com.xunmeng.pinduoduo")),
        SchemePreset("闲鱼 · 搜索", "fleamarket://x_search_items?keyword=", listOf("com.taobao.idlefish")),
        SchemePreset("什么值得买 · 搜索", "smzdm://search?json={\"channelName\":\"home\",\"search_type\":\"1\",\"keyWord\":\"\"}", listOf("com.smzdm.client.android")),
        // —— 本地生活 ——
        SchemePreset("美团 · 搜索", "imeituan://www.meituan.com/search?q=", listOf("com.sankuai.meituan")),
        SchemePreset("饿了么 · 搜索", "eleme://search?keyword=", listOf("me.ele")),
        SchemePreset("大众点评 · 搜索", "dianping://shoplist?keyword=", listOf("com.dianping.v1")),
        SchemePreset("携程 · 搜索", "ctrip://wireless/search?keyword=", listOf("ctrip.android.view")),
        SchemePreset("高德地图 · 搜索", "amapuri://search/general?keyword=", listOf("com.autonavi.minimap")),
        // —— 工具与其他 ——
        SchemePreset("网易云音乐 · 搜索", "orpheus://search?key=", listOf("com.netease.cloudmusic")),
        SchemePreset("高德 · 路线", "amapuri://search/general?keyword=", emptyList()),
        SchemePreset("豆包 · 提问", "doubao://flow/chat?question=", listOf("com.larus.nova")),
        SchemePreset("Kimi · 提问", "kimi://chat?name=chat&prompt=", listOf("com.moonshot.kimichat")),
        SchemePreset("微信 · 扫一扫", "weixin://dl/scan", listOf("com.tencent.mm")),
        SchemePreset("支付宝 · 扫一扫", "alipayqr://platformapi/startapp?saId=10000007",
            listOf("com.eg.android.AlipayGphone")),
    )

    /** 与包名匹配的预设（按提示词前缀匹配，覆盖子包/极速版等变体）。 */
    fun suggestFor(pkg: String): List<SchemePreset> =
        all.filter { p -> p.pkgHints.any { h -> pkg == h || pkg.startsWith("$h.") } }
}
