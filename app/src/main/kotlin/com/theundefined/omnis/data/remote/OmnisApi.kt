package com.theundefined.omnis.data.remote

import com.theundefined.omnis.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface OmnisApi {
    @GET("/discovery/search")
    suspend fun getInitialCookies(@Query("vid") view: String): Response<Unit>

    @POST("/primaws/suprimaLogin")
    @FormUrlEncoded
    suspend fun login(
        @Query("lang") lang: String = "pl",
        @Field("authenticationProfile") authProfile: String = "Alma",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("institution") institution: String,
        @Field("view") view: String,
        @Field("targetUrl") targetUrl: String
    ): Response<LoginResponse>

    // Token gościa — ten sam, którym oficjalny interfejs WWW Primo przegląda katalog bez logowania.
    // Body to literał stringu JSON (token w cudzysłowach), stąd ResponseBody zamiast modelu.
    @GET("/primaws/rest/pub/institution/{institution}/guestJwt")
    suspend fun getGuestJwt(
        @Path("institution") institution: String,
        @Query("viewId") view: String,
        @Query("targetUrl") targetUrl: String,
        @Query("isGuest") isGuest: Boolean = true,
        @Query("lang") lang: String = "pl"
    ): Response<ResponseBody>

    @GET("/primaws/rest/priv/myaccount/counters")
    suspend fun getCounters(
        @Header("Authorization") token: String,
        @Query("lang") lang: String = "pl"
    ): Response<CountersResponse>

    @GET("/primaws/rest/priv/myaccount/loans")
    suspend fun getLoans(
        @Header("Authorization") token: String,
        @Query("lang") lang: String = "pl",
        @Query("bulk") bulk: Int = 50,
        @Query("offset") offset: Int = 1,
        @Query("type") type: String = "active"
    ): Response<LoanResponse>

    @POST("/primaws/rest/priv/myaccount/renew_loans")
    suspend fun renewLoan(
        @Header("Authorization") token: String,
        @Query("lang") lang: String = "pl",
        @Body body: Map<String, String>
    ): Response<Any>

    @GET("/primaws/rest/priv/myaccount/requests")
    suspend fun getRequests(
        @Header("Authorization") token: String,
        @Query("lang") lang: String = "pl"
    ): Response<RequestsResponse>

    // Ścieżka i body przechwycone z akcji anulowania w oficjalnym UI (omnis-py, cancel_hold):
    // request_type to "holds" (liczba mnoga, jak klucz kategorii), nie "hold". Odpowiedź sukcesu
    // (zweryfikowana w omnis-py 2026-10-04) to koperta "status": "ok", "reply-code": "0000";
    // błąd bywa HTTP 200 z "status": "failed" — patrz primoFailureMessage.
    @POST("/primaws/rest/priv/myaccount/cancel_requests")
    suspend fun cancelRequest(
        @Header("Authorization") token: String,
        @Query("lang") lang: String = "pl",
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @GET("/primaws/rest/pub/pnxs")
    suspend fun searchPnxs(
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String
    ): Response<PnxsSearchResponse>

    @POST("/primaws/rest/pub/delivery")
    suspend fun getDelivery(
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String,
        @Body almaIds: List<String>
    ): Response<List<DeliveryItem>>

    // Pełny rekord z holdingami (delivery.holding) — publiczny, działa bez tokena.
    @GET("/primaws/rest/pub/pnxs/L/{recordId}")
    suspend fun getRecord(
        @Path("recordId") recordId: String,
        @Query("vid") view: String,
        @Query("lang") lang: String = "pl"
    ): Response<RecordResponse>

    @GET("/primaws/rest/pub/getPhysicalService/{bareMmsid}")
    suspend fun getPhysicalServiceId(
        @Path("bareMmsid") bareMmsid: String,
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String
    ): Response<PhysicalServiceResponse>

    @POST("/primaws/rest/priv/ILSServices/holdings/{physicalServiceId}")
    suspend fun getHoldingsStatus(
        @Path("physicalServiceId") physicalServiceId: String,
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String,
        @Body body: HoldingsStatusRequest
    ): Response<HoldingsStatusResponse>

    // Formularz rezerwacji jednego egzemplarza. [requestPath] to `link-to-service` z
    // ILSServices/holdings, użyty dosłownie (już niesie institution/hasHold/hasBooking w query).
    @GET
    suspend fun getHoldForm(
        @Url requestPath: String,
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String
    ): Response<HoldFormResponse>

    // Złożenie rezerwacji — ten sam URL co formularz. Sukces to tylko koperta
    // {"status": "ok", "reply-text": "ok"}, bez ID rezerwacji (omnis-py, zweryfikowane na żywo).
    @POST
    suspend fun placeHold(
        @Url requestPath: String,
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>
}
