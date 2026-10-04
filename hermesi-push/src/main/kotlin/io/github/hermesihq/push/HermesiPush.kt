package io.github.hermesihq.push

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * How an app wants Hermesi's notifications to behave.
 *
 * @property defaultChannelId The notification channel used when a message names none. Created on first use.
 * @property defaultChannelName What the person sees for that channel in the system settings.
 * @property smallIcon A drawable for the status bar. Without one the app icon is used, which on recent Android
 * versions often renders as a grey square, so setting one is worth doing.
 * @property deepLinkSchemes Schemes of your own deep links (for example `myapp`) that a notification's link may
 * open. `http` and `https` are always allowed; nothing else is.
 * @property showInForeground Whether to draw a notification when one arrives while the app is open. Firebase draws
 * none itself in that case.
 * @property showImages Whether a notification drawn while the app is open shows its picture, which means downloading it
 * (`https` only, at most 5 MB, a few seconds). Firebase shows the picture of a notification it draws itself, whatever
 * this says.
 */
public class HermesiPushOptions(
    public val defaultChannelId: String = "hermesi_default",
    public val defaultChannelName: String = "Notifications",
    public val smallIcon: Int? = null,
    public val deepLinkSchemes: Set<String> = emptySet(),
    public val showInForeground: Boolean = true,
    public val showImages: Boolean = true,
)

/**
 * Registers this device for push notifications sent by Hermesi.
 *
 * Call [configure] once from `Application.onCreate`, then [register] when a subscriber signs in and on each app
 * start after that, and [unregister] when they sign out. Add [HermesiMessagingService] to the manifest so that a
 * rotated token and a notification that arrives while the app is open are handled.
 */
public object HermesiPush {
    private const val TAG = "Hermesi"
    private const val PREFS = "io.github.hermesihq.push"
    private const val KEY_TOKEN = "token"
    private const val KEY_EXTRA = "metadata"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var state: State? = null

    private class State(
        val context: Context,
        val options: HermesiPushOptions,
        val registrar: DeviceRegistrar,
        val store: SharedPreferencesTokenStore,
    )

    /**
     * Sets the SDK up. Call it from `Application.onCreate`, not from an activity: Firebase can start the app's
     * process just to deliver a message, and nothing else has run by then.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, client: HermesiClient, options: HermesiPushOptions = HermesiPushOptions()) {
        configure(context, client, options, FirebaseTokenSource())
    }

    internal fun configure(context: Context, api: DeviceApi, options: HermesiPushOptions, source: TokenSource) {
        val app = context.applicationContext
        val store = SharedPreferencesTokenStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KEY_TOKEN)
        val registrar = DeviceRegistrar(
            api = api,
            source = source,
            store = store,
            metadata = { metadataFor(app) },
            onWarning = { message, cause -> Log.w(TAG, message, cause) },
        )
        state = State(app, options, registrar, store)
    }

    /**
     * Registers this device and returns its token. Call it from a coroutine after the subscriber has signed in,
     * and again on every app start: it is safe to repeat, and it is what puts a device back after Hermesi had
     * marked it invalid. [metadata] is stored with the device (`{ "plan": "pro" }`); `platform` and `transport`
     * are always set by the SDK.
     *
     * Throws [HermesiApiError] if Hermesi refuses and [java.io.IOException] if it cannot be reached.
     */
    public suspend fun register(metadata: Map<String, Any?> = emptyMap()): String {
        val current = requireConfigured()
        // Kept, because a token refresh later runs with nobody calling this and has to send the same metadata.
        current.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXTRA, JSONObject(metadata.mapValues { it.value ?: JSONObject.NULL }).toString())
            .apply()
        return current.registrar.register()
    }

    /** Removes this device from Hermesi and discards its Firebase token. Call it on sign-out. */
    public suspend fun unregister() {
        requireConfigured().registrar.unregister()
    }

    /** True if [register] has succeeded and [unregister] has not been called since. */
    public fun isRegistered(): Boolean = state?.registrar?.isRegistered() ?: false

    /**
     * Whether a notification can be shown. Always true before Android 13; from Android 13 it needs the
     * `POST_NOTIFICATIONS` permission, which only an activity can ask for. Hermesi registers the device either way.
     */
    @JvmStatic
    public fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * The link of the notification that opened the app, from the intent it was opened with, or null. Firebase
     * delivers a background notification's data as intent extras, so this is where an activity finds out what a
     * tap was for. A link in a scheme the app did not allow is dropped.
     */
    @JvmStatic
    public fun actionUrl(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        val data = extras.keySet().mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap()
        return PushContent(null, null, data).actionUrl(state?.options?.deepLinkSchemes.orEmpty())
    }

    // --- Called by HermesiMessagingService ---

    internal fun handleNewToken(token: String): kotlinx.coroutines.Job? {
        val current = state ?: run {
            Log.w(TAG, "A new push token arrived before HermesiPush.configure() ran; call it from Application.onCreate.")
            return null
        }
        return scope.launch {
            try {
                // Ignored when the device was never registered: there is no subscriber to register it for yet.
                current.registrar.refresh(token)
            } catch (e: Exception) {
                // Not worth failing a service over: the next register() puts the device right.
                Log.w(TAG, "Could not register the refreshed push token; it will be registered on the next start.", e)
            }
        }
    }

    internal fun handleMessage(message: RemoteMessage): Boolean {
        val notification = message.notification ?: return false
        return showInForeground(
            PushContent(notification.title, notification.body, message.data),
            notification.channelId,
            notification.imageUrl?.toString(),
        )
    }

    internal fun showInForeground(content: PushContent, channelId: String?, imageUrl: String? = null): Boolean {
        val current = state ?: return false
        if (!current.options.showInForeground) return false
        HermesiNotifications.show(current.context, current.options, content, channelId, imageUrl)
        return true
    }

    /** Test seam: forget the configuration. */
    internal fun reset() {
        state = null
    }

    private fun requireConfigured(): State =
        state ?: error("HermesiPush.configure() has not been called. Call it from Application.onCreate.")

    private fun metadataFor(context: Context): Map<String, Any?> {
        val extra = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXTRA, null)
            ?.let { runCatching { JSONObject(it).toMap() }.getOrNull() }
            .orEmpty()
        val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return DeviceMetadata.build(osVersion = Build.VERSION.RELEASE.orEmpty(), appVersion = appVersion, extra = extra)
    }

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key -> opt(key).takeUnless { it === JSONObject.NULL } }
}
