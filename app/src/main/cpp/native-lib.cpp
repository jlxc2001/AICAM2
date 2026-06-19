#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cfloat>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include "net.h"
#include "mat.h"

#define LOG_TAG "VehicleNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Object {
    float x;
    float y;
    float w;
    float h;
    int label;
    float prob;
};

static ncnn::Net g_net;
static std::mutex g_mutex;
static bool g_loaded = false;
static int g_target_size = 320;
static float g_prob_threshold = 0.25f;
static float g_nms_threshold = 0.45f;
static int g_max_results = 40;

static inline float sigmoid(float x) {
    return 1.f / (1.f + std::exp(-x));
}

static const char* coco_label_name(int label) {
    static const char* names[] = {
            "PERSON", "BICYCLE", "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK",
            "BOAT", "TRAFFIC LIGHT", "FIRE HYDRANT", "STOP SIGN", "PARKING METER", "BENCH",
            "BIRD", "CAT", "DOG", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA",
            "GIRAFFE", "BACKPACK", "UMBRELLA", "HANDBAG", "TIE", "SUITCASE", "FRISBEE",
            "SKIS", "SNOWBOARD", "SPORTS BALL", "KITE", "BASEBALL BAT", "BASEBALL GLOVE",
            "SKATEBOARD", "SURFBOARD", "TENNIS RACKET", "BOTTLE", "WINE GLASS", "CUP",
            "FORK", "KNIFE", "SPOON", "BOWL", "BANANA", "APPLE", "SANDWICH", "ORANGE",
            "BROCCOLI", "CARROT", "HOT DOG", "PIZZA", "DONUT", "CAKE", "CHAIR", "COUCH",
            "POTTED PLANT", "BED", "DINING TABLE", "TOILET", "TV", "LAPTOP", "MOUSE",
            "REMOTE", "KEYBOARD", "CELL PHONE", "MICROWAVE", "OVEN", "TOASTER", "SINK",
            "REFRIGERATOR", "BOOK", "CLOCK", "VASE", "SCISSORS", "TEDDY BEAR", "HAIR DRIER",
            "TOOTHBRUSH"
    };
    if (label >= 0 && label < (int)(sizeof(names) / sizeof(names[0]))) return names[label];
    return "TARGET";
}

static float intersection_area(const Object& a, const Object& b) {
    float x0 = std::max(a.x, b.x);
    float y0 = std::max(a.y, b.y);
    float x1 = std::min(a.x + a.w, b.x + b.w);
    float y1 = std::min(a.y + a.h, b.y + b.h);
    return std::max(0.f, x1 - x0) * std::max(0.f, y1 - y0);
}

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold) {
    picked.clear();
    const int n = (int)objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++) areas[i] = objects[i].w * objects[i].h;

    for (int i = 0; i < n; i++) {
        const Object& a = objects[i];
        bool keep = true;
        for (int j : picked) {
            const Object& b = objects[j];
            if (a.label != b.label) continue;
            float inter = intersection_area(a, b);
            float uni = areas[i] + areas[j] - inter;
            if (uni > 0.f && inter / uni > nms_threshold) {
                keep = false;
                break;
            }
        }
        if (keep) picked.push_back(i);
    }
}

static float dfl_expectation(const float* p) {
    float maxv = -FLT_MAX;
    for (int i = 0; i < 16; i++) maxv = std::max(maxv, p[i]);
    float sum = 0.f;
    float exps[16];
    for (int i = 0; i < 16; i++) {
        exps[i] = std::exp(p[i] - maxv);
        sum += exps[i];
    }
    float dis = 0.f;
    for (int i = 0; i < 16; i++) dis += i * exps[i] / sum;
    return dis;
}

static void generate_proposals(const ncnn::Mat& pred, const std::vector<int>& strides,
                               int input_w, int input_h, float prob_threshold,
                               std::vector<Object>& objects) {
    int row_offset = 0;
    const int reg_max = 16;
    const int reg_count = reg_max * 4;
    const int num_class = pred.w - reg_count;

    for (int stride : strides) {
        const int num_grid_x = input_w / stride;
        const int num_grid_y = input_h / stride;
        const int num_grid = num_grid_x * num_grid_y;

        for (int y = 0; y < num_grid_y; y++) {
            for (int x = 0; x < num_grid_x; x++) {
                const float* row = pred.row(row_offset + y * num_grid_x + x);
                int label = -1;
                float score_raw = -FLT_MAX;
                const float* score_ptr = row + reg_count;
                for (int k = 0; k < num_class; k++) {
                    if (score_ptr[k] > score_raw) {
                        score_raw = score_ptr[k];
                        label = k;
                    }
                }
                float score = sigmoid(score_raw);
                if (score < prob_threshold) continue;

                float l = dfl_expectation(row + 0 * reg_max) * stride;
                float t = dfl_expectation(row + 1 * reg_max) * stride;
                float r = dfl_expectation(row + 2 * reg_max) * stride;
                float b = dfl_expectation(row + 3 * reg_max) * stride;

                float cx = (x + 0.5f) * stride;
                float cy = (y + 0.5f) * stride;
                Object obj;
                obj.x = cx - l;
                obj.y = cy - t;
                obj.w = l + r;
                obj.h = t + b;
                obj.label = label;
                obj.prob = score;
                objects.push_back(obj);
            }
        }
        row_offset += num_grid;
    }
}

