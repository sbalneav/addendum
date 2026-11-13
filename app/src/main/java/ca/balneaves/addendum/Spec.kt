package ca.balneaves.addendum

import java.time.LocalDate

/**
 * The form description a server script prints for "<form> spec".
 * See ARCHITECTURE.md ("The spec format"). Plain Kotlin, no Android, so it's unit-tested.
 */
enum class FieldType {
    TEXT, NUMBER, DATE, TIME, DROPDOWN, RADIO, CHECKBOX;

    companion object {
        /** Unknown types fall back to TEXT, so a newer script still works on an older phone. */
        fun parse(s: String): FieldType = when (s) {
            "number" -> NUMBER
            "date" -> DATE
            "time" -> TIME
            "dropdown" -> DROPDOWN
            "radio" -> RADIO
            "checkbox" -> CHECKBOX
            else -> TEXT
        }
    }
}

data class Choice(val value: String, val label: String)

data class FieldSpec(
    val name: String,
    val type: FieldType,
    val label: String,
    val required: Boolean = false,
    val default: String? = null,
    val remember: Boolean = false,
    val hint: String? = null,
    val decimals: Int? = null,
    val keep: Boolean = false,
    val choices: List<Choice> = emptyList(),
)

data class FormSpec(
    val title: String?,
    val button: String?,
    val fields: List<FieldSpec>,
    val hasStatus: Boolean,
) {
    companion object {
        private val NAME = Regex("^[a-z0-9_]+$")

        /** Parses spec text. Unknown directives and attributes are ignored. Throws if there are no fields. */
        fun parse(text: String): FormSpec {
            var title: String? = null
            var button: String? = null
            var hasStatus = false
            val fields = LinkedHashMap<String, FieldSpec>()

            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                when (p[0]) {
                    "title" -> title = p.getOrNull(1)?.takeIf { it.isNotBlank() }
                    "button" -> button = p.getOrNull(1)?.takeIf { it.isNotBlank() }
                    "status" -> hasStatus = true
                    "field" -> {
                        if (p.size < 4) continue
                        val name = p[1]
                        if (!NAME.matches(name) || name == "_id" || name in fields) continue
                        var f = FieldSpec(name, FieldType.parse(p[2]), p[3].ifBlank { name })
                        for (attr in p.drop(4)) {
                            val k = attr.substringBefore('=')
                            val v = attr.substringAfter('=', "")
                            f = when (k) {
                                "required" -> f.copy(required = true)
                                "remember" -> f.copy(remember = true)
                                "keep" -> f.copy(keep = true)
                                "default" -> f.copy(default = v)
                                "hint" -> f.copy(hint = v)
                                "decimals" -> f.copy(decimals = v.toIntOrNull())
                                else -> f
                            }
                        }
                        fields[name] = f
                    }
                    "choice" -> {
                        if (p.size < 3) continue
                        val f = fields[p[1]] ?: continue
                        val label = p.getOrNull(3)?.takeIf { it.isNotBlank() } ?: p[2]
                        fields[f.name] = f.copy(choices = f.choices + Choice(p[2], label))
                    }
                }
            }
            require(fields.isNotEmpty()) { "The form has no fields." }
            return FormSpec(title, button, fields.values.toList(), hasStatus)
        }
    }
}

/** The rules for filling in, checking and sending a form. */
object FormLogic {
    private val NUMBER = Regex("""^\d+(\.\d+)?$""")
    private val TIME = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    /** Starting value for a field: the remembered one if allowed, otherwise its default. */
    fun initialValue(f: FieldSpec, last: String?, today: LocalDate): String {
        if (f.remember && !last.isNullOrEmpty() && (f.choices.isEmpty() || f.choices.any { it.value == last })) {
            return last
        }
        val d = f.default
        return when (f.type) {
            FieldType.DATE -> when {
                d == "today" -> today.toString()
                d != null && parseDate(d) != null -> d
                else -> ""
            }
            FieldType.TIME -> d?.takeIf { TIME.matches(it) }.orEmpty()
            FieldType.CHECKBOX -> if (d == "yes") "yes" else "no"
            FieldType.DROPDOWN, FieldType.RADIO ->
                d?.takeIf { v -> f.choices.any { it.value == v } }
                    ?: if (f.type == FieldType.RADIO && f.required) f.choices.firstOrNull()?.value.orEmpty() else ""
            else -> d.orEmpty()
        }
    }

