package ru.brotherfastprint.core

/** Stack locations only. Throwable messages and filenames can contain private document data. */
object CrashSummary {
    fun format(error: Throwable, threadId: Long): String = buildString {
        append("java_crash thread_id=$threadId\n")
        var cause: Throwable? = error
        repeat(4) { depth ->
            val current = cause ?: return@buildString
            append("cause=$depth class=${identifier(current.javaClass.name)}\n")
            current.stackTrace.take(12).forEach { frame ->
                append("at ${identifier(frame.className)}.${identifier(frame.methodName)} line=${frame.lineNumber}\n")
            }
            cause = current.cause
        }
    }.take(8 * 1024)
    private fun identifier(value: String) = value.take(180).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.$<>" }
}
