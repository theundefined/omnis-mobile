package com.theundefined.omnis

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description

private const val TAG = "TestScreenshots"

/**
 * Katalog na zrzuty ekranu. AGP przekazuje runnerowi `additionalTestOutputDir` i po
 * `connectedAndroidTest` ściąga jego zawartość do
 * `app/build/outputs/connected_android_test_additional_output/` — stamtąd CI wrzuca je jako artefakt.
 * Bez tego argumentu (np. uruchomienie z IDE innym runnerem) zapisujemy w zewnętrznym katalogu
 * aplikacji, skąd można je ściągnąć `adb pull`.
 */
private val screenshotDir: File by lazy {
    val base =
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: targetContext.getExternalFilesDir(null)
            ?: targetContext.filesDir
    File(base, "screenshots").apply { mkdirs() }
}

/**
 * Zrzut całego ekranu urządzenia (przez [android.app.UiAutomation], więc obejmuje też menu, dialogi i
 * pasek systemowy — w odróżnieniu od `captureToImage()` z Compose). Błąd zapisu tylko logujemy:
 * zrzut jest diagnostyką i nie może wywrócić testu.
 */
fun takeScreenshot(name: String) {
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    try {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (bitmap == null) {
            Log.w(TAG, "takeScreenshot() zwrócił null dla $safeName")
            return
        }
        val file = File(screenshotDir, "$safeName.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i(TAG, "Zapisano ${file.absolutePath}")
    } catch (e: Exception) {
        Log.w(TAG, "Nie udało się zapisać zrzutu $safeName", e)
    }
}

/** Zrzut po ustaniu animacji/rekompozycji, z nazwą `Klasa_metoda_krok`. */
fun ComposeTestRule.screenshot(step: String) {
    waitForIdle()
    takeScreenshot("${currentTestName ?: "unknown"}_$step")
}

/** Nazwa bieżącego testu (`Klasa_metoda`) — ustawiana przez [ScreenshotRule]. */
@Volatile private var currentTestName: String? = null

/**
 * Robi zrzut ekranu na końcu każdego testu, z sufiksem `_passed` albo `_failed`. Musi być
 * WEWNĘTRZNĄ regułą względem reguły Compose (ostatnia w [org.junit.rules.RuleChain]), żeby Activity
 * jeszcze żyła, gdy test się kończy — inaczej zrzut z porażki pokazałby ekran launchera.
 */
class ScreenshotRule(private val composeRule: ComposeTestRule) : TestWatcher() {
    override fun starting(description: Description) {
        currentTestName = "${description.testClass.simpleName}_${description.methodName}"
    }

    override fun succeeded(description: Description) = capture("passed")

    override fun failed(e: Throwable, description: Description) = capture("failed")

    override fun finished(description: Description) {
        currentTestName = null
    }

    private fun capture(suffix: String) {
        // Przy porażce Compose może nie osiągnąć bezczynności (np. wisi na sieci) — wtedy i tak
        // robimy zrzut, bez czekania.
        runCatching { composeRule.waitForIdle() }
        takeScreenshot("${currentTestName ?: "unknown"}_$suffix")
    }
}
