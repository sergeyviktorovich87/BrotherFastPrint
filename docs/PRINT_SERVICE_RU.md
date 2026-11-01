# Системная печать — 0.3.2 Preview

## Компоненты

- `MainActivity`: настройки/ручной IP, NSD-поиск по кнопке, duplex/экономия, системные настройки, скрытые в меню PDF/тест/диагностика. Не владеет системной службой.
- `BrotherPrintService`: компонент `exported=true` с `BIND_PRINT_SERVICE`, системными action/metadata и `stopWithTask=false`. Все вызовы PrintJob/PrintDocument выполняются на main. Полученный PDF FD может быть pipe, поэтому worker копирует его во внутренний seekable файл.
- `BrotherDiscoverySession`: сразу публикует сохранённую запись, capabilities A4/600/mono/три duplex-режима. Сетевые callbacks и probes живут только во время подключения службы и активного discovery/tracking. UNKNOWN остаётся выбираемым; только явный REFUSED делает UNAVAILABLE. Устаревшие/отменённые результаты игнорируются по generation.
- `PrintEngine`: общая последовательная очередь системной/прямой печати, приватный spool, отмена/лимиты/диагностика. Весь HBP готовится до подключения. Общий `Diagnostics` основного Application UID пишет все технические события через `SupportJournal` в два файла до 128 KiB. Ограниченная очередь и UTF-8 ротация не допускают неограниченного роста; конец job/probe получает worker-checkpoint. Ошибки записи логов не отменяют печать.
- `RenderClient` / `RenderService`: отдельный изолированный процесс, три FD и параметры по Binder. Bitmap 4827×128, один повторно используемый mono-файл. PdfRenderer использует RENDER_MODE_FOR_PRINT. CUPS в APK нет.
- `MonoJob` / brlaser: один JOB/EOJ для всех выбранных страниц, пустых оборотов и копий. Duplex передаётся как bool; long/short edge различаются преобразованием растра в Kotlin. Vendor закреплён по commit, файлы brlaser не модифицированы.
- `RawTcpTransport`: одно соединение на подготовленный job; fallback/retry разрешены только до печатных байтов. После write неопределённая ошибка не вызывает повтор.

## Диапазоны и duplex

`PrintJobInfo.pages` задаёт индексы фактического spool PDF. Android может извлечь выбранный диапазон в новый PDF и заменить его на ALL_PAGES, поэтому повторное применение исходной нумерации недопустимо. PagePlan объединяет пересечения, сохраняет порядок и создаёт collated copies. Нечётная копия при duplex дополняется индексом BLANK=-1. Это гарантирует новый лист для следующего экземпляра и учитывается в лимите 500 выходных сторон.

brlaser использует `ESC&l2S` и `cupsBackSide=Rotated`. Проверено реальное поведение `cups-filters/filter/pdftoraster.cxx` версии 1.28.17: для Rotated и !Tumble меняются обе оси изображения и оба поля. Поэтому long edge разворачивает оборот на 180° с перестановкой top/bottom 16/8 pt; short edge оставляет растр без разворота. Landscape сначала поворачивается по часовой стрелке в портретную геометрию принтера. Пустой оборот создаётся sparse mono-файлом; он не открывает несуществующую PDF-страницу. Направление и физическое совмещение проверяются на принтере отдельно.

## NSD

`PrinterFinder` ограничен 15 секундами, 48 объявлениями и 24 проверяемыми адресами/портами. На API 33+ NetworkRequest содержит Wi-Fi/Ethernet; на API 34+ ServiceInfoCallback возвращает адреса. Старые resolveService выполняются последовательно. До API 33 включается multicast lock и обязательно освобождается. Только HL-L2340D series по имени/TXT проходит фильтр. Адрес — локальный IPv4; произвольные hostname/public IP не принимаются.

Объявленный `_pdl-datastream._tcp` порт проверяется как RAW. `_ipp._tcp`, `_ipps._tcp`, `_printer._tcp` дают адрес, на котором отдельно проверяется 9100. Запросы IPP и отправка HBP на 631/515 не выполняются. Выбор результата сохраняет оба значения и не является заданием печати. Ручная запись работает без NSD; автоматический поиск всей подсети не реализован.

## Ошибки и lifecycle

onDisconnected останавливает discovery-сеть/probes и отменяет живые задания; onConnected возобновляет работу активного session. Ранее начатое job после смерти процесса завершается ошибкой с предупреждением, а не автоматически переотправляется. Отмена/ошибка/успех удаляют spool; остатки после сбоя удаляются при следующем старте engine. Главный экран не хранит обязательный для службы объект и onStop не отменяет системное задание.

