# AICAM

基于 NanoDet + ncnn 的安卓录屏识别 Demo。

## 功能

- 识别来源：MediaProjection 录屏画面
- 显示方式：悬浮窗 Overlay
- 模型：NanoDet ELite0_320
- 架构：armeabi-v7a，适合 32 位安卓 10 设备测试
- HUD：目标框贴合物体，保留 TRACK / LOCK / LOCKED 与颜色变化，无扫描动画
- 本版已移除 OpenCV 依赖，避免 OpenMP 链接错误

## GitHub Actions 编译

1. 上传整个项目到 GitHub 空仓库根目录。
2. 打开 Actions。
3. 运行 `release-apk`。
4. 成功后在 Releases 或 Artifacts 下载 APK。

## 使用

1. 安装 APK。
2. 点击开始识别屏幕画面。
3. 授权悬浮窗。
4. 授权录屏。
5. 切换到你的摄像头 App，全屏显示画面。
6. AICAM 会用悬浮窗显示识别框。

## 注意

- 普通 Android App 必须经过系统录屏授权，不能静默录屏。
- 如果被识别的摄像头 App 禁止录屏，可能会黑屏。
- 本版为 CPU 推理，优先保证旧设备可用。
