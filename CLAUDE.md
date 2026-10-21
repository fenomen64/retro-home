# Retro Home — правила для Claude

Android-лаунчер для Retroid Pocket G2: горизонтально — консольный режим, вертикально — телефонный.
Автор — продакт-менеджер из Украины (не из России). Общаться **на русском**. Сборки, тесты и релизы делает Claude.
Недельный лимит у автора небольшой: работать экономно, не пересказывать лишнего, не перечитывать большие файлы без нужды.

## Стек
Kotlin + Jetpack Compose, AGP 8.13, Kotlin 2.2, Gradle 8.14, JDK 21, minSdk 29 / targetSdk 35.
Тесты: JUnit + Robolectric 4.16 + Roborazzi (скриншоты в `app/build/screenshots`).
Код: `app/src/main/java/com/retrohome/launcher` (`data/` — логика, `ui/` — экраны, `ui/console/` — консольный режим, `i18n/Strings.kt` — тексты RU/EN).

## Как выпускать обновление
1. Поднять `versionCode`/`versionName` в `app/build.gradle.kts` и `MetaRepository.VERSION`.
2. Добавить раздел `## x.yy` в `CHANGELOG.md` (простым языком, для пользователя).
3. `./gradlew testDebugUnitTest assembleDebug` — всё зелёное.
4. Коммит (автор — noreply-адрес `fenomen64@users.noreply.github.com`), push в `main`.
5. GitHub Actions (`release.yml`) сам собирает подписанный APK и публикует релиз `v<версия>` с файлом `RetroHome-<версия>.apk`.
   Дождаться, скачать APK из релиза, проверить версию и подпись (SHA-256 сертификата
   `27781f7f13c9011ad333b057127030a7df6b887872ac095dbd7265b9df19c053`) и прислать файл автору.

## Нельзя
- Публиковать ключ подписи, пароли, `keystore/`, `keystore.properties`, любые секреты. Ничего конфиденциального в репозиторий.
- Отправлять email автора или ключи RetroAchievements куда-либо, кроме их сервисов.
- Обходить заблокированные сайты.
- Использовать чужие логотипы, персонажей и товарные знаки в графике (только свои придуманные образы).

## Приставка автора (adb)
Если сессия связана с компьютером автора (Windows) и там есть `adb`, можно ставить сборку
на Retroid (`adb install -r`), снимать скриншоты (`adb exec-out screencap -p`), видео (`adb shell screenrecord`),
логи (`adb logcat`), нажимать кнопки (`adb shell input keyevent`) и мерить FPS (`adb shell dumpsys gfxinfo`).

## Текущий большой проект
3D «игровая комната» для консольного режима — см. `docs/room3d.md` (план, решения, сообщения для каждого этапа).
