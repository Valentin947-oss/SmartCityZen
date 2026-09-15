package mk.smartcityzen.app.util

import android.content.Context
import android.graphics.Color
import mk.smartcityzen.app.model.MobilityType

private const val PREFS_NAME = "smart_cityzen_prefs"
private const val KEY_MOBILITY_TYPE = "mobility_type"

/** The citizen's own way of moving through the city — saved once, used everywhere
 *  (colors the live location icon, pre-selects the mobility field when reporting). */
object MobilityPrefs {

    fun get(context: Context): MobilityType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MOBILITY_TYPE, MobilityType.WALKING.name)
        return MobilityType.values().find { it.name == saved } ?: MobilityType.WALKING
    }

    fun set(context: Context, type: MobilityType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MOBILITY_TYPE, type.name)
            .apply()
    }

    /** Distinct color per mobility type — none of these repeat the pin colors already
     *  used elsewhere on the map (start=green, end=orange, problem=red, institution=blue,
     *  parking=purple, route line=brand purple). */
    fun colorFor(type: MobilityType): Int = when (type) {
        MobilityType.WALKING -> Color.parseColor("#212121")
        MobilityType.WHEELCHAIR -> Color.parseColor("#1565C0")
        MobilityType.STROLLER -> Color.parseColor("#EC407A")
        MobilityType.SCOOTER -> Color.parseColor("#00897B")
    }
}
