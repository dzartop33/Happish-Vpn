# Release APK через GitHub Actions

Debug APK подходит для теста. Для нормальной установки и распространения лучше собирать **signed release APK**.

В проект уже добавлена поддержка release-подписи через GitHub Secrets.

## 1. Создать keystore

На ПК или в облачном терминале с Java:

```bash
keytool -genkeypair \
  -v \
  -keystore happish-release.keystore \
  -alias happish \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Запомни пароли. Их нельзя терять: обновления Android-приложения должны подписываться тем же ключом.

## 2. Закодировать keystore в base64

Linux/macOS:

```bash
base64 -w 0 happish-release.keystore > keystore-base64.txt
```

Если `-w` не поддерживается:

```bash
base64 happish-release.keystore | tr -d '\n' > keystore-base64.txt
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("happish-release.keystore")) | Set-Content keystore-base64.txt
```

## 3. Добавить GitHub Secrets

В репозитории GitHub открой:

```text
Settings → Secrets and variables → Actions → New repository secret
```

Добавь 4 секрета:

```text
ANDROID_KEYSTORE_BASE64     содержимое keystore-base64.txt
ANDROID_KEYSTORE_PASSWORD   пароль keystore
ANDROID_KEY_ALIAS           happish
ANDROID_KEY_PASSWORD        пароль ключа
```

## 4. Запустить workflow

Открой:

```text
Actions → Build Happish Android APK → Run workflow
```

Поставь:

```text
build_libbox: true
build_release: true
```

`sing_box_tag` можно оставить пустым или указать конкретный tag, например:

```text
v1.12.0
```

## 5. Скачать release APK

После успешной сборки открой последний workflow run и скачай artifact:

```text
happish-release-apk
```

Внутри будет:

```text
HappishVPN-release.apk
```

Это подписанный release APK.

## Важно

- Не публикуй `.keystore` файл в GitHub.
- Не отправляй пароли от keystore другим людям.
- Если потерять keystore, пользователи не смогут обновиться поверх старой версии приложения.
