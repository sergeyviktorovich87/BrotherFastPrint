package ru.brotherfastprint.printing

import java.io.File
import java.io.Closeable
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded technical events only; no document bytes, names, URIs or user-entered text. */
class SupportJournal(private val directory: File, private val maxFileBytes: Int = 128 * 1024,
                     private val maxPending: Int = 256) : Closeable {
    private val file = File(directory, "service.log")
    private val previous = File(directory, "service.previous.log")
    private val queueLock = Any()
    private val diskLock = Any()
    private val pending = ArrayDeque<String>()
    private var scheduled = false
    private var closed = false
    private var dropped = 0L
    private val error = AtomicReference<String?>()
    val lastEvent = AtomicReference("")
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "support-journal").apply { isDaemon = true } })

    init { require(maxFileBytes >= 128 && maxPending >= 1) }

    fun append(line: String) {
        val clean = line.take(1024).map { if (it.isISOControl()) ' ' else it }.joinToString("")
        lastEvent.set(clean)
        synchronized(queueLock) {
            if (closed) return
            if (pending.size == maxPending) { pending.removeFirst(); dropped++ }
            pending.addLast(clean)
            if (!scheduled) {
                scheduled = true
                writer.execute(::drain)
            }
        }
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(queueLock) {
                if (pending.isEmpty()) { scheduled = false; return }
                pending.toList().also { pending.clear() }
            }
            try { synchronized(diskLock) { writeBatch(batch) } }
            catch (e: Exception) { error.set(e.javaClass.simpleName) }
        }
    }

    private fun writeBatch(batch: List<String>) {
        check(directory.isDirectory || directory.mkdirs())
        var output: FileOutputStream? = null
        var size = file.length()
        try {
            for (line in batch) {
                val bytes = line.toByteArray(Charsets.UTF_8)
                var end = minOf(bytes.size, maxFileBytes - 1)
                if (end < bytes.size) while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
                if (size + end + 1 > maxFileBytes) {
                    output?.close(); output = null
                    Files.move(file.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    size = 0
                }
                if (output == null) output = FileOutputStream(file, true)
                output.write(bytes, 0, end); output.write('\n'.code)
                size += end + 1
            }
        } finally { output?.close() }
    }

    /** Worker-only barrier. A logging/storage failure must not abort printing. */
    fun flush(timeoutMs: Long = 2000): Boolean = runCatching {
        writer.submit<Boolean> {
            synchronized(diskLock) { if (file.isFile) FileOutputStream(file, true).use { it.fd.sync() } }
            error.get() == null
        }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    fun recent(limit: Int = 2000): List<String> = synchronized(diskLock) {
        listOf(previous, file).flatMap { path ->
            runCatching {
                if (!path.isFile) emptyList() else RandomAccessFile(path, "r").use { input ->
                    val n = minOf(input.length(), maxFileBytes.toLong()).toInt()
                    input.seek(input.length() - n)
                    val bytes = ByteArray(n); input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
                }
            }.getOrDefault(emptyList())
        }.takeLast(limit)
    }

    fun health(): String = "journal_dropped_events=${synchronized(queueLock) { dropped }} journal_write_error=${error.get() ?: "none"}"

    override fun close() {
        synchronized(queueLock) { closed = true }
        flush(); writer.shutdown(); writer.awaitTermination(2, TimeUnit.SECONDS)
    }
}
