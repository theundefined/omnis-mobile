package com.theundefined.omnis.data.remote

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * Systemowy geokoder (Google Play Services na większości urządzeń) — zapasowe źródło położenia
 * filii, gdy biblioteka nie podała w Almie linku do Map. Blokujący: wołać poza wątkiem UI.
 */
class PlaceGeocoder(context: Context) {
    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context.applicationContext, Locale("pl", "PL")) else null

    // Wersja z callbackiem jest dopiero od API 33 (minSdk = 26), a ta synchroniczna, choć
    // oznaczona jako przestarzała, nadal działa na wszystkich wersjach.
    //
    // Wynik bez ulicy to zwykle środek miasta/dzielnicy (geokoder nie znalazł konkretnego
    // miejsca) — lepiej pokazać filię jako nieustaloną niż postawić pinezkę w złym miejscu.
    @Suppress("DEPRECATION")
    fun locate(query: String): Pair<Double, Double>? =
        try {
            geocoder
                ?.getFromLocationName(query, 3)
                ?.firstOrNull { it.thoroughfare != null }
                ?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null
        }
}
