package io.github.hermesihq.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Add this to your manifest in place of your own `FirebaseMessagingService`, or extend it if you have one:
 *
 * ```xml
 * <service android:name="io.github.hermesihq.push.HermesiMessagingService" android:exported="false">
 *     <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
 * </service>
 * ```
 *
 * It does two things. When Firebase replaces the device's token, it registers the new one with Hermesi (only if
 * this device had been registered). And when a notification arrives while the app is open, where Firebase draws
 * nothing, it shows one. Override [onMessageReceived] and not call `super` to take over the second.
 */
public open class HermesiMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        HermesiPush.handleNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!HermesiPush.handleMessage(message)) super.onMessageReceived(message)
    }
}
