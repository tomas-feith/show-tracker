package com.showtracker.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.showtracker.app.domain.DismissedShow
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted

/** How many to list before collapsing behind "show all". */
private const val COLLAPSED = 5

/**
 * The shows hidden from suggestions, and the way back.
 *
 * "Not interested" is otherwise a one-way door: it is a single tap, it is permanent, and
 * until this existed there was no screen anywhere that would even tell you what you had
 * hidden. A destructive action reachable by mis-tap needs somewhere to undo it, and that
 * is worth more than the handful of rows it costs.
 */
@Composable
fun DismissedSection(viewModel: LibraryViewModel) {
    val hidden by viewModel.dismissedShows.collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf(false) }

    if (hidden.isEmpty()) return

    Text(
        "Hidden from suggestions",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "These never appear in \"For you\". They are still findable through search.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )

    val shown = if (expanded) hidden else hidden.take(COLLAPSED)
    shown.forEach { show ->
        HiddenRow(show) { viewModel.restoreDismissed(show.id) }
    }

    if (hidden.size > COLLAPSED) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "Show fewer" else "Show all ${hidden.size}",
                color = Accent,
            )
        }
    }

    OutlinedButton(
        onClick = { viewModel.restoreAllDismissed() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Un-hide all", color = TextMuted)
    }
}

@Composable
private fun HiddenRow(
    show: DismissedShow,
    onRestore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            // A dismissal made before names were stored has none; the id is at least
            // something to identify the row by, rather than a blank line with a button.
            show.name.ifBlank { "Show #${show.id}" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (show.name.isBlank()) TextFaint else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRestore) {
            Text("Un-hide", color = Accent)
        }
    }
}
