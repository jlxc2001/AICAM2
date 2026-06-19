package com.jlxc.vehicleinfoncnn;

public class Detection {
    public final int labelId;
    public final String label;
    public final float x;
    public final float y;
    public final float width;
    public final float height;
    public final float confidence;
    public final float areaRatio;

    public Detection(int labelId, String label, float x, float y, float width, float height, float confidence, float areaRatio) {
        this.labelId = labelId;
        this.label = label;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.confidence = confidence;
        this.areaRatio = areaRatio;
    }

    public String infoLine() {
        return label + " " + Math.round(confidence * 100f) + "%  "
                + "框:" + Math.round(width) + "×" + Math.round(height)
                + "  占画面:" + String.format(java.util.Locale.US, "%.1f", areaRatio * 100f) + "%";
    }
}
