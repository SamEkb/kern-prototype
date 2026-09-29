# CLAUDE.md — Android-приложение оцифровки керна («Долбление / Стол»)

Инженерное ТЗ целиком — `ANDROID_KERN_APP.md` (в корне). Здесь — выжимка для
работы в этом репозитории (домен, стек, контракт s900, правила) плюс заметки о
тулчейне, которых в ТЗ нет.

## Что это
Отдельное Android-приложение (Kotlin/Compose) для съёмки лотков керна **парами
кадров** (Дневной свет + УФ) с полностью ручной, воспроизводимой камерой, и
пакетной отправки на сервер **s900** (проект `s8`).

## Домен (§2 ТЗ)
- **Долбление** (`Drilling`) = `AnalysisSource` на сервере. Несёт паспортные гео-поля.
- **Стол** (`Table`) = `FieldOfView` (STACK). Нумеруется №1, №2, …
- **Слой** (`Layer`) = `SourceImage` с `lampType`. Всегда пара Дневной + УФ.
- **Стек** = пара слоёв одного стола, группировка по `stackUid`.
- **Профиль камеры** = ручные параметры под тип освещения (только на клиенте).
- `LampType` — **только** `DAYLIGHT` / `UF` (УФ = `UF`, не «UV»), совпадать с сервером.

## Стек и модульная структура
Kotlin · Compose (Material3, тема `KernTheme`) · Camera2 (ручная съёмка) · Room ·
DataStore · Retrofit/OkHttp (kotlinx.serialization) · WorkManager · Hilt · Coroutines.

- `domain/model` — доменные модели (LampType, CameraProfile, ServerSettings, Passport…).
- `data/db` — Room (entities/dao/relation/Converters/KernDatabase).
- `data/settings` — DataStore (ServerSettingsStore, CameraProfileStore).
- `data/storage` — FileStorage (фото во `filesDir`).
- `data` — `KernRepository` (единый источник истины), `PassportCodec`.
- `camera` — `KernCamera` (абстракция камеры) + `ToupcamController` (внешняя USB-камера
  Touptek E3ISPM, **основной путь**; JNI-мост `com.droid.usbcam.CamLib`/`jni/jnicam.cpp` +
  `ToupcamProfileApplier`) + `CameraController` (Camera2 телефона, **запасной**) + `CameraProfileApplier`.
  Нативка ToupCam (проприетарный `libtoupcam.so`, `toupcam.h`, мост) вендорится в `app/src/main/jni/`.
- `network` — `S900Api`, DTO, `S900Interceptor` (Basic + подмена host), `ConnectionTester`.
- `sync` — `DrillingUploader` (§8.3), `UploadWorker`, `SyncManager`.
- `di` — Hilt-модули (Database/Network/Serialization).
- `ui` — `theme` (токены §14), `components`, `screens`, `nav`; `viewmodel` — VM.

## Правила (§13 ТЗ)
- Домен именовать по §2. `LampType` = DAYLIGHT/UF.
- Room — единственный источник истины; UI читает из `Flow`, не из сети.
- Сеть — только в `network`/`sync`/WorkManager; никаких сетевых вызовов из UI.
- Все параметры камеры и применение профилей — через реализацию `KernCamera`
  (`ToupcamController`/`CameraController`) + соответствующий `*ProfileApplier`; не разбрасывать
  `CaptureRequest.Builder`/вызовы SDK по экранам. Активную камеру выбирает Hilt (`di/CameraModule`).
- Не перезаливать уже отправленные слои — проверять `remoteImageId`.
- Дизайн — токены `KernTokens`/`KernTheme` (§14). Не хардкодить цвета/размеры.
- Перед изменением контракта §8 — сверяться с репозиторием `s8` (`web/.../api/v3/s8/*`,
  `s8-api/.../dto/*`, `API.md`); сомнения → открытый вопрос (§10), не угадывать.

## Контракт s900 (§8) — кратко
- База: `http(s)://<host>:<port>/s8/api/v3/s8/`. Cleartext HTTP разрешён
  (`network_security_config`). Из эмулятора хост = **`10.0.2.2`** (не `localhost`).
