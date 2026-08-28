package com.ogesture.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** An installed app the user can turn Ogesture off for. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Launchable apps on this phone, for the compatibility screen's picker. Ogesture ships no
 * per-app claims: the user adds apps they have hit dead edge-taps in themselves (apps
 * whose touch security ignores accessibility-injected taps — verified for e.g. Swiggy).
 * Visibility comes from the manifest's <queries> launcher-intent declaration, so this
 * sees launchable apps only — no QUERY_ALL_PACKAGES.
 */
fun installedLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
    }
    return resolved
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

/** Label for a stored package, falling back to the raw package name if it's gone. */
fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}

// Rasterized launcher icons, so scrolling the picker back over an app doesn't redecode it.
// Bounded because the picker lists every launchable app; at icon size these are ~50 KB each.
private val iconCache = LruCache<String, Bitmap>(64)

/** Already-rasterized icon for [packageName], or null if it hasn't been loaded yet. */
fun cachedAppIcon(packageName: String): Bitmap? = iconCache.get(packageName)

/**
 * Clears the process-wide icon cache. Called when the compatibility/picker UI is left so launcher
 * bitmaps don't outlive the feature — keeps the privacy statement ("held only while the UI is
 * in use") accurate.
 */
fun clearIconCache() { iconCache.evictAll() }

/**
 * Rasterizes the app's launcher icon at [sizePx] and caches it; null if the app is gone.
 * Decoding an adaptive icon is slow enough to drop frames — call this off the main thread.
 */
fun loadAppIcon(context: Context, packageName: String, sizePx: Int): Bitmap? {
    iconCache.get(packageName)?.let { return it }
    return try {
        context.packageManager.getApplicationIcon(packageName)
            .toBitmap(sizePx, sizePx)
            .also { iconCache.put(packageName, it) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
