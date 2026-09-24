#!/usr/bin/env node
/**
 * 广告跳过匹配引擎模拟器（v1.7.0）
 * 与 AdSkipService.java 的 findSkipButton + canUseBounds + AppRules 逻辑保持一致
 * 用于在电脑上验证命中率 / 误点率，无需手机
 *
 * 用法:
 *   node simulator.js              # 运行内置测试场景
 *   node simulator.js --xml 文件    # 导入 uiautomator dump 的 XML 验证
 */
'use strict';
const fs = require('fs');

// ============================================================
// 1. 匹配规则（与 Java 版完全一致）
// ============================================================

// 通用文本规则 [关键词, 最大长度, 是否仅角落]
const TEXT_RULES = [
    ['跳过', 6, '1'],
    ['关闭广告', 8, '1'],
];

// 纯倒计时
const COUNTDOWN_RE = /^\d{1,2}\s*[s秒]?$/;

// 通用 ID 规则（包含匹配，仅角落）
const ID_RULES = [
    'skip', 'count_down', 'tv_time', 'ad_mark', 'dismiss',
    'tt_splash', 'gdt_ad', 'vlion_ad',
];

// 坐标兜底只在这些 Activity 上生效
const AD_ACTIVITY_HINTS = ['splash', 'advert', 'welcome', 'launch', 'loading', 'guide', 'openad', 'ads'];

// App 专属规则（与 AppRules.java 一致）
const APP_RULES = [
    { pkg: 'tv.danmaku.bili', ids: ['count_down', 'btn_skip', 'skip_view'] },
    { pkg: 'com.zhihu.android', ids: ['btn_skip', 'skip_view', 'ad_skip'] },
    { pkg: 'com.smzdm.client.android', ids: ['tv_skip'] },
    { pkg: 'com.MobileTicket', ids: ['tv_skip'] },
    { pkg: 'com.jd.app.reader', ids: ['mJumpBtn', 'mJumpButton'] },
    { pkg: 'com.douban.frodo', ids: ['ad_mark'], bounds: [0.85, 0.05, 0.98, 0.12] },
    { pkg: 'net.csdn.csdnplus', ids: ['tt_splash_skip_btn', 'vlion_ad_closed'] },
    { pkg: 'com.qiyi.video', texts: ['关闭'], textMaxLen: [4] },
    { pkg: 'com.qiyi.video.lite', texts: ['关闭'], textMaxLen: [4] },
    { pkg: 'com.ss.android.article.news', texts: ['跳过'], textMaxLen: [4] },
    { pkg: 'com.coolapk.market', ids: ['tt_splash_skip_btn'] },
    { pkg: 'com.cainiao.wireless', ids: ['homesplash_close_fullscreen', 'tt_splash_skip_btn'] },
    { pkg: 'com.hupu.games', ids: ['tt_splash_skip_btn', 'tv_time'] },
    { pkg: 'cn.com.cmbc.newmbank', ids: ['view_count_down'] },
    { pkg: 'com.job.android', ids: ['skipBtn'] },
    { pkg: 'io.dushu.fandengreader', ids: ['layout_skip'] },
    { pkg: 'cn.damai', ids: ['homepage_advert_pb'] },
    { pkg: 'com.wallstreetcn.news', ids: ['iv_jump'] },
    { pkg: 'com.bbk.appstore', ids: ['vbutton_title'], texts: ['进入首页'], textMaxLen: [5] },
    { pkg: 'com.luna.music', bounds: [0.85, 0.05, 0.98, 0.12] },
    { pkg: 'com.qq.qcloud', ids: ['gdt_ad_text'], bounds: [0.85, 0.05, 0.98, 0.12] },
    { pkg: 'com.miui.player', texts: ['跳过'], textMaxLen: [4] },
    { pkg: 'com.didapinche.booking', texts: ['跳过'], textMaxLen: [2] },
    { pkg: 'com.example.pptv', ids: ['iv_close'] },
    { pkg: 'com.hihonor.deskclock', texts: ['跳过'], textMaxLen: [0] },
];

function findAppRule(pkg) {
    return APP_RULES.find(r => r.pkg === pkg) || null;
}

// ============================================================
// 2. 匹配引擎（与 Java 版 findSkipButton 一致）
// ============================================================

