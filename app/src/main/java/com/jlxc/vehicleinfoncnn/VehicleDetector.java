package com.jlxc.vehicleinfoncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public class VehicleDetector {
    static {
        System.loadLibrary("vehicle_ncnn");
    }

    public native boolean init(AssetManager assetManager, boolean useGpu, int targetSize);
    public native void setOptions(float probThreshold, float nmsThreshold, int maxResults);
    public native Detection[] detect(Bitmap bitmap);
    public native void release();
}
