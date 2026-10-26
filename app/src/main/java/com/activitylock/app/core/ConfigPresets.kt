package com.activitylock.app.core

import kotlinx.serialization.Serializable

/**
 * 一键方案：一个 App 的完整自律配置 = 锁哪个界面 + 拦截后跳哪。
 * builtin 方案随 App 内置；用户方案可自建、修改、删除、导入导出。
 */
@Serializable
data class ConfigPreset(
    val id: String,
    val label: String,
    val packageName: String,
    val activityName: String,
    val redirectUri: String? = null,
    val note: String = "",
    /** 深链落点是否经过真机验证 */
    val verified: Boolean = false,
    val builtin: Boolean = false,
)

/**
 * 内置方案库。容器与深链数据来源：
 * ✅ = 本机（小米 14 HyperOS）真机验证；其余来自公开深链/静态分析，锁定前建议先「测试跳转」。
 */
object BuiltinPresets {

    val all = listOf(
        ConfigPreset(
            "b_xhs", "小红书", "com.xingin.xhs", "com.xingin.xhs.index.v2.IndexActivityV2",
            "xhsdiscover://search/result?keyword=", "屏蔽首页推荐流，点图标直达搜索页；返回键直接退出",
            verified = true, builtin = true,
        ),
        ConfigPreset(
            // ★ 真机验证（2026-10-13）：桌面图标的启动 Activity 是 .splash.SplashActivity，
            //   不是 .main.MainActivity。跳转必须配在**入口**上才会被命中。
            //   另：snssdk1128://search/trending 落的是「抖音热榜」(BulletContainerActivity)，
            //   真正可输入的搜索页是 snssdk1128://search?keyword= (SearchResultActivity)。
            "b_douyin", "抖音", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.splash.SplashActivity",
            "snssdk1128://search?keyword=",
            "锁桌面入口，点图标直达搜索页（真机验证）。建议再锁主容器 MainActivity 做纯拦截（不填跳转），挡掉返回时的推荐流",
            verified = true, builtin = true,
        ),
        ConfigPreset(
            // ★ 真机核对（2026-10-13，resolve-activity）：桌面入口是 .SplashActivity，
            //   不是 .MainTabActivity —— 锁错界面 = 点图标根本不被拦。
            "b_weibo", "微博", "com.sina.weibo", "com.sina.weibo.SplashActivity",
            "sinaweibo://searchall?q=", "屏蔽首页信息流，点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_bili", "哔哩哔哩", "tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2",
            "bilibili://search?keyword=", "屏蔽首页推荐，点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            // ★ 真机核对：桌面入口是 com.taobao.tao.welcome.Welcome（TBMainActivity 是内部主容器）
            "b_taobao", "淘宝", "com.taobao.taobao", "com.taobao.tao.welcome.Welcome",
            "taobao://s.taobao.com/search?q=", "点图标直达搜索路由（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            // ★ 真机核对：桌面入口是 com.taobao.fleamarket.home.activity.InitActivity
            "b_idlefish", "闲鱼", "com.taobao.idlefish", "com.taobao.fleamarket.home.activity.InitActivity",
            "fleamarket://x_search_items?keyword=", "点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_douyin_jx", "抖音精选", "com.ss.android.yumme.video", "com.ss.android.yumme.video.SplashActivity",
            null, "入口整锁（搜索深链未验证，可在收藏夹自行补跳转）",
            builtin = true,
        ),
        ConfigPreset(
            // ★ 真机验证（2026-10-26）：jike://page.jk/search 走 .bu.route.RouteActivity
            //   统一路由，COLD 508ms 落 .bu.search.ui.SearchActivity（独立搜索框）。
            //   authority 唯一定位（page.jk），不会弹系统选择器。
            //   ⚠️ 即刻有 14 个 launcher 图标变体（.standard/.pink/.cat/.neon/...，
            //   换图标功能各占一个 Activity）。这里只锁当前默认的 .standard ——
            //   若用户在即刻里换了图标，桌面入口会变成别的变体，本规则将不再命中。
            "b_jike", "即刻", "com.ruguoapp.jike", "com.ruguoapp.jike.standard",
            "jike://page.jk/search", "锁定入口，点图标直达搜索页（换过图标则需重新锁定新入口）",
            verified = true, builtin = true,
        ),
        ConfigPreset(
            // ★ 真机验证（2026-10-26）推翻旧结论「暂无可直落搜索页的深链」：
            //   ① 隐式 coolmarket://com.coolapk.market/search → 弹系统选择器
            //      （该 scheme 同时注册在 MainActivity〔带 authority+path〕与
            //        AppLinkActivity〔只有 scheme、无限制〕，隐式 Intent 双匹配）
            //   ② 纯组件 .view.search.SuperSearchActivity → 仅 WARM 有效，
            //      COLD 会漂移回 MainActivity 并停在首页信息流
            //   ③ 显式组件 + data（下面这条）→ 稳定落 SuperSearchActivity（COLD 1942ms）✅
            "b_coolapk", "酷安", "com.coolapk.market", "com.coolapk.market.view.main.MainActivity",
            "com.coolapk.market/.view.main.MainActivity|coolmarket://com.coolapk.market/search",
            "锁定首页，点图标直达搜索页（显式组件+深链，锁定前先测试跳转）",
            verified = true, builtin = true,
        ),
        ConfigPreset(
            // ★ 真机核对：桌面入口是 .app.ui.activity.LauncherActivity
            "b_zhihu", "知乎", "com.zhihu.android", "com.zhihu.android.app.ui.activity.LauncherActivity",
            null, "纯拦截：zhihu://search 落地后主容器在上层，不能做跳转",
            builtin = true,
        ),
        ConfigPreset(
            "b_pdd", "拼多多", "com.xunmeng.pinduoduo", "com.xunmeng.pinduoduo.ui.activity.MainFrameActivity",
            "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=",
            "锁定首页容器，跳转搜索结果页（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_weixin", "微信朋友圈", "com.tencent.mm", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
            null, "屏蔽朋友圈时间线（其他功能不受影响）",
            builtin = true,
        ),
    )
}
