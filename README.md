# Ogesture

**Gesture navigation for all.**

Ogesture adds gesture navigation to Android phones that don't allow gesture-based navigation with third-party launchers. E.g. Xiaomi phones.

You can now use gestures even with 3-button navigation enabled.

**[Download APK ...](https://github.com/tanujnotes/Ogesture/releases)**

**[Demo video ...](https://youtu.be/3mwjV9Nu9EU)**

## Gestures supported

- Swipe inward from the left edge 
- Swipe inward from the left edge and hold
- Swipe inward from the right edge
- Swipe inward from the right edge and hold
- Swipe up from the bottom edge
- Swipe up from the bottom edge and hold

## Actions supported
- Home
- Back
- Recents

Each gesture can be mapped to any action (or none)

## How it works

Ogesture draws thin, invisible overlays along the edges of the screen and uses an accessibility service to perform the navigation actions when it detects a swipe.

It needs three permissions:

1. **Display over other apps** — to place the edge overlays that detect swipes.
2. **Accessibility service** — to perform the Back, Home, and Recents actions.
3. **Unrestricted battery usage** — so the system doesn't kill the gesture service in the background.

The app guides you through granting all three on first launch.

## Things to remember

- This app is free, open source, and collects no data. Everything runs locally on your device.
- Android's security policy blocks gestures on certain screens, such as your phone's Settings pages.

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE).

---

Built with ❤️ by team [Olauncher](https://play.google.com/store/apps/details?id=app.olauncher).
