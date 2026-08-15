package com.showtracker.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.showtracker.app.data.ImportResult
import com.showtracker.app.data.exportFileName
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.catchingUserFacing
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read a document the user picked, as UTF-8.
 *
 * The charset is stated rather than left to the platform default. That default is cp1252
 * on some systems, which cannot represent U+014D - so a library containing "Shōgun" would
 * import with a corrupted title and no error anywhere.
 */
private suspend fun readText(
    context: Context,
    uri: Uri,
): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not open that file." }
                .readBytes()
                .toString(Charsets.UTF_8)
        }
    }

private suspend fun writeText(
    context: Context,
    uri: Uri,
    text: String,
) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not write to that file." }
                .write(text.toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * Export and import, through the system file picker.
 *
 * Both go through the Storage Access Framework, so the app needs no storage permission and
 * the user chooses exactly where a file comes from or goes to.
 */
@Composable
fun DataSection(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                catchingUserFacing { writeText(context, uri, viewModel.exportJson()) }
                    .onSuccess {
                        failed = false
                        message = "Library exported."
                    }.onFailure {
                        failed = true
                        message = it.message ?: "Export failed."
                    }
                busy = false
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // Confirmed before anything is read: an import replaces the whole library, and
            // that is not something to discover after the fact.
            if (uri != null) pendingImport = uri
        }

    Text(
        "Your data",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "An export contains every show you follow and how far through each one you are. " +
            "Your TMDB key is not included, since the file is meant to leave the phone.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )

    OutlinedButton(
        onClick = { exportLauncher.launch(exportFileName()) },
        enabled = !busy && state.shows.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (state.shows.isEmpty()) "Nothing to export yet" else "Export library",
            color = if (state.shows.isEmpty()) TextFaint else TextMuted,
        )
    }

    OutlinedButton(
        onClick = {
            // Not "application/json": some file managers hand a .json file over as
            // text/plain or application/octet-stream, and a strict filter hides the very
            // file the user is trying to pick.
            importLauncher.launch(arrayOf("*/*"))
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Import library", color = TextMuted)
    }

    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) Danger else StateAiring,
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Replace your library?") },
            text = {
                Text(
                    if (state.shows.isEmpty()) {
                        "The shows in this file will be loaded."
                    } else {
                        "The ${state.shows.size} shows you currently follow will be " +
                            "replaced by the contents of this file, including how far " +
                            "through each one you are."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        busy = true
                        catchingUserFacing { viewModel.importJson(readText(context, uri)) }
                            .onSuccess { result ->
                                when (result) {
                                    is ImportResult.Success -> {
                                        failed = false
                                        message =
                                            "Imported ${result.shows.size} " +
                                            if (result.shows.size == 1) "show." else "shows."
                                    }

                                    is ImportResult.Failure -> {
                                        failed = true
                                        message = result.reason
                                    }
                                }
                            }.onFailure {
                                failed = true
                                message = it.message ?: "Import failed."
                            }
                        busy = false
                    }
                }) {
                    Text("Replace", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
    }
}
