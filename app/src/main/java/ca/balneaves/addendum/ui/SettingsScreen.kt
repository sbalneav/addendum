package ca.balneaves.addendum.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ca.balneaves.addendum.MainViewModel
import ca.balneaves.addendum.UiState
import ca.balneaves.addendum.UnknownHostKeyException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit, onEditProfile: (String?) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    var confirmRegen by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Profiles", style = MaterialTheme.typography.titleMedium)
            if (state.profiles.isEmpty()) {
                Text("None yet. A profile is one form on one server.", style = MaterialTheme.typography.bodySmall)
            }
            state.profiles.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEditProfile(p.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName)
                        Text(
                            "${p.form} on ${p.user}@${p.endpoint.hostId}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
            }
            OutlinedButton(onClick = { onEditProfile(null) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  Add profile")
            }

            HorizontalDivider()
            Text("This phone's public key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every profile uses this key. Add it to ~/.ssh/authorized_keys on each server, " +
                    "prefixed with the forced-command options from server/README.md.",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(state.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(state.publicKey)) }) { Text("Copy") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, state.publicKey)
                    context.startActivity(Intent.createChooser(send, "Share public key"))
                }) { Text("Share") }
                TextButton(onClick = { confirmRegen = true }) { Text("Regenerate") }
            }

            HorizontalDivider()
            Text("Trusted servers", style = MaterialTheme.typography.titleMedium)
            if (state.hostKeys.isEmpty()) {
                Text(
                    "None yet. You'll be asked to confirm each server's host key the first time you connect.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.hostKeys.forEach { (hostId, fp) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hostId)
                        Text(fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { confirmForget = hostId }) { Text("Forget") }
                }
            }
        }
    }

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text("Regenerate key?") },
            text = { Text("The old key will stop working on every server. You'll need to put the new one in authorized_keys.") },
            confirmButton = { TextButton(onClick = { confirmRegen = false; vm.regenerateKey() }) { Text("Regenerate") } },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("Cancel") } },
        )
    }
    confirmForget?.let { hostId ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget $hostId?") },
            text = { Text("You'll be asked to confirm its host key again next time you connect.") },
            confirmButton = { TextButton(onClick = { confirmForget = null; vm.forgetHostKey(hostId) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun HostKeyDialog(e: UnknownHostKeyException, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First connection to ${e.hostId}. Its host key fingerprint is:")
                Text(e.fingerprint, fontFamily = FontFamily.Monospace)
                Text(
                    "On the server, compare with:\nfor k in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \$k; done",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Trust") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}
