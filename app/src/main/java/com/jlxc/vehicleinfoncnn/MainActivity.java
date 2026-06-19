package com.jlxc.vehicleinfoncnn;

import android.Manifest;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.annotation.OptIn;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import android.view.WindowManager;
import java.util.Locale;
import java.util.List;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 1001;
    private static final String PREFS = "tactical_detector_settings";
    private static final String KEY_CONF = "confidence";
    private static final String KEY_NMS = "nms";
    private static final String KEY_MAX = "max_results";
    private static final String KEY_INPUT = "input_size";
    private static final String KEY_INTERVAL = "infer_interval";
    private static final String KEY_LABELS = "show_labels";
    private static final String KEY_RETICLE = "show_reticle";
    private static final String KEY_VEHICLE_ONLY = "vehicle_only";
    private static final String KEY_GPU = "use_gpu";
    private static final String KEY_CAMERA_ID = "camera_id";
    private static final String KEY_WIDE_ZOOM = "wide_zoom";
    private static final String KEY_COMPAT_PREVIEW = "compat_preview";
    private static final String KEY_ANALYSIS_SIZE = "analysis_size";
    private static final String KEY_LEFT_RISK_LINE = "left_risk_line";
    private static final String KEY_RIGHT_RISK_LINE = "right_risk_line";
    private static final int UDP_COMMAND_PORT = 47210;
    private static final int HTTP_MJPEG_PORT = 47211;

    private PreviewView previewView;
    private OverlayView overlayView;
    private ExecutorService cameraExecutor;
    private VehicleDetector detector;
    private SharedPreferences prefs;
    private volatile boolean detectorReady = false;
    private volatile float confThreshold = 0.25f;
    private volatile float nmsThreshold = 0.45f;
    private volatile int maxResults = 40;
    private volatile int inputSize = 320;
    private volatile int inferIntervalMs = 120;
    private volatile boolean showLabels = true;
    private volatile boolean showReticle = true;
    private volatile boolean vehicleOnly = false;
    private volatile boolean useGpu = false;
    private volatile String selectedCameraId = "";
    private volatile float wideZoomRatio = 1.0f;
    private volatile boolean compatPreview = true;
    private volatile int analysisSizeMode = 2; // 0=640x480, 1=960x540, 2=1280x720, 3=CameraX auto
    private volatile float leftRiskLine = 0.45f;
    private volatile float rightRiskLine = 0.55f;
    private volatile boolean leftDanger = false;
    private volatile boolean rightDanger = false;
    private volatile int dangerStatus = 3; // 0=both, 1=left, 2=right, 3=clear
    private volatile String activeTurn = "NONE";
    private volatile Camera boundCamera;
    private RearVisionBridge rearBridge;
    private StreamFrameRenderer streamFrameRenderer;
    private long lastInferMs = 0L;
    private long lastErrorToastMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();
        buildUi();

        streamFrameRenderer = new StreamFrameRenderer(getAssets());
        rearBridge = new RearVisionBridge(UDP_COMMAND_PORT, HTTP_MJPEG_PORT, this::handleRearCommand);
        rearBridge.start();

        cameraExecutor = Executors.newSingleThreadExecutor();
        detector = new VehicleDetector();
        detectorReady = detector.init(getAssets(), useGpu, inputSize);
        if (detectorReady) {
            detector.setOptions(confThreshold, nmsThreshold, maxResults);
        } else {
            Toast.makeText(this, "模型加载失败：请确认 assets 中有 yolov8n.ncnn.param / yolov8n.ncnn.bin", Toast.LENGTH_LONG).show();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        root.setLongClickable(true);
        root.setOnLongClickListener(v -> {
            if (overlayView != null) overlayView.setRiskLineEditMode(false);
            showSettingsDialog();
            return true;
        });

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(compatPreview ? PreviewView.ImplementationMode.COMPATIBLE : PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setLongClickable(true);
        previewView.setOnLongClickListener(v -> {
            if (overlayView != null) overlayView.setRiskLineEditMode(false);
            showSettingsDialog();
            return true;
        });

        overlayView = new OverlayView(this);
        overlayView.setRenderOptions(showLabels, showReticle);
        overlayView.setRiskState(leftDanger, rightDanger, leftRiskLine, rightRiskLine);
        overlayView.setRiskLineChangeListener((leftLine, rightLine) -> {
            leftRiskLine = leftLine;
            rightRiskLine = rightLine;
            saveSettings();
            if (rearBridge != null) rearBridge.updateStatus(buildRearStatusJson());
            Toast.makeText(this, "左右风险阈值线已保存", Toast.LENGTH_SHORT).show();
        });
        overlayView.setLongClickable(true);
        overlayView.setOnLongClickListener(v -> {
            if (overlayView != null) overlayView.setRiskLineEditMode(false);
            showSettingsDialog();
            return true;
        });

        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        enterImmersiveMode();

        Toast.makeText(this, "长按设置；局域网后视节点已启动", Toast.LENGTH_SHORT).show();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                if (previewView != null) {
                    previewView.setImplementationMode(compatPreview ?
                            PreviewView.ImplementationMode.COMPATIBLE : PreviewView.ImplementationMode.PERFORMANCE);
                }

                List<CameraSelector> selectors = buildCameraSelectorFallbacks();
                List<Size> sizes = buildAnalysisSizeFallbacks();
                Throwable lastError = null;
                boolean bound = false;

                outer:
                for (CameraSelector selector : selectors) {
                    for (Size targetSize : sizes) {
                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                        if (targetSize != null) {
                            analysisBuilder.setTargetResolution(targetSize);
                        }
                        ImageAnalysis analysis = analysisBuilder.build();
                        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                        try {
                            cameraProvider.unbindAll();
                            boundCamera = cameraProvider.bindToLifecycle(this, selector, preview, analysis);
                            bound = true;
                            applyWideZoomIfNeeded(boundCamera);
                            break outer;
                        } catch (Throwable bindError) {
                            lastError = bindError;
                            try { cameraProvider.unbindAll(); } catch (Throwable ignored) { }
                        }
                    }
                }

                if (!bound) {
                    selectedCameraId = "";
                    wideZoomRatio = 1.0f;
                    saveSettings();
                    throw lastError == null ? new IllegalStateException("No camera can be bound") : lastError;
                }
                enterImmersiveMode();
            } catch (Throwable e) {
                selectedCameraId = "";
                wideZoomRatio = 1.0f;
                saveSettings();
                Toast.makeText(this, "相机启动失败，已回退默认镜头：" + safeMsg(e), Toast.LENGTH_LONG).show();
                enterImmersiveMode();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private List<CameraSelector> buildCameraSelectorFallbacks() {
        List<CameraSelector> selectors = new ArrayList<>();
        String cameraId = selectedCameraId == null ? "" : selectedCameraId.trim();
        if (!cameraId.isEmpty()) {
            try { selectors.add(buildCameraSelectorById(cameraId)); } catch (Throwable ignored) { }
        }
        selectors.add(CameraSelector.DEFAULT_BACK_CAMERA);
        selectors.add(CameraSelector.DEFAULT_FRONT_CAMERA);
        return selectors;
    }

    private List<Size> buildAnalysisSizeFallbacks() {
        List<Size> sizes = new ArrayList<>();
        if (analysisSizeMode == 3) {
            sizes.add(null);
            sizes.add(new Size(640, 480));
            return sizes;
        }
        if (analysisSizeMode == 2) sizes.add(new Size(1280, 720));
        else if (analysisSizeMode == 1) sizes.add(new Size(960, 540));
        else sizes.add(new Size(640, 480));
        sizes.add(new Size(640, 480));
        sizes.add(null);
        return sizes;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void applyWideZoomIfNeeded(Camera camera) {
        if (camera == null) return;
        final float z = wideZoomRatio <= 0f ? 1.0f : wideZoomRatio;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                CaptureRequestOptions opts = new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                        .build();
                Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(opts);
            }
        } catch (Throwable ignored) { }

        try {
            // CameraX 自己的 ZoomControl 更稳，但有些机型不允许小于 1.0。
            // 小于 1.0 的广角倍率优先交给上面的 Camera2 CONTROL_ZOOM_RATIO。
            if (z >= 1.0f) camera.getCameraControl().setZoomRatio(z);
        } catch (Throwable ignored) { }
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private CameraSelector buildCameraSelectorById(String cameraId) {
        if (cameraId == null || cameraId.trim().isEmpty()) return CameraSelector.DEFAULT_BACK_CAMERA;
        final String targetId = cameraId.trim();
        return new CameraSelector.Builder().addCameraFilter(cameraInfos -> {
            List<androidx.camera.core.CameraInfo> out = new ArrayList<>();
            for (androidx.camera.core.CameraInfo info : cameraInfos) {
                try {
                    String id = androidx.camera.camera2.interop.Camera2CameraInfo.from(info).getCameraId();
                    if (targetId.equals(id)) out.add(info);
                } catch (Throwable ignored) { }
            }
            return out;
        }).build();
    }

    private String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        return m;
    }

    private float minFocalLength(float[] focals) {
        if (focals == null || focals.length == 0) return -1f;
        float min = Float.MAX_VALUE;
        for (float f : focals) if (f > 0f && f < min) min = f;
        return min == Float.MAX_VALUE ? -1f : min;
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        if (!detectorReady || now - lastInferMs < inferIntervalMs) {
            imageProxy.close();
            return;
        }
        lastInferMs = now;

        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) bitmap = rotateBitmap(bitmap, rotation);
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }

            Detection[] arr = detector.detect(bitmap);
            List<Detection> list = new ArrayList<>();
            if (arr != null) {
                for (Detection d : arr) {
                    if (!vehicleOnly || isRearRiskLabel(d.labelId)) list.add(d);
                }
            }
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            evaluateRearRisk(list, bw, bh);
            String statusJson = buildRearStatusJson();
            if (rearBridge != null) {
                rearBridge.updateStatus(statusJson);
                try {
                    byte[] frame = streamFrameRenderer.render(bitmap, list, leftRiskLine, rightRiskLine,
                            leftDanger, rightDanger, dangerStatus, activeTurn, showLabels);
                    rearBridge.updateFrame(frame);
                } catch (Throwable ignored) { }
            }
            runOnUiThread(() -> {
                overlayView.setDetections(list, bw, bh);
                overlayView.setRiskState(leftDanger, rightDanger, leftRiskLine, rightRiskLine);
            });
        } catch (Throwable t) {
            long tNow = System.currentTimeMillis();
            if (tNow - lastErrorToastMs > 2500) {
                lastErrorToastMs = tNow;
                runOnUiThread(() -> Toast.makeText(this, "识别异常：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        } finally {
            imageProxy.close();
        }
    }

    private boolean isRearRiskLabel(int labelId) {
        // COCO safety-first rear blind-spot classes:
        // 0 person, 1 bicycle, 2 car, 3 motorcycle, 5 bus, 7 truck.
        // 为了并线安全，人也纳入风险目标；只要疑似人/车出现在左右风险区，就触发红框和状态上报。
        return labelId == 0 || labelId == 1 || labelId == 2 || labelId == 3 || labelId == 5 || labelId == 7;
    }


    private void evaluateRearRisk(List<Detection> list, int imageW, int imageH) {
        boolean left = false;
        boolean right = false;
        if (list != null) {
            for (Detection d : list) {
                if (d == null || !isRearRiskLabel(d.labelId)) continue;
                // Safety-first mode: no extra confidence or area gate here.
                // The detector's own threshold still controls what enters the detection list,
                // but risk evaluation does not apply a second, stricter filter.
                float cx = (d.x + d.width * 0.5f) / Math.max(1f, imageW);
                if (cx <= leftRiskLine) left = true;
                if (cx >= rightRiskLine) right = true;
            }
        }
        leftDanger = left;
        rightDanger = right;
        if (left && right) dangerStatus = 0;
        else if (left) dangerStatus = 1;
        else if (right) dangerStatus = 2;
        else dangerStatus = 3; // 3=clear；用户定义的 0/1/2 保留给有风险状态。
    }

    private void handleRearCommand(String commandText, InetAddress address, int port) {
        if (commandText == null) return;
        String up = commandText.trim().toUpperCase(Locale.US);
        if (up.contains("TURN_LEFT") || up.equals("LEFT") || up.contains("MIKU_LEFT")) {
            activeTurn = "LEFT";
            if (rearBridge != null) rearBridge.enableStreamFor(15000);
        } else if (up.contains("TURN_RIGHT") || up.equals("RIGHT") || up.contains("MIKU_RIGHT")) {
            activeTurn = "RIGHT";
            if (rearBridge != null) rearBridge.enableStreamFor(15000);
        } else if (up.contains("TURN_OFF") || up.contains("CANCEL") || up.contains("STREAM_OFF") || up.contains("NONE")) {
            activeTurn = "NONE";
            if (rearBridge != null && up.contains("STREAM_OFF")) rearBridge.disableStream();
        } else if (up.contains("STREAM_ON") || up.contains("PING")) {
            if (rearBridge != null) rearBridge.enableStreamFor(15000);
        }
        if (rearBridge != null) rearBridge.updateStatus(buildRearStatusJson());
    }

    private String buildRearStatusJson() {
        String ip = getLocalIpAddress();
        return String.format(Locale.US,
                "{\"type\":\"miku_rear_ai\",\"status\":%d,\"left\":%s,\"right\":%s,\"turn\":\"%s\",\"stream\":\"http://%s:%d/stream\",\"snapshot\":\"http://%s:%d/snapshot.jpg\",\"leftLine\":%.3f,\"rightLine\":%.3f,\"ts\":%d}",
                dangerStatus,
                leftDanger ? "true" : "false",
                rightDanger ? "true" : "false",
                activeTurn == null ? "NONE" : activeTurn,
                ip, HTTP_MJPEG_PORT,
                ip, HTTP_MJPEG_PORT,
                leftRiskLine,
                rightRiskLine,
                System.currentTimeMillis());
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "0.0.0.0";
    }

    private void showSettingsDialog() {
        final int oldInputSize = inputSize;
        final boolean oldUseGpu = useGpu;
        final String oldCameraId = selectedCameraId == null ? "" : selectedCameraId;
        final float oldWideZoomRatio = wideZoomRatio;
        final boolean oldCompatPreview = compatPreview;
        final int oldAnalysisSizeMode = analysisSizeMode;
        Typeface hudTypeface;
        try {
            hudTypeface = Typeface.createFromAsset(getAssets(), "fonts/jlxc_hud_vector.ttf");
        } catch (Throwable t) {
            hudTypeface = Typeface.create("monospace", Typeface.BOLD);
        }

        final Dialog dialog = new Dialog(this);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(panelBg(0xee020900, 0xff39ff14, 1));
        scrollView.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("FCS / HUD CONFIG");
        title.setTextColor(0xffa8ff9c);
        title.setTextSize(20f);
        title.setTypeface(hudTypeface);
        title.setLetterSpacing(0.08f);
        title.setPadding(0, 0, 0, dp(4));
        panel.addView(title);

        TextView sub = new TextView(this);
        sub.setText("VISION SYSTEM // LONG PRESS TO CLOSE PANEL // ALL TARGETS BY DEFAULT");
        sub.setTextColor(0xaa39ff14);
        sub.setTextSize(10.5f);
        sub.setTypeface(hudTypeface);
        sub.setLetterSpacing(0.05f);
        sub.setPadding(0, 0, 0, dp(12));
        panel.addView(sub);

        TextView hint = new TextView(this);
        hint.setText("默认显示 YOLOv8 COCO 全类别；后视联动建议 1280x720 分析分辨率。多摄手机优先用 AUTO + 0.6x 广角倍率；车机端通过 UDP 指令触发，HTTP MJPEG 拉取 1280x720 画面。保存后立即应用。 ");
        hint.setTextSize(13.5f);
        hint.setTextColor(0xff8dff82);
        hint.setLineSpacing(0, 1.15f);
        hint.setPadding(0, 0, 0, dp(10));
        panel.addView(hint);

        SeekControl conf = addSeek(panel, "01 // 置信度阈值", Math.round(confThreshold * 100f), 5, 90, 1, "%");
        SeekControl nms = addSeek(panel, "02 // NMS重叠过滤", Math.round(nmsThreshold * 100f), 10, 90, 1, "%");
        SeekControl max = addSeek(panel, "03 // 最多显示目标", maxResults, 1, 80, 1, "个");
        SeekControl interval = addSeek(panel, "04 // 推理间隔", inferIntervalMs, 60, 500, 20, "ms");

        TextView inputTitle = sectionTitle("05 // 模型输入尺寸");
        panel.addView(inputTitle);
        RadioGroup inputGroup = new RadioGroup(this);
        inputGroup.setOrientation(RadioGroup.HORIZONTAL);
        inputGroup.setPadding(0, 0, 0, dp(4));
        RadioButton size320 = radio("320 快速");
        RadioButton size416 = radio("416 平衡");
        RadioButton size640 = radio("640 高精度");
        inputGroup.addView(size320);
        inputGroup.addView(size416);
        inputGroup.addView(size640);
        if (inputSize == 640) size640.setChecked(true);
        else if (inputSize == 416) size416.setChecked(true);
        else size320.setChecked(true);
        panel.addView(inputGroup);

        TextView cameraTitle = sectionTitle("06 // 摄像头选择");
        panel.addView(cameraTitle);
        RadioGroup cameraGroup = new RadioGroup(this);
        cameraGroup.setOrientation(RadioGroup.VERTICAL);
        cameraGroup.setPadding(0, 0, 0, dp(4));
        List<CameraOption> cameraOptions = loadCameraOptions();
        RadioButton autoCam = radio("AUTO  DEFAULT BACK  // 系统默认后置，最稳");
        autoCam.setTag("");
        cameraGroup.addView(autoCam);
        boolean checkedAny = false;
        if (selectedCameraId == null || selectedCameraId.trim().isEmpty()) {
            autoCam.setChecked(true);
            checkedAny = true;
        }
        if (cameraOptions.isEmpty()) {
            TextView noCam = new TextView(this);
            noCam.setText("未读取到 Camera2 摄像头列表；仍可使用 AUTO 默认后置镜头。");
            noCam.setTextSize(12.5f);
            noCam.setTextColor(0x9939ff14);
            cameraGroup.addView(noCam);
        } else {
            for (CameraOption option : cameraOptions) {
                RadioButton rb = radio(option.title);
                rb.setTag(option.cameraId);
                cameraGroup.addView(rb);
                if (!checkedAny && option.cameraId.equals(selectedCameraId)) {
                    rb.setChecked(true);
                    checkedAny = true;
                }
            }
        }
        panel.addView(cameraGroup);
        TextView cameraHint = new TextView(this);
        cameraHint.setText("提示：AUTO 最稳；焦距数字越小通常越广。部分厂商会隐藏物理超广角，某些 CAM-ID 不能被 CameraX 直接绑定，失败时会自动回退 AUTO。");
        cameraHint.setTextSize(12f);
        cameraHint.setTextColor(0x9939ff14);
        cameraHint.setPadding(0, dp(2), 0, dp(8));
        panel.addView(cameraHint);

        TextView zoomTitle = sectionTitle("07 // 广角倍率 / 逻辑多摄");
        panel.addView(zoomTitle);
        RadioGroup zoomGroup = new RadioGroup(this);
        zoomGroup.setOrientation(RadioGroup.HORIZONTAL);
        zoomGroup.setPadding(0, 0, 0, dp(4));
        RadioButton zoomOff = radio("OFF/1.0x"); zoomOff.setTag("1.0");
        RadioButton zoom06 = radio("0.6x"); zoom06.setTag("0.6");
        RadioButton zoom07 = radio("0.7x"); zoom07.setTag("0.7");
        RadioButton zoom08 = radio("0.8x"); zoom08.setTag("0.8");
        zoomGroup.addView(zoomOff); zoomGroup.addView(zoom06); zoomGroup.addView(zoom07); zoomGroup.addView(zoom08);
        if (Math.abs(wideZoomRatio - 0.6f) < 0.05f) zoom06.setChecked(true);
        else if (Math.abs(wideZoomRatio - 0.7f) < 0.05f) zoom07.setChecked(true);
        else if (Math.abs(wideZoomRatio - 0.8f) < 0.05f) zoom08.setChecked(true);
        else zoomOff.setChecked(true);
        panel.addView(zoomGroup);
        TextView zoomHint = new TextView(this);
        zoomHint.setText("你的系统相机广角 dump 显示 zoomRatio=0.6。很多新机不是直接暴露广角 Camera-ID，而是在默认后摄上设置 0.6x 触发超广角。Android 11+ 成功率更高。 ");
        zoomHint.setTextSize(12f);
        zoomHint.setTextColor(0x9939ff14);
        zoomHint.setPadding(0, dp(2), 0, dp(8));
        panel.addView(zoomHint);

        TextView analysisTitle = sectionTitle("08 // 相机分析分辨率");
        panel.addView(analysisTitle);
        RadioGroup analysisGroup = new RadioGroup(this);
        analysisGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton ana640 = radio("640x480  // 最稳，低延迟"); ana640.setTag("0");
        RadioButton ana960 = radio("960x540  // 平衡，适合旗舰"); ana960.setTag("1");
        RadioButton ana720 = radio("1280x720 // 更清楚，压力更大"); ana720.setTag("2");
        RadioButton anaAuto = radio("AUTO     // 交给 CameraX 自动选择"); anaAuto.setTag("3");
        analysisGroup.addView(ana640); analysisGroup.addView(ana960); analysisGroup.addView(ana720); analysisGroup.addView(anaAuto);
        if (analysisSizeMode == 1) ana960.setChecked(true);
        else if (analysisSizeMode == 2) ana720.setChecked(true);
        else if (analysisSizeMode == 3) anaAuto.setChecked(true);
        else ana640.setChecked(true);
        panel.addView(analysisGroup);

        Switch compat = sw("09 // 兼容预览模式 SurfaceView/TextureView", compatPreview);
        panel.addView(compat);
        TextView compatHint = new TextView(this);
        compatHint.setText("兼容预览模式更适合老机/魔改系统/部分多摄设备；旗舰机追求低延迟可关闭试试。出现黑屏、预览拉伸、切镜头失败时建议打开。 ");
        compatHint.setTextSize(12f);
        compatHint.setTextColor(0x9939ff14);
        compatHint.setPadding(0, dp(2), 0, dp(8));
        panel.addView(compatHint);

        Switch labels = sw("10 // 显示目标标签", showLabels);
        Switch reticle = sw("11 // 显示火控准星", showReticle);
        Switch onlyVehicles = sw("12 // 只显示人/交通风险目标", vehicleOnly);
        Switch gpu = sw("13 // 尝试 GPU / Vulkan", useGpu);
        panel.addView(labels);
        panel.addView(reticle);
        panel.addView(onlyVehicles);
        panel.addView(gpu);

        TextView gpuHint = new TextView(this);
        gpuHint.setText("GPU 依赖手机 Vulkan 驱动；如果开启后模型加载失败，关闭该项即可。骁龙810建议 CPU + 320；天玑9500可试 640 + Vulkan + 0.6x。 ");
        gpuHint.setTextSize(12f);
        gpuHint.setTextColor(0x9939ff14);
        gpuHint.setPadding(0, dp(5), 0, dp(12));
        panel.addView(gpuHint);

        TextView rearTitle = sectionTitle("14 // 后视联动 / 局域网输出");
        panel.addView(rearTitle);
        TextView rearInfo = new TextView(this);
        String ip = getLocalIpAddress();
        rearInfo.setText("UDP 控制端口：" + UDP_COMMAND_PORT
                + "\nHTTP/MJPEG： http://" + ip + ":" + HTTP_MJPEG_PORT + "/stream"
                + "\n状态 JSON： http://" + ip + ":" + HTTP_MJPEG_PORT + "/status"
                + "\n状态码：0=左右都有车，1=左侧有车，2=右侧有车，3=两侧安全/未检测到风险。车机端收到转向后可显示 1280x720 视频流，占 2560x720 屏幕一半。");
        rearInfo.setTextSize(12.2f);
        rearInfo.setTextColor(0xff8dff82);
        rearInfo.setLineSpacing(0, 1.15f);
        rearInfo.setPadding(0, dp(2), 0, dp(8));
        panel.addView(rearInfo);

        TextView lineInfo = new TextView(this);
        lineInfo.setText(String.format(Locale.US, "左右风险阈值线：LEFT %.0f%% / RIGHT %.0f%%。点击下面按钮后，直接在预览画面拖动两条竖线保存。", leftRiskLine * 100f, rightRiskLine * 100f));
        lineInfo.setTextSize(12.2f);
        lineInfo.setTextColor(0x9939ff14);
        lineInfo.setPadding(0, 0, 0, dp(8));
        panel.addView(lineInfo);

        Button editLines = tacticalButton("EDIT RISK LINES", hudTypeface);
        editLines.setOnClickListener(v -> {
            overlayView.setRiskLineEditMode(true);
            dialog.dismiss();
            enterImmersiveMode();
            Toast.makeText(this, "拖动两条竖线调整左右侧有车判定范围", Toast.LENGTH_LONG).show();
        });
        panel.addView(editLines);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        Button cancel = tacticalButton("CANCEL", hudTypeface);
        Button reset = tacticalButton("RESET", hudTypeface);
        Button apply = tacticalButton("APPLY", hudTypeface);
        buttons.addView(cancel);
        buttons.addView(reset);
        buttons.addView(apply);
        panel.addView(buttons);

        cancel.setOnClickListener(v -> dialog.dismiss());
        reset.setOnClickListener(v -> {
            resetSettings();
            saveSettings();
            applySettings(inputSize != oldInputSize || useGpu != oldUseGpu);
            if (shouldRestartCamera(oldCameraId, oldWideZoomRatio, oldCompatPreview, oldAnalysisSizeMode)) startCamera();
            dialog.dismiss();
            enterImmersiveMode();
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
        });
        apply.setOnClickListener(v -> {
            confThreshold = conf.value() / 100f;
            nmsThreshold = nms.value() / 100f;
            maxResults = max.value();
            inferIntervalMs = interval.value();
            showLabels = labels.isChecked();
            showReticle = reticle.isChecked();
            vehicleOnly = onlyVehicles.isChecked();
            useGpu = gpu.isChecked();
            compatPreview = compat.isChecked();
            wideZoomRatio = readSelectedFloat(zoomGroup, 1.0f);
            analysisSizeMode = readSelectedInt(analysisGroup, 0);
            String newCameraId = readSelectedCameraId(cameraGroup);
            if (newCameraId != null) selectedCameraId = newCameraId;
            if (size640.isChecked()) inputSize = 640;
            else if (size416.isChecked()) inputSize = 416;
            else inputSize = 320;
            saveSettings();
            applySettings(inputSize != oldInputSize || useGpu != oldUseGpu);
            if (shouldRestartCamera(oldCameraId, oldWideZoomRatio, oldCompatPreview, oldAnalysisSizeMode)) {
                startCamera();
                String camLabel = (selectedCameraId == null || selectedCameraId.trim().isEmpty()) ? "AUTO" : selectedCameraId;
                Toast.makeText(this, "相机参数已切换：CAM " + camLabel + " / " + String.format(Locale.US, "%.1fx", wideZoomRatio), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            enterImmersiveMode();
        });

        dialog.setOnDismissListener(d -> enterImmersiveMode());
        dialog.setContentView(scrollView);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private String readSelectedCameraId(RadioGroup group) {
        if (group == null) return null;
        int id = group.getCheckedRadioButtonId();
        if (id == View.NO_ID) return null;
        View v = group.findViewById(id);
        Object tag = v == null ? null : v.getTag();
        return tag == null ? null : String.valueOf(tag);
    }


    private float readSelectedFloat(RadioGroup group, float def) {
        if (group == null) return def;
        int id = group.getCheckedRadioButtonId();
        if (id == View.NO_ID) return def;
        View v = group.findViewById(id);
        Object tag = v == null ? null : v.getTag();
        if (tag == null) return def;
        try { return Float.parseFloat(String.valueOf(tag)); } catch (Throwable ignored) { return def; }
    }

    private int readSelectedInt(RadioGroup group, int def) {
        if (group == null) return def;
        int id = group.getCheckedRadioButtonId();
        if (id == View.NO_ID) return def;
        View v = group.findViewById(id);
        Object tag = v == null ? null : v.getTag();
        if (tag == null) return def;
        try { return Integer.parseInt(String.valueOf(tag)); } catch (Throwable ignored) { return def; }
    }

    private boolean shouldRestartCamera(String oldCameraId, float oldZoom, boolean oldCompat, int oldAnalysisMode) {
        String newCameraId = selectedCameraId == null ? "" : selectedCameraId;
        return !oldCameraId.equals(newCameraId)
                || Math.abs(oldZoom - wideZoomRatio) > 0.01f
                || oldCompat != compatPreview
                || oldAnalysisMode != analysisSizeMode;
    }

    private static class CameraOption {
        final String cameraId;
        final String title;
        CameraOption(String cameraId, String title) {
            this.cameraId = cameraId;
            this.title = title;
        }
    }

    private List<CameraOption> loadCameraOptions() {
        List<CameraOption> out = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            Arrays.sort(ids);
            for (String id : ids) {
                try {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    String facingText = facingToText(facing);
                    String focalText = focalToText(focals);
                    String hint = "";
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        float minFocal = minFocalLength(focals);
                        if (minFocal > 0f && minFocal <= 2.5f) hint = "  // 可能是超广角";
                        else hint = "  // 后置";
                    }
                    out.add(new CameraOption(id, "CAM-" + id + "  " + facingText + "  " + focalText + hint));
                } catch (Throwable ignored) {
                    out.add(new CameraOption(id, "CAM-" + id + "  // 信息读取失败"));
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private String facingToText(Integer facing) {
        if (facing == null) return "UNKNOWN";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (Build.VERSION.SDK_INT >= 23 && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return "UNKNOWN";
    }

    private String focalToText(float[] focals) {
        if (focals == null || focals.length == 0) return "FOCAL ?";
        StringBuilder sb = new StringBuilder("FOCAL ");
        for (int i = 0; i < focals.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(String.format(Locale.US, "%.2f", focals[i]));
        }
        sb.append("mm");
        return sb.toString();
    }

    private GradientDrawable panelBg(int color, int strokeColor, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(0f);
        bg.setStroke(dp(strokeDp), strokeColor);
        return bg;
    }

    private Button tacticalButton(String text, Typeface typeface) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xff39ff14);
        b.setTextSize(11.5f);
        b.setTypeface(typeface);
        b.setLetterSpacing(0.08f);
        b.setAllCaps(false);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        b.setBackground(panelBg(0x33001600, 0xaa39ff14, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(dp(7), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(0xff39ff14);
        t.setGravity(Gravity.START);
        t.setPadding(0, dp(12), 0, dp(4));
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return t;
    }

    private RadioButton radio(String text) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(text);
        r.setTextSize(13.5f);
        r.setTextColor(0xffa8ff9c);
        r.setButtonTintList(tacticalTint());
        r.setPadding(0, 0, dp(10), 0);
        return r;
    }

    private Switch sw(String text, boolean checked) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(14f);
        s.setTextColor(0xffa8ff9c);
        s.setChecked(checked);
        s.setPadding(0, dp(6), 0, dp(6));
        s.setThumbTintList(tacticalTint());
        s.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0x7739ff14, 0x3339ff14}));
        return s;
    }

    private ColorStateList tacticalTint() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xff39ff14, 0x8839ff14});
    }

    private SeekControl addSeek(LinearLayout parent, String title, int value, int min, int max, int step, String suffix) {
        TextView label = sectionTitle(title + " : " + value + suffix);
        parent.addView(label);
        SeekBar bar = new SeekBar(this);
        int progressMax = Math.max(1, (max - min) / step);
        bar.setMax(progressMax);
        int progress = Math.max(0, Math.min(progressMax, (value - min) / step));
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(0xff39ff14));
        bar.setThumbTintList(ColorStateList.valueOf(0xffa8ff9c));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x4439ff14));
        SeekControl control = new SeekControl(bar, label, title, min, step, suffix);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(title + " : " + control.value() + suffix);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(bar);
        return control;
    }

    private static class SeekControl {
        final SeekBar bar;
        final TextView label;
        final String title;
        final int min;
        final int step;
        final String suffix;
        SeekControl(SeekBar bar, TextView label, String title, int min, int step, String suffix) {
            this.bar = bar;
            this.label = label;
            this.title = title;
            this.min = min;
            this.step = step;
            this.suffix = suffix;
        }
        int value() {
            return min + bar.getProgress() * step;
        }
    }

    private void loadSettings() {
        confThreshold = prefs.getFloat(KEY_CONF, 0.25f);
        nmsThreshold = prefs.getFloat(KEY_NMS, 0.45f);
        maxResults = prefs.getInt(KEY_MAX, 40);
        inputSize = prefs.getInt(KEY_INPUT, 320);
        inferIntervalMs = prefs.getInt(KEY_INTERVAL, 120);
        showLabels = prefs.getBoolean(KEY_LABELS, true);
        showReticle = prefs.getBoolean(KEY_RETICLE, true);
        vehicleOnly = prefs.getBoolean(KEY_VEHICLE_ONLY, false);
        useGpu = prefs.getBoolean(KEY_GPU, false);
        selectedCameraId = prefs.getString(KEY_CAMERA_ID, "");
        wideZoomRatio = prefs.getFloat(KEY_WIDE_ZOOM, 1.0f);
        compatPreview = prefs.getBoolean(KEY_COMPAT_PREVIEW, true);
        analysisSizeMode = prefs.getInt(KEY_ANALYSIS_SIZE, 2);
        leftRiskLine = prefs.getFloat(KEY_LEFT_RISK_LINE, 0.45f);
        rightRiskLine = prefs.getFloat(KEY_RIGHT_RISK_LINE, 0.55f);
    }

    private void saveSettings() {
        prefs.edit()
                .putFloat(KEY_CONF, confThreshold)
                .putFloat(KEY_NMS, nmsThreshold)
                .putInt(KEY_MAX, maxResults)
                .putInt(KEY_INPUT, inputSize)
                .putInt(KEY_INTERVAL, inferIntervalMs)
                .putBoolean(KEY_LABELS, showLabels)
                .putBoolean(KEY_RETICLE, showReticle)
                .putBoolean(KEY_VEHICLE_ONLY, vehicleOnly)
                .putBoolean(KEY_GPU, useGpu)
                .putString(KEY_CAMERA_ID, selectedCameraId == null ? "" : selectedCameraId)
                .putFloat(KEY_WIDE_ZOOM, wideZoomRatio)
                .putBoolean(KEY_COMPAT_PREVIEW, compatPreview)
                .putInt(KEY_ANALYSIS_SIZE, analysisSizeMode)
                .putFloat(KEY_LEFT_RISK_LINE, leftRiskLine)
                .putFloat(KEY_RIGHT_RISK_LINE, rightRiskLine)
                .apply();
    }

    private void resetSettings() {
        confThreshold = 0.25f;
        nmsThreshold = 0.45f;
        maxResults = 40;
        inputSize = 320;
        inferIntervalMs = 120;
        showLabels = true;
        showReticle = true;
        vehicleOnly = false;
        useGpu = false;
        selectedCameraId = "";
        wideZoomRatio = 1.0f;
        compatPreview = true;
        analysisSizeMode = 2;
        leftRiskLine = 0.45f;
        rightRiskLine = 0.55f;
        leftDanger = false;
        rightDanger = false;
        dangerStatus = 3;
        activeTurn = "NONE";
    }

    private void applySettings(boolean reinitModel) {
        overlayView.setRenderOptions(showLabels, showReticle);
        if (!reinitModel) {
            if (detectorReady) detector.setOptions(confThreshold, nmsThreshold, maxResults);
            Toast.makeText(this, "设置已应用", Toast.LENGTH_SHORT).show();
            return;
        }
        detectorReady = false;
        Toast.makeText(this, "正在重新加载模型", Toast.LENGTH_SHORT).show();
        cameraExecutor.execute(() -> {
            boolean ok;
            try {
                detector.release();
                ok = detector.init(getAssets(), useGpu, inputSize);
                if (ok) detector.setOptions(confThreshold, nmsThreshold, maxResults);
            } catch (Throwable t) {
                ok = false;
            }
            final boolean loaded = ok;
            detectorReady = loaded;
            runOnUiThread(() -> Toast.makeText(this, loaded ? "模型已重新加载" : "模型重新加载失败", Toast.LENGTH_SHORT).show());
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static Bitmap rotateBitmap(Bitmap src, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        if (out != src) src.recycle();
        return out;
    }

    private static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) throw new IllegalStateException("ImageProxy image is null");
        byte[] nv21 = yuv420888ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 82, out);
        byte[] jpegBytes = out.toByteArray();
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
    }

    private static byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] out = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            int rowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                out[pos++] = yBuffer.get(rowStart + col);
            }
        }

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = row * vRowStride + col * vPixelStride;
                int uuPos = row * uRowStride + col * uPixelStride;
                out[pos++] = vBuffer.get(vuPos);
                out[pos++] = uBuffer.get(uuPos);
            }
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "需要相机权限才能实时识别", Toast.LENGTH_LONG).show();
        }
    }

    private void enterImmersiveMode() {
        try {
            Window window = getWindow();
            if (window == null) return;
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detectorReady = false;
        if (rearBridge != null) rearBridge.stop();
        if (detector != null) detector.release();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
    }
}
