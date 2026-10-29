// Открытие камеры Touptek с выбором режима баланса белого RGB Gain и резервный путь без него.
// SDK по умолчанию открывает камеру в Temp/Tint; RGB Gain выбирается суффиксом ";wb=rgb"
// в camId (doc/en.html SDK: разделы White Balance и Toupcam_Open). Сочетание суффикса с
// Android-идентификатором "fd-…" на устройстве не проверено, поэтому при NULL — один повтор
// без суффикса, чтобы камера осталась доступна для съёмки.
//
// Без JNI и SDK: функция проверяется хостовым тестом app/src/test/cpp/wb_open_test.cpp.
#pragma once

#include <stdio.h>

// Способ открытия WB: с каким параметром камера открыта. Не доказательство работы баланса белого.
// Коды совпадают с com.siams.kern_prototype.camera.WbOpenMethod.
enum WbOpenMethod {
    WB_OPEN_NONE = 0,               // камера не открыта
    WB_OPEN_RGB_SUFFIX = 1,         // открыта с ";wb=rgb"
    WB_OPEN_FALLBACK_NO_SUFFIX = 2, // с суффиксом Toupcam_Open вернул NULL; открыта без него
};

// Открыть [baseId] с ";wb=rgb"; только если [open] вернул NULL — ровно один повтор с [baseId].
// Последующие ошибки (в т. ч. отказы WB) повторного открытия не вызывают — их здесь нет.
template <typename OpenFn, typename LogFn>
auto openWithWbRgb(const char *baseId, OpenFn open, LogFn log, int *method) -> decltype(open(baseId)) {
    *method = WB_OPEN_NONE;
    char id[160] = {0};
    snprintf(id, sizeof(id), "%s;wb=rgb", baseId);
    auto h = open(id);
    if (h) {
        *method = WB_OPEN_RGB_SUFFIX;
        return h;
    }
    log("Toupcam_Open with ;wb=rgb returned NULL; retrying once without suffix (WB mode not selected)");
    h = open(baseId);
    if (h) *method = WB_OPEN_FALLBACK_NO_SUFFIX;
    return h;
}
