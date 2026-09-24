package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityWindowInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 广告跳过无障碍服务 v1.7.0
 *
 * 本版核心原则：**宁可漏点，绝不误点**。
 *
 * 与上一版的关键差异：
 * 1. 通用关键词收敛 —— 删掉 "关闭/立即体验/同意并继续/知道了/进入首页" 等危险词，
 *    只保留 "跳过" 系（"立即体验" 是进广告的按钮，"同意并继续" 是同意协议的按钮）
 * 2. 通用规则全部要求 "角落区域"（右上/右下）—— 屏幕中央的 "跳过本章/跳过引导" 不再误触
 * 3. 通用 ID 规则删掉 "close/jump"（contains 匹配太宽，会点掉弹窗关闭键）
 * 4. 坐标兜底（bounds）加三重门禁：必须是广告特征 Activity + 该 App 未被禁用 + 失败即永久禁用
 * 5. 窗口去重不再用 identityHashCode（每次 getRoot 都变，去重形同虚设）
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // ==== 通用文本规则 [关键词, 最大长度, 是否仅角落] ====
    // 只保留 "跳过" 系 —— 这是唯一高置信、且绝不会把广告点出来的词
    private static final String[][] TEXT_RULES = {
            {"跳过", "6", "1"},
            {"关闭广告", "8", "1"},
    };

    // 纯倒计时文本（"3s" / "5秒"），仅角落区域
    private static final Pattern COUNTDOWN_TEXT = Pattern.compile("^\\d{1,2}\\s*[s秒]?$");

    // ==== 通用 ID 规则（包含匹配，仅角落区域） ====
    // 已删除 "close"（会命中任何弹窗关闭键）和 "jump"（已有 App 专属规则）
    private static final String[] ID_RULES = {
            "skip", "count_down", "tv_time", "ad_mark", "dismiss",
            "tt_splash", "gdt_ad", "vlion_ad",
    };

    // 坐标兜底只在这些 "像广告页" 的 Activity 上生效
    private static final String[] AD_ACTIVITY_HINTS = {
            "splash", "advert", "welcome", "launch", "loading", "guide", "openad", "ads",
    };

    // ==== 状态 ====
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 同一窗口防重复点击（基于 pkg+activity，稳定标识）
    private String lastClickedKey = "";
    private long lastClickedAt = 0;
    private static final long KEY_COOLDOWN_MS = 2000;

    // 内容变化防抖（窗口切换永不防抖）
    private long lastContentTime = 0;
    private static final long CONTENT_DEBOUNCE_MS = 30;

    // 快速重试：0 / 100 / 250 / 500 / 800ms
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;
    private static final long[] RETRY_DELAYS = {0, 100, 250, 500, 800};

    // ===== 测试日志 / 结果判定 =====
    private SkipLogDb logDb;
    private String lastClickPkg = "";
    private String lastClickActivity = "";
    private long lastClickRecordTime = 0;
    private boolean lastClickWasBounds = false;
    private static final long RESULT_TIMEOUT_MS = 2000;

    private SharedPreferences prefs;

    // 当前前台 Activity（来自 WINDOW_STATE_CHANGED 事件的 className，
    // 注意：窗口根节点的 getClassName() 返回的是 FrameLayout，不是 Activity 名）
    private String currentActivity = "";

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scan(lastRetryPkg, lastRetryEventType);
        }
    };
    private String lastRetryPkg = "";
    private int lastRetryEventType = 0;
    private String lastMatchType = "text";

    // 点击后 2s 内窗口没变化 → 判定失败
    private final Runnable resultTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastClickRecordTime > 0 && logDb != null) {
                logDb.markLastClickResult(false);
                // 坐标兜底点空了 → 该 App 永久禁用坐标兜底，避免反复误点
                if (lastClickWasBounds && !lastClickPkg.isEmpty()) {
                    prefs.edit().putBoolean("bounds_disabled_" + lastClickPkg, true).apply();
                    Log.w(TAG, "坐标点击无效，永久禁用该 App 的坐标兜底: " + lastClickPkg);
                }
                lastClickRecordTime = 0;
            }
        }
    };

    // ============================================================
    // 事件入口
    // ============================================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        String eventPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // 记录当前前台 Activity（只有窗口状态变化的 className 才是真正的 Activity 名）
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cn = event.getClassName();
            if (cn != null && cn.length() > 0) {
                currentActivity = cn.toString();
            }
        }

        // ===== 结果判定：上一次点击后窗口切换 → 成功 =====
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                lastClickRecordTime > 0 && logDb != null) {
            long elapsed = System.currentTimeMillis() - lastClickRecordTime;
            if (elapsed < RESULT_TIMEOUT_MS) {
                String activity = event.getClassName() != null ? event.getClassName().toString() : "";
                boolean pkgChanged = !eventPkg.equals(lastClickPkg);
                boolean activityChanged = !activity.equals(lastClickActivity);
                logDb.markLastClickResult(pkgChanged || activityChanged);
                lastClickRecordTime = 0;
            }
        }

        // 自己的界面 / 系统界面 / 桌面 → 一律不管
        if (eventPkg.equals(getPackageName()) ||
                eventPkg.equals("com.android.systemui") ||
                eventPkg.contains("launcher") ||
                eventPkg.contains("inputmethod")) {
            return;
        }

        // 窗口切换 → 立即扫描，永不防抖
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(scanRunnable);
            retryCount = 0;
            scan(eventPkg, eventType);
            return;
        }

        // 内容变化 → 30ms 防抖
        long now = System.currentTimeMillis();
        if (now - lastContentTime < CONTENT_DEBOUNCE_MS) return;
        lastContentTime = now;
        handler.removeCallbacks(scanRunnable);
        retryCount = 0;
        scan(eventPkg, eventType);
    }

    // ============================================================
    // 扫描
    // ============================================================

    private void scan(String eventPkg, int eventType) {
        List<AccessibilityNodeInfo> roots = getApplicationWindowRoots();
        if (roots.isEmpty()) {
            logEvent(eventPkg, "", eventType, false, "none", false);
            scheduleRetry(eventPkg, eventType);
            return;
        }

        try {
            String firstPkg = "";
            String firstActivity = "";

            for (AccessibilityNodeInfo root : roots) {
                if (root == null) continue;
                try {
                    String rootPkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                    if (rootPkg.isEmpty() || rootPkg.equals(getPackageName())) continue;

                    // 用事件里拿到的 Activity 名（root.getClassName() 只会返回 FrameLayout）
                    String activity = currentActivity;
                    if (firstPkg.isEmpty()) {
                        firstPkg = rootPkg;
                        firstActivity = activity;
                    }

                    // 同一窗口刚点过 → 跳过（用稳定的 pkg|activity 作为 key）
                    String windowKey = rootPkg + "|" + activity;
                    if (windowKey.equals(lastClickedKey) &&
                            System.currentTimeMillis() - lastClickedAt < KEY_COOLDOWN_MS) {
                        continue;
                    }

                    AccessibilityNodeInfo skipNode = findSkipButton(root, rootPkg);
                    if (skipNode != null) {
                        String matchType = lastMatchType;
                        CharSequence nodeText = skipNode.getText();
                        performSafeClick(skipNode);
                        skipNode.recycle();
                        recordClick(rootPkg, activity, eventType, matchType, false);
                        Log.d(TAG, "点击跳过: " + nodeText + " [" + matchType + "] @ " + rootPkg);
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "扫描窗口出错", e);
                }
            }

            // ===== 坐标兜底（节点树选不中的按钮，如豆瓣/汽水音乐） =====
            if (firstPkg.isEmpty()) firstPkg = eventPkg;
            String boundsKey = firstPkg + "|" + firstActivity;
            boolean boundsAlreadyDone = boundsKey.equals(lastClickedKey) &&
                    System.currentTimeMillis() - lastClickedAt < KEY_COOLDOWN_MS;
            if (!boundsAlreadyDone && canUseBounds(firstPkg, firstActivity)) {
                AppRules.Rule rule = AppRules.findRule(firstPkg);
                if (rule != null && rule.bounds != null) {
                    int sw = getResources().getDisplayMetrics().widthPixels;
                    int sh = getResources().getDisplayMetrics().heightPixels;
                    Rect r = new Rect(
                            (int) (rule.bounds[0] * sw), (int) (rule.bounds[1] * sh),
                            (int) (rule.bounds[2] * sw), (int) (rule.bounds[3] * sh));
                    performGestureClick(r.centerX(), r.centerY());
                    recordClick(firstPkg, firstActivity, eventType, "bounds", true);
                    Log.d(TAG, "坐标兜底点击: " + firstPkg + " @" + firstActivity);
                    return;
                }
            }

            logEvent(firstPkg, firstActivity, eventType, false, "none", false);
            scheduleRetry(firstPkg, eventType);
        } finally {
            for (AccessibilityNodeInfo r : roots) {
                if (r != null) r.recycle();
            }
        }
    }

    /**
     * 坐标兜底门禁：
     * ① 该 App 未被"失败禁用"
     * ② 当前 Activity 必须是广告特征页（splash/advert/welcome...）
     * 两条都满足才允许盲点，最大限度避免误点。
     */
    private boolean canUseBounds(String pkg, String activity) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (prefs == null) prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("bounds_disabled_" + pkg, false)) return false;
        if (activity == null || activity.isEmpty()) return false;
        String a = activity.toLowerCase(Locale.ROOT);
        for (String hint : AD_ACTIVITY_HINTS) {
            if (a.contains(hint)) return true;
        }
        return false;
    }

    /**
     * 统一记录一次点击：去重状态 + 日志 + 结果超时
     */
    private void recordClick(String pkg, String activity, int eventType,
                             String matchType, boolean isBounds) {
        lastClickedKey = pkg + "|" + activity;
        lastClickedAt = System.currentTimeMillis();
        handler.removeCallbacks(scanRunnable);
        retryCount = 0;

        logEvent(pkg, activity, eventType, true, matchType, true);

        lastClickPkg = pkg;
        lastClickActivity = activity;
        lastClickWasBounds = isBounds;
        lastClickRecordTime = System.currentTimeMillis();

        handler.removeCallbacks(resultTimeoutRunnable);
        handler.postDelayed(resultTimeoutRunnable, RESULT_TIMEOUT_MS);
    }

    private void scheduleRetry(String pkg, int eventType) {
        if (retryCount < MAX_RETRIES) {
            long delay = RETRY_DELAYS[retryCount];
            retryCount++;
            lastRetryPkg = pkg;
            lastRetryEventType = eventType;
            handler.postDelayed(scanRunnable, delay);
        }
    }

    private void logEvent(String pkg, String activity, int eventType,
                          boolean found, String matchType, boolean clicked) {
        try {
            if (logDb == null) logDb = new SkipLogDb(this);
            String eventName = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    ? "window_change" : "content_change";
            logDb.insert(System.currentTimeMillis(), pkg, activity, eventName,
                    found, matchType, clicked);
        } catch (Exception ignored) {
        }
    }

    /**
     * 获取所有应用窗口的根节点（多窗口，覆盖悬浮广告层）
     */
    private List<AccessibilityNodeInfo> getApplicationWindowRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = getWindows();
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    int type = w.getType();
                    if (type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                            type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) {
                            roots.add(root);
                            if (roots.size() >= 3) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取窗口列表失败", e);
        }

        if (roots.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) roots.add(root);
        }
        return roots;
    }

    // ============================================================
    // 节点搜索（App 规则优先 + 通用规则兜底）
    // ============================================================

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root, String pkg) {
        if (root == null) return null;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        AppRules.Rule rule = AppRules.findRule(pkg);

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectCandidates(root, candidates);
        candidates.sort((a, b) -> zoneOf(a, sw, sh) - zoneOf(b, sw, sh));

        for (AccessibilityNodeInfo node : candidates) {
            String nodePkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
            if (nodePkg.isEmpty() || nodePkg.equals(getPackageName())) continue;

            String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() <= 0 || bounds.height() <= 0) continue;
            if (!isSmallEnough(bounds, sw, sh)) continue;

            boolean inCorner = inCorner(bounds, sw, sh);

            // 1. App 专属规则：ID 结尾匹配（最可靠，不限位置）
            if (rule != null && rule.ids.length > 0 && !viewId.isEmpty()) {
                for (String id : rule.ids) {
                    if (viewId.endsWith(id)) {
                        lastMatchType = "app_id";
                        recycleOthers(candidates, node);
                        return node;
                    }
                }
            }

            // 2. App 专属规则：文本包含 + 长度（不限位置）
            if (rule != null && rule.texts.length > 0) {
                for (int i = 0; i < rule.texts.length; i++) {
                    int maxLen = i < rule.textMaxLen.length ? rule.textMaxLen[i] : 6;
                    if (text.contains(rule.texts[i]) && (maxLen == 0 || text.length() <= maxLen)) {
                        lastMatchType = "app_text";
                        recycleOthers(candidates, node);
                        return node;
                    }
                }
            }

            // 3. 通用文本规则（仅角落区域，避免误点屏幕中央的"跳过本章"）
            if (inCorner) {
                for (String[] t : TEXT_RULES) {
                    String keyword = t[0];
                    int maxLen = Integer.parseInt(t[1]);
                    boolean cornerOnly = t[2].equals("1");
                    boolean textHit = !text.isEmpty() && text.contains(keyword) && text.length() <= maxLen;
                    boolean descHit = !desc.isEmpty() && desc.contains(keyword) && desc.length() <= maxLen;
                    if ((textHit || descHit) && (!cornerOnly || inCorner)) {
                        lastMatchType = "text";
                        recycleOthers(candidates, node);
                        return node;
                    }
                }

                // 4. 纯倒计时文本（"3s"/"5秒"）仅角落
                if (!text.isEmpty() && text.length() <= 3 && COUNTDOWN_TEXT.matcher(text).matches()) {
                    lastMatchType = "countdown";
                    recycleOthers(candidates, node);
                    return node;
                }

                // 5. 通用 ID 规则（仅角落，已剔除 close/jump 等宽泛词）
                if (!viewId.isEmpty()) {
                    String idLower = viewId.toLowerCase(Locale.ROOT);
                    for (String id : ID_RULES) {
                        if (idLower.contains(id)) {
                            lastMatchType = "id";
                            recycleOthers(candidates, node);
                            return node;
                        }
                    }
                }
            }
        }

        recycleAll(candidates);
        return null;
    }

    private boolean isSmallEnough(Rect bounds, int sw, int sh) {
        return bounds.width() <= sw * 0.6 && bounds.height() <= sh * 0.4;
    }

    /** 角落区域：右侧(x>60%) 且 位于顶部(上35%) 或 底部(下35%) */
    private boolean inCorner(Rect b, int sw, int sh) {
        int cx = b.centerX(), cy = b.centerY();
        boolean rightSide = cx > sw * 0.6;
        boolean topBand = cy < sh * 0.35;
        boolean bottomBand = cy > sh * 0.65;
        return rightSide && (topBand || bottomBand);
    }

    private void collectCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null || result.size() >= 500) return;

        CharSequence text = node.getText();
        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        boolean hasContent = (text != null && text.length() > 0) ||
                (desc != null && desc.length() > 0) ||
                (viewId != null && !viewId.isEmpty());

        if (hasContent) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                result.add(AccessibilityNodeInfo.obtain(node));
            }
        }

        for (int i = 0; i < node.getChildCount() && result.size() < 500; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCandidates(child, result);
                child.recycle();
            }
        }
    }

    private int zoneOf(AccessibilityNodeInfo node, int sw, int sh) {
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        int cx = b.centerX(), cy = b.centerY();
        if (cx > sw * 0.55 && cy < sh * 0.4) return 0;       // 右上角
        if (cx > sw * 0.55 && cy > sh * 0.55) return 1;      // 右下角
        return 2;
    }

    // ============================================================
    // 点击（安全链）
    // ============================================================

    private void performSafeClick(AccessibilityNodeInfo node) {
        if (node == null) return;

        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }

        AccessibilityNodeInfo ancestor = findSafeClickableAncestor(node);
        if (ancestor != null) {
            boolean clicked = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            ancestor.recycle();
            if (clicked) return;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        performGestureClick(rect.centerX(), rect.centerY());
    }

    private AccessibilityNodeInfo findSafeClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        while (parent != null) {
            if (parent.isClickable()) {
                Rect b = new Rect();
                parent.getBoundsInScreen(b);
                if (b.width() <= sw * 0.7 && b.height() <= sh * 0.5) {
                    return parent;
                }
                parent.recycle();
                return null;
            }
            AccessibilityNodeInfo grandparent = parent.getParent();
            parent.recycle();
            parent = grandparent;
        }
        return null;
    }

    private void performGestureClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        dispatchGesture(gb.build(), null, null);
    }

    // ============================================================
    // 回收
    // ============================================================

    private void recycleAll(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) { if (n != null) n.recycle(); }
    }

    private void recycleOthers(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo keep) {
        for (AccessibilityNodeInfo n : nodes) { if (n != keep && n != null) n.recycle(); }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        logDb = new SkipLogDb(this);
        startForegroundNotification();
        startHealthCheckService();
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "广告跳过", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("广告跳过")
                .setContentText("自动跳过广告服务运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void startHealthCheckService() {
        try {
            Intent intent = new Intent(this, HealthCheckService.class);
            startForegroundService(intent);
        } catch (Exception e) {
            Log.w(TAG, "启动保活服务失败: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (prefs != null) {
            prefs.edit().putBoolean("service_enabled", false).apply();
        }
    }
}
