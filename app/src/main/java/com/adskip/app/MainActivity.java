package com.adskip.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "adskip_prefs";
    private static final String KEY_ENABLED = "service_enabled";

    private Button btnToggle;
    private TextView tvStatus;
    private TextView tvGuide;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        btnToggle = findViewById(R.id.btn_toggle);
        tvStatus = findViewById(R.id.tv_status);
        tvGuide = findViewById(R.id.tv_guide);

        updateUI();

        btnToggle.setOnClickListener(v -> {
            if (isAccessibilityServiceEnabled()) {
                // 已启用，跳转到设置页关闭
                openAccessibilitySettings();
            } else {
                // 未启用，跳转到设置页开启
                openAccessibilitySettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            tvStatus.setText("● 服务运行中");
            tvStatus.setTextColor(0xFF4CAF50);
            btnToggle.setText("管理无障碍权限");
            tvGuide.setText("广告跳过服务已开启\n打开任意含广告的 App，跳过按钮将被自动点击");
        } else {
            tvStatus.setText("○ 服务未启动");
            tvStatus.setTextColor(0xFFF44336);
            btnToggle.setText("开启无障碍服务");
            tvGuide.setText("点击上方按钮，在无障碍设置中找到「广告跳过」，开启服务即可");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + AdSkipService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        return enabledServices.contains(serviceName) || enabledServices.contains("com.adskip.app");
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
