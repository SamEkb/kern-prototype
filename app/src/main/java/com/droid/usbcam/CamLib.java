package com.droid.usbcam;

import android.graphics.Bitmap;

/**
 * JNI-обёртка ToupCam SDK для камеры Touptek (§3). Пакет com.droid.usbcam сохранён,
 * потому что имена JNI-функций в jnicam.cpp кодируют пакет+класс. Расширена относительно
 * официального демо ToupTek: добавлены снимок (snap/pullStillImage), размер снимка и
 * ручные параметры (экспозиция/усиление/баланс белого/опции).
 *
 * Нативная библиотека грузится статически: libtoupcam.so (C API) + libjnicam.so (мост).
 * Событие камеры приходит из натива в {@link #OnEvent(int)} и пересылается слушателю.
 */
public class CamLib {

    /** Получатель событий SDK. Вызывается в потоке SDK — не должен блокировать. */
    public interface EventListener {
        void onEvent(int event);
    }

    private static CamLib sInstance;
    private static volatile EventListener sListener = null;

    static {
        System.loadLibrary("toupcam");
        System.loadLibrary("jnicam");
    }

    public static synchronized CamLib getInstance() {
        if (sInstance == null)
            sInstance = new CamLib();
        return sInstance;
    }

    /** Слушатель событий SDK; null — события отбрасываются. */
    public void setEventListener(EventListener listener) {
        sListener = listener;
    }

    /** Открыть камеру по file descriptor из UsbDeviceConnection. true — если поток запущен. */
    public boolean OpenDevice(int vendorId, int productId, int fd) {
        openDevice(vendorId, productId, fd);
        return isAlive();
    }

    /** Вызывается ИЗ нативного колбэка (поток SDK). Пересылает код события слушателю. */
    public void OnEvent(int event) {
        final EventListener l = sListener;
        if (l != null) l.onEvent(event);
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

    /**
     * Забрать снимок в Bitmap ARGB_8888 (по событию EVENT_STILLIMAGE).
     * @return true — в bitmap новый кадр; false — SDK не отдал кадр (bitmap не изменён).
     */
    public native boolean pullStillImage(Bitmap bitmap);

    /** Запустить полнокадровый снимок → придёт EVENT_STILLIMAGE. false — SDK отклонил Snap. */
    public native boolean snap();

    public native int setAutoExpoEnable(int mode);         // 0 = ручная экспозиция

    // --- Авто-процедуры (§2.1/§2.2) ---
    /** Лимиты авто-AE: макс. выдержка (мкс) и макс. усиление (%). §2.1 (приоритет выдержки). */
    public native int setMaxAutoExpoTimeAGain(int maxTimeUs, int maxGainPercent);

    /** Текущая выдержка, мкс (для чтения результата авто-AE). */
    public native int getExpoTime();

    /** Текущее усиление, % (100 = 1.0x). */
    public native int getExpoAGain();

    /** Однократный автобаланс белого (§2.2). Результат — через {@link #getWhiteBalanceGain()}. */
    public native int awbOnce();

    /** Поканальные усиления WB (знаковые R,G,B, -127..127). */
    public native int[] getWhiteBalanceGain();

    /**
     * §2.3 FFC: нормализовать снимок делением на эталон (in-place по {@code still}).
     * {@code tmpl} — уменьшенный размытый шаблон неравномерности освещения.
     * @return false — коррекция не выполнена (снимок не изменён).
     */
    public native boolean applyFlatField(Bitmap still, Bitmap tmpl);

    public native int setExpoTime(int microseconds);       // экспозиция, мкс

    public native int setExpoAGain(int percent);           // усиление, % (100 = 1.0x)

    public native int setWhiteBalanceGain(int r, int g, int b);

    /** Гамма (тональная кривая), проценты: Toupcam 20..180 (100 = 1.0). §3.3. */
    public native int setGamma(int gamma);

    public native int setOption(int option, int value);

    /**
     * R14: фактическое состояние (readback), пары [HRESULT, значение...] — раскладку см. в
     * jnicam.cpp (readState). Сеттеры возвращают HRESULT: 0 — принят, иначе — нет.
     */
    public native int[] readState();

    /** R14/R17: сведения о модели и прошивке для журнала диагностики («ключ=значение» по строкам). */
    public native String describe();

    public native boolean isAlive();

    public native void releaseCamera();
}
