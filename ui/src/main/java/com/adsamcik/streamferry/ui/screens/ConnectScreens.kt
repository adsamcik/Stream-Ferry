package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.ui.UiController
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
fun ServerSetupScreen(state: AppUiState, viewModel: UiController) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = state.serverUrlInput.isNotBlank() &&
        state.connectionState != ConnectionState.TESTING &&
        (!state.needsHttpApproval || state.allowHttp)
    fun submit() {
        if (!canSubmit) return
        focusManager.clearFocus()
        keyboard?.hide()
        viewModel.testConnectionAndContinue()
    }

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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .keepFocusedFieldVisible(),
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = state.allowHttp,
                                role = Role.Checkbox,
                                onValueChange = viewModel::onAllowHttpChanged,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.allowHttp, onCheckedChange = null)
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
            onClick = { submit() },
            enabled = canSubmit,
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
fun LoginScreen(state: AppUiState, viewModel: UiController) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val quickConnect = state.quickConnect
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canLogin = username.isNotBlank() && !state.isBusy
    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun submitLogin() {
        if (!canLogin) return
        dismissKeyboard()
        viewModel.login(username, password)
    }

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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .keepFocusedFieldVisible(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .keepFocusedFieldVisible(),
                )
                Button(
                    onClick = { submitLogin() },
                    enabled = canLogin,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Log in", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = {
                        dismissKeyboard()
                        viewModel.startQuickConnect()
                    },
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

/** Scrolls a setup input above the IME after it opens, without moving unfocused content. */
@Composable
private fun Modifier.keepFocusedFieldVisible(): Modifier {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(isFocused, imeBottom) {
        if (isFocused) bringIntoViewRequester.bringIntoView()
    }

    return this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged { isFocused = it.isFocused }
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
fun ServersScreen(state: AppUiState, viewModel: UiController) {
    var forgetCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Your Jellyfin servers",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Choose where your library comes from. Server addresses stay private on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.servers.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    "No servers saved yet. Add one and it stays available even when offline.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.servers.forEach { server ->
            val containerColor by animateColorAsState(
                targetValue = if (server.active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "server card color",
            )
            val foreground = if (server.active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                Column(
                    Modifier.padding(18.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            server.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = foreground,
                        )
                        if (server.active) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Active", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    Text(
                        server.baseUrlRedactedForUi,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server.active) foreground else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!server.active) {
                        Text(
                            if (server.loggedIn) "Ready to use" else "Sign-in required",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!server.active) {
                            Button(onClick = { viewModel.switchServer(server.id) }) { Text("Use server") }
                        }
                        OutlinedButton(onClick = { forgetCandidateId = server.id }) { Text("Forget") }
                    }
                }
            }
        }
        Button(
            onClick = { viewModel.navigate(Route.SERVER_SETUP) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("Add a server", modifier = Modifier.padding(start = 8.dp))
        }
    }

    val forgetting = forgetCandidateId?.let { id -> state.servers.firstOrNull { it.id == id } }
    if (forgetting != null) {
        AlertDialog(
            onDismissRequest = { forgetCandidateId = null },
            title = { Text("Forget ${forgetting.name}?") },
            text = {
                Text(
                    buildString {
                        if (forgetting.active) append("Active playback from this server will stop. ")
                        append("Its saved address, sign-in, and pending watch-state changes will be removed from this phone.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetServer(forgetting.id)
                        forgetCandidateId = null
                    },
                ) { Text("Forget server") }
            },
            dismissButton = {
                TextButton(onClick = { forgetCandidateId = null }) { Text("Cancel") }
            },
        )
    }
}
