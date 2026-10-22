package com.adsamcik.streamferry.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-mdpi")
class SourceSignInNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedCredentialsOfferSignInInsteadOfRepeatingConnectionRetry() {
        var signIns = 0
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) {
                SourceUnavailableHeaderNotice(onRetry = { signIns++ }, signInRequired = true)
            }
        }
        compose.onNodeWithText("Sign in required").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }
}
