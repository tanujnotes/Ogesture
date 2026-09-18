package com.ogesture.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ogesture.R
import com.ogesture.ui.theme.ExtendedTheme

enum class AccessibilityStatus { NOT_GRANTED, NEEDS_REBIND, BOUND }

/**
 * Single card listing everything the gestures need to run: the accessibility service
 * (which owns the gesture-zone overlay windows) plus unrestricted battery usage.
 */
@Composable
fun SetupCard(
    accessibilityStatus: AccessibilityStatus,
    batteryUnrestricted: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            val (state, subtitle, actionLabel) = when (accessibilityStatus) {
                AccessibilityStatus.BOUND -> Triple(
                    RowState.OK,
                    stringResource(R.string.permission_granted),
                    null,
                )
                AccessibilityStatus.NEEDS_REBIND -> Triple(
                    RowState.WARN,
                    stringResource(R.string.permission_needs_rebind),
                    stringResource(R.string.permission_open_settings),
                )
                AccessibilityStatus.NOT_GRANTED -> Triple(
                    RowState.MISSING,
                    stringResource(R.string.permission_not_granted),
                    stringResource(R.string.permission_open_settings),
                )
            }
            RequirementRow(
                label = stringResource(R.string.permission_accessibility),
                state = state,
                subtitle = subtitle,
                actionLabel = actionLabel,
                onAction = onRequestAccessibility,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            RequirementRow(
                label = stringResource(R.string.reliability_battery_label),
                state = if (batteryUnrestricted) RowState.OK else RowState.MISSING,
                subtitle = if (batteryUnrestricted) {
                    stringResource(R.string.reliability_battery_on)
                } else {
                    stringResource(R.string.reliability_battery_off)
                },
                actionLabel = stringResource(R.string.reliability_battery_action),
                onAction = onRequestUnrestricted,
            )
            oemReliabilityNoteRes()?.let { noteRes ->
                Text(
                    text = stringResource(noteRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Brands whose OS ships an "Autostart" (or equivalent) permission that must be enabled
 * for background services to survive. Compared against [Build.MANUFACTURER] lowercased;
 * sub-brands report the parent manufacturer (Redmi/Poco → "xiaomi", Honor → "huawei" on
 * older devices, "honor" on newer ones).
 */
private val AUTOSTART_MANUFACTURERS = setOf(
    "xiaomi", "oppo", "vivo", "oneplus", "huawei", "honor",
    "realme", "meizu", "asus", "letv", "infinix", "tecno", "itel",
)

/** OEM-specific reliability hint shown under the battery row, or null on stock-like brands. */
private fun oemReliabilityNoteRes(): Int? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    return when {
        manufacturer == "samsung" -> R.string.reliability_samsung_note
        manufacturer in AUTOSTART_MANUFACTURERS -> R.string.reliability_autostart_note
        else -> null
    }
}

private enum class RowState { OK, WARN, MISSING }

@Composable
private fun RequirementRow(
    label: String,
    state: RowState,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val statusColor = when (state) {
        RowState.OK -> ExtendedTheme.colors.success
        RowState.WARN -> ExtendedTheme.colors.warning
        RowState.MISSING -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBadge(color = statusColor, ok = state == RowState.OK)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == RowState.OK) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    statusColor
                },
            )
        }
        if (state != RowState.OK && actionLabel != null) {
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun StatusBadge(color: Color, ok: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = color.copy(alpha = 0.12f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
    }
}
