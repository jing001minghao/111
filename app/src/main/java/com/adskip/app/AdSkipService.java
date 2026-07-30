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
 * 广告跳过无障碍服务 v2.0
 * - 位置优先扫描（右上角优先）
 * - 延迟 + 二次确认
 * - 倒计时智能点击
 * - 兜底盲点
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // ==== 匹配关键词 ====
    private static final Pattern[] SKIP_PATTERNS = {
            Pattern.compile("^(跳过|跳过广告|SKIP|Skip|skip|关闭广告|点击关闭|我知道了|我已知晓|同意并继续|关闭)$"),
            Pattern.compile("^(Close|Dismiss|Got it|Agree|OK)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^跳过\\s*\\d+\\s*s?$"),
            Pattern.compile("^\\d+\\s*s?\\s*跳过$"),
            Pattern.compile("^\\d+\\s*s$"),
    };

    private static final Pattern[] SKIP_ID_PATTERNS = {
            Pattern.compile(".*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*tt_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*gdt_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ksad.*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ad_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*splash_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*close_ad.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*dismiss.*", Pattern.CASE_INSENSITIVE),
    };

    // ==== 状态 ====
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 2500;

    // 倒计时追踪
    private CountdownTracker countdownTracker = null;

    // 延迟扫描状态
    private boolean scanPending = false;
    private AccessibilityNodeInfo pendingRoot = null;

    // 二次确认状态
    private Rect lastCandidateRect = null;
    private long lastCandidateTime = 0;
    private static final long CONFIRM_INTERVAL_MS = 150;

    // 盲点已执行标记（同一窗口不重复盲点）
    private String lastBlindWindowId = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        String eventPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (eventPkg.equals(getPackageName()) ||
                eventPkg.equals("com.android.systemui") ||
                eventPkg.contains("launcher")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) return;

        // 倒计时追踪：检查是否是倒计时文本变化
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && countdownTracker != null) {
            CharSequence eventText = event.getText() != null && event.getText().size() > 0
                    ? event.getText().get(0) : null;
            if (eventText != null && countdownTracker.update(eventText.toString().trim())) {
                Log.d(TAG, "倒计时递减: " + countdownTracker.currentValue);
                if (countdownTracker.shouldClick()) {
                    performBlindClick(countdownTracker.rect);
                    countdownTracker = null;
                    lastClickTime = now;
                }
                return;
            }
        }

        // 窗口切换 → 延迟扫描
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && rootPkg.toString().equals(getPackageName())) {
                root.recycle();
                return;
            }

            // 取消之前的延迟任务
            handler.removeCallbacks(scanRunnable);
            handler.removeCallbacks(blindClickRunnable);

            // 重置二次确认
            lastCandidateRect = null;
            countdownTracker = null;

            // 300ms 后扫描
            pendingRoot = root;
            handler.postDelayed(scanRunnable, 300);

            // 1 秒后兜底盲点（如果还没成功跳过）
            String windowId = Integer.toHexString(root.hashCode());
            if (!windowId.equals(lastBlindWindowId)) {
                handler.postDelayed(blindClickRunnable, 1000);
            }
        }
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingRoot == null) return;
            try {
                AccessibilityNodeInfo skipNode = findSkipButton(pendingRoot);
                if (skipNode != null) {
                    // 二次确认
                    Rect bounds = new Rect();
                    skipNode.getBoundsInScreen(bounds);

                    if (lastCandidateRect != null &&
                            Math.abs(bounds.centerX() - lastCandidateRect.centerX()) < 20 &&
                            Math.abs(bounds.centerY() - lastCandidateRect.centerY()) < 20 &&
                            System.currentTimeMillis() - lastCandidateTime < CONFIRM_INTERVAL_MS + 100) {
                        // 两次找到同一位置 → 确认点击
                        performClick(skipNode);
                        lastClickTime = System.currentTimeMillis();
                        lastCandidateRect = null;
                        countdownTracker = null;
                        handler.removeCallbacks(blindClickRunnable);
                        Log.d(TAG, "二次确认通过，点击跳过");
                    } else {
                        // 第一次找到 → 记录位置，200ms 后再扫一次
                        lastCandidateRect = bounds;
                        lastCandidateTime = System.currentTimeMillis();
                        skipNode.recycle();

                        // 检查是否是倒计时按钮（如 "跳过 5s"）
                        CharSequence text = skipNode.getText();
                        if (text != null && countdownTracker == null) {
                            String s = text.toString().trim();
                            java.util.regex.Matcher m = Pattern.compile("(\\d+)\\s*s?").matcher(s);
                            if (m.find()) {
                                int val = Integer.parseInt(m.group(1));
                                if (val > 1) {
                                    countdownTracker = new CountdownTracker(bounds, val);
                                    Log.d(TAG, "检测到倒计时跳过: " + val + "s");
                                }
                            }
                        }

                        handler.postDelayed(scanRunnable, CONFIRM_INTERVAL_MS);
                    }
                } else {
                    lastCandidateRect = null;
                }
            } finally {
                if (pendingRoot != null) {
                    pendingRoot.recycle();
                    pendingRoot = null;
                }
            }
        }
    };

    private final Runnable blindClickRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;
            Log.d(TAG, "执行兜底盲点");
            int sw = getResources().getDisplayMetrics().widthPixels;
            Rect blindRect = new Rect(sw - 160, 0, sw, 180);
            performBlindClick(blindRect);
            if (pendingRoot != null) {
                int windowId = pendingRoot.hashCode();
                lastBlindWindowId = Integer.toHexString(windowId);
            }
        }
    };

    // ============================================================
    // 节点搜索
    // ============================================================

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int zoneRight = sw;
        int zoneLeft = (int) (sw * 0.55);
        int zoneTop = 0;
        int zoneBottom = (int) (getResources().getDisplayMetrics().heightPixels * 0.35);

        // 第一轮：右上角优先扫描
        List<AccessibilityNodeInfo> zoneNodes = new ArrayList<>();
        findClickableNodesInZone(root, zoneLeft, zoneTop, zoneRight, zoneBottom, zoneNodes, 200);
        AccessibilityNodeInfo result = matchNodes(zoneNodes);
        if (result != null) {
            recycleOthers(zoneNodes, result);
            return result;
        }
        recycleAll(zoneNodes);

        // 第二轮：全屏扫描
        List<AccessibilityNodeInfo> allNodes = new ArrayList<>();
        findClickableNodes(root, allNodes);
        result = matchNodes(allNodes);
        if (result != null) {
            recycleOthers(allNodes, result);
            return result;
        }
        recycleAll(allNodes);
        return null;
    }

    private AccessibilityNodeInfo matchNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            String nodePkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
            if (nodePkg.isEmpty() || nodePkg.equals(getPackageName())) continue;

            CharSequence text = node.getText();
            String viewId = node.getViewIdResourceName();
            CharSequence contentDesc = node.getContentDescription();

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() <= 0 || bounds.height() <= 0) continue;
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (bounds.width() > sw * 0.6 || bounds.height() > sh * 0.5) continue;

            if (viewId != null && !viewId.isEmpty()) {
                for (Pattern p : SKIP_ID_PATTERNS) {
                    if (p.matcher(viewId).matches()) return node;
                }
            }
            if (text != null && text.length() > 0 && text.length() <= 10) {
                String s = text.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(s).matches()) return node;
                }
            }
            if (contentDesc != null && contentDesc.length() > 0 && contentDesc.length() <= 10) {
                String s = contentDesc.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(s).matches()) return node;
                }
            }
        }
        return null;
    }

    private void findClickableNodesInZone(AccessibilityNodeInfo node,
                                           int zl, int zt, int zr, int zb,
                                           List<AccessibilityNodeInfo> result, int max) {
        if (node == null || result.size() >= max) return;
        if (node.isClickable()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() >= 20 && b.height() >= 20 &&
                    b.width() <= 300 && b.height() <= 150 &&
                    b.left >= zl && b.top >= zt && b.right <= zr && b.bottom <= zb) {
                result.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        for (int i = 0; i < node.getChildCount() && result.size() < max; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findClickableNodesInZone(child, zl, zt, zr, zb, result, max);
                child.recycle();
            }
        }
    }

    private void findClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isClickable() && result.size() < 200) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount() && result.size() < 200; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findClickableNodes(child, result);
                child.recycle();
            }
        }
    }

    // ============================================================
    // 点击
    // ============================================================

    private void performClick(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        performBlindClick(rect);
    }

    private void performBlindClick(Rect rect) {
        Path path = new Path();
        path.moveTo(rect.centerX(), rect.centerY());
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
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
        Log.d(TAG, "服务已连接");
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

    // ============================================================
    // 倒计时追踪器
    // ============================================================

    static class CountdownTracker {
        final Rect rect;
        int currentValue;

        CountdownTracker(Rect rect, int initialValue) {
            this.rect = rect;
            this.currentValue = initialValue;
        }

        /** 返回 true 表示数值递减了 */
        boolean update(String text) {
            java.util.regex.Matcher m = Pattern.compile("(\\d+)").matcher(text);
            if (m.find()) {
                int val = Integer.parseInt(m.group(1));
                if (val < currentValue && val > 0) {
                    currentValue = val;
                    return true;
                }
            }
            return false;
        }

        boolean shouldClick() {
            return currentValue <= 1;
        }
    }
}
