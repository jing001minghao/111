@echo off
chcp 65001 >nul
rem ============================================================
rem 广告跳过 - 电脑端测试助手
rem 前提：手机开启 USB 调试并连接电脑，已安装 adb
rem
rem 用法：
rem   test_capture.bat       抓取当前屏幕界面并分析是否命中跳过按钮
rem   test_scenarios.bat     运行内置 11 个模拟场景测试
rem ============================================================

echo.
echo ============================================
echo  广告跳过 - 电脑端测试助手
echo ============================================
echo.

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 adb，请先安装 platform-tools 并加入 PATH
    echo 下载: https://developer.android.com/tools/releases/platform-tools
    echo 或安装 Android Studio 后重启本脚本
    pause
    exit /b 1
)

adb devices | findstr /r "device$" >nul
if %errorlevel% neq 0 (
    echo [错误] 未检测到已连接手机，请检查：
    echo   1. 手机开启「开发者选项」-「USB 调试」
    echo   2. USB 线连接电脑，手机弹窗选「允许调试」
    pause
    exit /b 1
)

echo [1/3] 手机已连接，开始抓取当前屏幕...
adb shell uiautomator dump /sdcard/window_dump.xml >nul 2>&1
adb pull /sdcard/window_dump.xml window_dump.xml >nul 2>&1

if not exist window_dump.xml (
    echo [错误] 抓取失败，请确认当前手机屏幕有内容
    pause
    exit /b 1
)

echo [2/3] 界面已导出，开始分析...
node simulator.js --xml window_dump.xml

echo.
echo [3/3] 分析完成
echo 提示：如果显示"未命中"，但屏幕上确实有跳过按钮，
echo       把 window_dump.xml 发给我，我来补规则。
echo.
pause
