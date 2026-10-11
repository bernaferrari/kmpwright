package com.bernaferrari.kmpwright.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun navigationButtonIsDisplayed() {
        composeTestRule.onNodeWithText("Navigate").assertExists()
    }

    @Test
    fun getPageInfoButtonIsDisplayed() {
        composeTestRule.onNodeWithText("Get Page Info").assertExists()
    }

    @Test
    fun screenshotButtonIsDisplayed() {
        composeTestRule.onNodeWithText("Take Screenshot").assertExists()
    }
}
