#!/usr/bin/env node
/**
 * 广告跳过匹配引擎模拟器（v1.6.0）
 * 与 AdSkipService.java 的 findSkipButton + AppRules 逻辑保持一致
 * 用于在电脑上验证规则命中率，无需手机
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

// 通用文本规则 [关键词, 最大长度, 是否仅右上角]
const TEXT_RULES = [
    ['跳过', 8, '0'],
    ['关闭', 5, '0'],
    ['进入首页', 6, '0'],
    ['立即体验', 6, '0'],
    ['知道了', 4, '0'],
    ['同意并继续', 7, '0'],
];

// 纯倒计时
const COUNTDOWN_RE = /^\d+\s*[s秒]?$/;

// 通用 ID 规则（结尾匹配）
const ID_RULES = [
    'skip', 'count_down', 'tv_time', 'jump', 'close',
    'ad_mark', 'dismiss', 'tt_splash', 'gdt_ad', 'vlion_ad',
];

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

/**
 * 模拟节点
 * { id, text, desc, bounds: [left, top, right, bottom], pkg }
 */
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

/**
 * 返回匹配结果: { match: true/false, type: 'app_id'|'app_text'|'text'|'countdown'|'id'|null }
 */
function findSkipButton(nodes, pkg, screen) {
    const sw = screen.w, sh = screen.h;
    const rule = findAppRule(pkg);

    // 排序：右上角 > 右下角 > 其他
    const sorted = [...nodes].sort((a, b) => zoneOf(a, sw, sh) - zoneOf(b, sw, sh));

    for (const node of sorted) {
        const viewId = node.id || '';
        const text = (node.text || '').trim();
        const desc = (node.desc || '').trim();
        const bounds = node.bounds;
        if (!bounds || bounds[2] - bounds[0] <= 0 || bounds[3] - bounds[1] <= 0) continue;

        const cx = (bounds[0] + bounds[2]) / 2;
        const cy = (bounds[1] + bounds[3]) / 2;
        const inTopRight = cx > sw * 0.5 && cy < sh * 0.5;

        // 1. App 专属 ID
        if (rule && rule.ids && rule.ids.length && viewId) {
            for (const id of rule.ids) {
                if (viewId.endsWith(id) && isSmallEnough(bounds, sw, sh)) {
                    return { match: true, type: 'app_id', id };
                }
            }
        }

        // 2. App 专属文本
        if (rule && rule.texts && rule.texts.length) {
            for (let i = 0; i < rule.texts.length; i++) {
                const maxLen = rule.textMaxLen && i < rule.textMaxLen.length ? rule.textMaxLen[i] : 6;
                if (text.includes(rule.texts[i]) && (maxLen === 0 || text.length <= maxLen) &&
                        isSmallEnough(bounds, sw, sh)) {
                    return { match: true, type: 'app_text', keyword: rule.texts[i] };
                }
            }
        }

        // 3. 通用文本
        for (const t of TEXT_RULES) {
            const keyword = t[0], maxLen = parseInt(t[1]), topRightOnly = t[2] === '1';
            const textHit = text && text.includes(keyword) && text.length <= maxLen;
            const descHit = desc && desc.includes(keyword) && desc.length <= maxLen;
            if ((textHit || descHit) && (!topRightOnly || inTopRight) && isSmallEnough(bounds, sw, sh)) {
                return { match: true, type: 'text', keyword };
            }
        }

        // 4. 纯倒计时（右上角）
        if (inTopRight && text && text.length <= 4 && COUNTDOWN_RE.test(text) && isSmallEnough(bounds, sw, sh)) {
            return { match: true, type: 'countdown', text };
        }

        // 5. 通用 ID 规则（包含匹配）
        // 注意：真实 ID 结构是 "包名:关键词_btn"，结尾可能是 _btn/_layout 等，
        // 所以用 contains 而不是 endsWith（否则 tt_splash_skip_btn 永远匹配不到）
        if (viewId) {
            const lower = viewId.toLowerCase();
            for (const id of ID_RULES) {
                if (lower.includes(id) && isSmallEnough(bounds, sw, sh)) {
                    return { match: true, type: 'id', id };
                }
            }
        }
    }
    return { match: false, type: null };
}

// ============================================================
// 3. 测试场景
// ============================================================

const SCREEN = { w: 1080, h: 2400 };  // 1080x2400 常见分辨率

// 辅助：构造右上角小按钮
const cornerBtn = (l = 0.86, t = 0.02, r = 0.97, b = 0.08) =>
    [Math.round(1080 * l), Math.round(2400 * t), Math.round(1080 * r), Math.round(2400 * b)];

