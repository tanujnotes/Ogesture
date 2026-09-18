# Ogesture

Some phone brands, like Xiaomi, don’t allow navigation gestures with third-party Android launchers. Ogesture adds those gestures back.

**Gesture navigation for all.**

**[Play Store](https://play.google.com/store/apps/details?id=com.ogesture)**

**[APK release](https://github.com/tanujnotes/Ogesture/releases)**

**[Demo video](https://youtu.be/3mwjV9Nu9EU)**

## Gestures supported

| Gesture | Action |
|---|---|
| Swipe inward from the left or right edge | Back |
| Swipe up from the bottom edge | Home |
| Swipe up from the bottom edge and hold | Recents |

## How it works

Ogesture draws thin, invisible overlays along the edges of the screen and performs the navigation actions when it detects a swipe. The overlays are accessibility-overlay windows owned by Ogesture's accessibility service, so they work even on secure system screens (such as Android Settings) and do not require the "Display over other apps" permission.

It needs two permissions:

1. **Accessibility service** — owns the edge overlays that detect swipes and performs the Back, Home, and Recents actions.
2. **Unrestricted battery usage** — so the system doesn't kill the accessibility service in the background.

---

### Privacy
This app is free, open source, and collects no data. Everything runs locally on your device, and it does not have or need network permission.

### License

Licensed under the [GNU Affero General Public License v3.0](LICENSE).

---

Built with ❤️ by [Team Olauncher](https://play.google.com/store/apps/details?id=app.olauncher).
