#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "net.h"
#include "cpu.h"

#define TAG "AICAM-NanoDet"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct Object {
    cv::Rect_<float> rect;
    int label;
    float prob;
};

static ncnn::Net g_net;
static bool g_loaded = false;
static const int NUM_CLASS = 80;
static const float PROB_THRESHOLD = 0.40f;
static const float NMS_THRESHOLD = 0.50f;

static inline float fast_exp(float x) {
    return std::exp(x);
}

static void softmax(const float* src, float* dst, int length) {
    float alpha = -FLT_MAX;
    for (int i = 0; i < length; i++) {
        if (src[i] > alpha) alpha = src[i];
    }
    float denominator = 0.f;
    for (int i = 0; i < length; i++) {
        dst[i] = fast_exp(src[i] - alpha);
        denominator += dst[i];
    }
    if (denominator <= 0.f) denominator = 1.f;
    for (int i = 0; i < length; i++) {
        dst[i] /= denominator;
    }
}

static float intersection_area(const Object& a, const Object& b) {
    cv::Rect_<float> inter = a.rect & b.rect;
    return inter.area();
}

static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right) {
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;

    while (i <= j) {
        while (objects[i].prob > p) i++;
        while (objects[j].prob < p) j--;
        if (i <= j) {
            std::swap(objects[i], objects[j]);
            i++;
            j--;
        }
    }

    if (left < j) qsort_descent_inplace(objects, left, j);
    if (i < right) qsort_descent_inplace(objects, i, right);
}

static void qsort_descent_inplace(std::vector<Object>& objects) {
    if (objects.empty()) return;
    qsort_descent_inplace(objects, 0, (int)objects.size() - 1);
}

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold) {
    picked.clear();
    const int n = (int)objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++) {
        areas[i] = objects[i].rect.area();
    }

    for (int i = 0; i < n; i++) {
        const Object& a = objects[i];
        int keep = 1;
        for (int j : picked) {
            const Object& b = objects[j];
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[j] - inter_area;
            if (union_area <= 0.f) continue;
            if (inter_area / union_area > nms_threshold) {
                keep = 0;
                break;
            }
        }
        if (keep) picked.push_back(i);
    }
}

