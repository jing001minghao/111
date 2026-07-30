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
import androidx.cardview.widget.CardView;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "adskip_prefs";

    private Button btnToggle, btnBattery, btnAutostart;
    private TextView tvStatus, tvGuide, tvWhitelistTitle;
    private CardView cardWhitelist;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        btnToggle = findViewById(R.id.btn_toggle);
        btnBattery = findViewById(R.id.btn_battery);
        btnAutostart = findViewById(R.id.btn_autostart);
        tvStatus = findViewById(R.id.tv_status);
        tvGuide = findViewById(R.id.tv_guide);
        tvWhitelistTitle = findViewById(R.id.tv_whitelist_title);
        cardWhitelist = findViewById(R.id.card_whitelist);

        updateUI();

        btnToggle.setOnClickListener(v -> openAccessibilitySettings());
        btnBattery.setOnClickListener(v -> openAppSettings());
        btnAutostart.setOnClickListener(v -> openAutostartSettings());
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
            tvGuide.setText("自动跳过已开启\n打开任意 App，广告跳过按钮将被自动点击");
        } else {
            tvStatus.setText("○ 服务未启动");
            tvStatus.setTextColor(0xFFF44336);
            btnToggle.setText("开启无障碍服务");
            tvGuide.setText("点击上方按钮，在无障碍设置中找到「广告跳过」并开启");
        }

        // ColorOS 特化引导
        if (isColorOS()) {
            tvWhitelistTitle.setVisibility(View.VISIBLE);
            cardWhitelist.setVisibility(View.VISIBLE);
        } else {
            tvWhitelistTitle.setVisibility(View.GONE);
            cardWhitelist.setVisibility(View.GONE);
        }
    }

    private boolean isColorOS() {
        return Build.MANUFACTURER.equalsIgnoreCase("oppo") ||
                Build.MANUFACTURER.equalsIgnoreCase("oneplus") ||
                Build.DISPLAY.toUpperCase().contains("COLOROS");
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + AdSkipService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null &&
                (enabledServices.contains(serviceName) || enabledServices.contains("com.adskip.app"));
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openAutostartSettings() {
        // ColorOS 自启动管理页面
        try {
            Intent intent = new Intent();
            intent.setClassName("com.coloros.oppoguardelf",
                    "com.coloros.oppoguardelf.PhoneManagerMainActivity");
            startActivity(intent);
        } catch (Exception e) {
            // 如果跳转失败，打开应用详情页
            openAppSettings();
        }
    }
}
