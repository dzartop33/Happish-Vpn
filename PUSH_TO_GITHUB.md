# Как загрузить Happish VPN в GitHub

Репозиторий:

```text
https://github.com/dzartop33/Happish-Vpn.git
```

## Вариант через ПК / Termux

Перейди в папку проекта:

```bash
cd happish-android
```

И выполни:

```bash
git init
git add .
git commit -m "Initial Happish VPN"
git branch -M main
git remote add origin https://github.com/dzartop33/Happish-Vpn.git
git push -u origin main
```

Если GitHub попросит логин/пароль:

- логин: твой GitHub username;
- пароль: не обычный пароль, а GitHub token.

## Если remote origin уже существует

Вместо `git remote add origin ...` выполни:

```bash
git remote set-url origin https://github.com/dzartop33/Happish-Vpn.git
git push -u origin main
```

## Если репозиторий не пустой и push не проходит

Если GitHub пишет, что в репозитории уже есть файлы, сначала попробуй:

```bash
git pull origin main --rebase
git push -u origin main
```

Если это новый пустой репозиторий, но там случайно создан README/LICENSE и ты хочешь заменить всё содержимым проекта, можно сделать force push:

```bash
git push -u origin main --force
```

Используй `--force` только если точно понимаешь, что можно перезаписать содержимое репозитория.

## После загрузки

Открой на GitHub:

```text
Actions → Build Happish Android APK → Run workflow
```

Поставь:

```text
sing_box_tag: пусто
build_libbox: true
build_release: false
```

Нажми:

```text
Run workflow
```

После успешной сборки скачай artifact:

```text
happish-debug-apk
```

Внутри будет APK.
