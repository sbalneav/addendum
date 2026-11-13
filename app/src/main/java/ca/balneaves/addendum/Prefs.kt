package ca.balneaves.addendum

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One form on one server, as set up in Settings. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val form: String = "",
) {
    val endpoint: Endpoint get() = Endpoint(host, port, user)
    val displayName: String get() = name.ifBlank { form.ifBlank { "Untitled" } }
}

/**
 * Thin wrapper over SharedPreferences. Everything persistent except the outbox and
 * the phone's key lives here: profiles, pinned host keys (per server), and per
 * profile the cached spec and status (so the form works offline) and the last
 * values of "remember" fields.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("addendum", Context.MODE_PRIVATE)

    var profiles: List<Profile>
        get() {
            val arr = JSONArray(sp.getString(K_PROFILES, "[]"))
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user"),
                    form = o.optString("form"),
                )
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                        .put("port", it.port).put("user", it.user).put("form", it.form),
                )
            }
            sp.edit().putString(K_PROFILES, arr.toString()).apply()
        }

    fun profile(id: String?): Profile? = profiles.firstOrNull { it.id == id }

    var currentProfileId: String?
        get() = sp.getString(K_CURRENT, null)
        set(v) = sp.edit().putString(K_CURRENT, v).apply()

    /** Pinned host keys: hostId (see [Endpoint.hostId]) -> base64 key blob, as in known_hosts. */
    val hostKeys: Map<String, String>
        get() = readMap(K_HOSTKEYS)

    fun hostKey(hostId: String): String? = hostKeys[hostId]

    fun setHostKey(hostId: String, keyBase64: String?) {
        val m = hostKeys.toMutableMap()
        if (keyBase64 == null) m.remove(hostId) else m[hostId] = keyBase64
        writeMap(K_HOSTKEYS, m)
    }

    fun spec(profileId: String): String? = sp.getString("spec_$profileId", null)
    fun setSpec(profileId: String, text: String?) = sp.edit().putString("spec_$profileId", text).apply()

    fun status(profileId: String): Pair<String, Long>? =
        sp.getString("status_$profileId", null)?.let { it to sp.getLong("status_at_$profileId", 0) }

    fun setStatus(profileId: String, text: String, at: Long) =
        sp.edit().putString("status_$profileId", text).putLong("status_at_$profileId", at).apply()

    /** Last sent value of each "remember" field. */
    fun lastValues(profileId: String): Map<String, String> = readMap("last_$profileId")
    fun setLastValues(profileId: String, values: Map<String, String>) = writeMap("last_$profileId", values)

    /** Forget everything cached for a deleted profile. */
    fun clearProfileData(profileId: String) = sp.edit()
        .remove("spec_$profileId").remove("status_$profileId").remove("status_at_$profileId")
        .remove("last_$profileId").apply()

    private fun readMap(key: String): Map<String, String> {
        val o = JSONObject(sp.getString(key, "{}")!!)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun writeMap(key: String, m: Map<String, String>) =
        sp.edit().putString(key, JSONObject(m).toString()).apply()

    private companion object {
        const val K_PROFILES = "profiles"
        const val K_CURRENT = "current_profile"
        const val K_HOSTKEYS = "host_keys"
    }
}
