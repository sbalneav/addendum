package ca.balneaves.addendum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SpecTest {
    private val appointment = """
        title	Appointment
        button	Add appointment
        field	when	date	Date	required	default=today
        field	time	time	Time
        field	what	text	What	required	hint=Dentist, Auto Haus…
        status
    """.trimIndent()

    private val ledger = """
        # a comment
        field	payee	text	What was it for?	required
        field	amount	number	Amount	required	decimals=2
        field	category	dropdown	Category	required
        choice	category	Expenses:Food
        choice	category	Expenses:Car:Fuel	Fuel
        field	source	radio	Paid from	required	remember
        choice	source	Assets:Chequing
        choice	source	Liabilities:Mastercard
        field	cleared	checkbox	Cleared
        frobnicate	whatever
        field	weird	sparkly	Weird	unknownattr
    """.trimIndent()

    private val today = LocalDate.of(2026, 11, 13)

    @Test fun parsesAppointment() {
        val s = FormSpec.parse(appointment)
        assertEquals("Appointment", s.title)
        assertEquals("Add appointment", s.button)
        assertTrue(s.hasStatus)
        assertEquals(listOf("when", "time", "what"), s.fields.map { it.name })
        assertEquals(FieldType.DATE, s.fields[0].type)
        assertTrue(s.fields[0].required)
        assertEquals("today", s.fields[0].default)
        assertEquals("Dentist, Auto Haus…", s.fields[2].hint)
    }

    @Test fun parsesChoicesAndIgnoresUnknowns() {
        val s = FormSpec.parse(ledger)
        assertNull(s.title)
        assertFalse(s.hasStatus)
        val cat = s.fields.first { it.name == "category" }
        assertEquals(listOf("Expenses:Food", "Expenses:Car:Fuel"), cat.choices.map { it.value })
        assertEquals("Fuel", cat.choices[1].label)
        assertEquals(2, s.fields.first { it.name == "amount" }.decimals)
        assertEquals(FieldType.TEXT, s.fields.first { it.name == "weird" }.type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noFieldsIsAnError() {
        FormSpec.parse("title\tEmpty\n")
    }

    @Test fun rejectsBadFieldNames() {
        val s = FormSpec.parse("field\t_id\ttext\tX\nfield\tBad Name\ttext\tX\nfield\tok\ttext\tOK\n")
        assertEquals(listOf("ok"), s.fields.map { it.name })
    }

    @Test fun initialValues() {
        val s = FormSpec.parse(appointment)
        assertEquals(mapOf("when" to "2026-11-13", "time" to "", "what" to ""), FormLogic.initialValues(s, emptyMap(), today))

        val l = FormSpec.parse(ledger)
        val v = FormLogic.initialValues(l, mapOf("source" to "Liabilities:Mastercard"), today)
        assertEquals("Liabilities:Mastercard", v["source"])  // remembered
        assertEquals("no", v["cleared"])
        // A remembered value that's no longer a choice falls back to the first radio choice.
        assertEquals("Assets:Chequing", FormLogic.initialValues(l, mapOf("source" to "Gone"), today)["source"])
    }

    @Test fun resetKeepsOnlyKeepFields() {
        val s = FormSpec.parse("field\twhen\tdate\tDate\tdefault=today\tkeep\nfield\twhat\ttext\tWhat\n")
        val r = FormLogic.resetValues(s, mapOf("when" to "2026-12-25", "what" to "x"), emptyMap(), today)
        assertEquals(mapOf("when" to "2026-12-25", "what" to ""), r)
    }

    @Test fun validation() {
        val l = FormSpec.parse(ledger)
        val ok = mapOf("payee" to "Gas", "amount" to "$1,052.3", "category" to "Expenses:Food", "source" to "Assets:Chequing")
        assertEquals(emptyMap<String, String>(), FormLogic.validate(l, ok))

        val bad = FormLogic.validate(l, ok + mapOf("payee" to "  ", "amount" to "1.234", "category" to "Nope"))
        assertEquals(setOf("payee", "amount", "category"), bad.keys)
        assertEquals("At most 2 decimal places", bad["amount"])

        val a = FormSpec.parse(appointment)
        val e = FormLogic.validate(a, mapOf("when" to "2026-02-30", "time" to "25:00", "what" to "x"))
        assertEquals(setOf("when", "time"), e.keys)
    }

    @Test fun outgoingAndEncode() {
        val l = FormSpec.parse(ledger)
        val out = FormLogic.outgoing(l, mapOf("payee" to " Gas\tstation\n", "amount" to "$1,052.30"))
        assertEquals("Gasstation", out["payee"])
        assertEquals("1052.30", out["amount"])
        assertEquals("", out["category"])
        assertEquals("a\t1\nb\t\n_id\tabc\n", FormLogic.encode(linkedMapOf("a" to "1", "b" to ""), "abc"))
    }

    @Test fun summaryUsesLabels() {
        val l = FormSpec.parse(ledger)
        val v = mapOf("payee" to "Gas", "amount" to "52.30", "category" to "Expenses:Car:Fuel", "cleared" to "yes", "source" to "")
        assertEquals("Gas · 52.30 · Fuel · Cleared", FormLogic.summary(l, v))
    }

    @Test fun fieldErrors() {
        val a = FormSpec.parse(appointment)
        assertEquals("when" to "not a valid date: x", FormLogic.fieldError(a, "when: not a valid date: x"))
        assertEquals(null to "addendum: busy", FormLogic.fieldError(a, "addendum: busy"))
        assertEquals(null to "nope: whatever", FormLogic.fieldError(a, "nope: whatever"))
    }
}
