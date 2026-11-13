package ca.balneaves.addendum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

data class UiState(
    val profiles: List<Profile> = emptyList(),
    val profile: Profile? = null,
    /** The current profile's form, from the server or the cache. Null until first loaded. */
    val spec: FormSpec? = null,
    /** Why the form couldn't be loaded, shown in place of the form when there's no cached copy. */
    val specError: String? = null,
    val values: Map<String, String> = emptyMap(),
    val fieldErrors: Map<String, String> = emptyMap(),
    val status: String? = null,
    val statusAt: Long = 0,
    val busy: Boolean = false,
    /** One-shot message for the snackbar. */
    val message: String? = null,
    /** Set when a server presents a host key we haven't pinned; the UI asks the user. */
    val pendingHostKey: UnknownHostKeyException? = null,
    val publicKey: String = "",
    /** hostId -> fingerprint, for Settings. */
    val hostKeys: Map<String, String> = emptyMap(),
    /** Records saved on the phone but not yet written on the server. */
    val pending: List<Pending> = emptyList(),
    /** Forms a server offers, from "list", for the profile editor. */
    val forms: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val identity = Identity(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Action to retry after the user trusts a new host key. */
    private var retry: (() -> Unit)? = null

    init {
        Outbox.init(app)
        viewModelScope.launch { Outbox.items.collect { p -> _state.update { it.copy(pending = p) } } }
        _state.update { it.copy(profiles = prefs.profiles, hostKeys = fingerprints()) }
        val current = prefs.profile(prefs.currentProfileId) ?: prefs.profiles.firstOrNull()
        showProfile(current, fetch = false)
        viewModelScope.launch {
            val pk = withContext(Dispatchers.IO) { identity.publicKeyLine() }
            _state.update { it.copy(publicKey = pk) }
            if (current != null) refresh(quiet = true)
            if (Outbox.items.value.any { it.error == null }) sendPending(quiet = true)
        }
    }

    // ---- the form ---------------------------------------------------------------

    fun setValue(name: String, v: String) = _state.update {
        it.copy(values = it.values + (name to v), fieldErrors = it.fieldErrors - name)
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun selectProfile(id: String) {
        if (id == _state.value.profile?.id) return
        showProfile(prefs.profile(id), fetch = true)
    }

    /** Switch to [p], showing its cached form and status straight away, then optionally fetching fresh ones. */
    private fun showProfile(p: Profile?, fetch: Boolean) {
        prefs.currentProfileId = p?.id
        val spec = p?.let { prefs.spec(it.id) }?.let { runCatching { FormSpec.parse(it) }.getOrNull() }
        val status = p?.let { prefs.status(it.id) }
        _state.update {
            it.copy(
                profile = p,
                spec = spec,
                specError = null,
                values = if (spec != null && p != null) FormLogic.initialValues(spec, prefs.lastValues(p.id), LocalDate.now()) else emptyMap(),
                fieldErrors = emptyMap(),
                status = status?.first,
                statusAt = status?.second ?: 0,
            )
        }
        if (fetch && p != null) refresh(quiet = true)
    }

    /** Fetch the form and its status from the server. */
    fun refresh(quiet: Boolean = false) {
        val p = _state.value.profile ?: return
        remote(p.endpoint, retryWith = { refresh(quiet) }, onError = { msg ->
            if (_state.value.spec == null) _state.update { it.copy(specError = msg) }
            if (!quiet || _state.value.spec != null) _state.update { it.copy(message = msg) }
        }) { server ->
            val text = server.spec(p.form)
            val spec = FormSpec.parse(text)
            prefs.setSpec(p.id, text)
            _state.update { s ->
                if (s.profile?.id != p.id) return@update s
                // Keep what's been typed for fields that still exist; fill in the rest.
                val fresh = FormLogic.initialValues(spec, prefs.lastValues(p.id), LocalDate.now())
                val kept = if (s.spec == null) fresh else fresh + s.values.filterKeys { k -> spec.fields.any { it.name == k } }
                s.copy(spec = spec, specError = null, values = kept, message = if (quiet) s.message else "Form reloaded")
            }
            if (spec.hasStatus) fetchStatus(server, p) else _state.update { it.copy(status = null) }
        }
    }

    private fun fetchStatus(server: FormServer, p: Profile) {
        val text = try {
            server.status(p.form)
        } catch (e: ServerException) {
            e.message ?: "Couldn't get the status"
        }
        val at = System.currentTimeMillis()
        prefs.setStatus(p.id, text, at)
        _state.update { if (it.profile?.id == p.id) it.copy(status = text, statusAt = at) else it }
    }

    /** Refreshes the status panel in the background; on failure the cached text stays. */
    private fun refreshStatus(p: Profile) = viewModelScope.launch(Dispatchers.IO) {
        if (_state.value.spec?.hasStatus != true) return@launch
        try {
            fetchStatus(FormServer(SshClient(p.endpoint, prefs.hostKey(p.endpoint.hostId), identity)), p)
        } catch (_: Exception) {
        }
    }

    fun submit() {
        val s = _state.value
        val p = s.profile ?: return
        val spec = s.spec ?: return
        if (s.busy) return
        val errors = FormLogic.validate(spec, s.values)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        val values = FormLogic.outgoing(spec, s.values)
        val record = Pending(UUID.randomUUID().toString(), p.id, p.form, values, FormLogic.summary(spec, values))
        val app = getApplication<Application>()
        val remembered = spec.fields.filter { it.remember }.associate { it.name to values[it.name].orEmpty() }
        if (remembered.isNotEmpty()) prefs.setLastValues(p.id, prefs.lastValues(p.id) + remembered)

        // Saved on the phone before we try the network, so it can't be lost.
        Outbox.add(app, record)
        _state.update { it.copy(busy = true, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val outcome = Outbox.flush(app, includeFailed = false)
            val mine = Outbox.items.value.firstOrNull { it.id == record.id }
            when {
                mine == null -> {
                    val reply = outcome.replies[record.id].orEmpty().replace('\t', ' ').lines().firstOrNull().orEmpty()
                    clearForm(spec, p, if (reply.isBlank()) "Added" else "Added: $reply")
                    refreshStatus(p)
                }
                mine.error != null -> {
                    // Rejected as invalid. The form is still filled in, so drop the queued copy and let the user fix it.
                    Outbox.remove(app, record.id)
                    val (field, msg) = FormLogic.fieldError(spec, mine.error)
                    _state.update {
                        if (field != null) it.copy(fieldErrors = mapOf(field to msg)) else it.copy(message = msg)
                    }
                }
                else -> {
                    clearForm(spec, p, "Saved on the phone; it'll be sent when the server is reachable")
                    handleStopped(outcome.stoppedBy)
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun clearForm(spec: FormSpec, p: Profile, message: String) = _state.update {
        if (it.profile?.id != p.id) return@update it.copy(message = message)
        it.copy(
            values = FormLogic.resetValues(spec, it.values, prefs.lastValues(p.id), LocalDate.now()),
            fieldErrors = emptyMap(),
            message = message,
        )
    }

    /** "Send now" on the waiting card; also run quietly on launch. */
    fun sendPending(quiet: Boolean = false) {
        if (_state.value.busy) return
        val app = getApplication<Application>()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val outcome = Outbox.flush(app, includeFailed = !quiet)
            val left = Outbox.items.value.size
            if (outcome.sent > 0) _state.value.profile?.let { refreshStatus(it) }
            if (outcome.stoppedBy != null) {
                handleStopped(outcome.stoppedBy, quiet)
            } else if (!quiet || outcome.sent > 0) {
                _state.update {
                    it.copy(message = "Sent ${outcome.sent}" + if (left > 0) ", $left need attention" else "")
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun discardPending(id: String) {
        Outbox.remove(getApplication(), id)
        _state.update { it.copy(message = "Discarded") }
    }

    /** A flush stopped with records still queued: ask about the host key, or retry in the background. */
    private fun handleStopped(e: Exception?, quiet: Boolean = true) {
        when (e) {
            null -> {}
            is UnknownHostKeyException -> {
                retry = { sendPending() }
                _state.update { it.copy(pendingHostKey = e) }
            }
            else -> {
                Outbox.scheduleRetry(getApplication())
                if (!quiet) _state.update { it.copy(message = "Couldn't send: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ---- profiles and settings ---------------------------------------------------

    /** Saves a new or edited profile and switches to it. */
    fun saveProfile(p: Profile) {
        val cleaned = p.copy(name = p.name.trim(), host = p.host.trim(), user = p.user.trim(), form = p.form.trim())
        val old = prefs.profile(cleaned.id)
        prefs.profiles = if (old == null) prefs.profiles + cleaned else prefs.profiles.map { if (it.id == cleaned.id) cleaned else it }
        // A different server or form means the cached form is for something else.
        if (old != null && (old.endpoint != cleaned.endpoint || old.form != cleaned.form)) {
            prefs.clearProfileData(cleaned.id)
        }
        _state.update { it.copy(profiles = prefs.profiles, message = "Saved ${cleaned.displayName}") }
        showProfile(cleaned, fetch = true)
    }

    fun deleteProfile(id: String) {
        prefs.profiles = prefs.profiles.filterNot { it.id == id }
        prefs.clearProfileData(id)
        _state.update { it.copy(profiles = prefs.profiles, message = "Profile deleted") }
        if (_state.value.profile?.id == id) showProfile(prefs.profiles.firstOrNull(), fetch = true)
    }

    /** Ask a server which forms it offers, for the profile editor. */
    fun loadForms(endpoint: Endpoint) {
        _state.update { it.copy(forms = emptyList()) }
        remote(endpoint, retryWith = { loadForms(endpoint) }) { server ->
            val forms = server.list()
            _state.update {
                it.copy(forms = forms, message = if (forms.isEmpty()) "The server has no forms installed" else null)
            }
        }
    }

    fun forgetHostKey(hostId: String) {
        prefs.setHostKey(hostId, null)
        _state.update { it.copy(hostKeys = fingerprints(), message = "Host key for $hostId forgotten") }
    }

    fun regenerateKey() = viewModelScope.launch {
        val pk = withContext(Dispatchers.IO) { identity.regenerate(); identity.publicKeyLine() }
        _state.update { it.copy(publicKey = pk, message = "New key generated. Update authorized_keys on the server.") }
    }

    fun trustHostKey(accept: Boolean) {
        val pending = _state.value.pendingHostKey ?: return
        _state.update { it.copy(pendingHostKey = null) }
        val r = retry
        retry = null
        if (accept) {
            prefs.setHostKey(pending.hostId, pending.keyBase64)
            _state.update { it.copy(hostKeys = fingerprints()) }
            r?.invoke()
        }
    }

    /** Runs [block] against a server off the main thread, mapping failures to UI state. */
    private fun remote(
        endpoint: Endpoint,
        retryWith: () -> Unit,
        onError: ((String) -> Unit)? = null,
        block: suspend (FormServer) -> Unit,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block(FormServer(SshClient(endpoint, prefs.hostKey(endpoint.hostId), identity)))
                }
            } catch (e: UnknownHostKeyException) {
                retry = retryWith
                _state.update { it.copy(pendingHostKey = e) }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (onError != null) onError(msg) else _state.update { it.copy(message = msg) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun fingerprints(): Map<String, String> = prefs.hostKeys.mapValues { (_, v) ->
        runCatching { fingerprintOf(v) }.getOrDefault("(unreadable)")
    }
}
