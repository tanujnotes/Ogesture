# Privacy Policy

**Ogesture** — last updated 20 August 2026

Ogesture does not collect, store, transmit, or share any personal data. There are no accounts, no analytics, no crash reporting, and no advertising. Everything the app does happens on your device.

Ogesture does not request the `INTERNET` permission, so it has no technical ability to send anything anywhere. The app is open source under the AGPL-3.0, so anyone can verify every claim on this page against the code.

## Data we collect

None.

We do not collect your name, email, contacts, location, files, screen content, or any identifier. Nothing is uploaded, because nothing can be — the app has no network access.

## The accessibility service

Ogesture runs an Android accessibility service. Android reserves the Back, Home, and Recents actions for accessibility services, so this is the only way any app can perform them when you swipe in from a screen edge.

**What the service cannot do.** It is configured with `canRetrieveWindowContent="false"`. It cannot read the content of your screen, the text you type, your passwords, your messages, or what you tap. It is also limited to a single event type, `typeWindowStateChanged` — it does not receive typing, scrolling, focus, or click events at all.

**What the service does.** Two things, and nothing else:

1. **Performs Back, Home, and Recents** when you swipe in from an edge zone.
2. **Reads the package name of the app currently in the foreground.** This comes from the window-state event — never from screen content. It is used for one purpose: to switch the gesture zones off while you are inside an app you added to the compatibility exclusion list. The package name is held in memory while the app runs, is never written to disk, and never leaves the device.

**Touch replay.** The service is allowed to dispatch touch gestures. This is used solely to hand back a touch that an edge strip consumed but that did not turn out to be a gesture, so your tap still reaches the app underneath. Ogesture never synthesizes taps of its own and never interacts with any app on your behalf.

You can turn the service off at any time in **Settings › Accessibility › Ogesture**.

## The list of apps on your phone

The compatibility screen lets you pick apps to turn gestures off in, so it shows your launchable apps with their names and icons. Icons are decoded and held in a small in-memory cache while the screen is open; they are never written to disk. The list is read fresh each time and is never stored or transmitted.

Ogesture does **not** request `QUERY_ALL_PACKAGES`. Its `<queries>` declaration is deliberately narrow: apps with a launcher icon (for the picker), and installed keyboards (so a keyboard opening is not mistaken for you switching apps).

## Permissions

| Permission | Why it is needed |
|---|---|
| **Accessibility service** | Owns the thin, invisible edge overlays that detect your swipes, and performs Back, Home, and Recents. See above. |
| **Unrestricted battery usage** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Stops the system from killing the accessibility service in the background. |
| **Vibrate** (`VIBRATE`) | Haptic feedback when a gesture triggers. |

Ogesture also reads one system setting, `ENABLED_ACCESSIBILITY_SERVICES`, to check whether you have granted it accessibility access — so the app can show the correct setup state and turn gestures off if the permission goes away.

## What is stored on your device

Two things, in the app's private storage:

- Whether gestures are switched on.
- The list of apps you excluded, stored as package names.

That is the entire contents. There is no database of your activity, no history of gestures performed, and no log of which apps you have opened. Uninstalling Ogesture removes all of it.

## Backups

Ogesture is excluded from Android's backup system entirely. Its settings are not included in your Google account backup, and are not carried over by device-to-device transfer when you set up a new phone. Nothing about Ogesture leaves your device by any route.

The trade-off is deliberate: after moving to a new phone you will need to switch gestures back on and re-add any apps you had excluded.

## Third parties

There are none. Ogesture bundles no analytics SDK, no advertising SDK, no crash reporter, and no third-party service of any kind. Its dependencies are Google's own AndroidX and Jetpack Compose libraries, none of which transmit data on the app's behalf.

## Children

Ogesture is suitable for all ages. It collects no data from anyone, including children under 13.

## Changes

If this policy ever changes, the updated version will be published at this address and the date above will be revised.

## Contact

Questions: open an issue at <https://github.com/tanujnotes/Ogesture/issues>.
