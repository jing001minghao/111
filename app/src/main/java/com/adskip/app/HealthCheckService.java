package com.adskip.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * 独立前台服务 — 挂第二个通知提高进程优先级
 * 启动 AlarmManager 心跳，每 15 分钟检查无障碍服务状态
 */
public class HealthCheckService extends Service {

    private static final String TAG = "HealthCheckService";
    private static final String CHANNEL_ID = "adskip_health";
    private static final int NOTIFICATION_ID = 2;
    private static final long CHECK_INTERVAL_MS = 15 * 60 * 1000; // 15 分钟

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "保活服务启动");
        startForegroundNotification();
        scheduleHealthCheck();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "广告跳过保活", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("跳过服务")
                .setContentText("持续运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void scheduleHealthCheck() {
        Intent intent = new Intent(this, HealthCheckReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                pi);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "保活服务停止");
        // 自动重启
        Intent restartIntent = new Intent(this, HealthCheckService.class);
        startService(restartIntent);
    }
}
