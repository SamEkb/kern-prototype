# USB-камера Touptek E3ISPM20000KPA — интеграция

Приложение снимает пары кадров (Дневной/УФ) **внешней USB-камерой Touptek**, а не
камерой телефона. Всё необходимое из ToupCam SDK **уже добавлено в репозиторий** —
отдельный ручной шаг скачивания SDK больше не нужен. Сборка `:app:assembleDebug`
зелёная, `.so` и мост пакуются в APK.

---

## 1. Что лежит в проекте (из ToupCam SDK)

| Файл | Роль |
|---|---|
| `app/src/main/jni/libusbcam/arm64-v8a/libtoupcam.so`, `armeabi-v7a/…` | нативный SDK ToupTek (C API), проприетарный |
| `app/src/main/jni/libusbcam/toupcam.h` | заголовок C API |
| `app/src/main/jni/libusbcam/Android.mk` | prebuilt-модуль `toupcam` для ndk-build |
| `app/src/main/jni/jnicam.cpp` | JNI-мост (расширен: снимок + ручные параметры) → `libjnicam.so` |
| `app/src/main/jni/Android.mk`, `Application.mk` | сборка `libjnicam.so` (ndk-build) |
| `app/src/main/java/com/droid/usbcam/CamLib.java` | Java-обёртка JNI (пакет менять нельзя — имена JNI кодируют пакет) |

Наш код (пакет `com.siams.kern_prototype.camera`):

| Файл | Роль |
|---|---|
| `KernCamera.kt` | абстракция камеры (общий контракт с Camera2) |
| `ToupcamController.kt` | реализация: USB-доступ → `CamLib.OpenDevice` → превью → снимок → JPEG |
| `ToupcamProfileApplier.kt` | профиль (Camera2-модель) → `setExpoTime`/`setExpoAGain`/`setWhiteBalanceGain`, авто OFF |
| `di/CameraModule.kt` | Hilt: `KernCamera` → `ToupcamController` |
| `camera/CameraController.kt` | Camera2 телефона — запасной путь (сменить привязку в `CameraModule`) |

Манифест: `uses-feature usb.host` + `USB_DEVICE_ATTACHED`/`res/xml/device_filter.xml`.
`build.gradle.kts`: `externalNativeBuild { ndkBuild }`, `ndkVersion`, `abiFilters = arm64-v8a, armeabi-v7a`.

## 2. Требование сборки: NDK

JNI-мост собирается через **ndk-build**, поэтому нужен Android NDK. В `build.gradle.kts`
зафиксировано `ndkVersion = "28.2.13676358"`. Установка (если у другого разработчика нет):

```
sdkmanager "ndk;28.2.13676358"
```

(Android Studio также предложит установить NDK при открытии проекта.) CMake не требуется.

## 3. Камера (спецификация, кратко)

- Сенсор **Sony IMX183**, цвет, 1″; **20 Мп**, полный кадр **5440×3648**; пиксель 2.4 мкм.
- **USB 3.0**, разъём на камере — **USB3.0 Micro-B** (не USB-C).
- Питание по шине (до ~4.5 Вт). C-mount, 380–650 нм.
- fps: 15 @ 5440×3648, 50 @ 2736×1824, 60 @ 1824×1216.

Превью идёт на «быстром» разрешении (крупнейшее с шириной ≤ 2048), снимок — на полном
(индексы и размеры выбираются нативно в `jnicam.cpp: openDevice`).

## 4. Подключение и первый запуск (на реальном устройстве)

1. Телефон с поддержкой **USB-host (OTG)**. Кабель/переходник **USB3.0 Micro-B → USB-C**;
   при нестабильном питании — **OTG-хаб с внешним питанием** (камера прожорлива).
2. Соберите и установите: `./gradlew :app:installDebug` (или из Android Studio).
3. Подключите камеру, откройте приложение, зайдите на экран съёмки.
4. Появится системный диалог доступа к USB — разрешите (галочка «по умолчанию»
   избавит от повторов). Далее — живое превью; спуск сохраняет полный кадр в тот же
   пайплайн (Room → отправка на s900), что и раньше.

> Эмулятор не подходит: у него нет USB-host. Живая съёмка — только на устройстве.

## 5. Что проверить/подстроить на реальном устройстве

- **VID/PID именно вашего экземпляра** (USB3 «K»-серия). Определение камеры идёт в рантайме
  через `CamLib.getModelName(vid,pid)` (нативный `Toupcam_get_Model`), так что приложение
  заработает и без правок. Для авто-открытия при подключении добавьте реальный VID в
  `res/xml/device_filter.xml` (десятичный). Узнать: `UsbManager.getDeviceList()` или `lsusb`.
- **Ориентация/цвет**: на Android SDK по умолчанию отдаёт кадр top-down и порядок байт RGB —
  `jnicam.cpp` рассчитан на это (RGB24 → ARGB_8888, alpha=255). Если на конкретном железе
  кадр перевёрнут — включите `Toupcam_put_Option(gHcam, TOUPCAM_OPTION_UPSIDE_DOWN, 1)` в
  `openDevice`.
- **Питание**: если превью рвётся/камера отваливается (EVENT 0x81) — почти всегда не хватает
  питания OTG; используйте хаб с внешним питанием.

## 6. Соответствие профиля камеры (Camera2 ↔ Touptek)

`CameraProfile` исходно под Camera2; `ToupcamProfileApplier` отображает его в родные
параметры Touptek и жёстко выключает автоматику (детерминизм, §6.2):

| Профиль (Camera2) | Touptek (`CamLib`/C API) |
|---|---|
| `exposureTimeNs` | `setExpoTime` (мкс), `setAutoExpoEnable(0)` |
| `iso` | `setExpoAGain` (%, 100 = 1.0x) |
| `wbRedGain`/`wbBlueGain` (RGGB) | `setWhiteBalanceGain` (знаковые R/G/B) |
| `focusDistance` | не используется (фокус микроскопа механический) |

Значения по-прежнему настраиваются в «Профили камеры» и применяются к превью на лету.

## 7. Лицензия

`libtoupcam.so`, `toupcam.h`, а также производные `CamLib.java`/`jnicam.cpp` взяты из
официального **ToupCam SDK** (ToupTek, версия 59.30594.20260120). Это штатный способ
использования SDK — компиляция в своё приложение. Перед распространением сверьтесь с
условиями лицензии из архива SDK.
