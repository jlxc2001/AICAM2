本目录会被打包进 APK assets。

已内置：
- yolov8n.ncnn.param
- fonts/jlxc_hud_vector.ttf

GitHub Actions / scripts/setup_deps.sh 会自动下载：
- yolov8n.ncnn.bin

注意：
- 当前模型是 YOLOv8n COCO 通用目标检测模型。
- 默认显示所有 COCO 类别。
- App 设置里可切换为“只显示交通工具”。
- HUD 字体为项目内生成的 JLXC HUD Vector，随 APK 打包，不依赖系统字体。
