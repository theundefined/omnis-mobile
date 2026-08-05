package com.theundefined.omnis.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val username: String,
    val password: String,
    val tenant: Tenant,
    val displayName: String? = null,
    val isEnabled: Boolean = true,
    val finesAmount: Double = 0.0,
    private val _finesCurrency: String? = "PLN",
    val loansCount: Int = 0,
    val preferredForSearch: Boolean = false
) {
    val finesCurrency: String
        get() = _finesCurrency ?: "PLN"
}