const SCENARIOS = [
    {
        name: 'B站开屏广告（倒计时按钮）',
        pkg: 'tv.danmaku.bili',
        nodes: [
            { id: 'tv.danmaku.bili:id/count_down', text: '跳过 5s', bounds: cornerBtn() },
        ],
        expect: 'app_id',
    },
    {
        name: '爱奇艺开屏（关闭按钮）',
        pkg: 'com.qiyi.video',
        nodes: [
            { id: 'com.qiyi.video:id/splash_btn', text: '关闭', bounds: cornerBtn(0.82, 0.03, 0.95, 0.09) },
        ],
        expect: 'app_text',
    },
    {
        name: '知乎开屏（btn_skip）',
        pkg: 'com.zhihu.android',
        nodes: [
            { id: 'com.zhihu.android:id/btn_skip', text: '', bounds: cornerBtn() },
        ],
        expect: 'app_id',
    },
    {
        name: '穿山甲SDK广告（tt_splash_skip_btn）',
        pkg: 'com.some.app',
        nodes: [
            { id: 'com.byted.pangle.m:id/tt_splash_skip_btn', text: '', bounds: cornerBtn() },
        ],
        expect: 'id',
    },
    {
        name: '通用"跳过"文本按钮',
        pkg: 'com.unknown.app',
        nodes: [
            { id: '', text: '跳过', bounds: cornerBtn() },
        ],
        expect: 'text',
    },
    {
        name: '纯倒计时"3s"（右上角）',
        pkg: 'com.unknown2.app',
        nodes: [
            { id: '', text: '3s', bounds: cornerBtn() },
        ],
        expect: 'countdown',
    },
    {
        name: '负例：广告正文（不应点击）',
        pkg: 'com.unknown3.app',
        nodes: [
            { id: '', text: '全场1折起 点击立即抢购', bounds: [50, 400, 1030, 2000] },
        ],
        expect: null,  // 全屏大块 → 不命中，安全
    },
    {
        name: '负例：列表页"跳过本章"（非广告，但位置不在角落）',
        pkg: 'com.unknown4.app',
        nodes: [
            { id: '', text: '跳过本章', bounds: [300, 1100, 800, 1200] },  // 屏幕中央
        ],
        expect: 'text',  // 会命中"跳过"≤8字 —— 说明: 中央位置的"跳过本章"会被误触（注意点）
    },
    {
        name: '12306开屏（tv_skip）',
        pkg: 'com.MobileTicket',
        nodes: [
            { id: 'com.MobileTicket:id/tv_skip', text: '跳过 3s', bounds: cornerBtn(0.8, 0.02, 0.96, 0.1) },
        ],
        expect: 'app_id',
    },
    {
        name: '京东读书（mJumpBtn）',
        pkg: 'com.jd.app.reader',
        nodes: [
            { id: 'com.jd.app.reader:id/mJumpBtn', text: '', bounds: cornerBtn(0.85, 0.05, 0.98, 0.12) },
        ],
        expect: 'app_id',
    },
    {
        name: '豆瓣（ad_mark，节点选不中→坐标兜底）',
        pkg: 'com.douban.frodo',
        nodes: [
            { id: '', text: '', bounds: cornerBtn() },  // 空节点，无属性
        ],
        expect: 'bounds',  // 规则里有 bounds → 坐标兜底
    },
];

// ============================================================
// 4. 运行
// ============================================================

function run() {
    let pass = 0, fail = 0;
    console.log('='.repeat(70));
    console.log('广告跳过匹配引擎模拟测试 (v1.6.0)  屏幕: 1080x2400');
    console.log('='.repeat(70));

    for (const s of SCENARIOS) {
        let result;
        // 坐标兜底检查
        const rule = findAppRule(s.pkg);
        result = findSkipButton(s.nodes, s.pkg, SCREEN);
        if (!result.match && rule && rule.bounds) {
            result = { match: true, type: 'bounds' };
        }

        const ok = result.type === s.expect;
        ok ? pass++ : fail++;
        const mark = ok ? '✅' : '❌';
        console.log(`${mark} [${s.name}]`);
        console.log(`    期望: ${s.expect}  实际: ${result.match ? result.type : '未命中'}` +
            (result.type && result.type !== 'bounds' ? `  依据: ${JSON.stringify(result.id || result.keyword || result.text || '')}` : ''));
    }

    console.log('='.repeat(70));
    console.log(`结果: ${pass}/${SCENARIOS.length} 通过, ${fail} 失败`);
    console.log('='.repeat(70));

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
    // 包名从 XML 里抓（resource-id 前缀）
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
