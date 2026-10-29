plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.siams.kern_prototype"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    // NDK для сборки JNI-моста ToupCam (jnicam.cpp → libjnicam.so, линкует libtoupcam.so).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.siams.kern_prototype"
        // §3: Camera2 manual capture requires API 26 (Android 8.0).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // §3: ToupCam SDK поставляет libtoupcam.so только под ARM (реальные телефоны;
        // у эмулятора нет USB-host — живая съёмка только на устройстве). Ограничиваем
        // набор ABI и для сборки JNI (ndkBuild), и для упаковки.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    // Сборка нативного моста: jni/Android.mk собирает libjnicam.so и упаковывает
    // prebuilt libtoupcam.so (из jni/libusbcam/<abi>/) в APK.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // Крупные .so ToupCam: распаковывать при установке, чтобы System.loadLibrary работал стабильно.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // R07: схемы Room хранятся в репозитории (app/schemas) и доступны тестам миграций.
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Image loading
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.android)
    // Compose-тесты раскладки под Robolectric (компоненты без строковых ресурсов).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Хостовый тест нативной логики открытия камеры (jni/wb_open.h): Toupcam_Open в нём подменён,
// поэтому проверяется повтор без ";wb=rgb", которого Kotlin-подмена камеры не исполняет.
// Нужен компилятор C++ хоста: c++ из PATH или переменная CXX. Запуск: ./gradlew :app:hostNativeTest
val hostNativeTestDir = layout.buildDirectory.dir("hostNativeTest")
val compileHostNativeTest by tasks.registering(Exec::class) {
    val source = file("src/test/cpp/wb_open_test.cpp")
    val binary = hostNativeTestDir.map { it.file("wb_open_test") }
    inputs.files(source, file("src/main/jni/wb_open.h"))
    outputs.file(binary)
    val compiler = providers.environmentVariable("CXX").orElse("c++").get()
    executable = compiler
    args(
        "-std=c++11", "-Wall", "-Wextra", "-Werror",
        "-I", file("src/main/jni").path,
        source.path, "-o", binary.get().asFile.path,
    )
    doFirst {
        // Понятная ошибка вместо «не удалось запустить процесс»; тест не пропускается молча.
        val found = File(compiler).isAbsolute && File(compiler).canExecute() ||
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, compiler).canExecute() }
        if (!found) throw GradleException(
            "hostNativeTest: нет компилятора C++ хоста '$compiler'. Установите g++ или clang++ либо задайте CXX.",
        )
        hostNativeTestDir.get().asFile.mkdirs()
    }
}
tasks.register<Exec>("hostNativeTest") {
    description = "Хостовый тест резервного открытия камеры (jni/wb_open.h)."
    dependsOn(compileHostNativeTest)
    executable = hostNativeTestDir.get().file("wb_open_test").asFile.path
}
