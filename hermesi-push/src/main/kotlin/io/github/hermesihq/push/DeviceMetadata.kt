package io.github.hermesihq.push

/**
 * The metadata stored with a device in Hermesi.
 *
 * `transport` is how Hermesi decides which provider carries a device, and for an Android device with a Firebase
 * token it is `fcm`. It is set after anything the app adds, because an app that overrode it would send its devices
 * to a provider that cannot reach them. The same goes for `platform`.
 */
internal object DeviceMetadata {
    fun build(osVersion: String, appVersion: String?, extra: Map<String, Any?>): Map<String, Any?> =
        buildMap {
            put("os_version", osVersion)
            if (appVersion != null) put("app_version", appVersion)
            put("sdk", "hermesi-android/${BuildConfig.VERSION}")
            putAll(extra)
            put("platform", "android")
            put("transport", "fcm")
        }
}
