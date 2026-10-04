package com.theundefined.omnis.data.repository

import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.*
import com.theundefined.omnis.data.remote.OmnisApi
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OmnisRepository(private val accountManager: AccountManager) {

    companion object {
        const val HISTORY_PAGE_SIZE = 50

        // Ten sam domyślny timeout co httpx.AsyncClient w omnis-py: ustawiony jawnie, zamiast
        // polegać na cichych domyślnych wartościach OkHttp (10s connect/read/write).
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        // Adresy filii praktycznie się nie zmieniają — raz na miesiąc wystarczy.
        const val BRANCH_INFO_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

        const val BRANCH_COORDINATES_TTL_MILLIS = 180L * 24 * 60 * 60 * 1000
    }

    // Osobny, goły klient do rozwijania skróconych linków do Map: bez logowania ciał i bez
    // ciasteczek Primo z createClient, przekierowań nie podąża (czytamy tylko nagłówek Location).
    private val redirectProbe by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun createClient(
        baseUrl: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): OmnisApi {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

        val cookieJar =
            object : CookieJar {
                private val cookies = java.util.concurrent.CopyOnWriteArrayList<Cookie>()

                override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
                    cookies.addAll(responseCookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies
            }

        val okHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(logging)
                .cookieJar(cookieJar)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(createPrimoGson()))
            .build()
            .create(OmnisApi::class.java)
    }

    suspend fun loginAndAddAccount(
        username: String,
        password: String,
        tenant: Tenant
    ): Result<Account> {
        return try {
            val api =
                createClient(
                    tenant.baseUrl,
                    tenant.defaultTimeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            api.getInitialCookies(tenant.view)

            val response =
                api.login(
                    username = username,
                    password = password,
                    institution = tenant.institution,
                    view = tenant.view,
                    targetUrl = "${tenant.baseUrl}/discovery/search?vid=${tenant.view}"
                )

            if (response.isSuccessful) {
                val token =
                    response.body()?.jwtData?.trim('"')
                        ?: return Result.failure(
                            Exception("Błąd: Nie otrzymano tokena autoryzacyjnego.")
                        )

                // Decode JWT to get display name
                var displayName = username
                try {
                    val parts = token.split(".")
                    if (parts.size >= 2) {
                        val payload =
                            String(
                                android.util.Base64.decode(
                                    parts[1],
                                    android.util.Base64.URL_SAFE or
                                        android.util.Base64.NO_PADDING or
                                        android.util.Base64.NO_WRAP
                                )
                            )
                        val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
                        displayName = json.get("displayName")?.asString ?: username
                    }
                } catch (e: Exception) {
                    // Fallback to username
                }

                // Get user info and counters
                val countersResponse = api.getCounters("Bearer $token")
                var finesAmount = 0.0
                var loansCount = 0

                if (countersResponse.isSuccessful) {
                    val actions =
                        countersResponse.body()?.data?.listofactions?.action ?: emptyList()
                    actions.forEach { action ->
                        when (action.type) {
                            "Loans" -> loansCount = action.value.toIntOrNull() ?: 0
                            "Fines" -> finesAmount = action.value.toDoubleOrNull() ?: 0.0
                        }
                    }
                }

                val account =
                    Account(
                        id = UUID.randomUUID().toString(),
                        username = username,
                        password = password,
                        tenant = tenant,
                        displayName = displayName,
                        finesAmount = finesAmount,
                        loansCount = loansCount,
                        isDemo = tenant.isDemo,
                        timeoutSeconds = tenant.defaultTimeoutSeconds
                    )
                accountManager.addAccount(account)
                Result.success(account)
            } else {
                val errorMessage =
                    when (response.code()) {
                        401 -> "Błędny login lub hasło."
                        403 -> "Brak uprawnień do konta."
                        404 -> "Nie odnaleziono serwera biblioteki."
                        else ->
                            "Błąd logowania (${response.code()}). Sprawdź dane i spróbuj ponownie."
                    }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Błąd połączenia: ${e.localizedMessage}"))
        }
    }

    suspend fun fetchAccountProfile(account: Account): Result<Account> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            api.getInitialCookies(account.tenant.view)

            val loginResponse =
                api.login(
                    username = account.username,
                    password = account.password,
                    institution = account.tenant.institution,
                    view = account.tenant.view,
                    targetUrl =
                        "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
                )

            val token =
                loginResponse.body()?.jwtData?.trim('"')
                    ?: return Result.failure(Exception("Błąd autoryzacji profilu."))

            // Decode JWT for name
            var displayName = account.displayName ?: account.username
            try {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payload =
                        String(
                            android.util.Base64.decode(
                                parts[1],
                                android.util.Base64.URL_SAFE or
                                    android.util.Base64.NO_PADDING or
                                    android.util.Base64.NO_WRAP
                            )
                        )
                    val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
                    displayName = json.get("displayName")?.asString ?: account.username
                }
            } catch (e: Exception) {}

            val countersResponse = api.getCounters("Bearer $token")
            var finesAmount = account.finesAmount
            var loansCount = account.loansCount

            if (countersResponse.isSuccessful) {
                val actions = countersResponse.body()?.data?.listofactions?.action ?: emptyList()
                actions.forEach { action ->
                    when (action.type) {
                        "Loans" -> loansCount = action.value.toIntOrNull() ?: loansCount
                        "Fines" -> finesAmount = action.value.toDoubleOrNull() ?: finesAmount
                    }
                }
            }

            val updatedAccount =
                account.copy(
                    displayName = displayName,
                    finesAmount = finesAmount,
                    loansCount = loansCount
                )
            accountManager.updateAccount(updatedAccount)
            Result.success(updatedAccount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wyszukiwanie w katalogu jest anonimowe (token gościa, jak w oficjalnym UI Primo) — działa w
     * każdej bibliotece z listy, bez konta w niej i bez wysyłania hasła.
     */
    private suspend fun guestToken(api: OmnisApi, tenant: Tenant): Result<String> {
        val response =
            api.getGuestJwt(
                institution = tenant.institution,
                view = tenant.view,
                targetUrl = "${tenant.baseUrl}/discovery/search?vid=${tenant.view}"
            )
        val token = response.body()?.string()?.trim()?.trim('"')
        if (!response.isSuccessful || token.isNullOrEmpty()) {
            return Result.failure(Exception("Błąd pobierania tokenu gościa: ${response.code()}"))
        }
        return Result.success(token)
    }

    private suspend fun loginForToken(api: OmnisApi, account: Account): Result<String> {
        api.getInitialCookies(account.tenant.view)
        val loginResponse =
            api.login(
                username = account.username,
                password = account.password,
                institution = account.tenant.institution,
                view = account.tenant.view,
                targetUrl = "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
            )
        val token =
            loginResponse.body()?.jwtData?.trim('"')
                ?: return Result.failure(Exception("Błąd autoryzacji podczas pobierania książek."))
        return Result.success(token)
    }

    private fun toLoan(item: LoanResponseItem, account: Account): Loan =
        Loan(
            id = item.id,
            mmsid = item.mmsid,
            title = item.title,
            author = item.author,
            dueDate = item.dueDate,
            dueHour = item.dueHour,
            loanDate = item.loanDate,
            status = item.status,
            libraryName = item.libraryName,
            locationName = item.locationName,
            subLocationName = item.subLocationName,
            barcode = item.barcode,
            renewable = item.renew == "Y",
            accountId = account.id,
            ownerName = account.displayName ?: account.username,
            tenantName = account.tenant.name,
            callNumber = item.callnumber2,
            year = item.year,
            itemCategoryName = item.itemcategoryname,
            maxRenewDate = item.maxrenewdate,
            renewStatuses = renewStatusMessages(item.renewstatuses),
            returnDate = item.returndate,
            returnHour = item.returnhour
        )

    /** Pobiera WSZYSTKIE strony (przechodzi po `showmore`) — używane dla aktywnych wypożyczeń. */
    suspend fun getLoansForAccount(account: Account, type: String = "active"): Result<List<Loan>> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val token =
                loginForToken(api, account).getOrElse {
                    return Result.failure(it)
                }

            val allItems = mutableListOf<LoanResponseItem>()
            var offset = 1
            while (true) {
                val loansResponse =
                    api.getLoans(
                        "Bearer $token",
                        bulk = HISTORY_PAGE_SIZE,
                        offset = offset,
                        type = type
                    )
                if (!loansResponse.isSuccessful) {
                    val errorMsg =
                        if (loansResponse.code() == 401) "Sesja wygasła lub błędne hasło."
                        else "Błąd pobierania danych: ${loansResponse.code()}"
                    return Result.failure(Exception(errorMsg))
                }
                val loansList = loansResponse.body()?.data?.loans
                allItems.addAll(loansList?.loan ?: emptyList())

                val showMore = loansList?.showmore
                if (showMore.isNullOrEmpty() || "Y" !in showMore) break
                offset += HISTORY_PAGE_SIZE
            }

            Result.success(allItems.map { toLoan(it, account) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uzupełnia wypożyczenia o serię i autora z rekordu katalogu (pub/pnxs/L/alma{mmsid}, ten sam
     * co w getBranchInfo). Rekord pobieramy tylko dla książek, których nie sprawdziliśmy wcześniej
     * — reszta przepisuje dane z `previous` (cache) po mmsid. Błąd sieci lub HTTP zostawia
     * catalogFetched=false (ponowna próba przy następnym odświeżeniu). Rekordu, którego nie ma w
     * katalogu (np. książka z innej biblioteki sieci), Primo nie zgłasza błędem, tylko HTTP 200 bez
     * `pnx` — to zapisuje się jako sprawdzone bez serii, żeby nie ponawiać w kółko.
     */
    suspend fun withCatalogDetails(
        account: Account,
        loans: List<Loan>,
        previous: List<Loan>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Loan> {
        val known =
            previous
                .filter { it.catalogFetched }
                .associate { it.mmsid to CatalogDetails(it.series, it.catalogAuthor) }
        val missing = loans.map { it.mmsid }.filter { it !in known }.distinct()
        val fetched =
            if (missing.isEmpty()) emptyMap()
            else {
                val api =
                    createClient(
                        account.tenant.baseUrl,
                        account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                    )
                val limit = Semaphore(4)
                val done = AtomicInteger(0)
                onProgress(0, missing.size)
                coroutineScope {
                    missing
                        .map { mmsid ->
                            async {
                                limit.withPermit {
                                    try {
                                        val response =
                                            api.getRecord("alma$mmsid", account.tenant.view)
                                        val pnx = response.body()?.pnx
                                        if (!response.isSuccessful) null
                                        else
                                            mmsid to
                                                CatalogDetails(
                                                    pnx?.addataFirst("seriestitle"),
                                                    pnx?.addataFirst("au")
                                                )
                                    } catch (e: Exception) {
                                        null
                                    } finally {
                                        onProgress(done.incrementAndGet(), missing.size)
                                    }
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                        .toMap()
                }
            }
        return loans.map { loan ->
            val details = known[loan.mmsid] ?: fetched[loan.mmsid] ?: return@map loan
            loan.copy(
                series = details.series,
                catalogAuthor = details.author,
                catalogFetched = true
            )
        }
    }

    private data class CatalogDetails(val series: String?, val author: String?)

    /**
     * Pobiera JEDNĄ stronę historii wypożyczeń (bez podążania za `showmore`) — pozwala UI
     * doczytywać kolejne strony na żądanie zamiast pobierać całą (potencjalnie wieloletnią)
     * historię naraz. Drugi element pary to informacja, czy istnieje kolejna strona.
     */
    suspend fun getLoanHistoryPage(
        account: Account,
        offset: Int,
        bulk: Int = HISTORY_PAGE_SIZE
    ): Result<Pair<List<Loan>, Boolean>> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val token =
                loginForToken(api, account).getOrElse {
                    return Result.failure(it)
                }

            val loansResponse =
                api.getLoans("Bearer $token", bulk = bulk, offset = offset, type = "history")
            if (!loansResponse.isSuccessful) {
                val errorMsg =
                    if (loansResponse.code() == 401) "Sesja wygasła lub błędne hasło."
                    else "Błąd pobierania danych: ${loansResponse.code()}"
                return Result.failure(Exception(errorMsg))
            }
            val loansList = loansResponse.body()?.data?.loans
            val loans = (loansList?.loan ?: emptyList()).map { toLoan(it, account) }
            val hasMore = loansList?.showmore?.let { "Y" in it } ?: false

            Result.success(loans to hasMore)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Adres i link do mapy filii [branchName] z holdingów rekordu [mmsid] (dowolnego egzemplarza
     * wypożyczonego z tej filii). Publiczny endpoint — bez logowania. Znaleziony wynik trafia do
     * cache'u per biblioteka na BRANCH_INFO_TTL_MILLIS. Result.success(null) = filii nie ma w
     * holdingach tego rekordu — tego nie cache'ujemy, bo inny rekord z tej filii może ją mieć.
     */
    suspend fun getBranchInfo(
        tenant: Tenant,
        mmsid: String,
        branchName: String,
        timeoutSeconds: Long? = null
    ): Result<BranchInfo?> {
        val tenantKey = tenant.searchKey()
        val cached = accountManager.getCachedBranchInfo(tenantKey)
        val now = System.currentTimeMillis()
        cached[branchName]
            ?.takeIf { now - it.fetchedAtMillis < BRANCH_INFO_TTL_MILLIS }
            ?.let {
                return Result.success(it)
            }
        return try {
            val api =
                createClient(
                    tenant.baseUrl,
                    timeoutSeconds ?: tenant.defaultTimeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val response = api.getRecord("alma$mmsid", tenant.view)
            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code()}"))
            }
            val holdings = response.body()?.delivery?.holding ?: emptyList()
            val info =
                branchInfoFromHoldings(holdings, branchName, now) ?: return Result.success(null)
            accountManager.saveCachedBranchInfo(tenantKey, cached + (branchName to info))
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Współrzędne z linku do Map. Skrócony link (maps.app.goo.gl) rozwijamy o jeden skok naraz,
     * czytając tylko nagłówek Location — pełny link ma już współrzędne, a samej strony
     * google.com/maps nie otwieramy (w UE bywa za nią ekran zgody na ciasteczka).
     */
    private fun coordinatesFromLink(url: String): Pair<Double, Double>? {
        var current = url
        repeat(3) {
            coordinatesFromMapsUrl(current)?.let {
                return it
            }
            val host = runCatching { URI(current).host }.getOrNull()?.lowercase() ?: return null
            if (host != "goo.gl" && !host.endsWith(".goo.gl")) return null
            val request = Request.Builder().url(current).head().build()
            current =
                redirectProbe.newCall(request).execute().use { it.header("Location") }
                    ?: return null
        }
        return coordinatesFromMapsUrl(current)
    }

    /**
     * Ustala położenie filii: cache, potem link do Map z holdingu, na końcu geokoder. Wynik każdej
     * filii trafia do [onResolved] od razu (null = nie udało się), żeby mapa mogła dokładać pinezki
     * na bieżąco. Nieudanych prób nie cache'ujemy.
     */
    suspend fun resolveBranchLocations(
        requests: List<BranchLocationRequest>,
        geocode: (String) -> Pair<Double, Double>?,
        onResolved: (key: String, coordinates: Coordinates?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val cached = accountManager.getCachedBranchCoordinates()
        val missing =
            requests
                .distinctBy { it.key }
                .filter { r ->
                    val hit =
                        cached[r.key]?.takeIf {
                            now - it.fetchedAtMillis < BRANCH_COORDINATES_TTL_MILLIS
                        }
                    if (hit != null) onResolved(r.key, hit)
                    hit == null
                }
        if (missing.isEmpty()) return

        val limit = Semaphore(4)
        val cacheLock = Mutex()
        coroutineScope {
            missing.forEach { r ->
                launch(Dispatchers.IO) {
                    val point =
                        limit.withPermit {
                            r.mapsUrl?.let { runCatching { coordinatesFromLink(it) }.getOrNull() }
                                ?: r.geocodeQueries.firstNotNullOfOrNull { geocode(it) }
                        }
                    val coordinates = point?.let { Coordinates(it.first, it.second, now) }
                    onResolved(r.key, coordinates)
                    if (coordinates != null) {
                        cacheLock.withLock {
                            accountManager.saveCachedBranchCoordinates(
                                accountManager.getCachedBranchCoordinates() + (r.key to coordinates)
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun renewLoan(account: Account, loanId: String): Result<Unit> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val loginResponse =
                api.login(
                    username = account.username,
                    password = account.password,
                    institution = account.tenant.institution,
                    view = account.tenant.view,
                    targetUrl =
                        "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
                )
            val token =
                loginResponse.body()?.jwtData?.trim('"')
                    ?: return Result.failure(Exception("Błąd autoryzacji podczas przedłużania."))

            val response = api.renewLoan("Bearer $token", body = mapOf("id" to loanId))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Przedłużenie nieudane: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rezerwacje (holds) konta — port `client.py::get_requests` (omnis-py), tylko kategoria hold.
     */
    suspend fun getHoldsForAccount(account: Account): Result<List<Hold>> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val token =
                loginForToken(api, account).getOrElse {
                    return Result.failure(it)
                }
            fetchHolds(api, token, account)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchHolds(
        api: OmnisApi,
        token: String,
        account: Account
    ): Result<List<Hold>> {
        val response = api.getRequests("Bearer $token")
        if (!response.isSuccessful) {
            val errorMsg =
                if (response.code() == 401) "Sesja wygasła lub błędne hasło."
                else "Błąd pobierania rezerwacji: ${response.code()}"
            return Result.failure(Exception(errorMsg))
        }
        val items = response.body()?.holdItems(createPrimoGson()) ?: emptyList()
        return Result.success(items.mapNotNull { it.toHold(account) })
    }

    /**
     * Port `client.py::cancel_hold` (omnis-py), plus ponowny odczyt rezerwacji tym samym tokenem —
     * kształt odpowiedzi sukcesu cancel_requests nie był nigdy zweryfikowany, więc sam kod 2xx nie
     * dowodzi, że rezerwacja zniknęła. Sukces niesie świeżą listę rezerwacji konta albo null, gdy
     * anulowanie przeszło, ale odczytu nie udało się zrobić.
     */
    suspend fun cancelHold(account: Account, requestId: String): Result<List<Hold>?> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val token =
                loginForToken(api, account).getOrElse {
                    return Result.failure(it)
                }
            val response =
                api.cancelRequest(
                    "Bearer $token",
                    body = mapOf("request_id" to requestId, "request_type" to "holds")
                )
            if (!response.isSuccessful) {
                return Result.failure(
                    Exception("Anulowanie rezerwacji nieudane: ${response.code()}")
                )
            }
            primoFailureMessage(response.body()?.string())?.let {
                return Result.failure(Exception(it))
            }
            val refreshed =
                try {
                    api.getRequests("Bearer $token")
                        .takeIf { it.isSuccessful }
                        ?.body()
                        ?.holdItems(createPrimoGson())
                        ?.mapNotNull { it.toHold(account) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            Result.success(refreshed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sesja jednego składania rezerwacji: jeden klient (z ciasteczkami) i jeden token na cały
     * przepływ — `link-to-service` egzemplarza niesie `physicalServiceId`, który zmienia się między
     * logowaniami, więc formularz i złożenie muszą iść w tej samej sesji, w której pobrano
     * egzemplarze. Trzymana tylko w pamięci, na czas jednego okna; po błędzie zaczynamy od nowa.
     */
    class HoldSession
    internal constructor(
        val account: Account,
        internal val api: OmnisApi,
        internal val token: String
    )

    suspend fun openHoldSession(account: Account): Result<HoldSession> {
        return try {
            val api =
                createClient(
                    account.tenant.baseUrl,
                    account.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            loginForToken(api, account).map { HoldSession(account, api, it) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Egzemplarze wydania [mmsid] w filii [holding], które da się zarezerwować — port
     * `client.py::get_holdable_items` (omnis-py), ale dla jednej, już wybranej filii: holding z
     * holKey mamy z wyników wyszukiwania, więc bez ponownego szukania rekordu i bez pytania o
     * wszystkie filie naraz. getPhysicalService zawsze świeży (zależy od sesji).
     */
    suspend fun getHoldableItems(
        session: HoldSession,
        mmsid: String,
        holding: Holding
    ): Result<List<HoldableItem>> {
        return try {
            val tenant = session.account.tenant
            val bearer = "Bearer ${session.token}"
            val serviceId =
                session.api
                    .getPhysicalServiceId(mmsid, physicalServiceParams(tenant, mmsid), bearer)
                    .body()
                    ?.physicalServiceId
                    ?: return Result.failure(
                        Exception("Biblioteka nie udostępnia egzemplarzy tego wydania.")
                    )
            val response =
                session.api.getHoldingsStatus(
                    serviceId,
                    mapOf("record-institution" to tenant.institution, "lang" to "pl"),
                    bearer,
                    holdingsRequest(tenant, mmsid, holding)
                )
            if (!response.isSuccessful) {
                return Result.failure(Exception("Błąd pobierania egzemplarzy: ${response.code()}"))
            }
            Result.success(response.body()?.holdableItems(createPrimoGson(), mmsid) ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Formularz rezerwacji egzemplarza (tylko odczyt) — port `client.py::get_hold_options`. */
    suspend fun getHoldOptions(
        session: HoldSession,
        item: HoldableItem
    ): Result<HoldRequestOptions> {
        return try {
            // Te same dodatkowe parametry, które wysyła UI Primo przy otwieraniu formularza.
            val params =
                mapOf(
                    "lang" to "pl",
                    "itemcategoryname" to (item.category ?: ""),
                    "itemid" to item.itemId,
                    "itemstatusname" to (item.statusName ?: ""),
                    "mainlocationname" to (item.mainLocation ?: ""),
                    "secondarylocationname" to (item.subLocation ?: ""),
                    "vid" to session.account.tenant.view
                )
            val response =
                session.api.getHoldForm(item.requestPath, params, "Bearer ${session.token}")
            if (!response.isSuccessful) {
                return Result.failure(
                    Exception("Błąd pobierania formularza rezerwacji: ${response.code()}")
                )
            }
            response.body()?.toOptions(item)?.let { Result.success(it) }
                ?: Result.failure(Exception("Ten egzemplarz nie oferuje rezerwacji."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getHolds(session: HoldSession): Result<List<Hold>> {
        return try {
            fetchHolds(session.api, session.token, session.account)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Złożenie rezerwacji — port `client.py::place_hold`; body jak w oficjalnym UI. Odpowiedź
     * sukcesu nie niesie ID, a nowa rezerwacja pojawia się w myaccount/requests z kilkusekundowym
     * opóźnieniem — potwierdzenie to zadanie wołającego (getHolds w tej samej sesji).
     */
    suspend fun placeHold(
        session: HoldSession,
        options: HoldRequestOptions,
        pickup: PickupLocation
    ): Result<Unit> {
        return try {
            val body =
                mapOf(
                    "requestType" to options.requestType,
                    "pickupLocation" to pickup.id,
                    "materialType" to options.materialType,
                    "itemId" to options.item.itemId,
                    "group_id" to options.item.mmsid,
                    "pickupLibraryId" to pickup.id,
                    "pickupType" to pickup.type
                )
            val response =
                session.api.placeHold(
                    options.item.requestPath,
                    mapOf("lang" to "pl"),
                    "Bearer ${session.token}",
                    body
                )
            if (!response.isSuccessful) {
                val errorMsg =
                    if (response.code() == 401) "Sesja wygasła — spróbuj ponownie."
                    else "Rezerwacja nieudana: ${response.code()}"
                return Result.failure(Exception(errorMsg))
            }
            primoFailureMessage(response.body()?.string())?.let {
                return Result.failure(Exception(it))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wyszukiwanie katalogu — port `client.py::search_books` (omnis-py). Zawsze zwraca WSZYSTKIE
     * filie dla wszystkich wydań (bez branch_filter po stronie serwera) — filtrowanie po
     * zaznaczonych filiach dzieje się po stronie klienta w ViewModelu (patrz
     * docs/plans/book-search.md §7), żeby zaznaczanie/odznaczanie checkboxów było natychmiastowe i
     * nie wymagało ponownego wyszukiwania za każdym kliknięciem.
     *
     * Token gościa pozyskany raz na początku i reużywany przez CAŁY pipeline (top search +
     * per-dzieło wyszukanie wydań + delivery, a potem fetchDueDates przez SearchPage.guestToken) —
     * nie pobierać nowego per pod-request. Terminy zwrotu NIE są tu pobierane — patrz
     * fetchDueDates.
     */
    suspend fun searchBooks(
        tenant: Tenant,
        query: String,
        offset: Int = 0,
        limit: Int = 10,
        field: SearchField = SearchField.ANY
    ): Result<SearchPage> {
        return try {
            val api =
                createClient(
                    tenant.baseUrl,
                    tenant.defaultTimeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                )
            val token =
                guestToken(api, tenant).getOrElse {
                    return Result.failure(it)
                }
            val bearer = "Bearer $token"

            fun buildParams(
                q: String,
                offset: Int = 0,
                qInclude: String = "",
                sort: String = "rank",
                limit: Int,
                cameFrom: String? = null
            ): Map<String, String> {
                val params =
                    mutableMapOf(
                        "acTriggered" to "false",
                        "blendFacetsSeparately" to "false",
                        "citationTrailFilterByAvailability" to "true",
                        "disableCache" to "false",
                        "getMore" to "0",
                        "inst" to tenant.institution,
                        "isCDSearch" to "false",
                        "lang" to "pl",
                        "limit" to limit.toString(),
                        "newspapersActive" to "false",
                        "newspapersSearch" to "false",
                        "offset" to offset.toString(),
                        "otbRanking" to "false",
                        "pcAvailability" to "true",
                        "q" to "${field.primoField},contains,$q",
                        "qExclude" to "",
                        "qInclude" to qInclude,
                        "rapido" to "false",
                        "refEntryActive" to "false",
                        "rtaLinks" to "true",
                        // MyInstitution (lokalny katalog instytucji) istnieje we wszystkich
                        // KNOWN_TENANTS; wcześniejsze MyInstitution2 miały tylko Raczyńscy, a
                        // reszta zwracała 400. `tab` Primo ignoruje (sprawdzone na żywo).
                        "scope" to "MyInstitution",
                        "searchInFulltextUserSelection" to "true",
                        "skipDelivery" to "Y",
                        "sort" to sort,
                        "tab" to "LibraryCatalog",
                        "vid" to tenant.view
                    )
                cameFrom?.let { params["came_from"] = it }
                return params
            }

            val topParams = buildParams(query, offset = offset, limit = limit)
            val topHttpResponse = api.searchPnxs(topParams, bearer)
            if (!topHttpResponse.isSuccessful) {
                val errorMsg =
                    if (topHttpResponse.code() == 401) "Sesja wygasła lub błędne hasło."
                    else
                        "Błąd wyszukiwania: ${topHttpResponse.code()} ${
                            topHttpResponse.errorBody()?.string()?.take(200) ?: ""
                        }"
                return Result.failure(Exception(errorMsg))
            }
            val topResponse = topHttpResponse.body()
            val topDocs = topResponse?.docs ?: emptyList()
            // info.total — pole do zweryfikowania (patrz docs/plans/book-search.md §6/§7).
            // Fallback bez niego: strona wróciła pełna (limit elementów) -> zakładamy, że może
            // być więcej; nadmiarowo ostrożne (jeden zbędny "Załaduj więcej" na końcu), ale
            // nigdy nie ucina wyników przedwcześnie.
            val hasMore =
                topResponse?.info?.total?.let { total -> offset + topDocs.size < total }
                    ?: (topDocs.size >= limit)

            // Krok 2: dociągnij WSZYSTKIE wydania per frbrgroupid, równolegle.
            data class Resolved(
                val versions: List<PnxDoc>,
                val deliveryById: Map<String, DeliveryItem>
            )
            val resolved = coroutineScope {
                topDocs
                    .map { doc ->
                        async {
                            val frbrgroupid = doc.pnx.frbrgroupid()
                            val (versionDocs, deliveryParams) =
                                if (frbrgroupid != null) {
                                    val groupParams =
                                        buildParams(
                                            query,
                                            qInclude = "facet_frbrgroupid,exact,$frbrgroupid",
                                            sort = "date_d",
                                            limit = 50,
                                            cameFrom = "addFacet"
                                        )
                                    val docs =
                                        api.searchPnxs(groupParams, bearer).body()?.docs?.takeIf {
                                            it.isNotEmpty()
                                        } ?: listOf(doc)
                                    docs to groupParams
                                } else listOf(doc) to topParams

                            val almaIds = versionDocs.mapNotNull { it.pnx.almaId() }.distinct()
                            val deliveryById =
                                if (almaIds.isNotEmpty()) {
                                    api.getDelivery(deliveryParams, bearer, almaIds)
                                        .body()
                                        ?.mapNotNull { item ->
                                            item.pnx.almaId()?.let { it to item }
                                        }
                                        ?.toMap() ?: emptyMap()
                                } else emptyMap()

                            Resolved(versionDocs, deliveryById)
                        }
                    }
                    .awaitAll()
            }

            // Zbierz wyniki + listę "brakujących dat" do dociągnięcia w fetchDueDates.
            val dueDateLookups = mutableListOf<DueDateLookup>()
            val results =
                topDocs.zip(resolved).map { (doc, r) ->
                    val frbrgroupid = doc.pnx.frbrgroupid()
                    val title =
                        doc.pnx.addataFirst("btitle") ?: doc.pnx.displayFirst("title") ?: "Unknown"
                    val author = doc.pnx.addataFirst("au")

                    val versions =
                        r.versions.map { v ->
                            val delivery = v.pnx.almaId()?.let { r.deliveryById[it] }
                            val holdings = delivery?.delivery?.holding ?: emptyList()
                            val branches =
                                holdings.mapIndexed { index, h ->
                                    val unavailable = h.availabilityStatus == "unavailable"
                                    if (unavailable) {
                                        dueDateLookups.add(
                                            DueDateLookup(v.pnx.bareMmsid(), index, h)
                                        )
                                    }
                                    BranchAvailability(
                                        libraryName = h.mainLocation,
                                        libraryCode = h.libraryCode,
                                        subLocation = h.subLocation,
                                        status = h.availabilityStatus,
                                        // Primo bywa, że daje "" zamiast null (np. UWr) —
                                        // pusty link otwierany jako VIEW wywalał aplikację.
                                        mapsUrl = h.stackMapUrl?.trim()?.takeIf { it.isNotEmpty() },
                                        dueDatePending = unavailable,
                                        holding = h
                                    )
                                }
                            BookVersion(
                                mmsid = v.pnx.bareMmsid(),
                                title = v.pnx.displayFirst("title") ?: title,
                                author = v.pnx.addataFirst("au") ?: author,
                                edition = v.pnx.displayFirst("edition"),
                                publisher = v.pnx.addataFirst("pub"),
                                publicationDate = v.pnx.addataFirst("date"),
                                isbns = v.pnx.addata["isbn"] ?: emptyList(),
                                frbrgroupid = frbrgroupid,
                                branches = branches,
                                resourceType = v.pnx.displayFirst("type"),
                                series = v.pnx.addataFirst("seriestitle"),
                                genres = v.pnx.display["genre"] ?: emptyList(),
                                subjects = v.pnx.display["subject"] ?: emptyList(),
                                language = v.pnx.displayFirst("language"),
                                physicalDescription = v.pnx.displayFirst("format"),
                                description = v.pnx.addataFirst("abstract"),
                                networkMmsid = v.pnx.control["originalsourceid"]?.firstOrNull()
                            )
                        }
                    SearchResult(frbrgroupid, title, author, versions)
                }

            Result.success(SearchPage(results, hasMore, dueDateLookups, token))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Termin zwrotu dla wypożyczonych egzemplarzy z wyników (SearchPage.dueDateLookups) — te same
     * zapytania co dotąd, tylko już po pokazaniu wyników: getPhysicalService raz na wydanie, potem
     * ILSServices/holdings per filia. Równoległość ogranicza OkHttp (5 zapytań naraz na host,
     * domyślnie) — celowo nie więcej niż strona biblioteki. `onResult` dostaje każdy egzemplarz
     * dokładnie raz: z datą albo z null, gdy Primo jej nie podało lub zapytanie się nie udało —
     * jeden wolny czy błędny egzemplarz nie psuje reszty wyników.
     */
    suspend fun fetchDueDates(
        tenant: Tenant,
        page: SearchPage,
        onResult: (DueDateLookup, DueDate?) -> Unit
    ) {
        val bearer = "Bearer ${page.guestToken ?: return}"
        val api =
            createClient(tenant.baseUrl, tenant.defaultTimeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS)
        coroutineScope {
            page.dueDateLookups
                .groupBy { it.bareMmsid }
                .forEach { (mmsid, lookups) ->
                    launch {
                        val serviceId =
                            try {
                                api.getPhysicalServiceId(
                                        mmsid,
                                        physicalServiceParams(tenant, mmsid),
                                        bearer
                                    )
                                    .body()
                                    ?.physicalServiceId
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                        lookups.forEach { lookup ->
                            if (serviceId == null) {
                                onResult(lookup, null)
                                return@forEach
                            }
                            launch {
                                val dueDate =
                                    try {
                                        holdingDueDate(api, tenant, bearer, serviceId, lookup)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        null
                                    }
                                onResult(lookup, dueDate)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun holdingDueDate(
        api: OmnisApi,
        tenant: Tenant,
        bearer: String,
        serviceId: String,
        lookup: DueDateLookup
    ): DueDate? {
        val status =
            api.getHoldingsStatus(
                    serviceId,
                    mapOf("record-institution" to tenant.institution, "lang" to "pl"),
                    bearer,
                    holdingsRequest(tenant, lookup.bareMmsid, lookup.holding)
                )
                .body()
        return status
            ?.data
            ?.itemInfo
            ?.locations
            ?.flatMap { it.items ?: emptyList() }
            ?.firstNotNullOfOrNull { item ->
                Regex("""(\d{2}/\d{2}/\d{4})""").find(item.itemstatusname)?.value?.let {
                    DueDate(it, item.itemstatusname.lowercase().contains("przekroczon"))
                }
            }
    }

    private fun physicalServiceParams(tenant: Tenant, bareMmsid: String): Map<String, String> =
        mapOf(
            "vid" to tenant.view,
            "lang" to "pl",
            "recordOwner" to "48OMNIS_NETWORK",
            "sourceRecordId" to bareMmsid,
            "resource_type" to "book",
            "isRapido" to "false"
        )

    // `locations` zawiera WYŁĄCZNIE tę jedną filię (z holKey) — z pełną listą holdingów Primo
    // zwraca pustą listę egzemplarzy (patrz CLAUDE.md omnis-py).
    private fun holdingsRequest(tenant: Tenant, bareMmsid: String, holding: Holding) =
        HoldingsStatusRequest(
            filters =
                HoldingsFilters(
                    sublibrary = holding.mainLocation,
                    holid = holding.holdId ?: "",
                    sublibs = holding.mainLocation,
                    ilsRecordList = listOf(IlsRecordRef(tenant.institution, bareMmsid)),
                    vid = tenant.view
                ),
            locations = listOf(holding)
        )

    fun getAccounts() = accountManager.getAccounts()

    fun updateAccount(account: Account) = accountManager.updateAccount(account)

    fun removeAccount(account: Account) = accountManager.removeAccount(account)

    fun enterDemoMode() = accountManager.saveAccounts(applyDemoMode(accountManager.getAccounts()))

    fun exitDemoMode() = accountManager.saveAccounts(exitDemoMode(accountManager.getAccounts()))

    fun getCachedLoans(accountId: String): List<Loan> = accountManager.getCachedLoans(accountId)

    fun saveCachedLoans(accountId: String, loans: List<Loan>) =
        accountManager.saveCachedLoans(accountId, loans)

    fun getCachedHistory(accountId: String): HistoryCacheEntry =
        accountManager.getCachedHistory(accountId)

    fun saveCachedHistory(accountId: String, entry: HistoryCacheEntry) =
        accountManager.saveCachedHistory(accountId, entry)

    fun clearCachedHistory(accountId: String) = accountManager.clearCachedHistory(accountId)

    fun getSearchBranchPrefs(tenantKey: String): SearchBranchPrefs =
        accountManager.getSearchBranchPrefs(tenantKey)

    fun saveSearchBranchPrefs(tenantKey: String, prefs: SearchBranchPrefs) =
        accountManager.saveSearchBranchPrefs(tenantKey, prefs)

    fun getSearchHistory(): List<SearchHistoryEntry> = accountManager.getSearchHistory()

    fun saveSearchHistory(entries: List<SearchHistoryEntry>) =
        accountManager.saveSearchHistory(entries)

    fun clearSearchHistory() = accountManager.clearSearchHistory()

    fun getSearchTenantKeys(): Set<String>? = accountManager.getSearchTenantKeys()

    fun saveSearchTenantKeys(keys: Set<String>) = accountManager.saveSearchTenantKeys(keys)
}
