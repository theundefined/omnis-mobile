package com.theundefined.omnis.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    private val gson = Gson()

    fun getAccounts(): List<Account> {
        val json = sharedPreferences.getString("accounts", null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<Account>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
    }

    private fun saveAccounts(accounts: List<Account>) {
        val json = gson.toJson(accounts)
        sharedPreferences.edit().putString("accounts", json).apply()
    }
}
