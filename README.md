# Ogesture

Some phone brands, like Xiaomi, don’t allow navigation gestures with third-party Android launchers. Ogesture adds those gestures back.

**Gesture navigation for all.**

**[Play Store](https://play.google.com/store/apps/details?id=com.ogesture)**

**[APK release](https://github.com/tanujnotes/Ogesture/releases)**

**[Demo video](https://youtu.be/3mwjV9Nu9EU)**

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

---

### Things to remember
Android's security policy blocks gestures on certain screens, such as your phone's Settings pages.

### Privacy
This app is free, open source, and collects no data. Everything runs locally on your device, and it does not have or need network permission.

### License

Licensed under the [GNU Affero General Public License v3.0](LICENSE).

---

Built with ❤️ by [Team Olauncher](https://play.google.com/store/apps/details?id=app.olauncher).