static void generate_proposals(const ncnn::Mat& cls_pred,
                               const ncnn::Mat& dis_pred,
                               int stride,
                               int in_pad_w,
                               int in_pad_h,
                               float prob_threshold,
                               std::vector<Object>& objects) {
    const int num_grid_x = in_pad_w / stride;
    const int num_grid_y = in_pad_h / stride;
    const int num_points = num_grid_x * num_grid_y;

    if (cls_pred.dims != 2 || dis_pred.dims != 2) {
        return;
    }

    int cls_w = cls_pred.w;
    int cls_h = cls_pred.h;
    int dis_w = dis_pred.w;
    int dis_h = dis_pred.h;

    if (cls_h < num_points || dis_h < num_points || cls_w < NUM_CLASS || dis_w < 4) {
        return;
    }

    int reg_max_1 = dis_w / 4;
    if (reg_max_1 <= 0) return;

    std::vector<float> dis_after_sm(reg_max_1);

    for (int idx = 0; idx < num_points; idx++) {
        const int y = idx / num_grid_x;
        const int x = idx % num_grid_x;

        const float* scores = cls_pred.row(idx);

        int label = -1;
        float score = prob_threshold;
        for (int c = 0; c < NUM_CLASS; c++) {
            if (scores[c] > score) {
                score = scores[c];
                label = c;
            }
        }

        if (label < 0) continue;

        const float* bbox_pred = dis_pred.row(idx);
        float dis_pred_values[4];

        for (int k = 0; k < 4; k++) {
            softmax(bbox_pred + k * reg_max_1, dis_after_sm.data(), reg_max_1);
            float dis = 0.f;
            for (int l = 0; l < reg_max_1; l++) {
                dis += l * dis_after_sm[l];
            }
            dis_pred_values[k] = dis * stride;
        }

        float cx = (x + 0.5f) * stride;
        float cy = (y + 0.5f) * stride;
        float x0 = cx - dis_pred_values[0];
        float y0 = cy - dis_pred_values[1];
        float x1 = cx + dis_pred_values[2];
        float y1 = cy + dis_pred_values[3];

        Object obj;
        obj.rect.x = x0;
        obj.rect.y = y0;
        obj.rect.width = x1 - x0;
        obj.rect.height = y1 - y0;
        obj.label = label;
        obj.prob = score;
        objects.push_back(obj);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tencent_nanodetncnn_NanoDetNcnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu) {
    (void)env;
    (void)thiz;
    (void)modelid;
    (void)cpugpu;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    g_net.clear();
    g_net.opt.use_vulkan_compute = false;
    g_net.opt.num_threads = 2;
    g_net.opt.use_fp16_arithmetic = true;
    g_net.opt.use_packing_layout = true;

    const char* param = "nanodet-ELite0_320.param";
    const char* bin = "nanodet-ELite0_320.bin";

    int ret1 = g_net.load_param(mgr, param);
    int ret2 = g_net.load_model(mgr, bin);
    if (ret1 != 0 || ret2 != 0) {
        LOGE("load model failed param=%d bin=%d", ret1, ret2);
        g_loaded = false;
        return JNI_FALSE;
    }

    g_loaded = true;
    LOGI("model loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tencent_nanodetncnn_NanoDetNcnn_detectRGBA(JNIEnv* env, jobject thiz, jobject rgbaBuffer,
                                                    jint width, jint height, jint rowStride, jint targetSize) {
    (void)thiz;

    std::vector<float> empty;
    empty.push_back((float)std::max(1, (int)width));
    empty.push_back((float)std::max(1, (int)height));

    if (!g_loaded || rgbaBuffer == nullptr || width <= 0 || height <= 0 || rowStride <= 0) {
        jfloatArray arr = env->NewFloatArray((jsize)empty.size());
        env->SetFloatArrayRegion(arr, 0, (jsize)empty.size(), empty.data());
        return arr;
    }

    unsigned char* rgba = (unsigned char*)env->GetDirectBufferAddress(rgbaBuffer);
    if (!rgba) {
        jfloatArray arr = env->NewFloatArray((jsize)empty.size());
        env->SetFloatArrayRegion(arr, 0, (jsize)empty.size(), empty.data());
        return arr;
    }

    cv::Mat rgba_full(height, rowStride / 4, CV_8UC4, rgba);
    cv::Mat rgba_crop = rgba_full(cv::Rect(0, 0, width, height));
    cv::Mat rgb;
    cv::cvtColor(rgba_crop, rgb, cv::COLOR_RGBA2RGB);

    int input_size = targetSize > 0 ? targetSize : 320;
    if (input_size < 160) input_size = 320;

    int img_w = rgb.cols;
    int img_h = rgb.rows;
    float scale = std::min((float)input_size / img_w, (float)input_size / img_h);
    int new_w = (int)(img_w * scale);
    int new_h = (int)(img_h * scale);

    cv::Mat resized;
    cv::resize(rgb, resized, cv::Size(new_w, new_h));

    int wpad = input_size - new_w;
    int hpad = input_size - new_h;
    int left = wpad / 2;
    int right = wpad - left;
    int top = hpad / 2;
    int bottom = hpad - top;

    cv::Mat padded;
    cv::copyMakeBorder(resized, padded, top, bottom, left, right, cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));

    ncnn::Mat in = ncnn::Mat::from_pixels(padded.data, ncnn::Mat::PIXEL_RGB, padded.cols, padded.rows);
    const float mean_vals[3] = {103.53f, 116.28f, 123.675f};
    const float norm_vals[3] = {0.017429f, 0.017507f, 0.017125f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = g_net.create_extractor();
    ex.input("input.1", in);

    std::vector<Object> proposals;
    const int strides[3] = {8, 16, 32};
    const char* cls_names[3] = {"cls_pred_stride_8", "cls_pred_stride_16", "cls_pred_stride_32"};
    const char* dis_names[3] = {"dis_pred_stride_8", "dis_pred_stride_16", "dis_pred_stride_32"};

    for (int i = 0; i < 3; i++) {
        ncnn::Mat cls_pred;
        ncnn::Mat dis_pred;
        int retc = ex.extract(cls_names[i], cls_pred);
        int retd = ex.extract(dis_names[i], dis_pred);
        if (retc == 0 && retd == 0) {
            generate_proposals(cls_pred, dis_pred, strides[i], input_size, input_size, PROB_THRESHOLD, proposals);
        }
    }

    qsort_descent_inplace(proposals);
    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, NMS_THRESHOLD);

    std::vector<float> out;
    out.reserve(2 + picked.size() * 6);
    out.push_back((float)img_w);
    out.push_back((float)img_h);

    const int max_objects = 50;
    int count = 0;
    for (int idx : picked) {
        if (count >= max_objects) break;
        Object obj = proposals[idx];

        float x0 = (obj.rect.x - left) / scale;
        float y0 = (obj.rect.y - top) / scale;
        float x1 = (obj.rect.x + obj.rect.width - left) / scale;
        float y1 = (obj.rect.y + obj.rect.height - top) / scale;

        x0 = std::max(0.f, std::min(x0, (float)(img_w - 1)));
        y0 = std::max(0.f, std::min(y0, (float)(img_h - 1)));
        x1 = std::max(0.f, std::min(x1, (float)(img_w - 1)));
        y1 = std::max(0.f, std::min(y1, (float)(img_h - 1)));

        float bw = x1 - x0;
        float bh = y1 - y0;
        if (bw <= 2.f || bh <= 2.f) continue;

        out.push_back((float)obj.label);
        out.push_back(obj.prob);
        out.push_back(x0);
        out.push_back(y0);
        out.push_back(bw);
        out.push_back(bh);
        count++;
    }

    jfloatArray arr = env->NewFloatArray((jsize)out.size());
    env->SetFloatArrayRegion(arr, 0, (jsize)out.size(), out.data());
    return arr;
}
