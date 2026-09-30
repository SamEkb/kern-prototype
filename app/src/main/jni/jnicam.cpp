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
#include <algorithm>
#include <string>
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
// R17: снимок выбирается по списку STILL-разрешений; false — модель снимок не поддерживает.
static bool      gSnapSupported = false;
// Ёмкость буфера RGB24 под наибольший кадр своего вида: любой кадр SDK в неё помещается.
// Достоверна, только если соответствующий список разрешений перечислен ПОЛНОСТЬЮ (флаги ниже);
// иначе чтение кадров этого вида запрещено — проверка FrameInfo после записи от переполнения не спасает.
static int       gMaxStillBytes = 0;
static int       gMaxPreviewBytes = 0;
static bool      gPreviewCapacityOk = false;
static bool      gStillCapacityOk = false;

static int rgb24Bytes(int w, int h) { return ((w * 24 + 31) & ~31) / 8 * h; }

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
// R09: true — только если в bitmap действительно скопирован НОВЫЙ кадр SDK.
// R17: буфер — под наибольший кадр своего вида; фактический размер кадра (FrameInfo) должен
// совпасть с bitmap, иначе кадр отвергается (без копирования).
static bool pullInto(JNIEnv *env, jobject bitmap, bool still) {
    if (!gHcam || bitmap == nullptr) return false;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;
    bool ok = false;

    const int w = (int) info.width;
    const int h = (int) info.height;
    const int srcStride = ((w * 24 + 31) & ~31) / 8; // строки RGB24 выровнены на 4 байта
    if (!(still ? gStillCapacityOk : gPreviewCapacityOk)) {
        LOGE("%s: ёмкость буфера не установлена достоверно (список разрешений неполон) — чтение запрещено",
             still ? "pullStillImage" : "pullImage");
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }
    const int capacity = std::max(srcStride * h, still ? gMaxStillBytes : gMaxPreviewBytes);
    if (ensureBuf(capacity)) {
        ToupcamFrameInfoV2 fi;
        memset(&fi, 0, sizeof(fi));
        HRESULT hr = still
                     ? Toupcam_PullStillImageV2(gHcam, gRgbBuf, 24, &fi)
                     : Toupcam_PullImageV2(gHcam, gRgbBuf, 24, &fi);
        if (SUCCEEDED(hr) && (fi.width != (unsigned) w || fi.height != (unsigned) h)) {
            LOGE("%s: размер кадра %ux%u не совпадает с буфером %dx%d — кадр отвергнут",
                 still ? "pullStillImage" : "pullImage", fi.width, fi.height, w, h);
            hr = (HRESULT) 0x8000ffff; // E_UNEXPECTED (в toupcam.h объявлен не для всех платформ)
        }
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
            ok = true;
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
    } else if (still) {
        LOGE("pullStillImage: нет памяти под буфер %d байт", srcStride * h);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

// FFC (§2.3): нормализация снимка делением на эталон неравномерности освещения.
// corrected_c = clamp( raw_c * mean_c / tmpl_c ), поканально; tmpl — уменьшенный размытый
// шаблон, сэмплируется nearest (он низкочастотный, поэтому этого достаточно). In-place по still.
// Возвращает false, если коррекция НЕ выполнена (снимок при этом не изменён).
static bool flatFieldCorrect(JNIEnv *env, jobject still, jobject tmpl) {
    AndroidBitmapInfo si, ti;
    if (AndroidBitmap_getInfo(env, still, &si) < 0 || AndroidBitmap_getInfo(env, tmpl, &ti) < 0) return false;
    if (si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || ti.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (ti.width == 0 || ti.height == 0 || si.width == 0 || si.height == 0) return false;

    void *tp = nullptr;
    if (AndroidBitmap_lockPixels(env, tmpl, &tp) < 0) return false;
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
    if (AndroidBitmap_lockPixels(env, still, &sp) < 0) { AndroidBitmap_unlockPixels(env, tmpl); return false; }
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
    return true;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_droid_usbcam_CamLib_applyFlatField(JNIEnv *env, jobject obj, jobject still, jobject tmpl) {
    if (still == nullptr || tmpl == nullptr) return JNI_FALSE;
    return flatFieldCorrect(env, still, tmpl) ? JNI_TRUE : JNI_FALSE;
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

    // Превью — крупнейшее из списка ПРЕВЬЮ с шириной <= PREVIEW_MAX_WIDTH (ради fps).
    int n = Toupcam_get_ResolutionNumber(gHcam);
    unsigned previewIndex = 0;
    long long previewArea = -1;
    gMaxPreviewBytes = 0;
    gPreviewCapacityOk = n > 0;
    for (int i = 0; i < n; ++i) {
        int rw = 0, rh = 0;
        HRESULT hr = Toupcam_get_Resolution(gHcam, i, &rw, &rh);
        LOGI("openDevice: preview[%d] %dx%d hr=0x%08x", i, rw, rh, (unsigned) hr);
        if (SUCCEEDED(hr) && rw > 0 && rh > 0) {
            long long area = (long long) rw * rh;
            gMaxPreviewBytes = std::max(gMaxPreviewBytes, rgb24Bytes(rw, rh));
            if (rw <= PREVIEW_MAX_WIDTH && area > previewArea) { previewArea = area; previewIndex = (unsigned) i; }
        } else {
            gPreviewCapacityOk = false;
        }
    }
    if (!gPreviewCapacityOk) {
        // Без полного списка нельзя гарантировать, что кадр превью поместится в буфер.
        LOGE("openDevice: список разрешений превью не перечислен полностью (n=%d) — открытие прервано", n);
        Toupcam_Close(gHcam);
        gHcam = nullptr;
        return JNI_ERR;
    }
    if (previewArea < 0) previewIndex = (unsigned) (n - 1); // фолбэк: минимальное

    // R17: снимок — крупнейшее из списка STILL (Toupcam_Snap принимает индекс этого списка).
    int ns = Toupcam_get_StillResolutionNumber(gHcam);
    long long stillArea = -1;
    gStillIndex = 0;
    gMaxStillBytes = 0;
    gStillCapacityOk = ns > 0;
    for (int i = 0; i < ns; ++i) {
        int rw = 0, rh = 0;
        HRESULT hr = Toupcam_get_StillResolution(gHcam, (unsigned) i, &rw, &rh);
        LOGI("openDevice: still[%d] %dx%d hr=0x%08x", i, rw, rh, (unsigned) hr);
        if (SUCCEEDED(hr) && rw > 0 && rh > 0) {
            long long area = (long long) rw * rh;
            gMaxStillBytes = std::max(gMaxStillBytes, rgb24Bytes(rw, rh));
            if (area > stillArea) { stillArea = area; gStillIndex = (unsigned) i; }
        } else {
            gStillCapacityOk = false;
        }
    }
    // Снимок — только при полностью перечисленном списке still (иначе ёмкость буфера не гарантирована).
    gSnapSupported = gStillCapacityOk && stillArea > 0;
    if (!gSnapSupported) {
        LOGE("openDevice: список still-разрешений пуст или неполон (n=%d) — снимок не поддерживается", ns);
    }

    HRESULT hrSize = Toupcam_put_eSize(gHcam, previewIndex);
    if (FAILED(hrSize)) LOGE("openDevice: put_eSize(%u) hr=0x%08x", previewIndex, (unsigned) hrSize);

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

// Размеры: {0,0} — определить не удалось (вызывающий обязан считать это ошибкой).
JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getPreviewSize(JNIEnv *env, jobject obj) {
    int size[2] = {0, 0};
    if (gHcam && FAILED(Toupcam_get_Size(gHcam, size, size + 1))) { size[0] = 0; size[1] = 0; }
    jintArray array = env->NewIntArray(2);
    env->SetIntArrayRegion(array, 0, 2, size);
    return array;
}

JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getStillSize(JNIEnv *env, jobject obj) {
    int size[2] = {0, 0};
    if (gHcam && gSnapSupported && FAILED(Toupcam_get_StillResolution(gHcam, gStillIndex, size, size + 1))) {
        size[0] = 0; size[1] = 0;
    }
    jintArray array = env->NewIntArray(2);
    env->SetIntArrayRegion(array, 0, 2, size);
    return array;
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_pullImage(JNIEnv *env, jobject obj, jobject bitmap) {
    pullInto(env, bitmap, false);
}

JNIEXPORT jboolean JNICALL Java_com_droid_usbcam_CamLib_pullStillImage(JNIEnv *env, jobject obj, jobject bitmap) {
    return pullInto(env, bitmap, true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_droid_usbcam_CamLib_snap(JNIEnv *env, jobject obj) {
    if (!gHcam) return JNI_FALSE;
    if (!gSnapSupported) {
        LOGE("snap: модель не сообщила still-разрешений — снимок не поддерживается");
        return JNI_FALSE;
    }
    HRESULT hr = Toupcam_Snap(gHcam, gStillIndex);
    if (FAILED(hr)) LOGE("snap: Toupcam_Snap FAILED hr=0x%08x", (unsigned) hr);
    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

// R14: сеттеры возвращают HRESULT (0 — S_OK; E_NOTIMPL и т.п. — параметр не принят).
#define NO_CAMERA ((jint) 0x80004005) /* E_FAIL */

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setAutoExpoEnable(JNIEnv *env, jobject obj, jint mode) {
    return gHcam ? (jint) Toupcam_put_AutoExpoEnable(gHcam, mode) : NO_CAMERA;
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setExpoTime(JNIEnv *env, jobject obj, jint microseconds) {
    return gHcam ? (jint) Toupcam_put_ExpoTime(gHcam, (unsigned) microseconds) : NO_CAMERA;
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setExpoAGain(JNIEnv *env, jobject obj, jint percent) {
    return gHcam ? (jint) Toupcam_put_ExpoAGain(gHcam, (unsigned short) percent) : NO_CAMERA;
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setWhiteBalanceGain(JNIEnv *env, jobject obj, jint r, jint g, jint b) {
    if (!gHcam) return NO_CAMERA;
    int aGain[3] = {r, g, b};
    return (jint) Toupcam_put_WhiteBalanceGain(gHcam, aGain);
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setGamma(JNIEnv *env, jobject obj, jint gamma) {
    return gHcam ? (jint) Toupcam_put_Gamma(gHcam, gamma) : NO_CAMERA;   // проценты, 20..180 (100 = 1.0), §3.3
}

// --- Авто-процедуры (§2.1/§2.2): AE с приоритетом выдержки + one-push AWB ---

// Ограничить авто-AE: максимум выдержки (мкс) и максимум усиления (%). §2.1: низкий
// потолок усиления → AE предпочитает удлинять выдержку, а не поднимать gain (меньше зерна).
JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setMaxAutoExpoTimeAGain(JNIEnv *env, jobject obj, jint maxTimeUs, jint maxGainPercent) {
    return gHcam ? (jint) Toupcam_put_MaxAutoExpoTimeAGain(gHcam, (unsigned) maxTimeUs, (unsigned short) maxGainPercent) : NO_CAMERA;
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
JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_awbOnce(JNIEnv *env, jobject obj) {
    return gHcam ? (jint) Toupcam_AwbInit(gHcam, nullptr, nullptr) : NO_CAMERA;
}

JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_getWhiteBalanceGain(JNIEnv *env, jobject obj) {
    int g[3] = {0, 0, 0};
    if (gHcam) Toupcam_get_WhiteBalanceGain(gHcam, g);   // знаковые R,G,B (-127..127)
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, g);
    return arr;
}

JNIEXPORT jint JNICALL Java_com_droid_usbcam_CamLib_setOption(JNIEnv *env, jobject obj, jint option, jint value) {
    return gHcam ? (jint) Toupcam_put_Option(gHcam, (unsigned) option, value) : NO_CAMERA;
}

// R14: фактическое состояние параметров (readback) — пары [HRESULT, значение...]:
//  0 hr,expoUs  2 hr,realExpoUs  4 hr,gain%  6 hr,gamma  8 hr,wbR,wbG,wbB  12 hr,aeMode
//  14 hr,expoMin,expoMax,expoDef  18 hr,gainMin,gainMax,gainDef  22 hr,optWbGain  24 hr,optIsp
JNIEXPORT jintArray JNICALL Java_com_droid_usbcam_CamLib_readState(JNIEnv *env, jobject obj) {
    jint v[26];
    for (int i = 0; i < 26; ++i) v[i] = 0;
    if (gHcam) {
        unsigned t = 0, rt = 0, tmin = 0, tmax = 0, tdef = 0;
        unsigned short g = 0, gmin = 0, gmax = 0, gdef = 0;
        int gamma = 0, ae = 0, wb[3] = {0, 0, 0}, optWb = 0, optIsp = 0;
        v[0] = (jint) Toupcam_get_ExpoTime(gHcam, &t);               v[1] = (jint) t;
        v[2] = (jint) Toupcam_get_RealExpoTime(gHcam, &rt);          v[3] = (jint) rt;
        v[4] = (jint) Toupcam_get_ExpoAGain(gHcam, &g);              v[5] = (jint) g;
        v[6] = (jint) Toupcam_get_Gamma(gHcam, &gamma);              v[7] = gamma;
        v[8] = (jint) Toupcam_get_WhiteBalanceGain(gHcam, wb);       v[9] = wb[0]; v[10] = wb[1]; v[11] = wb[2];
        v[12] = (jint) Toupcam_get_AutoExpoEnable(gHcam, &ae);       v[13] = ae;
        v[14] = (jint) Toupcam_get_ExpTimeRange(gHcam, &tmin, &tmax, &tdef);
        v[15] = (jint) tmin; v[16] = (jint) tmax; v[17] = (jint) tdef;
        v[18] = (jint) Toupcam_get_ExpoAGainRange(gHcam, &gmin, &gmax, &gdef);
        v[19] = gmin; v[20] = gmax; v[21] = gdef;
        v[22] = (jint) Toupcam_get_Option(gHcam, TOUPCAM_OPTION_WBGAIN, &optWb); v[23] = optWb;
        v[24] = (jint) Toupcam_get_Option(gHcam, TOUPCAM_OPTION_ISP, &optIsp);   v[25] = optIsp;
    } else {
        for (int i = 0; i < 26; i += 2) v[i] = NO_CAMERA;
    }
    jintArray arr = env->NewIntArray(26);
    env->SetIntArrayRegion(arr, 0, 26, v);
    return arr;
}

// R14/R17: сведения о модели и прошивке (для журнала диагностики), строки «ключ=значение».
JNIEXPORT jstring JNICALL Java_com_droid_usbcam_CamLib_describe(JNIEnv *env, jobject obj) {
    std::string out;
    char line[160];
    snprintf(line, sizeof(line), "sdk=%s\n", Toupcam_Version()); out += line;
    if (gHcam) {
        const ToupcamModelV2 *m = Toupcam_query_Model(gHcam);
        if (m) {
            snprintf(line, sizeof(line), "model=%s\nflags=0x%016llx\npreviewCount=%u\nstillCount=%u\n",
                     m->name, (unsigned long long) m->flag, m->preview, m->still);
            out += line;
        }
        char fw[16] = {0}, hw[16] = {0};
        if (SUCCEEDED(Toupcam_get_FwVersion(gHcam, fw))) { snprintf(line, sizeof(line), "fw=%s\n", fw); out += line; }
        if (SUCCEEDED(Toupcam_get_HwVersion(gHcam, hw))) { snprintf(line, sizeof(line), "hw=%s\n", hw); out += line; }
        snprintf(line, sizeof(line), "stillIndex=%u\nsnapSupported=%d\n", gStillIndex, gSnapSupported ? 1 : 0); out += line;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_droid_usbcam_CamLib_isAlive(JNIEnv *env, jobject obj) {
    return (gHcam != nullptr);
}

JNIEXPORT void JNICALL Java_com_droid_usbcam_CamLib_releaseCamera(JNIEnv *env, jobject obj) {
    gSnapSupported = false;
    gPreviewCapacityOk = false;
    gStillCapacityOk = false;
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
