#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "net.h"
#include "cpu.h"

#define TAG "AICAM-NanoDet"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct RectF {
    float x;
    float y;
    float width;
    float height;

    float area() const {
        return std::max(0.f, width) * std::max(0.f, height);
    }
};

struct Object {
    RectF rect;
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
    float ax0 = a.rect.x;
    float ay0 = a.rect.y;
    float ax1 = a.rect.x + a.rect.width;
    float ay1 = a.rect.y + a.rect.height;

    float bx0 = b.rect.x;
    float by0 = b.rect.y;
    float bx1 = b.rect.x + b.rect.width;
    float by1 = b.rect.y + b.rect.height;

    float x0 = std::max(ax0, bx0);
    float y0 = std::max(ay0, by0);
    float x1 = std::min(ax1, bx1);
    float y1 = std::min(ay1, by1);

    return std::max(0.f, x1 - x0) * std::max(0.f, y1 - y0);
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
                               int in_w,
                               int in_h,
                               float prob_threshold,
                               std::vector<Object>& objects) {
    const int num_grid_x = in_w / stride;
    const int num_grid_y = in_h / stride;
    const int num_points = num_grid_x * num_grid_y;

    if (cls_pred.dims != 2 || dis_pred.dims != 2) {
        return;
    }

    const int cls_w = cls_pred.w;
    const int cls_h = cls_pred.h;
    const int dis_w = dis_pred.w;
    const int dis_h = dis_pred.h;

    if (cls_h < num_points || dis_h < num_points || cls_w < NUM_CLASS || dis_w < 4) {
        return;
    }

    const int reg_max_1 = dis_w / 4;
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

static jfloatArray make_empty_result(JNIEnv* env, int width, int height) {
    float data[2] = {(float)std::max(1, width), (float)std::max(1, height)};
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, data);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tencent_nanodetncnn_NanoDetNcnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu) {
    (void)thiz;
    (void)modelid;
    (void)cpugpu;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    g_net.clear();
    ncnn::set_cpu_powersave(2);
    ncnn::set_omp_num_threads(2);

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

    if (!g_loaded || rgbaBuffer == nullptr || width <= 0 || height <= 0 || rowStride <= 0) {
        return make_empty_result(env, width, height);
    }

    unsigned char* rgba = (unsigned char*)env->GetDirectBufferAddress(rgbaBuffer);
    if (!rgba) {
        return make_empty_result(env, width, height);
    }

    int input_size = targetSize > 0 ? targetSize : 320;
    if (input_size < 160) input_size = 320;

    // ImageReader 的 rowStride 通常大于 width*4，ncnn 不接受带 stride 的输入，先复制成连续 RGBA。
    std::vector<unsigned char> rgba_contiguous((size_t)width * (size_t)height * 4u);
    for (int y = 0; y < height; y++) {
        const unsigned char* src = rgba + (size_t)y * (size_t)rowStride;
        unsigned char* dst = rgba_contiguous.data() + (size_t)y * (size_t)width * 4u;
        std::memcpy(dst, src, (size_t)width * 4u);
    }

    // 为了彻底去掉 OpenCV 依赖，这里直接将画面拉伸到 320x320。
    // 检测框再用 scaleX/scaleY 映射回原始屏幕坐标。
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
            rgba_contiguous.data(),
            ncnn::Mat::PIXEL_RGBA2RGB,
            width,
            height,
            input_size,
            input_size);

    const float mean_vals[3] = {103.53f, 116.28f, 123.675f};
    const float norm_vals[3] = {0.017429f, 0.017507f, 0.017125f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = g_net.create_extractor();
    if (ex.input("input.1", in) != 0) {
        return make_empty_result(env, width, height);
    }

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
    out.push_back((float)width);
    out.push_back((float)height);

    const float scale_x = (float)width / (float)input_size;
    const float scale_y = (float)height / (float)input_size;

    const int max_objects = 50;
    int count = 0;
    for (int idx : picked) {
        if (count >= max_objects) break;
        Object obj = proposals[idx];

        float x0 = obj.rect.x * scale_x;
        float y0 = obj.rect.y * scale_y;
        float x1 = (obj.rect.x + obj.rect.width) * scale_x;
        float y1 = (obj.rect.y + obj.rect.height) * scale_y;

        x0 = std::max(0.f, std::min(x0, (float)(width - 1)));
        y0 = std::max(0.f, std::min(y0, (float)(height - 1)));
        x1 = std::max(0.f, std::min(x1, (float)(width - 1)));
        y1 = std::max(0.f, std::min(y1, (float)(height - 1)));

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
