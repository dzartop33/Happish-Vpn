# Сборка APK через GitHub Actions

Этот вариант нужен, если нет ПК или не хочется локально ставить Go/Android SDK/NDK. GitHub сам соберёт `libbox.aar` и APK в облаке.

## 1. Загрузить проект на GitHub

Создай новый репозиторий на GitHub и загрузи туда содержимое папки `happish-android`.

Важно: в корне репозитория должны лежать файлы:

```text
app/
build.gradle.kts
settings.gradle.kts
scripts/
.github/workflows/android-build.yml
```

## 2. Запустить сборку

На GitHub открой:

```text
Actions → Build Happish Android APK → Run workflow
```

Параметры:

- `sing_box_tag` — можно оставить пустым, тогда будет взят последний stable tag sing-box.
- `build_libbox` — оставь `true`, чтобы GitHub сам собрал `libbox.aar`.
- `build_release` — `false` для обычного debug APK, `true` для подписанного release APK. Для release нужны GitHub Secrets, см. `RELEASE_SIGNING.md`.

Нажми зелёную кнопку **Run workflow**.

## 3. Скачать APK

Когда сборка закончится:

```text
Actions → последний запуск → Artifacts
```

Там будут файлы:

```text
happish-debug-apk
libbox-aar
```

Если запускал с `build_release=true`, дополнительно будет:

```text
happish-release-apk
```

Скачай `happish-debug-apk`, внутри будет APK примерно такого вида:

```text
app-debug.apk
```

Его можно установить на Android-телефон.

## 4. Если сборка libbox упала

Возможные причины:

- изменилась команда сборки в новой версии sing-box;
- GitHub не успел скачать зависимости;
- выбранный tag sing-box несовместим;
- изменилась версия Android NDK.

Тогда попробуй запустить workflow с конкретным tag, например:

```text
v1.12.0
```

Если не поможет — нужно открыть лог GitHub Actions и посмотреть, на каком шаге упало.

## 5. Release APK

Workflow уже умеет собирать signed release APK:

```text
assembleRelease
```

Для этого нужно добавить keystore в GitHub Secrets и запустить workflow с:

```text
build_release: true
```

Подробная инструкция находится в:

```text
RELEASE_SIGNING.md
```
