package com.theundefined.omnis.guard

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.SearchBranchPrefs
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.searchKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistLogicTest {
    private val tenantA =
        Tenant(
            name = "Biblioteka A",
            baseUrl = "https://a.example",
            institution = "A",
            view = "A:A"
        )
    private val tenantB =
        Tenant(
            name = "Biblioteka B",
            baseUrl = "https://b.example",
            institution = "B",
            view = "B:B"
        )
    private val keyA = tenantA.searchKey()
    private val keyB = tenantB.searchKey()

    private fun branch(name: String, available: Boolean) =
        BranchAvailability(
            libraryName = name,
            libraryCode = name,
            subLocation = null,
            status = if (available) "available" else "unavailable"
        )

    private fun version(
        mmsid: String,
        branches: List<BranchAvailability> = emptyList(),
        type: String? = null,
        almaId: String? = "alma$mmsid"
    ) =
        BookVersion(
            mmsid = mmsid,
            title = "Lalka",
            author = "Prus",
            edition = null,
            publisher = null,
            publicationDate = null,
            isbns = emptyList(),
            frbrgroupid = "g1",
            branches = branches,
            resourceType = type,
            almaId = almaId
        )

    private fun result(vararg versions: BookVersion, frbr: String? = "g1") =
        SearchResult(frbr, "Lalka", "Prus", versions.toList())

    private fun watched(
        id: String = "w1",
        tenant: Tenant = tenantA,
        records: List<String> = listOf("r1"),
        known: List<String> = emptyList(),
        available: List<String> = emptyList(),
        checked: Long? = 100L,
        awaiting: Boolean = false,
        added: Long = 1L
    ) =
        WatchedTitle(
            id = id,
            tenant = tenant,
            recordIds = records,
            title = "T $id",
            addedAt = added,
            lastCheckedAt = checked,
            knownBranches = known,
            availableBranches = available,
            awaitingAvailability = awaiting
        )

    private fun delivery(vararg entries: Pair<String, List<BranchStatus>>): LibraryDelivery =
        entries.toMap()

    private fun st(branch: String, available: Boolean) = BranchStatus(branch, available)

    private val noPrefs = emptyMap<String, SearchBranchPrefs>()

    // --- identyfikator, wydania, dodawanie ---

    @Test
    fun watchIdIsByWorkNotByEdition() {
        assertEquals("$keyA|g:g1", watchIdOf(keyA, result(version("1"))))
        assertEquals("$keyA|g:g1", watchIdOf(keyA, result(version("1"), version("2"))))
        assertTrue(watchIdOf(keyA, result(version("1"), frbr = null)).startsWith("$keyA|t:lalka|"))
        assertTrue(watchIdOf(keyA, result(version("1"))) != watchIdOf(keyB, result(version("1"))))
    }

    @Test
    fun watchesPrintBooksUnlessMediaFilterSaysOtherwise() {
        val book = version("1")
        val audio = version("2", type = "Audiobook")
        val r = result(book, audio)
        assertEquals(listOf(book), watchedVersions(r, emptySet()))
        assertEquals(listOf(audio), watchedVersions(r, setOf("audiobook")))
        // Filtr, którego żadne wydanie nie spełnia, nie zeruje obserwacji.
        assertEquals(listOf(book), watchedVersions(r, setOf("video")))
        // Brak książek drukowanych -> wszystko, co jest.
        assertEquals(listOf(audio), watchedVersions(result(audio), emptySet()))
    }

    @Test
    fun newWatchedTitleIsSeededFromSearchResult() {
        val r =
            result(
                version("1", listOf(branch("Filia 1", false), branch("Filia 2", true))),
                version("2", listOf(branch("Filia 2", false), branch("Filia 3", true)))
            )
        val item = newWatchedTitle(tenantA, r, emptySet(), 500L, SearchBranchPrefs())!!
        assertEquals(listOf("alma1", "alma2"), item.recordIds)
        assertEquals(listOf("1", "2"), item.mmsids)
        assertEquals(listOf("Filia 1", "Filia 2", "Filia 3"), item.knownBranches)
        assertEquals(listOf("Filia 2", "Filia 3"), item.availableBranches)
        assertEquals(500L, item.lastCheckedAt)
        assertEquals(500L, item.addedAt)
        assertEquals(tenantA, item.tenant)
        // Dostępny już przy dodaniu -> nie ma o czym powiadamiać, dopóki nie zniknie.
        assertFalse(item.awaitingAvailability)
    }

    @Test
    fun titleAddedUnavailableIsAwaiting() {
        val r = result(version("1", listOf(branch("Filia 1", false))))
        val item = newWatchedTitle(tenantA, r, emptySet(), 1L, SearchBranchPrefs())!!
        assertTrue(item.awaitingAvailability)
    }

    @Test
    fun titleAvailableOnlyOutsidePreferredBranchesIsAwaiting() {
        val r = result(version("1", listOf(branch("Filia 1", false), branch("Filia 2", true))))
        val prefs = SearchBranchPrefs(setOf("Filia 1"), showAllBranches = false)
        assertTrue(newWatchedTitle(tenantA, r, emptySet(), 1L, prefs)!!.awaitingAvailability)
    }

    @Test
    fun cannotWatchWithoutRecordIds() {
        assertNull(
            newWatchedTitle(
                tenantA,
                result(version("1", almaId = null)),
                emptySet(),
                1L,
                SearchBranchPrefs()
            )
        )
    }

    @Test
    fun addRespectsLimitAndDuplicates() {
        val full = (1..MAX_WATCHED).map { watched(id = "w$it") }
        val (sameList, limit) = addWatched(full, watched(id = "new"))
        assertEquals(AddOutcome.LIMIT_REACHED, limit)
        assertEquals(full, sameList)

        val (dupList, dup) = addWatched(full, watched(id = "w3"))
        assertEquals(AddOutcome.ALREADY_WATCHED, dup)
        assertEquals(full, dupList)

        val (added, ok) = addWatched(full.drop(1), watched(id = "new"))
        assertEquals(AddOutcome.ADDED, ok)
        assertEquals(MAX_WATCHED, added.size)
        assertEquals("new", added.last().id)
    }

    // --- filie z preferencji ---

    @Test
    fun branchPrefsRestrictCountedBranches() {
        val title = watched(known = listOf("F1", "F2", "F3"), available = listOf("F2", "F3"))
        assertEquals(listOf("F2", "F3"), countedBranches(title, SearchBranchPrefs()))
        val only1 = SearchBranchPrefs(setOf("F1"), showAllBranches = false)
        assertEquals(emptyList<String>(), countedBranches(title, only1))
        val only3 = SearchBranchPrefs(setOf("F3"), showAllBranches = false)
        assertEquals(listOf("F3"), countedBranches(title, only3))
        // "Wszystkie filie" wygrywa z zapamiętanym wyborem.
        assertEquals(
            listOf("F2", "F3"),
            countedBranches(title, SearchBranchPrefs(setOf("F1"), showAllBranches = true))
        )
    }

    @Test
    fun selectionOfBranchesTheTitleLacksMeansUnavailable() {
        // Użytkownik wybrał tylko Filię 16, a tytuł jest w 2 i 56 — nie jest dostępny "dla niego",
        // mimo że żadna wybrana filia nie występuje wśród egzemplarzy tego tytułu.
        val title = watched(known = listOf("Filia 2", "Filia 56"), available = listOf("Filia 2"))
        val prefs = SearchBranchPrefs(setOf("Filia 16"), showAllBranches = false)
        assertTrue(countedBranches(title, prefs).isEmpty())
        val rows = watchRows(listOf(title), mapOf(keyA to prefs))
        assertEquals(WatchStatus.UNAVAILABLE, rows.single().status)
    }

    @Test
    fun emptySelectionDoesNotFilter() {
        val title = watched(known = listOf("F1"), available = listOf("F1"))
        val prefs = SearchBranchPrefs(emptySet(), showAllBranches = false)
        assertEquals(listOf("F1"), countedBranches(title, prefs))
    }

    // --- przejścia i powiadomienia ---

    private fun check(
        current: List<WatchedTitle>,
        deliveries: Map<String, LibraryDelivery>,
        prefs: Map<String, SearchBranchPrefs> = noPrefs,
        now: Long = 200L,
        notify: Boolean = true
    ) = applyChecks(current, deliveries, prefs, now, notify)

    @Test
    fun unavailableToAvailableNotifiesOnce() {
        val before = watched(known = listOf("F1"), available = emptyList(), awaiting = true)
        val first = check(listOf(before), mapOf(keyA to delivery("r1" to listOf(st("F1", true)))))
        assertEquals(listOf("w1"), first.toNotify.map { it.id })
        val t = first.titles.single()
        assertEquals(listOf("F1"), t.availableBranches)
        assertEquals(200L, t.lastCheckedAt)
        // Do czasu potwierdzenia wysłania flaga zostaje (nie wysłane -> ponowimy).
        assertTrue(t.awaitingAvailability)
        val retry = check(first.titles, mapOf(keyA to delivery("r1" to listOf(st("F1", true)))))
        assertEquals(1, retry.toNotify.size)

        // Po wysłaniu: kolejne dni z tytułem wciąż na półce nie powiadamiają.
        val marked = markNotified(first.titles, setOf("w1"))
        val next = check(marked, mapOf(keyA to delivery("r1" to listOf(st("F1", true)))))
        assertTrue(next.toNotify.isEmpty())
    }

    @Test
    fun notifiesAgainOnlyAfterTitleWasUnavailableAgain() {
        var list = listOf(watched(known = listOf("F1"), available = listOf("F1"), awaiting = false))
        // Dostępna -> nic.
        assertTrue(
            check(list, mapOf(keyA to delivery("r1" to listOf(st("F1", true))))).toNotify.isEmpty()
        )
        // Wypożyczona -> uzbrojenie.
        list = check(list, mapOf(keyA to delivery("r1" to listOf(st("F1", false))))).titles
        assertTrue(list.single().awaitingAvailability)
        assertTrue(list.single().availableBranches.isEmpty())
        // Wraca -> powiadomienie.
        val back = check(list, mapOf(keyA to delivery("r1" to listOf(st("F1", true)))))
        assertEquals(1, back.toNotify.size)
    }

    @Test
    fun firstObservationOfAvailableTitleDoesNotNotify() {
        val fresh = watched(checked = null, awaiting = false)
        val out = check(listOf(fresh), mapOf(keyA to delivery("r1" to listOf(st("F1", true)))))
        assertTrue(out.toNotify.isEmpty())
        assertEquals(200L, out.titles.single().lastCheckedAt)
        assertFalse(out.titles.single().awaitingAvailability)
    }

    @Test
    fun libraryErrorLeavesStateUntouchedAndSilent() {
        val a = watched(id = "a", tenant = tenantA, awaiting = true, known = listOf("F1"))
        val b = watched(id = "b", tenant = tenantB, awaiting = true, known = listOf("G1"))
        // Biblioteka A nie odpowiedziała (brak wpisu); B tak.
        val out = check(listOf(a, b), mapOf(keyB to delivery("r1" to listOf(st("G1", true)))))
        assertEquals(a, out.titles[0])
        assertEquals(listOf("b"), out.toNotify.map { it.id })
    }

    @Test
    fun recordMissingFromResponseLeavesTitleUntouched() {
        val t = watched(records = listOf("r1"), awaiting = true, known = listOf("F1"))
        val out = check(listOf(t), mapOf(keyA to delivery("other" to listOf(st("F1", true)))))
        assertEquals(t, out.titles.single())
        assertTrue(out.toNotify.isEmpty())
    }

    @Test
    fun recordWithoutHoldingsIsUnavailableNotUnknown() {
        val t = watched(known = listOf("F1"), available = listOf("F1"))
        val out = check(listOf(t), mapOf(keyA to delivery("r1" to emptyList())))
        val updated = out.titles.single()
        assertTrue(updated.awaitingAvailability)
        assertTrue(updated.availableBranches.isEmpty())
        assertTrue(updated.knownBranches.isEmpty())
    }

    @Test
    fun editionsAreMergedAcrossRecords() {
        val t = watched(records = listOf("r1", "r2"))
        val out =
            check(
                listOf(t),
                mapOf(
                    keyA to
                        delivery(
                            "r1" to listOf(st("F1", false), st("F1", false)),
                            "r2" to listOf(st("F2", true), st("F1", false))
                        )
                )
            )
        val updated = out.titles.single()
        assertEquals(listOf("F1", "F2"), updated.knownBranches)
        assertEquals(listOf("F2"), updated.availableBranches)
    }

    @Test
    fun preferredBranchesDecideWhatCountsAsAvailable() {
        val t = watched(known = listOf("F1", "F2"), available = emptyList(), awaiting = true)
        val delivery = mapOf(keyA to delivery("r1" to listOf(st("F1", false), st("F2", true))))
        val prefs = mapOf(keyA to SearchBranchPrefs(setOf("F1"), showAllBranches = false))
        // Wolne tylko w F2, a liczy się F1 -> bez powiadomienia.
        assertTrue(check(listOf(t), delivery, prefs).toNotify.isEmpty())
        // Bez preferencji ten sam stan powiadamia.
        assertEquals(1, check(listOf(t), delivery).toNotify.size)
    }

    @Test
    fun preferencesAreNotStoredSoChangingThemDoesNotRewriteState() {
        val t = watched(known = listOf("F1", "F2"), available = listOf("F2"))
        val out =
            check(
                listOf(t),
                mapOf(keyA to delivery("r1" to listOf(st("F1", false), st("F2", true)))),
                mapOf(keyA to SearchBranchPrefs(setOf("F1"), showAllBranches = false))
            )
        // Surowa dostępność zachowana — preferencje czytamy na bieżąco.
        assertEquals(listOf("F2"), out.titles.single().availableBranches)
    }

    @Test
    fun manualRefreshConsumesFlagWithoutNotification() {
        val t = watched(known = listOf("F1"), awaiting = true)
        val out =
            check(
                listOf(t),
                mapOf(keyA to delivery("r1" to listOf(st("F1", true)))),
                notify = false
            )
        assertTrue(out.toNotify.isEmpty())
        assertFalse(out.titles.single().awaitingAvailability)
    }

    @Test
    fun resultsMergeIntoCurrentListByIdNotWholesale() {
        // Lista z chwili pobierania miała w1 i w2; w międzyczasie użytkownik usunął w2 i dodał w3.
        val w1 = watched(id = "w1", records = listOf("r1"), awaiting = true, known = listOf("F1"))
        val w3 = watched(id = "w3", records = listOf("r3"), checked = 50L)
        val out =
            check(
                listOf(w1, w3),
                mapOf(
                    keyA to delivery("r1" to listOf(st("F1", true)), "r2" to listOf(st("F1", true)))
                )
            )
        assertEquals(listOf("w1", "w3"), out.titles.map { it.id })
        assertEquals(w3, out.titles[1])
        assertEquals(listOf("w1"), out.toNotify.map { it.id })
    }

    @Test
    fun markNotifiedAndRemoveTouchOnlyTargets() {
        val list = listOf(watched(id = "a", awaiting = true), watched(id = "b", awaiting = true))
        val marked = markNotified(list, setOf("a"))
        assertFalse(marked[0].awaitingAvailability)
        assertTrue(marked[1].awaitingAvailability)
        assertEquals(listOf("b"), removeWatched(list, "a").map { it.id })
        assertEquals(list, removeWatched(list, "zzz"))
    }

    // --- zapytania, wiersze, zapis ---

    @Test
    fun oneRequestPerLibraryWithAllRecords() {
        val list =
            listOf(
                watched(id = "1", tenant = tenantA, records = listOf("r1", "r2")),
                watched(id = "2", tenant = tenantB, records = listOf("r3")),
                watched(id = "3", tenant = tenantA, records = listOf("r2", "r4"))
            )
        val requests = libraryRequests(list)
        assertEquals(2, requests.size)
        assertEquals(listOf("r1", "r2", "r4"), requests.first { it.tenantKey == keyA }.recordIds)
        assertEquals(listOf("r3"), requests.first { it.tenantKey == keyB }.recordIds)
    }

    @Test
    fun requestUsesKnownTenantWhenAvailable() {
        val known = KNOWN_TENANTS.first()
        val stale = known.copy(baseUrl = "https://stale.example")
        val request = libraryRequests(listOf(watched(tenant = stale))).single()
        assertEquals(known, request.tenant)
        // Własna biblioteka (spoza listy) zostaje taka, jaką zapisano.
        assertEquals(tenantA, libraryRequests(listOf(watched(tenant = tenantA))).single().tenant)
    }

    @Test
    fun rowsShowStatusAndSortAvailableFirst() {
        val notChecked = watched(id = "n", checked = null, added = 5L)
        val unavailable = watched(id = "u", known = listOf("F1"), added = 4L)
        val noCopies = watched(id = "c", known = emptyList(), added = 3L)
        val available =
            watched(id = "a", known = listOf("F1"), available = listOf("F1"), added = 1L)
        val rows = watchRows(listOf(notChecked, unavailable, noCopies, available), noPrefs)
        assertEquals(listOf("a", "n", "u", "c"), rows.map { it.title.id })
        assertEquals(
            listOf(
                WatchStatus.AVAILABLE,
                WatchStatus.NOT_CHECKED,
                WatchStatus.UNAVAILABLE,
                WatchStatus.NO_COPIES
            ),
            rows.map { it.status }
        )
        assertEquals(listOf("F1"), rows[0].branches)
    }

    @Test
    fun rowsApplyBranchPrefs() {
        val t = watched(known = listOf("F1", "F2"), available = listOf("F2"))
        val prefs = mapOf(keyA to SearchBranchPrefs(setOf("F1"), showAllBranches = false))
        assertEquals(WatchStatus.UNAVAILABLE, watchRows(listOf(t), prefs).single().status)
        assertEquals(WatchStatus.AVAILABLE, watchRows(listOf(t), noPrefs).single().status)
    }

    @Test
    fun savedListRoundTripsAndOldMinimalRecordLoads() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val original = listOf(watched(id = "x", awaiting = true, known = listOf("F1")))
        assertEquals(
            original,
            json.decodeFromString<List<WatchedTitle>>(json.encodeToString(original))
        )

        val minimal =
            """[{"id":"x","tenant":{"name":"A","baseUrl":"https://a","institution":"I","view":"I:V"},"title":"T"}]"""
        val loaded = json.decodeFromString<List<WatchedTitle>>(minimal).single()
        assertNotNull(loaded)
        assertNull(loaded.lastCheckedAt)
        assertTrue(loaded.recordIds.isEmpty())
        assertFalse(loaded.awaitingAvailability)
    }
}
