package com.theundefined.omnis.data.model

import java.text.Collator
import java.util.Locale
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
    val preferredForSearch: Boolean = false,
    val timeoutSeconds: Long? = null,
    val isDemo: Boolean = false,
    val disabledByDemo: Boolean = false
) {
    val finesCurrency: String
        get() = _finesCurrency ?: "PLN"
}

private val accountNameCollator: Collator =
    Collator.getInstance(Locale("pl")).apply { strength = Collator.PRIMARY }

/**
 * Kolejność listy kont w ekranie Ustawień: alfabetycznie wg nazwy konta (z uwzględnieniem polskich
 * znaków diakrytycznych), z kontem demo zawsze na końcu — niezależnie od jego nazwy/tenanta.
 */
fun List<Account>.sortedForSettings(): List<Account> =
    sortedWith(
        compareBy<Account> { it.isDemo }
            .thenBy(accountNameCollator) { it.displayName ?: it.username }
    )
