package com.theundefined.omnis

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.MOCK_TENANT
import com.theundefined.omnis.data.model.applyDemoMode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Limit na operacje sieciowe przeciw omnis-mock. Darmowa instancja na Render budzi się ~50 s, a
 * repozytorium loguje się od nowa przy każdej operacji — stąd zapas.
 */
const val NETWORK_TIMEOUT_MS = 120_000L

private const val TAG = "DemoUiTest"

val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

fun str(@StringRes id: Int, vararg args: Any): String = targetContext.getString(id, *args)

/**
 * Czyści trwały stan aplikacji (konta, cache wypożyczeń, preferencje widoku, historię wyszukiwań)
 * PRZED uruchomieniem Activity, a z `seedDemoAccount = true` od razu zapisuje włączone konto demo —
 * tak jak po "Włącz tryb demo" w ustawieniach. Musi być zewnętrzną regułą w
 * [org.junit.rules.RuleChain] względem reguły Compose, która startuje Activity.
 */
class AppStateRule(private val seedDemoAccount: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                clearAppState()
                if (seedDemoAccount)
                    AccountManager(targetContext).saveAccounts(applyDemoMode(emptyList()))
                try {
                    base.evaluate()
                } finally {
                    clearAppState()
                }
            }
        }

    private fun clearAppState() {
        // deleteSharedPreferences czyści też cache SharedPreferences w procesie, więc kolejny
        // AccountManager/ViewPrefs zaczyna od zera.
        val prefsDir = File(targetContext.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach {
            targetContext.deleteSharedPreferences(it.nameWithoutExtension)
        }
    }
}

/** Budzi instancję omnis-mock przed testami, żeby pierwszy login nie wpadł w timeout klienta. */
fun wakeUpDemoServer() {
    val deadline = System.currentTimeMillis() + 180_000L
    while (System.currentTimeMillis() < deadline) {
        try {
            val connection =
                URL("${MOCK_TENANT.baseUrl}/healthz").openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            val code = connection.responseCode
            connection.disconnect()
            if (code == 200) return
            Log.w(TAG, "omnis-mock /healthz -> HTTP $code, ponawiam")
        } catch (e: Exception) {
            Log.w(TAG, "omnis-mock /healthz niedostępny: $e, ponawiam")
        }
        Thread.sleep(5_000)
    }
    Log.w(TAG, "omnis-mock nie obudził się w 3 min — testy prawdopodobnie przekroczą limity")
}

fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = NETWORK_TIMEOUT_MS) {
    waitUntil(timeoutMs) {
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

fun ComposeTestRule.waitForText(
    text: String,
    substring: Boolean = true,
    timeoutMs: Long = NETWORK_TIMEOUT_MS
) = waitForNode(hasText(text, substring = substring, ignoreCase = true), timeoutMs)

/**
 * Czeka, aż (pierwsza) leniwa lista na ekranie zawiera element z tekstem, i przewija do niego —
 * działa też dla elementów jeszcze niewyrenderowanych poza ekranem.
 */
fun ComposeTestRule.waitAndScrollToText(
    text: String,
    substring: Boolean = true,
    timeoutMs: Long = NETWORK_TIMEOUT_MS
) {
    waitUntil(timeoutMs) {
        runCatching {
                onAllNodes(hasScrollToNodeAction())
                    .onFirst()
                    .performScrollToNode(hasText(text, substring = substring, ignoreCase = true))
            }
            .isSuccess
    }
    waitForText(text, substring = substring, timeoutMs = 5_000)
}
