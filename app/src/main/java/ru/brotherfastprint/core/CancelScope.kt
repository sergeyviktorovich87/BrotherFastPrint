package ru.brotherfastprint.core

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class CancelScope {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val actions = mutableSetOf<() -> Unit>()
    val isCancelled: Boolean get() = cancelled.get()

    fun check() { if (isCancelled) throw CancellationException() }

    fun onCancel(action: () -> Unit): () -> Unit {
        val runNow = synchronized(lock) {
            if (isCancelled) true else { actions += action; false }
        }
        if (runNow) runCatching(action)
        return { synchronized(lock) { actions -= action }; Unit }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val copy = synchronized(lock) { actions.toList().also { actions.clear() } }
        copy.forEach { runCatching(it) }
    }
}
