package com.theundefined.omnis.data.repository

import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.*
import com.theundefined.omnis.data.remote.OmnisApi
import java.util.UUID
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OmnisRepository(private val accountManager: AccountManager) {

    companion object {
        const val HISTORY_PAGE_SIZE = 50
    }

    private fun createClient(baseUrl: String): OmnisApi {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

        val cookieJar =
            object : CookieJar {
                private val cookies = mutableListOf<Cookie>()

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
                .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OmnisApi::class.java)
    }

    suspend fun loginAndAddAccount(
        username: String,
        password: String,
        tenant: Tenant
    ): Result<Account> {
        return try {
            val api = createClient(tenant.baseUrl)
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
                        loansCount = loansCount
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
            val api = createClient(account.tenant.baseUrl)
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
            ownerName = account.displayName ?: account.username
        )

    /** Pobiera WSZYSTKIE strony (przechodzi po `showmore`) — używane dla aktywnych wypożyczeń. */
    suspend fun getLoansForAccount(account: Account, type: String = "active"): Result<List<Loan>> {
        return try {
            val api = createClient(account.tenant.baseUrl)
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
            val api = createClient(account.tenant.baseUrl)
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

    suspend fun renewLoan(account: Account, loanId: String): Result<Unit> {
        return try {
            val api = createClient(account.tenant.baseUrl)
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

    fun getAccounts() = accountManager.getAccounts()

    fun updateAccount(account: Account) = accountManager.updateAccount(account)

    fun removeAccount(account: Account) = accountManager.removeAccount(account)

    fun getCachedLoans(accountId: String): List<Loan> = accountManager.getCachedLoans(accountId)

    fun saveCachedLoans(accountId: String, loans: List<Loan>) =
        accountManager.saveCachedLoans(accountId, loans)

    fun getCachedHistory(accountId: String): HistoryCacheEntry =
        accountManager.getCachedHistory(accountId)

    fun saveCachedHistory(accountId: String, entry: HistoryCacheEntry) =
        accountManager.saveCachedHistory(accountId, entry)

    fun clearCachedHistory(accountId: String) = accountManager.clearCachedHistory(accountId)
}
