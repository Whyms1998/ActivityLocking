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
            "b_douyin", "抖音", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.main.MainActivity",
            "snssdk1128://search/trending", "屏蔽首页推荐流，点图标直达搜索页；返回键直接退出",
            verified = true, builtin = true,
        ),
        ConfigPreset(
            "b_weibo", "微博", "com.sina.weibo", "com.sina.weibo.MainTabActivity",
            "sinaweibo://searchall?q=", "屏蔽首页信息流，点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_bili", "哔哩哔哩", "tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2",
            "bilibili://search?keyword=", "屏蔽首页推荐，点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_taobao", "淘宝", "com.taobao.taobao", "com.taobao.tao.TBMainActivity",
            "taobao://s.taobao.com/search?q=", "点图标直达搜索路由（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_idlefish", "闲鱼", "com.taobao.idlefish", "com.taobao.idlefish.maincontainer.activity.MainActivity",
            "fleamarket://x_search_items?keyword=", "点图标直达搜索（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_douyin_jx", "抖音精选", "com.ss.android.yumme.video", "com.ss.android.yumme.video.SplashActivity",
            null, "入口整锁（搜索深链未验证，可在收藏夹自行补跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_jike", "即刻", "com.ruguoapp.jike", "com.ruguoapp.jike.standard",
            "jike://page.jk/search", "锁定入口，跳转搜索页（锁定前先测试跳转）",
            builtin = true,
        ),
        ConfigPreset(
            "b_coolapk", "酷安", "com.coolapk.market", "com.coolapk.market.view.main.MainActivity",
            null, "屏蔽首页（暂无可直落搜索页的深链，纯拦截）",
            builtin = true,
        ),
        ConfigPreset(
            "b_zhihu", "知乎", "com.zhihu.android", "com.zhihu.android.app.ui.activity.MainActivity",
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
