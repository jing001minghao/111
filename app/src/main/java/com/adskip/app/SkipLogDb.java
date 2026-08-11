package com.adskip.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 跳过日志数据库 — 测试阶段记录每次跳过执行情况
 */
public class SkipLogDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "adskip_logs.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "skip_logs";

    // 列
    public static final String COL_ID = "id";
    public static final String COL_TIME = "time";           // 时间戳(ms)
    public static final String COL_PKG = "pkg";             // App包名
    public static final String COL_ACTIVITY = "activity";   // Activity名
    public static final String COL_EVENT = "event";         // 事件类型: window_change / content_change
    public static final String COL_FOUND = "found";         // 是否找到跳过按钮 0/1
    public static final String COL_MATCH = "match";         // 匹配方式: text/id/none
    public static final String COL_CLICKED = "clicked";     // 是否执行点击 0/1
    public static final String COL_SUCCESS = "success";     // 点击后是否成功跳转 0/1/-1未知

    public SkipLogDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_TIME + " INTEGER," +
                COL_PKG + " TEXT," +
                COL_ACTIVITY + " TEXT," +
                COL_EVENT + " TEXT," +
                COL_FOUND + " INTEGER," +
                COL_MATCH + " TEXT," +
                COL_CLICKED + " INTEGER," +
                COL_SUCCESS + " INTEGER" +
                ")");
        db.execSQL("CREATE INDEX idx_pkg ON " + TABLE + "(" + COL_PKG + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /**
     * 插入一条日志
     */
    public synchronized void insert(long time, String pkg, String activity, String event,
                                    boolean found, String match, boolean clicked) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(COL_TIME, time);
            cv.put(COL_PKG, pkg == null ? "" : pkg);
            cv.put(COL_ACTIVITY, activity == null ? "" : activity);
            cv.put(COL_EVENT, event);
            cv.put(COL_FOUND, found ? 1 : 0);
            cv.put(COL_MATCH, match == null ? "none" : match);
            cv.put(COL_CLICKED, clicked ? 1 : 0);
            cv.put(COL_SUCCESS, -1);  // 未知，等待确认
            db.insert(TABLE, null, cv);
        } catch (Exception ignored) {
        }
    }

    /**
     * 标记最近一次点击的记录为成功/失败
     */
    public synchronized void markLastClickResult(boolean success) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT " + COL_ID + " FROM " + TABLE +
                            " WHERE " + COL_CLICKED + "=1 AND " + COL_SUCCESS + "=-1" +
                            " ORDER BY " + COL_ID + " DESC LIMIT 1", null);
            if (c != null && c.moveToFirst()) {
                int id = c.getInt(0);
                ContentValues cv = new ContentValues();
                cv.put(COL_SUCCESS, success ? 1 : 0);
                db.update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
            }
            if (c != null) c.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 按 App 分组的统计：总次数 / 找到数 / 点击数 / 成功数
     */
    public synchronized List<AppStat> getAppStats() {
        List<AppStat> stats = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT " + COL_PKG + "," +
                            " COUNT(*) AS total," +
                            " SUM(" + COL_FOUND + ") AS found," +
                            " SUM(" + COL_CLICKED + ") AS clicked," +
                            " SUM(CASE WHEN " + COL_SUCCESS + "=1 THEN 1 ELSE 0 END) AS success" +
                            " FROM " + TABLE +
                            " GROUP BY " + COL_PKG +
                            " ORDER BY total DESC", null);
            if (c != null) {
                while (c.moveToNext()) {
                    AppStat s = new AppStat();
                    s.pkg = c.getString(0);
                    s.total = c.getInt(1);
                    s.found = c.getInt(2);
                    s.clicked = c.getInt(3);
                    s.success = c.getInt(4);
                    stats.add(s);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        return stats;
    }

    /**
     * 最近记录
     */
    public synchronized List<LogEntry> getRecentLogs(int limit) {
        List<LogEntry> logs = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.query(TABLE, null, null, null, null, null,
                    COL_ID + " DESC", String.valueOf(limit));
            if (c != null) {
                while (c.moveToNext()) {
                    LogEntry e = new LogEntry();
                    e.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                    e.time = c.getLong(c.getColumnIndexOrThrow(COL_TIME));
                    e.pkg = c.getString(c.getColumnIndexOrThrow(COL_PKG));
                    e.activity = c.getString(c.getColumnIndexOrThrow(COL_ACTIVITY));
                    e.event = c.getString(c.getColumnIndexOrThrow(COL_EVENT));
                    e.found = c.getInt(c.getColumnIndexOrThrow(COL_FOUND)) == 1;
                    e.match = c.getString(c.getColumnIndexOrThrow(COL_MATCH));
                    e.clicked = c.getInt(c.getColumnIndexOrThrow(COL_CLICKED)) == 1;
                    e.success = c.getInt(c.getColumnIndexOrThrow(COL_SUCCESS));
                    logs.add(e);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        return logs;
    }

    public synchronized void clear() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception ignored) {
        }
    }

    // ============ 数据类 ============

    public static class AppStat {
        public String pkg;
        public int total;
        public int found;
        public int clicked;
        public int success;

        public String summary() {
            return "事件 " + total + " | 找到 " + found +
                    " | 点击 " + clicked + " | 成功 " + success;
        }
    }

    public static class LogEntry {
        public long id;
        public long time;
        public String pkg;
        public String activity;
        public String event;
        public boolean found;
        public String match;
        public boolean clicked;
        public int success;  // 1成功 0失败 -1未知

        public String resultText() {
            if (clicked) {
                return success == 1 ? "成功" : (success == 0 ? "失败" : "待确认");
            }
            return found ? "未点击" : "未找到";
        }
    }
}
