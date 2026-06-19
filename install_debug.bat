@echo off
chcp 65001 >nul
set APK=VehicleInfoNcnn-debug-installable.apk
if not exist "%APK%" (
  echo 请把本脚本放在 APK 同一目录，或把 APK 改名为 %APK%
  pause
  exit /b 1
)
echo 正在安装 %APK% ...
adb install -r "%APK%"
if errorlevel 1 (
  echo.
  echo 安装失败。可能是旧版签名冲突，正在尝试卸载旧包后重装...
  adb uninstall com.jlxc.vehicleinfoncnn
  adb install -r "%APK%"
)
pause
