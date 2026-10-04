# Sample app

A one-screen Android app that registers itself with Hermesi and shows what arrives. It exists so that someone with an
emulator or a phone can see the whole path work, and so that the SDK's calls are visible in one short file
(`MainActivity.kt`, `SampleApp.kt`).

It uses the library from this repository. An app of your own depends on the JitPack coordinates in the main README.

**What this does not test for you**: nothing here has been run on a real device by the people who wrote it. The SDK's own
tests cover the logic, the network calls and what is drawn; this is how you check the part they cannot, with a real Firebase
project and a real notification.

## What you need

1. **Hermesi running**, with an environment, its **public key** (`hm_pk_...`) and its **secret key** (`hm_sk_...`).
2. **A Firebase Cloud Messaging provider** configured in Hermesi (Providers screen, push channel): the service account of
   your Firebase project. See `docs/provider-setup.md` in Hermesi.
3. **A Firebase Android app** for this sample, with package name `io.github.hermesihq.push.sample`. Download its
   `google-services.json` into this directory (`sample/google-services.json`). It is yours and is ignored by Git.
4. **An emulator with Google Play** (a "Google Play" system image) or a phone. Push does not work on an emulator image
   without Google Play services.
5. **Node 18 or later**, for the token server.

## Run it

**1. Start the token server.** A subscriber token proves to Hermesi which person a device belongs to and is signed with your
secret key, so it must come from a backend. This stands in for yours:

```bash
cd sample/token-server
HERMESI_SECRET_KEY=hm_sk_... HERMESI_ENVIRONMENT_ID=env_... node server.mjs
```

It mints a token for **anyone who asks**, so it listens on `127.0.0.1` only. Never expose it.

**2. Run the app** from Android Studio (open this directory, run the `sample` configuration), or:

```bash
./gradlew :sample:installDebug
```

**3. Fill in the screen**:

| Field | Emulator | Phone on the same network |
| --- | --- | --- |
| Hermesi client API | `http://10.0.2.2:8010/v1/client` | `http://<your machine>:8010/v1/client` |
| Public key | `hm_pk_...` | same |
| Token server | `http://10.0.2.2:8787` | `http://<your machine>:8787`, and start the server with `HOST=0.0.0.0` on a network you trust |
| Subscriber | any external id, for example `user_1` | same |

(`10.0.2.2` is how the Android emulator reaches the machine it runs on.)

**4. Tap the buttons in order**: *Allow notifications* (Android 13+), then *Register this device*. The screen prints the
start of the Firebase token, or Hermesi's error code if it refused. You should now see the device in Hermesi under the
subscriber, with `platform: android` and `transport: fcm`.

**5. Send a notification.** In Hermesi, make a template with a **Push** tab (a title and a body; for the link, set
`action_url` to `sample://orders/4821` or any `https` URL) and a workflow with a push step, then trigger it for your subscriber:

```bash
curl -X POST http://localhost:8010/v1/events \
  -H "Authorization: Bearer hm_sk_..." -H "Content-Type: application/json" \
  -d '{"name": "your.event", "recipient": "user_1"}'
```

## What to look for

- **App in the background**: Firebase draws the notification. Tap it: the app opens and the screen prints the link.
- **App open**: Firebase draws nothing, and the SDK draws one. Tap it: same.
- **A link the app did not allow** (for example `file://...` or `javascript:...`) is dropped, not opened. The sample allows
  `http`, `https` and `sample`.
- **Unregister**, then send again: nothing arrives. **Register** again: it does.
- **Token rotation**: clearing the app's data, or reinstalling, gives the device a new Firebase token. Register again and
  look at the subscriber in Hermesi: one device, not two stale ones.

If something fails, the screen shows the error code. `invalid_device_registration` and `unknown_device_transport` mean Hermesi
refused what the app sent; an `IOException` means a URL above is wrong or something is not running.
