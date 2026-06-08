# Happish VPN — Android MVP

MVP Android-клиента в стиле Happ/Hiddify: красивый Compose UI, импорт подписок по ссылке/тексту, парсинг серверов и генерация sing-box config.

## Что уже сделано

- Android Kotlin + Jetpack Compose + Material 3.
- Главный экран с большой кнопкой подключения, списком серверов и настройками.
- Импорт HTTP(S) subscription URL или вставленных ссылок.
- Парсер форматов:
  - `vless://`
  - `vmess://`
  - `trojan://`
  - `ss://`
  - `hy2://` / `hysteria2://`
  - base64 subscription со списком ссылок.
- Генератор `sing-box` JSON config под TUN inbound.
- Android `VpnService` + foreground service.
- Сохранение subscription URL, списка серверов, выбранного сервера и настроек через SharedPreferences.
- Автообновление HTTP(S)-подписки при запуске, если последняя загрузка была больше 6 часов назад.
- Проверка задержки серверов через TCP connect, отображение `ms/timeout`, сортировка по скорости и автовыбор самого быстрого сервера.
- UI-блок логов: импорт, проверка серверов, запуск/остановка VPN, события sing-box/libbox и ошибки.
- GitHub Actions умеет собирать debug APK и signed release APK через GitHub Secrets.
- Split tunneling по приложениям: выбор приложений, режим «только выбранные через VPN» или «выбранные мимо VPN», передача include/exclude package rules в libbox.
- QR-импорт подписок и отдельных server links через камеру.
- DNS-настройки: System, Cloudflare DoT, Google DoT, AdGuard DoT и Custom DNS; выбранный DNS попадает в sing-box config.
- Режимы маршрутизации: Global, Bypass LAN, Rule-based и Direct; выбранный режим попадает в sing-box route config.
- Улучшенная генерация sing-box config для Reality, uTLS fingerprint, WebSocket, gRPC, HTTP/2, HTTPUpgrade и xHTTP/splitHTTP.
- Поиск и фильтры серверов: по имени/host/protocol, избранные, рабочие, timeout, VLESS, VMess, Trojan, Shadowsocks, Hy2.
- Избранные серверы со звёздочкой и закреплением сверху списка.
- Импорт подписок/ссылок из буфера обмена одной кнопкой.
- Share intent: можно отправить текст/ссылку в Happish VPN через «Поделиться», а также открывать `vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://` ссылки.
- Экспорт логов: копирование логов в буфер обмена и отправка через системное меню Share.
- Quick Settings Tile: плитка в шторке Android для быстрого GO/STOP выбранного сервера.
- R8/ProGuard rules для release-сборки: keep-правила для libbox, Go mobile runtime, ZXing и reflection adapter.
- Статистика сессии в UI: download/upload, текущая скорость и длительность подключения.
- Улучшенное foreground-уведомление: открытие приложения по нажатию и кнопка «Отключить» прямо из уведомления.
- Карточка информации о выбранном сервере: protocol, host, port, UUID/method и важные transport/TLS/Reality параметры.
- Экспорт/импорт профиля настроек JSON через буфер обмена: подписка, серверы, DNS, routing, split tunneling и избранное.
- Kill switch помощник: открывает Android VPN settings и объясняет Always-on VPN / Block connections without VPN.
- Проверка обновлений через GitHub Releases по репозиторию `owner/repo`.
- Импорт Clash YAML и sing-box JSON подписок/конфигов базового уровня.
- Виджет рабочего стола Android с кнопкой GO/STOP.
- Локальный crash reporting: сохранение последнего crash report, копирование и отправка.
- Навигация по разделам: Главная, Серверы, Настройки, Логи.
- Защита локальных настроек через EncryptedSharedPreferences с fallback на обычные SharedPreferences.
- Проверка внешнего IP после подключения и вручную из UI.
- Ограничения размера подписки и количества импортируемых серверов для защиты от тяжёлых/битых подписок.

## Важно про «реальное подключение»

