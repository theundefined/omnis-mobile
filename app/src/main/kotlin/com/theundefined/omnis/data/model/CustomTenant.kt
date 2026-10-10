package com.theundefined.omnis.data.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder

/**
 * Profil logowania Primo, którego używamy, gdy tenant nie mówi inaczej (wszystkie z KNOWN_TENANTS).
 */
const val DEFAULT_AUTH_PROFILE = "Alma"

/** Przykładowy link do katalogu, pokazywany jako podpowiedź przy dodawaniu własnej biblioteki. */
const val EXAMPLE_CATALOG_LINK =
    "https://omnis-br.primo.exlibrisgroup.com/discovery/search?vid=48OMNIS_BRP:BRACZ"

/**
 * Biblioteka spoza KNOWN_TENANTS, wyczytana z linku do jej katalogu Primo VE. Każda strona katalogu
 * (wyszukiwanie, rekord, konto) ma w adresie `vid=INSTYTUCJA:WIDOK`, a instytucja to część `vid`
 * przed dwukropkiem — więc jeden wklejony link wystarcza, żeby zbudować [Tenant].
 */
data class CatalogLink(
    val baseUrl: String,
    val institution: String,
    val view: String,
    /** Link do nowego interfejsu NDE (`/nde/...`) — logowanie w tej apce go nie obsługuje. */
    val isNde: Boolean
)

// Kody instytucji i widoków Primo to litery, cyfry, '_' i '-' (np. 48OMNIS_BRP:BRACZ,
// 48OMNIS_UJA:uja).
// Walidujemy ściśle, bo widok trafia niezakodowany do ścieżki URL (patrz
// OmnisApi.getViewConfiguration).
private val VIEW_REGEX = Regex("^([A-Za-z0-9_-]+):([A-Za-z0-9_-]+)$")

fun parseCatalogLink(input: String): CatalogLink? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    val host = uri.host?.lowercase() ?: return null

    val vid =
        uri.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == "vid" }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
            ?.trim() ?: return null
    val match = VIEW_REGEX.matchEntire(vid) ?: return null

    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return CatalogLink(
        baseUrl = "https://$host$port",
        institution = match.groupValues[1],
        view = vid,
        isNde = uri.path.orEmpty().startsWith("/nde")
    )
}

/**
 * Fragment konfiguracji widoku Primo (`/primaws/rest/pub/configuration/vid/{vid}`), który nas
 * obchodzi.
 */
data class PrimoViewConfig(
    val institutionName: String?,
    val institutionCode: String?,
    /** Profile logowania: nazwa profilu -> system (ALMA = login i hasło, SAML/CAS/OAUTH = SSO). */
    val authProfiles: List<Pair<String, String>>
)

fun parsePrimoViewConfig(json: String): PrimoViewConfig? {
    val root =
        runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null
    val institution = root.obj("primo-view")?.obj("institution")
    val profiles =
        root
            .get("authentication")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val name = o.str("profile-name") ?: return@mapNotNull null
                name to (o.str("authentication-system") ?: "")
            }
    // Bez sekcji authentication to nie jest konfiguracja widoku Primo VE.
    if (institution == null && profiles == null) return null
    return PrimoViewConfig(
        institutionName = institution?.str("description"),
        institutionCode = institution?.str("institution-code"),
        authProfiles = profiles.orEmpty()
    )
}

/** Powód, dla którego własnej biblioteki nie da się dodać — każdy z osobnym komunikatem w UI. */
sealed class CustomTenantError(message: String) : Exception(message) {
    class NotPrimo(link: CatalogLink) :
        CustomTenantError(
            if (link.isNde)
                "Ten link prowadzi do nowego interfejsu Primo (NDE), którego aplikacja jeszcze nie obsługuje."
            else "Pod adresem ${link.baseUrl} nie ma katalogu Primo VE z widokiem ${link.view}."
        )

    class SsoOnly :
        CustomTenantError(
            "Ta biblioteka loguje tylko przez zewnętrzny system (np. konto uczelniane / SSO). " +
                "Aplikacja obsługuje wyłącznie logowanie loginem i hasłem do konta bibliotecznego."
        )
}

/**
 * Buduje [Tenant] z linku i konfiguracji widoku. Jeśli link wskazuje bibliotekę, która już jest w
 * [known], zwraca tamten wpis — z ładną nazwą i bez zbędnego dubla w wyszukiwarce.
 */
fun resolveCustomTenant(
    link: CatalogLink,
    config: PrimoViewConfig?,
    known: List<Tenant> = KNOWN_TENANTS
): Result<Tenant> {
    if (config == null) return Result.failure(CustomTenantError.NotPrimo(link))
    val institution = config.institutionCode?.takeIf { it.isNotBlank() } ?: link.institution
    known
        .firstOrNull { !it.isDemo && it.institution == institution && it.view == link.view }
        ?.let {
            return Result.success(it)
        }

    // Profil systemu ALMA = lokalne konto biblioteczne (login + hasło), jedyne, które umiemy
    // obsłużyć.
    val almaProfile =
        config.authProfiles.firstOrNull { it.second.equals("ALMA", ignoreCase = true) }?.first
            ?: return Result.failure(CustomTenantError.SsoOnly())

    return Result.success(
        Tenant(
            name =
                config.institutionName?.takeIf { it.isNotBlank() }
                    ?: link.baseUrl.removePrefix("https://"),
            baseUrl = link.baseUrl,
            institution = institution,
            view = link.view,
            authProfile = almaProfile.takeIf { it != DEFAULT_AUTH_PROFILE }
        )
    )
}

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString
