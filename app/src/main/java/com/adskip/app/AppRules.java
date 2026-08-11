package com.adskip.app;

/**
 * App 专属跳过规则库（内置版）
 * 数据来源：SKIP 开源项目规则库（https://github.com/GuoXiCheng/SKIP）
 * 已提取主流 App 的高置信规则，按 packageName 精确匹配，绝不误触
 */
public class AppRules {

    /** 单个 App 的规则 */
    public static class Rule {
        public final String pkg;
        public final String[] ids;      // 资源ID（viewId 结尾匹配，如 "btn_skip" 匹配 "...:id/btn_skip"）
        public final String[] texts;    // 文本（包含匹配）
        public final int[] textMaxLen;  // 文本最大长度，0=不限（与 texts 一一对应）
        public final float[] bounds;    // 固定坐标兜底 [left,top,right,bottom]（屏幕比例 0-1），null=无

        Rule(String pkg, String[] ids, String[] texts, int[] textMaxLen, float[] bounds) {
            this.pkg = pkg;
            this.ids = ids == null ? new String[0] : ids;
            this.texts = texts == null ? new String[0] : texts;
            this.textMaxLen = textMaxLen == null ? new int[0] : textMaxLen;
            this.bounds = bounds;
        }
    }

    /** 全部内置规则 */
    public static final Rule[] RULES = {
            // 哔哩哔哩
            new Rule("tv.danmaku.bili",
                    new String[]{"count_down", "btn_skip", "skip_view"}, null, null, null),
            // 知乎
            new Rule("com.zhihu.android",
                    new String[]{"btn_skip", "skip_view", "ad_skip"}, null, null, null),
            // 什么值得买
            new Rule("com.smzdm.client.android",
                    new String[]{"tv_skip"}, null, null, null),
            // 铁路12306
            new Rule("com.MobileTicket",
                    new String[]{"tv_skip"}, null, null, null),
            // 京东读书
            new Rule("com.jd.app.reader",
                    new String[]{"mJumpBtn", "mJumpButton"}, null, null, null),
            // 豆瓣（按钮无法选中，需坐标兜底）
            new Rule("com.douban.frodo",
                    new String[]{"ad_mark"}, null, null, new float[]{0.85f, 0.05f, 0.98f, 0.12f}),
            // CSDN
            new Rule("net.csdn.csdnplus",
                    new String[]{"tt_splash_skip_btn", "vlion_ad_closed"}, null, null, null),
            // 爱奇艺（跳过按钮文本是"关闭"）
            new Rule("com.qiyi.video",
                    null, new String[]{"关闭"}, new int[]{4}, null),
            // 爱奇艺极速版
            new Rule("com.qiyi.video.lite",
                    null, new String[]{"关闭"}, new int[]{4}, null),
            // 今日头条
            new Rule("com.ss.android.article.news",
                    null, new String[]{"跳过"}, new int[]{4}, null),
            // 酷安
            new Rule("com.coolapk.market",
                    new String[]{"tt_splash_skip_btn"}, null, null, null),
            // 菜鸟
            new Rule("com.cainiao.wireless",
                    new String[]{"homesplash_close_fullscreen", "tt_splash_skip_btn"}, null, null, null),
            // 虎扑
            new Rule("com.hupu.games",
                    new String[]{"tt_splash_skip_btn", "tv_time"}, null, null, null),
            // 民生银行（倒计时文本）
            new Rule("cn.com.cmbc.newmbank",
                    new String[]{"view_count_down"}, null, null, null),
            // 前程无忧
            new Rule("com.job.android",
                    new String[]{"skipBtn"}, null, null, null),
            // 帆书
            new Rule("io.dushu.fandengreader",
                    new String[]{"layout_skip"}, null, null, null),
            // 大麦
            new Rule("cn.damai",
                    new String[]{"homepage_advert_pb"}, null, null, null),
            // 华尔街见闻
            new Rule("com.wallstreetcn.news",
                    new String[]{"iv_jump"}, null, null, null),
            // VIVO应用商店
            new Rule("com.bbk.appstore",
                    new String[]{"vbutton_title"},
                    new String[]{"进入首页"}, new int[]{5}, null),
            // 汽水音乐
            new Rule("com.luna.music", null, null, null,
                    new float[]{0.85f, 0.05f, 0.98f, 0.12f}),
            // 腾讯微云
            new Rule("com.qq.qcloud",
                    new String[]{"gdt_ad_text"}, null, null,
                    new float[]{0.85f, 0.05f, 0.98f, 0.12f}),
            // 小米音乐
            new Rule("com.miui.player",
                    null, new String[]{"跳过"}, new int[]{4}, null),
            // 嘀嗒出行
            new Rule("com.didapinche.booking",
                    null, new String[]{"跳过"}, new int[]{2}, null),
            // 人人视频
            new Rule("com.example.pptv",
                    new String[]{"iv_close"}, null, null, null),
            // 荣耀时钟
            new Rule("com.hihonor.deskclock",
                    null, new String[]{"跳过"}, new int[]{0}, null),
    };

    /**
     * 按包名查规则，找不到返回 null
     */
    public static Rule findRule(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (Rule r : RULES) {
            if (r.pkg.equals(pkg)) return r;
        }
        return null;
    }
}
