package ca.balneaves.addendum.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.balneaves.addendum.FieldSpec
import ca.balneaves.addendum.FieldType
import ca.balneaves.addendum.FormLogic
import ca.balneaves.addendum.MainViewModel
import ca.balneaves.addendum.Pending
import ca.balneaves.addendum.Profile
import ca.balneaves.addendum.UiState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** More choices than this and a dropdown gets a search box. */
private const val SEARCHABLE_OVER = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(state: UiState, vm: MainViewModel, onSettings: () -> Unit, onNewProfile: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ProfileSwitcher(state, vm::selectProfile) },
                actions = {
                    if (state.profile != null) {
                        IconButton(onClick = { vm.refresh() }, enabled = !state.busy) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload the form and status")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.pending.isNotEmpty()) {
                PendingCard(state.pending, state.profiles, state.busy, onSend = { vm.sendPending() }, onDiscard = vm::discardPending)
            }

            val spec = state.spec
            when {
                state.profile == null -> {
                    Text("No profiles yet. A profile is one form on one server.")
                    Button(onClick = onNewProfile) { Text("Add a profile") }
                }
                spec == null -> {
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Loading the form…")
                        }
                    } else {
                        Text(state.specError?.let { "Couldn't load the form: $it" } ?: "The form hasn't been loaded yet.")
                        OutlinedButton(onClick = { vm.refresh() }) { Text("Try again") }
                    }
                }
                else -> {
                    spec.fields.forEach { f ->
                        Field(f, state.values[f.name].orEmpty(), state.fieldErrors[f.name]) { vm.setValue(f.name, it) }
                    }
                    Button(
                        onClick = vm::submit,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(spec.button ?: "Add")
                    }
                    if (spec.hasStatus) state.status?.let { StatusCard(it, state.statusAt) }
                }
            }
        }
    }
}

/** The title: the current profile's name, and a menu to switch when there's more than one. */
@Composable
private fun ProfileSwitcher(state: UiState, onSelect: (String) -> Unit) {
    val name = state.spec?.title ?: state.profile?.displayName ?: "Addendum"
    if (state.profiles.size < 2) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.selectable(selected = open, onClick = { open = true }, role = Role.DropdownList),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch profile")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName) },
                    onClick = { open = false; onSelect(p.id) },
                )
            }
        }
    }
}

/** One input, chosen by the field's type. */
@Composable
private fun Field(f: FieldSpec, value: String, error: String?, onChange: (String) -> Unit) {
    val label = if (f.required) f.label else "${f.label} (optional)"
    when (f.type) {
        FieldType.TEXT, FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = f.hint?.let { { Text(it) } },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = if (f.type == FieldType.NUMBER)
                KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
            else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldType.DATE -> DateField(label, value, !f.required, error, onChange)
        FieldType.TIME -> TimeField(label, value, !f.required, error, onChange)
        FieldType.DROPDOWN ->
            if (f.choices.size > SEARCHABLE_OVER) SearchableDropdown(label, f, value, error, onChange)
            else SimpleDropdown(label, f, value, error, onChange)
        FieldType.RADIO -> Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            f.choices.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = value == c.value, onClick = { onChange(c.value) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == c.value, onClick = null)
                    Text(c.label, Modifier.padding(start = 8.dp))
                }
            }
            ErrorText(error)
        }
        FieldType.CHECKBOX -> Column {
            Row(
                Modifier.fillMaxWidth().toggleable(value == "yes", role = Role.Checkbox) { onChange(if (it) "yes" else "no") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = value == "yes", onCheckedChange = null)
                Text(f.label, Modifier.padding(start = 8.dp))
            }
            ErrorText(error)
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** A read-only text field that opens a picker when tapped, with an optional Clear. */
@Composable
private fun PickerField(label: String, shown: String, error: String?, clearable: Boolean, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(shown.ifEmpty { "Not set" }, style = MaterialTheme.typography.bodyLarge)
            ErrorText(error)
        }
        if (clearable && shown.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        OutlinedButton(onClick = onOpen) { Text(if (shown.isEmpty()) "Set" else "Change") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String, clearable: Boolean, error: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val date = FormLogic.parseDate(value)
    val shown = when {
        date == null -> ""
        date == LocalDate.now() -> "Today, " + date.format(DateTimeFormatter.ofPattern("EEE MMM d"))
        date == LocalDate.now().plusDays(1) -> "Tomorrow, " + date.format(DateTimeFormatter.ofPattern("EEE MMM d"))
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
    }
    PickerField(label, shown, error, clearable, onOpen = { open = true }, onClear = { onChange("") })
    if (open) {
        // DatePicker works in UTC midnight millis.
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, clearable: Boolean, error: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val time = runCatching { LocalTime.parse(value) }.getOrNull()
    val shown = time?.format(DateTimeFormatter.ofPattern("h:mm a")).orEmpty()
    PickerField(label, shown, error, clearable, onOpen = { open = true }, onClear = { onChange("") })
    if (open) {
        val start = time ?: LocalTime.of(9, 0)
        val picker = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(picker.hour, picker.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(picker) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(label: String, f: FieldSpec, value: String, error: String?, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = f.choices.firstOrNull { it.value == value }?.label ?: value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!f.required) {
                DropdownMenuItem(
                    text = { Text("(none)") },
                    onClick = { onChange(""); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            f.choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = { onChange(c.value); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** A dropdown you can type into to filter, for long lists. Only list entries are accepted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableDropdown(label: String, f: FieldSpec, value: String, error: String?, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = f.choices.firstOrNull { it.value == value }?.label.orEmpty()
    var query by remember(selectedLabel) { mutableStateOf(selectedLabel) }
    val filtered = if (query == selectedLabel) f.choices
        else f.choices.filter { it.label.contains(query.trim(), ignoreCase = true) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
                if (it != selectedLabel) onChange("")
            },
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && filtered.isNotEmpty(), onDismissRequest = { expanded = false }) {
            filtered.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = {
                        onChange(c.value)
                        query = c.label
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** The script's status text, shown exactly as sent, as of [at]. */
@Composable
private fun StatusCard(text: String, at: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (at > 0) {
                val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                val fmt = if (time.toLocalDate() == LocalDate.now()) "'As of' h:mm a" else "'As of' MMM d, h:mm a"
                Text(time.format(DateTimeFormatter.ofPattern(fmt)), style = MaterialTheme.typography.bodySmall)
            }
            // Monospace with no wrapping keeps columns aligned; scrolls sideways if too wide.
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Records saved on the phone that a server hasn't confirmed yet. */
@Composable
private fun PendingCard(
    pending: List<Pending>,
    profiles: List<Profile>,
    busy: Boolean,
    onSend: () -> Unit,
    onDiscard: (String) -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf<Pending?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pending.size} waiting to send", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSend, enabled = !busy) { Text("Send now") }
            }
            pending.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.summary)
                        Text(
                            profiles.firstOrNull { it.id == p.profileId }?.displayName ?: p.form,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { confirmDiscard = p }) { Text("Discard") }
                }
            }
        }
    }
    confirmDiscard?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this record?") },
            text = { Text("${p.summary}\n\nIt hasn't been written on the server and will be lost.") },
            confirmButton = { TextButton(onClick = { onDiscard(p.id); confirmDiscard = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep") } },
        )
    }
}
