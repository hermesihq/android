package io.github.hermesihq.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A notification drawn while the app is open, with a picture: what is drawn, and what is left alone when it cannot be. */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the real decoder, so that bytes which are not a picture are refused as they are on a device. The legacy
// shadow invents a bitmap for anything.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HermesiPushImageTest {

    private class FakeApi : DeviceApi {
        override suspend fun registerDevice(token: String, metadata: Map<String, Any?>) = Unit
        override suspend fun unregisterDevice(token: String) = Unit
    }

    private class FakeSource : TokenSource {
        override suspend fun currentToken(): String = "t1"
        override suspend fun deleteToken() = Unit
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val manager: NotificationManager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val content = PushContent("Shipped", "Tomorrow", mapOf("order" to "4821"))

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        HermesiPush.reset()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        HermesiNotifications.imageFetcher = ImageFetcher(timeoutMillis = 2_000, requireHttps = false)
        configure(HermesiPushOptions())
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
        HermesiNotifications.imageFetcher = ImageFetcher()
        HermesiPush.reset()
    }

    private fun configure(options: HermesiPushOptions) = HermesiPush.configure(app, FakeApi(), options, FakeSource())

    private fun picture(): MockResponse {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
    }

    private fun shown() = shadowOf(manager).allNotifications.single()

    private fun template(notification: Notification) = notification.extras.getString(Notification.EXTRA_TEMPLATE)

    private val bigPicture = "android.app.Notification\$BigPictureStyle"
    private val bigText = "android.app.Notification\$BigTextStyle"

    @Test
    fun drawsThePictureWhenTheMessageHasOne() {
        server.enqueue(picture())

        HermesiPush.showInForeground(content, channelId = null, imageUrl = server.url("/p.png").toString())

        val notification = shown()
        assertEquals(bigPicture, template(notification))
        assertNotNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertEquals("Shipped", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Tomorrow", notification.extras.getString(Notification.EXTRA_SUMMARY_TEXT))
    }

    @Test
    fun drawsTheNotificationAsTextWhenThePictureCannotBeFetched() {
        server.enqueue(MockResponse().setResponseCode(404))

        HermesiPush.showInForeground(content, channelId = null, imageUrl = server.url("/missing.png").toString())

        val notification = shown()
        assertEquals("a picture that fails must not cost the notification", bigText, template(notification))
        assertEquals("Shipped", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun drawsTheNotificationAsTextWhenTheUrlIsNotHttps() {
        // The fetcher the app really uses refuses it before any request is made.
        HermesiNotifications.imageFetcher = ImageFetcher()

        HermesiPush.showInForeground(content, channelId = null, imageUrl = "http://cdn.example.test/p.png")

        assertEquals(bigText, template(shown()))
    }

    @Test
    fun fetchesNothingWhenTheMessageHasNoPicture() {
        HermesiPush.showInForeground(content, channelId = null, imageUrl = null)
        HermesiPush.showInForeground(content, channelId = null, imageUrl = "   ")

        assertEquals(0, server.requestCount)
        assertTrue(shadowOf(manager).allNotifications.all { template(it) == bigText })
    }

    @Test
    fun fetchesNothingWhenTheAppSwitchedImagesOff() {
        configure(HermesiPushOptions(showImages = false))
        server.enqueue(picture())

        HermesiPush.showInForeground(content, channelId = null, imageUrl = server.url("/p.png").toString())

        assertEquals(0, server.requestCount)
        assertEquals(bigText, template(shown()))
        assertNull(shown().extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
    }

    @Test
    fun fetchesNothingWithoutTheNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(picture())

        HermesiPush.showInForeground(content, channelId = null, imageUrl = server.url("/p.png").toString())

        assertEquals("no point downloading a picture for a notification that will be dropped", 0, server.requestCount)
    }

    // --- the message Firebase hands to the service ---

    /**
     * A notification message as Firebase delivers it. `RemoteMessage` has no public builder for the notification block, so
     * this builds the bundle Firebase's own parsing reads, which is what the library depends on.
     */
    private fun firebaseMessage(title: String?, body: String?, image: String?, channel: String?, data: Map<String, String> = emptyMap()): RemoteMessage {
        val bundle = Bundle().apply {
            putString("from", "1234567890")
            putString("google.message_id", "0:1")
            title?.let { putString("gcm.n.title", it) }
            body?.let { putString("gcm.n.body", it) }
            image?.let { putString("gcm.n.image", it) }
            channel?.let { putString("gcm.n.android_channel_id", it) }
            putString("gcm.n.e", "1")
            data.forEach { (k, v) -> putString(k, v) }
        }
        return RemoteMessage(bundle)
    }

    @Test
    fun drawsTheNotificationAndItsPictureFromAFirebaseMessage() {
        server.enqueue(picture())
        val message = firebaseMessage("Shipped", "Tomorrow", server.url("/p.png").toString(), channel = null, data = mapOf("order" to "4821"))

        assertTrue(HermesiPush.handleMessage(message))

        val notification = shown()
        assertEquals("Shipped", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("the image URL must travel from the Firebase message to the fetcher", bigPicture, template(notification))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFirebaseMessageWithNoPictureIsDrawnAsText() {
        val message = firebaseMessage("Shipped", "Tomorrow", image = null, channel = null)

        assertTrue(HermesiPush.handleMessage(message))

        assertEquals(bigText, template(shown()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMessageWithNoNotificationBlockIsLeftToTheApp() {
        val dataOnly = RemoteMessage(Bundle().apply { putString("from", "1"); putString("order", "1") })

        assertFalse(HermesiPush.handleMessage(dataOnly))
    }
}

