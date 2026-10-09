package com.theundefined.omnis

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.theundefined.omnis.data.model.MOCK_TENANT
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Zrzuty ekranu na stronę projektu i do README — NIE test regresji. Pomijany, dopóki runner nie
 * dostanie argumentu `storeScreenshots=true` (workflow `screenshots.yml`, uruchamiany ręcznie),
 * więc zwykłe `connectedDebugAndroidTest` w ui-tests.yml go nie odpala.
 *
 * Konto demo (omnis-mock) dla wszystkiego, co wymaga konta, plus jedno anonimowe wyszukanie w
 * prawdziwej bibliotece (Biblioteka Raczyńskich) z mapą filii — dlatego tylko ręcznie i rzadko,
 * żeby nie dokładać ruchu stronie biblioteki. Niczego nie przedłuża, nie rezerwuje ani nie anuluje:
 * okno rezerwacji jest otwierane i zamykane przed potwierdzeniem.
 *
 * Argument `night=true` przełącza aplikację w ciemny motyw; pliki mają przedrostek `store_light_`
 * albo `store_dark_` i numer kolejności.
 */
// Język per aplikacja (LocaleManager) jest od API 33.
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(OnDemandRule)
            .around(AppStateRule(seedDemoAccount = true))
            .around(composeRule)

    @Test
    fun loans() {
        composeRule.waitForText(DEMO_LOAN_TITLES.first())
        shot("01_loans")

        composeRule.onNodeWithText(str(R.string.group_by_account), substring = true).performClick()
        composeRule.onNodeWithText(str(R.string.group_by_branch)).performClick()
        composeRule.waitForText(DEMO_LOAN_TITLES.first(), timeoutMs = 5_000)
        shot("02_loans_by_branch")

        composeRule
            .onAllNodes(hasText(DEMO_LOAN_TITLES.first(), substring = true))
            .onFirst()
            .performClick()
        composeRule.waitForText(str(R.string.close), substring = false, timeoutMs = 5_000)
        shot("03_loan_details")
    }

    @Test
    fun holds() {
        openFromMoreMenu(R.string.holds_title)
        val statusPrefix = str(R.string.hold_status_label, "").trim()
        composeRule.waitForNode(
            hasText(statusPrefix, substring = true) or
                hasText(str(R.string.no_holds), substring = false)
        )
        shot("04_holds")
    }

    @Test
    fun libraryCard() {
        openFromMoreMenu(R.string.library_card_title)
        // Tytuł jest też pozycją menu — czekamy na przycisk wstecz, który ma tylko ekran karty.
        composeRule.waitForNode(hasContentDescription(str(R.string.cd_back)), timeoutMs = 5_000)
        settle(1_000)
        shot("05_library_card")
    }

    @Test
    fun history() {
        openFromMoreMenu(R.string.history_title)
        composeRule.waitAndScrollToText("Ostatni rejs wyobraźni")
        shot("06_history")
    }

    @Test
    fun placeHoldDialog() {
        openSearch()
        search("Nibylandii")
        composeRule.waitForText("Cienie Nibylandii")
        composeRule
            .onAllNodes(hasText(str(R.string.hold_place), substring = false))
            .onFirst()
            .performClick()
        composeRule.waitForText(
            str(R.string.hold_place_title),
            substring = false,
            timeoutMs = 5_000
        )

        // Tylko do ekranu potwierdzenia — przycisku "Zarezerwuj" w oknie NIGDY nie klikamy (konto
        // demo jest współdzielone). "Dalej" bywa nieaktywne, gdy trzeba wybrać filię — wtedy
        // zostaje zrzut z wyboru.
        val next = runCatching {
            composeRule
                .onNodeWithText(str(R.string.hold_place_next))
                .assertIsEnabled()
                .performClick()
            composeRule.waitForText(str(R.string.hold_place_back), substring = false)
        }
        shot("07_place_hold")
        if (next.isSuccess) {
            composeRule.onNodeWithText(str(R.string.hold_place_back)).performClick()
        }
        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
    }

    @Test
    fun searchRealLibrary() {
        openSearch()

        // Wybór biblioteki: dodaj Raczyńskich, odznacz demo. Chip pokazuje bibliotekę demo.
        composeRule.onNodeWithText(MOCK_TENANT.name, substring = true).performClick()
        composeRule.waitForText(str(R.string.search_libraries_title), timeoutMs = 5_000)
        val filter = composeRule.onAllNodes(hasSetTextAction()).onLast()
        filter.performTextInput("Pozna")
        composeRule.onNodeWithText(REAL_LIBRARY, substring = false).performClick()
        shot("08_library_picker")
        filter.performTextClearance()
        filter.performTextInput("Demo")
        composeRule.onNodeWithText(MOCK_TENANT.name, substring = false).performClick()
        composeRule.onNodeWithText(str(R.string.search_libraries_done)).performClick()

        search(REAL_QUERY)
        // Ikona mapy jest przy każdym wyniku — pojawia się dopiero z wynikami.
        composeRule.waitForNode(hasContentDescription(str(R.string.cd_show_on_map)))
        settle(8_000) // terminy zwrotu wypożyczonych egzemplarzy dochodzą po wynikach
        shot("09_search_results")

        // Przycisk mapy pierwszego wyniku bywa pod krawędzią ekranu — najpierw przewijamy.
        val mapButton = hasContentDescription(str(R.string.cd_show_on_map))
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(mapButton)
        composeRule.onAllNodes(mapButton).onFirst().performClick()
        settle(15_000) // geolokalizacja filii i kafelki OpenStreetMap
        shot("10_branch_map")
    }

    private fun openSearch() {
        composeRule.onNodeWithContentDescription(str(R.string.cd_search)).performClick()
        composeRule.waitForText(str(R.string.search_title), substring = false, timeoutMs = 5_000)
    }

    private fun search(query: String) {
        val field = composeRule.onAllNodes(hasSetTextAction()).onFirst()
        field.performTextInput(query)
        field.performImeAction()
    }

    private fun openFromMoreMenu(itemRes: Int) {
        composeRule.waitForText(DEMO_LOAN_TITLES.first())
        composeRule.onNodeWithContentDescription(str(R.string.cd_more_options)).performClick()
        composeRule.onNodeWithText(str(itemRes), substring = false).performClick()
    }

    private fun settle(millis: Long) {
        composeRule.waitForIdle()
        SystemClock.sleep(millis)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        takeScreenshot("store_${if (night) "dark" else "light"}_$name")
    }

    companion object {
        private const val REAL_LIBRARY = "Biblioteka Raczyńskich (Poznań)"
        private const val REAL_QUERY = "Ostatnie życzenie Sapkowski"

        private val arguments
            get() = InstrumentationRegistry.getArguments()

        private val enabled: Boolean
            get() = arguments.getString("storeScreenshots") == "true"

        /**
         * Pomija każdy test, gdy zrzuty nie są zamówione — per test, nie w @BeforeClass, bo
         * pominięcie testu jest raportowane jako "skipped" przewidywalnie (ui-tests to bramka
         * publikacji w Play). Zewnętrzna reguła: nie czyści stanu i nie startuje Activity.
         */
        private val OnDemandRule = TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    assumeTrue("Zrzuty tylko na żądanie (storeScreenshots=true)", enabled)
                    base.evaluate()
                }
            }
        }

        private val night: Boolean
            get() = arguments.getString("night") == "true"

        @BeforeClass
        @JvmStatic
        fun setUpDevice() {
            if (!enabled) return
            applyLocaleAndTheme()
            cleanStatusBar()
            wakeUpDemoServer()
        }

        /**
         * Polski interfejs niezależnie od języka emulatora (język per aplikacja, API 33+) i motyw z
         * argumentu `night`. Czekamy, aż konfiguracja dotrze do procesu — `str()` w testach czyta
         * zasoby kontekstu aplikacji.
         */
        private fun applyLocaleAndTheme() {
            targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags("pl-PL")
            targetContext
                .getSystemService(UiModeManager::class.java)
                .setApplicationNightMode(
                    if (night) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                )
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                val config = targetContext.resources.configuration
                val isNight =
                    config.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                if (config.locales[0].language == "pl" && isNight == night) return
                SystemClock.sleep(200)
            }
            // Bez tego zrzuty po cichu wyszłyby po angielsku albo w złym motywie.
            error("Język pl / motyw night=$night nie dotarł do aplikacji w 10 s")
        }

        /**
         * Czysty ekran: bez systemowych okien błędów, pasek stanu w trybie demo (stała godzina,
         * pełna bateria i zasięg, bez powiadomień).
         */
        private fun cleanStatusBar() {
            val demo = "am broadcast -a com.android.systemui.demo -e command"
            listOf(
                    // ANR-y systemu (np. launchera na świeżym emulatorze) zasłaniałyby zrzuty.
                    "settings put global hide_error_dialogs 1",
                    "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
                    "settings put global sysui_demo_allowed 1",
                    "$demo enter",
                    "$demo clock -e hhmm 1000",
                    "$demo battery -e level 100 -e plugged false",
                    "$demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4",
                    "$demo notifications -e visible false"
                )
                .forEach { shell(it) }
        }

        // Czytamy wyjście do końca — dopiero wtedy polecenie na pewno się wykonało.
        private fun shell(command: String) {
            val pfd =
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }
}
