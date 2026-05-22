package com.theundefined.omnis.data.remote

import com.theundefined.omnis.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface OmnisApi {
    @GET("/discovery/search")
    suspend fun getInitialCookies(
        @Query("vid") view: String
    ): Response<Unit>

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
}
