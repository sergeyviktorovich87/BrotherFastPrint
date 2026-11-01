# BrotherFastPrint 0.3.2 Preview — фактические проверки

Дата: 1 ноября 2026 года.

## Основание изменения

Пользователь предоставил лог 0.3.1 и два снимка одного preview: FastPrint показывает «Принтер не готов» до открытия приложения и становится доступным после него. По логу фоновые service/probes работают с VPN, background_restricted=false; после job_received подготовка и передача занимают 2240 мс. Второй эпизод preview не создал второго job_received. Физическая двусторонняя печать long edge и NSD-обнаружение проверенного принтера подтверждаются пользовательским результатом/логом. Подробный разбор и ограничения вывода — BACKGROUND_0.3.2_RU.md.

Точная первопричина задержки в Android этим логом не доказана. Изменения исправляют проверенные слабые места initial publication, выбора dispatcher и восстановления очереди, а не объявляют фоновые настройки Samsung виновными.

## Изменения

- Полный initial PrinterInfo с IDLE/UNKNOWN, A4/600/mono/duplex кэшируется в constructor discovery session до observer attachment и до probe.
- ServiceDispatcher использует Handler.createAsync на API 28+ и публичную маркировку Message asynchronous на 26–27. Собственные service/engine/discovery/diagnostic callbacks и executor PDF binding API 29+ обходят UI synchronization barriers; main-thread правила Android остаются в силе. Это не отменяет блокирующие Binder RPC, CPU freeze или сетевые ограничения ОС.
- PrinterPublisher проверяет фактический cached PrinterInfo после addPrinters; только если старый snapshot остался вне discovery, применяется buffered remove/add того же ID. Совместимые vendor-реализации лишних удалений не получают.
- Queue watchdog проверяет активные системные jobs раз в 5 с во время discovery/tracking/jobs, стартует с отсрочкой 250 мс после подключения и удаляется при disconnect/destroy. Только неуправляемый QUEUED может начать обработку; in-flight не переотправляется, неизвестный начатый после reconnect завершается FAIL_UNCERTAIN. При пустой очереди/отсутствии запросов после grace scan цикл останавливается.
- Добавлены service instance/session, printer_published/cached_count/capabilities, system_job_callback/accept/started, источник callback/queue_scan, dispatch lag и длительный spooler RPC. document_age_ms включает preview; queued_ms по-прежнему является внутренней метрикой engine.
- Новых permissions, foreground-service, wakelock, запросов исключения из батареи или автоматического изменения VPN нет. Renderer/HBP/C++ не менялись.

## Сборка и идентичность

Итоговый прогон `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug` завершён BUILD SUCCESSFUL, 83 задачи, 18 executed / 65 up-to-date.

- APK `BrotherFastPrint-0.3.2-preview-arm64.apk`, 1521784 байта.
- SHA-256 `fbb7d64caa2bd4f8acfe594bafae8e5ddf5202647281f1442f29bc83bf4accb3`.
- Package ru.brotherfastprint.preview; versionCode 8; versionName 0.3.2-preview; minSdk 26, targetSdk/compileSdk 36; arm64-v8a.
- apksigner: подпись корректна; SHA-256 сертификата `f7f04543fc5d214e8c4c00534b9a86b2c90475520deb24e7d83e599e19dc1944`, совпадает с Preview 0.3.0/0.3.1. Обновление поверх них сохраняет настройки.
- `zipalign -c -P 16 4` прошёл. libfastprint.so побайтно совпадает с 0.3.1/0.3.0, включая ранее проверенный ELF LOAD alignment 0x4000.
- Manifest permissions остались INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_MULTICAST_STATE. PrintService защищена BIND_PRINT_SERVICE; sandbox остаётся isolated и неэкспортированным.
- Android Lint: 0 ошибок, 1 предупреждение ChromeOsAbiSupport (целевой APK arm64). Deprecated Java Thread.id предупреждение сохранено из предыдущей версии ради API 26.
- Инструменты прежние: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.21, SDK 36, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1.

## Проверки

**56 JVM-тестов, 0 failures/errors/skipped.** К 52 прежним добавлены четыре сценария PrintRecovery: единственный подхват QUEUED при перекрытии callback/scan, запрет переотправки после неопределённого in-flight, сохранение живого managed worker и игнорирование terminal/несcheduled состояний. Socket, duplex/page plan, геометрия, NSD policy, журнал и privacy/crash проверки прошли.

**9 Android instrumented-тестов собраны, но не запускались.** Шесть прежних PdfRenderer/Binder/JNI/duplex/manifest/stripe/diagnostics тестов дополнены проверкой async сообщения и двумя проверками actual framework cache до observer attachment/при stopped discovery. Для PrinterId используется публичный generatePrinterId тестового PrintService с легально подключённым Context; hidden APIs/reflection/реальная печать не нужны. Эти тесты требуют Android runtime, которого в окружении нет.

Нативная библиотека побайтно неизменна; повторный host/ASan/UBSan прогон не выполнялся. Предыдущие проверки HBP 1/10/50 A4 и duplex с белыми оборотами находятся в VALIDATION_0.3.0_RU.md. Изменение callback executor PDF binding требует device-проверки, даже при совпадающем native-коде.

## Что требуется на телефоне

1. Установить 0.3.2 поверх Preview, один раз выключить/включить BrotherFastPrint Preview в настройках печати, затем закрыть его экран.
2. Не меняя одновременно VPN/питание, открыть печать из Word, выбрать FastPrint. Ожидается готовый принтер без запуска Activity. Отправить одно задание и дождаться печати.
3. При повторении проблемы отметить длительность ожидания, затем открыть приложение и сохранить TXT: в нём должны быть initial=true/cached_count=1 и различимые callback/scan/engine-события. При подготовке отчёта этот сценарий ожидал проверки; позднее пользователь подтвердил работу плагина после обновления (см. ниже).
4. Short-edge duplex, landscape, 50 страниц, два нечётных collated экземпляра и количественные паузы между листами остаются аппаратными сценариями. tcp_end не измеряет выход бумаги.

Документы и их названия/URI не журналируются и не входят в исходники. Логи остаются ограниченными приватными файлами; TXT экспортируется только по действию пользователя. Автоматического повтора после write/неопределённой ошибки нет.

## Подтверждение владельца после обновления

1 ноября 2026 года владелец сообщил: «плагин работает. все как я и хотел» и разрешил публикацию проекта. Это подтверждение работоспособности итогового плагина на его Samsung Android 16 / Brother HL-L2340DWR после исправлений 0.3.2. Оно не заменяет отдельные измерения межстраничных пауз и не означает выполнения всех перечисленных device-тестов. При оформлении GitHub исходный код приложения и проверенный APK не менялись.
