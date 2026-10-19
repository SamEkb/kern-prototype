# Разработка и проверка

[← README](../README.md) · [Архитектура](ARCHITECTURE.md)

## Окружение

Версии зафиксированы в [каталоге зависимостей](../gradle/libs.versions.toml),
[Gradle Wrapper](../gradle/wrapper/gradle-wrapper.properties) и
[настройках app](../app/build.gradle.kts). Не обновляйте их только ради запуска.

| Компонент | Версия проекта |
|---|---|
| Gradle | 9.5.0, запускать через ./gradlew |
| Android Gradle Plugin | 9.3.0, встроенный Kotlin |
| Kotlin / Hilt | 2.2.10 / 2.60.1 |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 26 |
| NDK | 28.2.13676358, ndk-build |
| Room | 2.8.2, схема приложения v7 |
| ABI | arm64-v8a, armeabi-v7a |

Используйте Gradle JDK из совместимой Android Studio. В проверенном локальном
окружении это `/opt/android-studio/jbr`; на другой машине путь будет иным.
Java source/target 11 в модуле — уровень исходников, не инструкция запускать Gradle на JDK 11.

SDK задаётся Android Studio или локальным `local.properties` (`sdk.dir`).
Установите платформу для compileSdk 36.1 и NDK:

```bash
sdkmanager "ndk;28.2.13676358"
```

CMake не нужен. ToupCam SDK уже находится в репозитории.
Первый запуск требует доступа к репозиториям Google/Maven и дистрибутиву Gradle.
`--offline` подходит только при заполненном кеше; он не исправляет отсутствие SDK.

Не добавляйте `org.jetbrains.kotlin.android`: Kotlin встроен в AGP.
Сохраните настройки KSP и configuration-cache из [gradle.properties](../gradle.properties);
причины их выбора записаны в комментариях.

## Команды

```bash
# APK приложения
./gradlew :app:assembleDebug

# Unit/Robolectric и lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# Сборка APK инструментальных тестов (не их выполнение)
./gradlew :app:assembleDebugAndroidTest

# Установка на подключённое устройство
./gradlew :app:installDebug

# Инструментальные тесты на совместимом устройстве
./gradlew :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release-подпись и публикация не настроены этой инструкцией; успешный debug build
не означает готовый промышленный релиз.

## Полная локальная проверка

Пример для Bash: отдельный каталог на каждый прогон, код возврата Gradle сохраняется,
XML не затираются следующим запуском.

```bash
review_run_dir=$(mktemp -d /tmp/kern-check.XXXXXX)
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --rerun-tasks --console=plain > "$review_run_dir/gradle.log" 2>&1
review_exit=$?
cp -R app/build/test-results "$review_run_dir/" 2>/dev/null || true
cp -R app/build/reports "$review_run_dir/" 2>/dev/null || true
printf 'Gradle exit=%s; reports=%s\n' "$review_exit" "$review_run_dir"
```

При раннем сбое сборки отчёты на диске могут остаться от прежнего прогона.
Сначала проверьте лог, выполнение тестовой задачи и время файлов, затем считайте тесты.
После правок только документации достаточно проверки ссылок и diff — Gradle не нужен.

После мутаций используйте `--rerun-tasks`: ранее восстановление файлов со старым
mtime позволяло инкрементальной сборке оставить мутированный класс.
Пойманная мутация — компилируемый вариант, на котором падает ожидаемый тест;
ошибка компиляции или старый XML этого не доказывают.

## Где какие проверки

- camera: контроллер с FakeCameraRig, отмена, подключения, ожидание, FFC, аудит.
- data/db: настоящий Room под Robolectric, миграции, повреждение и шлюз открытия.
- data/settings: перенос и шифрование секрета; тестовый ключ JCE вместо Android Keystore.
- sync/network: MockWebServer, назначение, ошибки и повторы.
- viewmodel: ввод, черновики, ожидание записи; отменить и дождаться корутин до resetMain.
- ui: отдельные используемые компонентами раскладки и чистая логика.

Глобальный `includeAndroidResources` ранее приводил к нестабильности тестов БД
на нативном SQLite/WAL, вплоть до SIGBUS. Он остаётся выключенным. Не включайте
его как универсальное исправление отсутствующего ресурса без изоляции проблемы.

Реальная камера, Keystore, питание USB, поворот во время снимка, восстановление
и S900 проверяются по [чек-листу](../ACCEPTANCE_CHECKLIST.md). Robolectric их не заменяет.
APK содержит только ARM-библиотеки; обычный x86-эмулятор не заменяет стенд Touptek.

## Передача сборки и диагностика

Передайте APK, его SHA-256, commit ID, дату, чек-лист и список открытых ограничений:

```bash
git rev-parse HEAD
git status --short
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s ToupcamController KernMemory CameraScreenSession CamLibDevice
```

При изменённом рабочем дереве commit ID не описывает весь APK — укажите это.
В отчёте с устройства нужны модель телефона, Android, камера/прошивка,
питание/хаб, освещение, шаги и время ошибки. Для AE/AWB нужны сводки обеих ламп
и HRESULT чтений. Не публикуйте пароли и содержимое рабочего архива в общих логах.

Для JNI проверьте фактический состав APK и импорты/экспорты обеих ABI.
Количество JNI-символов само по себе не доказывает корректность моста.

## Обновление документации

README — вход; ARCHITECTURE — связи и инварианты; WORKFLOWS — поведение;
STATUS — нерешённое; чек-лист — способы приёмки.
Изменив поведение, обновите соответствующие разделы. Исторические отчёты
сохраняйте с датой, не используйте их как текущую спецификацию.
