package io.github.hermesihq.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Settings for [HermesiClient].
 *
 * @property publicKey The environment's public key, `hm_pk_...`. It is safe to ship in an app: on its own it
 * grants nothing.
 * @property getSubscriberToken Returns a token that proves which subscriber this device belongs to. Your backend
 * mints it with your secret key, so this calls your backend; the SDK never has the secret. It is called before
 * every request, so return a cached token while it is still valid and fetch a new one when it is not.
 */
public class HermesiClientOptions(
    public val publicKey: String,
    apiBaseUrl: String,
    public val getSubscriberToken: suspend () -> String,
    public val connectTimeoutMillis: Int = 10_000,
    public val readTimeoutMillis: Int = 15_000,
) {
    /** The client API root, for example `https://api.example.com/v1/client`, without a trailing slash. */
    public val apiBaseUrl: String = apiBaseUrl.trimEnd('/')
}

/** One thing wrong with a request, as the API describes it. */
public class HermesiErrorDetail(public val field: String?, public val issue: String?)

/**
 * Hermesi refused a request. Branch on [code], which is stable; [message] is for a developer and may change.
 * A failure to reach Hermesi at all is an [IOException] instead, which is worth retrying.
 */
public class HermesiApiError(
    public val status: Int,
    public val type: String,
    public val code: String,
    message: String,
    public val requestId: String,
    public val detail: List<HermesiErrorDetail>,
) : Exception(message) {
    /** True for a failure that asking again later can fix: a server error or rate limiting. */
    public val isRetryable: Boolean get() = status == 429 || status >= 500

    internal companion object {
        fun from(status: Int, body: String): HermesiApiError {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: JSONException) {
                null
            }
            val details = error?.optJSONArray("detail")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let {
                        HermesiErrorDetail(it.optString("field").ifEmpty { null }, it.optString("issue").ifEmpty { null })
                    }
                }
            }.orEmpty()
            return HermesiApiError(
                status = status,
                // The API never sends this type, so a caller can tell the SDK reporting a response it could not
                // read from the server reporting a failure.
                type = error?.optString("type").orEmpty().ifEmpty { "sdk_error" },
                code = error?.optString("code").orEmpty().ifEmpty { "unexpected_response" },
                message = error?.optString("message").orEmpty().ifEmpty { "Hermesi request failed with status $status." },
                requestId = error?.optString("request_id").orEmpty(),
                detail = details,
            )
        }
    }
}

/** What goes over the wire, so a test can stand in for the network. */
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: String?,
)

public class HttpResponse(public val status: Int, public val body: String)

public fun interface HttpTransport {
    /** Sends the request and returns the response whatever its status. Throws [IOException] if there is none. */
    public suspend fun execute(request: HttpRequest): HttpResponse
}

/** The default transport: `HttpURLConnection`, so the SDK brings no HTTP library of its own. */
internal class UrlConnectionTransport(
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            // A redirect would carry the subscriber token to wherever it points.
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            HttpResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/** The two calls a device registration makes. [HermesiClient] is the implementation; this is what the rest uses. */
public interface DeviceApi {
    /** Registers or refreshes this device's token. Registering the same token again is an update. */
    public suspend fun registerDevice(token: String, metadata: Map<String, Any?>)

    /** Removes a token. A token Hermesi does not have is not an error. */
    public suspend fun unregisterDevice(token: String)
}

/**
 * Talks to Hermesi's client API on behalf of one subscriber.
 *
 * Every function is a `suspend` function and can be called from any dispatcher. It throws [HermesiApiError] when
 * Hermesi refuses and [IOException] when it cannot be reached.
 */
public class HermesiClient @JvmOverloads public constructor(
    private val options: HermesiClientOptions,
    private val transport: HttpTransport = UrlConnectionTransport(options.connectTimeoutMillis, options.readTimeoutMillis),
) : DeviceApi {

    override suspend fun registerDevice(token: String, metadata: Map<String, Any?>) {
        val body = JSONObject()
            .put("channel", PUSH_CHANNEL)
            .put("identifier", token)
            .put("metadata", toJson(metadata))
        send("POST", "/channels", body.toString())
    }

    override suspend fun unregisterDevice(token: String) {
        send("DELETE", "/channels/$PUSH_CHANNEL/${encodePathSegment(token)}", null)
    }

    private suspend fun send(method: String, path: String, body: String?) {
        val headers = buildMap {
            put("Authorization", "Bearer ${options.publicKey}")
            put("X-Hermesi-Subscriber-Token", options.getSubscriberToken())
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        val response = transport.execute(HttpRequest(method, options.apiBaseUrl + path, headers, body))
        if (response.status !in 200..299) throw HermesiApiError.from(response.status, response.body)
    }

    private companion object {
        const val PUSH_CHANNEL = "push"

        /** A path segment, not a query value: a space is `%20`, and `+` is a plus. */
        fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun toJson(value: Any?): Any? = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { json -> value.forEach { (k, v) -> json.put(k.toString(), toJson(v)) } }
            is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(toJson(it)) } }
            else -> value
        }
    }
}
