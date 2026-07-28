package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
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
    private static final String PREFS_NAME = "adskip_prefs";

    // 跳过按钮的常见文本关键词（支持正则匹配）
    private static final Pattern[] SKIP_PATTERNS = {
            Pattern.compile(".*跳过.*"),
            Pattern.compile(".*SKIP.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*跳过广告.*"),
            Pattern.compile(".*关闭.*"),
            Pattern.compile(".*关闭广告.*"),
            Pattern.compile(".*点击跳过.*"),
            Pattern.compile(".*skip.*ad.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\u00d7.*"),  // × 关闭按钮
    };

    // 跳过按钮的常见 resource-id 关键词
    private static final Pattern[] SKIP_ID_PATTERNS = {
            Pattern.compile(".*skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*close.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*dismiss.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ad_skip.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*tt_skip.*", Pattern.CASE_INSENSITIVE),   // 穿山甲广告
            Pattern.compile(".*gdt_skip.*", Pattern.CASE_INSENSITIVE),  // 优量汇广告
            Pattern.compile(".*ksad.*skip.*", Pattern.CASE_INSENSITIVE), // 快手广告
    };

    // 已处理过的窗口，避免重复点击
    private String lastClickedWindowId = "";
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 1500; // 1.5秒冷却时间

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // 过滤掉系统界面和自己
        if (packageName.equals(getPackageName()) ||
                packageName.equals("com.android.systemui") ||
                packageName.contains("launcher")) {
            return;
        }

        // 只处理窗口状态变化和内容变化事件
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        // 冷却时间检查
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            return;
        }

        // 查找跳过按钮
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            AccessibilityNodeInfo skipNode = findSkipButton(root);
            if (skipNode != null) {
                performClick(skipNode);
                lastClickTime = now;
                Log.d(TAG, "自动点击跳过按钮: " + skipNode.getText() + " in " + packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "查找跳过按钮出错", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * 在节点树中递归查找跳过按钮
     */
    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();

        // 递归搜索所有可点击的节点
        findClickableNodes(root, candidates);

        // 按优先级排序：优先匹配文本，其次匹配 ID
        for (AccessibilityNodeInfo node : candidates) {
            CharSequence text = node.getText();
            String viewId = node.getViewIdResourceName();
            CharSequence contentDesc = node.getContentDescription();

            // 检查文本内容
            if (text != null && text.length() > 0) {
                String textStr = text.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(textStr).matches()) {
                        return node; // 直接返回第一个匹配的
                    }
                }
            }

            // 检查 contentDescription
            if (contentDesc != null && contentDesc.length() > 0) {
                String descStr = contentDesc.toString().trim();
                for (Pattern p : SKIP_PATTERNS) {
                    if (p.matcher(descStr).matches()) {
                        return node;
                    }
                }
            }

            // 检查 resource-id
            if (viewId != null && !viewId.isEmpty()) {
                for (Pattern p : SKIP_ID_PATTERNS) {
                    if (p.matcher(viewId).matches()) {
                        return node;
                    }
                }
            }
        }

        // 清理未被选中的节点
        for (AccessibilityNodeInfo node : candidates) {
            if (node != null) {
                node.recycle();
            }
        }

        return null;
    }

    /**
     * 递归收集所有可点击的叶子节点
     */
    private void findClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        // 只关注可点击的节点
        if (node.isClickable()) {
            // 限制搜索深度，避免过多节点
            if (result.size() < 200) {
                result.add(AccessibilityNodeInfo.obtain(node));
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && result.size() < 200; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findClickableNodes(child, result);
                child.recycle();
            }
        }
    }

    /**
     * 通过无障碍手势模拟点击
     */
    private void performClick(AccessibilityNodeInfo node) {
        if (node == null) return;

        // 首选 performAction 方式
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (success) {
                return;
            }
        }

        // 备用方案：使用手势点击节点中心
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        float centerX = rect.centerX();
        float centerY = rect.centerY();

        Path clickPath = new Path();
        clickPath.moveTo(centerX, centerY);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 50));

        dispatchGesture(gestureBuilder.build(), null, null);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已连接");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("service_enabled", true).apply();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean("service_enabled", false).apply();
    }
}
