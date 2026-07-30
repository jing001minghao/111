package com.adskip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * 心跳广播接收器 — 检查无障碍服务是否存活
 */
public class HealthCheckReceiver extends BroadcastReceiver {

    private static final String TAG = "HealthCheckReceiver";
    private static final String CHANNEL_ID = "adskip_alert";
    private static final int ALERT_NOTIFICATION_ID = 100;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "心跳检查...");

        // 检查无障碍服务是否运行
        boolean running = isAccessibilityServiceEnabled(context);

        if (!running) {
            Log.w(TAG, "无障碍服务未运行，发送提醒通知");
            sendAlertNotification(context);

            // 尝试重启保活服务
            Intent restartIntent = new Intent(context, HealthCheckService.class);
            context.startService(restartIntent);
        } else {
            Log.d(TAG, "无障碍服务正常运行");
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context) {
        String serviceName = context.getPackageName() + "/" + AdSkipService.class.getCanonicalName();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(serviceName);
    }

    private void sendAlertNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "服务状态提醒", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);

        Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(
                context, 1, settingsIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("广告跳过服务已停止")
                .setContentText("点击重新开启无障碍服务")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        nm.notify(ALERT_NOTIFICATION_ID, notification);
    }
}
