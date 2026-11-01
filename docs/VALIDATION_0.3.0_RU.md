# BrotherFastPrint 0.3.0 Preview — фактические проверки

Дата: 1 ноября 2026 года.

## Сборка и идентичность

Успешно выполнены `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`, `lintDebug` (83 Gradle tasks, BUILD SUCCESSFUL). JDK 17 / Gradle 8.11.1 / SDK 36 / Build Tools 35.0.0 / NDK 28.2.13676358 / CMake 3.22.1. Новая тестовая подпись: прежний ключ 0.2.1 не сохранился при очистке среды, поэтому выбран отдельный Preview package, сохраняющий установленную рабочую версию пользователя.

- APK: `BrotherFastPrint-0.3.0-preview-arm64.apk`, 1494058 байт.
- SHA-256: `d282566c2fd11ffa7765153d3e6fe143615e74a14e8d66f20056ac0357fe1849`.
- Package: `ru.brotherfastprint.preview`, versionCode 6, versionName 0.3.0-preview.
- minSdk 26, targetSdk/compileSdk 36, только arm64-v8a.
- apksigner: корректная APK Signature Scheme v2. Сертификат SHA-256 `f7f04543fc5d214e8c4c00534b9a86b2c90475520deb24e7d83e599e19dc1944`.
- `zipalign -c -P 16 4` прошёл; все ELF LOAD в libfastprint.so имеют alignment 0x4000.
- Итоговый manifest: MainActivity с правильным namespace; PrintService защищена BIND_PRINT_SERVICE, exported=true, stopWithTask=false и системная metadata; RenderService exported=false, isolatedProcess=true.
- Разрешения ровно INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_MULTICAST_STATE. Разрешений файлов/контактов/location/камеры/микрофона нет. Нет входящего HTTP/TCP-сервера/аналитики.
- В APK включены NOTICE, GPLv3, Apache-2.0 и LLVM license assets. Соответствующие исходники приложены; приватного ключа в них нет.
- Android Lint: **0 ошибок, 1 предупреждение** ChromeOsAbiSupport (x86_64 не входит в целевую сборку arm64).

## JVM

**46 тестов, 0 failures/errors/skipped.**

| Набор | Тестов |
|---|---:|
| EndpointTest | 2 |
| LanRoutingTest | 5 |
| PagePlanTest | 6 |
| DuplexPlanTest | 5 |
| PrinterAdvertisementTest | 4 |
| ProbePolicyTest | 2 |
| RawTcpTransportTest | 8 |
| ReachabilityTest | 4 |
| TcpDiagnosticsTest | 3 |
| PaperGeometryTest | 5 |
| RenderInstanceNameTest | 2 |

Новые проверки: пустые обороты между нечётными копиями; подбор по диапазону, а не по полной длине PDF; 50 страниц без лишних пустых сторон; лимит учитывает padding; разворот по порядку выходных сторон; порт RAW из DNS-SD; IPP/IPPS/LPR никогда не принимаются за RAW-порт; фильтр модели; запрет public IP; timeout/VPN/background причины не делают ручную запись невыбираемой; восстановление после REFUSED; преобразование оборота/асимметричных полей для portrait/landscape и целостность полос.

Сохранены реальные socket-тесты: одно TCP-соединение, точные байты, fallback только до write, отсутствие повторной передачи после RST, отмена и таймаут. Эти host-тесты не эмулируют Android netd, VPN-клиент и Samsung power manager.

## Нативный код и HBP

`bash tools/test_native.sh` прошёл. Все vendored файлы brlaser побайтно соответствуют закреплённому commit `2a49e3287c70c254e7e3ac9dabe9d6a07218c3fa`.

Прошли upstream test_line/test_block/test_job и собственные проверки dithering/alpha/padding, совпадения полос, full A4, FD alias, lifecycle/EOF, truncation и cancellation. Независимый bounded decoder восстановил исходный растр побайтно для всех сценариев:

| Сценарий | Сторон × строк | HBP, байт |
|---|---:|---:|
| Simplex | 1 × 6816 | 522518 |
| Simplex | 10 × 6816 | 5220875 |
| Simplex | 50 × 6816 | 26100829 |
| Duplex | 4 × 6816 | 2088659 |
| Duplex с полностью белыми оборотами | 4 × 6816 | 1059307 |

В каждом сценарии ровно один PJL JOB/EOJ; duplex-поток содержит ESC&l2S, simplex не содержит. Растр сравнивается по одной странице; не удерживается 50 decoded A4 одновременно.

Нативные unit-тесты и все пять roundtrip-сценариев повторно прошли под AddressSanitizer/UndefinedBehaviorSanitizer, без сообщений о нарушениях. Leak detection выключен (`ASAN_OPTIONS=detect_leaks=0`); проверка утечек этим не подтверждается.

## Android runtime и физический принтер

Пять instrumented-тестов **собраны, но не запущены**: первая страница через PdfRenderer/Binder/JNI, выбранные страницы/копии в одном job, нечётные duplex-копии с пустыми оборотами, системная защита компонента и совпадение полос с whole bitmap. Устройства Android и аппаратно ускоренного эмулятора в этой среде нет. Снимки нового UI на телефоне не получены.

Пользователь подтвердил физическую печать POC и быструю печать предыдущих версий, но **новая 0.3.0-preview физически не проверена**. Успешная сборка не доказывает корректное направление duplex, доступность при закрытой Activity на Samsung, mDNS с данным роутером/VPN или уменьшение межстраничных пауз в новых условиях.

Что потребуется от пользователя:

1. Установить Preview рядом с 0.2.1; включить именно новую службу, выбрать принтер с пометкой FastPrint.
2. Проверить «Поиск»: нужный IP и RAW-порт сохраняются после выбора, подключение проходит. При блокировке multicast проверить ручной адрес.
3. В системной печати проверить long/short edge на 4-страничном тесте с TOP/BOTTOM, затем landscape. Проверить нечётный диапазон и две копии: чистый оборот не должен становиться первой стороной следующего экземпляра.
4. Смахнуть Preview из недавних и печатать из Word/PDF без его запуска. Повторить с VPN. Force Stop тестировать отдельно, поскольку это stopped state Android.
5. При сбое скопировать «⋮ → Диагностика», включая service_diagnostics предыдущего процесса; указать способ закрытия и VPN-клиент. Без такого лога первопричина исходной недоступности Samsung не объявляется установленной.
6. Количественные паузы между листами измерить на принтере для одинакового документа; tcp_end не является временем выхода последнего листа.
