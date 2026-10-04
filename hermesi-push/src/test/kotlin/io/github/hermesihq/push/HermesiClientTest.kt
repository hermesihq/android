package io.github.hermesihq.push

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Against a real HTTP server, so that what is checked is what goes on the wire: the headers, the path encoding,
 * and what the client does with a refusal. The network layer is the default one, not a fake.
 */
class HermesiClientTest {
    private lateinit var server: MockWebServer
    private var tokenCalls = 0

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        tokenCalls = 0
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    private fun client(base: String = server.url("/v1/client").toString()) = HermesiClient(
        HermesiClientOptions(publicKey = "hm_pk_test", apiBaseUrl = base, getSubscriberToken = { "st_${++tokenCalls}" }),
    )

    private fun errorBody(code: String = "invalid_device_registration", status: String = "validation_error") =
        """{"error":{"type":"$status","code":"$code","message":"Nope.","request_id":"req_1","detail":[{"field":"identifier","issue":"bad"}]}}"""

    @Test
    fun registersADeviceAsAPushChannelWithItsMetadata() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"sch_1"}"""))

        client().registerDevice("fcm-token:abc", mapOf("platform" to "android", "nested" to mapOf("a" to 1), "none" to null))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/client/channels", request.path)
        assertEquals("Bearer hm_pk_test", request.getHeader("Authorization"))
        assertEquals("st_1", request.getHeader("X-Hermesi-Subscriber-Token"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("push", body.getString("channel"))
        assertEquals("fcm-token:abc", body.getString("identifier"))
        assertEquals("android", body.getJSONObject("metadata").getString("platform"))
        assertEquals(1, body.getJSONObject("metadata").getJSONObject("nested").getInt("a"))
        assertTrue("a null is sent as null, not dropped", body.getJSONObject("metadata").isNull("none"))
        assertTrue(body.getJSONObject("metadata").has("none"))
    }

    @Test
    fun removesADeviceByTokenWithThePathEncoded() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client().unregisterDevice("fcm token/with:odd+chars")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        // A path segment: a space is %20 and a plus is a plus, and a slash cannot escape the segment.
        assertEquals("/v1/client/channels/push/fcm%20token%2Fwith%3Aodd%2Bchars", request.path)
        assertNull("a DELETE has no body", request.getHeader("Content-Type"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun asksForASubscriberTokenOnEveryRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client()

        client.registerDevice("t", emptyMap())
        client.unregisterDevice("t")

        assertEquals("st_1", server.takeRequest().getHeader("X-Hermesi-Subscriber-Token"))
        assertEquals("st_2", server.takeRequest().getHeader("X-Hermesi-Subscriber-Token"))
    }

    @Test
    fun toleratesATrailingSlashOnTheBaseUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client(server.url("/v1/client/").toString()).registerDevice("t", emptyMap())

        assertEquals("/v1/client/channels", server.takeRequest().path)
    }

    @Test
    fun turnsARefusalIntoAnErrorCarryingWhatTheApiSaid() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody(errorBody()))

        val error = runCatching { client().registerDevice("t", emptyMap()) }.exceptionOrNull()

        error as HermesiApiError
        assertEquals(422, error.status)
        assertEquals("validation_error", error.type)
        assertEquals("invalid_device_registration", error.code)
        assertEquals("Nope.", error.message)
        assertEquals("req_1", error.requestId)
        assertEquals("identifier", error.detail.single().field)
        assertFalse(error.isRetryable)
    }

    @Test
    fun marksServerErrorsAndRateLimitingAsRetryable() = runTest {
        for (status in listOf(429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(errorBody("unavailable", "internal_error")))
            val error = runCatching { client().registerDevice("t", emptyMap()) }.exceptionOrNull() as HermesiApiError
            assertTrue("$status should be retryable", error.isRetryable)
        }
    }

    @Test
    fun saysSoWhenARefusalIsNotInTheEnvelopeTheApiUses() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val error = runCatching { client().registerDevice("t", emptyMap()) }.exceptionOrNull() as HermesiApiError

        // The API never sends these, so a caller can tell the SDK failing to read an answer from the server
        // reporting a failure.
        assertEquals("sdk_error", error.type)
        assertEquals("unexpected_response", error.code)
        assertEquals(502, error.status)
        assertTrue(error.isRetryable)
    }

    @Test
    fun doesNotFollowARedirectWithTheSubscriberTokenInIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val error = runCatching { client().registerDevice("t", emptyMap()) }.exceptionOrNull()

        assertTrue(error is HermesiApiError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun throwsAnIoExceptionWhenHermesiCannotBeReached() = runTest {
        val base = server.url("/v1/client").toString()
        server.shutdown()

        try {
            client(base).registerDevice("t", emptyMap())
            fail("expected an IOException")
        } catch (expected: IOException) {
            // The caller's cue to retry later, as distinct from a refusal.
        }
    }

    @Test
    fun doesNotSendTheRequestWhenNoSubscriberTokenIsAvailable() = runTest {
        val client = HermesiClient(
            HermesiClientOptions("hm_pk_test", server.url("/v1/client").toString(), { throw IllegalStateException("signed out") }),
        )

        val error = runCatching { client.registerDevice("t", emptyMap()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, server.requestCount)
    }
}
