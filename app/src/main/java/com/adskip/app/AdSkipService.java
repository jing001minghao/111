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
 * 广告跳过无障碍服务 v1.2.0
 * 秒点版：找到跳过按钮立即点击，content change + 重复扫描确保不漏
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // ==== 匹配关键词 ====
    private static final Pattern[] SKIP_PATTERNS = {
            Pattern.compile("^(跳过|跳过广告|SKIP|Skip|skip|关闭广告|点击关闭|我知道了|我已知晓|同意并继续|关闭|立即跳过|马上跳过)$"),
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
            Pattern.compile(".*tt_splash.*", Pattern.CASE_INSENSITIVE),
    };

    // ==== 状态 ====
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 2000;

    // 内容变化防抖
    private long lastContentChangeTime = 0;
    private static final long CONTENT_DEBOUNCE_MS = 150;

    // 当前窗口的扫描计数
    private String currentWindowId = "";
    private int scanCount = 0;
    private static final int MAX_SCANS = 4;

    // 倒计时追踪
    private CountdownTracker countdownTracker = null;

    // 盲点窗口记录
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

        // 倒计时追踪
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && countdownTracker != null) {
            CharSequence eventText = event.getText() != null && event.getText().size() > 0
                    ? event.getText().get(0) : null;
            if (eventText != null && countdownTracker.update(eventText.toString().trim())) {
                if (countdownTracker.shouldClick()) {
                    performBlindClick(countdownTracker.rect);
                    countdownTracker = null;
                    lastClickTime = now;
                    Log.d(TAG, "倒计时到1s，点击");
                }
                return;
            }
        }

        // 窗口切换：重置扫描状态，启动多轮扫描
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.removeCallbacks(scanRunnable);
            handler.removeCallbacks(blindClickRunnable);
            countdownTracker = null;
            currentWindowId = eventPkg + "_" + now;
            scanCount = 0;
            scheduleScan(100);  // 首扫 100ms 快速响应
            handler.postDelayed(blindClickRunnable, 1500);
        }

        // 内容变化：防抖触发扫描（关键的修复）
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentChangeTime < CONTENT_DEBOUNCE_MS) return;
            lastContentChangeTime = now;
            handler.removeCallbacks(scanRunnable);
            scheduleScan(50);  // 内容变化后马上扫
        }
    }

    /**
     * 安排一次扫描，扫不到会递归重试
     */
    private void scheduleScan(long delay) {
        if (scanCount >= MAX_SCANS) return;
        scanCount++;
        handler.postDelayed(scanRunnable, delay);
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            try {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg != null && rootPkg.toString().equals(getPackageName())) return;

                AccessibilityNodeInfo skipNode = findSkipButton(root);
                if (skipNode != null) {
                    // 找到→秒点
                    CharSequence text = skipNode.getText();

                    // 检查是否是倒计时按钮
                    if (text != null) {
                        String s = text.toString().trim();
                        java.util.regex.Matcher m = Pattern.compile("(\\d+)\\s*s?").matcher(s);
                        if (m.find()) {
                            int val = Integer.parseInt(m.group(1));
                            if (val > 1) {
                                Rect bounds = new Rect();
                                skipNode.getBoundsInScreen(bounds);
                                countdownTracker = new CountdownTracker(bounds, val);
                                Log.d(TAG, "倒计时" + val + "s，等待递减");
                                skipNode.recycle();
                                return;
                            }
                        }
                    }

                    // 非倒计时→直接点
                    performClick(skipNode);
                    lastClickTime = System.currentTimeMillis();
                    handler.removeCallbacks(blindClickRunnable);
                    Log.d(TAG, "秒点跳过按钮: " + text);
                    return;
                }

                // 没找到→重试
                if (scanCount < MAX_SCANS) {
                    scheduleScan(200);
                }
            } finally {
                root.recycle();
            }
        }
    };

    private final Runnable blindClickRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return;
            if (currentWindowId.equals(lastBlindWindowId)) return;
            lastBlindWindowId = currentWindowId;
            Log.d(TAG, "兜底盲点");
            int sw = getResources().getDisplayMetrics().widthPixels;
            Rect blindRect = new Rect(sw - 140, 10, sw - 10, 120);
            performBlindClick(blindRect);
        }
    };

    // ============================================================
    // 节点搜索
    // ============================================================

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        // 第一轮：右上角
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        findClickableNodesInZone(root, (int)(sw*0.55), 0, sw, (int)(sh*0.4), nodes);
        AccessibilityNodeInfo result = matchNodes(nodes);
        if (result != null) { recycleOthers(nodes, result); return result; }
        recycleAll(nodes);

        // 第二轮：右下角
        nodes.clear();
        findClickableNodesInZone(root, (int)(sw*0.55), (int)(sh*0.55), sw, sh, nodes);
        result = matchNodes(nodes);
        if (result != null) { recycleOthers(nodes, result); return result; }
        recycleAll(nodes);

        // 第三轮：全屏
        nodes.clear();
        findClickableNodes(root, nodes);
        return matchNodes(nodes);
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
            // 放宽尺寸限制，只排除全屏视图
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (bounds.width() > sw * 0.7 || bounds.height() > sh * 0.6) continue;

            if (viewId != null && !viewId.isEmpty()) {
                for (Pattern p : SKIP_ID_PATTERNS) {
                    if (p.matcher(viewId).matches()) return node;
                }
            }
            if (text != null && text.length() > 0 && text.length() <= 15) {
                String s = text.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(s).matches()) return node;
                }
            }
            if (contentDesc != null && contentDesc.length() > 0 && contentDesc.length() <= 15) {
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
                                           List<AccessibilityNodeInfo> result) {
        if (node == null || result.size() >= 200) return;
        if (node.isClickable()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() >= 15 && b.height() >= 15 &&
                    b.right >= zl && b.bottom >= zt &&
                    b.left <= zr && b.top <= zb) {
                // 放宽判断：区域有交集即可
                if (b.left < zr && b.right > zl && b.top < zb && b.bottom > zt) {
                    result.add(AccessibilityNodeInfo.obtain(node));
                }
            }
        }
        for (int i = 0; i < node.getChildCount() && result.size() < 200; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findClickableNodesInZone(child, zl, zt, zr, zb, result);
                child.recycle();
            }
        }
    }

    private void findClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isClickable() && result.size() < 300) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount() && result.size() < 300; i++) {
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
                .setContentText("秒点服务运行中")
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
