# Changelog

All notable changes to Hermesi Push for Android. This file describes what a consumer gets.

**`0.x` means the public API can still change.** A minor bump may contain a breaking change; a patch bump will
not. Each release lists breaking changes first.

## Unreleased

- **Pictures.** A notification that arrives while the app is open now shows its picture (`BigPictureStyle`). The download is
  `https` only, at most 5 MB, decoded at a size a notification can use, and abandoned after a few seconds; any failure
  leaves the notification as text. `HermesiPushOptions.showImages` turns it off. Firebase already shows the picture of a
  notification it draws itself.

## 0.1.0 (2026-10-04)

First release.

- `HermesiPush.configure`, `register`, `unregister`, `isRegistered`, `notificationsAllowed`, `actionUrl`.
- `HermesiClient`: registers and removes a push device against Hermesi's client API.
- `HermesiMessagingService`: re-registers a rotated Firebase token, and shows notifications that arrive while the
  app is open.
- A tapped notification opens only `http`, `https` and the app's own listed deep link schemes.
