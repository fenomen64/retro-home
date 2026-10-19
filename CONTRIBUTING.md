# Как помочь проекту / Contributing

Спасибо за интерес к Retro Home!

## Сообщить об ошибке
Откройте [Issue](../../issues) и укажите:
- устройство и версию Android;
- версию лаунчера (Настройки → внизу);
- что делали, что ожидали и что произошло; скриншот очень помогает.
- если не нашлось или неверное описание игры: скриншот «X → Как искалось описание».

## Предложить изменение
1. Сделайте форк и ветку от `main`.
2. Проверьте, что тесты проходят: `./gradlew testDebugUnitTest`.
3. Для изменений интерфейса приложите снимки «до/после»
   (`./gradlew testDebugUnitTest -Proborazzi.test.record=true` → `app/build/screenshots`).
4. Откройте Pull Request с описанием, что и зачем изменено.

Комментарии в коде на русском; новые можно писать на русском или английском.
Все тексты интерфейса — в `app/src/main/java/com/retrohome/launcher/i18n/Strings.kt` (RU и EN).

## Чего не принимаем
Ссылок на игры, BIOS и другие материалы, нарушающие авторские права.

---

**English:** bug reports and pull requests are welcome. Please run `./gradlew testDebugUnitTest` before submitting,
attach before/after screenshots for UI changes, and never include links to ROMs or BIOS files.
By contributing you agree that your contribution is licensed under GPL-3.0.
