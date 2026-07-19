package com.theundefined.omnis.data.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tenant(val name: String, val baseUrl: String, val institution: String, val view: String)

data class UserInfo(
    val displayName: String,
    val userName: String,
    val loansCount: Int = 0,
    val requestsCount: Int = 0,
    val finesAmount: Double = 0.0,
    val finesCurrency: String = "PLN"
)

@Serializable
data class Loan(
    @SerializedName("loanid") @SerialName("loanid") val id: String,
    val mmsid: String,
    val title: String,
    val author: String?,
    @SerializedName("duedate") @SerialName("duedate") val dueDate: String,
    @SerializedName("duehour") @SerialName("duehour") val dueHour: String,
    @SerializedName("loandate") @SerialName("loandate") val loanDate: String,
    @SerializedName("loanstatus") @SerialName("loanstatus") val status: String,
    @SerializedName("ilsinstitutionname") @SerialName("ilsinstitutionname") val libraryName: String,
    @SerializedName("mainlocationname") @SerialName("mainlocationname") val locationName: String,
    @SerializedName("secondarylocationname")
    @SerialName("secondarylocationname")
    val subLocationName: String?,
    @SerializedName("itembarcode") @SerialName("itembarcode") val barcode: String,
    val renewable: Boolean = false,
    var accountId: String? = null,
    var ownerName: String? = null
)

data class LoanResponse(val data: LoanData)

data class LoanData(val loans: LoansList)

data class LoansList(
    @SerializedName("loan") val loan: List<LoanResponseItem>,
    val showmore: List<String>?
)

data class LoanResponseItem(
    @SerializedName("loanid") val id: String,
    val mmsid: String,
    val title: String,
    val author: String?,
    @SerializedName("duedate") val dueDate: String,
    @SerializedName("duehour") val dueHour: String,
    @SerializedName("loandate") val loanDate: String,
    @SerializedName("loanstatus") val status: String,
    @SerializedName("ilsinstitutionname") val libraryName: String,
    @SerializedName("mainlocationname") val locationName: String,
    @SerializedName("secondarylocationname") val subLocationName: String?,
    @SerializedName("itembarcode") val barcode: String,
    val renew: String?
)

data class LoginResponse(val jwtData: String?)

data class CountersResponse(val data: CountersData)

data class CountersData(val listofactions: ListOfActions)

data class ListOfActions(val action: List<CounterAction>)

data class CounterAction(val type: String, val value: String)
