# Hermesi Push for Android

Register an Android device for push notifications sent by [Hermesi](https://github.com/hermesihq),
and show them. It is a small library over Firebase Cloud Messaging: it keeps the device's token
registered with Hermesi as it changes, and draws a notification when one arrives while your app is open.

- Registers and unregisters the device, and keeps it registered when Firebase rotates the token.
- Shows notifications that arrive in the foreground, with the right channel and a safe tap target.
- Shows a notification's picture, downloaded within strict limits.
- No HTTP library, no JSON library, and no Firebase version of its own: it uses the one your app has.

Android 6.0 (API 23) and later. Kotlin, with `suspend` functions.

## Before you start

1. In Hermesi, configure a **Firebase Cloud Messaging** provider (Providers screen).
2. Your app already uses Firebase Messaging (`google-services.json`, `firebase-messaging`).
3. Your backend can mint a **subscriber token** for the signed-in person, as it does for the web SDK. The SDK
   never has your secret key; it asks your backend for the token each time it talks to Hermesi.

## Install

Through [JitPack](https://jitpack.io), which builds a release from its Git tag:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.hermesihq:android:0.1.0")
}
```

It declares Firebase Messaging as `compileOnly`, so your app must have it:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
implementation("com.google.firebase:firebase-messaging")
```

## Set up

**1. Configure it once, in `Application.onCreate`.** Not in an activity: Firebase can start your process just to
deliver a message, and nothing else has run by then.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val client = HermesiClient(
            HermesiClientOptions(
                publicKey = "hm_pk_...",                        // safe to ship in an app
                apiBaseUrl = "https://your-hermesi-host/v1/client",
                getSubscriberToken = { backend.mintHermesiToken() }, // calls YOUR backend
            ),
        )
        HermesiPush.configure(
            this,
            client,
            HermesiPushOptions(
                smallIcon = R.drawable.ic_notification,
                deepLinkSchemes = setOf("myapp"),               // see "Links" below
            ),
        )
    }
}
```

**2. Add the service to your manifest**, in place of your own `FirebaseMessagingService` (or extend it):

```xml
<service android:name="io.github.hermesihq.push.HermesiMessagingService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

**3. Register when the person signs in, and on every app start after that.** It is safe to repeat, and it is what
puts a device back after Hermesi had marked it invalid.

```kotlin
lifecycleScope.launch {
    try {
        HermesiPush.register(mapOf("plan" to "pro"))   // optional metadata, stored with the device
    } catch (e: HermesiApiError) {
        // Hermesi refused: e.code says why
    } catch (e: IOException) {
        // could not reach Hermesi: try again later
    }
}
```

**4. Unregister when they sign out:**

```kotlin
HermesiPush.unregister()
```

## Notification permission (Android 13+)

From Android 13 a notification needs the `POST_NOTIFICATIONS` permission, and only an activity can ask for it, at a
moment you choose. Hermesi registers the device either way, so `register()` does not wait for it.
`HermesiPush.notificationsAllowed(context)` tells you whether it has been granted.

## Foreground and background

Firebase draws a notification itself when your app is in the background, and draws nothing when it is open.
`HermesiMessagingService` draws one in that second case. Set `showInForeground = false` in `HermesiPushOptions`, or
override `onMessageReceived` without calling `super`, to handle it yourself.

## Links

A notification can carry a link. It reaches your app in the data under `action_url`. When the person taps a
notification you drew, it opens that link; when they tap one Firebase drew, your launcher activity opens and the
link is in the intent, where `HermesiPush.actionUrl(intent)` finds it.

The link comes from a template, not from your app, so only some schemes are opened: `http` and `https`, and the
deep link schemes you list in `deepLinkSchemes`. Anything else (`file`, `content`, `intent`, `javascript`) is dropped.
A deep link of your own is opened by your app only, so another app claiming the same scheme cannot receive it.

## Channels

Android 8+ shows every notification through a channel. A message can name one (`android.channel_id` in the
template); if your app has no such channel, the notification is drawn on a default channel the SDK creates
(`defaultChannelId` and `defaultChannelName` in the options) rather than being dropped by the system.

## What it sends to Hermesi

The device is stored with `platform: android`, `transport: fcm`, `os_version`, `app_version`, `sdk`, and whatever
metadata you passed. `platform` and `transport` are always set by the SDK: the transport is how Hermesi chooses the
provider that can reach the device, and an app that overrode it would send its devices to one that cannot.

## Errors

- `HermesiApiError`: Hermesi refused the request. Branch on `code`; `isRetryable` is true for a server error or
  rate limiting.
- `IOException`: Hermesi could not be reached.
- Both are thrown from `register()` and `unregister()`. A token refresh in the background handles its own failures:
  if it cannot register the new token it logs and leaves it to the next `register()`.

## Pictures

A notification's picture (the template's image URL) is shown in both cases:

- **App in the background**: Firebase downloads and shows it itself.
- **App open**: the SDK downloads it and draws the notification with a large picture (`BigPictureStyle`). The picture
  comes from a template, not from your app, and is fetched on the person's phone, so the download is strict: **`https` only**
  (a redirect is followed only to another `https` address, at most three times), **at most 5 MB** (refused from `Content-Length`
  before it is read, and cut off while it arrives if the server did not say), decoded at a size a notification can use rather
  than at full resolution, and abandoned after a few seconds. The download runs on the thread Firebase delivers the message on.
  If it fails for any reason the notification is still shown, as text. Set `showImages = false` in `HermesiPushOptions` to
  never download a picture.

## What it does not do

It does not draw an inbox, and it does not cover iOS (see [hermesihq/ios](https://github.com/hermesihq/ios)). Android only,
push only.

## Building

The tests run on the JVM under Robolectric, with no emulator:

```
./gradlew :hermesi-push:testDebugUnitTest
```

They cover the HTTP calls, the token rotation rules, what is drawn and what a tap opens. They do not cover a real
device or a real Firebase project; that needs a person with an app.

## License

MIT
