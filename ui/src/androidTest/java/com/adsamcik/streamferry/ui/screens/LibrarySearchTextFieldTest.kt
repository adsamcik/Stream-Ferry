package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibrarySearchTextFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSearchFocusesAcceptsImeSearchAndCloses() {
        var value by mutableStateOf("")
        var submitted = false
        var closed = false
        composeRule.setContent {
            StreamFerryTheme(dynamicColor = false) {
                LibrarySearchTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Search library",
                    compact = true,
                    onClear = { value = "" },
                    onClose = { closed = true },
                    onImeSearch = { submitted = true },
                )
            }
        }

        val searchField = composeRule.onNodeWithTag("library-search-field").assertIsFocused()
        searchField.performTextInput("matrix")
        searchField.performImeAction()
        composeRule.runOnIdle {
            assertEquals("matrix", value)
            assertTrue(submitted)
        }

        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
    }
}
