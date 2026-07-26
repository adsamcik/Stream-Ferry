package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.components.ExpressiveLoadingIndicator
import com.adsamcik.streamferry.ui.components.QuickConnectCodeCard
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.ConnectionState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme

@Composable
fun WelcomeScreen(loggedIn: Boolean, onContinue: () -> Unit, onLocalOnly: () -> Unit) {    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Cast,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Text(
                    "Your phone is the gateway",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "The TV streams video only from this phone — from your Jellyfin server or videos on this device. Your server address and login never leave your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Your phone and TV must be on the same Wi-Fi/LAN. The phone may reach Jellyfin over LAN, remote HTTPS, or VPN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  ${if (loggedIn) "Continue to library" else "Connect to Jellyfin"}", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = onLocalOnly,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("Use videos on this device", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun ServerSetupScreen(state: AppUiState, viewModel: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Enter your Jellyfin server address. https is recommended; http is allowed only for a LAN server you approve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.serverUrlInput,
            onValueChange = viewModel::onServerUrlChanged,
            label = { Text("Jellyfin server URL") },
            placeholder = { Text("https://jellyfin.example.com") },
            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = state.needsHttpApproval,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This is an unencrypted (http) address. Only continue for a LAN server you trust.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.allowHttp, onCheckedChange = viewModel::onAllowHttpChanged)
                        Text("Allow http for this LAN server", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.connectionState == ConnectionState.CONNECTED && state.serverName != null,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Text(
                "Connected to ${state.serverName}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = { viewModel.testConnectionAndContinue() },
            enabled = state.serverUrlInput.isNotBlank() &&
                state.connectionState != ConnectionState.TESTING &&
                (!state.needsHttpApproval || state.allowHttp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  Test connection & continue", style = MaterialTheme.typography.titleMedium)
        }
        AnimatedVisibility(
            visible = state.connectionState == ConnectionState.TESTING,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpressiveLoadingIndicator(Modifier.size(32.dp), description = "Contacting server")
                Text("Contacting server…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun LoginScreen(state: AppUiState, viewModel: MainViewModel) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val quickConnect = state.quickConnect

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.serverName?.let {
            Text(
                "Server: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = quickConnect != null,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            quickConnect?.let {
                QuickConnectPanel(code = it.code, onCancel = viewModel::cancelQuickConnect)
            }
        }
        AnimatedVisibility(
            visible = quickConnect == null,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your password is never stored; only an access token is kept, encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.login(username, password) },
                    enabled = username.isNotBlank() && !state.isBusy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Log in", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = viewModel::startQuickConnect,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Use Quick Connect", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Quick Connect lets you sign in without typing your password: enter the code below on a " +
                        "device already signed in to your Jellyfin server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(
                    visible = state.isBusy,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ExpressiveLoadingIndicator(Modifier.size(32.dp), description = "Signing in")
                        Text("Please wait…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickConnectPanel(code: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickConnectCodeCard(code = code)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveLoadingIndicator(Modifier.size(32.dp), description = "Waiting for Quick Connect approval")
            Text("Waiting for approval…", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) { Text("Cancel Quick Connect") }
    }
}

// ---------------------------------------------------------------------------------------------------
// Design-time @Preview composables (no ViewModel required). They render in Android Studio's preview
// pane; R8 strips these unused functions from the release build.
// ---------------------------------------------------------------------------------------------------

@Preview(name = "Welcome", showBackground = true)
@Preview(name = "Welcome · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WelcomeScreenPreview() {    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                WelcomeScreen(loggedIn = false, onContinue = {}, onLocalOnly = {})
            }
        }
    }
}

@Preview(name = "Quick Connect", showBackground = true)
@Preview(name = "Quick Connect · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuickConnectPanelPreview() {
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                QuickConnectPanel(code = "ABCD-1234", onCancel = {})
            }
        }
    }
}

@Composable
fun ServersScreen(state: AppUiState, viewModel: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your Jellyfin servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (state.servers.isEmpty()) {
            Text("No servers saved yet. Add one and it stays here even when offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.servers.forEach { s ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.name, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(s.baseUrlRedactedForUi, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val status = if (s.active) "Active" else if (s.loggedIn) "Saved" else "Saved - not signed in"
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!s.active) Button(onClick = { viewModel.switchServer(s.id) }, shape = RoundedCornerShape(16.dp)) { Text("Use") }
                        OutlinedButton(onClick = { viewModel.forgetServer(s.id) }, shape = RoundedCornerShape(16.dp)) { Text("Forget") }
                    }
                }
            }
        }
        Button(onClick = { viewModel.navigate(Route.SERVER_SETUP) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Add a server")
        }
    }
}