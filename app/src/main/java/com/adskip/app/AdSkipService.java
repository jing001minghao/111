package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 广告跳过无障碍服务 v1.5.0
 * 内置 SKIP 规则库 + 包含匹配：识别率大幅提升
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // ==== 通用文本规则（包含匹配 + 长度限制，模仿 SKIP） ====
    // 格式: {关键词, 最大长度, 是否仅限右上角区域}
    // maxLen=0 表示不限长度（极少用）
    private static final String[][] TEXT_RULES = {
            {"跳过", "8", "0"},        // 跳过/跳过广告/跳过5s/3s跳过...
            {"关闭", "5", "0"},        // 关闭/关闭广告（爱奇艺）
            {"进入首页", "6", "0"},    // VIVO 应用商店
            {"立即体验", "6", "0"},
            {"知道了", "4", "0"},
            {"同意并继续", "7", "0"},
    };

    // 纯倒计时文本（如 "3s"、"5 秒"）只在右上角区域才匹配，防止误触
    private static final Pattern COUNTDOWN_TEXT = Pattern.compile("^\\d+\\s*[s秒]?$");

    // ==== 通用 ID 规则（结尾匹配，比全正则更稳） ====
    private static final String[] ID_RULES = {
            "skip", "count_down", "tv_time", "jump", "close",
            "ad_mark", "dismiss", "tt_splash", "gdt_ad", "vlion_ad",
    };

    // ==== 状态 ====
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 1500;

    // 内容变化防抖
    private long lastEventTime = 0;
    private static final long EVENT_DEBOUNCE_MS = 80;

    // 快速重试
    private int retryCount = 0;
    private static final int MAX_RETRIES = 4;   // 立即 + 60 + 120 + 180ms
    private static final long RETRY_INTERVAL_MS = 60;

    // ===== 测试日志 =====
    private SkipLogDb logDb;
    private String lastClickPkg = "";
    private String lastClickActivity = "";
    private long lastClickRecordTime = 0;
    private static final long RESULT_TIMEOUT_MS = 2000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        String eventPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // ===== 结果判定：点击后窗口切换且包名/Activity变化 → 跳过成功 =====
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                lastClickRecordTime > 0 && logDb != null) {
            long elapsed = System.currentTimeMillis() - lastClickRecordTime;
            if (elapsed < RESULT_TIMEOUT_MS) {
                String activity = event.getClassName() != null ? event.getClassName().toString() : "";
                boolean pkgChanged = !eventPkg.equals(lastClickPkg);
                boolean activityChanged = !activity.equals(lastClickActivity);
                if (pkgChanged || activityChanged) {
                    logDb.markLastClickResult(true);
                    Log.d(TAG, "跳过成功: " + lastClickPkg + " -> " + eventPkg);
                } else {
                    logDb.markLastClickResult(false);
                }
                lastClickRecordTime = 0;
            }
        }

        if (eventPkg.equals(getPackageName()) ||
                eventPkg.equals("com.android.systemui") ||
                eventPkg.contains("launcher")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return;

        if (now - lastEventTime < EVENT_DEBOUNCE_MS) return;
        lastEventTime = now;

        handler.removeCallbacks(scanRunnable);
        retryCount = 0;
        scan(eventPkg, eventType);
    }

    private void scan(String eventPkg, int eventType) {
        if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            logEvent(eventPkg, "", eventType, false, "none", false);
            return;
        }

        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && rootPkg.toString().equals(getPackageName())) return;

            String activity = root.getClassName() != null ? root.getClassName().toString() : "";

            AccessibilityNodeInfo skipNode = findSkipButton(root, eventPkg);
            if (skipNode != null) {
                String matchType = lastMatchType;
                CharSequence nodeText = skipNode.getText();  // 先取文本，recycle 前
                performSafeClick(skipNode);
                skipNode.recycle();  // 释放节点，防止内存泄漏
                lastClickTime = System.currentTimeMillis();
                handler.removeCallbacks(scanRunnable);
                retryCount = 0;

                logEvent(eventPkg, activity, eventType, true, matchType, true);
                lastClickPkg = eventPkg;
                lastClickActivity = activity;
                lastClickRecordTime = System.currentTimeMillis();

                handler.postDelayed(() -> {
                    if (lastClickRecordTime > 0 && logDb != null) {
                        logDb.markLastClickResult(false);
                        lastClickRecordTime = 0;
                    }
                }, RESULT_TIMEOUT_MS);

                Log.d(TAG, "点击跳过: " + nodeText + " [" + matchType + "]");
                return;
            }

            // ===== App 专属坐标兜底（节点树选不中的按钮，如豆瓣） =====
            AppRules.Rule rule = AppRules.findRule(eventPkg);
            if (rule != null && rule.bounds != null) {
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                Rect r = new Rect(
                        (int) (rule.bounds[0] * sw), (int) (rule.bounds[1] * sh),
                        (int) (rule.bounds[2] * sw), (int) (rule.bounds[3] * sh));
                performGestureClick(r.centerX(), r.centerY());
                lastClickTime = System.currentTimeMillis();
                handler.removeCallbacks(scanRunnable);
                retryCount = 0;
                logEvent(eventPkg, activity, eventType, true, "bounds", true);
                lastClickPkg = eventPkg;
                lastClickActivity = activity;
                lastClickRecordTime = System.currentTimeMillis();
                handler.postDelayed(() -> {
                    if (lastClickRecordTime > 0 && logDb != null) {
                        logDb.markLastClickResult(false);
                        lastClickRecordTime = 0;
                    }
                }, RESULT_TIMEOUT_MS);
                Log.d(TAG, "坐标兜底点击: " + eventPkg);
                return;
            }

            logEvent(eventPkg, activity, eventType, false, "none", false);
            lastRetryPkg = eventPkg;
            lastRetryEventType = eventType;
            if (retryCount < MAX_RETRIES) {
                retryCount++;
                handler.postDelayed(scanRunnable, RETRY_INTERVAL_MS);
            }
        } finally {
            root.recycle();
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

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scan(lastRetryPkg, lastRetryEventType);
        }
    };
    private String lastRetryPkg = "";
    private int lastRetryEventType = 0;
    private String lastMatchType = "text";

    // ============================================================
    // 节点搜索（App规则优先 + 通用规则兜底）
    // ============================================================

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root, String pkg) {
        if (root == null) return null;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        AppRules.Rule rule = AppRules.findRule(pkg);

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectCandidates(root, candidates, sw, sh);

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
            boolean inTopRight = bounds.centerX() > sw * 0.5 && bounds.centerY() < sh * 0.5;

            // 1. App 专属规则：ID 结尾匹配（最可靠）
            if (rule != null && rule.ids.length > 0 && !viewId.isEmpty()) {
                for (String id : rule.ids) {
                    if (viewId.endsWith(id) && isSmallEnough(bounds, sw, sh)) {
                        lastMatchType = "app_id";
                        recycleOthers(candidates, node);
                        return node;
                    }
                }
            }

            // 2. App 专属规则：文本包含 + 长度
            if (rule != null && rule.texts.length > 0) {
                for (int i = 0; i < rule.texts.length; i++) {
                    int maxLen = i < rule.textMaxLen.length ? rule.textMaxLen[i] : 6;
                    if (text.contains(rule.texts[i]) && (maxLen == 0 || text.length() <= maxLen) &&
                            isSmallEnough(bounds, sw, sh)) {
                        lastMatchType = "app_text";
                        recycleOthers(candidates, node);
                        return node;
                    }
                }
            }

            // 3. 通用文本规则（包含 + 长度，text/desc 分别判断）
            for (String[] t : TEXT_RULES) {
                String keyword = t[0];
                int maxLen = Integer.parseInt(t[1]);
                boolean topRightOnly = t[2].equals("1");
                boolean textHit = !text.isEmpty() && text.contains(keyword) && text.length() <= maxLen;
                boolean descHit = !desc.isEmpty() && desc.contains(keyword) && desc.length() <= maxLen;
                if ((textHit || descHit) &&
                        (!topRightOnly || inTopRight) &&
                        isSmallEnough(bounds, sw, sh)) {
                    lastMatchType = "text";
                    recycleOthers(candidates, node);
                    return node;
                }
            }

            // 4. 纯倒计时文本（"3s"/"5秒"）仅右上角
            if (inTopRight && !text.isEmpty() && text.length() <= 4 &&
                    COUNTDOWN_TEXT.matcher(text).matches() &&
                    isSmallEnough(bounds, sw, sh)) {
                lastMatchType = "countdown";
                recycleOthers(candidates, node);
                return node;
            }

            // 5. 通用 ID 规则（结尾匹配）
            if (!viewId.isEmpty()) {
                String idLower = viewId.toLowerCase();
                for (String id : ID_RULES) {
                    if (idLower.endsWith(id) && isSmallEnough(bounds, sw, sh)) {
                        lastMatchType = "id";
                        recycleOthers(candidates, node);
                        return node;
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

    private void collectCandidates(AccessibilityNodeInfo node,
                                    List<AccessibilityNodeInfo> result,
                                    int sw, int sh) {
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
                collectCandidates(child, result, sw, sh);
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
            ancestor.recycle();  // 无论成功失败都要回收，防止内存泄漏
            if (clicked) {
                return;
            }
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("service_enabled", false).apply();
    }
}