- **Авторизация — СЕССИОННАЯ, не Basic** (проверено на живом s900, резолв §10.3):
  форма `POST /s8/sia/auth/login` (form `username`/`password`) → cookie `JSESSIONID`.
  Реализация: `InMemoryCookieJar` + `SessionLoginInterceptor` (на 302→`/sia/auth/`
  логинится и повторяет запрос). Клиент с `followRedirects=false`.
- Последовательность отправки стола — `DrillingUploader.uploadTable` (§8.3):
  create source (идемпотентно по id) → залить Дневной (новый FoV) → PUT метаданные
  (`lampType`/`stackUid`/`coreImageType`) → залить УФ в тот же FoV → PUT метаданные.
  **Проверено end-to-end на живом s900:** пара DAYLIGHT+UF ложится в один FieldOfView
  с общим `stackUid`.
- **Особенности ответов s900 (важно!):** `POST /analysis/source` возвращает id
  **голой строкой** (не JSON); `PUT …/image/{iid}` возвращает 200 с **пустым телом**.
  Поэтому эти два вызова — `Response<ResponseBody>` + `ensureSuccess` (без JSON-парсинга),
  иначе конвертер kotlinx падает. Ошибки s900 отдаёт HTML-страницей → `errorText()` чистит теги.
- Паспортные поля — плоские гео-ключи верхнего уровня (§8.5), см. `PassportSchema`.
  `GET source` их не эхоит (особенность read-DTO), **НО данные хранятся и ищутся**:
  проверено — `field`/`well_number` из create появляются в фильтрах «Фотокниги керна»
  (месторождение → скважина) и матчат керн. Рендер картинки в фотокнике требует
  **ImageMagick на сервере** (если не установлен — «Не найдено imagemagick», серверный ops).
- ⚠️ `POST /analysis/source` идемпотентен, но атрибуты существующего проекта **НЕ обновляет**.
  Правки паспорта уже созданного проекта надо слать через `PUT /analysis/source/{id}`
  (сейчас клиент шлёт только POST — при повторной отправке отредактированного паспорта
  изменения на сервер не уедут; TODO, если понадобится). Владелец проекта — `anonymous` (§10.4).
- Права: пользователь должен иметь право создания источника (`tryAllPermission`),
  иначе `create` → 500 «Недостаточно прав» (§10.4). `administrator` подошёл после
  донастройки прав на сервере.

## Открытые вопросы к s900 (§10) — до Этапа 5
Аутентификация, точный контракт ингестии стека («как GEO 360»), триггер
`postupload_processing`, авторитет глубины, `pixelSize`. См. §10 ТЗ.

## Заметки по тулчейну (важно!)
Тулчейн бле­дже­во-новый (AGP 9.3 со **встроенным Kotlin**, Kotlin 2.2.10, Gradle 9.5).
- **Не** добавлять плагин `org.jetbrains.kotlin.android` — Kotlin применяет сам AGP.
- Hilt: нужна версия **≥ 2.60** (2.57 падает «Android BaseExtension not found»).
- В `gradle.properties`: `android.disallowKotlinSourceSets=false` — иначе KSP
  (Room/Hilt) конфликтует со встроенным Kotlin.
- `org.gradle.configuration-cache=false` — AGP 9.3 ломает конфиг-кэш на `MergeResources`.
- Сборка: `./gradlew :app:assembleDebug`. Первый прогон ~6 мин (скачивание),
  инкрементально ~10–15 с.

## Статус реализации
Этапы 0–5 + большая часть 6–7 реализованы и собираются (`:app:assembleDebug` зелёный).
Пройден проход на полное соответствие бизнес-ТЗ:
- **Форма**: два режима — упрощённый (только «Название») и полный (гео-схема §8.5),
  переключается флагом `fieldsConfigLoadedAt`. Онбординг первого запуска: при
  ненастроенном сервере старт открывает «Настройки» (`StartViewModel` + эффект в `KernNavGraph`).
- **Настройки**: кнопка «Загрузить конфигурацию полей», просмотр списка полей, честный
  статус (встроенная схема, эндпоинт §10.1 не готов), валидация host/порт.
