package io.github.hermesihq.push.sample

import android.app.Application
import android.content.Context
import io.github.hermesihq.push.HermesiClient
import io.github.hermesihq.push.HermesiClientOptions
import io.github.hermesihq.push.HermesiPush
import io.github.hermesihq.push.HermesiPushOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the person types into the sample, kept between launches. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("sample", Context.MODE_PRIVATE)

    // 10.0.2.2 is how the Android emulator reaches the machine it runs on. A physical phone needs your machine's address.
    var apiBaseUrl: String
        get() = prefs.getString("apiBaseUrl", "http://10.0.2.2:8010/v1/client").orEmpty()
        set(value) = prefs.edit().putString("apiBaseUrl", value.trim()).apply()

    var publicKey: String
        get() = prefs.getString("publicKey", "").orEmpty()
        set(value) = prefs.edit().putString("publicKey", value.trim()).apply()

    var tokenServerUrl: String
        get() = prefs.getString("tokenServerUrl", "http://10.0.2.2:8787").orEmpty()
        set(value) = prefs.edit().putString("tokenServerUrl", value.trim()).apply()

    var subscriber: String
        get() = prefs.getString("subscriber", "user_1").orEmpty()
        set(value) = prefs.edit().putString("subscriber", value.trim()).apply()
}

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        configureHermesi(this)
    }

    companion object {
        /**
         * Builds the client from the saved settings and hands it to the SDK. Called from `Application.onCreate`, which is
         * the one place the SDK needs to be configured: Firebase can start this process just to deliver a message.
         * The sample calls it again when the settings change; an app of your own configures once.
         */
        fun configureHermesi(context: Context) {
            val settings = Settings(context)
            val client = HermesiClient(
                HermesiClientOptions(
                    publicKey = settings.publicKey,
                    apiBaseUrl = settings.apiBaseUrl,
                    // In a real app this asks YOUR backend for a token for the signed-in person. Here it asks the
                    // development token server in sample/token-server, which will mint one for anybody.
                    getSubscriberToken = { fetchSubscriberToken(settings.tokenServerUrl, settings.subscriber) },
                ),
            )
            HermesiPush.configure(
                context,
                client,
                HermesiPushOptions(
                    smallIcon = R.drawable.ic_notification,
                    // The sample's own deep link: a notification whose link is sample://orders/4821 opens MainActivity.
                    deepLinkSchemes = setOf("sample"),
                ),
            )
        }

        private suspend fun fetchSubscriberToken(serverUrl: String, subscriber: String): String =
            withContext(Dispatchers.IO) {
                val url = URL("${serverUrl.trimEnd('/')}/token?subscriber=${URLEncoder.encode(subscriber, "UTF-8")}")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    check(connection.responseCode == 200) { "The token server answered ${connection.responseCode}." }
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("token")
                } finally {
                    connection.disconnect()
                }
            }
    }
}
