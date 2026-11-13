package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * The permission-gated action button for deleting an item from the Jellyfin server.
 *
 * The button is hidden when the current user is not allowed to delete content and disabled while a
 * delete is running or the item is being downloaded locally. Tapping it asks for confirmation
 * first; the dialog copy is supplied by the caller so container items (shows and seasons) can warn
 * about the episodes that will be removed too.
 */
@Composable
fun DeleteServerButton(
    canDeleteFromServer: Boolean,
    isDeletingFromServer: Boolean,
    isDownloaded: Boolean,
    message: String,
    onDeleteFromServerClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadInProgress: Boolean = false,
) {
    if (!canDeleteFromServer) return

    var showDialog by rememberSaveable { mutableStateOf(false) }

    FilledTonalIconButton(
        onClick = { showDialog = true },
        enabled = !isDeletingFromServer && !downloadInProgress,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_delete_forever),
            contentDescription = stringResource(CoreR.string.delete_from_server),
        )
    }

    if (showDialog) {
        DeleteServerItemDialog(
            message = message,
            isDownloaded = isDownloaded,
            onConfirm = {
                showDialog = false
                onDeleteFromServerClick()
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
fun DeleteServerItemDialog(
    message: String,
    isDownloaded: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = stringResource(CoreR.string.delete_from_server)) },
        text = {
            Column {
                Text(text = message)
                if (isDownloaded) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    Text(text = stringResource(CoreR.string.delete_from_server_downloaded_message))
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(CoreR.string.delete_from_server),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreR.string.cancel)) }
        },
    )
}
