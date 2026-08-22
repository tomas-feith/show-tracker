package com.showtracker.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

/**
 * Scheduled backups into a folder the user picks.
 *
 * The pitch on screen is deliberately concrete about what this is *for*, because the
 * platform already backs the library up to the user's Google account and a second backup
 * feature that does not explain how it differs just reads as duplication. It differs in the
 * only way that matters day to day: these are dated files that can be read back one at a
 * time, so a mistake made a week ago is recoverable.
 */
@Composable
fun BackupSection(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folder by viewModel.backupFolder.collectAsState(initial = null)
    val lastBackupAt by viewModel.lastBackupAt.collectAsState(initial = null)
    val lastError by viewModel.lastBackupError.collectAsState(initial = null)

    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { tree ->
            if (tree == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                // Without taking the grant persistably it dies with the process, and the
                // first scheduled run tomorrow would fail on a folder the user was told
                // was configured.
                val taken =
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }.isSuccess

                if (taken) {
                    viewModel.setBackupFolder(tree.toString())
                    failed = false
                    message = "Backup folder set. The first backup runs now."
                    viewModel.backUpNow(
                        onDone = {
                            failed = false
                            message = it
                        },
                        onError = {
                            failed = true
                            message = it
                        },
                    )
                } else {
                    failed = true
                    message = "Android would not grant lasting access to that folder."
                }
                busy = false
            }
        }

    Text(
        "Scheduled backups",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "Writes a dated copy of your library to a folder you choose, once a day, keeping " +
            "the last $KEPT. Point it at a folder your cloud app already syncs and the " +
            "copies leave the phone without this app needing an account. Your TMDB key " +
            "is never included.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )

    BackupStatus(folder, lastBackupAt, lastError)

    OutlinedButton(
        onClick = { picker.launch(null) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (folder == null) "Choose backup folder" else "Choose a different folder",
            color = TextMuted,
        )
    }

    if (folder != null) {
        OutlinedButton(
            onClick = {
                busy = true
                viewModel.backUpNow(
                    onDone = {
                        failed = false
                        message = it
                        busy = false
                    },
                    onError = {
                        failed = true
                        message = it
                        busy = false
                    },
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back up now", color = TextMuted)
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    viewModel.clearBackupFolder()
                    failed = false
                    message = "Scheduled backups turned off. Existing files are left alone."
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Turn off scheduled backups", color = TextMuted)
        }
    }

    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) Danger else StateAiring,
        )
    }
}

/** Where the backups go, when the last one ran, and whether it is still working. */
@Composable
private fun BackupStatus(
    folder: String?,
    lastBackupAt: String?,
    lastError: String?,
) {
    folder?.let {
        Text(
            "Saving to ${folderLabel(it)}",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text(
            lastBackupAt?.let { at -> "Last backup $at" } ?: "No backup taken yet.",
            style = MaterialTheme.typography.bodySmall,
            color = TextFaint,
        )
    }

    // The worker's own complaint, which outlives this screen. Shown separately from the
    // section's own message, which only reports what the user just did.
    lastError?.let {
        Text(
            "The last scheduled backup failed: $it Choose the folder again to fix it.",
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
        )
    }
}

private const val KEPT = 14

/**
 * Something human out of a tree URI.
 *
 * A SAF tree URI has no readable path, only a provider-specific document id. The tail of
 * that id is usually the folder name, which is enough to tell two folders apart - and this
 * is a label, not an identifier, so being approximate is fine. The full URI would be
 * unreadable and would tell the user less.
 */
private fun folderLabel(uri: String): String {
    val decoded = Uri.decode(uri)
    return decoded.substringAfterLast('/', "").ifEmpty { decoded.substringAfterLast(':') }
}
