package com.aiope2.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ToolTabStrip(calls: List<String>, results: List<String>) {
  if (calls.isEmpty()) return
  val cs = MaterialTheme.colorScheme
  var selectedIdx by remember { mutableStateOf(-1) }

  Column(Modifier.fillMaxWidth()) {
    // Tab row — scrollable when many tools
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      calls.forEachIndexed { idx, call ->
        val isDone = idx < results.size
        val isSelected = idx == selectedIdx
        val toolName = call.substringBefore("(").substringBefore(" ").trim()

        val statusColor = if (isDone) Color(0xFF4CAF50) else cs.primary

        Surface(
          modifier = Modifier.widthIn(min = 48.dp, max = 140.dp).heightIn(min = 34.dp)
            .clickable { selectedIdx = if (isSelected) -1 else idx },
          shape = RoundedCornerShape(12.dp),
          color = if (isSelected) Color(0xFF1A1A2E) else Color(0xFF111111),
          border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f))
          } else {
            androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
          },
        ) {
          Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (!isDone) {
                ShimmerText(toolName, cs)
              } else {
                Text(toolName, fontSize = 12.sp, fontWeight = FontWeight.W700, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }

    // Expanded content
    AnimatedVisibility(visible = selectedIdx in calls.indices, enter = expandVertically(), exit = shrinkVertically()) {
      if (selectedIdx in calls.indices) {
        val isDone = selectedIdx < results.size
        Surface(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          shape = RoundedCornerShape(12.dp),
          color = Color(0xFF111111),
        ) {
          Column(Modifier.padding(10.dp).heightIn(max = 160.dp)) {
            SelectionContainer {
              Text(calls[selectedIdx], fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
            }
            if (isDone) {
              Spacer(Modifier.height(6.dp))
              Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                  Text(results[selectedIdx], fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace)
                }
              }
            }
          }
        }
      }
    }
  }
}
