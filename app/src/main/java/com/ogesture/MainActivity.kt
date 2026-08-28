package com.ogesture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ogesture.service.EdgeGestureAccessibilityService
import com.ogesture.ui.AccessibilityConsentDialog
import com.ogesture.ui.AccessibilityStatus
import com.ogesture.ui.CompatEntryCard
import com.ogesture.ui.CompatibilityScreen
import com.ogesture.ui.GestureAreasEntryCard
import com.ogesture.ui.GestureAreasScreen
import com.ogesture.ui.MainViewModel
import com.ogesture.ui.PRIVACY_POLICY_URL
import com.ogesture.ui.SystemNavigationEntryCard
import com.ogesture.ui.SystemNavigationScreen
import com.ogesture.ui.SetupCard
import com.ogesture.ui.theme.OgestureTheme
import kotlinx.coroutines.delay

/**
 * Lightweight, mutually-exclusive navigation destinations for the app's local Compose
 * navigation (no Navigation Compose dependency). At most one secondary screen is active at
 * a time; [AppScreen.MAIN] is the dashboard, the other two are reached via entry cards.
 */
enum class AppScreen { MAIN, GESTURE_AREAS, COMPATIBILITY, SYSTEM_NAVIGATION }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Without this, 3-button navigation gets a translucent contrast scrim that makes the
        // nav bar look lighter than the app background.
        window.isNavigationBarContrastEnforced = false
        setContent {
            OgestureTheme {
                // Lightweight local navigation — no Navigation Compose dependency. A single
                // mutually-exclusive destination so two secondary screens can't be active at once.
                var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
                when (screen) {
                    AppScreen.GESTURE_AREAS -> {
                        BackHandler { screen = AppScreen.MAIN }
                        GestureAreasScreen(onBack = { screen = AppScreen.MAIN })
                    }
                    AppScreen.COMPATIBILITY -> {
                        BackHandler { screen = AppScreen.MAIN }
                        CompatibilityScreen(onBack = { screen = AppScreen.MAIN })
                    }
                    AppScreen.SYSTEM_NAVIGATION -> {
                        BackHandler { screen = AppScreen.MAIN }
                        SystemNavigationScreen(onBack = { screen = AppScreen.MAIN })
                    }
                    AppScreen.MAIN -> {
                        MainScreen(
                            onOpenGestureAreas = { screen = AppScreen.GESTURE_AREAS },
                            onOpenCompat = { screen = AppScreen.COMPATIBILITY },
                            onOpenSystemNav = { screen = AppScreen.SYSTEM_NAVIGATION },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onOpenGestureAreas: () -> Unit,
    onOpenCompat: () -> Unit,
    onOpenSystemNav: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val masterEnabled by viewModel.masterEnabled.collectAsState()

    var accessibilityStatus by remember { mutableStateOf(computeAccessibilityStatus(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var showAccessibilityConsent by rememberSaveable { mutableStateOf(false) }

    // Accessibility bound state comes from the service's StateFlow — no polling needed for that.
    val bound by EdgeGestureAccessibilityService.bound.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    // Lifecycle-safe observer: added in DisposableEffect and removed on dispose so navigation
    // between screens can't accumulate observers.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Battery whitelist changes outside the app, so re-check on resume.
                batteryUnrestricted = isBatteryUnrestricted(context)
                // Accessibility enabled-in-settings can also change outside the app.
                accessibilityStatus = computeAccessibilityStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Accessibility *bound* comes from the service StateFlow above (event-driven, no polling).
    // Keep the UI's accessibilityStatus in sync with it so the setup card reflects the live bind
    // state without a 1 Hz loop. The static "enabled in settings" + battery parts are refreshed
    // on resume. A bounded safety net handles the post-update rebind grace: if the service is
    // unbound while master is on, re-check for a few seconds before concluding it's broken.
    LaunchedEffect(bound) {
        if (bound) {
            // Service (re)bound — refresh the accessibility status so the setup card reflects it
            // immediately, not only on the next ON_RESUME.
            accessibilityStatus = computeAccessibilityStatus(context)
        } else if (viewModel.masterEnabled.value) {
            // Post-update rebind grace: the service briefly reports unbound right after an APK
            // reinstall. Re-check a bounded number of times before disabling.
            var unhealthyChecks = 0
            while (!EdgeGestureAccessibilityService.bound.value && viewModel.masterEnabled.value && unhealthyChecks < DISABLE_AFTER_SECONDS) {
                delay(1000)
                unhealthyChecks++
                batteryUnrestricted = isBatteryUnrestricted(context)
                accessibilityStatus = computeAccessibilityStatus(context)
            }
            // If still unbound after the grace window, disable gestures and tell the user.
            if (!EdgeGestureAccessibilityService.bound.value && viewModel.masterEnabled.value) {
                viewModel.setMasterEnabled(false)
                Toast.makeText(
                    context,
                    if (!batteryUnrestricted) R.string.toast_gestures_off_battery
                    else R.string.toast_gestures_off_accessibility,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val accessibilityReady = accessibilityStatus == AccessibilityStatus.BOUND
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showAccessibilityConsent) {
        AccessibilityConsentDialog(
            onContinue = {
                showAccessibilityConsent = false
                openAccessibilitySettings(context)
            },
            onDismiss = { showAccessibilityConsent = false },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            MasterSwitchCard(
                enabled = masterEnabled,
                canEnable = accessibilityReady && batteryUnrestricted,
                onToggle = { viewModel.setMasterEnabled(it) },
            )

            SectionHeader(stringResource(R.string.setup_title))
            SetupCard(
                accessibilityStatus = accessibilityStatus,
                batteryUnrestricted = batteryUnrestricted,
                // Play policy: the disclosure comes before every trip to Accessibility settings,
                // including the post-update rebind, since that also re-enables the service.
                onRequestAccessibility = { showAccessibilityConsent = true },
                onRequestUnrestricted = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            )

            SectionHeader(stringResource(R.string.gestures_title))
            GesturesCard()

            SectionHeader(stringResource(R.string.gesture_areas_title))
            GestureAreasEntryCard(onClick = onOpenGestureAreas)

            SectionHeader(stringResource(R.string.compat_title))
            CompatEntryCard(onClick = onOpenCompat)

            SectionHeader(stringResource(R.string.sysnav_title))
            SystemNavigationEntryCard(onClick = onOpenSystemNav)

            SectionHeader(stringResource(R.string.remember_title))
            RememberCard()

            FooterCredit()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    canEnable: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "masterSwitchContainer",
    )
    val interactive = canEnable || enabled
    Surface(
        onClick = { onToggle(!enabled) },
        enabled = interactive,
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.master_switch_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        enabled -> stringResource(R.string.master_switch_subtitle_on)
                        canEnable -> stringResource(R.string.master_switch_subtitle_ready)
                        else -> stringResource(R.string.master_switch_subtitle_blocked)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle(it) },
                enabled = interactive,
            )
        }
    }
}

@Composable
private fun GesturesCard() {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            GestureRow(
                label = stringResource(R.string.gesture_back_label),
                description = stringResource(R.string.gesture_back_desc),
            ) {
                val color = MaterialTheme.colorScheme.onSecondaryContainer
                Canvas(modifier = Modifier.size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2
                    val triangle = Path().apply {
                        moveTo(size.width - inset, inset)
                        lineTo(size.width - inset, size.height - inset)
                        lineTo(inset, size.height / 2)
                        close()
                    }
                    drawPath(
                        path = triangle,
                        color = color,
                        style = Stroke(width = stroke, join = StrokeJoin.Round),
                    )
                }
            }
            GestureRow(
                label = stringResource(R.string.gesture_home_label),
                description = stringResource(R.string.gesture_home_desc),
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = CircleShape,
                        ),
                )
            }
            GestureRow(
                label = stringResource(R.string.gesture_recents_label),
                description = stringResource(R.string.gesture_recents_desc),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun GestureRow(
    label: String,
    description: String,
    badge: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            badge()
        }
        Column {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RememberCard() {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val openSourceText = buildAnnotatedString {
                append(stringResource(R.string.remember_open_source_prefix))
                append(" ")
                withLink(LinkAnnotation.Url(GITHUB_URL, linkStyles())) {
                    append(stringResource(R.string.remember_open_source_link))
                }
                append(stringResource(R.string.remember_open_source_suffix))
                append(" ")
                append(stringResource(R.string.remember_read_the_full_prefix))
                append(" ")
                withLink(LinkAnnotation.Url(PRIVACY_POLICY_URL, linkStyles())) {
                    append(stringResource(R.string.remember_privacy_policy_suffix))
                }
            }
            RememberPoint(openSourceText)
            RememberPoint(AnnotatedString(stringResource(R.string.remember_on_device)))
        }
    }
}

@Composable
private fun FooterCredit() {
    val text = buildAnnotatedString {
        append(stringResource(R.string.footer_built_with))
        append(" ")
        appendInlineContent("heart", "♥")
        append(" ")
        append(stringResource(R.string.footer_by_team))
        append(" ")
        withLink(
            LinkAnnotation.Url(OLAUNCHER_PLAY_STORE_URL, linkStyles()),
        ) {
            append(stringResource(R.string.footer_olauncher))
        }
    }
    val inlineContent = mapOf(
        "heart" to InlineTextContent(
            Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color(0xFFEF5350),
            )
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            inlineContent = inlineContent,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun linkStyles() = TextLinkStyles(
    style = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    ),
)

@Composable
private fun RememberPoint(text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seconds the gesture requirements must stay unmet before the switch is auto-disabled. */
private const val DISABLE_AFTER_SECONDS = 3
private const val OLAUNCHER_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=app.olauncher"
private const val GITHUB_URL = "https://github.com/tanujnotes/Ogesture"

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun computeAccessibilityStatus(context: android.content.Context): AccessibilityStatus {
    val inSettings = EdgeGestureAccessibilityService.isEnabledInSettings(context)
    val bound = EdgeGestureAccessibilityService.isBound()
    return when {
        bound -> AccessibilityStatus.BOUND
        inSettings -> AccessibilityStatus.NEEDS_REBIND
        else -> AccessibilityStatus.NOT_GRANTED
    }
}
