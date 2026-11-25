package com.example.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AppThemeId
import com.example.ui.theme.AppThemeSpec
import com.example.ui.theme.appThemeSpec
import com.example.ui.theme.themedCorner

@StringRes
private fun AppThemeId.nameRes(): Int = when (this) {
    AppThemeId.XP_LUNA -> R.string.theme_xp_luna_name
    AppThemeId.MIDNIGHT -> R.string.theme_midnight_name
    AppThemeId.DAYLIGHT -> R.string.theme_daylight_name
    AppThemeId.SYSTEM -> R.string.theme_system_name
}

@StringRes
private fun AppThemeId.descriptionRes(): Int = when (this) {
    AppThemeId.XP_LUNA -> R.string.theme_xp_luna_description
    AppThemeId.MIDNIGHT -> R.string.theme_midnight_description
    AppThemeId.DAYLIGHT -> R.string.theme_daylight_description
    AppThemeId.SYSTEM -> R.string.theme_system_description
}

/** The Appearance section of Settings: one row per theme, applied as soon as it is tapped. */
@Composable
fun ThemePickerCard(
    selected: AppThemeId,
    onSelect: (AppThemeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = themedCorner(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings_appearance_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
                for (theme in AppThemeId.entries) {
                    ThemeOptionRow(
                        theme = theme,
                        selected = theme == selected,
                        onSelect = { onSelect(theme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(theme: AppThemeId, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_option_${theme.storageKey}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeSwatch(appThemeSpec(theme, isSystemInDarkTheme()))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(theme.nameRes()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(theme.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row is the click target, so the radio button only shows the state.
        RadioButton(selected = selected, onClick = null)
    }
}

/** A thumbnail of a window in the given theme: its title bar, background, a card and an accent. */
@Composable
private fun ThemeSwatch(spec: AppThemeSpec) {
    val shape = RoundedCornerShape(4.dp.coerceAtMost(spec.chrome.maxCorner))
    Column(
        modifier = Modifier
            .size(width = 52.dp, height = 40.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(spec.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp)
                .background(spec.chrome.titleBar.background),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(spec.colorScheme.surfaceContainer)
                    .border(1.dp, spec.colorScheme.outlineVariant),
            )
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 8.dp)
                    .align(Alignment.Bottom)
                    .background(spec.chrome.primaryAction),
            )
        }
    }
}
