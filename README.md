# AICAM

基于 ncnn + NanoDet 的安卓屏幕录屏识别 Demo。

## 功能

- 使用 MediaProjection 获取当前屏幕画面。
- 适合识别必须通过专用 App 打开的摄像头预览画面。
- 使用悬浮窗绘制识别框。
- HUD 风格识别框：TRACK / LOCK / LOCKED，绿色 / 黄色 / 红色。
- 默认只编译 armeabi-v7a，适合 32 位 Android 设备。

## 使用

1. 打开 App。
2. 点击「开始识别屏幕画面」。
3. 允许悬浮窗权限。
4. 允许录屏权限。
5. 切到摄像头预览 App。
6. 识别框会悬浮显示在画面上。

## GitHub Actions

仓库 Actions 里运行 `release-apk` 即可自动打包 Release APK。
