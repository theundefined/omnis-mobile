package com.theundefined.omnis.data.model

import java.net.URLDecoder
import kotlinx.serialization.Serializable

/** Współrzędne filii — cache'owane trwale, bo filie praktycznie się nie przeprowadzają. */
@Serializable
data class Coordinates(val lat: Double, val lon: Double, val fetchedAtMillis: Long = 0L)

/**
 * Czego szukać, żeby ustalić położenie jednej filii: najpierw link do Map z holdingu (po
 * rozwinięciu skrótu zawiera współrzędne), potem kolejne zapytania do geokodera. `key` identyfikuje
 * miejsce, nie nazwę filii — np. "BG - Czytelnie" i "BG - Wypożyczalnia" mają ten sam link i ląduje
 * na nich jedna pinezka i jedno zapytanie.
 */
data class BranchLocationRequest(
    val key: String,
    val mapsUrl: String?,
    val geocodeQueries: List<String>
)

// `!3d{lat}!4d{lon}` to położenie samego miejsca; `@lat,lon` to środek widoku mapy (bywa
// przesunięty o kilkaset metrów), więc dopiero w drugiej kolejności.
private val PLACE_COORDS = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
private val VIEWPORT_COORDS = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
private val QUERY_COORDS =
    Regex("""[?&](?:q|query|ll|destination)=(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""")

/**
 * Współrzędne zaszyte w pełnym linku Google Maps. Skrócone linki (maps.app.goo.gl) ich nie mają —
 * trzeba je najpierw rozwinąć (OmnisRepository.resolveBranchLocations).
 */
fun coordinatesFromMapsUrl(url: String): Pair<Double, Double>? {
    val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    val match =
        PLACE_COORDS.find(decoded) ?: VIEWPORT_COORDS.find(decoded) ?: QUERY_COORDS.find(decoded)
    val lat = match?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
    val lon = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

private val CITY_IN_PARENS = Regex("""\(([^)]+)\)""")

/**
 * "Biblioteka Raczyńskich (Poznań)" -> "Poznań"; większość nazw w KNOWN_TENANTS ma miasto w
 * nawiasie.
 */
fun cityFromTenantName(tenantName: String?): String? =
    tenantName
        ?.let { CITY_IN_PARENS.find(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }

fun branchLocationRequest(
    tenantName: String?,
    branchName: String,
    address: String?,
    mapsUrl: String?
): BranchLocationRequest {
    val city = cityFromTenantName(tenantName)
    val queries =
        listOfNotNull(
                if (address != null && city != null) "$address, $city" else null,
                branchMapsQuery(tenantName, branchName, address)
            )
            .distinct()
    val usableUrl = mapsUrl?.trim()?.takeIf { it.isNotEmpty() && isMapsLink(it) }
    return BranchLocationRequest(
        key = usableUrl ?: "q:${queries.first()}",
        mapsUrl = usableUrl,
        geocodeQueries = queries
    )
}
