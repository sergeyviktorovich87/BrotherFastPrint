package ru.brotherfastprint.printing

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class SupportJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun completedJobSurvivesNewJournalInstance() {
        val dir = temp.newFolder()
        SupportJournal(dir).use { journal ->
            journal.append("job_received pages=3")
            journal.append("render_end page=3")
            journal.append("tcp_end accepted_bytes=12345")
            journal.append("job_end sent=true")
            assertTrue(journal.flush())
        }
        SupportJournal(dir).use { journal ->
            assertEquals(listOf("job_received pages=3", "render_end page=3",
                "tcp_end accepted_bytes=12345", "job_end sent=true"), journal.recent())
        }
    }

    @Test fun rotationIsByteBoundedAndDoesNotSplitUtf8Characters() {
        val dir = temp.newFolder()
        SupportJournal(dir, maxFileBytes = 256).use { journal ->
            repeat(12) {
                journal.append("event=$it " + "Проверка 🔎".repeat(80))
                assertTrue(journal.flush())
            }
            val files = dir.listFiles()!!.filter { it.isFile }
            assertEquals(2, files.size)
            for (file in files) {
                assertTrue(file.length() <= 256)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                val content = decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
                assertTrue(content.startsWith("event="))
                assertTrue(content.endsWith("\n"))
            }
            assertTrue(journal.recent().last().startsWith("event=11 "))
        }
    }

    @Test fun loggingFailureIsReportedWithoutThrowingIntoPrintWorker() {
        val blocked = temp.newFile("not-a-directory")
        SupportJournal(blocked).use { journal ->
            journal.append("job_end sent=true")
            assertFalse(journal.flush())
            assertFalse(journal.health().contains("journal_write_error=none"))
            assertFalse(journal.health().contains(blocked.absolutePath))
        }
    }

    @Test fun boundedQueueKeepsLastJobEndUnderBurstAndControlsCannotForgeLines() {
        val dir = temp.newFolder()
        SupportJournal(dir, maxPending = 4).use { journal ->
            repeat(2000) { journal.append("event=$it") }
            journal.append("job_end sent=true\nforged=second_line\r")
            assertTrue(journal.flush())
            val last = journal.recent().last()
            assertEquals("job_end sent=true forged=second_line ", last)
            assertFalse(journal.recent().any { it.startsWith("forged=") })
        }
    }
}
