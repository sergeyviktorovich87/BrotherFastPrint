package ru.brotherfastprint.render

import java.util.UUID

/** Context.bindIsolatedService accepts only ASCII letters, digits, underscores and periods. */
internal object RenderInstanceName {
    fun create(id: UUID = UUID.randomUUID()): String = "poc_${id.toString().replace("-", "")}"
}
