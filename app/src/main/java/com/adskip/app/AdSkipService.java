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
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 广告跳过无障碍服务
 * 自动检测并点击"跳过"按钮
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    private static final String CHANNEL_ID = "adskip_foreground";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "adskip_prefs";

    // 跳过按钮的常见文本关键词（精确匹配，避免误判）
    private static final Pattern[] SKIP_PATTERNS = {
            Pattern.compile("^(跳过|跳过广告|SKIP|Skip|skip|关闭广告)$"),
            Pattern.compile("^跳过\\s*\\d+\\s*s?$"),
            Pattern.compile("^\\d+\\s*s?\\s*跳过$"),
            Pattern.compile("^\\d+\\s*s$"),
    };

    // 跳过按钮的常见 resource-id 关键词
    private static final Pattern[] SKIP_ID_PATTERNS = {
            Pattern.compile(".*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*tt_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*gdt_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ksad.*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ad_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*splash_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*close_ad.*", Pattern.CASE_INSENSITIVE),
    };

    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 2000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        // 只处理窗口切换事件，不处理内容变化（大幅减少触发频率）
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        String eventPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (eventPkg.equals(getPackageName()) ||
                eventPkg.equals("com.android.systemui") ||
                eventPkg.contains("launcher")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // 二次过滤：root 窗口包名也必须不是自己
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && rootPkg.toString().equals(getPackageName())) {
                root.recycle();
                return;
            }

            AccessibilityNodeInfo skipNode = findSkipButton(root);
            if (skipNode != null) {
                performClick(skipNode);
                lastClickTime = now;
                Log.d(TAG, "点击跳过: " + skipNode.getText() + " @ " + eventPkg);
            }
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findClickableNodes(root, candidates);

        for (AccessibilityNodeInfo node : candidates) {
            // 排除自己 App 的节点
            String nodePkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
            if (nodePkg.isEmpty() || nodePkg.equals(getPackageName())) {
                node.recycle();
                continue;
            }

            CharSequence text = node.getText();
            String viewId = node.getViewIdResourceName();
            CharSequence contentDesc = node.getContentDescription();

            // 尺寸检查
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int w = bounds.width();
            int h = bounds.height();
            if (w <= 0 || h <= 0) { node.recycle(); continue; }
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (w > sw * 0.6 || h > sh * 0.5) { node.recycle(); continue; }

            boolean matched = false;

            if (viewId != null && !viewId.isEmpty()) {
                for (Pattern p : SKIP_ID_PATTERNS) {
                    if (p.matcher(viewId).matches()) { matched = true; break; }
                }
            }
            if (!matched && text != null && text.length() > 0 && text.length() <= 10) {
                String s = text.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(s).matches()) { matched = true; break; }
                }
            }
            if (!matched && contentDesc != null && contentDesc.length() > 0 && contentDesc.length() <= 10) {
                String s = contentDesc.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(s).matches()) { matched = true; break; }
                }
            }

            if (matched) {
                for (AccessibilityNodeInfo n : candidates) {
                    if (n != node && n != null) n.recycle();
                }
                return node;
            }
            node.recycle();
        }
        return null;
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

    private void performClick(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }
        // 备用手势点击
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        Path path = new Path();
        path.moveTo(rect.centerX(), rect.centerY());
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(gb.build(), null, null);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "服务已连接");
        startForegroundNotification();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("service_enabled", true).apply();
    }

    /**
     * 启动前台通知，防止被 ColorOS 杀掉
     */
    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "广告跳过", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("广告跳过服务运行中");
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
