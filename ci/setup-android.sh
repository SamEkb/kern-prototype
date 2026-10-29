# Подготовка окружения сборки на раннере GitLab: JDK 21 и Android SDK/NDK.
# Подключается через `source` (экспортирует JAVA_HOME, ANDROID_HOME, PATH).
#
# Раннер ничего заранее не обязан иметь, кроме bash, curl, unzip, tar, flock и компилятора C++
# хоста (c++ или переменная CXX — для :app:hostNativeTest; не скачивается): недостающее
# скачивается один раз в кеш KERN_CI_HOME (по умолчанию ~/.cache/kern-ci) и дальше
# переиспользуется. Параллельные задачи ставят пакеты под общим замком.
# Версии — те же, что в app/build.gradle.kts и gradle/gradle-daemon-jvm.properties.

KERN_CI_HOME="${KERN_CI_HOME:-$HOME/.cache/kern-ci}"
KERN_JDK_MAJOR=21
KERN_JDK_URL="https://api.adoptium.net/v3/binary/latest/${KERN_JDK_MAJOR}/ga/linux/x64/jdk/hotspot/normal/eclipse"
KERN_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
KERN_SDK_PACKAGES=(
    "platform-tools"
    "platforms;android-36.1"
    "build-tools;36.0.0"
    "ndk;28.2.13676358"
)

kern_fail() { echo "setup-android: $*" >&2; return 1; }

kern_java_major() { # мажорная версия java из $1/bin/java (или пусто)
    "$1/bin/java" -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p'
}

kern_setup() {
    local tool
    for tool in curl unzip tar flock; do
        command -v "$tool" >/dev/null || kern_fail "на раннере нет '$tool'" || return 1
    done
    # Нативный тест обязателен: без компилятора задача падает сразу, а не на шаге hostNativeTest.
    command -v "${CXX:-c++}" >/dev/null ||
        kern_fail "на раннере нет компилятора C++ хоста '${CXX:-c++}' (нужен :app:hostNativeTest): установите g++ или clang++ либо задайте CXX" ||
        return 1
    mkdir -p "$KERN_CI_HOME" || return 1

    exec 9>"$KERN_CI_HOME/.lock"
    flock 9 || return 1

    # JDK: системная, если подходит по версии; иначе — своя в кеше.
    local system_java=""
    if command -v java >/dev/null; then
        system_java="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ "$(kern_java_major "$JAVA_HOME")" = "$KERN_JDK_MAJOR" ]; then
        :
    elif [ -n "$system_java" ] && [ "$(kern_java_major "$system_java")" = "$KERN_JDK_MAJOR" ]; then
        export JAVA_HOME="$system_java"
    else
        local jdk="$KERN_CI_HOME/jdk-$KERN_JDK_MAJOR"
        if [ ! -x "$jdk/bin/java" ]; then
            echo "setup-android: скачиваю JDK $KERN_JDK_MAJOR"
            rm -rf "$jdk" "$jdk.tmp" && mkdir -p "$jdk.tmp" &&
                curl -fsSL --retry 3 "$KERN_JDK_URL" | tar -xz -C "$jdk.tmp" --strip-components=1 &&
                mv "$jdk.tmp" "$jdk" || { flock -u 9; kern_fail "не удалось установить JDK"; return 1; }
        fi
        export JAVA_HOME="$jdk"
    fi

    # Android SDK: cmdline-tools, лицензии и пакеты проекта.
    export ANDROID_HOME="$KERN_CI_HOME/android-sdk"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$sdkmanager" ]; then
        echo "setup-android: скачиваю Android cmdline-tools"
        local tmp="$KERN_CI_HOME/cmdline-tools.tmp"
        rm -rf "$tmp" && mkdir -p "$tmp" &&
            curl -fsSL --retry 3 -o "$tmp/tools.zip" "$KERN_CMDLINE_TOOLS_URL" &&
            unzip -q "$tmp/tools.zip" -d "$tmp" &&
            mkdir -p "$ANDROID_HOME/cmdline-tools" &&
            rm -rf "$ANDROID_HOME/cmdline-tools/latest" &&
            mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" &&
            rm -rf "$tmp" || { flock -u 9; kern_fail "не удалось установить cmdline-tools"; return 1; }
    fi
    local stamp="$ANDROID_HOME/.kern-packages"
    if [ "$(cat "$stamp" 2>/dev/null)" != "${KERN_SDK_PACKAGES[*]}" ]; then
        echo "setup-android: устанавливаю пакеты SDK: ${KERN_SDK_PACKAGES[*]}"
        { yes || true; } | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null &&
            "$sdkmanager" --sdk_root="$ANDROID_HOME" "${KERN_SDK_PACKAGES[@]}" >/dev/null &&
            echo "${KERN_SDK_PACKAGES[*]}" >"$stamp" || { flock -u 9; kern_fail "не удалось установить пакеты SDK"; return 1; }
    fi

    flock -u 9
    export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
    echo "setup-android: JAVA_HOME=$JAVA_HOME ($(kern_java_major "$JAVA_HOME")), ANDROID_HOME=$ANDROID_HOME"
}

kern_setup
