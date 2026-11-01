package ru.brotherfastprint.core

/** Handler delays use uptime; elapsedRealtime also includes deep sleep. Keep both clocks. */
class DispatchDeadline(private val elapsed: Long, private val uptime: Long, delay: Long) {
    init {
        require(elapsed >= 0 && uptime >= 0 && delay >= 0)
        require(delay <= Long.MAX_VALUE - maxOf(elapsed, uptime))
    }
    private val dueElapsed = elapsed + delay
    private val dueUptime = uptime + delay
    data class Delay(val elapsedLateMs: Long, val awakeLateMs: Long, val sleepMs: Long)
    fun measure(nowElapsed: Long, nowUptime: Long): Delay {
        require(nowElapsed >= elapsed && nowUptime >= uptime)
        return Delay((nowElapsed - dueElapsed).coerceAtLeast(0), (nowUptime - dueUptime).coerceAtLeast(0),
            ((nowElapsed - elapsed) - (nowUptime - uptime)).coerceAtLeast(0))
    }
}
