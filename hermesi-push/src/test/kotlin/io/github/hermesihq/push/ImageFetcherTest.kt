package io.github.hermesihq.push

import android.graphics.Bitmap
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * The picture download, against a real HTTP server. The fetcher is strict on purpose, because the URL comes from a template
 * and is fetched on the person's phone: these are the ways it is not allowed to cost the app anything.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the real decoder, so that bytes which are not a picture are refused as they are on a device. The legacy
// shadow invents a bitmap for anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageFetcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    private fun png(width: Int = 40, height: Int = 20): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun noisyPng(width: Int, height: Int): ByteArray {
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, random.nextInt() or (0xFF shl 24))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun body(bytes: ByteArray) = MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))

    /** Plain http is the only thing a local test server speaks, so these tests turn the https rule off; one test keeps it on. */
    private fun fetcher(maxBytes: Int = 1_000_000, maxDimension: Int = 1024) =
        ImageFetcher(maxBytes = maxBytes, maxDimension = maxDimension, timeoutMillis = 2_000, requireHttps = false)

    private fun url(path: String = "/p.png") = server.url(path).toString()

    @Test
    fun fetchesAndDecodesAPicture() {
        server.enqueue(body(png(40, 20)))

        val bitmap = fetcher().fetch(url())

        assertNotNull(bitmap)
        assertEquals(40, bitmap!!.width)
        assertEquals(20, bitmap.height)
    }

    @Test
    fun decodesALargePictureAtASizeANotificationCanUseNotAtFullResolution() {
        server.enqueue(body(png(3000, 2000)))

        val bitmap = fetcher(maxBytes = 50_000_000, maxDimension = 1024).fetch(url())

        // A 6000 pixel photograph is 144 MB as a bitmap; the sample size keeps the longest side at least 1024.
        assertEquals(1500, bitmap!!.width)
        assertEquals(1000, bitmap.height)
    }

    @Test
    fun picksTheLargestSampleSizeThatKeepsThePictureBigEnough() {
        assertEquals(1, ImageFetcher.sampleSize(1000, 500, 1024))
        assertEquals(1, ImageFetcher.sampleSize(2047, 100, 1024))
        assertEquals(2, ImageFetcher.sampleSize(2048, 100, 1024))
        assertEquals(4, ImageFetcher.sampleSize(4500, 3000, 1024))
        assertEquals(8, ImageFetcher.sampleSize(8192, 100, 1024))
    }

    @Test
    fun neverRequestsAnUrlThatIsNotHttps() {
        val strict = ImageFetcher(timeoutMillis = 2_000) // requireHttps stays on

        assertNull(strict.fetch(url())) // http://localhost
        assertNull(strict.fetch("file:///data/data/app/secret.png"))
        assertNull(strict.fetch("ftp://example.test/a.png"))
        assertNull(strict.fetch("javascript:alert(1)"))
        assertNull(strict.fetch("//example.test/a.png"))
        assertNull(strict.fetch("not a url"))
        assertNull(strict.fetch(""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusesAPictureThatAnnouncesItIsTooBigWithoutReadingIt() {
        server.enqueue(body(ByteArray(5_000) { 1 }))

        assertNull(fetcher(maxBytes = 1_000).fetch(url()))
    }

    @Test
    fun cutsOffAPictureThatTurnsOutToBeTooBig() {
        // Random pixels do not compress, so this is a real picture of several kilobytes. Chunked, so there is no
        // Content-Length to go by: it is counted as it arrives.
        val big = noisyPng(100, 100)
        assertTrue("the picture must be larger than the limit for this to mean anything", big.size > 2_000)
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(big), 256))
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(big), 256))

        assertNull(fetcher(maxBytes = 2_000).fetch(url()))
        // The same picture, chunked the same way, is fine under a larger limit: it is the size that decided.
        assertNotNull(fetcher(maxBytes = big.size).fetch(url()))
    }

    @Test
    fun acceptsAPictureExactlyAtTheLimitAndNotOneByteOver() {
        val bytes = png()
        server.enqueue(body(bytes))
        server.enqueue(body(bytes))

        assertNotNull(fetcher(maxBytes = bytes.size).fetch(url()))
        assertNull(fetcher(maxBytes = bytes.size - 1).fetch(url()))
    }

    @Test
    fun refusesWhatIsNotAPicture() {
        server.enqueue(body("<html>not a picture</html>".toByteArray()))
        server.enqueue(body(ByteArray(0)))

        assertNull(fetcher().fetch(url()))
        assertNull(fetcher().fetch(url()))
    }

    @Test
    fun refusesAnErrorResponse() {
        for (status in listOf(403, 404, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(Buffer().write(png())))
            assertNull("HTTP $status must not be drawn", fetcher().fetch(url()))
        }
    }

    @Test
    fun followsARedirectToAnAllowedAddress() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real.png"))
        server.enqueue(body(png(10, 10)))

        val bitmap = fetcher().fetch(url("/short"))

        assertNotNull(bitmap)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doesNotFollowARedirectToAnAddressThatIsNotAllowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "ftp://example.test/a.png"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///data/secret"))

        assertNull(fetcher().fetch(url()))
        assertNull(fetcher().fetch(url()))
    }

    @Test
    fun givesUpOnARedirectLoop() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }

        assertNull(fetcher().fetch(url()))
        assertEquals("the original request and three redirects, no more", 4, server.requestCount)
    }

    @Test
    fun returnsNullWhenTheServerCannotBeReached() {
        val dead = url()
        server.shutdown()

        assertNull(fetcher().fetch(dead))
    }
}
