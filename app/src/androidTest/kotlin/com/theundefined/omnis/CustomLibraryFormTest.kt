package com.theundefined.omnis

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theundefined.omnis.data.model.EXAMPLE_CATALOG_LINK
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Formularz "Mojej biblioteki nie ma na liście": walidacja linku do katalogu i przykład. Bez sieci
 * i bez logowania — nie klikamy "Zaloguj i dodaj".
 */
@RunWith(AndroidJUnit4::class)
class CustomLibraryFormTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(AppStateRule(seedDemoAccount = false))
            .around(composeRule)
            .around(ScreenshotRule(composeRule))

    @Test
    fun customLibraryLink_isValidated_andExampleFillsIt() {
        composeRule.onNodeWithText(str(R.string.add_first_account)).performClick()
        composeRule.onNodeWithText(str(R.string.add_new_account)).performClick()
        composeRule.onNodeWithText(str(R.string.custom_library_not_listed)).performClick()

        val addButton = composeRule.onNodeWithText(str(R.string.login_and_add))
        addButton.assertIsNotEnabled()

        composeRule
            .onNode(hasSetTextAction() and hasText(str(R.string.custom_library_link)))
            .performTextInput("https://example.com/bez-vid")
        composeRule.onNodeWithText(str(R.string.custom_library_link_invalid)).assertExists()
        addButton.assertIsNotEnabled()
        composeRule.screenshot("invalid_link")

        composeRule.onNodeWithText(str(R.string.custom_library_use_example)).performClick()
        composeRule.onNodeWithText(EXAMPLE_CATALOG_LINK).assertExists()
        addButton.assertIsEnabled()
        composeRule.screenshot("example_link")
    }
}
