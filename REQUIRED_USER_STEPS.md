# Что обязательно нужно подключить вручную

Некоторые вещи нельзя полностью сделать внутри этого workspace без твоего участия.

## 1. Реальный VPN core: libbox.aar

Без `libbox.aar` приложение соберёт интерфейс, импорт, настройки и сервисы, но реальное VLESS/VMess/Trojan/Shadowsocks/Hysteria2 подключение не заработает.

Нужно получить файл:

```text
app/libs/libbox.aar
```

### Лучший способ без ПК

1. Загрузи проект на GitHub.
2. Открой `Actions → Build Happish Android APK`.
3. Запусти workflow с параметрами:

```text
build_libbox: true
build_release: false
```

GitHub соберёт `libbox.aar` и APK.

### Если есть ПК

```bash
cd happish-android
./scripts/sync-libbox.sh
```

Нужны Go + Android SDK + Android NDK + Java.

## 2. Release APK подпись

Для нормальной release-сборки нужно создать keystore и добавить Secrets на GitHub.

Смотри:

```text
RELEASE_SIGNING.md
```

Нужные GitHub Secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## 3. Репозиторий для обновлений

В приложении есть проверка обновлений через GitHub Releases. Нужно создать репозиторий и публиковать APK в Releases.

В приложении укажи:

```text
owner/repository
```

Например:

```text
myname/happish-vpn
```

## 4. Тест на реальном телефоне

VPN нельзя полноценно проверить в preview. Нужно реальное Android-устройство:

1. Установить APK.
2. Дать VPN-разрешение.
3. Импортировать подписку.
4. Проверить серверы.
5. Нажать GO.
6. Проверить внешний IP в карточке `Проверка IP`.

## 5. Что прислать мне, если сборка/подключение упадёт

Скопируй и пришли:

- лог GitHub Actions, если не собралось;
- логи из карточки `Логи`;
- crash report из карточки `Crash reporting`;
- тип ссылки, которая не импортируется: VLESS/VMess/Trojan/SS/Clash/sing-box.

Не присылай публично реальные UUID/password/token подписки без маскирования.