Android `VpnService` сам по себе только создаёт TUN-интерфейс. Чтобы реально подключаться к VLESS/VMess/Trojan/Shadowsocks/Hysteria2, нужен сетевой core — обычно `sing-box/libbox`, как в sing-box-for-android/Hiddify-подобных клиентах.

В проекте уже есть слой интеграции:

- `core/SingBoxConfigGenerator.kt` — делает JSON конфиг.
- `core/HappishVpnService.kt` — запускает VPN service.
- `core/SingBoxCoreAdapter.kt` — место подключения `libbox.aar`.
- `app/libs/` — сюда кладётся `libbox.aar`.

Если `libbox.aar` не добавлен, приложение честно покажет ошибку при подключении. После добавления AAR используется реальный `CommandServer` sing-box и настоящий TUN через `VpnService.Builder`.

## Сборка

Открой папку `happish-android` в Android Studio Iguana/Koala или новее и нажми **Run**.

CLI после создания Gradle Wrapper в Android Studio или командой `gradle wrapper`:

```bash
cd happish-android
./gradlew assembleDebug
```

В текущем workspace wrapper `.jar` не добавлен, поэтому проще открыть проект в Android Studio — она скачает нужный Gradle/Android Gradle Plugin.

## Как добавить sing-box core

Слой реального подключения уже реализован в `SingBoxCoreAdapter.kt` через runtime-интеграцию с `io.nekohasekai.libbox`:

- создаётся `CommandServer`;
- создаётся `CommandServerHandler`;
- создаётся `PlatformInterface`;
- `openTun(TunOptions)` открывает настоящий Android TUN через `VpnService.Builder`;
- `autoDetectInterfaceControl(fd)` вызывает `protect(fd)`, чтобы не было VPN-loop;
- `startOrReloadService(configJson, OverrideOptions())` запускает sing-box.

Осталось добавить сам native core:

### Вариант A — готовый AAR

1. Положить совместимый `libbox.aar` в `app/libs/libbox.aar`.
2. Пересобрать APK.
3. Запустить на реальном устройстве и проверить подписку.

### Вариант B — собрать из исходников

```bash
cd happish-android
./scripts/sync-libbox.sh          # latest stable tag
# или
./scripts/sync-libbox.sh v1.12.0  # конкретная версия
```

Скрипт скачает `SagerNet/sing-box`, соберёт Android `libbox.aar` и скопирует его в `app/libs/libbox.aar`.

> Требования для сборки core: Go, Android SDK/NDK, Java, git. У разных версий sing-box build flags могут слегка отличаться.

Для production-версии нужно ещё добавить:

- URLTest через sing-box core для более точной проверки задержки.
- Полноценные профили в Room/DataStore вместо простого SharedPreferences-хранилища.
- Улучшенный split tunneling: поиск приложений, системные приложения, профили правил.
- Автообновление подписок.
- Расширенный экран логов с фильтрами, поиском и сохранением в файл.
- Расширенные DNS-режимы: FakeIP, DoH templates, DNS rules по доменам.
- Расширенную маршрутизацию: geosite/geoip rules, bypass по странам, block ads и пользовательские правила.
- Более полную обработку Clash/sing-box JSON/YAML: groups, rules, providers, full outbound fields.
- Улучшенный QR-импорт: импорт из изображения/галереи и история сканов.
- Внешний crash reporting через Sentry/Firebase и полноценный release pipeline с версиями/changelog.

## Структура

```text
app/src/main/java/ai/arena/happish/
├── MainActivity.kt                  # Compose UI
├── data/
│   ├── ProxyServer.kt               # модель сервера
│   └── SubscriptionParser.kt        # импорт/парсинг ссылок
└── core/
    ├── ConnectionController.kt      # старт/стоп из UI
    ├── HappishVpnService.kt         # Android VpnService
    ├── SingBoxConfigGenerator.kt    # sing-box JSON
    └── SingBoxCoreAdapter.kt        # интеграция libbox
```
