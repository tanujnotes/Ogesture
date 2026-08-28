# Ogesture

**Gesture navigation for all.**

Ogesture adds gesture navigation to Android phones that don't allow gesture-based navigation with third-party launchers. E.g. Xiaomi phones.

You can now use gestures even with 3-button navigation enabled.

**[Download APK ...](https://github.com/tanujnotes/Ogesture/releases)**

**[Demo video ...](https://youtu.be/3mwjV9Nu9EU)**

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

The app guides you through granting both on first launch.

### Optional: hide the system navigation buttons (Xiaomi/HyperOS)

On some Xiaomi/Redmi/POCO devices running MIUI/HyperOS the system three-button navigation bar can come back even after you switched to gesture navigation, which gets in the way of Ogesture handling Back, Home and Recents. Ogesture has an optional **System navigation** watchdog (off by default) that keeps the three-button bar hidden by maintaining two system settings (`force_fsg_nav_bar=1` and `hide_gesture_line=1`) while it is active.

This is an optional, advanced feature and needs a one-time privileged permission that cannot be granted through a normal dialog. Enable USB debugging, connect your phone to a computer with Android Platform Tools/ADB, and run once:

```
adb shell pm grant com.ogesture android.permission.WRITE_SECURE_SETTINGS
```

(Windows PowerShell: `.\adb.exe shell pm grant com.ogesture android.permission.WRITE_SECURE_SETTINGS`)

Root is not required. The permission is used only while this option is enabled, only to set the two navigation settings above, and Ogesture restores the previous navigation state when you turn the option off. When the option is disabled, Ogesture stops enforcing these system settings.

## Things to remember

- This app is free, open source, and collects no data. Everything runs locally on your device.

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE).

---

Built with ❤️ by team [Olauncher](https://play.google.com/store/apps/details?id=app.olauncher).