    fun initialValues(spec: FormSpec, last: Map<String, String>, today: LocalDate): Map<String, String> =
        spec.fields.associate { it.name to initialValue(it, last[it.name], today) }

    /** After a successful send: "keep" fields stay as they are, the rest start over. */
    fun resetValues(spec: FormSpec, current: Map<String, String>, last: Map<String, String>, today: LocalDate) =
        spec.fields.associate {
            it.name to if (it.keep) current[it.name].orEmpty() else initialValue(it, last[it.name], today)
        }

    /** One line, no control characters, trimmed: what the protocol allows. */
    fun clean(v: String): String = v.filterNot { it.isISOControl() }.trim()

    /** "$1,234.5" -> "1234.5"; doesn't validate. */
    fun normaliseNumber(v: String): String = clean(v).removePrefix("$").replace(",", "").trim()

    /** Field name -> message, for every field that's wrong. Empty means OK to send. */
    fun validate(spec: FormSpec, values: Map<String, String>): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        for (f in spec.fields) {
            val v = clean(values[f.name].orEmpty())
            if (v.isEmpty() || (f.type == FieldType.CHECKBOX && v == "no")) {
                if (f.required) errors[f.name] = "Required"
                continue
            }
            val problem = when (f.type) {
                FieldType.NUMBER -> {
                    val n = normaliseNumber(v)
                    when {
                        !NUMBER.matches(n) -> "Enter a number, like 12.50"
                        f.decimals != null && n.substringAfter('.', "").length > f.decimals ->
                            if (f.decimals == 0) "Whole numbers only" else "At most ${f.decimals} decimal places"
                        else -> null
                    }
                }
                FieldType.DATE -> if (parseDate(v) == null) "Not a valid date" else null
                FieldType.TIME -> if (!TIME.matches(v)) "Not a valid time" else null
                FieldType.DROPDOWN, FieldType.RADIO ->
                    if (f.choices.none { it.value == v }) "Pick one from the list" else null
                else -> null
            }
            if (problem != null) errors[f.name] = problem
        }
        return errors
    }

    /** The values as the server receives them (cleaned, numbers normalised), in field order. */
    fun outgoing(spec: FormSpec, values: Map<String, String>): Map<String, String> =
        spec.fields.associate { f ->
            val v = values[f.name].orEmpty()
            f.name to if (f.type == FieldType.NUMBER) normaliseNumber(v) else clean(v)
        }

    /** stdin for "<form> add": "name<TAB>value" lines plus the "_id" line. */
    fun encode(values: Map<String, String>, id: String): String = buildString {
        values.forEach { (k, v) -> append(k).append('\t').append(clean(v)).append('\n') }
        append("_id\t").append(id).append('\n')
    }

    /** A short one-line description of a record, for the waiting-to-send list. */
    fun summary(spec: FormSpec, values: Map<String, String>): String =
        spec.fields.mapNotNull { f ->
            val v = values[f.name].orEmpty()
            when {
                v.isEmpty() -> null
                f.type == FieldType.CHECKBOX -> if (v == "yes") f.label else null
                f.choices.isNotEmpty() -> f.choices.firstOrNull { it.value == v }?.label ?: v
                else -> v
            }
        }.joinToString(" · ")

    /**
     * Splits a server error like "when: not a valid date" into (field, message)
     * when the prefix names a field in [spec]; otherwise (null, whole message).
     */
    fun fieldError(spec: FormSpec?, message: String): Pair<String?, String> {
        val name = message.substringBefore(": ", "")
        if (spec != null && spec.fields.any { it.name == name }) {
            return name to message.substringAfter(": ").trim()
        }
        return null to message
    }

    fun parseDate(v: String): LocalDate? = try {
        LocalDate.parse(v)
    } catch (_: Exception) {
        null
    }
}
