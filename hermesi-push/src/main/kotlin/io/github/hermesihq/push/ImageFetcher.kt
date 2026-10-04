package io.github.hermesihq.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Downloads the picture for a notification that arrived while the app was open, within limits that keep a hostile or broken
 * URL from costing the app anything.
 *
 * The URL comes from a template, not from the app, and it is fetched on the person's phone, so this is strict: `https` only,
 * a redirect is followed only to another allowed address and at most three times, no more than [maxBytes] is ever held
 * (a response that announces more is refused before it is read, and one that does not is cut off when it has sent too
 * much), the picture is decoded at a size a notification can use and not at its full resolution (a 6000 pixel photograph
 * is 144 MB as a bitmap), and the whole thing gives up after a few seconds. Every failure is a null, never an exception: a
 * notification whose picture could not be fetched is still shown, as text.
 *
 * Blocking: call it from a background thread. Firebase delivers a message on one.
 */
internal class ImageFetcher(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    /** Tests serve from a plain-http local server; production never turns this off. */
    private val requireHttps: Boolean = true,
) {

    fun fetch(url: String): Bitmap? {
        return try {
            val bytes = download(url) ?: return null
            decode(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch the notification picture.", e)
            null
        }
    }

    private fun download(start: String): ByteArray? {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val url = allowed(current) ?: return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return read(connection)
                    301, 302, 303, 307, 308 -> {
                        val next = connection.getHeaderField("Location") ?: return null
                        current = URI(current).resolve(next).toString()
                    }
                    else -> {
                        Log.w(TAG, "The notification picture answered HTTP $status.")
                        return null
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun allowed(raw: String): URL? {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val ok = scheme == "https" || (scheme == "http" && !requireHttps)
        return if (ok && !uri.host.isNullOrEmpty()) uri.toURL() else null
    }

    private fun read(connection: HttpURLConnection): ByteArray? {
        // `contentLength` is -1 when it is not announced (or does not fit an Int), and the count below covers that.
        if (connection.contentLength > maxBytes) return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > maxBytes) return null
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Decodes at the smallest size that is still at least [maxDimension] on its longest side, not at full resolution. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) },
        )
    }

    internal companion object {
        const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024
        const val DEFAULT_MAX_DIMENSION = 1024
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
        const val MAX_REDIRECTS = 3
        private const val TAG = "Hermesi"

        /** The largest power of two that leaves the longest side still at least [target], and never less than 1. */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}
