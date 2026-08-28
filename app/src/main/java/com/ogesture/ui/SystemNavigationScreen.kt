package com.ogesture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ogesture.R
import com.ogesture.service.SystemNavigationController

/**
 * Optional HyperOS system-navigation watchdog settings screen. Reached from the main dashboard
 * via [SystemNavigationEntryCard]. Mirrors [CompatibilityScreen]/[GestureAreasScreen]: a Material 3
 * Scaffold + TopAppBar over a scrollable column with an intro card, a feature switch, and an
 * ADB-permission setup section.
 *
 * The screen is device-aware: on non-Xiaomi/Redmi/POCO devices the switch is disabled with a
 * "Not supported on this device" notice, and no enforcement can run. On supported devices the
 * switch is enabled only when the WRITE_SECURE_SETTINGS permission (granted via ADB) is present;
 * when it's missing, the exact one-time ADB command is shown with a copy button.
 *
 * Permission state is re-checked whenever the screen becomes visible (resume), so the user can
 * leave Ogesture, run the ADB command, come back, and immediately see "permission granted"
 * without restarting the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemNavigationScreen(onBack: () -> Unit, viewModel: MainViewModel = viewModel()) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val masterEnabled by viewModel.masterEnabled.collectAsState()
    val hideSystemNav by viewModel.hideSystemNavigation.collectAsState()

    val deviceSupported = SystemNavigationController.isXiaomiEcosystemDevice()

    // Re-check the ADB-granted permission whenever the screen resumes, so a user who left to run
    // the adb command sees the new state immediately without restarting the app.
    var permGranted by remember { mutableStateOf(hasWriteSecureSettings(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val observer = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permGranted = hasWriteSecureSettings(context)
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { SysNavTopBar(onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoCard(stringResource(R.string.sysnav_intro))

            Spacer(modifier = Modifier.height(8.dp))
            FeatureSwitchCard(
                supported = deviceSupported,
                permissionGranted = permGranted,
                masterOn = masterEnabled,
                hideEnabled = hideSystemNav,
                onToggle = { viewModel.setHideSystemNavigation(it) },
            )

            if (deviceSupported) {
                Spacer(modifier = Modifier.height(8.dp))
                PermissionCard(granted = permGranted, context = context)
            }
        }
    }
}

/** Row on the main screen that opens [SystemNavigationScreen]. Mirrors [CompatEntryCard]. */
@Composable
fun SystemNavigationEntryCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.sysnav_entry_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeatureSwitchCard(
    supported: Boolean,
    permissionGranted: Boolean,
    masterOn: Boolean,
    hideEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sysnav_switch_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.sysnav_switch_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hideEnabled && supported,
                    // A feature that is already ON must always be switchable OFF, even if the
                    // permission was revoked or master gestures are off. Prerequisites only gate
                    // turning it ON (when currently OFF).
                    enabled = supported && (hideEnabled || (permissionGranted && masterOn)),
                    onCheckedChange = onToggle,
                )
            }
            if (!supported) {
                Text(
                    text = stringResource(R.string.sysnav_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else if (!masterOn) {
                Text(
                    text = stringResource(R.string.sysnav_needs_gestures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(granted: Boolean, context: Context) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (granted) stringResource(R.string.sysnav_perm_granted)
                    else stringResource(R.string.sysnav_perm_required),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (!granted) {
                Text(
                    text = stringResource(R.string.sysnav_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Windows command — must be present in the app UI.
                CommandRow(
                    text = stringResource(R.string.sysnav_adb_cmd_windows),
                    context = context,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = stringResource(R.string.sysnav_adb_unix_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CommandRow(
                    text = stringResource(R.string.sysnav_adb_cmd_unix),
                    context = context,
                )
                Text(
                    text = stringResource(R.string.sysnav_setup_notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CommandRow(text: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        TextButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", text))
            Toast.makeText(context, R.string.sysnav_copied, Toast.LENGTH_SHORT).show()
        }) {
            Text(
                text = stringResource(R.string.sysnav_copy),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SysNavTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.sysnav_screen_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

private fun hasWriteSecureSettings(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}
