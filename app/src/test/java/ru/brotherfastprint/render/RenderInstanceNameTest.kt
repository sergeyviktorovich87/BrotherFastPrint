package ru.brotherfastprint.render

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RenderInstanceNameTest {
    // External Android API contract, not a naming-style preference.
    private val acceptedByAndroid = Regex("[A-Za-z0-9_.]+")

    @Test fun uuidBasedInstanceNamesMeetTheAndroidContract() {
        val ids = listOf(UUID(0, 0), UUID(-1, -1), UUID(Long.MIN_VALUE, Long.MAX_VALUE), UUID.randomUUID())
        for (id in ids) {
            val oldName = "poc_$id"
            assertFalse("Regression fixture must reproduce the rejected 0.1.0 name", acceptedByAndroid.matches(oldName))
            val actual = RenderInstanceName.create(id)
            assertTrue("Android must accept $actual", acceptedByAndroid.matches(actual))
        }
    }

    @Test fun separateRendererInstancesKeepSeparateIdentifiers() {
        val first = RenderInstanceName.create(UUID(0, 1))
        val second = RenderInstanceName.create(UUID(0, 2))
        assertNotEquals(first, second)
    }
}
