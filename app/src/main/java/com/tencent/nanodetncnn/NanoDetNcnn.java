package com.tencent.nanodetncnn;

import android.content.res.AssetManager;

import java.nio.ByteBuffer;

public class NanoDetNcnn {
    static {
        System.loadLibrary("nanodetncnn");
    }

    /**
     * modelid 当前只使用 3，也就是 nanodet-ELite0_320。
     * cpugpu 当前建议固定 0，低配/32位车机更稳。
     */
    public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);

    /**
     * 返回格式：
     * result[0] = 原始录屏帧宽度
     * result[1] = 原始录屏帧高度
     * 后续每个目标 6 个 float：label, prob, x, y, w, h
     */
    public native float[] detectRGBA(ByteBuffer rgba, int width, int height, int rowStride, int targetSize);
}
