package com.showtracker.app.ui.settings

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.notify.canPostNotifications
import com.showtracker.app.notify.notificationPermissionIsRuntime
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.catchingUserFacing
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

private const val SIGNUP_URL = "https://www.themoviedb.org/settings/api"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextMuted,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KeyHelp(connected = state.apiKey != null, onOpenSignup = {
                context.startActivity(Intent(Intent.ACTION_VIEW, SIGNUP_URL.toUri()))
            })

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        if (state.apiKey != null) {
                            "Replace key"
                        } else {
                            "Paste your API key or read token"
                        },
                        color = TextFaint,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        message = null
                        try {
                            // Validated before it is stored, so a typo surfaces here rather
                            // than as a mysteriously empty library later.
                            catchingUserFacing { viewModel.saveApiKey(draft) }
                                .onSuccess {
                                    draft = ""
                                    failed = false
                                    message = "Key saved."
                                    onBack()
                                }.onFailure {
                                    failed = true
                                    message = it.message ?: "Could not verify key."
                                }
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking && draft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verify and save")
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Danger else StateAiring,
                )
            }

            if (state.apiKey != null) {
                OutlinedButton(
                    onClick = { scope.launch { viewModel.forgetApiKey() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove stored key", color = TextMuted)
                }
            }

            Notifications()

            Text(
                "Following ${state.shows.size} " +
                    if (state.shows.size == 1) "show." else "shows.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
        }
    }
}

@Composable
private fun KeyHelp(
    connected: Boolean,
    onOpenSignup: () -> Unit,
) {
    Text(
        "TMDB API key",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "Season data comes from The Movie Database. A personal key is free: create an " +
            "account, open Settings then API, and request a Developer key.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )
    Text(
        "Open TMDB API settings",
        style = MaterialTheme.typography.bodyMedium,
        color = Accent,
        modifier = Modifier.clickable(onClick = onOpenSignup),
    )
    Text(
        if (connected) "Status: connected" else "Status: not configured",
        style = MaterialTheme.typography.bodySmall,
        color = if (connected) StateAiring else Danger,
    )
}

/**
 * Notification permission.
 *
 * Requested from a button rather than on first launch: the prompt means something once the
 * user knows the app is about announcing new seasons, and Android only ever shows it twice
 * before going permanently silent, so spending one on a cold start is wasteful.
 */
@Composable
private fun Notifications() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(canPostNotifications(context)) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }

    Text(
        "Notifications",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "The app checks for new seasons roughly twice a day in the background, and again " +
            "whenever you open it. Android decides exactly when background checks run, so " +
            "treat them as a bonus rather than a guarantee.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )

    if (granted || !notificationPermissionIsRuntime()) {
        Text(
            "Status: allowed",
            style = MaterialTheme.typography.bodySmall,
            color = StateAiring,
        )
    } else {
        OutlinedButton(
            onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow notifications", color = TextMuted)
        }
    }
}
