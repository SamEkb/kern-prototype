// JNI-мост к ToupCam SDK для камеры Touptek E3ISPM (§3).
// Расширенная версия официального демо ToupTek (android/samples/demoandroid):
// добавлены полнокадровый снимок (Snap/PullStillImage) и ручные параметры
// (экспозиция/усиление/баланс белого/опции). Кадры отдаются прямо в Android Bitmap
// (ARGB_8888) через jnigraphics — из RGB24 (порядок байт на Android по умолчанию RGB,
// изображение top-down), альфа выставляется в 0xFF.
//
// Соответствие Java: класс com.droid.usbcam.CamLib (имена JNI-функций кодируют пакет).

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/bitmap.h>
#include "toupcam.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "JNI_CAM", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "JNI_CAM", __VA_ARGS__)

// Ограничения USB-нагрузки для обхода kernel panic (hardened usercopy в copy_urb_data_to_user
// на MTK/Android 15: SDK реапит URB с мусорным размером ~1.9 ГБ → BUG() → ребут).
// Меньше кадр / полоса / fps → меньше размер USB-передач. Значения подбираются экспериментально.
static const int PREVIEW_MAX_WIDTH = 1280;  // было 2048
static const int PREVIEW_BANDWIDTH = 25;    // TOUPCAM_OPTION_BANDWIDTH, [1-100]%
static const int PREVIEW_FRAMERATE = 8;     // TOUPCAM_OPTION_FRAMERATE, кадров/с (0 = без лимита)

static jobject   gObject = nullptr;
static JavaVM   *gLocalVm = nullptr;
static HToupcam  gHcam = nullptr;
static unsigned  gStillIndex = 0;

// Переиспользуемый буфер RGB24 (пул кадров сериализован на одном потоке событий).
static unsigned char *gRgbBuf = nullptr;
static int            gRgbBufSize = 0;

static bool ensureBuf(int size) {
    if (size <= 0) return false;
    if (gRgbBufSize < size) {
        unsigned char *p = (unsigned char *) realloc(gRgbBuf, size);
        if (!p) return false;
        gRgbBuf = p;
        gRgbBufSize = size;
    }
    return true;
}

// Событие SDK приходит на внутреннем потоке SDK → пробрасываем в Java CamLib.OnEvent(int).
static void ucamCallback(unsigned nEvent, void *pCallbackCtx) {
    if (!gObject || !gLocalVm) return;
    bool isAttached = false;
    JNIEnv *env = nullptr;
    if (gLocalVm->GetEnv((void **) &env, JNI_VERSION_1_2) < 0) {
        if (gLocalVm->AttachCurrentThread(&env, nullptr) < 0)
            return;
        isAttached = true;
    }
    jmethodID onEvent = env->GetMethodID(env->GetObjectClass(gObject), "OnEvent", "(I)V");
    if (onEvent != nullptr)
        env->CallVoidMethod(gObject, onEvent, nEvent);
    if (isAttached)
        gLocalVm->DetachCurrentThread();
}

