package com.smsgatewayagent.processor

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Deterministic (non-AI) template engine. It powers two features:
 *
 *  1. Training      — turn one or more sample messages into a reusable `{placeholder}` template.
 *  2. Extraction    — compile a template (or raw regex) into a named-group [Pattern] and pull
 *                     structured fields out of a live SMS.
 *
 * Placeholder names double as type hints so the compiled regex uses a sensible sub-pattern.
 */
object TemplateEngine {

    /** A recognised entity: capture group 1 of [pattern] is the value to turn into a placeholder. */
    private data class Detector(val name: String, val priority: Int, val pattern: Pattern)

    /**
     * Compile one detector defensively. A malformed pattern must never take down the whole
     * initializer — on some engines (Android's ICU-backed regex from API 34+) a construct another
     * engine tolerates is a hard [java.util.regex.PatternSyntaxException]. If that escaped here it
     * would poison this object's `<clinit>` and every later access would throw
     * NoClassDefFoundError, crashing the SMS pipeline on every message. So we drop the bad detector
     * and keep going instead.
     */
    private fun detector(name: String, priority: Int, regex: String): Detector? =
        try {
            Detector(name, priority, Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
        } catch (t: Throwable) {
            android.util.Log.e("TemplateEngine", "Skipping detector '$name' (bad regex): ${t.message}")
            null
        }

    // Ordered most-specific → most-generic. Lower priority number wins on overlap.
    // NOTE: keep every pattern ICU-safe — no quantifier applied directly to a zero-width assertion
    // (write `(?:\b)?`, never `\b?`), or newer Android will reject it at compile time.
    private val detectors: List<Detector> = listOfNotNull(
        detector("otp", 1, """\b(?:otp|one[\s-]?time[\s-]?password|verification\s+code|secret\s+code|code|pin)\b[^0-9]{0,20}(\d{4,8})"""),
        detector("trxId", 2, """\b(?:trx[\s._-]?id|txn[\s._-]?id|transaction\s*id|trans\s*id|trxid|tid)\b[:\s._-]*([A-Za-z0-9]{4,})"""),
        detector("reference", 3, """\b(?:ref(?:erence)?(?:\s*no)?)\b[:\s._#-]*([A-Za-z0-9]{3,})"""),
        detector("balance", 4, """\b(?:bal(?:ance)?|avbl\s*bal|available\s*balance|current\s*balance)\b[^0-9]{0,15}([\d,]+(?:\.\d+)?)"""),
        detector("account", 5, """\b(?:a\/c|acc(?:ount)?(?:\s*(?:no|number))?)\b[^A-Za-z0-9]{0,6}([Xx*]{0,}\d{3,})"""),
        detector("amount", 6, """(?:tk|bdt|rs|inr|usd|amount|amt|\$|৳)(?:\b)?[\s.:]*([\d,]+(?:\.\d+)?)"""),
        detector("phone", 7, """(\+?\d[\d\s-]{8,}\d)"""),
        detector("date", 8, """(\d{1,4}[\/\-.]\d{1,2}[\/\-.]\d{1,4})"""),
        detector("time", 9, """(\d{1,2}:\d{2}(?::\d{2})?\s?(?:[AaPp][Mm])?)"""),
        detector("number", 20, """([\d,]+(?:\.\d+)?)""")
    )

    /** Sub-pattern used when a placeholder is compiled back into a regex. */
    private fun subPatternFor(name: String): String = when (baseName(name)) {
        "amount", "balance", "number" -> """[\d,]+(?:\.\d+)?"""
        "phone" -> """\+?\d[\d\s-]{8,}\d"""
        "otp" -> """\d{4,8}"""
        "date" -> """\d{1,4}[\/\-.]\d{1,2}[\/\-.]\d{1,4}"""
        "time" -> """\d{1,2}:\d{2}(?::\d{2})?\s?(?:[AaPp][Mm])?"""
        "account" -> """[Xx*]{0,}\d{2,}"""
        "trxId", "reference" -> """[A-Za-z0-9]+"""
        else -> """.+?"""
    }

    private fun baseName(name: String) = name.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

    // ----------------------------------------------------------------- Training
    /**
     * Generate a `{placeholder}` template from a single sample message by replacing every
     * recognised entity span with a uniquely named placeholder.
     */
    fun generateTemplate(sample: String): String {
        data class Span(val start: Int, val end: Int, val name: String, val priority: Int)

        val spans = mutableListOf<Span>()
        for (d in detectors) {
            val m = d.pattern.matcher(sample)
            while (m.find()) {
                if (m.groupCount() >= 1 && m.group(1) != null) {
                    spans.add(Span(m.start(1), m.end(1), d.name, d.priority))
                }
            }
        }
        // Resolve overlaps: keep higher priority (lower number), then longer, then earlier.
        spans.sortWith(compareBy({ it.priority }, { -(it.end - it.start) }, { it.start }))
        val chosen = mutableListOf<Span>()
        for (s in spans) {
            if (chosen.none { s.start < it.end && it.start < s.end }) chosen.add(s)
        }
        chosen.sortBy { it.start }

        val nameCounts = HashMap<String, Int>()
        val sb = StringBuilder()
        var cursor = 0
        for (s in chosen) {
            if (s.start < cursor) continue
            sb.append(sample, cursor, s.start)
            val count = (nameCounts[s.name] ?: 0) + 1
            nameCounts[s.name] = count
            val finalName = if (count == 1) s.name else "${s.name}$count"
            sb.append("{").append(finalName).append("}")
            cursor = s.end
        }
        sb.append(sample, cursor, sample.length)
        return sb.toString()
    }

    /**
     * Train from multiple samples: produce the template that best generalises them
     * (currently the template generated from the richest sample, plus its compiled regex).
     */
    fun train(samples: List<String>): JSONObject {
        val best = samples.maxByOrNull { generateTemplate(it).count { ch -> ch == '{' } } ?: samples.firstOrNull() ?: ""
        val template = generateTemplate(best)
        val regex = templateToRegex(template)
        val placeholders = JSONArray().apply { placeholderNames(template).forEach { put(it) } }
        return JSONObject().apply {
            put("template", template)
            put("regex", regex)
            put("placeholders", placeholders)
            put("sampleMessages", JSONArray(samples))
        }
    }

    // --------------------------------------------------------------- Extraction
    /** Convert a `{placeholder}` template into a Java regex with named capture groups. */
    fun templateToRegex(template: String): String {
        val token = Pattern.compile("""\{([A-Za-z][A-Za-z0-9]*)\}""")
        val m = token.matcher(template)
        val sb = StringBuilder("^\\s*")
        var cursor = 0
        val used = HashSet<String>()
        while (m.find()) {
            val literal = template.substring(cursor, m.start())
            sb.append(escapeLiteral(literal))
            var name = m.group(1)!!
            // Java named groups must be unique; suffix duplicates defensively.
            var unique = name; var i = 2
            while (!used.add(unique)) { unique = "$name$i"; i++ }
            sb.append("(?<").append(unique).append(">").append(subPatternFor(name)).append(")")
            cursor = m.end()
        }
        sb.append(escapeLiteral(template.substring(cursor)))
        sb.append("\\s*")
        return sb.toString()
    }

    /** Run a compiled named-group regex against [text] and return the captured fields. */
    fun extract(regex: String, text: String): JSONObject {
        val result = JSONObject()
        val names = groupNames(regex)
        val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            for (n in names) {
                try {
                    val v = matcher.group(n)
                    if (v != null) result.put(n, v.trim())
                } catch (_: Exception) { /* group not present in this match */ }
            }
        }
        return result
    }

    fun matches(regex: String, text: String): Boolean =
        Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(text).find()

    fun placeholderNames(template: String): List<String> {
        val m = Pattern.compile("""\{([A-Za-z][A-Za-z0-9]*)\}""").matcher(template)
        val out = mutableListOf<String>()
        while (m.find()) out.add(m.group(1)!!)
        return out
    }

    private fun groupNames(regex: String): List<String> {
        val m = Pattern.compile("""\(\?<([A-Za-z][A-Za-z0-9]*)>""").matcher(regex)
        val out = mutableListOf<String>()
        while (m.find()) out.add(m.group(1)!!)
        return out
    }

    /** Escape regex metacharacters in literal text and make runs of whitespace flexible. */
    private fun escapeLiteral(literal: String): String {
        if (literal.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c.isWhitespace()) {
                while (i < literal.length && literal[i].isWhitespace()) i++
                sb.append("""\s+""")
            } else {
                if ("\\.[]{}()*+-?^\$|".indexOf(c) >= 0) sb.append('\\')
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
