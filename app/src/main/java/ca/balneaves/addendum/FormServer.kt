package ca.balneaves.addendum

/**
 * The server answered with an error. [permanent] (exit 1) means the request itself is bad,
 * so retrying won't help. Otherwise (exit 2, or anything else) it's a temporary problem.
 */
class ServerException(message: String, val permanent: Boolean) : Exception(message)

/**
 * Client side of the protocol spoken by server/addendum (see ARCHITECTURE.md).
 * Keep the two in sync.
 *
 *   list            : stdout is one form name per line
 *   <form> spec     : stdout is the form description
 *   <form> status   : stdout is text for the status panel
 *   <form> add      : stdin is "name<TAB>value" lines plus "_id<TAB><uuid>";
 *                     stdout is "OK" then the text that was added
 *   exit status     : 0 ok, 1 bad request (permanent), 2 temporary server problem
 */
class FormServer(private val ssh: SshClient) {

    fun list(): List<String> =
        call("list").lines().map { it.trim() }.filter { NAME.matches(it) }

    fun spec(form: String): String = call("${checked(form)} spec")

    fun status(form: String): String = call("${checked(form)} status").trimEnd()

    /** Sends one record; returns the text the server says it added. */
    fun add(form: String, values: Map<String, String>, id: String): String {
        val out = call("${checked(form)} add", FormLogic.encode(values, id))
        if (!out.startsWith("OK")) throw ServerException(out.ifBlank { "No reply from the server" }.trim(), permanent = false)
        return out.removePrefix("OK").trim('\n')
    }

    private fun call(command: String, stdin: String = ""): String {
        val r = ssh.run(command, stdin)
        if (r.exitCode != 0) {
            val msg = r.stderr.trim().ifBlank { r.stdout.trim() }.ifBlank { "\"$command\" failed (exit ${r.exitCode})" }
            throw ServerException(msg, permanent = r.exitCode == 1)
        }
        return r.stdout
    }

    private fun checked(form: String): String {
        require(NAME.matches(form)) { "Not a valid form name: \"$form\"" }
        return form
    }

    companion object {
        val NAME = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
