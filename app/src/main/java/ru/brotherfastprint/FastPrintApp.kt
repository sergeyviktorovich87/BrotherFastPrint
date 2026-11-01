package ru.brotherfastprint

import android.app.Application
import ru.brotherfastprint.printing.PrintEngine
import ru.brotherfastprint.printing.Diagnostics

class FastPrintApp : Application() {
    // Must stay lazy: Application is also instantiated in the isolated PDF process.
    val diagnostics: Diagnostics by lazy { Diagnostics(this) }
    val engine: PrintEngine by lazy { PrintEngine(this, diagnostics) }
    val controller: PocController by lazy { PocController(this, engine) }

    override fun onCreate() {
        super.onCreate()
        // Isolated render UIDs cannot access the app's private directory or framework exit history.
        if (android.os.Process.myUid() == applicationInfo.uid) diagnostics.installCrashHandler()
    }
}
