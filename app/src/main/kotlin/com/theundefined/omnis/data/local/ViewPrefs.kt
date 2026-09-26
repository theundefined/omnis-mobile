package com.theundefined.omnis.data.local

import android.content.Context

/**
 * Zapamiętany wybór grupowania/sortowania list wypożyczeń. Zwykłe (nieszyfrowane) SharedPreferences
 * — to czyste preferencje UI, bez danych osobowych, w przeciwieństwie do `AccountManager`. Wartości
 * to nazwy enumów; nieznana/usunięta nazwa daje wartość domyślną.
 */
class ViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("omnis_view_prefs", Context.MODE_PRIVATE)

    fun <E : Enum<E>> get(key: String, default: E): E {
        val name = prefs.getString(key, null) ?: return default
        return default.declaringJavaClass.enumConstants?.firstOrNull { it.name == name } ?: default
    }

    fun put(key: String, value: Enum<*>) {
        prefs.edit().putString(key, value.name).apply()
    }
}
