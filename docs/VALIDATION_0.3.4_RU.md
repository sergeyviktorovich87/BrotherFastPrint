# Проверки 0.3.4 Preview

## Изменение и основание

Свежий пользовательский лог подтверждает documents_bayer8 в фактически отправленном задании 0.3.3; новый фото-профиль ещё не проверен. До его принятия queue/probe timers существенно опоздали, обработка началась рядом с открытием Activity. После принятия подготовка и передача заняли около 5.94 секунды. Разница часов Handler/elapsed не позволяет объявить опоздание точной длительностью блокировки main thread. Подробности и границы вывода: [BACKGROUND_0.3.4_RU.md](BACKGROUND_0.3.4_RU.md).

## Автоматические проверки

- Итоговый GitHub Actions run `41319331934`, source commit `23728fa3f87be3fe7686a89cd45e610138c646ac`: native checks, assembleDebug, testDebugUnitTest, assembleDebugAndroidTest, lintDebug — успешно.
- HTML-отчёт: **65 JVM-тестов, 0 failures, 0 ignored**. Пять новых тестов DispatchDeadline отделяют глубокий сон от задержки при бодрствовании, смешанные интервалы, нормальный вызов и переполнение срока.
- **11 instrumented-тестов собраны, не запускались.** Новый сценарий проверяет, что companion service не экспортирована, имеет тип connectedDevice и разрешение WAKE_LOCK; реальный Android foreground startup этим не проверяется.
- Native-набор и независимые HBP round trips 1/10/50 A4/duplex прошли в CI. Нативный код не менялся; отдельный повтор ASan/UBSan не выполнялся.
- Artifact `13089100601` скачан и сверен с digest GitHub до извлечения APK.

## APK

- BrotherFastPrint-0.3.4-preview-arm64.apk, **1538137 байт**.
- SHA-256 `0057db3e502ce8f2bb47d499150627e56b49f9f965fb0b521c3ae34af67f015f`.
- Package ru.brotherfastprint.preview; versionCode 10; versionName 0.3.4-preview; minSdk 26; target/compile 36; arm64-v8a. Проверено aapt2.
- apksigner verify: v2/v3 успешно; SHA-256 сертификата `f7f04543fc5d214e8c4c00534b9a86b2c90475520deb24e7d83e599e19dc1944` совпадает с установленными Preview.
- zipalign `-c -P 16 4` успешно; три ELF LOAD сегмента имеют alignment 16384. libfastprint.so побайтно совпадает с 0.3.3.
- Manifest содержит прежние четыре сетевых разрешения и три новых обычных: FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, WAKE_LOCK. POST_NOTIFICATIONS не добавлено. Companion service неэкспортирована; framework PrintService по-прежнему защищена BIND_PRINT_SERVICE.
- Ключ находится только вне исходников, в локальном защищённом каталоге; в git/Actions он не передавался. Пользовательский лог и фотографии не входят в исходники.

## Непроверенное

Фактический запуск FGS из системного binding на Samsung, разрешение/отказ ОС, удержание CPU, исчезновение паузы, установка обновления на телефон и качество фото требуют аппаратной проверки. Совпадение package/сертификата и рост versionCode позволяют ожидать обновление поверх 0.3.3, но не заменяют выполненную установку. Новый лог должен содержать keepalive_foreground_started/wake_acquired либо явные категории отказов. Политики Samsung/Android/VPN автоматически не меняются; неопределённое задание не переотправляется.
