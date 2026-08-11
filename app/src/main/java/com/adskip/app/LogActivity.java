package com.adskip.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 测试日志页面：按 App 统计 + 最近记录
 */
public class LogActivity extends AppCompatActivity {

    private LinearLayout container;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SkipLogDb db;
    private PackageManager pm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new SkipLogDb(this);
        pm = getPackageManager();

        // 顶部：标题 + 清空按钮
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("测试日志");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnClear = new Button(this);
        btnClear.setText("清空");
        btnClear.setOnClickListener(v -> {
            db.clear();
            refresh();
        });

        header.addView(title);
        header.addView(btnClear);
        root.addView(header);

        // 内容区（可滚动）
        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        container.removeAllViews();

        // ===== 按 App 统计 =====
        TextView statTitle = new TextView(this);
        statTitle.setText("按 App 统计");
        statTitle.setTextSize(16);
        statTitle.setTypeface(null, Typeface.BOLD);
        statTitle.setPadding(0, dp(8), 0, dp(4));
        container.addView(statTitle);

        List<SkipLogDb.AppStat> stats = db.getAppStats();
        if (stats.isEmpty()) {
            container.addView(makeText("暂无记录，打开几个带开屏广告的 App 后再来看", 13, "#757575"));
        }
        for (SkipLogDb.AppStat s : stats) {
            TextView tv = new TextView(this);
            tv.setText(appName(s.pkg) + "\n    " + s.summary());
            tv.setTextSize(14);
            tv.setPadding(dp(8), dp(4), dp(4), dp(4));
            container.addView(tv);
        }

        // ===== 最近记录 =====
        TextView logTitle = new TextView(this);
        logTitle.setText("最近记录");
        logTitle.setTextSize(16);
        logTitle.setTypeface(null, Typeface.BOLD);
        logTitle.setPadding(0, dp(16), 0, dp(4));
        container.addView(logTitle);

        List<SkipLogDb.LogEntry> logs = db.getRecentLogs(100);
        if (logs.isEmpty()) {
            container.addView(makeText("暂无记录", 13, "#757575"));
        }
        for (SkipLogDb.LogEntry e : logs) {
            container.addView(makeLogRow(e));
        }
    }

    private TextView makeLogRow(SkipLogDb.LogEntry e) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(e.time));
        String foundStr = e.found ? "✅找到(" + e.match + ")" : "❌未找到";
        String result = e.clicked ? ("点击→" + e.resultText()) : "未点击";

        TextView tv = new TextView(this);
        tv.setText(appName(e.pkg) + "\n" +
                time + "  " + (e.event.contains("window") ? "开屏" : "内容变化") + "\n" +
                "    " + foundStr + "  " + result);
        tv.setTextSize(13);
        tv.setPadding(dp(8), dp(6), dp(4), dp(6));
        tv.setBackgroundColor(0x0D000000);
        return tv;
    }

    private TextView makeText(String text, float size, String color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(android.graphics.Color.parseColor(color));
        tv.setPadding(dp(8), dp(4), dp(4), dp(4));
        return tv;
    }

    private String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "未知App";
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(info);
            return name + " (" + pkg + ")";
        } catch (Exception e) {
            return pkg;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
