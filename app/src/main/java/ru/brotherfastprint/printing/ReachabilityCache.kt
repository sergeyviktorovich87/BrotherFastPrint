package ru.brotherfastprint.printing

import android.content.Context
import ru.brotherfastprint.core.Endpoint

/** Persist only a positive TCP check, never a stale OFFLINE flag. No document data is stored. */
object ReachabilityCache {
    const val TIME_KEY = "reachable_at"
    private const val ENDPOINT_KEY = "reachable_endpoint"
    private const val MAX_AGE = 5 * 60_000L
    private fun key(endpoint: Endpoint) = "${endpoint.host}:${endpoint.port}"
    fun markOnline(context: Context, endpoint: Endpoint) {
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit()
            .putString(ENDPOINT_KEY, key(endpoint)).putLong(TIME_KEY, System.currentTimeMillis()).apply()
    }
    fun recentlyOnline(context: Context, endpoint: Endpoint): Boolean {
        val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong(TIME_KEY, 0)
        return prefs.getString(ENDPOINT_KEY, null) == key(endpoint) && age in 0..MAX_AGE
    }
}
