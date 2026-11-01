package ru.brotherfastprint

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.core.Endpoint
import ru.brotherfastprint.core.RasterProfile
import ru.brotherfastprint.discovery.PrinterFinder
import ru.brotherfastprint.printing.EngineState

/** Settings are independent of the system-owned PrintService and its lifetime. */
class MainActivity : Activity() {
    private val engine get() = (application as FastPrintApp).engine
    private val diagnosticStore get() = (application as FastPrintApp).diagnostics
    private val controller get() = (application as FastPrintApp).controller
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var result: TextView
    private lateinit var duplex: TextView
    private lateinit var rasterProfile: TextView
    private lateinit var toner: Switch
    private lateinit var jobCard: LinearLayout
    private lateinit var jobStatus: TextView
    private lateinit var progress: ProgressBar
    private val idleControls = mutableListOf<View>()
    private var unsubscribe: (() -> Unit)? = null
    private var finder: PrinterFinder? = null
    private var searchDialog: AlertDialog? = null
    private var active = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)
    private fun text(value: String, size: Float = 16f, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(color(if (muted) R.color.muted else R.color.ink))
    }
    private fun rounded(fill: Int, radius: Int = 20, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), color(R.color.outline))
    }
    private fun column(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun card(parent: LinearLayout) = column(20).apply {
        background = rounded(R.color.surface, stroke = true)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f; minHeight = dp(50)
        setTextColor(color(if (primary) R.color.on_accent else R.color.accent))
        backgroundTintList = ColorStateList.valueOf(color(if (primary) R.color.accent else R.color.accent_soft))
        setOnClickListener { action() }
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(color(R.color.outline)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = column().apply { setPadding(dp(20), dp(16), dp(20), dp(28)) }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(center) }
        setContentView(scroll)
        center.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val width = minOf(center.width, dp(600))
            if (width > 0 && content.layoutParams.width != width) {
                content.layoutParams = (content.layoutParams as LinearLayout.LayoutParams).apply { this.width = width }
            }
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        scroll.requestApplyInsets()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("BrotherFastPrint", 23f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "⋮"; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(color(R.color.ink)); contentDescription = "Дополнительные действия"
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showMenu(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)
        content.addView(text("Локальная служба печати", 14f, true))

        val printer = card(content)
        val identity = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_printer)
            imageTintList = ColorStateList.valueOf(color(R.color.accent))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, 0, dp(14), 0)
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        val labels = column()
        labels.addView(text("Brother HL-L2340DWR", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        status = text("Доступность не проверена", 14f, true).apply { setPadding(0, dp(6), 0, 0) }
        labels.addView(status)
        identity.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); printer.addView(identity)
        address = text("", 16f).apply {
            minHeight = dp(54); gravity = Gravity.CENTER_VERTICAL
            setTextColor(color(R.color.accent)); isClickable = true; isFocusable = true
            contentDescription = "Изменить IP и RAW-порт принтера"
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { editAddress() }
        }
        printer.addView(address); idleControls += address
        val actions = LinearLayout(this)
        val check = button("Проверить") { engine.probe(PrinterConfig.endpoint(this)) }
        val search = button("Поиск", true) { searchPrinters() }
        actions.addView(check, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        actions.addView(search, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        idleControls += check; idleControls += search; printer.addView(actions)
        result = text("", 14f, true).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        printer.addView(result)

        val settings = card(content)
        settings.addView(text("Параметры по умолчанию", 16f).apply { setTypeface(typeface, Typeface.BOLD) })
        settings.addView(text("A4  ·  600 DPI  ·  чёрно-белая", 14f, true).apply { setPadding(0, dp(8), 0, dp(8)) })
        duplex = text("", 16f).apply {
            minHeight = dp(62); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { chooseDuplex() }
        }
        settings.addView(duplex); idleControls += duplex
        divider(settings)
        rasterProfile = text("", 16f).apply {
            minHeight = dp(62); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { chooseRasterProfile() }
        }
        settings.addView(rasterProfile); idleControls += rasterProfile
        divider(settings)
        @Suppress("UseSwitchCompatOrMaterialCode")
        toner = Switch(this).apply {
            text = "Экономия тонера"; textSize = 16f; minHeight = dp(48)
            setTextColor(color(R.color.ink)); isChecked = PrinterConfig.tonerSave(this@MainActivity)
            setOnCheckedChangeListener { _, value -> PrinterConfig.saveToner(this@MainActivity, value) }
        }
        settings.addView(toner); idleControls += toner
        settings.addView(text("Копии, диапазон и стороны можно изменить в системном окне печати.", 13f, true).apply { setPadding(0, dp(12), 0, 0) })

        jobCard = card(content)
        jobCard.addView(text("Текущее задание", 16f).apply { setTypeface(typeface, Typeface.BOLD) })
        jobStatus = text("", 14f).apply { setPadding(0, dp(10), 0, dp(12)) }; jobCard.addView(jobStatus)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        jobCard.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        jobCard.addView(button("Отменить") { engine.cancelCurrent() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        content.addView(button("Настройки печати Android", true) {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        content.addView(text("Включите BrotherFastPrint Preview и выберите принтер с пометкой FastPrint в меню «Печать».", 14f, true).apply { setPadding(dp(4), dp(10), dp(4), dp(16)) })
        content.addView(text("Всё обрабатывается на телефоне.\n${BuildConfig.VERSION_NAME} · тестовая версия", 12f, true))
        refreshSettings(); render(engine.snapshot())
    }

    private fun refreshSettings() {
        val endpoint = PrinterConfig.endpoint(this)
        address.text = getString(R.string.printer_address, endpoint.host, endpoint.port)
        duplex.text = getString(R.string.default_duplex, Duplex.label(PrinterConfig.duplex(this)))
        rasterProfile.text = "Обработка изображения\n${RasterProfile.label(PrinterConfig.rasterProfile(this))} ›"
    }
    private fun editAddress() {
        if (engine.snapshot().busy) return
        val endpoint = PrinterConfig.endpoint(this)
        val form = column(20)
        form.addView(text("IP принтера", 13f, true))
        val ip = EditText(this).apply { setSingleLine(); inputType = InputType.TYPE_CLASS_PHONE; setText(endpoint.host); hint = "192.168.1.41" }
        form.addView(ip)
        form.addView(text("RAW-порт", 13f, true))
        val port = EditText(this).apply { setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER; setText(String.format(java.util.Locale.ROOT, "%d", endpoint.port)) }
        form.addView(port)
        val dialog = AlertDialog.Builder(this).setTitle("Адрес принтера").setView(form)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val parsed = Endpoint.parse(ip.text.toString(), port.text.toString())
                    PrinterConfig.save(this, parsed, toner.isChecked)
                    refreshSettings(); engine.printerConfigured()
                    dialog.dismiss()
                } catch (_: IllegalArgumentException) { ip.error = "Нужен локальный IPv4, порт от 1 до 65535" }
            }
        }
        dialog.show()
    }
    private fun chooseDuplex() {
        val modes = Duplex.modes
        AlertDialog.Builder(this).setTitle("Двусторонняя печать")
            .setSingleChoiceItems(modes.map(Duplex::label).toTypedArray(), modes.indexOf(PrinterConfig.duplex(this))) { dialog, index ->
                PrinterConfig.saveDuplex(this, modes[index]); refreshSettings(); dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }
    private fun chooseRasterProfile() {
        if (engine.snapshot().busy) return
        val modes = RasterProfile.modes
        AlertDialog.Builder(this).setTitle("Обработка изображения")
            .setSingleChoiceItems(modes.map(RasterProfile::label).toTypedArray(), modes.indexOf(PrinterConfig.rasterProfile(this))) { dialog, index ->
                PrinterConfig.saveRasterProfile(this, modes[index]); refreshSettings(); dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }
    private fun searchPrinters() {
        if (finder != null || engine.snapshot().busy) return
        val box = column(20)
        val hint = text("Ищем Brother в вашей Wi-Fi сети…", 14f, true)
        val spinner = ProgressBar(this)
        val choices = column()
        box.addView(hint); box.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })
        box.addView(choices)
        val dialog = AlertDialog.Builder(this).setTitle("Поиск принтера").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Закрыть", null).create()
        val search = PrinterFinder(this, engine::logService)
        finder = search; searchDialog = dialog
        dialog.setOnDismissListener { if (finder === search) finder = null; searchDialog = null; search.finish() }
        dialog.show()
        search.start { found, done ->
            if (!active || !dialog.isShowing) return@start
            choices.removeAllViews()
            found.forEach { printer ->
                choices.addView(button("${printer.name}\n${printer.endpoint.host}:${printer.endpoint.port}") {
                    PrinterConfig.save(this, printer.endpoint, toner.isChecked)
                    refreshSettings(); engine.printerConfigured(true)
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            spinner.visibility = if (done) View.GONE else View.VISIBLE
            hint.setText(when {
                found.isNotEmpty() -> R.string.search_found
                done -> R.string.search_empty
                else -> R.string.search_running
            })
            if (done && finder === search) finder = null
        }
    }
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Печать PDF напрямую").isEnabled = !engine.snapshot().busy
        popup.menu.add(0, 2, 1, "Тестовая страница").isEnabled = !engine.snapshot().busy
        popup.menu.add(0, 3, 2, "Диагностика")
        popup.menu.add(0, 4, 3, "О приложении")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf" }, 10)
                }
                2 -> AlertDialog.Builder(this).setTitle("Тестовая печать")
                    .setItems(arrayOf("A4 · 1 страница", "4 страницы · проверка duplex")) { _, item ->
                        controller.selectSample(if (item == 0) 1 else 4); confirmPrint()
                    }.setNegativeButton("Отмена", null).show()
                3 -> diagnostics()
                4 -> AlertDialog.Builder(this).setTitle("BrotherFastPrint Preview").setMessage(
                    "${BuildConfig.VERSION_NAME}\nBrother HL-L2340DWR / HL-L2340DW series\n\nЛокальная печать A4, 600 DPI. Документ готовится целиком и передаётся одним RAW-заданием.\n\nДрайвер основан на brlaser, Peter De Wachter и участники проекта. Приложение и драйвер распространяются по GNU GPL v3.\n\nПосле передачи данных автоматического повтора нет: часть страниц могла уже напечататься."
                ).setPositiveButton("Закрыть", null).show()
            }; true
        }
        popup.show()
    }
    private fun confirmPrint() {
        if (engine.snapshot().busy) return
        AlertDialog.Builder(this).setTitle("Напечатать документ?")
            .setMessage("Все страницы · A4 · 600 DPI\n${Duplex.label(PrinterConfig.duplex(this))}\n${RasterProfile.label(PrinterConfig.rasterProfile(this))}\n${PrinterConfig.endpoint(this).host}")
            .setNegativeButton("Отмена", null).setPositiveButton("Печатать") { _, _ -> controller.printAll(PrinterConfig.endpoint(this)) }.show()
    }
    private fun diagnostics() {
        var log = ""
        val view = text("Подготовка отчёта…", 11f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("Диагностика").setView(ScrollView(this).apply { addView(view) })
            .setNegativeButton("Закрыть", null).setPositiveButton("Копировать") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BrotherFastPrint diagnostics", log))
                Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }.setNeutralButton("Сохранить лог") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                    val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
                    putExtra(Intent.EXTRA_TITLE, "BrotherFastPrint-log-$stamp.txt")
                }, 20)
            }.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        val accepted = diagnosticStore.reportAsync { captured ->
            if (!isDestroyed && dialog.isShowing) {
                captured.onSuccess {
                    log = it; view.text = it
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                }.onFailure { view.setText(R.string.diagnostics_failed) }
            }
        }
        if (!accepted) view.setText(R.string.diagnostics_busy)
    }
    private fun render(s: EngineState) {
        idleControls.forEach { it.isEnabled = !s.busy }
        jobCard.visibility = if (s.busy) View.VISIBLE else View.GONE
        jobStatus.text = s.status
        progress.isIndeterminate = s.stage != "prepare" || s.pages <= 0
        if (!progress.isIndeterminate) progress.progress = (100 * (s.page - 1).coerceAtLeast(0) / s.pages).coerceIn(0, 100)
        status.text = when {
            s.busy && s.stage != "probe" -> "Подготовка и печать…"
            s.stage == "probe" -> "Проверка соединения…"
            s.reachable == true -> "Доступен · RAW-порт отвечает"
            s.reachable == false -> "Не удалось подключиться"
            else -> "Доступность не проверена"
        }
        status.setTextColor(color(if (s.reachable == true) R.color.accent else R.color.muted))
        result.text = s.status
        result.visibility = if (!s.busy && s.status != "Готов к печати") View.VISIBLE else View.GONE
    }
    override fun onStart() {
        super.onStart(); active = true; refreshSettings()
        engine.logService("activity_started")
        unsubscribe = engine.observe { if (active) render(it) }
    }
    override fun onStop() {
        active = false; unsubscribe?.invoke(); unsubscribe = null
        searchDialog?.dismiss(); finder?.finish(); finder = null
        engine.logService("activity_stopped")
        super.onStop()
    }
    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) data?.data?.takeIf { it.scheme == "content" }?.let {
            controller.select(it); confirmPrint()
        }
        if (requestCode == 20 && resultCode == RESULT_OK) data?.data?.takeIf { it.scheme == "content" }?.let {
            val accepted = diagnosticStore.exportAsync(it) { success ->
                if (!isDestroyed) Toast.makeText(this, if (success) "Лог сохранён" else "Не удалось сохранить лог", Toast.LENGTH_LONG).show()
            }
            if (!accepted) Toast.makeText(this, "Отчёт уже готовится. Повторите сохранение.", Toast.LENGTH_LONG).show()
        }
    }
}
