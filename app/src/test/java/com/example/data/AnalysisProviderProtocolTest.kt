package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalysisProviderProtocolTest {
    @Test
    fun ollamaRequestUsesConfiguredPromptTemperatureAndNormalizedEndpoint() {
        val request = AnalysisProviderProtocol.buildRequest(
            "ollama_local", AppSettings(ollamaEndpointUrl = "http://10.0.2.2:11434/api/generate/"),
            "", 0.4f, "Custom system instruction", "Evidence from OCR"
        )
        val body = body(request)
        assertEquals("http://10.0.2.2:11434/api/generate", request.url.toString())
        assertEquals("Custom system instruction", body.getString("system"))
        assertEquals("Evidence from OCR", body.getString("prompt"))
        assertEquals(0.4, body.getJSONObject("options").getDouble("temperature"), 0.00001)
        assertFalse(body.getBoolean("stream"))
        assertFalse("Structured analysis requires an answer, not a thinking-only response", body.getBoolean("think"))
        val schema = body.getJSONObject("format")
        assertEquals("object", schema.getString("type"))
        val categories = schema.getJSONObject("properties").getJSONObject("categoryLevel1").getJSONArray("enum")
        assertEquals(listOf("Technical", "Non-technical"), (0 until categories.length()).map(categories::getString))
        assertEquals(2, schema.getJSONObject("properties").getJSONObject("description").getInt("minItems"))
        assertFalse("The model must choose facts; no fixture answer belongs in the output grammar",
            schema.getJSONObject("properties").getJSONObject("evidenceNotes").getJSONObject("items").has("enum"))
        assertEquals(2048, body.getJSONObject("options").getInt("num_predict"))
    }

    @Test
    fun openAiCompatibleCloudRequestKeepsSystemRoleAndCredentialOutOfUrl() {
        val request = AnalysisProviderProtocol.buildRequest(
            "ollama_cloud", AppSettings(ollamaEndpointUrl = "https://ollama.com/v1/chat/completions/"),
            "test-key", 0.7f, "Only summarize observed content", "OCR text"
        )
        val body = body(request)
        assertEquals("https://ollama.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("test-key"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("OCR text", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(0.7, body.getDouble("temperature"), 0.00001)
    }

    @Test
    fun removedProviderCannotBuildAnyNetworkRequest() {
        try {
            AnalysisProviderProtocol.buildRequest("gemini", AppSettings(), "fixture-only", 0f, "", "")
            fail("Removed provider must be rejected")
        } catch (_: IllegalArgumentException) {
            // A retired provider cannot send a request, even when a credential exists.
        }
    }

    @Test
    fun localAndUnsupportedProvidersCannotAccidentallySendAnalysis() {
        for (provider in listOf("local", "gemini", "unexpected")) {
            try {
                AnalysisProviderProtocol.buildRequest(provider, AppSettings(), "", 0f, "", "")
                fail("$provider must not produce a network request without configuration")
            } catch (_: IllegalArgumentException) {
                // The caller retains the explicitly marked deterministic fallback.
            }
        }
    }

    @Test
    fun bothSupportedProviderEnvelopesProduceTheSameStructuredEvidence() {
        val payload = payload()
        val envelopes = listOf(
            "ollama_local" to JSONObject().put("response", payload.toString()),
            "ollama_cloud" to JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", "```json\n$payload\n```"))))
        )
        for ((provider, envelope) in envelopes) {
            val result = AnalysisProviderProtocol.parseResponse(42, envelope.toString(), provider)
            assertEquals(42L, result.videoId)
            assertEquals("Observed tutorial", result.descriptionLine1)
            assertEquals("Technical", result.categoryLevel1)
            assertEquals("Testing", result.categoryLevel2)
            assertEquals("Contract", result.categoryLevel3)
            assertEquals(listOf("line one\nline two"), result.codeOrCommands)
            assertEquals("ollama", result.source)
        }
    }

    @Test
    fun malformedEmptyAndErrorResponsesNeverBecomeFabricatedSuccessfulAnalysis() {
        for (response in listOf("{}", "{\"error\":\"model missing\"}", "{\"response\":\"{}\"}",
            payload().put("categoryLevel2", "").toString(), payload().put("description", JSONArray()).toString())) {
            try {
                AnalysisProviderProtocol.parseResponse(42, response, "ollama_local")
                fail("Malformed response became a successful analysis: $response")
            } catch (_: Exception) {
                // Production catches parser errors and uses source=fallback.
            }
        }
    }

    @Test
    fun cancellationCancelsTheHttpCallAndClosesLateResponse() = runTest {
        val call = DeferredCall()
        val request = Request.Builder().url("http://example.test/").build()
        val pending = async { executeAnalysisRequest(Call.Factory { call }, request) }
        runCurrent()
        assertTrue(call.executed)
        pending.cancel()
        runCurrent()
        assertTrue(call.cancelled)
        var closed = false
        val lateSource = object : ForwardingSource(Buffer().writeUtf8("late response")) {
            override fun close() { closed = true }
        }.buffer()
        val lateBody = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 13L
            override fun source(): BufferedSource = lateSource
        }
        call.callback!!.onResponse(call, Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(lateBody).build())
        assertTrue("A response arriving after cancellation must be closed", closed)
        try {
            pending.await()
            fail("Cancelled analysis must not return a response")
        } catch (_: CancellationException) {
            // Cancellation remains visible to the processor.
        }
    }

    private fun body(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun payload() = JSONObject()
        .put("description", JSONArray().put("Observed tutorial").put("OCR evidence"))
        .put("categoryLevel1", "Technical")
        .put("categoryLevel2", "Testing")
        .put("categoryLevel3", "Contract")
        .put("codeOrCommands", JSONArray().put("line one\nline two"))

    private class DeferredCall : Call {
        var callback: Callback? = null
        var cancelled = false
        var executed = false
        override fun request() = Request.Builder().url("http://example.test/").build()
        override fun execute(): Response = error("Blocking HTTP execution is forbidden in this test")
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = DeferredCall()
    }
}
