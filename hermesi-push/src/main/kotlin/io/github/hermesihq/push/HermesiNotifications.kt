package io.github.hermesihq.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Draws a notification for a message that arrived while the app was open. Firebase draws the ones that arrive
 * in the background itself.
 *
 * A tap opens the notification's link if it has an allowed one that some activity can handle, and the app's
 * launcher activity otherwise. Every data entry rides along as an intent extra, which is also what Firebase does
 * for a background notification, so one code path reads both (see [HermesiPush.actionUrl]).
 */
internal object HermesiNotifications {

    fun show(context: Context, options: HermesiPushOptions, content: PushContent, channelId: String?) {
        // Without the permission the call is silently dropped by the system; skipping it is just honest.
        if (!HermesiPush.notificationsAllowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        val channel = channelId?.takeIf { it.isNotEmpty() && channelExists(context, it) } ?: ensureDefaultChannel(context, options)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(options.smallIcon ?: context.applicationInfo.icon)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        tapIntent(context, content, options)?.let { builder.setContentIntent(it) }

        @Suppress("MissingPermission") // checked above through notificationsAllowed
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun tapIntent(context: Context, content: PushContent, options: HermesiPushOptions): PendingIntent? {
        val link = content.actionUrl(options.deepLinkSchemes)
        val intent = link?.let { url ->
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).also { view ->
                // A link of the app's own is opened by the app and nothing else, so another app that claims the
                // same scheme cannot receive it.
                if (!url.startsWith("http", ignoreCase = true)) view.setPackage(context.packageName)
            }.takeIf { context.packageManager.resolveActivity(it, 0) != null }
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return null
        content.data.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun channelExists(context: Context, id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id) != null
    }

    private fun ensureDefaultChannel(context: Context, options: HermesiPushOptions): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(options.defaultChannelId) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(options.defaultChannelId, options.defaultChannelName, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
        return options.defaultChannelId
    }
}
