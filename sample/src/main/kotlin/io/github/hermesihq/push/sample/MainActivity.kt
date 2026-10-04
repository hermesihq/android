package io.github.hermesihq.push.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.hermesihq.push.HermesiApiError
import io.github.hermesihq.push.HermesiPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One screen: the settings, and the four things an app does with the SDK. Built in code so that there is no layout file
 * between you and the calls.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private val fields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        column.addView(label("Hermesi Push sample", large = true))
        column.addView(label("The settings are saved on this device. Start the token server first (see the README)."))
        addField(column, "Hermesi client API", settings.apiBaseUrl, "apiBaseUrl")
        addField(column, "Public key (hm_pk_...)", settings.publicKey, "publicKey")
        addField(column, "Token server", settings.tokenServerUrl, "tokenServerUrl")
        addField(column, "Subscriber (external id)", settings.subscriber, "subscriber")
        column.addView(button("Save settings") { saveSettings(); log("Settings saved.") })
        column.addView(button("1. Allow notifications") { askForNotificationPermission() })
        column.addView(button("2. Register this device") { register() })
        column.addView(button("3. Unregister this device") { unregister() })
        status = label("")
        column.addView(status)
        setContentView(ScrollView(this).apply { addView(column) })

        log("Registered with Hermesi: ${HermesiPush.isRegistered()}. Notifications allowed: ${HermesiPush.notificationsAllowed(this)}.")
        showLink(intent)
    }

    // A tap on a notification that Firebase drew (the app was in the background) reaches the app as an intent, and the
    // link is in it. A tap on one the SDK drew (the app was open) arrives the same way.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showLink(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showLink(intent: Intent?) {
        intent?.data?.let { log("Opened by a link: $it") }
        HermesiPush.actionUrl(intent)?.let { log("The notification's link: $it") }
    }

    private fun saveSettings() {
        settings.apiBaseUrl = fields.getValue("apiBaseUrl").text.toString()
        settings.publicKey = fields.getValue("publicKey").text.toString()
        settings.tokenServerUrl = fields.getValue("tokenServerUrl").text.toString()
        settings.subscriber = fields.getValue("subscriber").text.toString()
        // The sample reconfigures the SDK with the new values. An app of your own configures it once, in Application.onCreate.
        SampleApp.configureHermesi(this)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !HermesiPush.notificationsAllowed(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            log("Notifications are already allowed.")
        }
    }

    @Deprecated("The sample keeps to the platform Activity to stay free of AndroidX.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log("Notifications allowed: ${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}.")
    }

    private fun register() {
        saveSettings()
        scope.launch {
            try {
                // `plan` is metadata of your own, stored with the device. platform and transport are set by the SDK.
                val token = HermesiPush.register(mapOf("plan" to "sample"))
                log("Registered. Firebase token: ${token.take(24)}...")
            } catch (e: HermesiApiError) {
                log("Hermesi refused: ${e.code} (HTTP ${e.status}): ${e.message}")
            } catch (e: IOException) {
                log("Could not reach Hermesi or the token server: ${e.message}")
            } catch (e: Exception) {
                // Firebase is not set up (no google-services.json), or the token server said no.
                log("Failed: ${e.message}")
            }
        }
    }

    private fun unregister() {
        scope.launch {
            try {
                HermesiPush.unregister()
                log("Unregistered.")
            } catch (e: HermesiApiError) {
                log("Hermesi refused: ${e.code} (HTTP ${e.status}): ${e.message}")
            } catch (e: Exception) {
                log("Failed: ${e.message}")
            }
        }
    }

    private fun log(line: String) {
        status.text = (status.text.toString() + "\n" + line).trim()
    }

    private fun label(text: String, large: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (large) 22f else 14f
        setPadding(0, 16, 0, 8)
    }

    private fun addField(parent: LinearLayout, hint: String, value: String, key: String) {
        parent.addView(label(hint))
        val field = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        fields[key] = field
        parent.addView(field)
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener(onClick)
    }
}
