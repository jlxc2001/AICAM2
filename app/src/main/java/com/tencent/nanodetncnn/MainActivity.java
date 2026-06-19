package com.tencent.nanodetncnn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_OVERLAY = 1002;

    private final NanoDetNcnn nanoDet = new NanoDetNcnn();
    private TextView statusView;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        statusView = findViewById(R.id.status);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        boolean loaded = nanoDet.loadModel(getAssets(), 3, 0);
        statusView.setText(loaded
                ? "模型已加载：NanoDet ELite0 320 / CPU / armeabi-v7a"
                : "模型加载失败：请检查 assets 模型文件");

        Button start = findViewById(R.id.startScreenDetect);
        Button stop = findViewById(R.id.stopScreenDetect);

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScreenDetect();
            }
        });

        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScreenDetect();
            }
        });
    }

    private void startScreenDetect() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
            return;
        }

        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    private void stopScreenDetect() {
        Intent intent = new Intent(this, ScreenDetectService.class);
        intent.setAction(ScreenDetectService.ACTION_STOP);
        startService(intent);
        statusView.setText("已发送停止识别指令");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startScreenDetect();
            } else {
                Toast.makeText(this, "没有悬浮窗权限，无法显示识别框", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "录屏授权被取消", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, ScreenDetectService.class);
            intent.setAction(ScreenDetectService.ACTION_START);
            intent.putExtra(ScreenDetectService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(ScreenDetectService.EXTRA_DATA, data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            statusView.setText("屏幕识别已启动。现在可以切到摄像头预览 App。");
            Toast.makeText(this, "屏幕识别已启动", Toast.LENGTH_SHORT).show();
        }
    }
}
