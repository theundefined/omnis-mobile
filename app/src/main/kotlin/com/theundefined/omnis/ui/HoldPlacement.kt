package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.HoldRequestOptions
import com.theundefined.omnis.data.model.PickupLocation
import com.theundefined.omnis.data.model.searchKey
import java.text.Collator
import java.util.Locale

enum class HoldPlacementPhase {
    SELECT, // wybór konta i filii — tylko dane z wyników wyszukiwania, bez sieci
    LOADING, // logowanie, egzemplarze filii, formularz, bieżące rezerwacje konta
    CONFIRM, // podsumowanie (+ wybór miejsca odbioru, gdy jest kilka) przed złożeniem
    PLACING,
    DONE
}

/**
 * Okno składania rezerwacji jednego wydania. Konto i filię wybiera użytkownik (nie zgadujemy, gdy
 * jest ich kilka — jak `--place-hold` w omnis-py); egzemplarz w filii dobieramy sami
 * ([pickHoldableItem]). `existingHold` = konto już ma rezerwację tego rekordu (ostrzeżenie, nie
 * blokada). W DONE `confirming` trwa, dopóki nowa rezerwacja nie pojawi się w myaccount/requests
 * (Primo dodaje ją z opóźnieniem); `placedHold == null` po potwierdzaniu = jeszcze jej nie widać.
 */
data class HoldPlacementState(
    val title: String,
    val version: BookVersion,
    val accounts: List<Account>,
    val branches: List<BranchAvailability>,
    val accountId: String? = accounts.singleOrNull()?.id,
    val branchIndex: Int? = if (branches.size == 1) 0 else null,
    val phase: HoldPlacementPhase = HoldPlacementPhase.SELECT,
    val options: HoldRequestOptions? = null,
    val pickup: PickupLocation? = null,
    val existingHold: Hold? = null,
    val confirming: Boolean = false,
    val placedHold: Hold? = null,
    val error: String? = null
) {
    val account: Account?
        get() = accounts.find { it.id == accountId }

    val branch: BranchAvailability?
        get() = branchIndex?.let { branches.getOrNull(it) }
}

/** Włączone konta w bibliotece sekcji wyników — tylko nimi można zarezerwować. */
fun holdAccounts(accounts: List<Account>, tenantKey: String): List<Account> =
    accounts.filter { it.isEnabled && it.tenant.searchKey() == tenantKey }

/** Filie wydania, w których da się zapytać o egzemplarze (jest holding), dostępne najpierw. */
fun holdBranches(version: BookVersion): List<BranchAvailability> =
    version.branches
        .filter { it.holding != null }
        .sortedWith(
            compareByDescending<BranchAvailability> { it.status == "available" }
                .thenBy(Collator.getInstance(Locale("pl"))) { it.libraryName }
        )

/**
 * Id, pod którymi myaccount/requests może zgłaszać rezerwacje tego wydania (lokalne i sieciowe).
 */
fun holdRecordIds(version: BookVersion, itemMmsid: String?): Set<String> =
    setOfNotNull(version.mmsid, itemMmsid, version.networkMmsid).filter { it.isNotBlank() }.toSet()

/** Rezerwacja, która pojawiła się po złożeniu — najpierw ta na ten rekord, inaczej jedyna nowa. */
fun findNewHold(beforeIds: Set<String>, holds: List<Hold>, recordIds: Set<String>): Hold? {
    val fresh = holds.filter { it.id !in beforeIds }
    return fresh.firstOrNull { it.mmsid in recordIds } ?: fresh.singleOrNull()
}
