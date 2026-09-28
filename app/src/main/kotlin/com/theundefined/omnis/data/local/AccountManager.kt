package com.theundefined.omnis.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theundefined.omnis.data.model.Account
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AccountManager(context: Context) {
    private val masterKey =
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val sharedPreferences =
        EncryptedSharedPreferences.create(
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

    fun getCachedHistory(accountId: String): com.theundefined.omnis.data.model.HistoryCacheEntry {
        val jsonStr =
            sharedPreferences.getString("history_$accountId", null)
                ?: return com.theundefined.omnis.data.model.HistoryCacheEntry()
        return try {
            json.decodeFromString<com.theundefined.omnis.data.model.HistoryCacheEntry>(jsonStr)
        } catch (e: Exception) {
            com.theundefined.omnis.data.model.HistoryCacheEntry()
        }
    }

    fun saveCachedHistory(
        accountId: String,
        entry: com.theundefined.omnis.data.model.HistoryCacheEntry
    ) {
        val jsonStr = json.encodeToString(entry)
        sharedPreferences.edit().putString("history_$accountId", jsonStr).apply()
    }

    fun clearCachedHistory(accountId: String) {
        sharedPreferences.edit().remove("history_$accountId").apply()
    }

    /**
     * Preferencje filtra filii wyszukiwania — klucz per BIBLIOTEKA (Tenant.searchKey()), nie per
     * konto: kilka kont dzielących ten sam katalog dzieli też te same preferencje. Dlatego (w
     * odróżnieniu od loans_$accountId/history_$accountId) nie jest czyszczony w removeAccount —
     * usunięcie jednego z kilku kont tej samej biblioteki nie powinno kasować preferencji filii dla
     * pozostałych.
     */
    fun getSearchBranchPrefs(
        tenantKey: String
    ): com.theundefined.omnis.data.model.SearchBranchPrefs {
        val jsonStr =
            sharedPreferences.getString("search_branches_$tenantKey", null)
                ?: return com.theundefined.omnis.data.model.SearchBranchPrefs()
        return try {
            json.decodeFromString<com.theundefined.omnis.data.model.SearchBranchPrefs>(jsonStr)
        } catch (e: Exception) {
            com.theundefined.omnis.data.model.SearchBranchPrefs()
        }
    }

    fun saveSearchBranchPrefs(
        tenantKey: String,
        prefs: com.theundefined.omnis.data.model.SearchBranchPrefs
    ) {
        val jsonStr = json.encodeToString(prefs)
        sharedPreferences.edit().putString("search_branches_$tenantKey", jsonStr).apply()
    }

    /**
     * Dane filii (adres/link do mapy) per biblioteka — klucz jak w getSearchBranchPrefs, z tego
     * samego powodu nie czyszczony przy usuwaniu konta. To publiczne dane z katalogu, nie osobowe.
     */
    fun getCachedBranchInfo(
        tenantKey: String
    ): Map<String, com.theundefined.omnis.data.model.BranchInfo> {
        val jsonStr =
            sharedPreferences.getString("branch_info_$tenantKey", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, com.theundefined.omnis.data.model.BranchInfo>>(
                jsonStr
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveCachedBranchInfo(
        tenantKey: String,
        branches: Map<String, com.theundefined.omnis.data.model.BranchInfo>
    ) {
        val jsonStr = json.encodeToString(branches)
        sharedPreferences.edit().putString("branch_info_$tenantKey", jsonStr).apply()
    }

    /**
     * Współrzędne filii, kluczowane BranchLocationRequest.key (link do Map albo zapytanie
     * geokodera) — wspólne dla wszystkich bibliotek, bo klucz już jednoznacznie wskazuje miejsce.
     */
    fun getCachedBranchCoordinates(): Map<String, com.theundefined.omnis.data.model.Coordinates> {
        val jsonStr = sharedPreferences.getString("branch_coordinates", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, com.theundefined.omnis.data.model.Coordinates>>(
                jsonStr
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveCachedBranchCoordinates(
        coordinates: Map<String, com.theundefined.omnis.data.model.Coordinates>
    ) {
        val jsonStr = json.encodeToString(coordinates)
        sharedPreferences.edit().putString("branch_coordinates", jsonStr).apply()
    }

    /**
     * Lokalna historia wyszukiwań, najnowsze pierwsze. W zaszyfrowanych prefs (a nie ViewPrefs), bo
     * zapytania mogą zdradzać zainteresowania użytkownika. Wspólna dla wszystkich kont — nie
     * czyszczona w removeAccount, tylko jawnie przez clearSearchHistory.
     */
    fun getSearchHistory(): List<com.theundefined.omnis.data.model.SearchHistoryEntry> {
        val jsonStr = sharedPreferences.getString("search_history", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<com.theundefined.omnis.data.model.SearchHistoryEntry>>(
                jsonStr
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSearchHistory(entries: List<com.theundefined.omnis.data.model.SearchHistoryEntry>) {
        val jsonStr = json.encodeToString(entries)
        sharedPreferences.edit().putString("search_history", jsonStr).apply()
    }

    fun clearSearchHistory() {
        sharedPreferences.edit().remove("search_history").apply()
    }

    /**
     * Biblioteki (Tenant.searchKey()), w których szuka ekran wyszukiwania. `null` = użytkownik
     * nigdy nie zmieniał wyboru — wtedy domyślnie biblioteki włączonych kont (OmnisViewModel).
     */
    fun getSearchTenantKeys(): Set<String>? {
        val jsonStr = sharedPreferences.getString("search_tenants", null) ?: return null
        return try {
            json.decodeFromString<Set<String>>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun saveSearchTenantKeys(keys: Set<String>) {
        sharedPreferences.edit().putString("search_tenants", json.encodeToString(keys)).apply()
    }

    fun addAccount(account: Account) {
        val accounts = getAccounts().toMutableList()
        accounts.removeAll {
            it.username == account.username && it.tenant.institution == account.tenant.institution
        }
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
        clearCachedHistory(account.id)
    }

    /**
     * Publiczne, żeby OmnisRepository mogło zapisać cały zastąpiony zestaw kont jednym atomowym
     * zapisem (np. applyDemoMode/exitDemoMode), zamiast wielu osobnych updateAccount().
     */
    fun saveAccounts(accounts: List<Account>) {
        val jsonStr = json.encodeToString(accounts)
        sharedPreferences.edit().putString("accounts", jsonStr).apply()
    }
}
