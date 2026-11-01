package ru.brotherfastprint

import android.content.Context
import ru.brotherfastprint.core.Endpoint
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.core.RasterProfile

object PrinterConfig {
    const val NAME = "Brother HL-L2340DWR"
    const val LOCAL_ID = "brother_saved"
    fun endpoint(context: Context): Endpoint {
        val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
        return Endpoint.parse(prefs.getString("ip", "192.168.1.41")!!, prefs.getString("port", "9100")!!)
    }
    fun duplex(context: Context): Int = context.getSharedPreferences("printer", Context.MODE_PRIVATE).getInt("duplex", Duplex.NONE).takeIf { it in Duplex.modes } ?: Duplex.NONE
    fun saveDuplex(context: Context, mode: Int) {
        Duplex.requireValid(mode)
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit().putInt("duplex", mode).apply()
    }
    fun saveToner(context: Context, value: Boolean) {
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit().putBoolean("toner_save", value).apply()
    }
    fun rasterProfile(context: Context): Int = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
        .getInt("raster_profile", RasterProfile.DOCUMENTS).takeIf { it in RasterProfile.modes } ?: RasterProfile.DOCUMENTS
    fun saveRasterProfile(context: Context, mode: Int) {
        RasterProfile.requireValid(mode)
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit().putInt("raster_profile", mode).apply()
    }
    fun tonerSave(context: Context) = context.getSharedPreferences("printer", Context.MODE_PRIVATE).getBoolean("toner_save", false)
    fun save(context: Context, endpoint: Endpoint, tonerSave: Boolean) {
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit()
            .putString("ip", endpoint.host).putString("port", endpoint.port.toString())
            .putBoolean("toner_save", tonerSave).apply()
    }
}
