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
 * 广告跳过无障碍服务 v1.3.0
 * 秒点 + 绝不误触：只点击确认匹配的跳过按钮，无盲点
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // ==== 匹配关键词（严格确认制） ====
    private static final Pattern[] SKIP_PATTERNS = {
            // 精确匹配，绝不模糊
            Pattern.compile("^(跳过|跳过广告|SKIP|Skip|skip|关闭广告|立即跳过|马上跳过|点击跳过)$"),
            Pattern.compile("^(Close ad|Close|Dismiss|Got it|Agree)$", Pattern.CASE_INSENSITIVE),
            // 倒计时型："跳过5s"、"5s跳过"、"跳过 5s"、"3s"（倒计时文本本身可点击）
            Pattern.compile("^跳过\\s*\\d+\\s*s?$"),
            Pattern.compile("^\\d+\\s*s?\\s*跳过$"),
    };

    private static final Pattern[] SKIP_ID_PATTERNS = {
            Pattern.compile(".*tt_skip.*", Pattern.CASE_INSENSITIVE),   // 穿山甲
            Pattern.compile(".*tt_splash.*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*gdt_skip.*", Pattern.CASE_INSENSITIVE),  // 优量汇
            Pattern.compile(".*ksad.*skip.*", Pattern.CASE_INSENSITIVE), // 快手
            Pattern.compile(".*ad_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*splash_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*skip.*button.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*skip.*", Pattern.CASE_INSENSITIVE),
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
    // 上次点击的上下文（用于判定成功/失败）
    private String lastClickPkg = "";
    private String lastClickActivity = "";
    private long lastClickRecordTime = 0;
    private static final long RESULT_TIMEOUT_MS = 2000;  // 点击后 2s 内窗口切换视为成功

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
                lastClickRecordTime > 0) {
            long elapsed = System.currentTimeMillis() - lastClickRecordTime;
            if (elapsed < RESULT_TIMEOUT_MS) {
                String activity = event.getClassName() != null ? event.getClassName().toString() : "";
                // 包名变了，或同包名但Activity变了 → 认为跳转成功
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

        // 事件防抖（内容变化很频繁）
        if (now - lastEventTime < EVENT_DEBOUNCE_MS) return;
        lastEventTime = now;

        // 取消未执行的扫描，立即重新调度
        handler.removeCallbacks(scanRunnable);
        retryCount = 0;
        scan(eventPkg, eventType);  // 立即扫描，零延迟
    }

    /**
     * 立即扫描 + 快速重试
     */
    private void scan(String eventPkg, int eventType) {
        if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // 记录：窗口树为空
            logEvent(eventPkg, "", eventType, false, "none", false);
            return;
        }

        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && rootPkg.toString().equals(getPackageName())) return;

            String activity = root.getClassName() != null ? root.getClassName().toString() : "";

            AccessibilityNodeInfo skipNode = findSkipButton(root);
            if (skipNode != null) {
                String matchType = lastMatchType;
                performSafeClick(skipNode);
                lastClickTime = System.currentTimeMillis();
                handler.removeCallbacks(scanRunnable);
                retryCount = 0;

                // 记录点击 + 等待结果判定
                logEvent(eventPkg, activity, eventType, true, matchType, true);
                lastClickPkg = eventPkg;
                lastClickActivity = activity;
                lastClickRecordTime = System.currentTimeMillis();

                // 2s 后如果还没被标记成功 → 标记失败
                handler.postDelayed(() -> {
                    if (lastClickRecordTime > 0) {
                        logDb.markLastClickResult(false);
                        lastClickRecordTime = 0;
                    }
                }, RESULT_TIMEOUT_MS);

                Log.d(TAG, "点击跳过按钮: " + skipNode.getText() + " match=" + matchType);
                return;
            }

            // 没找到 → 记录 + 快速重试（广告可能还在渲染）
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

    /**
     * 记录日志（测试阶段）
     */
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
    private String lastMatchType = "text";  // text / id / desc

    // ============================================================
    // 节点搜索（单次遍历 + 严格确认）
    // ============================================================

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectCandidates(root, candidates, sw, sh);

        // 按位置优先级排序：右上角 > 右下角 > 其他
        final int zoneTopRight = 0, zoneBottomRight = 1, zoneOther = 2;
        candidates.sort((a, b) -> zoneOf(a, sw, sh) - zoneOf(b, sw, sh));

        // 严格匹配：文本/描述/ID 三通道，全部需要命中关键词
        for (AccessibilityNodeInfo node : candidates) {
            String nodePkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
            if (nodePkg.isEmpty() || nodePkg.equals(getPackageName())) continue;

            if (matches(node)) {
                // 点击目标检查：尺寸安全（非全屏）
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > sw * 0.6 || bounds.height() > sh * 0.4) {
                    continue;  // 太大会误触，跳过
                }
                recycleOthers(candidates, node);
                return node;
            }
        }

        recycleAll(candidates);
        return null;
    }

    /**
     * 单次 DFS 收集所有候选节点（不限于 clickable，为找父节点做准备）
     */
    private void collectCandidates(AccessibilityNodeInfo node,
                                    List<AccessibilityNodeInfo> result,
                                    int sw, int sh) {
        if (node == null || result.size() >= 500) return;

        // 只收集有文本/描述/ID 的节点（减少内存）
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

    /**
     * 严格匹配：文本 / 描述 / ID 任一命中关键词即确认
     * 记录匹配方式到 lastMatchType
     */
    private boolean matches(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String viewId = node.getViewIdResourceName();

        if (text != null && text.length() > 0 && text.length() <= 12) {
            String s = text.toString().trim();
            for (Pattern p : SKIP_PATTERNS) {
                if (p.matcher(s).matches()) { lastMatchType = "text"; return true; }
            }
        }
        if (desc != null && desc.length() > 0 && desc.length() <= 12) {
            String s = desc.toString().trim();
            for (Pattern p : SKIP_PATTERNS) {
                if (p.matcher(s).matches()) { lastMatchType = "desc"; return true; }
            }
        }
        if (viewId != null && !viewId.isEmpty()) {
            for (Pattern p : SKIP_ID_PATTERNS) {
                if (p.matcher(viewId).matches()) { lastMatchType = "id"; return true; }
            }
        }
        return false;
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

    /**
     * 安全点击链：
     * 1. 节点自己 ACTION_CLICK
     * 2. 失败 → 找最近的 clickable 祖先（尺寸安全后）点击
     * 3. 再失败 → 手势点击节点中心
     */
    private void performSafeClick(AccessibilityNodeInfo node) {
        if (node == null) return;

        // 1. 自己点击
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }

        // 2. 祖先点击（必须通过尺寸安全检查）
        AccessibilityNodeInfo ancestor = findSafeClickableAncestor(node);
        if (ancestor != null) {
            if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return;
            }
            ancestor.recycle();
        }

        // 3. 手势点击节点中心（节点已确认是跳过按钮，坐标安全）
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        performGestureClick(rect.centerX(), rect.centerY());
    }

    /**
     * 向上找最近的 clickable 祖先，要求尺寸安全（非全屏容器）
     */
    private AccessibilityNodeInfo findSafeClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        while (parent != null) {
            if (parent.isClickable()) {
                Rect b = new Rect();
                parent.getBoundsInScreen(b);
                // 尺寸安全：不是全屏容器（广告容器通常全屏）
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
        Intent intent = new Intent(this, HealthCheckService.class);
        startService(intent);
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
