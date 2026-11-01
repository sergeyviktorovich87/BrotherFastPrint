package ru.brotherfastprint.core

/** Main-thread discovery state. A cancelled or stale probe must never publish an offline result. */
class Reachability {
    enum class State { UNKNOWN, ONLINE, OFFLINE }
    var state: State = State.UNKNOWN
        private set
    private var generation = 0L
    fun invalidate() { generation++; state = State.UNKNOWN }
    fun beginProbe(): Long = ++generation
    fun finishProbe(ticket: Long, online: Boolean): Boolean {
        if (ticket != generation) return false
        state = if (online) State.ONLINE else State.OFFLINE
        return true
    }
    fun confirmedOnline() { generation++; state = State.ONLINE }
    val selectable: Boolean get() = state != State.OFFLINE
}
