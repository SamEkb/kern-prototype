// Хостовый тест резервного открытия (wb_open.h): Toupcam_Open подменён счётчиком вызовов.
// Kotlin-подмена камеры JNI не исполняет, поэтому повтор проверяется здесь.
// Запуск: ./gradlew :app:hostNativeTest

#include <stdio.h>
#include <string.h>
#include <string>
#include <vector>
#include "wb_open.h"

namespace {

struct Handle { int unused; };
Handle gCamera;

std::vector<std::string> gOpened;   // camId каждого вызова Toupcam_Open
std::vector<bool> gAnswers;         // ответ каждого вызова: true — хэндл, false — NULL
int gLogs = 0;
int gFailures = 0;

Handle *fakeOpen(const char *camId) {
    size_t n = gOpened.size();
    gOpened.push_back(camId);
    return n < gAnswers.size() && gAnswers[n] ? &gCamera : nullptr;
}

void fakeLog(const char *) { ++gLogs; }

void expect(bool ok, const char *what) {
    if (!ok) {
        ++gFailures;
        fprintf(stderr, "FAIL: %s\n", what);
    }
}

Handle *run(std::vector<bool> answers, int *method) {
    gOpened.clear();
    gAnswers = answers;
    gLogs = 0;
    *method = -1;
    return openWithWbRgb("fd-7-0547-1234", fakeOpen, fakeLog, method);
}

void suffixAcceptedOpensOnce() {
    int method;
    Handle *h = run({true}, &method);
    expect(h == &gCamera, "suffix: handle returned");
    expect(gOpened.size() == 1, "suffix: exactly one Toupcam_Open");
    expect(gOpened[0] == "fd-7-0547-1234;wb=rgb", "suffix: camId with ;wb=rgb");
    expect(method == WB_OPEN_RGB_SUFFIX, "suffix: method RGB_SUFFIX");
    expect(gLogs == 0, "suffix: no fallback log");
}

void nullWithSuffixRetriesOnceWithoutSuffix() {
    int method;
    Handle *h = run({false, true}, &method);
    expect(h == &gCamera, "fallback: handle returned");
    expect(gOpened.size() == 2, "fallback: exactly two Toupcam_Open");
    expect(gOpened[0] == "fd-7-0547-1234;wb=rgb", "fallback: first with suffix");
    expect(gOpened[1] == "fd-7-0547-1234", "fallback: second without suffix");
    expect(method == WB_OPEN_FALLBACK_NO_SUFFIX, "fallback: method FALLBACK_NO_SUFFIX");
    expect(gLogs == 1, "fallback: logged once");
}

void bothNullFailsWithoutThirdAttempt() {
    int method;
    Handle *h = run({false, false, true}, &method);
    expect(h == nullptr, "both null: no handle");
    expect(gOpened.size() == 2, "both null: no third Toupcam_Open");
    expect(method == WB_OPEN_NONE, "both null: method NONE");
}

void staleMethodIsReset() {
    int method = WB_OPEN_RGB_SUFFIX;
    gOpened.clear();
    gAnswers = {false, false};
    openWithWbRgb("fd-7-0547-1234", fakeOpen, fakeLog, &method);
    expect(method == WB_OPEN_NONE, "reset: previous method not kept after failed open");
}

}  // namespace

int main() {
    suffixAcceptedOpensOnce();
    nullWithSuffixRetriesOnceWithoutSuffix();
    bothNullFailsWithoutThirdAttempt();
    staleMethodIsReset();
    if (gFailures) {
        fprintf(stderr, "wb_open_test: %d failure(s)\n", gFailures);
        return 1;
    }
    printf("wb_open_test: 4 tests passed\n");
    return 0;
}
