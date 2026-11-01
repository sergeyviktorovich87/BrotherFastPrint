package ru.brotherfastprint.core

/** Persisted IDs also cross the sandbox/JNI boundary; keep the values stable. */
object RasterProfile {
    const val DOCUMENTS = 0
    const val PHOTO = 1
    val modes = intArrayOf(DOCUMENTS, PHOTO)
    fun requireValid(mode: Int) { require(mode == DOCUMENTS || mode == PHOTO) }
    fun label(mode: Int): String = when (mode) {
        DOCUMENTS -> "Документы"
        PHOTO -> "Фото · улучшенные полутона"
        else -> throw IllegalArgumentException("Invalid raster profile")
    }
    fun technicalName(mode: Int): String = when (mode) {
        DOCUMENTS -> "documents_bayer8"
        PHOTO -> "photo_cluster106_gamma08"
        else -> throw IllegalArgumentException("Invalid raster profile")
    }
}
