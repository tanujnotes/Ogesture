package com.ogesture.ui

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ogesture.R
import com.ogesture.data.GestureZoneSettings

/**
 * Dedicated settings screen for the configurable gesture-zone geometry. Reached from the main
 * dashboard via [GestureAreasEntryCard]. Mirrors [CompatibilityScreen]'s structure: a
 * Material 3 [Scaffold] with a [TopAppBar] (back arrow + "Gesture areas" title) over a
 * scrollable column that opens with a short info card and then holds the detailed settings card.
 *
 * The four sliders are moved here unchanged from the main screen: local drag state while
 * dragging, persistence on `onValueChangeFinished`, immediate application via the controller
 * (which observes the same DataStore flow). No Save button. Defaults/ranges/steps and the
 * underlying [GestureZoneSettings] / `computeGestureZoneLayout` are untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureAreasScreen(onBack: () -> Unit, viewModel: MainViewModel = viewModel()) {
    BackHandler { onBack() }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { GestureAreasTopBar(onBack) },
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
            InfoCard(stringResource(R.string.gesture_areas_subtitle))
            Spacer(modifier = Modifier.height(8.dp))
            GestureAreasCard(viewModel)
        }
    }
}

/** Row on the main screen that opens [GestureAreasScreen]. Mirrors [CompatEntryCard]. */
@Composable
fun GestureAreasEntryCard(onClick: () -> Unit) {
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
                text = stringResource(R.string.gesture_areas_entry_desc),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureAreasTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.gesture_areas_screen_title)) },
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
private fun GestureAreasCard(viewModel: MainViewModel) {
    val settings by viewModel.gestureZoneSettings.collectAsState()
    val cancellation by viewModel.gestureCancellationSettings.collectAsState()

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            AreaSectionHeader(stringResource(R.string.areas_back_header))
            PercentSlider(
                label = stringResource(R.string.areas_back_height_label),
                hint = stringResource(R.string.areas_back_height_hint),
                value = settings.backActivationHeightPercent,
                onValueChangeFinished = { viewModel.setBackActivationHeight(it) },
            )
            SensitivitySlider(
                label = stringResource(R.string.areas_back_sensitivity_label),
                hint = stringResource(R.string.areas_back_sensitivity_hint),
                baseDp = GestureZoneSettings.BASE_BACK_THICKNESS_DP,
                value = settings.backEdgeSensitivity,
                onValueChangeFinished = { viewModel.setBackEdgeSensitivity(it) },
            )

            CancellationToggle(stringResource(R.string.areas_cancel_back_label), stringResource(R.string.areas_cancel_back_hint), cancellation.cancelBack, viewModel::setCancelBack)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            AreaSectionHeader(stringResource(R.string.areas_bottom_header))
            Text(
                text = stringResource(R.string.areas_bottom_shared_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PercentSlider(
                label = stringResource(R.string.areas_bottom_width_label),
                hint = stringResource(R.string.areas_bottom_width_hint),
                value = settings.bottomActivationWidthPercent,
                onValueChangeFinished = { viewModel.setBottomActivationWidth(it) },
            )
            SensitivitySlider(
                label = stringResource(R.string.areas_bottom_sensitivity_label),
                hint = stringResource(R.string.areas_bottom_sensitivity_hint),
                baseDp = GestureZoneSettings.BASE_BOTTOM_THICKNESS_DP,
                value = settings.bottomEdgeSensitivity,
                onValueChangeFinished = { viewModel.setBottomEdgeSensitivity(it) },
            )
            CancellationToggle(stringResource(R.string.areas_cancel_home_label), stringResource(R.string.areas_cancel_home_hint), cancellation.cancelHome, viewModel::setCancelHome)
        }
    }
}

@Composable
private fun CancellationToggle(label: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AreaSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun PercentSlider(
    label: String,
    hint: String,
    value: Int,
    onValueChangeFinished: (Int) -> Unit,
) {
    // Local drag state snapped to the percentage step; persisted only when the thumb lifts.
    var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.areas_percent_value, drag.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                // Round (don't truncate) to the nearest int before snapping to the 10% step,
                // so a thumb released near 20% doesn't fall back to 10% via Float.toInt().
                val snapped = GestureZoneSettings.clampPercent(Math.round(drag))
                drag = snapped.toFloat()
                onValueChangeFinished(snapped)
            },
            valueRange = GestureZoneSettings.PERCENT_MIN.toFloat()..GestureZoneSettings.PERCENT_MAX.toFloat(),
            steps = (GestureZoneSettings.PERCENT_MAX - GestureZoneSettings.PERCENT_MIN) / GestureZoneSettings.PERCENT_STEP - 1,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SensitivitySlider(
    label: String,
    hint: String,
    baseDp: Int,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
) {
    var drag by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            val effectiveDp = (baseDp * drag).toInt().coerceAtLeast(1)
            Text(
                text = stringResource(R.string.areas_sensitivity_value, drag, effectiveDp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val snapped = GestureZoneSettings.clampSensitivity(drag)
                drag = snapped
                onValueChangeFinished(snapped)
            },
            valueRange = GestureZoneSettings.SENSITIVITY_MIN..GestureZoneSettings.SENSITIVITY_MAX,
            steps = ((GestureZoneSettings.SENSITIVITY_MAX - GestureZoneSettings.SENSITIVITY_MIN) / GestureZoneSettings.SENSITIVITY_STEP).toInt() - 1,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
