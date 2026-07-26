package com.adsamcik.streamferry.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/**
 * App-wide indeterminate progress treatment backed by Material 3 Expressive's morphing shapes.
 *
 * A caller-supplied description makes compact indicators understandable without announcing nearby
 * visible text twice. The progress semantic is explicit because this wrapper can be used at custom
 * sizes and inside surfaces that merge descendant semantics.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    LoadingIndicator(
        modifier = modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            if (description != null) {
                contentDescription = description
            }
        },
    )
