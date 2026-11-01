package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class RasterProfileTest {
    @Test fun documentsRemainTheDefault() {
        assertEquals(RasterProfile.DOCUMENTS, PrintOptions().rasterProfile)
        assertArrayEquals(intArrayOf(0, 1), RasterProfile.modes)
    }
    @Test fun photoDoesNotChangeCopiesRangesOrDuplex() {
        val options = PrintOptions(2, intArrayOf(0, 2), duplex = Duplex.LONG_EDGE, rasterProfile = RasterProfile.PHOTO)
        assertArrayEquals(intArrayOf(0, 1, 2, PagePlan.BLANK, 0, 1, 2, PagePlan.BLANK), PagePlan.create(3, options))
    }
    @Test fun profileNamesAreBoundedTechnicalValues() {
        assertEquals("documents_bayer8", RasterProfile.technicalName(0))
        assertEquals("photo_cluster106_gamma08", RasterProfile.technicalName(1))
    }
    @Test(expected = IllegalArgumentException::class) fun sandboxRejectsUnknownProfile() {
        PagePlan.create(1, PrintOptions(rasterProfile = 2))
    }
}
