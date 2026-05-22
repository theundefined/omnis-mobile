package com.theundefined.omnis.data.repository

import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.*
import com.theundefined.omnis.data.remote.OmnisApi
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

class OmnisRepository(private val accountManager: AccountManager) {

    private fun createClient(baseUrl: String): OmnisApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val cookieJar = object : CookieJar {
            private val cookies = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
                cookies.addAll(responseCookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .cookieJar(cookieJar)
            .followRedirects(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OmnisApi::class.java)
    }

    suspend fun loginAndAddAccount(username: String, password: String, tenant: Tenant): Result<Account> {
        return try {
            val api = createClient(tenant.baseUrl)
            api.getInitialCookies(tenant.view)
            
            val response = api.login(
                username = username,
                password = password,
                institution = tenant.institution,
                view = tenant.view,
                targetUrl = "${tenant.baseUrl}/discovery/search?vid=${tenant.view}"
            )

            if (response.isSuccessful) {
                val token = response.body()?.jwtData?.trim('"') ?: return Result.failure(Exception("No token"))
                
                // Decode JWT to get display name
                var displayName = username
                try {
                    val parts = token.split(".")
                    if (parts.size >= 2) {
                        val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
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
                    val actions = countersResponse.body()?.data?.listofactions?.action ?: emptyList()
                    actions.forEach { action ->
                        when (action.type) {
                            "Loans" -> loansCount = action.value.toIntOrNull() ?: 0
                            "Fines" -> finesAmount = action.value.toDoubleOrNull() ?: 0.0
                        }
                    }
                    // For display name, we would ideally parse JWT or fetch personal settings
                    // Omnis-py parses JWT: display_name = payload.get("displayName", "Unknown")
                }

                val account = Account(
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
                Result.failure(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAccountProfile(account: Account): Result<Account> {
        return try {
            val api = createClient(account.tenant.baseUrl)
            api.getInitialCookies(account.tenant.view)
            
            val loginResponse = api.login(
                username = account.username,
                password = account.password,
                institution = account.tenant.institution,
                view = account.tenant.view,
                targetUrl = "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
            )
            
            val token = loginResponse.body()?.jwtData?.trim('"') ?: return Result.failure(Exception("Auth failed"))
            
            // Decode JWT for name
            var displayName = account.displayName ?: account.username
            try {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
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

            val updatedAccount = account.copy(
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

    suspend fun getLoansForAccount(account: Account): Result<List<Loan>> {
        return try {
            val api = createClient(account.tenant.baseUrl)
            api.getInitialCookies(account.tenant.view)
            
            val loginResponse = api.login(
                username = account.username,
                password = account.password,
                institution = account.tenant.institution,
                view = account.tenant.view,
                targetUrl = "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
            )
            
            val token = loginResponse.body()?.jwtData?.trim('"') ?: return Result.failure(Exception("Auth failed"))
            
            val loansResponse = api.getLoans("Bearer $token")
            if (loansResponse.isSuccessful) {
                val apiLoans = loansResponse.body()?.data?.loans?.loan ?: emptyList()
                val loans = apiLoans.map { item ->
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
                }

                Result.success(loans)
            } else {
                Result.failure(Exception("Failed to fetch loans: ${loansResponse.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renewLoan(account: Account, loanId: String): Result<Unit> {
        return try {
            val api = createClient(account.tenant.baseUrl)
            // Re-login to get fresh token (simplification)
            val loginResponse = api.login(
                username = account.username,
                password = account.password,
                institution = account.tenant.institution,
                view = account.tenant.view,
                targetUrl = "${account.tenant.baseUrl}/discovery/search?vid=${account.tenant.view}"
            )
            val token = loginResponse.body()?.jwtData?.trim('"') ?: return Result.failure(Exception("Auth failed"))
            
            val response = api.renewLoan("Bearer $token", body = mapOf("id" to loanId))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Renew failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getAccounts() = accountManager.getAccounts()
    fun updateAccount(account: Account) = accountManager.updateAccount(account)
    fun removeAccount(account: Account) = accountManager.removeAccount(account)
}
