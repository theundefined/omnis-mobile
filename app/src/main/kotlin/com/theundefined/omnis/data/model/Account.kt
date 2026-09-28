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
    val timeoutSeconds: Long? = null,
    val isDemo: Boolean = false,
    val disabledByDemo: Boolean = false,
    /** Numer karty ustawiony ręcznie; null = numer wynika z loginu (patrz [libraryCardNumber]). */
    val cardNumber: String? = null
) {
    val finesCurrency: String
        get() = _finesCurrency ?: "PLN"

    /**
     * Numer do kodu kreskowego karty bibliotecznej: ręcznie ustawiony albo login, jeśli wygląda jak
     * numer karty (np. "BR123456" w Raczyńskich). E-mail czy PESEL (11 cyfr) numerem karty nie są.
     */
    val libraryCardNumber: String?
        get() = cardNumber ?: username.takeIf { looksLikeCardNumber(it) }
}

private val CARD_NUMBER_REGEX = Regex("[A-Za-z0-9-]{4,30}")
private val PESEL_REGEX = Regex("\\d{11}")

fun looksLikeCardNumber(login: String): Boolean =
    CARD_NUMBER_REGEX.matches(login) && !PESEL_REGEX.matches(login)

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
