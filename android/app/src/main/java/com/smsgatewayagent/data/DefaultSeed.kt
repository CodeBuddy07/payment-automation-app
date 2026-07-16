package com.smsgatewayagent.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * First-run seed so the app is useful out of the box: the two payment services this operator
 * handles (bKash, Nagad) plus ready-made "money received" extraction rules for each.
 *
 * Rules use the `regex` processor with named capture groups — the group names become the fields in
 * `parsedData` that the server matches a pending deposit against. The server's matcher looks up
 * (case-insensitively) `amount`, `counterparty`, `trxId` and `datetime`, and derives the payment
 * method from the SMS sender ("bKash"/"NAGAD"), so those group names are deliberate. Patterns are
 * ICU-safe (they run on Android's stricter regex engine on newer OS versions), whitespace-tolerant,
 * and matched partially (find()) so trailing text like "Download App: …" never breaks them.
 */
object DefaultSeed {

    /** Bump when the seed content changes so existing installs pick up the new defaults once. */
    const val VERSION = "v2"

    /** Starter service catalog — the two services this operator handles. Editable in Station Setup. */
    val services = listOf("bkash", "nagad")

    /** Ready-made rules. Only seeded when the user has no rules of their own. */
    fun rules(): List<JSONObject> = listOf(
        rule(
            name = "bKash — Money received",
            sender = "bKash",
            // Anchor only on amount + sender number + TrxID; skip everything between them with .*?
            // so optional/extra fields (Ref, Fee, Balance, reordering) can never break the match:
            //   "...received Tk 100.00 from 01609527510. Ref 1. Fee Tk 0.00. ... TrxID DGB2AC4RC2 at ..."
            //   "...received Tk 1,450.00 from 01887684002. Fee Tk 0.00. ... TrxID DGB2ABL8G6 at ..."
            regex = """received\s+Tk\s+(?<amount>[\d,]+(?:\.\d+)?)\s+from\s+(?<counterparty>\d+).*?TrxID\s+(?<trxId>[A-Za-z0-9]+)(?:\s+at\s+(?<datetime>\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}))?""",
            sample = "You have received Tk 100.00 from 01609527510. Ref 1. Fee Tk 0.00. Balance Tk 10,234.16. TrxID DGB2AC4RC2 at 11/07/2026 21:41",
        ),
        rule(
            name = "Nagad — Money received",
            sender = "NAGAD",
            // Same approach for Nagad's multiline "Money Received" (DOTALL means .*? crosses newlines):
            //   "Money Received.\nAmount: Tk 156.00\nSender: 01941105000\nRef: N/A\nTxnID: 75KOJBDW\n..."
            // Ref/Balance and anything else between the anchors are skipped.
            regex = """Amount:\s*Tk\s+(?<amount>[\d,]+(?:\.\d+)?).*?Sender:\s*(?<counterparty>\d+).*?TxnID:\s*(?<trxId>[A-Za-z0-9]+)(?:.*?(?<datetime>\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}))?""",
            sample = "Money Received.\nAmount: Tk 156.00\nSender: 01941105000\nRef: N/A\nTxnID: 75KOJBDW\nBalance: Tk 456.30\n26/06/2026 12:55",
        ),
    )

    private fun rule(name: String, sender: String, regex: String, sample: String): JSONObject = JSONObject().apply {
        put("name", name)
        put("senderPattern", sender)          // bKash arrives as "bKash", Nagad as "NAGAD" (matched case-insensitively)
        put("senderMatchType", "contains")
        put("processorType", "regex")
        put("regex", regex)
        put("enabled", true)
        put("priority", 10)
        put("sampleMessages", JSONArray().put(sample))
    }
}
