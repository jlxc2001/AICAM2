package com.tencent.nanodetncnn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenDetectService extends Service {
    public static final String ACTION_START = "com.tencent.nanodetncnn.START_SCREEN_DETECT";
    public static final String ACTION_STOP = "com.tencent.nanodetncnn.STOP_SCREEN_DETECT";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL_ID = "aicam_screen_detect";
    private static final int NOTIFICATION_ID = 101;

    private static final int TARGET_SIZE = 320;
    private static final long DETECT_INTERVAL_MS = 220L;

    private final NanoDetNcnn nanoDet = new NanoDetNcnn();
    private final AtomicBoolean detecting = new AtomicBoolean(false);

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private WindowManager windowManager;
    private DetectionOverlayView overlayView;

    private int captureWidth;
    private int captureHeight;
    private int densityDpi;
    private long lastDetectTime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        nanoDet.loadModel(getAssets(), 3, 0);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, buildNotification());

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (resultCode == 0 || data == null) {
                stopAll();
                stopSelf();
                return START_NOT_STICKY;
            }

            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            startOverlay();
            startCapture();
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startOverlay() {
        if (overlayView != null) return;

        overlayView = new DetectionOverlayView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SECURE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;

        windowManager.addView(overlayView, params);
    }

    private void startCapture() {
        stopCaptureOnly();

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        captureWidth = Math.max(1, metrics.widthPixels);
        captureHeight = Math.max(1, metrics.heightPixels);
        densityDpi = metrics.densityDpi;

        captureThread = new HandlerThread("AICAM-ScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                handleImage(reader);
            }
        }, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AICAM-Screen",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private void handleImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastDetectTime < DETECT_INTERVAL_MS || detecting.get()) {
            image.close();
            return;
        }
        lastDetectTime = now;
        detecting.set(true);

        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                return;
            }

            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();

            final float[] result = nanoDet.detectRGBA(buffer, width, height, rowStride, TARGET_SIZE);
            if (overlayView != null) {
                overlayView.post(new Runnable() {
                    @Override
                    public void run() {
                        overlayView.updateDetections(result);
                    }
                });
            }
        } catch (Throwable t) {
            if (overlayView != null) {
                overlayView.post(new Runnable() {
                    @Override
                    public void run() {
                        overlayView.clear();
                    }
                });
            }
        } finally {
            detecting.set(false);
            image.close();
        }
    }

    private void stopAll() {
        stopCaptureOnly();

        if (overlayView != null) {
            try {
                overlayView.clear();
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayView = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mediaProjection = null;
        }
    }

    private void stopCaptureOnly() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AICAM Screen Detect",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("AICAM 正在识别屏幕画面")
                .setContentText("请切到摄像头预览 App")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);

        return builder.build();
    }
}
