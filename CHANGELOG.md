# Changelog

All notable changes to Hermesi Push for Android. This file describes what a consumer gets.

**`0.x` means the public API can still change.** A minor bump may contain a breaking change; a patch bump will
not. Each release lists breaking changes first.

## 0.1.0 (2026-10-04)

First release.

- `HermesiPush.configure`, `register`, `unregister`, `isRegistered`, `notificationsAllowed`, `actionUrl`.
- `HermesiClient`: registers and removes a push device against Hermesi's client API.
- `HermesiMessagingService`: re-registers a rotated Firebase token, and shows notifications that arrive while the
  app is open.
- A tapped notification opens only `http`, `https` and the app's own listed deep link schemes.