function zoneOf(node, sw, sh) {
    const cx = (node.bounds[0] + node.bounds[2]) / 2;
    const cy = (node.bounds[1] + node.bounds[3]) / 2;
    if (cx > sw * 0.55 && cy < sh * 0.4) return 0;
    if (cx > sw * 0.55 && cy > sh * 0.55) return 1;
    return 2;
}

function isSmallEnough(bounds, sw, sh) {
    const w = bounds[2] - bounds[0];
    const h = bounds[3] - bounds[1];
    return w <= sw * 0.6 && h <= sh * 0.4;
}

// 角落区域：右侧(x>60%) 且 顶部(上35%) 或 底部(下35%)
function inCorner(bounds, sw, sh) {
    const cx = (bounds[0] + bounds[2]) / 2;
    const cy = (bounds[1] + bounds[3]) / 2;
    return cx > sw * 0.6 && (cy < sh * 0.35 || cy > sh * 0.65);
}

function canUseBounds(pkg, activity) {
    if (!pkg || !activity) return false;
    const a = activity.toLowerCase();
    return AD_ACTIVITY_HINTS.some(h => a.includes(h));
}

/**
 * 返回匹配结果: { match, type: 'app_id'|'app_text'|'text'|'countdown'|'id'|null }
 */
function findSkipButton(nodes, pkg, screen) {
    const sw = screen.w, sh = screen.h;
    const rule = findAppRule(pkg);

    const sorted = [...nodes].sort((a, b) => zoneOf(a, sw, sh) - zoneOf(b, sw, sh));

    for (const node of sorted) {
        const viewId = node.id || '';
        const text = (node.text || '').trim();
        const desc = (node.desc || '').trim();
        const bounds = node.bounds;
        if (!bounds || bounds[2] - bounds[0] <= 0 || bounds[3] - bounds[1] <= 0) continue;
        if (!isSmallEnough(bounds, sw, sh)) continue;

        const corner = inCorner(bounds, sw, sh);

        // 1. App 专属 ID（不限位置）
        if (rule && rule.ids && rule.ids.length && viewId) {
            for (const id of rule.ids) {
                if (viewId.endsWith(id)) return { match: true, type: 'app_id', id };
            }
        }

        // 2. App 专属文本（不限位置）
        if (rule && rule.texts && rule.texts.length) {
            for (let i = 0; i < rule.texts.length; i++) {
                const maxLen = rule.textMaxLen && i < rule.textMaxLen.length ? rule.textMaxLen[i] : 6;
                if (text.includes(rule.texts[i]) && (maxLen === 0 || text.length <= maxLen)) {
                    return { match: true, type: 'app_text', keyword: rule.texts[i] };
                }
            }
        }

        // 以下通用规则仅在角落区域生效
        if (corner) {
            // 3. 通用文本
            for (const t of TEXT_RULES) {
                const keyword = t[0], maxLen = parseInt(t[1]), cornerOnly = t[2] === '1';
                const textHit = text && text.includes(keyword) && text.length <= maxLen;
                const descHit = desc && desc.includes(keyword) && desc.length <= maxLen;
                if ((textHit || descHit) && (!cornerOnly || corner)) {
                    return { match: true, type: 'text', keyword };
                }
            }

            // 4. 纯倒计时
            if (text && text.length <= 3 && COUNTDOWN_RE.test(text)) {
                return { match: true, type: 'countdown', text };
            }

            // 5. 通用 ID（包含匹配）
            if (viewId) {
                const lower = viewId.toLowerCase();
                for (const id of ID_RULES) {
                    if (lower.includes(id)) return { match: true, type: 'id', id };
                }
            }
        }
    }
    return { match: false, type: null };
}

// ============================================================
// 3. 测试场景
// ============================================================

const SCREEN = { w: 1080, h: 2400 };

// 右上角小按钮
const cornerBtn = (l = 0.86, t = 0.02, r = 0.97, b = 0.08) =>
    [Math.round(1080 * l), Math.round(2400 * t), Math.round(1080 * r), Math.round(2400 * b)];

