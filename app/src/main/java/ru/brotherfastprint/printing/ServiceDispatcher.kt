package ru.brotherfastprint.printing

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message

/** Print/Binder/network work must run on main without waiting for an Activity's UI barriers. */
object ServiceDispatcher {
    fun mainHandler(): Handler = if (Build.VERSION.SDK_INT >= 28) Handler.createAsync(Looper.getMainLooper())
    else object : Handler(Looper.getMainLooper()) {
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
            msg.isAsynchronous = true
            return super.sendMessageAtTime(msg, uptimeMillis)
        }
    }
}
