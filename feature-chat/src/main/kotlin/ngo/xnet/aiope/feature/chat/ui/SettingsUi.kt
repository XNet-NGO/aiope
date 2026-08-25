package ngo.xnet.aiope.feature.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Settings building blocks, grouped-card style (iOS Settings / Linear).
 *
 * The old settings screen was one flat `ListItem` column with a divider between every entry, so
 * eighteen unrelated things looked equally important. Here related rows live inside one rounded
 * group with a section label above it, which is what makes a long settings list scannable.
 *
 * Radius geometry follows [CuORadius]: the group card is `lg` (20dp) and rows inside it are inset
 * by [GroupInset] (6dp), so a highlighted row uses `lg - 6dp = 14dp` and its corner stays concentric
 * with the card's. Leading icon tiles are the innermost shape: `14 - 4dp gap = 10dp`, rounded to the
 * `sm` step for consistency with the rest of the scale.
 */
private val GroupInset = 6.dp

/** Small caps section label. Sits outside the card, so it reads as a header, not a row. */
@Composable
fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    // Demoted from primary: when headers, every icon and the one real action all share the accent,
    // the accent stops meaning "actionable". Blue is reserved for controls now.
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    // start = card margin (12dp) so the label is flush with the card edge it labels, not floating
    // between the card edge and the row-title column.
    modifier = modifier.padding(start = 2.dp, top = 22.dp, bottom = 8.dp),
  )
}

/** Rounded container that groups related rows. Rows are separated by hairlines, not full dividers. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(CuORadius.lg))
      // surfaceContainer alone measured ~1.5:1 against the background — marginal in sunlight.
      // A hairline stroke defines the card edge without raising the fill enough to flatten depth.
      .background(cs.surfaceContainerHigh)
      .border(BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.5f)), RoundedCornerShape(CuORadius.lg))
      .padding(GroupInset),
  ) {
    content()
  }
}

/**
 * One settings row: icon tile, title, optional subtitle, and either a chevron or a trailing slot.
 *
 * [accent] tints the icon tile so a group of rows is visually differentiated without adding
 * separate colours to the text — the label stays high-contrast.
 */
@Composable
fun SettingsRow(
  title: String,
  subtitle: String? = null,
  icon: ImageVector? = null,
  accent: Color? = null,
  showChevron: Boolean = true,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  val cs = MaterialTheme.colorScheme
  val tint = accent ?: cs.primary
  val rowShape = RoundedCornerShape(CuORadius.inner(CuORadius.lg, GroupInset))
  Row(
    Modifier
      .fillMaxWidth()
      .clip(rowShape)
      .let { if (onClick != null) it.clickable(onClick = onClick) else it }
      .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Box(
        Modifier
          .size(40.dp)
          // A tile at 1/10 the card's area looks proportionally rounder at equal radius, so it sits
          // one step below the row: 14 − 4 = 10dp.
          .clip(RoundedCornerShape(CuORadius.xs))
          .background(tint.copy(alpha = 0.24f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
      }
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
      if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
      }
    }
    when {
      trailing != null -> trailing()

      showChevron && onClick != null -> Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = cs.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/** Settings row whose trailing control is a switch; the whole row toggles it. */
@Composable
fun SettingsToggleRow(
  title: String,
  subtitle: String? = null,
  icon: ImageVector? = null,
  accent: Color? = null,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  SettingsRow(
    title = title,
    subtitle = subtitle,
    icon = icon,
    accent = accent,
    showChevron = false,
    onClick = { onCheckedChange(!checked) },
    trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
  )
}

/** Hairline between rows inside a group; inset so it doesn't touch the rounded corners. */
@Composable
fun SettingsRowDivider() {
  Box(
    Modifier
      .padding(horizontal = 14.dp)
      .fillMaxWidth()
      .height(0.7.dp)
      // 0.4 alpha measured ~1.3:1 against the card — functionally invisible. Raised so the hairline
      // actually separates rows.
      .background(MaterialTheme.colorScheme.outlineVariant),
  )
}

/** Vertical breathing room between groups. */
@Composable
fun SettingsGroupSpacer() = Spacer(Modifier.height(4.dp))

/** Arrangement helper so callers don't re-declare the same spacing. */
val SettingsRowArrangement = Arrangement.spacedBy(0.dp)
