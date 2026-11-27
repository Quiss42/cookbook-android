package de.ex_parrot.cookbook.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ex_parrot.cookbook.BuildConfig
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.ConflictChoice
import de.ex_parrot.cookbook.data.PendingChange
import de.ex_parrot.cookbook.data.UpdateInfo
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontFamily

@Composable
fun UpdateDialog(info: UpdateInfo, onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_found_title)) },
        text = { Text(stringResource(R.string.update_found_text, info.version, BuildConfig.VERSION_NAME)) },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) } },
    )
}

/** Asks how to resolve an offline change that clashes with a change on the server. */
@Composable
fun ConflictDialog(change: PendingChange, busy: Boolean, onChoice: (ConflictChoice) -> Unit) {
    val isDelete = change.type == PendingChange.Type.DELETE
    AlertDialog(
        // Must be decided; dismissing just shows it again later.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.conflict_title, change.name)) },
        text = {
            Column {
                Text(stringResource(if (isDelete) R.string.conflict_text_delete else R.string.conflict_text_update))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = !busy, onClick = { onChoice(ConflictChoice.MINE) }) {
                    Text(stringResource(if (isDelete) R.string.conflict_delete_anyway else R.string.conflict_mine))
                }
                TextButton(enabled = !busy, onClick = { onChoice(ConflictChoice.SERVER) }) {
                    Text(stringResource(if (isDelete) R.string.conflict_keep_recipe else R.string.conflict_server))
                }
                if (!isDelete) {
                    TextButton(enabled = !busy, onClick = { onChoice(ConflictChoice.BOTH) }) {
                        Text(stringResource(R.string.conflict_both))
                    }
                }
            }
        },
    )
}

/**
 * The server no longer accepts the app password, or ([certificateChanged]) a
 * self-signed server presents a different certificate. Offers to log in again.
 */
@Composable
fun LoginExpiredDialog(certificateChanged: Boolean, onRelogin: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (certificateChanged) R.string.certificate_changed_title else R.string.login_expired_title))
        },
        text = {
            Text(stringResource(if (certificateChanged) R.string.certificate_changed_text else R.string.login_expired_text))
        },
        confirmButton = { TextButton(onClick = onRelogin) { Text(stringResource(R.string.login_again)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) } },
    )
}

/** Shows a self-signed certificate's fingerprint for the user to compare before trusting it. */
@Composable
fun ConfirmCertificateDialog(fingerprint: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.certificate_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.certificate_confirm_text))
                SelectionContainer {
                    Text(
                        fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.certificate_trust)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Logging in with a different account would drop the offline changes of the expired one. */
@Composable
fun ConfirmOtherAccountDialog(previousAccount: String, waitingChanges: Int, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.other_account_title)) },
        text = {
            Text(pluralStringResource(R.plurals.other_account_text, waitingChanges, waitingChanges, previousAccount))
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.other_account_continue)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.cancel)) } },
    )
}
