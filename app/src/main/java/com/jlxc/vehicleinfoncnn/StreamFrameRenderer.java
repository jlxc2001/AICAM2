package com.jlxc.vehicleinfoncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

public class StreamFrameRenderer {
    public static final int OUT_W = 1280;
    public static final int OUT_H = 720;
    private static final int HUD_GREEN = 0xff39ff14;
    private static final int HUD_GREEN_SOFT = 0xffa8ff9c;
    private static final int ALERT_RED = 0xffff2020;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface typeface;

    public StreamFrameRenderer(AssetManager assets) {
        Typeface tf;
        try { tf = Typeface.createFromAsset(assets, "fonts/jlxc_hud_vector.ttf"); }
        catch (Throwable t) { tf = Typeface.create("monospace", Typeface.BOLD); }
        typeface = tf;
        text.setTypeface(typeface);
        text.setColor(HUD_GREEN_SOFT);
        text.setTextSize(24f);
        text.setLetterSpacing(0.08f);
        small.setTypeface(typeface);
        small.setColor(HUD_GREEN);
        small.setTextSize(18f);
        small.setLetterSpacing(0.06f);
    }

    public byte[] render(Bitmap src, List<Detection> detections, float leftLine, float rightLine,
                         boolean leftDanger, boolean rightDanger, int dangerStatus, String turn, boolean showLabels) {
        if (src == null) return placeholderJpeg(OUT_W, OUT_H, "NO CAMERA FRAME");
        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);

        float scale = Math.max(OUT_W / (float) src.getWidth(), OUT_H / (float) src.getHeight());
        float dw = src.getWidth() * scale;
        float dh = src.getHeight() * scale;
        float dx = (OUT_W - dw) * 0.5f;
        float dy = (OUT_H - dh) * 0.5f;
        RectF dst = new RectF(dx, dy, dx + dw, dy + dh);
        p.setAlpha(255);
        c.drawBitmap(src, null, dst, p);

        drawGlobalHud(c, leftLine, rightLine, leftDanger, rightDanger, dangerStatus, turn);

        if (detections != null) {
            for (int i = 0; i < detections.size(); i++) {
                Detection d = detections.get(i);
                RectF r = new RectF(
                        dx + d.x * scale,
                        dy + d.y * scale,
                        dx + (d.x + d.width) * scale,
                        dy + (d.y + d.height) * scale);
                drawTarget(c, r, d, i + 1, showLabels);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(160 * 1024);
        out.compress(Bitmap.CompressFormat.JPEG, 74, baos);
        out.recycle();
        return baos.toByteArray();
    }

    private void drawGlobalHud(Canvas c, float leftLine, float rightLine, boolean leftDanger, boolean rightDanger, int status, String turn) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.0f);
        p.setColor(0x3839ff14);
        for (int x = 80; x < OUT_W; x += 80) c.drawLine(x, 0, x, OUT_H, p);
        for (int y = 80; y < OUT_H; y += 80) c.drawLine(0, y, OUT_W, y, p);

        int lx = Math.round(leftLine * OUT_W);
        int rx = Math.round(rightLine * OUT_W);
        p.setStrokeWidth(2f);
        p.setColor(0xaa39ff14);
        c.drawLine(lx, 0, lx, OUT_H, p);
        c.drawLine(rx, 0, rx, OUT_H, p);
        small.setColor(0xcc39ff14);
        c.drawText("LEFT THR", lx + 8, 690, small);
        c.drawText("RIGHT THR", rx + 8, 690, small);

        if (leftDanger) drawAlertZone(c, new RectF(0, 0, lx, OUT_H), "LEFT BLOCKED");
        if (rightDanger) drawAlertZone(c, new RectF(rx, 0, OUT_W, OUT_H), "RIGHT BLOCKED");

        p.setColor(0xcc39ff14);
        p.setStrokeWidth(2f);
        c.drawLine(18, 18, 150, 18, p); c.drawLine(18, 18, 18, 110, p);
        c.drawLine(OUT_W - 18, 18, OUT_W - 150, 18, p); c.drawLine(OUT_W - 18, 18, OUT_W - 18, 110, p);
        c.drawLine(18, OUT_H - 18, 150, OUT_H - 18, p); c.drawLine(18, OUT_H - 18, 18, OUT_H - 110, p);
        c.drawLine(OUT_W - 18, OUT_H - 18, OUT_W - 150, OUT_H - 18, p); c.drawLine(OUT_W - 18, OUT_H - 18, OUT_W - 18, OUT_H - 110, p);

        text.setColor(HUD_GREEN_SOFT);
        c.drawText("MIKU REAR AI NODE // 1280x720", 34, 48, text);
        String right = String.format(Locale.US, "TURN:%s  STATUS:%d", safeTurn(turn), status);
        c.drawText(right, OUT_W - text.measureText(right) - 34, 48, text);
    }

    private String safeTurn(String turn) {
        return turn == null ? "NONE" : turn.toUpperCase(Locale.US);
    }

    private void drawAlertZone(Canvas c, RectF r, String label) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x28ff0000);
        c.drawRect(r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(9f);
        p.setColor(0x70ff2020);
        c.drawRect(r, p);
        p.setStrokeWidth(4f);
        p.setColor(ALERT_RED);
        c.drawRect(r.left + 8, r.top + 8, r.right - 8, r.bottom - 8, p);
        text.setColor(ALERT_RED);
        c.drawText(label, r.left + 28, 96, text);
        text.setColor(HUD_GREEN_SOFT);
    }

    private void drawTarget(Canvas c, RectF r, Detection d, int index, boolean showLabels) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(HUD_GREEN);
        float len = Math.max(28f, Math.min(Math.min(r.width(), r.height()) * 0.30f, 70f));
        drawCorner(c, r.left, r.top, len, true, true);
        drawCorner(c, r.right, r.top, len, false, true);
        drawCorner(c, r.left, r.bottom, len, true, false);
        drawCorner(c, r.right, r.bottom, len, false, false);
        p.setStrokeWidth(1.5f);
        p.setColor(0x8839ff14);
        c.drawRect(r, p);
        if (showLabels) {
            small.setColor(HUD_GREEN_SOFT);
            String label = String.format(Locale.US, "TGT-%02d %s %.0f%%", index,
                    d.label == null ? "OBJ" : d.label.toUpperCase(Locale.US), d.confidence * 100f);
            float tx = Math.max(4, Math.min(r.left, OUT_W - small.measureText(label) - 8));
            float ty = r.top - 8;
            if (ty < 24) ty = r.bottom + 24;
            c.drawText(label, tx, ty, small);
        }
    }

    private void drawCorner(Canvas c, float x, float y, float len, boolean left, boolean top) {
        float sx = left ? 1f : -1f;
        float sy = top ? 1f : -1f;
        c.drawLine(x, y, x + sx * len, y, p);
        c.drawLine(x, y, x, y + sy * len, p);
    }

    public static byte[] placeholderJpeg(int w, int h, String label) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff39ff14);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        c.drawRect(18, 18, w - 18, h - 18, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(30f);
        p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        c.drawText(label == null ? "WAITING" : label, 48, h / 2f, p);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
        b.compress(Bitmap.CompressFormat.JPEG, 72, baos);
        b.recycle();
        return baos.toByteArray();
    }
}
