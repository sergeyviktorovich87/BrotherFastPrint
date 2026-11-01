# BrotherFastPrint 0.3.1 Preview — фактические проверки

Дата: 1 ноября 2026 года.

## Что изменено и зачем

Аудит 0.3.0 выявил: только события службы сохранялись через SupportJournal, а подробные job/probe события PrintEngine оставались в RAM и терялись после смерти процесса. В 0.3.1 оба потока получают общий приватный постоянный журнал. Добавлены сохранение TXT через системный выбор файла, ограниченная сводка Java crash и доступные собственные причины завершения процессов Android. Это улучшает доказательность следующего аппаратного теста, а не объявляет исчезновение исходной недоступности принтера подтверждённым.

Нативный драйвер, duplex-преобразования, сетевой транспорт и политика доступности по сравнению с 0.3.0 не изменялись.

## Сборка и APK

Успешный чистый прогон: `:app:clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`. BUILD SUCCESSFUL, 86 tasks executed. Первый инкрементальный прогон обнаружил дублированный R$bool в dex-кеше; чистая сборка устранила stale build outputs. Ошибок компиляции/тестов в итоговом прогоне нет.

- APK `BrotherFastPrint-0.3.1-preview-arm64.apk`: 1499326 байт.
- SHA-256: `c96cb1056f40afb2ec524ca261787a223673c997d2e3426b3963304f82ada2aa`.
- Package `ru.brotherfastprint.preview`, versionCode 7, versionName 0.3.1-preview.
- minSdk 26, targetSdk/compileSdk 36, arm64-v8a.
- apksigner: корректная v2 подпись; сертификат SHA-256 `f7f04543fc5d214e8c4c00534b9a86b2c90475520deb24e7d83e599e19dc1944` совпадает с 0.3.0 Preview. Обновление поверх неё возможно, настройки сохраняются.
- `zipalign -c -P 16 4` прошёл. libfastprint.so побайтно совпадает с APK 0.3.0: ранее проверенный ELF LOAD alignment 0x4000 сохранён.
- Список permissions не менялся: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_MULTICAST_STATE. Нет доступа ко всем файлам, location, DUMP, новым серверам/API или автоматической отправке отчётов.
- Android Lint: **0 ошибок, 1 предупреждение** ChromeOsAbiSupport (целевая ABI arm64). Компилятор также отметил deprecated Java Thread.id; старый совместимый getter намеренно используется для minSdk 26.
- JDK 17 / Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.1.21 / SDK 36 / Build Tools 35.0.0 / NDK 28.2.13676358 / CMake 3.22.1.

## JVM

**52 теста, 0 failures/errors/skipped.**

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
| SupportJournalTest | 4 |
| CrashSummaryTest | 2 |

Новые сценарии:

1. Завершённое задание (render/TCP/job_end) читается новым экземпляром журнала после закрытия старого.
2. Ротация строго ограничена байтами; длинные кириллические строки/emoji не режутся внутри UTF-8 символа, оба файла остаются до заданного лимита.
3. Ошибка хранилища не выбрасывается в print worker, отмечается техническим классом без раскрытия файлового пути.
4. При burst из 2000 событий с маленькой очередью сохраняется последний job_end; управляющие символы не создают поддельные отдельные строки.
5. Exception messages, document URI и filenames не попадают в crash summary; остаются класс/метод/номер строки.
6. Циклические causes и длинные stack traces ограничены 8 KiB.

Сохранены реальные localhost/socket тесты одного TCP-соединения, точных байтов, таймаутов/отмены и запрета повторной передачи после write/RST; duplex/geometry/NSD policy тесты также прошли.

## Нативное ядро

Побайтовое сравнение libfastprint.so с проверенной 0.3.0 успешно. C++/JNI/vendor не менялись, поэтому повторный host/ASan/UBSan прогон не выполнялся. Предыдущие результаты (1/10/50 A4 и duplex с белыми оборотами) сохранены в VALIDATION_0.3.0_RU.md, вместе с их ограничениями. Изменения Kotlin-логирования не являются новым подтверждением физического HBP/duplex.

## Безопасность диагностики

Отчёт создаётся вне UI thread. Два приватных журнала — максимум по 128 KiB, очередь максимум 256 событий, сведения о dropped/write_error явно включены. Конец job/probe имеет fsync barrier с ограниченным ожиданием на worker. Ошибка сохранения не вызывает повтор печати. SIGKILL/отключение питания может оборвать последние ещё не сохранённые события; журнал не является гарантированным crash dump.

Java crash summary не включает Throwable.message, source filenames, имена/URI PDF. Сохраняются классы, методы, номера строк, версия, session и последнее техническое событие. После best-effort записи вызывается исходный fatal handler Android. В isolated render UID приватная диагностика не инициализируется.

На API 30+ читаются до 8 собственных ApplicationExitInfo records: timestamp, PID, reason/status, importance, PSS/RSS и нейтральная метка процесса. Description, traceInputStream/ANR/native tombstone и processStateSummary не запрашиваются. Отсутствие записи не интерпретируется как конкретная причина закрытия. На API 26–29 раздел явно unavailable.

TXT создаётся только по действию пользователя через ACTION_CREATE_DOCUMENT; приложение получает URI ровно выбранного файла. Дополнительных storage permissions, автоматической пересылки или облачной обработки нет. Кнопка «Копировать» сохранена.

## Что ещё не проверено

**6 instrumented-тестов собраны, но не запускались** на Android: пять прежних PdfRenderer/Binder/JNI/duplex/manifest/stripe тестов плюс отчёт с сохранённым событием. Реального Android-устройства в окружении нет. Экспорт через Samsung DocumentsUI, реальный crash handler и системные exit records на телефоне требуют проверки. Не проводилось искусственное падение приложения или физическая печать новой версией.

На телефоне потребуется:

1. Установить 0.3.1 поверх Preview 0.3.0, проверить сохранность IP/порта/duplex.
2. Напечатать тест, закрыть Preview обычным свайпом, снова открыть и выбрать «⋮ → Диагностика → Сохранить лог». В TXT должны оставаться render/encode/TCP/job_end предыдущего сеанса.
3. Повторить системную печать из Word при закрытом экране Preview. Если принтер недоступен, сохранить TXT, указать способ закрытия и состояние VPN.
4. Отдельно проверить NSD, оба переплёта, landscape и нечётные копии. Эти аппаратные проверки 0.3.0 остаются актуальными.

Сборка/host-тесты и записи tcp_end не подтверждают выход бумаги или исчезновение межстраничных пауз. Первопричина исходного поведения Samsung пока не объявляется установленной.