const SCENARIOS = [
    // ---------- 正例：应该命中 ----------
    {
        name: 'B站开屏广告（倒计时按钮）',
        pkg: 'tv.danmaku.bili',
        nodes: [{ id: 'tv.danmaku.bili:id/count_down', text: '跳过 5s', bounds: cornerBtn() }],
        expect: 'app_id',
    },
    {
        name: '爱奇艺开屏（App规则-关闭按钮）',
        pkg: 'com.qiyi.video',
        nodes: [{ id: 'com.qiyi.video:id/splash_btn', text: '关闭', bounds: cornerBtn(0.82, 0.03, 0.95, 0.09) }],
        expect: 'app_text',
    },
    {
        name: '知乎开屏（btn_skip）',
        pkg: 'com.zhihu.android',
        nodes: [{ id: 'com.zhihu.android:id/btn_skip', text: '', bounds: cornerBtn() }],
        expect: 'app_id',
    },
    {
        name: '穿山甲SDK广告（tt_splash_skip_btn，未知名App）',
        pkg: 'com.some.app',
        nodes: [{ id: 'com.byted.pangle.m:id/tt_splash_skip_btn', text: '', bounds: cornerBtn() }],
        expect: 'id',
    },
    {
        name: '通用"跳过"文本按钮（角落）',
        pkg: 'com.unknown.app',
        nodes: [{ id: '', text: '跳过', bounds: cornerBtn() }],
        expect: 'text',
    },
    {
        name: '纯倒计时"3s"（角落）',
        pkg: 'com.unknown2.app',
        nodes: [{ id: '', text: '3s', bounds: cornerBtn() }],
        expect: 'countdown',
    },
    {
        name: '12306开屏（tv_skip）',
        pkg: 'com.MobileTicket',
        nodes: [{ id: 'com.MobileTicket:id/tv_skip', text: '跳过 3s', bounds: cornerBtn(0.8, 0.02, 0.96, 0.1) }],
        expect: 'app_id',
    },
    {
        name: '京东读书（mJumpBtn）',
        pkg: 'com.jd.app.reader',
        nodes: [{ id: 'com.jd.app.reader:id/mJumpBtn', text: '', bounds: cornerBtn(0.85, 0.05, 0.98, 0.12) }],
        expect: 'app_id',
    },
    {
        name: 'App专属规则不限位置：B站count_down在中央也能命中',
        pkg: 'tv.danmaku.bili',
        nodes: [{ id: 'tv.danmaku.bili:id/count_down', text: '', bounds: [400, 1100, 680, 1200] }],
        expect: 'app_id',
    },

    // ---------- 反例：绝对不能点（防误点回归测试） ----------
    {
        name: '负例：广告正文全屏（不应点击）',
        pkg: 'com.unknown3.app',
        nodes: [{ id: '', text: '全场1折起 点击立即抢购', bounds: [50, 400, 1030, 2000] }],
        expect: null,
    },
    {
        name: '负例：列表页"跳过本章"在屏幕中央（修复后不应点）',
        pkg: 'com.unknown4.app',
        nodes: [{ id: '', text: '跳过本章', bounds: [300, 1100, 800, 1200] }],
        expect: null,
    },
    {
        name: '负例："立即体验"角落（这是进广告的按钮，绝不能点）',
        pkg: 'com.unknown5.app',
        nodes: [{ id: '', text: '立即体验', bounds: cornerBtn() }],
        expect: null,
    },
    {
        name: '负例："同意并继续"（协议同意按钮，绝不能点）',
        pkg: 'com.unknown6.app',
        nodes: [{ id: '', text: '同意并继续', bounds: cornerBtn() }],
        expect: null,
    },
    {
        name: '负例：未知名App"关闭"角落（通用规则已删"关闭"，不应点）',
        pkg: 'com.unknown7.app',
        nodes: [{ id: 'com.unknown7.app:id/iv_close', text: '关闭', bounds: cornerBtn() }],
        expect: null,
    },
    {
        name: '负例：弹窗关闭键 id=btn_close（通用ID已删close，不应点）',
        pkg: 'com.unknown8.app',
        nodes: [{ id: 'com.unknown8.app:id/btn_close', text: '', bounds: [700, 900, 900, 1050] }],
        expect: null,
    },
    {
        name: '负例：中央区域 id 含 count_down 的秒杀倒计时（不在角落，不应点）',
        pkg: 'com.unknown9.app',
        nodes: [{ id: 'com.unknown9.app:id/count_down', text: '限时抢购', bounds: [300, 900, 780, 1050] }],
        expect: null,
    },
];

