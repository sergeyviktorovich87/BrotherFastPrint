package ru.brotherfastprint

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.brotherfastprint.printing.PrintKeepAlive
import ru.brotherfastprint.printing.PrintKeepAliveService

/** Real Android service lifecycle and overlapping owners; no network/print submission. */
class KeepAliveInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val leases = mutableListOf<AutoCloseable>()
    private var activity: Activity? = null

    @Before fun openSettingsForAllowedForegroundStart() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        await(false)
    }

    @After fun closeOwnersAndActivity() {
        instrumentation.runOnMainSync { leases.forEach { it.close() }; activity?.finish() }
        instrumentation.waitForIdleSync()
        await(false)
    }

    private fun acquire(role: String): AutoCloseable = PrintKeepAlive.acquire(context, role).also { leases += it }

    @Suppress("DEPRECATION")
    private fun foreground() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == PrintKeepAliveService::class.java.name && it.foreground }

    private fun await(expected: Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            if (foreground() == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("Foreground companion state after lifecycle dispatch", expected, foreground())
    }

    @Test fun sessionAndJobOverlapOutliveSettingsAndStopAfterLastOwner() {
        lateinit var session: AutoCloseable
        lateinit var job: AutoCloseable
        instrumentation.runOnMainSync { session = acquire("system_session"); job = acquire("job") }
        await(true)
        instrumentation.runOnMainSync { session.close(); activity?.finish() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertTrue("Closing the Activity/session must not stop an active job", foreground())
        job.close()
        await(false)
    }

    @Test fun newOwnerDuringPendingStopKeepsForegroundProtection() {
        lateinit var oldSession: AutoCloseable
        instrumentation.runOnMainSync { oldSession = acquire("system_session") }
        await(true)
        // release and acquire are posted ahead of the framework's asynchronous destruction.
        instrumentation.runOnMainSync { oldSession.close(); acquire("system_session") }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_000)
        await(true)
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertTrue("A replacement session must retain foreground protection", foreground())
    }

    @Test fun ownerClosedBeforeDispatchDoesNotStartAnUnneededService() {
        instrumentation.runOnMainSync {
            repeat(20) { acquire("job").also { it.close(); it.close() } }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertFalse(foreground())
    }
}