static int detect_rgba(const unsigned char* rgba, int img_w, int img_h, std::vector<Object>& objects) {
    objects.clear();

    const int target_size = g_target_size;
    const int max_stride = 32;
    const float prob_threshold = g_prob_threshold;
    const float nms_threshold = g_nms_threshold;
    std::vector<int> strides = {8, 16, 32};

    int w = img_w;
    int h = img_h;
    float scale = 1.f;
    if (w > h) {
        scale = (float)target_size / (float)w;
        w = target_size;
        h = (int)(h * scale);
    } else {
        scale = (float)target_size / (float)h;
        h = target_size;
        w = (int)(w * scale);
    }

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2RGB, img_w, img_h, w, h);

    int wpad = (w + max_stride - 1) / max_stride * max_stride - w;
    int hpad = (h + max_stride - 1) / max_stride * max_stride - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad,
                           hpad / 2, hpad - hpad / 2,
                           wpad / 2, wpad - wpad / 2,
                           ncnn::BORDER_CONSTANT, 114.f);

    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in_pad.substract_mean_normalize(nullptr, norm_vals);

    ncnn::Extractor ex = g_net.create_extractor();
    ex.input("in0", in_pad);
    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0 || out.empty()) {
        LOGE("extract failed ret=%d", ret);
        return -1;
    }

    std::vector<Object> proposals;
    generate_proposals(out, strides, in_pad.w, in_pad.h, prob_threshold, proposals);
    std::sort(proposals.begin(), proposals.end(), [](const Object& a, const Object& b) {
        return a.prob > b.prob;
    });

    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, nms_threshold);

    objects.reserve(picked.size());
    for (int idx : picked) {
        Object obj = proposals[idx];
        float x0 = (obj.x - (wpad / 2.f)) / scale;
        float y0 = (obj.y - (hpad / 2.f)) / scale;
        float x1 = (obj.x + obj.w - (wpad / 2.f)) / scale;
        float y1 = (obj.y + obj.h - (hpad / 2.f)) / scale;

        x0 = std::max(0.f, std::min(x0, (float)(img_w - 1)));
        y0 = std::max(0.f, std::min(y0, (float)(img_h - 1)));
        x1 = std::max(0.f, std::min(x1, (float)(img_w - 1)));
        y1 = std::max(0.f, std::min(y1, (float)(img_h - 1)));

        obj.x = x0;
        obj.y = y0;
        obj.w = std::max(0.f, x1 - x0);
        obj.h = std::max(0.f, y1 - y0);
        if (obj.w > 1 && obj.h > 1) objects.push_back(obj);
    }

    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        return a.w * a.h > b.w * b.h;
    });
    if (objects.size() > (size_t)g_max_results) objects.resize((size_t)g_max_results);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jlxc_vehicleinfoncnn_VehicleDetector_setOptions(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jfloat probThreshold, jfloat nmsThreshold, jint maxResults) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_prob_threshold = std::max(0.05f, std::min(0.90f, (float)probThreshold));
    g_nms_threshold = std::max(0.10f, std::min(0.90f, (float)nmsThreshold));
    g_max_results = std::max(1, std::min(80, (int)maxResults));
    LOGI("setOptions prob=%.2f nms=%.2f max=%d", g_prob_threshold, g_nms_threshold, g_max_results);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jlxc_vehicleinfoncnn_VehicleDetector_init(JNIEnv* env, jobject /*thiz*/, jobject assetManager,
                                                   jboolean useGpu, jint targetSize) {
    std::lock_guard<std::mutex> lock(g_mutex);
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    g_net.clear();
    g_net.opt = ncnn::Option();
    g_net.opt.num_threads = 4;
    g_net.opt.use_packing_layout = true;
#if NCNN_VULKAN
    g_net.opt.use_vulkan_compute = (useGpu == JNI_TRUE);
#else
    (void)useGpu;
#endif
    g_target_size = targetSize > 0 ? targetSize : 320;

    int p = g_net.load_param(mgr, "yolov8n.ncnn.param");
    int m = g_net.load_model(mgr, "yolov8n.ncnn.bin");
    g_loaded = (p == 0 && m == 0);
    LOGI("model load param=%d model=%d loaded=%d target=%d", p, m, (int)g_loaded, g_target_size);
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_jlxc_vehicleinfoncnn_VehicleDetector_detect(JNIEnv* env, jobject /*thiz*/, jobject bitmap) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_loaded || bitmap == nullptr) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("unsupported bitmap format %d", info.format);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == nullptr) return nullptr;
    std::vector<unsigned char> rgba(info.width * info.height * 4);
    const unsigned char* src = static_cast<const unsigned char*>(pixels);
    for (uint32_t row = 0; row < info.height; row++) {
        memcpy(rgba.data() + row * info.width * 4, src + row * info.stride, info.width * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    std::vector<Object> objects;
    if (detect_rgba(rgba.data(), (int)info.width, (int)info.height, objects) != 0) {
        return nullptr;
    }

    jclass cls = env->FindClass("com/jlxc/vehicleinfoncnn/Detection");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;FFFFFF)V");
    if (!ctor) return nullptr;

    jobjectArray arr = env->NewObjectArray((jsize)objects.size(), cls, nullptr);
    for (size_t i = 0; i < objects.size(); i++) {
        const Object& o = objects[i];
        float area_ratio = (o.w * o.h) / std::max(1.f, (float)(info.width * info.height));
        jstring label = env->NewStringUTF(coco_label_name(o.label));
        jobject det = env->NewObject(cls, ctor, (jint)o.label, label,
                                     (jfloat)o.x, (jfloat)o.y, (jfloat)o.w, (jfloat)o.h,
                                     (jfloat)o.prob, (jfloat)area_ratio);
        env->SetObjectArrayElement(arr, (jsize)i, det);
        env->DeleteLocalRef(label);
        env->DeleteLocalRef(det);
    }
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jlxc_vehicleinfoncnn_VehicleDetector_release(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_net.clear();
    g_loaded = false;
}
