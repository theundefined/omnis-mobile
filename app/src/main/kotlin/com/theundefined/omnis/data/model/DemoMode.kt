package com.theundefined.omnis.data.model

import java.util.UUID

// Jawnie nie-sekretne, stałe dane logowania konta demo omnis-mock (te same co w omnis-py/cli.py i
// w SPEC.md samego omnis-mock).
const val DEMO_USERNAME = "demo"
const val DEMO_PASSWORD = "demo1234"

fun buildDemoAccount(existingId: String? = null): Account =
    Account(
        id = existingId ?: UUID.randomUUID().toString(),
        username = DEMO_USERNAME,
        password = DEMO_PASSWORD,
        tenant = MOCK_TENANT,
        isEnabled = true,
        isDemo = true,
        timeoutSeconds = MOCK_TENANT.defaultTimeoutSeconds
    )

/**
 * Port `_apply_demo_mode` z omnis-py/cli.py: wyłącza wszystkie włączone konta nie-demo (oznaczając
 * je `disabledByDemo=true`, żeby [exitDemoMode] wiedziało co przywrócić), i zapewnia dokładnie
 * jedno włączone konto demo — reużywając istniejące (zachowując jego `id`) albo dopisując nowe.
 * Idempotentne: drugie wywołanie nie tworzy duplikatu.
 */
fun applyDemoMode(accounts: List<Account>): List<Account> {
    val disabledOthers =
        accounts.map { account ->
            if (!account.isDemo && account.isEnabled) {
                account.copy(isEnabled = false, disabledByDemo = true)
            } else {
                account
            }
        }
    val existingDemo = disabledOthers.find { it.isDemo }
    return if (existingDemo != null) {
        disabledOthers.map { account ->
            if (account.id == existingDemo.id) buildDemoAccount(existingId = account.id)
            else account
        }
    } else {
        disabledOthers + buildDemoAccount()
    }
}

/**
 * Port `_exit_demo_mode` z omnis-py/cli.py: wyłącza konto(a) demo, i przywraca (`isEnabled=true`,
 * czyści `disabledByDemo`) tylko konta oznaczone `disabledByDemo=true` — świadomie nie rusza kont
 * wyłączonych ręcznie przed [applyDemoMode].
 */
fun exitDemoMode(accounts: List<Account>): List<Account> =
    accounts.map { account ->
        when {
            account.isDemo && account.isEnabled -> account.copy(isEnabled = false)
            account.disabledByDemo -> account.copy(isEnabled = true, disabledByDemo = false)
            else -> account
        }
    }
