package com.theundefined.omnis.data.model

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.Serializable

/**
 * To, co Primo mówi o filii — bez scrapowania stron konkretnej biblioteki, więc działa dla każdego
 * tenanta. Źródło: holding z `/primaws/rest/pub/pnxs/L/alma{mmsid}` (patrz
 * OmnisRepository.getBranchInfo). Godzin otwarcia/telefonu Primo nie udostępnia wcale.
 *
 * Oba pola są opcjonalne, bo zależą od konfiguracji danej biblioteki w Almie: część wypełnia
 * stackMapUrl (link do Google Maps), część nie; subLocation bywa adresem ("ul. Druskienicka 32"), a
 * bywa nazwą działu ("Wypożyczalnia") — stąd filtr looksLikeAddress.
 */
@Serializable
data class BranchInfo(
    val address: String? = null,
    val mapsUrl: String? = null,
    val fetchedAtMillis: Long = 0L
)

/** Odpowiedź `/pub/pnxs/L/{recordId}` — interesuje nas tylko delivery (pnx pomijamy celowo). */
data class RecordResponse(val delivery: Delivery? = null)

private val ADDRESS_MARKER =
    Regex("""(?i)(^|[\s,;(\-])(ul|os|al|pl)\.\s*\p{L}|\b(aleje|aleja|plac|rondo)\s+\p{L}""")

/** Czy subLocation wygląda na adres, a nie na nazwę działu/księgozbioru. */
fun looksLikeAddress(text: String?): Boolean = text != null && ADDRESS_MARKER.containsMatchIn(text)

/**
 * Wybiera z holdingów rekordu ten, który odpowiada filii wypożyczenia (Loan.locationName ==
 * Holding.mainLocation), i wyciąga z niego adres/link do mapy. Jedna filia może mieć kilka
 * holdingów (różne księgozbiory) — wolimy ten z adresem i mapą. null, gdy filii nie ma na liście
 * (np. wypożyczenie z innej biblioteki sieci).
 */
fun branchInfoFromHoldings(
    holdings: List<Holding>,
    branchName: String,
    nowMillis: Long
): BranchInfo? {
    val wanted = branchName.trim()
    val matching =
        holdings
            .filter { it.mainLocation.trim().equals(wanted, ignoreCase = true) }
            .ifEmpty {
                return null
            }
    val best =
        matching.maxBy {
            (if (looksLikeAddress(it.subLocation)) 2 else 0) +
                (if (!it.stackMapUrl.isNullOrBlank()) 1 else 0)
        }
    return BranchInfo(
        address = best.subLocation?.trim()?.takeIf { looksLikeAddress(it) },
        mapsUrl = best.stackMapUrl?.trim()?.takeIf { it.isNotEmpty() },
        fetchedAtMillis = nowMillis
    )
}

/** Czy link prowadzi do Google Maps (Łódź np. podaje tu stronę z listą filii). */
fun isMapsLink(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "goo.gl" ||
        host.endsWith(".goo.gl") ||
        (host.contains("google.") && url.contains("/maps"))
}

/** Zapytanie do wyszukiwarki map, gdy biblioteka nie podała własnego linku. */
fun branchMapsQuery(tenantName: String?, branchName: String, address: String?): String =
    listOfNotNull(tenantName, branchName, address).joinToString(", ")

fun mapsSearchUrl(query: String): String =
    "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(query, "UTF-8")
