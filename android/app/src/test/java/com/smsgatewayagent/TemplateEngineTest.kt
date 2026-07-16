package com.smsgatewayagent

import com.smsgatewayagent.processor.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {

    @Test
    fun generatesTemplateFromBkashStyleSms() {
        val sample = "Cash In Tk 500 from 01711111111. TrxID ABC12345. Balance Tk 2000."
        val template = TemplateEngine.generateTemplate(sample)
        // Every recognised entity becomes a placeholder.
        assertTrue(template.contains("{amount}"))
        assertTrue(template.contains("{phone}"))
        assertTrue(template.contains("{trxId}"))
        assertTrue(template.contains("{balance}"))
        // Literal scaffolding is preserved.
        assertTrue(template.startsWith("Cash In Tk {amount} from {phone}"))
    }

    @Test
    fun compiledTemplateExtractsNamedFields() {
        val template = "Cash In Tk {amount} from {phone}. TrxID {trxId}. Balance Tk {balance}."
        val regex = TemplateEngine.templateToRegex(template)
        val data = TemplateEngine.extract(
            regex,
            "Cash In Tk 500 from 01711111111. TrxID ABC12345. Balance Tk 2000.",
        )
        assertEquals("500", data.getString("amount"))
        assertEquals("01711111111", data.getString("phone"))
        assertEquals("ABC12345", data.getString("trxId"))
        assertEquals("2000", data.getString("balance"))
    }

    @Test
    fun otpIsDetected() {
        val template = TemplateEngine.generateTemplate("Your OTP is 482913. Do not share it.")
        assertTrue(template.contains("{otp}"))
    }

    @Test
    fun nonMatchingBodyReturnsNoFields() {
        val regex = TemplateEngine.templateToRegex("Balance is Tk {balance}")
        assertTrue(!TemplateEngine.matches(regex, "Totally unrelated message"))
    }

    @Test
    fun trainMergesSamplesAndExposesPlaceholders() {
        val result = TemplateEngine.train(
            listOf(
                "Cash In Tk 500 from 01711111111. TrxID ABC12345. Balance Tk 2000.",
                "Cash In Tk 750 from 01822222222. TrxID XYZ99999. Balance Tk 3000.",
            ),
        )
        assertTrue(result.getString("template").contains("{amount}"))
        assertTrue(result.getJSONArray("placeholders").length() >= 4)
    }
}
