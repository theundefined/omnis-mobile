package com.theundefined.omnis.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.theundefined.omnis.data.model.Account

class AccountManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "omnis_accounts",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun getAccounts(): List<Account> {
        val jsonStr = sharedPreferences.getString("accounts", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Account>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCachedLoans(accountId: String): List<com.theundefined.omnis.data.model.Loan> {
        val jsonStr = sharedPreferences.getString("loans_$accountId", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<com.theundefined.omnis.data.model.Loan>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedLoans(accountId: String, loans: List<com.theundefined.omnis.data.model.Loan>) {
        val jsonStr = json.encodeToString(loans)
        sharedPreferences.edit().putString("loans_$accountId", jsonStr).apply()
    }

    fun addAccount(account: Account) {
        val accounts = getAccounts().toMutableList()
        accounts.removeAll { it.username == account.username && it.tenant.institution == account.tenant.institution }
        accounts.add(account)
        saveAccounts(accounts)
    }

    fun updateAccount(account: Account) {
        val accounts = getAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index != -1) {
            accounts[index] = account
            saveAccounts(accounts)
        }
    }

    fun removeAccount(account: Account) {
        val accounts = getAccounts().toMutableList()
        accounts.removeIf { it.id == account.id }
        saveAccounts(accounts)
        sharedPreferences.edit().remove("loans_${account.id}").apply()
    }

    private fun saveAccounts(accounts: List<Account>) {
        val jsonStr = json.encodeToString(accounts)
        sharedPreferences.edit().putString("accounts", jsonStr).apply()
    }
}
