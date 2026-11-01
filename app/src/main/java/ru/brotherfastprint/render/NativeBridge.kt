package ru.brotherfastprint.render

import android.graphics.Bitmap

object NativeBridge {
    init { System.loadLibrary("fastprint") }
    external fun reset()
    external fun cancel()
    external fun appendMono(bitmap: Bitmap, rows: Int, absoluteY: Int, fd: Int)
    external fun encode(rasterFd: Int, hbpFd: Int, width: Int, height: Int): Long
    external fun beginDocument(hbpFd: Int, economy: Boolean, duplex: Boolean)
    external fun appendPage(rasterFd: Int, width: Int, height: Int): Long
    external fun finishDocument(): Long
    external fun abortDocument()
}
