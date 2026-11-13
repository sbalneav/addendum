package ca.balneaves.addendum

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A record waiting to be sent. [values] are exactly what goes to the server.
 * [error] is set when the server rejected it outright.
 */
data class Pending(
    val id: String,
    val profileId: String,
    val form: String,
    val values: Map<String, String>,
    val summary: String,
    val error: String? = null,
)

/**
 * What happened in one [Outbox.flush]. [replies] maps the id of each record sent to
 * the server's reply. [stoppedBy] is the first failure that left records unsent.
 */
data class FlushOutcome(val sent: Int, val replies: Map<String, String>, val stoppedBy: Exception?)

/**
 * Store-and-forward queue. Every record goes here first and is removed only after
 * the server confirms it, so nothing is lost when the server is unreachable.
 * Records carry a unique id and the server ignores ids it has already written, so
 * resending after a dropped connection can't create duplicates.
 *
 * The queue is persisted as JSON in SharedPreferences and shared by the UI and
 * [OutboxWorker] (same process), so it's a singleton.
 */
object Outbox {
    private const val PREFS = "outbox"
    private const val KEY = "items"
    private const val WORK = "outbox-flush"

    private val lock = Any()
    private val flushMutex = Mutex()
    private var loaded = false
    private val _items = MutableStateFlow<List<Pending>>(emptyList())
    val items: StateFlow<List<Pending>> = _items.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (!loaded) {
            _items.value = load(context)
            loaded = true
        }
    }

    fun add(context: Context, p: Pending) = mutate(context) { it + p }
    fun remove(context: Context, id: String) = mutate(context) { l -> l.filterNot { it.id == id } }
    private fun setError(context: Context, id: String, error: String) =
        mutate(context) { l -> l.map { if (it.id == id) it.copy(error = error) else it } }

    /**
     * Sends queued records oldest first. When a server can't be reached (or is busy,
     * or its host key needs confirming), the rest of that server's records stay
     * queued, but other servers' records still go. Records the server rejects as
     * invalid are marked with the error and skipped. Pass [includeFailed] to try
     * those again too.
     */
    suspend fun flush(context: Context, includeFailed: Boolean): FlushOutcome = flushMutex.withLock {
        init(context)
        val prefs = Prefs(context)
        val identity = Identity(context)
        val attempted = mutableSetOf<String>()
        val blockedHosts = mutableSetOf<String>()
        val replies = LinkedHashMap<String, String>()
        var firstFailure: Exception? = null
        while (true) {
            // Re-read each time so records added mid-flush are sent too.
            val next = items.value.firstOrNull {
                it.id !in attempted && (it.error == null || includeFailed)
            } ?: break
            attempted += next.id
            val profile = prefs.profile(next.profileId)
            if (profile == null) {
                setError(context, next.id, "Its profile was deleted")
                continue
            }
            val hostId = profile.endpoint.hostId
            if (hostId in blockedHosts) continue
            try {
                val reply = withContext(Dispatchers.IO) {
                    FormServer(SshClient(profile.endpoint, prefs.hostKey(hostId), identity))
                        .add(next.form, next.values, next.id)
                }
                remove(context, next.id)
                replies[next.id] = reply
            } catch (e: ServerException) {
                if (e.permanent) {
                    setError(context, next.id, e.message ?: "Rejected")
                } else {
                    blockedHosts += hostId
                    if (firstFailure == null) firstFailure = e
                }
            } catch (e: Exception) {
                blockedHosts += hostId
                if (firstFailure == null) firstFailure = e
            }
        }
        FlushOutcome(replies.size, replies, firstFailure)
    }

    /** Ask WorkManager to flush in the background once there's a network, retrying with backoff. */
    fun scheduleRetry(context: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun mutate(context: Context, f: (List<Pending>) -> List<Pending>) = synchronized(lock) {
        init(context)
        val l = f(_items.value)
        save(context, l)
        _items.value = l
    }

    private fun load(context: Context): List<Pending> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val v = o.getJSONObject("values")
            Pending(
                id = o.getString("id"),
                profileId = o.getString("profile"),
                form = o.getString("form"),
                // JSONObject doesn't keep key order; the server doesn't care about order.
                values = v.keys().asSequence().associateWith { v.getString(it) },
                summary = o.optString("summary"),
                error = if (o.isNull("error")) null else o.getString("error"),
            )
        }
    }

    private fun save(context: Context, l: List<Pending>) {
        val arr = JSONArray()
        l.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("profile", p.profileId)
                    .put("form", p.form)
                    .put("values", JSONObject(p.values))
                    .put("summary", p.summary)
                    .put("error", p.error ?: JSONObject.NULL),
            )
        }
        // commit(), not apply(): this is the only copy of the user's record.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }
}

/** Background sender. Retries (with WorkManager's backoff) until the outbox is drained. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val r = Outbox.flush(applicationContext, includeFailed = false)
        return if (r.stoppedBy == null) Result.success() else Result.retry()
    }
}
