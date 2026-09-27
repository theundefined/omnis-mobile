package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.BranchLocationRequest
import com.theundefined.omnis.data.model.Coordinates
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.branchLocationRequest
import com.theundefined.omnis.data.model.isMapsLink
import com.theundefined.omnis.data.model.looksLikeAddress
import kotlin.math.roundToLong

/** Egzemplarz(e) jednego wydania w jednej filii — wiersz w dymku pinezki. */
data class MapHolding(val version: BookVersion, val branch: BranchAvailability)

/** Filia z wyniku wyszukiwania, zebrana ze wszystkich wydań. */
data class MapBranch(
    val name: String,
    val address: String?,
    val location: BranchLocationRequest,
    val holdings: List<MapHolding>
)

/** Pinezka — jedno miejsce na mapie; kilka filii/księgozbiorów w tym samym budynku to jedna. */
data class MapPin(val lat: Double, val lon: Double, val branches: List<MapBranch>)

/**
 * Okno mapy dla jednego wyniku wyszukiwania. `coordinates[key] == null` przy kluczu obecnym w mapie
 * = położenia nie udało się ustalić; brak klucza = jeszcze w trakcie.
 */
data class BranchMapState(
    val title: String,
    val branches: List<MapBranch>,
    val coordinates: Map<String, Coordinates?> = emptyMap()
) {
    val isResolving: Boolean
        get() = branches.any { it.location.key !in coordinates }

    val unresolvedBranches: List<MapBranch>
        get() =
            branches.filter {
                it.location.key in coordinates && coordinates[it.location.key] == null
            }

    val pins: List<MapPin>
        get() = mapPins(branches, coordinates)
}

fun mapBranchesOf(result: SearchResult, tenantName: String?): List<MapBranch> =
    result.versions
        .flatMap { v -> v.branches.map { MapHolding(v, it) } }
        .groupBy { it.branch.libraryName.trim() }
        .map { (name, holdings) ->
            val address =
                holdings.firstNotNullOfOrNull { h ->
                    h.branch.subLocation?.trim()?.takeIf { looksLikeAddress(it) }
                }
            val mapsUrl =
                holdings.firstNotNullOfOrNull { h ->
                    h.branch.mapsUrl?.trim()?.takeIf { it.isNotEmpty() && isMapsLink(it) }
                }
            MapBranch(
                name,
                address,
                branchLocationRequest(tenantName, name, address, mapsUrl),
                holdings
            )
        }

/** Czy jest z czego ustalić położenie choć jednej filii — inaczej przycisk mapy nie ma sensu. */
fun hasMappableBranches(result: SearchResult): Boolean =
    result.versions.any { v ->
        v.branches.any { b ->
            looksLikeAddress(b.subLocation) || (b.mapsUrl?.let { isMapsLink(it.trim()) } == true)
        }
    }

// Różne linki do tego samego budynku dają współrzędne różniące się na dalszych miejscach po
// przecinku — ~1 m (5 miejsc) wystarczy, żeby je skleić, a nie połączyć sąsiednich budynków.
private fun Double.rounded(): Long = (this * 100_000).roundToLong()

fun mapPins(branches: List<MapBranch>, coordinates: Map<String, Coordinates?>): List<MapPin> =
    branches
        .mapNotNull { b -> coordinates[b.location.key]?.let { it to b } }
        .groupBy { (c, _) -> c.lat.rounded() to c.lon.rounded() }
        .values
        .map { group ->
            val (c, _) = group.first()
            MapPin(c.lat, c.lon, group.map { it.second })
        }

/** Najlepszy status spośród egzemplarzy pinezki — decyduje o jej kolorze. */
enum class PinStatus {
    AVAILABLE,
    BORROWED,
    OVERDUE,
    UNKNOWN
}

fun MapPin.status(): PinStatus {
    val all = branches.flatMap { b -> b.holdings.map { it.branch } }
    return when {
        all.any { it.status == "available" } -> PinStatus.AVAILABLE
        all.any { it.status == "unavailable" && it.dueDate != null && !it.overdue } ->
            PinStatus.BORROWED
        all.any { it.status == "unavailable" && it.dueDate != null } -> PinStatus.OVERDUE
        else -> PinStatus.UNKNOWN
    }
}
