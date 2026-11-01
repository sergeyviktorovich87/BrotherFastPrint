package ru.brotherfastprint.render

import org.junit.Assert.*
import org.junit.Test

class PaperGeometryTest {
    @Test fun stripeOffsetsDoNotChangeScaleOrHorizontalPosition() {
        val first = PaperGeometry.transform(595, 842, 0)
        val next = PaperGeometry.transform(595, 842, 128)
        assertEquals(128f, first[5] - next[5], 0.001f)
        assertEquals(first[0], next[0], 0f)
        assertEquals(first[2], next[2], 0f)
        assertTrue(PaperGeometry.width in 4826..4828)
        assertTrue(PaperGeometry.height in 6815..6817)
    }
    @Test fun landscapeRotatesClockwiseWithoutMirroring() {
        val m = PaperGeometry.transform(842, 595, 0)
        assertEquals(0f, m[0], 0f)
        assertTrue(m[1] < 0)
        assertTrue(m[3] > 0)
        assertTrue(m[0] * m[4] - m[1] * m[3] > 0)
    }
    @Test fun longEdgeRotatesBackWithoutMirroringAndSwapsAsymmetricMargins() {
        for ((w, h) in listOf(595 to 842, 842 to 595)) {
            val front = PaperGeometry.transform(w, h, 0)
            val back = PaperGeometry.transform(w, h, 0, true)
            assertEquals(-front[0], back[0], 0.001f)
            assertEquals(-front[1], back[1], 0.001f)
            assertEquals(-front[3], back[3], 0.001f)
            assertEquals(-front[4], back[4], 0.001f)
            assertEquals(PaperGeometry.width.toFloat(), front[2] + back[2], 0.001f)
            assertEquals(PaperGeometry.height - 8f * 600f / 72f, front[5] + back[5], 0.002f)
            assertTrue(back[0] * back[4] - back[1] * back[3] > 0)
        }
    }
    @Test fun rotatedBackStripesStillCoverOneContinuousImage() {
        val first = PaperGeometry.transform(595, 842, 0, true)
        val last = PaperGeometry.transform(595, 842, 6784, true)
        assertEquals(6784f, first[5] - last[5], 0.001f)
        assertEquals(first[0], last[0], 0f)
        assertEquals(first[2], last[2], 0f)
    }
    @Test fun unsupportedDimensionsAreRejectedBeforeBitmapCreation() {
        assertThrows(IllegalArgumentException::class.java) { PaperGeometry.transform(0, 842, 0) }
        assertThrows(IllegalArgumentException::class.java) { PaperGeometry.transform(20000, 842, 0) }
    }
}
