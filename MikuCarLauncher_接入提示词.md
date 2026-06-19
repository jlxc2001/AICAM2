# 给 MikuCarLauncher AI 窗口的接入提示词

请在 MikuCarLauncher 车机启动器里接入一个“后置 AI 视觉节点”。后置手机运行的 App 包名为 `com.jlxc.vehicleinfoncnn`，它在车内局域网里提供两个接口：

1. UDP 控制端口：`47210`
2. HTTP/MJPEG 视频流端口：`47211`

## 功能目标

当 MikuCarLauncher 识别到车辆左转向或右转向打开时，向后置手机发送 UDP 指令。后置手机收到指令后，会继续用自己的 ncnn/Yolo 算力判断后视画面左右侧是否存在人或疑似车辆风险，并提供 1280×720 的 MJPEG 视频流给车机显示。车机屏幕是 2560×720，因此请将该视频画面显示在屏幕一半区域，推荐尺寸为 1280×720。

## UDP 指令协议

向后置手机 IP 的 UDP `47210` 端口发送纯文本：

- 左转向打开：`TURN_LEFT`
- 右转向打开：`TURN_RIGHT`
- 转向关闭：`TURN_OFF`
- 只启动视频流/保活：`STREAM_ON`
- 关闭视频流：`STREAM_OFF`
- 测试/查询：`PING`

发送 UDP 后，请保留同一个 DatagramSocket 接收后置手机返回的 JSON 状态；或者直接轮询 HTTP `/status`。

## 状态 JSON

后置手机返回类似：

```json
{
  "type": "miku_rear_ai",
  "status": 2,
  "left": false,
  "right": true,
  "turn": "RIGHT",
  "stream": "http://后置手机IP:47211/stream",
  "snapshot": "http://后置手机IP:47211/snapshot.jpg",
  "leftLine": 0.45,
  "rightLine": 0.55,
  "ts": 1710000000000
}
```

状态码定义：

- `0`：左右两侧都有风险，禁止/谨慎变道
- `1`：左侧有风险
- `2`：右侧有风险
- `3`：未检测到左右侧风险，安全/无车

注意：用户最初要求 0/1/2 三个有风险状态；为了让车机能判断“无风险”，手机端额外提供 `3=clear`，同时也提供 `left` / `right` 两个布尔值，车机端可以优先用布尔值判断。

## 视频流

后置手机提供 MJPEG：

```text
http://后置手机IP:47211/stream
```

单帧快照：

```text
http://后置手机IP:47211/snapshot.jpg
```

视频画面已经由手机端合成好，包括：

- 原始后视摄像头画面
- ncnn AI 识别框
- 战斗机 HUD 风格叠加层
- 左/右侧有人或疑似车辆风险时对应侧红色警告框
- 1280×720 固定输出，适合放到 2560×720 车机屏幕的一半

## MikuCarLauncher 端建议逻辑

1. 在已有 CAN/转向识别逻辑里，当左转向打开：
   - 发送 UDP：`TURN_LEFT`
   - 打开或显示后视 AI 视频区域
   - 视频 URL 使用 `http://后置手机IP:47211/stream`
   - 读取状态 JSON，如果 `left=true` 或 `status=1/0`，播放“滴滴”或左侧风险提示

2. 当右转向打开：
   - 发送 UDP：`TURN_RIGHT`
   - 显示同一个 MJPEG 视频区域
   - 如果 `right=true` 或 `status=2/0`，播放“滴滴”或右侧风险提示

3. 当转向关闭：
   - 发送 UDP：`TURN_OFF`
   - 可以延迟 1～3 秒隐藏视频区域
   - 停止风险提示音

4. 视频显示区域：
   - 屏幕分辨率是 2560×720
   - 建议将视频区域设为 1280×720
   - 可以放在左半屏或右半屏，具体看 MikuCarLauncher UI 预留区域

5. 网络发现方式：
   - 第一版可以在设置里手动填写后置手机 IP
   - 后续可以做 UDP 广播发现，例如向局域网广播 `PING`，收到 `type=miku_rear_ai` 的 JSON 后自动保存 IP

## 容错建议

- 如果 HTTP 视频流断开，保留最后一帧 1 秒，然后隐藏视频区域。
- 如果 500ms 内没收到状态 JSON，就暂时按 `status=3` 处理，避免误报警。
- 如果用户正在打左转向，只需要重点判断 `left`；右转向同理重点判断 `right`。
- 如果 `status=0`，不管左转还是右转都视为有风险。
