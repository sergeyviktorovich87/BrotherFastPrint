package ru.brotherfastprint.core

/** Recovery may claim a never-started queued job, but must never replay an uncertain print. */
object PrintRecovery {
    enum class State { QUEUED, IN_FLIGHT, TERMINAL }
    enum class Action { START, FAIL_UNCERTAIN, LEAVE }
    fun decide(state: State, managed: Boolean, reconnect: Boolean): Action = when {
        managed -> Action.LEAVE
        state == State.QUEUED -> Action.START
        reconnect && state == State.IN_FLIGHT -> Action.FAIL_UNCERTAIN
        else -> Action.LEAVE
    }
}
