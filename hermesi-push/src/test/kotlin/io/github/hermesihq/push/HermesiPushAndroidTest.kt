package io.github.hermesihq.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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

/**
 * The Android half, under Robolectric: what gets drawn, what a tap opens, and what a refreshed token does. The
 * Firebase calls are the one thing replaced (there is no Firebase app here), through the same `TokenSource` the
 * pure tests use. What none of this can show is a real device and a real Firebase project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesiPushAndroidTest {

    private class FakeApi : DeviceApi {
        val calls = mutableListOf<String>()
        val metadata = mutableListOf<Map<String, Any?>>()
        override suspend fun registerDevice(token: String, metadata: Map<String, Any?>) {
            calls += "register:$token"
            this.metadata += metadata
        }

        override suspend fun unregisterDevice(token: String) {
            calls += "unregister:$token"
        }
    }

    private class FakeSource(var token: String = "t1") : TokenSource {
        var deleted = 0
        override suspend fun currentToken(): String = token
        override suspend fun deleteToken() {
            deleted++
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val api = FakeApi()
    private val source = FakeSource()
    private val manager: NotificationManager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun configure() {
        HermesiPush.reset()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        HermesiPush.configure(app, api, HermesiPushOptions(deepLinkSchemes = setOf("myapp"), smallIcon = android.R.drawable.ic_dialog_info), source)
    }

    @After
    fun forget() {
        HermesiPush.reset()
    }

    private fun content(url: String? = null, title: String? = "Shipped", body: String? = "Tomorrow") =
        PushContent(title, body, buildMap { if (url != null) put(PushContent.ACTION_URL_KEY, url); put("order", "4821") })

    private fun launcherActivity() {
        val component = ComponentName(app.packageName, "com.example.MainActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun linkActivity(scheme: String) {
        val component = ComponentName(app.packageName, "com.example.LinkActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            },
        )
    }

    private fun shown() = shadowOf(manager).allNotifications

    // --- permission ---

    @Test
    fun notificationsNeedThePermissionFromAndroid13() {
        assertTrue(HermesiPush.notificationsAllowed(app))

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(HermesiPush.notificationsAllowed(app))
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13NothingIsNeeded() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(HermesiPush.notificationsAllowed(app))
    }

    // --- drawing a notification ---

    @Test
    fun drawsTheNotificationOnADefaultChannelItCreates() {
        assertTrue(HermesiPush.showInForeground(content(), channelId = null))

        val notification = shown().single()
        assertEquals("Shipped", notification.extras.getString("android.title"))
        assertEquals("Tomorrow", notification.extras.getString("android.text"))
        assertEquals("hermesi_default", notification.channelId)
        assertNotNull(manager.getNotificationChannel("hermesi_default"))
    }

    @Test
    fun usesTheChannelAMessageNamesWhenTheAppHasIt() {
        manager.createNotificationChannel(android.app.NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_HIGH))

        HermesiPush.showInForeground(content(), channelId = "orders")

        assertEquals("orders", shown().single().channelId)
    }

    @Test
    fun fallsBackToTheDefaultChannelWhenTheMessageNamesOneTheAppNeverCreated() {
        // A notification posted to a channel that does not exist is dropped by the system.
        HermesiPush.showInForeground(content(), channelId = "no_such_channel")

        assertEquals("hermesi_default", shown().single().channelId)
    }

    @Test
    fun drawsNothingWithoutThePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        HermesiPush.showInForeground(content(), channelId = null)

        assertTrue(shown().isEmpty())
    }

    @Test
    fun drawsNothingWhenTheAppSwitchedForegroundDisplayOff() {
        HermesiPush.configure(app, api, HermesiPushOptions(showInForeground = false), source)

        assertFalse(HermesiPush.showInForeground(content(), channelId = null))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun drawsNothingBeforeConfigure() {
        HermesiPush.reset()

        assertFalse(HermesiPush.showInForeground(content(), channelId = null))
    }

    // --- what a tap opens ---

    @Test
    fun aTapOpensTheAppsOwnDeepLinkInTheAppOnly() {
        launcherActivity()
        linkActivity("myapp")

        HermesiPush.showInForeground(content(url = "myapp://orders/4821"), channelId = null)

        val intent = shadowOf(shown().single().contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("myapp://orders/4821", intent.dataString)
        // Another app that claims the same scheme must not receive it.
        assertEquals(app.packageName, intent.`package`)
        assertEquals("the data rides along as extras", "4821", intent.getStringExtra("order"))
    }

    @Test
    fun aTapOpensAWebLinkWithoutRestrictingItToTheApp() {
        launcherActivity()
        linkActivity("https")

        HermesiPush.showInForeground(content(url = "https://app.example.test/orders/4821"), channelId = null)

        val intent = shadowOf(shown().single().contentIntent).savedIntent
        assertEquals("https://app.example.test/orders/4821", intent.dataString)
        assertNull(intent.`package`)
    }

    @Test
    fun aLinkInASchemeTheAppDidNotAllowOpensTheLauncherInstead() {
        launcherActivity()
        linkActivity("file")

        HermesiPush.showInForeground(content(url = "file:///data/data/app/secret"), channelId = null)

        val intent = shadowOf(shown().single().contentIntent).savedIntent
        assertNull("the link must not be opened", intent.data)
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test
    fun aLinkNothingCanHandleOpensTheLauncherInstead() {
        launcherActivity()

        HermesiPush.showInForeground(content(url = "myapp://orders/4821"), channelId = null)

        assertEquals(Intent.ACTION_MAIN, shadowOf(shown().single().contentIntent).savedIntent.action)
    }

    // --- the link of a background notification ---

    @Test
    fun readsTheLinkOutOfTheIntentABackgroundTapOpenedTheAppWith() {
        val intent = Intent().putExtra(PushContent.ACTION_URL_KEY, "myapp://orders/1").putExtra("order", "1")

        assertEquals("myapp://orders/1", HermesiPush.actionUrl(intent))
    }

    @Test
    fun dropsALinkInASchemeTheAppDidNotAllow() {
        assertNull(HermesiPush.actionUrl(Intent().putExtra(PushContent.ACTION_URL_KEY, "intent://x#Intent;end")))
        assertNull(HermesiPush.actionUrl(Intent()))
        assertNull(HermesiPush.actionUrl(null))
    }

    // --- registering, and a refreshed token ---

    @Test
    fun registersWithTheDeviceMetadataAndTheAppsOwn() = runBlocking {
        val token = HermesiPush.register(mapOf("plan" to "pro", "transport" to "apns"))

        assertEquals("t1", token)
        val metadata = api.metadata.single()
        assertEquals("android", metadata["platform"])
        assertEquals("fcm", metadata["transport"])
        assertEquals("pro", metadata["plan"])
        assertTrue(HermesiPush.isRegistered())
    }

    @Test
    fun aRefreshedTokenIsRegisteredWithTheMetadataTheDeviceRegisteredWith() = runBlocking {
        HermesiPush.register(mapOf("plan" to "pro"))
        api.calls.clear()
        api.metadata.clear()

        // What the service does when Firebase rotates the token, possibly in a fresh process where nobody has
        // called register() since: the app's metadata has to come from storage, not from memory.
        HermesiPush.handleNewToken("t2")!!.join()

        assertEquals(listOf("register:t2", "unregister:t1"), api.calls)
        assertEquals("pro", api.metadata.single()["plan"])
    }

    @Test
    fun aRefreshedTokenIsIgnoredUntilTheDeviceHasBeenRegistered() = runBlocking {
        HermesiPush.handleNewToken("t2")!!.join()

        assertTrue(api.calls.isEmpty())
        assertFalse(HermesiPush.isRegistered())
    }

    @Test
    fun aRefreshedTokenBeforeConfigureIsIgnoredNotACrash() {
        HermesiPush.reset()

        assertNull(HermesiPush.handleNewToken("t2"))
    }

    @Test
    fun unregisteringRemovesTheDeviceAndDiscardsTheFirebaseToken() = runBlocking {
        HermesiPush.register()

        HermesiPush.unregister()

        assertEquals(listOf("register:t1", "unregister:t1"), api.calls)
        assertEquals(1, source.deleted)
        assertFalse(HermesiPush.isRegistered())
    }

    @Test
    fun saysWhatToDoWhenUsedBeforeConfigure() {
        HermesiPush.reset()

        val error = runCatching { runBlocking { HermesiPush.register() } }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Application.onCreate"))
    }
}
