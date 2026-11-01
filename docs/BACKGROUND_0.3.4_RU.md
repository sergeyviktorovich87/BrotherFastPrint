# 0.3.4 Preview: поддержка активного сеанса печати

## Что установил пользовательский лог 0.3.3

Выполненное задание содержит `raster_profile=documents_bayer8`, а не новый Photo. Применение нового фото-профиля этим отпечатком не проверено. После принятия задания подготовка/передача завершилась примерно за 5.94 секунды; RAW подключение при активном VPN прошло. Эти факты не подтверждают физический выход бумаги или качество.

Перед обработкой очереди наблюдается `dispatch_late_ms=117792`, для проверки принтера — `late_ms=112287`. Очередь первой приняла задание через queue_scan, затем пришёл обычный callback; почти одновременно запустилась Activity. Старый расчёт сравнивал elapsedRealtime с задержкой Handler, основанной на uptimeMillis. Первый включает глубокий сон, второй — нет. Следовательно, 118 секунд не являются доказанной длительностью блокировки main thread или доказательством cached-app freezer. Старые записи low_memory/user_requested также не доказывают причину этого эпизода.

## Изменение

Android по-прежнему создаёт и отключает BrotherPrintService. Отдельный неэкспортированный PrintKeepAliveService запускается только при владельце: подключённом системном сеансе либо задании общего PrintEngine. Владельцы не зависят от Activity; завершение одного задания не снимает поддержку остальных. После удаления последнего владельца служба останавливается. При отказе Android в запуске/повышении приоритета ошибка журналируется, обычный путь печати сохраняется.

Служба использует тип connectedDevice и foreground-уведомление без содержимого документа. Добавлены только три обычных разрешения: FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE и WAKE_LOCK. Требования connectedDevice удовлетворяются уже имеющимися CHANGE_NETWORK_STATE/CHANGE_WIFI_MULTICAST_STATE. POST_NOTIFICATIONS не запрашивается; Android 13+ может показывать службу только в системном списке активных приложений, если уведомления не разрешены.

Частичный wake lock запрашивается с timeout Android 60 секунд и обновляется через 20 секунд только при живых владельцах. При завершении службы он освобождается. Это ограничение через API Android, а не гарантия жёсткого wall-clock срока при зависании или остановке всего процесса: timeout callbacks также зависят от выполнения ОС. START_NOT_STICKY, отсутствие boot receiver/alarms и сохранённая логика PrintRecovery исключают использование этой службы для самостоятельного повторного запуска печати. Экран удерживать включённым не требуется.

Это мера против фоновых остановок и сна во время активной печати, **а не доказательство точной первопричины на Samsung и не выполненный device-тест**. Android может отказать в FGS-запуске; соответствующие события теперь видны в отчёте.

## Диагностика

- Для queue/probe сохраняются elapsed lateness, `awake_late_ms` и `sleep_ms`.
- Заголовок отчёта также разделяет elapsed_ms и настоящий uptime_ms; раньше поле uptime_ms ошибочно содержало elapsedRealtime.
- Callback и принятие задания фиксируют важность процесса, interactive/idle/power-save, elapsed/uptime.
- Получение PDF имеет отдельные begin/end события.
- Журнал содержит keepalive_requested/foreground_started/wake_acquired/stopped либо категории отказов.
- Поправлен whitelist RenderClient: события raster_profile, pdf_width_pt и pdf_height_pt ранее генерировались sandbox, но отбрасывались клиентом. В 0.3.4 они доходят до журнала.

Содержимое, названия и URI документов не добавляются в отчёт. Исходный пользовательский лог и фотографии в репозиторий не включаются.

## Проверка владельцем

Установить 0.3.4 поверх Preview, включить «Фото» для фотосравнения. Закрыть экран приложения, отправить одно задание из Галереи и подождать до 30 секунд. Ожидается начало подготовки без открытия Activity. Если пауза повторится, открыть приложение, сохранить TXT и отметить, был ли экран телефона включён. Техническое подтверждение поддержки сеанса — keepalive_foreground_started и keepalive_wake_acquired; причины отказа также должны быть записаны. Итоговые качество и фоновый старт проверяются на реальном принтере.

## Основания API

- [PrintService: lifecycle](https://developer.android.com/reference/android/printservice/PrintService).
- [Connected-device foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
- [Ограничения фонового запуска FGS](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- [SystemClock: elapsedRealtime / uptimeMillis](https://developer.android.com/reference/android/os/SystemClock).