Timeout фонового probe сам по себе не доказывает выключение принтера: Wi-Fi/VPN/UID-background ограничения дают такой же результат. Поэтому запись остаётся выбираемой с UNKNOWN; явный REFUSED даёт OFFLINE. Это позволяет попытаться печатать из другого приложения без предварительного открытия Preview. Причину конкретного исходного сбоя Samsung можно подтвердить только журналом/физическим тестом; добавлены lifecycle, pid, power/network/probe-события с сохранением между процессами.

Принудительная остановка пакета Android отличается от закрытия Activity: после неё приложение надо открыть снова. Foreground-service, автозапуск и обход системного запрета VPN не добавлены.

Успех RAW означает полную передачу в сетевой стек, не выход бумаги. Сведения о бумаге/тонере не выдумываются; SNMP/PJL/IPP-статусы пока не реализованы. Главная метрика пауз между листами измеряется на выходном лотке. Протокол/память/механика принтера могут влиять на неё даже при полностью готовом потоке.

## Первичные источники

- https://developer.android.com/reference/android/printservice/PrintService
- https://developer.android.com/reference/android/printservice/PrintDocument
- https://developer.android.com/reference/android/printservice/PrinterDiscoverySession
- https://developer.android.com/reference/android/print/PrinterCapabilitiesInfo.Builder
- https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
- https://developer.android.com/reference/android/net/nsd/NsdManager
- https://developer.android.com/develop/connectivity/vpn#bypass_vpn
- https://developer.android.com/about/versions/16/behavior-changes-all#local-network-permission
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/PrintSpooler/src/com/android/printspooler/ui/PrintActivity.java
- https://github.com/OpenPrinting/cups-filters/blob/1.28.17/filter/pdftoraster.cxx
- https://www.cups.org/doc/spec-ppd.html#cupsBackSide
- https://github.com/pdewacht/brlaser (закреплённые исходники в third_party)

## Дополнение диагностики 0.3.1

`Diagnostics.report()` выполняется на отдельном worker. Он синхронизирует журнал, читает ограниченную историю событий и Java crash summary, на API 30+ запрашивает до 8 собственных exit records. Нет DUMP-разрешения и запросов чужих пакетов; description/trace streams не читаются. Report не включает содержимое/имя/URI PDF. Стандартный fatal handler Android сохраняет своё поведение. Для isolated UID диагностика не инициализируется: PDF sandbox по-прежнему получает только FD и параметры.

MainActivity показывает отчёт с кнопками «Копировать» / «Сохранить лог». Для файла применяется ACTION_CREATE_DOCUMENT и content URI, выбранный пользователем; экспорт выполняется вне UI. Права на все файлы и постоянные grants каталога не нужны. После остановки Activity сервис продолжает писать журнал.

Официальные API для нового кода:
- https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int)
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/training/data-storage/shared/documents-files#create-file

## Старт и доставка — 0.3.2

Полный PrinterInfo кэшируется в конструкторе BrotherDiscoverySession. По AOSP PrintService ServiceHandler после возврата фабрики вызывает session.setObserver(mClient), а setObserver немедленно передаёт уже добавленные printers. Это позволяет отдать capabilities до onStartPrinterDiscovery и до первой проверки TCP. При остановленном discovery AOSP addPrinters может сохранять прежнюю запись с тем же ID; PrinterPublisher сначала проверяет результат в getPrinters и только при несовпадении заменяет кеш через buffered remove/add.

В AOSP ServiceHandler PrintService создан как async Handler. ServiceDispatcher приводит собственные main callbacks к тому же механизму: API 28+ Handler.createAsync, API 26–27 помечают Message asynchronous через открытые методы SDK. ServiceConnection executor при bindIsolatedService API 29+ также использует этот dispatcher вместо UI mainExecutor. Блокирование самого main thread Binder-вызовом это не устраняет, поэтому длительные queue RPC отмечаются отдельно.

Queue scan откладывается после подключения на 250 мс либо запускается после готового discovery snapshot. Пока есть активные discovery/tracking/jobs, повторяется каждые 5 с; после завершения остаётся один grace scan и цикл прекращается. onDisconnected/onDestroy удаляют callback. Callback и scan проходят один acceptJob на main и общий jobs map. PrintRecovery разрешает START только неуправляемому QUEUED; неизвестный in-flight после reconnect получает FAIL_UNCERTAIN, прочие состояния оставляются. Байтам TCP это не добавляет автоматического retry.

Новая диагностика отделяет system_job_callback/system_job_accept/system_job_started от job_received engine. document_age_ms измеряется от создания PrintJobInfo и включает preview. Это не достоверное время входа в Android STATE_QUEUED.

Первичные исходники проверены в ветке Android 16:
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/printservice/PrintService.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/printservice/PrinterDiscoverySession.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/print/java/com/android/server/print/RemotePrintService.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/packages/PrintSpooler/src/com/android/printspooler/ui/PrintActivity.java
- https://developer.android.com/reference/android/os/Handler#createAsync(android.os.Looper)