// ============================================================
// 4. 坐标兜底专项（含 Activity 门禁）
// ============================================================

const BOUNDS_CASES = [
    {
        name: '豆瓣开屏广告（Activity=SplashActivity → 允许坐标兜底）',
        pkg: 'com.douban.frodo',
        activity: 'com.douban.frodo.activity.SplashActivity',
        expect: true,
    },
    {
        name: '豆瓣首页（Activity=HomeActivity → 禁止坐标兜底，绝不误点）',
        pkg: 'com.douban.frodo',
        activity: 'com.douban.frodo.activity.HomeActivity',
        expect: false,
    },
    {
        name: '汽水音乐广告页（AdvertActivity → 允许）',
        pkg: 'com.luna.music',
        activity: 'com.luna.music.ad.AdvertActivity',
        expect: true,
    },
    {
        name: '汽水音乐正常页（MainActivity → 禁止）',
        pkg: 'com.luna.music',
        activity: 'com.luna.music.MainActivity',
        expect: false,
    },
];

// ============================================================
// 5. 运行
// ============================================================

function run() {
    let pass = 0, fail = 0;
    console.log('='.repeat(74));
    console.log('广告跳过匹配引擎模拟测试 (v1.7.0)  屏幕: 1080x2400');
    console.log('='.repeat(74));

    console.log('\n【一、匹配规则】');
    for (const s of SCENARIOS) {
        const result = findSkipButton(s.nodes, s.pkg, SCREEN);
        const ok = result.type === s.expect;
        ok ? pass++ : fail++;
        console.log(`${ok ? '✅' : '❌'} [${s.name}]`);
        console.log(`    期望: ${s.expect === null ? '不命中' : s.expect}  实际: ${result.match ? result.type : '未命中'}` +
            (result.type ? `  依据: ${JSON.stringify(result.id || result.keyword || result.text || '')}` : ''));
    }

    console.log('\n【二、坐标兜底 Activity 门禁】');
    for (const b of BOUNDS_CASES) {
        const rule = findAppRule(b.pkg);
        const allowed = !!(rule && rule.bounds) && canUseBounds(b.pkg, b.activity);
        const ok = allowed === b.expect;
        ok ? pass++ : fail++;
        console.log(`${ok ? '✅' : '❌'} [${b.name}]`);
        console.log(`    期望: ${b.expect ? '允许点击' : '禁止点击'}  实际: ${allowed ? '允许点击' : '禁止点击'}`);
    }

    const total = SCENARIOS.length + BOUNDS_CASES.length;
    console.log('\n' + '='.repeat(74));
    console.log(`结果: ${pass}/${total} 通过, ${fail} 失败`);
    console.log('='.repeat(74));

    if (fail > 0) process.exit(1);
}

// XML 导入模式：node simulator.js --xml dump.xml
function runXml(file) {
    const xml = fs.readFileSync(file, 'utf8');
    const nodes = [];
    const re = /<node[^>]*resource-id="([^"]*)"[^>]*text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>/g;
    let m;
    while ((m = re.exec(xml)) !== null) {
        nodes.push({
            id: m[1], text: m[2], desc: m[3],
            bounds: [parseInt(m[4]), parseInt(m[5]), parseInt(m[6]), parseInt(m[7])],
        });
    }
    const pkgMatch = xml.match(/resource-id="([^:]+):id\//);
    const pkg = pkgMatch ? pkgMatch[1] : 'unknown';
    console.log(`解析到 ${nodes.length} 个节点, 包名: ${pkg}`);

    const result = findSkipButton(nodes, pkg, { w: 1080, h: 2400 });
    console.log(result.match
        ? `✅ 命中跳过按钮! 方式: ${result.type}  依据: ${JSON.stringify(result.id || result.keyword || result.text || '')}`
        : '❌ 未找到跳过按钮（可能该界面没有广告，或需要补充规则）');
}

if (process.argv[2] === '--xml') {
    runXml(process.argv[3]);
} else {
    run();
}
