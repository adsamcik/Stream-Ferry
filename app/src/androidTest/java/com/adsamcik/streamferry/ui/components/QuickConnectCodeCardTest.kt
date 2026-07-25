package com.adsamcik.streamferry.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QuickConnectCodeCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun copy_isAvailableWithoutLongPress_andFeedbackIsVisible() {
        var copied = false
        composeRule.setContent {
            StreamFerryTheme(dynamicColor = false) {
                QuickConnectCodeCard(code = "ABCD12", onCopied = { copied = true })
            }
        }

        composeRule.onNodeWithTag("quick-connect-code").assertTextEquals("ABCD12")
        composeRule.onNodeWithTag("quick-connect-copy").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertTrue(copied) }
        composeRule.onNodeWithText("Code copied").assertIsDisplayed()
    }
}