// RGB24 (top-down, порядок RGB) → Bitmap ARGB_8888 (порядок в памяти R,G,B,A).
static void pullInto(JNIEnv *env, jobject bitmap, bool still) {
    if (!gHcam || bitmap == nullptr) return;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    const int w = (int) info.width;
    const int h = (int) info.height;
    const int srcStride = ((w * 24 + 31) & ~31) / 8; // строки RGB24 выровнены на 4 байта
    if (ensureBuf(srcStride * h)) {
        HRESULT hr = still
                     ? Toupcam_PullStillImageV2(gHcam, gRgbBuf, 24, nullptr)
                     : Toupcam_PullImageV2(gHcam, gRgbBuf, 24, nullptr);
        // Диагностика потока кадров (JNI_CAM): видно, доходят ли кадры и с каким результатом.
        if (!still) {
            static unsigned sFrameCount = 0;
            if (FAILED(hr)) {
                LOGE("pullImage: PullImageV2 FAILED hr=0x%08x (frame #%u)", (unsigned) hr, sFrameCount);
            } else if (sFrameCount == 0 || (sFrameCount % 30) == 0) {
                LOGI("pullImage: frame #%u ok %dx%d stride=%d", sFrameCount, w, h, srcStride);
            }
            ++sFrameCount;
        } else if (FAILED(hr)) {
            LOGE("pullStillImage: PullStillImageV2 FAILED hr=0x%08x", (unsigned) hr);
        }
        if (SUCCEEDED(hr)) {
            for (int y = 0; y < h; ++y) {
                const unsigned char *src = gRgbBuf + (size_t) y * srcStride;
                unsigned char *dst = (unsigned char *) pixels + (size_t) y * info.stride;
                for (int x = 0; x < w; ++x) {
                    dst[x * 4 + 0] = src[x * 3 + 0];
                    dst[x * 4 + 1] = src[x * 3 + 1];
                    dst[x * 4 + 2] = src[x * 3 + 2];
                    dst[x * 4 + 3] = 0xFF;
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

// FFC (§2.3): нормализация снимка делением на эталон неравномерности освещения.
// corrected_c = clamp( raw_c * mean_c / tmpl_c ), поканально; tmpl — уменьшенный размытый
// шаблон, сэмплируется nearest (он низкочастотный, поэтому этого достаточно). In-place по still.
static void flatFieldCorrect(JNIEnv *env, jobject still, jobject tmpl) {
    AndroidBitmapInfo si, ti;
    if (AndroidBitmap_getInfo(env, still, &si) < 0 || AndroidBitmap_getInfo(env, tmpl, &ti) < 0) return;
    if (si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ti.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (ti.width == 0 || ti.height == 0) return;

    void *tp = nullptr;
    if (AndroidBitmap_lockPixels(env, tmpl, &tp) < 0) return;
    const int tw = (int) ti.width, th = (int) ti.height;
    // Средние по каналам шаблона (для сохранения общей яркости).
    double sumR = 0, sumG = 0, sumB = 0;
    for (int y = 0; y < th; ++y) {
        const unsigned char *row = (const unsigned char *) tp + (size_t) y * ti.stride;
        for (int x = 0; x < tw; ++x) {
            sumR += row[x * 4 + 0]; sumG += row[x * 4 + 1]; sumB += row[x * 4 + 2];
        }
    }
    const double n = (double) tw * th;
    const double meanR = sumR / n, meanG = sumG / n, meanB = sumB / n;

    void *sp = nullptr;
    if (AndroidBitmap_lockPixels(env, still, &sp) < 0) { AndroidBitmap_unlockPixels(env, tmpl); return; }
    const int sw = (int) si.width, sh = (int) si.height;
    for (int y = 0; y < sh; ++y) {
        unsigned char *srow = (unsigned char *) sp + (size_t) y * si.stride;
        const int ty = (int) ((long long) y * th / sh);
        const unsigned char *trow = (const unsigned char *) tp + (size_t) ty * ti.stride;
        for (int x = 0; x < sw; ++x) {
            const int tx = (int) ((long long) x * tw / sw);
            const unsigned char *t = trow + tx * 4;
            unsigned char *s = srow + x * 4;
            const double gr = meanR / (t[0] > 0 ? t[0] : 1);
            const double gg = meanG / (t[1] > 0 ? t[1] : 1);
            const double gb = meanB / (t[2] > 0 ? t[2] : 1);
            int r = (int) (s[0] * gr + 0.5), g = (int) (s[1] * gg + 0.5), b = (int) (s[2] * gb + 0.5);
            s[0] = (unsigned char) (r < 0 ? 0 : r > 255 ? 255 : r);
            s[1] = (unsigned char) (g < 0 ? 0 : g > 255 ? 255 : g);
            s[2] = (unsigned char) (b < 0 ? 0 : b > 255 ? 255 : b);
            // альфу не трогаем
        }
    }
    AndroidBitmap_unlockPixels(env, still);
    AndroidBitmap_unlockPixels(env, tmpl);
}

extern "C" {

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_applyFlatField(JNIEnv *env, jobject obj, jobject still, jobject tmpl) {
    if (still != nullptr && tmpl != nullptr) flatFieldCorrect(env, still, tmpl);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_init(JNIEnv *env, jobject obj) {
    if (gObject) env->DeleteGlobalRef(gObject);
    gObject = env->NewGlobalRef(obj);
}

JNIEXPORT jstring JNICALL Java_com_droid_usbcam_CamLib_getModelName(JNIEnv *env, jobject obj, jint vendorId, jint productId) {
    const ToupcamModelV2 *pModel = Toupcam_get_Model((unsigned short) vendorId, (unsigned short) productId);
    if (pModel == nullptr)
        return nullptr;
    return env->NewStringUTF(pModel->name);
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_openDevice(JNIEnv *env, jobject obj, jint vendorId, jint productId, jint fd) {
    if (gHcam) {
        Toupcam_Close(gHcam);
        gHcam = nullptr;
    }
    char camId[128] = {0};
    snprintf(camId, sizeof(camId), "fd-%d-%04x-%04x", fd, vendorId, productId);
    gHcam = Toupcam_Open(camId);
    if (!gHcam) {
        LOGE("%s: Toupcam_Open failed", __FUNCTION__);
        return JNI_ERR;
    }

    // Формат кадра: RGB24 (на Android порядок байт RGB, изображение top-down — по умолчанию).
    Toupcam_put_Option(gHcam, TOUPCAM_OPTION_RGB, 0);

    // Выбор разрешений: снимок — максимальное; превью — крупнейшее с шириной <= 2048 (ради fps).
    int n = Toupcam_get_ResolutionNumber(gHcam);
    unsigned previewIndex = 0;
    long long previewArea = -1, stillArea = -1;
    gStillIndex = 0;
    for (int i = 0; i < n; ++i) {
        int rw = 0, rh = 0;
        if (SUCCEEDED(Toupcam_get_Resolution(gHcam, i, &rw, &rh))) {
            long long area = (long long) rw * rh;
            if (area > stillArea) { stillArea = area; gStillIndex = (unsigned) i; }
            if (rw <= PREVIEW_MAX_WIDTH && area > previewArea) { previewArea = area; previewIndex = (unsigned) i; }
        }
    }
    if (previewArea < 0) previewIndex = (n > 0) ? (unsigned) (n - 1) : 0; // фолбэк: минимальное

    Toupcam_put_eSize(gHcam, previewIndex);

    // Снижаем USB-нагрузку ДО старта потока (обход kernel panic в usbdevfs copy_urb_data_to_user).
    Toupcam_put_Option(gHcam, TOUPCAM_OPTION_BANDWIDTH, PREVIEW_BANDWIDTH);
    Toupcam_put_Option(gHcam, TOUPCAM_OPTION_FRAMERATE, PREVIEW_FRAMERATE);
    {
        int pw = 0, ph = 0;
        Toupcam_get_Resolution(gHcam, previewIndex, &pw, &ph);
        LOGI("openDevice: preview idx=%u %dx%d, bandwidth=%d%% framerate=%d",
             previewIndex, pw, ph, PREVIEW_BANDWIDTH, PREVIEW_FRAMERATE);
    }

    int ret = Toupcam_StartPullModeWithCallback(gHcam, ucamCallback, nullptr);
    if (!SUCCEEDED(ret)) {
        Toupcam_Close(gHcam);
        gHcam = nullptr;
        LOGE("%s: StartPullModeWithCallback failed ret=%d", __FUNCTION__, ret);
        return JNI_ERR;
    }
    return JNI_OK;
}

JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getPreviewSize(JNIEnv *env, jobject obj) {
    int size[2] = {0, 0};
    if (gHcam) Toupcam_get_Size(gHcam, size, size + 1);
    jintArray array = env->NewIntArray(2);
    env->SetIntArrayRegion(array, 0, 2, size);
    return array;
}

JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getStillSize(JNIEnv *env, jobject obj) {
    int size[2] = {0, 0};
    if (gHcam) Toupcam_get_StillResolution(gHcam, gStillIndex, size, size + 1);
    jintArray array = env->NewIntArray(2);
    env->SetIntArrayRegion(array, 0, 2, size);
    return array;
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_pullImage(JNIEnv *env, jobject obj, jobject bitmap) {
    pullInto(env, bitmap, false);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_pullStillImage(JNIEnv *env, jobject obj, jobject bitmap) {
    pullInto(env, bitmap, true);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_snap(JNIEnv *env, jobject obj) {
    if (gHcam) Toupcam_Snap(gHcam, gStillIndex);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setAutoExpoEnable(JNIEnv *env, jobject obj, jint mode) {
    if (gHcam) Toupcam_put_AutoExpoEnable(gHcam, mode);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setExpoTime(JNIEnv *env, jobject obj, jint microseconds) {
    if (gHcam) Toupcam_put_ExpoTime(gHcam, (unsigned) microseconds);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setExpoAGain(JNIEnv *env, jobject obj, jint percent) {
    if (gHcam) Toupcam_put_ExpoAGain(gHcam, (unsigned short) percent);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setWhiteBalanceGain(JNIEnv *env, jobject obj, jint r, jint g, jint b) {
    if (gHcam) {
        int aGain[3] = {r, g, b};
        Toupcam_put_WhiteBalanceGain(gHcam, aGain);
    }
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setGamma(JNIEnv *env, jobject obj, jint gamma) {
    if (gHcam) Toupcam_put_Gamma(gHcam, gamma);   // проценты, 20..180 (100 = 1.0), §3.3
}

// --- Авто-процедуры (§2.1/§2.2): AE с приоритетом выдержки + one-push AWB ---

// Ограничить авто-AE: максимум выдержки (мкс) и максимум усиления (%). §2.1: низкий
// потолок усиления → AE предпочитает удлинять выдержку, а не поднимать gain (меньше зерна).
JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setMaxAutoExpoTimeAGain(JNIEnv *env, jobject obj, jint maxTimeUs, jint maxGainPercent) {
    if (gHcam) Toupcam_put_MaxAutoExpoTimeAGain(gHcam, (unsigned) maxTimeUs, (unsigned short) maxGainPercent);
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_getExpoTime(JNIEnv *env, jobject obj) {
    unsigned t = 0;
    if (gHcam) Toupcam_get_ExpoTime(gHcam, &t);
    return (jint) t;   // микросекунды
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_getExpoAGain(JNIEnv *env, jobject obj) {
    unsigned short g = 100;
    if (gHcam) Toupcam_get_ExpoAGain(gHcam, &g);
    return (jint) g;   // проценты (100 = 1.0x)
}

// Однократный автобаланс белого (RGB-gain модель). Колбэк не нужен — SDK применяет
// усиления сам; результат читаем через getWhiteBalanceGain. §2.2.
JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_awbOnce(JNIEnv *env, jobject obj) {
    if (gHcam) Toupcam_AwbInit(gHcam, nullptr, nullptr);
}

JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getWhiteBalanceGain(JNIEnv *env, jobject obj) {
    int g[3] = {0, 0, 0};
    if (gHcam) Toupcam_get_WhiteBalanceGain(gHcam, g);   // знаковые R,G,B (-127..127)
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, g);
    return arr;
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_setOption(JNIEnv *env, jobject obj, jint option, jint value) {
    if (gHcam) Toupcam_put_Option(gHcam, (unsigned) option, value);
}

JNIEXPORT jboolean JNICALL Java_com_droid_usbcam_CamLib_isAlive(JNIEnv *env, jobject obj) {
    return (gHcam != nullptr);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_releaseCamera(JNIEnv *env, jobject obj) {
    if (gHcam) {
        Toupcam_Close(gHcam);
        gHcam = nullptr;
    }
}

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    gLocalVm = vm;
    return JNI_VERSION_1_2;
}

void JNI_OnUnload(JavaVM *vm, void *reserved) {
    if (!gObject) return;
    bool isAttached = false;
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_2) < 0) {
        if (vm->AttachCurrentThread(&env, nullptr) < 0)
            return;
        isAttached = true;
    }
    env->DeleteGlobalRef(gObject);
    gObject = nullptr;
    if (isAttached)
        vm->DetachCurrentThread();
}

} // extern "C"
