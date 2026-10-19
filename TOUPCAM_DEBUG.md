# TOUPCAM_DEBUG.md — журнал живой отладки камеры Touptek на устройстве

> **Журнал исторической аппаратной отладки.** Результаты относятся к указанным стендам и версиям. Текущей сборке нужна новая [приёмка](ACCEPTANCE_CHECKLIST.md); подключение — [TOUPCAM_SETUP](TOUPCAM_SETUP.md).

Хроника отладки внешней USB-камеры **Touptek E3ISPM** (§3 ТЗ) на реальном телефоне,
2026-08-19. Что ломалось, как диагностировали, что меняли, чем закончилось. Дополняет
`TOUPCAM_SETUP.md` (сборка/бинарники) и `CLAUDE.md` (архитектура).

---

## 1. Стенд

| Что | Значение |
|---|---|
| Телефон | Xiaomi Redmi Note 12 (`23053RN02Y`, codename `heat`), HyperOS/**Android 15**, чип **MediaTek** |
| Камера | Touptek E3ISPM, **USB3.0**, ~20 Мп. `VID 0x0547 PID 0x1123` (на телефоне, USB3) / `0x1122` (на ноуте — вероятно USB2-режим) |
| USB-дескриптор | `TP/USB3.0 Camera`, интерфейс **класс 255 (vendor-specific, НЕ UVC)**, 1 bulk-IN эндпоинт `0x81` |
| Разрешения | preview **1824×1216**, still **5440×3648** |
| Приложение | `com.siams.kern_prototype` (debug APK) |
| Отладка | ноут (Linux) ↔ телефон по **adb over Wi-Fi** через хотспот телефона |

---

## 2. Инфраструктура отладки (adb по Wi-Fi через хотспот)

Телефон раздаёт Wi-Fi-хотспот на ноут; USB-порт телефона занят камерой, поэтому adb по USB
недоступен во время теста → поднимаем **adb по Wi-Fi**, чтобы канал пережил отключение кабеля.

**Как поднять** (пока телефон воткнут в ноут кабелем):
```bash
ADB=$HOME/Android/Sdk/platform-tools/adb
GW=$(ip route | awk '/^default/ && /wlp2s0/ {print $3; exit}')   # IP телефона = шлюз хотспота
$ADB -s <usb-serial> tcpip 5555
$ADB connect $GW:5555
$ADB -s $GW:5555 shell getprop ro.product.model   # проверка
# дальше кабель можно выдернуть, воткнуть камеру — канал живёт
```

**Грабли инфраструктуры:**
- `adb tcpip 5555` **НЕ переживает перезагрузку телефона** — после ребута поднимать заново по USB.
- IP телефона (шлюз хотспота) **меняется на каждый ребут**: за сессию было
  `10.78.236.40 → 10.82.117.11 → 10.178.79.176`. Всегда определять заново.
- «Беспроводная отладка» (Android 11+) по mDNS на хотспоте не вещала — работал только классический `tcpip`.

**Захват логов камеры** (полный лог в файл + камерное на экран), фильтр по тегам
`ToupcamController / JNI_CAM / CameraController / drilling-upload` + USB + крэши:
```bash
$ADB -s $GW:5555 logcat -c
$ADB -s $GW:5555 logcat -v threadtime | tee full.log \
  | grep --line-buffered -Ei 'ToupcamController|JNI_CAM|UsbHost|UsbDevice|AndroidRuntime|FATAL|DEBUG |toupcam|tombstone|kern_prototype'
```

---

## 3. Проблемы и борьба (хронологически)

### 3.1. «Камера не находится» через хаб Harper HUB-04P
**Симптом:** телефон не видит камеру через хаб.
**Что за хаб:** обычный **USB 3.0 хаб для ПК**, вход **USB-A** (upstream), 4 порта USB-A
(downstream), доп. питание Micro-USB. НЕ OTG-хаб для телефона.
**Диагностика (по логам):** ни одного `UsbHostManager: USB device attached`; батарея
`plugType:2` (BATTERY_PLUGGED_USB, т.е. телефон **заряжается**), хост-режим (xhci/dwc3) не
включается. → Телефон был в режиме **устройства/зарядки**, а не хоста.
**Причина:** перепутаны порты. Варианты ошибки:
- телефон воткнут в **downstream**-порт (тогда телефон — устройство, заряжается, не хост);
- upstream-кабель хаба воткнут **в ноут** (тогда хост = ноут, и **ноут** видел камеру `0547:1122`).

**Правильная разводка (телефон = хост):**
```
телефон (USB-C) → OTG-переходник (USB-A «мама»→USB-C) → upstream-кабель хаба (USB-A «папа»)
хаб: передний (downstream) порт → камера;  Micro-USB → питание
```
**Итог:** после правильной разводки камера **определилась** (`USB device attached vidpid 0547:1123`).

### 3.2. «Телефон выключается» → на самом деле KERNEL PANIC
**Симптом:** телефон «сам выключается» через ~70 с работы камеры и при нажатии «снимок».
Сначала грешили на питание OTG (в OTG-хосте телефон не заряжается) — **это оказалось неверно**.
**Диагностика:** батарея была **89%** — не разрядка. Причина ребута:
```
ro.boot.bootreason: kernel_panic
sys.boot.reason:     kernel_panic,bug
```
Лог ядра **без рута** достаётся из dropbox (pstore/last_kmsg — Permission denied):
```bash
adb shell "dumpsys dropbox --print SYSTEM_LAST_KMSG" > last_kmsg.txt
```
**Точная паника (идентична во всех 3 ребутах):**
```
usercopy: Kernel memory exposure attempt detected from page alloc (offset 0, size 2047290368)!
kernel BUG at mm/usercopy.c:102!
Call trace:
  usercopy_abort
  __check_object_size
  copy_urb_data_to_user      ← копирование данных USB-URB в user-space
  processcompl
  usbdev_ioctl               ← usbdevfs ioctl (реап URB)
  __arm64_sys_ioctl
```
**Причина:** SDK камеры (закрытый `libtoupcam.so` через libusb/usbdevfs) при реапе USB-URB
просит ядро скопировать **~1.9 ГБ** (`2 047 290 368` байт — мусорный/переполненный размер;
реальный кадр ≤ ~60 МБ). Ядро Android 15 на MTK собрано с **CONFIG_HARDENED_USERCOPY** →
вместо возврата ошибки делает `BUG()` → **kernel panic → ребут**. Триггер — стрим USB3-камеры
и/или `snap()` 20 Мп. **Наш Kotlin/JNI-код ни при чём** — баг в связке ToupCam SDK ↔ ядро.

### 3.3. «Чёрный экран» → кадры не приходят
**Симптом:** камера открывается, но превью чёрное.
**Диагностика (после инструментального билда, см. §4):**
- `JNI_CAM openDevice: preview 1824x1216, bandwidth=25% framerate=8` — камера открылась,
  `StartPullModeWithCallback` без ошибок;
- **ни одного `EVENT_IMAGE`, ни одного `pullImage: frame`** → **SDK не отдаёт ни единого кадра**;
- камера дважды переподключалась (два `USB device attached`) — нестабильный USB.
**Вывод:** control-запросы (open, опции, размеры) проходят, а **bulk-поток кадров по USB3 не идёт**
(нет данных / битые — тот же мусорный URB, что валил ядро). Проблема в **USB3-транспорте**
(SuperSpeed на OTG телефона через дешёвый хаб хрупок). Это НЕ проблема Surface.

### 3.4. Тест камеры на ноуте (Linux) — РЕШАЮЩИЙ, изолировал «камера vs телефон»
**Зачем:** отделить неисправность камеры от проблемы телефона.
**Как:** официальный `libtoupcam.so` (x86_64) + мини-программа на C (open → pull-режим →
счётчик кадров → сохранить кадр). Либа и заголовок взяты из репо INDIGO
(`indigo-astronomy/indigo`, `ccd_touptek/bin_externals/libtoupcam`), поддерживает нашу
`E3ISPM20000KPA`. На Linux нужен **root** (иначе `StartPullModeWithCallback` → `hr=0x80070005`
E_ACCESSDENIED) — запускали под `sudo`.
**Результат (однозначный):**
```
Найдено камер: 1  → E3ISPM20000KPA  id=usb-0547-1122-2-2
Поток запущен...  → 28 кадров за 6 c, 0 ошибок, разрешение 5440x3648 (20 Мп)
КАМЕРА СТРИМИТ. Сохранён реальный кадр (frame.ppm).
```
**Вывод:** **камера/кабель/SDK исправны.** Та же камера + тот же `libtoupcam` на обычном
Linux-ядре стримит идеально → блокер именно **телефон** (его ядро паникует на USB-пути,
который нормальный хост переваривает). Важная деталь модельной таблицы ToupTek:
`0x1122 = E3ISPM20000KPA (USB3.0)`, `0x1123 = E3ISPM20000KPA (USB2.0)` — на телефоне камера
была `1123` (**уже USB2**) и всё равно падала; на ноуте `1122` (USB3) и работает. Значит
«просто перейти на USB2» телефон не спасёт — проблема в ядре/USB-стеке телефона, не в скорости шины.

Тестовые артефакты (в скретчпаде сессии, `scratchpad/touptest/`): `touptest.c`, `touptest`
(бинарь), `libtoupcam.so` (x64), `toupcam.h`, `frame.ppm/frame_view.png`,
`toupcamsdk.20260120.zip` (полный офиц. SDK, 242 МБ). SDK download (актуальный):
`https://www.touptekphotonics.com/downloads/software/download.php?soft=toupcamsdk`.

---

## 4. Изменения в коде (применены в рабочем дереве)

Цель: (а) не доводить USB до паники, (б) видеть поток кадров.

**`app/src/main/jni/jnicam.cpp`:**
- `PREVIEW_MAX_WIDTH` 2048 → **1280** (у камеры нет режима уже 1280 → превью осталось 1824×1216 — фолбэк на минимальный).
- До `StartPullModeWithCallback`: `Toupcam_put_Option(TOUPCAM_OPTION_BANDWIDTH, 25)` и
  `Toupcam_put_Option(TOUPCAM_OPTION_FRAMERATE, 8)` — режем USB-трафик.
- Лог `JNI_CAM` (добавлен `LOGI`): разрешение/опции при старте + счётчик кадров и `HRESULT`
  в `pullImage`.

**`app/src/main/java/.../camera/ToupcamController.kt`:**
- `previewSurface` / `previewBitmap` / `stillBitmap` → **`@Volatile`** (межпоточная видимость
  между потоком событий SDK и `startSession`/`open`).
- В `onSdkEvent(EVENT_IMAGE)` — лог: отличает «событие не приходит» от «нет поверхности».

Сборка/установка:
```bash
./gradlew :app:assembleDebug            # BUILD SUCCESSFUL ~14 c (инкрементально)
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Текущий статус (после теста на ноуте — РЕШЕНО, кто виноват)

| | Ноут (Linux) | Телефон (Redmi Note 12, Android 15/MTK) |
|---|---|---|
| Открытие камеры | ✅ | ✅ |
| Поток кадров | ✅ **28 кадров/6с, 0 ошибок, 20 Мп** | ❌ 0 кадров |
| Стабильность | ✅ | ❌ **kernel panic → ребут** |

- ✅ **Камера/кабель/SDK исправны** — доказано живым стримом на ноуте.
- ✅ На телефоне **kernel panic побеждён** ограничением полосы/fps (bandwidth=25%, framerate=8),
  но **кадров всё равно 0** — bulk-поток на этом телефоне не идёт.
- ⛔ **Блокер — сам телефон** (ядро MTK/Android 15 с hardened usercopy падает на USB-пути,
  который нормальный хост переваривает). Из приложения это НЕ чинится.

---

## 6. Что дальше (после того как виноват определён — телефон)

**Отпавшие версии:** питание батареи (было 89%), наш код (SDK-проводка верна), Surface
(кадры не доходят раньше), «просто перейти на USB2» (на телефоне камера УЖЕ была USB2 = PID
`1123` и всё равно падала — дело в USB-стеке/ядре телефона, не в скорости шины).

**Реальные варианты (по приоритету):**

1. **Другое Android-устройство** — самый быстрый способ разблокироваться, если продукт остаётся
   на Android. Нужно устройство, чьё ядро НЕ делает `BUG()`-панику на `copy_urb_data_to_user`.
   Кандидаты: другой SoC (Qualcomm Snapdragon вместо MediaTek), планшет, устройство с менее
   «жёстким» ядром. Тест-рецепт: поставить debug-APK, воткнуть камеру по OTG, открыть камеру
   в приложении; смотреть `adb shell "getprop | grep boot.reason"` (ребут?) и логкат
   (`JNI_CAM`/`ToupcamController` — пошли ли `EVENT_IMAGE`). ⚠️ Гарантий нет — лотерея по ядру.

2. **Репорт в ToupTek (первопричина).** Их закрытый `libtoupcam.so` при реапе USB-URB просит
   ядро скопировать ~1.9 ГБ (мусорный размер) → на ядрах с `CONFIG_HARDENED_USERCOPY` это
   `BUG()`/паника. Приложить: модель `E3ISPM20000KPA`, Android 15/MediaTek, версию SDK,
   `SYSTEM_LAST_KMSG` (§3.2). Фикс в SDK разблокировал бы все строгие ядра.

3. **Сменить хост-архитектуру.** Камера безупречно работает на обычном Linux → капчур-устройством
   мог бы стать мини-ПК/SBC/планшет на Linux. Это уже продуктовое решение (приложение — Android),
   вне рамок отладки, но технически самый надёжный путь.

**Питание (для любого рабочего варианта):** в OTG-хосте телефон НЕ заряжается → на долгой съёмке
сядет. Нужен **USB-C OTG-хаб/док с PD-passthrough** (хостит И заряжает) или OTG-сплиттер
«устройство + зарядка». Обычный USB-A хаб (Harper HUB-04P) так не умеет + путает порты (§3.1).

**Тюнинг (если найдётся рабочее устройство), в `jnicam.cpp`:** `TOUPCAM_OPTION_BANDWIDTH`
(0x2e, 1-100%), `TOUPCAM_OPTION_FRAMERATE` (0x11), `Toupcam_put_Speed`; диагностика
`TOUPCAM_OPTION_NUMBER_DROP_FRAME` (0x3e).

---

## 7. Шпаргалка (полезные факты и команды)

```bash
# причина последнего ребута
adb shell "getprop | grep -iE 'boot.?reason'"
# лог ядра до ребута (без рута)
adb shell "dumpsys dropbox --print SYSTEM_LAST_KMSG" > last_kmsg.txt
# камера в системе (телефон-хост)
adb shell dumpsys usb | grep -i camera        # или lsusb на ноуте
# уровень батареи (санити при «выключениях»)
adb shell "dumpsys battery | grep -iE 'level|status'"
```

- Теги логов: `ToupcamController` (Kotlin), `JNI_CAM` (нативка), `CameraController`
  (Camera2, запасной путь), `drilling-upload` (загрузка).
- Коды событий toupcam.h: `EVENT_IMAGE=0x0004`, `EVENT_STILLIMAGE=0x0005`,
  `EVENT_ERROR=0x0080`, `EVENT_DISCONNECTED=0x0081`.
- Опции: `TOUPCAM_OPTION_BANDWIDTH=0x2e`, `TOUPCAM_OPTION_FRAMERATE=0x11`,
  `TOUPCAM_OPTION_NUMBER_DROP_FRAME=0x3e`, `TOUPCAM_OPTION_RGB` (0=RGB24).

---

## 8. РАЗВЯЗКА: рабочее устройство найдено (2026-08-25)

Тест на **Samsung Galaxy A57 (SM-A576B, Exynos 1380 `s5e8865`, Android 16)** — та же камера,
тот же APK (bandwidth=25%/fps=8):
- камера определилась (`0547:1123`), `JNI_CAM: pullImage: frame #4410 ok`, `EVENT_IMAGE ... рисуем`;
- **>4400 кадров, 0 ошибок, >1 минуты непрерывного стрима, БЕЗ паники/ребута** — живое превью.
→ Exynos-ядро переваривает тот же USB-путь, на котором MediaTek делает `BUG()`. **Тупик снят.**
Собран тест-APK `kern-camera-test-YYYYMMDD.apk` (в корне репо) для передачи в тест.

Проверка «изза чего на Redmi не завелась НИ ОДНА камера» (пробовали 5/7/12/20 Мп):
причина **camera-agnostic** — все камеры Touptek используют один `libtoupcam` + один usbdevfs-путь,
а падает USB-драйвер/ядро телефона независимо от разрешения. Мегапиксели ни при чём.
(Первопричина «1.9 ГБ URB» — баг USB-драйвера MediaTek, превращённый в ребут `CONFIG_HARDENED_USERCOPY`.)

## 9. Рекомендации по выбору телефона (для пользователей приложения)

| SoC / устройство | Проверено | Итог |
|---|---|---|
| **MediaTek** (Redmi Note 12) | да | ⛔ kernel panic, ВСЕ камеры (5/7/12/20 Мп) |
| **Samsung Exynos** (Galaxy A57, Android 16) | да | ✅ работает, стабильный стрим |
| **Qualcomm Snapdragon** | нет | вероятно ОК (зрелый USB-стек) — проверять |
| **ПК/ноут x86 Linux** | да | ✅ работает (эталон) |

Правила:
- ⛔ **Избегать MediaTek** (Dimensity/Helio) — подтверждённый ребут на стриме камеры.
- ✅ Брать **Samsung Exynos** (A57 — проверено) или **Qualcomm Snapdragon**.
- 📌 **Версия Android не важна** — важен SoC/вендор ядра (A57 = Android 16, новее Redmi, и работает).
- 📌 **Стандартизировать ОДНУ проверенную модель** на всё развёртывание. Совместимость —
  «лотерея по ядру»; гарантия только через тест конкретной модели.
- ⚠️ Внутри одного бренда SoC бывает разный (регионы/ревизии) — смотреть **чип**, не бренд.
- Обязательно USB-OTG/host-режим; для долгой съёмки — OTG-хаб с питанием (PD): в OTG телефон не заряжается.

Валидация нового телефона перед закупкой: поставить тест-APK → камера по OTG → открыть приложение.
**PASS** = живое превью, без ребута. **FAIL** = ребут/чёрный экран (проверить
`adb shell getprop sys.boot.reason` → `kernel_panic`).
