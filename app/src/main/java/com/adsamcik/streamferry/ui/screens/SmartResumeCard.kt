package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.ui.state.SmartResumeUiState
import com.adsamcik.streamferry.ui.state.formatSmartResumeTime

@Composable
fun SmartResumeCard(state: SmartResumeUiState, onResume: () -> Unit, onDismiss: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Smart Resume", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            state.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                buildString {
                    append(state.sourceLabel)
                    append(" · ")
                    append(formatSmartResumeTime(state.positionSeconds))
                    state.durationSeconds?.takeIf { it > 0 }?.let { append(" / "); append(formatSmartResumeTime(it)) }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.progressFraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text(state.actionLabel) }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
