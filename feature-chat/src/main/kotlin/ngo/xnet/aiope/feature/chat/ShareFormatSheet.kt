package ngo.xnet.aiope.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFormatSheet(
  onDismissRequest: () -> Unit,
  onFormatSelected: (String) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 32.dp, top = 8.dp),
    ) {
      Text(
        "Export Conversation",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )

      HorizontalDivider(Modifier.padding(vertical = 8.dp))

      ShareFormatRow(
        title = "Plain Text",
        subtitle = "Share as a readable .txt file",
        icon = Icons.Default.TextFields,
        onClick = { onFormatSelected("txt") },
      )

      ShareFormatRow(
        title = "Markdown",
        subtitle = "Share as a formatted .md file",
        icon = Icons.Default.Description,
        onClick = { onFormatSelected("md") },
      )

      ShareFormatRow(
        title = "PDF",
        subtitle = "Generate a PDF document (with math support)",
        icon = Icons.Default.PictureAsPdf,
        onClick = { onFormatSelected("pdf") },
      )

      ShareFormatRow(
        title = "JSON",
        subtitle = "Raw structured data format",
        icon = Icons.Default.Code,
        onClick = { onFormatSelected("json") },
      )
    }
  }
}

@Composable
private fun ShareFormatRow(
  title: String,
  subtitle: String,
  icon: ImageVector,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(24.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
