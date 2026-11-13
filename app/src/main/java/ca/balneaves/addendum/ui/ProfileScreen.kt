package ca.balneaves.addendum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ca.balneaves.addendum.Endpoint
import ca.balneaves.addendum.FormServer
import ca.balneaves.addendum.MainViewModel
import ca.balneaves.addendum.Profile
import ca.balneaves.addendum.UiState

/** Add or edit one profile. [onDone] gets true when it was saved (go to the form), false otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: UiState, vm: MainViewModel, existing: Profile?, onDone: (saved: Boolean) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    // New profiles start with the last profile's server details: usually it's another form on the same server.
    val template = existing ?: state.profiles.lastOrNull()?.let { Profile(host = it.host, port = it.port, user = it.user) } ?: Profile()
    var name by rememberSaveable { mutableStateOf(template.name) }
    var host by rememberSaveable { mutableStateOf(template.host) }
    var port by rememberSaveable { mutableStateOf(template.port.toString()) }
    var user by rememberSaveable { mutableStateOf(template.user) }
    var form by rememberSaveable { mutableStateOf(template.form) }
    var confirmDelete by remember { mutableStateOf(false) }
    val endpoint = Endpoint(host.trim(), port.toIntOrNull() ?: 22, user.trim())
    val formOk = FormServer.NAME.matches(form.trim())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New profile" else "Edit profile") },
                navigationIcon = {
                    IconButton(onClick = { onDone(false) }) {
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
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Form", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                form, { form = it.trim().lowercase() },
                label = { Text("Form name") },
                singleLine = true,
                isError = form.isNotEmpty() && !formOk,
                supportingText = { Text("A script in ~/.config/addendum/forms/ on the server") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.loadForms(endpoint) },
                enabled = !state.busy && endpoint.host.isNotEmpty() && endpoint.user.isNotEmpty(),
            ) { Text("Find forms on this server") }
            if (state.forms.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.forms.forEach { f ->
                        FilterChip(selected = form == f, onClick = {
                            form = f
                            if (name.isBlank()) name = f.replaceFirstChar { it.uppercase() }
                        }, label = { Text(f) })
                    }
                }
            }

            OutlinedTextField(
                name, { name = it }, label = { Text("Profile name") },
                placeholder = { Text(form.ifBlank { "e.g. Appointments" }) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val base = existing ?: Profile()
                        vm.saveProfile(base.copy(name = name, host = endpoint.host, port = endpoint.port, user = endpoint.user, form = form.trim()))
                        onDone(true)
                    },
                    enabled = formOk && endpoint.host.isNotEmpty() && endpoint.user.isNotEmpty(),
                ) { Text("Save") }
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }

    if (confirmDelete && existing != null) {
        val waiting = state.pending.count { it.profileId == existing.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.displayName}?") },
            text = {
                Text(
                    "This only removes it from the phone; nothing changes on the server." +
                        if (waiting > 0) "\n\n$waiting record(s) waiting to send for it won't be sent." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteProfile(existing.id); onDone(false) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
