package com.droid.usbcam;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;

/**
 * JNI-обёртка ToupCam SDK для камеры Touptek (§3). Пакет com.droid.usbcam сохранён,
 * потому что имена JNI-функций в jnicam.cpp кодируют пакет+класс. Расширена относительно
 * официального демо ToupTek: добавлены снимок (snap/pullStillImage), размер снимка и
 * ручные параметры (экспозиция/усиление/баланс белого/опции).
 *
 * Нативная библиотека грузится статически: libtoupcam.so (C API) + libjnicam.so (мост).
 * Событие камеры приходит из натива в {@link #OnEvent(int)} и пересылается в Handler.
 */
public class CamLib {
    public static final int MSG_EVENT = 100;

    private static CamLib sInstance;
    private static Handler localHandler = null;

    static {
        System.loadLibrary("toupcam");
        System.loadLibrary("jnicam");
    }

    public static synchronized CamLib getInstance() {
        if (sInstance == null)
            sInstance = new CamLib();
        return sInstance;
    }

    public void setLocalHandler(Handler handler) {
        localHandler = handler;
    }

    /** Открыть камеру по file descriptor из UsbDeviceConnection. true — если поток запущен. */
    public boolean OpenDevice(int vendorId, int productId, int fd) {
        openDevice(vendorId, productId, fd);
        return isAlive();
    }

    /** Вызывается ИЗ нативного колбэка (поток SDK). Пересылает код события в Handler. */
    public void OnEvent(int event) {
        final Handler h = localHandler;
        if (h != null) {
            Message msg = h.obtainMessage();
            msg.what = MSG_EVENT;
            msg.arg1 = event;
            h.sendMessage(msg);
        }
    }

    // ---- native ------------------------------------------------------------
    /** Один раз перед открытием: сохраняет глобальную ссылку для колбэка. */
    public native void init();

    public static native String getModelName(int vendorId, int productId);

    private native int openDevice(int vendorId, int productId, int fd);

    /** Текущее разрешение живого потока [width, height]. */
    public native int[] getPreviewSize();

    /** Разрешение полнокадрового снимка [width, height]. */
    public native int[] getStillSize();

    /** Забрать очередной кадр превью в Bitmap ARGB_8888 (по событию EVENT_IMAGE). */
    public native void pullImage(Bitmap bitmap);

    /** Забрать снимок в Bitmap ARGB_8888 (по событию EVENT_STILLIMAGE). */
    public native void pullStillImage(Bitmap bitmap);

    /** Запустить полнокадровый снимок → придёт EVENT_STILLIMAGE. */
    public native void snap();

    public native void setAutoExpoEnable(int mode);         // 0 = ручная экспозиция

    // --- Авто-процедуры (§2.1/§2.2) ---
    /** Лимиты авто-AE: макс. выдержка (мкс) и макс. усиление (%). §2.1 (приоритет выдержки). */
    public native void setMaxAutoExpoTimeAGain(int maxTimeUs, int maxGainPercent);

    /** Текущая выдержка, мкс (для чтения результата авто-AE). */
    public native int getExpoTime();

    /** Текущее усиление, % (100 = 1.0x). */
    public native int getExpoAGain();

    /** Однократный автобаланс белого (§2.2). Результат — через {@link #getWhiteBalanceGain()}. */
    public native void awbOnce();

    /** Поканальные усиления WB (знаковые R,G,B, -127..127). */
    public native int[] getWhiteBalanceGain();

    /**
     * §2.3 FFC: нормализовать снимок делением на эталон (in-place по {@code still}).
     * {@code tmpl} — уменьшенный размытый шаблон неравномерности освещения.
     */
    public native void applyFlatField(Bitmap still, Bitmap tmpl);

    public native void setExpoTime(int microseconds);       // экспозиция, мкс

    public native void setExpoAGain(int percent);           // усиление, % (100 = 1.0x)

    public native void setWhiteBalanceGain(int r, int g, int b);

    /** Гамма (тональная кривая), проценты: Toupcam 20..180 (100 = 1.0). §3.3. */
    public native void setGamma(int gamma);

    public native void setOption(int option, int value);

    public native boolean isAlive();

    public native void releaseCamera();
}
