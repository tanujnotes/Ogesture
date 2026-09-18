package com.ogesture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ogesture.R
import com.ogesture.data.appLabel
import com.ogesture.data.cachedAppIcon
import com.ogesture.data.installedLaunchableApps
import com.ogesture.data.loadAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explains that some apps ignore the simulated taps Ogesture replays, and lets the user
 * manage the apps Ogesture is turned off for. The list is theirs alone: it starts empty,
 * apps appear only when added through the picker, and removing one turns gestures back
 * on there. Ogesture ships no per-app claims of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityScreen(onBack: () -> Unit, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val excludedApps by viewModel.excludedApps.collectAsState()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    if (showPicker) {
        BackHandler { showPicker = false }
        AppPickerScreen(
            excluded = excludedApps,
            onPick = { packageName ->
                viewModel.setAppExcluded(packageName, excluded = true)
                showPicker = false
            },
            onBack = { showPicker = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { CompatTopBar(stringResource(R.string.compat_screen_title), onBack) },
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
            InfoCard(stringResource(R.string.compat_intro))

            CompatSectionHeader(stringResource(R.string.compat_apps_header))
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    val entries = remember(excludedApps) {
                        excludedApps
                            .map { it to appLabel(context, it) }
                            .sortedBy { (_, label) -> label.lowercase() }
                    }
                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.compat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                    for ((packageName, label) in entries) {
                        ExcludedAppRow(
                            packageName = packageName,
                            label = label,
                            onRemove = { viewModel.setAppExcluded(packageName, excluded = false) },
                        )
                    }
                    AddAppRow(onClick = { showPicker = true })
                }
            }

        }
    }
}

/** Row on the main screen that opens [CompatibilityScreen]. */
@Composable
fun CompatEntryCard(onClick: () -> Unit) {
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
                text = stringResource(R.string.compat_entry_desc),
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

/** Full-screen list of launchable apps; tapping one turns Ogesture off for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    excluded: Set<String>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val allApps = remember { installedLaunchableApps(context) }
    val candidates = remember(allApps, excluded) {
        allApps.filter { it.packageName !in excluded }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { CompatTopBar(stringResource(R.string.compat_picker_title), onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            items(candidates, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(app.packageName) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ICON_TEXT_GAP),
                ) {
                    AppIcon(app.packageName, app.label)
                    Text(text = app.label, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompatTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
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

@Composable
private fun ExcludedAppRow(
    packageName: String,
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_TEXT_GAP),
    ) {
        AppIcon(packageName, label)
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        // The button keeps its full touch target; only the glyph inside is dialled down,
        // so removing an app stays easy to hit without the cross shouting for attention.
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.compat_remove_app, label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AddAppRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_TEXT_GAP),
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
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.compat_add_app),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/**
 * The app's own launcher icon, desaturated to match the rest of the UI. Icons are
 * rasterized off the main thread and cached, so scrolling the picker stays smooth; a
 * letter badge stands in while one loads, or for good if the app has been uninstalled.
 */
@Composable
private fun AppIcon(packageName: String, label: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    // Seeding from the cache means an already-loaded icon shows on the first frame,
    // instead of flashing the letter badge every time the row scrolls back into view.
    val icon by produceState(remember(packageName) { cachedAppIcon(packageName) }, packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadAppIcon(context, packageName, sizePx) }
        }
    }
    val bitmap = icon
    if (bitmap == null) {
        LetterBadge(label)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

@Composable
private fun LetterBadge(label: String) {
    Box(
        modifier = Modifier
            .size(ICON_SIZE)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private val ICON_SIZE = 40.dp

/** Gap between an app's icon and its name, in every list on this screen. */
private val ICON_TEXT_GAP = 24.dp

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

@Composable
private fun CompatSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}
