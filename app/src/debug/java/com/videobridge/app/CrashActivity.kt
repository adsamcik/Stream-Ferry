package com.videobridge.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.videobridge.diagnostics.ReportShare
import com.videobridge.ui.theme.JellyfinBridgeTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEBUG-ONLY full-screen crash report. Launched immediately by [com.videobridge.diagnostics.CrashReporter]
 * when the app crashes; runs in a separate `:crash` process (see src/debug/AndroidManifest.xml) so it
 * survives the dying main process. Shows the (already-redacted) stack trace with copy / share / save /
 * restart. Never shipped in release builds.
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching {
            intent?.getStringExtra(EXTRA_CRASH_FILE)?.let { File(it).readText() }
        }.getOrNull() ?: "No crash report available."
        setContent {
            JellyfinBridgeTheme {
                CrashScreen(report = report, onRestart = ::restartApp)
            }
        }
    }

    private fun restartApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finish()
        Runtime.getRuntime().exit(0) // end the :crash process
    }

    companion object {
        const val EXTRA_CRASH_FILE = "crashFilePath"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashScreen(report: String, onRestart: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openOutputStream(it)?.use { os -> os.write(report.toByteArray()) } }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Crash report (debug)") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("The app crashed. This screen is shown in debug builds only.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Crash report", report))) }
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val shareIntent = withContext(Dispatchers.IO) {
                            ReportShare.createIntent(context, report, "Jellyfin Bridge crash report")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share crash report"))
                    }
                }) { Text("Share") }
                OutlinedButton(onClick = { saveLauncher.launch("video-bridge-crash.txt") }) { Text("Save") }
            }
            OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Restart app") }
            SelectionContainer(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