- **Галерея**: правка № стола и удаление стола выведены в UI (`TableViewScreen`),
  подтверждение удаления слоя, индикатор занятого места (`usedBytes` в списке/очереди),
  миниатюры превью (`FileStorage.writeThumbnail`, `LayerEntity.thumbPath`), ленивое
  создание столов (нет пустых), пометка dirty на всех правках.
- **Отправка**: мультивыбор (чекбоксы + «выбрать всё»), детерминированный прогресс
  «стол i из N» (`DrillingEntity.uploadedTables/totalTables`), `PUT /analysis/source/{id}`
  для правок паспорта уже созданного проекта (по `remoteId`).
- **Камера (Camera2, запасной путь)**: фиксированная `COLOR_CORRECTION_TRANSFORM` (единичная)
  для детерминизма preview↔снимок, реальная сверка AE/AWB/AF в `CaptureResult`
  (`verifyManualLock`, `lastCaptureVerified`), параметры WB Green и длительность кадра в редакторе.
- **Камера Touptek E3ISPM (основной путь)**: съёмка ведётся ВНЕШНЕЙ USB-камерой Touptek
  (USB-OTG), а не камерой телефона. Реализация по официальному демо ToupTek (ndk-build):
  нативный `libtoupcam.so` (проприетарный, вендорится) + JNI-мост `jni/jnicam.cpp`→`libjnicam.so`
  + Java-обёртка `com.droid.usbcam.CamLib` (пакет НЕ менять — имена JNI кодируют пакет).
  `ToupcamController` (§ KernCamera): поиск камеры в `UsbManager` по `CamLib.getModelName(vid,pid)`
  → USB-доступ → `CamLib.OpenDevice("fd-…")` → нативный выбор разрешений + pull-режим →
  EVENT_IMAGE тянет кадр в Bitmap и рисует на `Surface`; `snap()` полного кадра ~20 Мп →
  EVENT_STILLIMAGE → Bitmap → JPEG в тот же файловый пайплайн. `jnicam.cpp` расширен над демо:
  добавлены снимок и ручные параметры. `ToupcamProfileApplier` (профиль Camera2 →
  `setExpoTime`/`setExpoAGain`/`setWhiteBalanceGain`, авто OFF). Hilt `di/CameraModule`
  привязывает `KernCamera`→`ToupcamController`. Манифест: `usb.host` + `USB_DEVICE_ATTACHED`/
  `res/xml/device_filter.xml`. `build.gradle.kts`: `externalNativeBuild ndkBuild`,
  `ndkVersion=28.2.13676358`, `abiFilters=arm64-v8a,armeabi-v7a`. Собрано и упаковано:
  APK содержит `libtoupcam.so`+`libjnicam.so` под обе ABI.
- **Дизайн §14**: иконки → Material Symbols Outlined; строки вынесены в `strings.xml`
  (RU). БД: version=4, явные миграции (`KernMigrations`, схемы в `app/schemas`, тесты `KernMigrationsTest`), копия БД перед обновлением (`DatabaseBackup`); разрушительного fallback нет (v1–v2 и сбой копии останавливают обновление).

**Заблокировано / остаётся вручную:**
- Динамическая загрузка полей паспорта с сервера — ждёт REST-эндпоинт §10.1 (сейчас
  «загрузка» = встроенная гео-схема; seam готов).
- Шрифт **Lato** (§14.3) не подключён: нужен TTF-бинарник (`res/font/lato_regular.ttf`,
  `lato_bold.ttf`) + замена `KernFont` в `Type.kt`. Downloadable Google Fonts не взят
  из-за offline-first. Сейчас фолбэк — системный sans-serif.
- Живой прогон на устройстве с камерой не выполнялся (камерные правки идентичности
  требуют проверки на реальном сенсоре — эмулятор не даёт MANUAL_SENSOR).
- **ToupCam SDK (см. `TOUPCAM_SETUP.md`)**: бинарники и мост уже в репозитории; для сборки
  нужен NDK (`sdkmanager "ndk;28.2.13676358"`; CMake не требуется). Живого прогона на камере
  Touptek ещё НЕ было (у эмулятора нет USB-host). На реальном устройстве уточнить VID/PID
  USB3-камеры (для `device_filter.xml`), при необходимости — переворот кадра
  (`TOUPCAM_OPTION_UPSIDE_DOWN` в `jnicam.cpp`), проверить питание OTG.
