package com.aiope2.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReasoningTabStrip(reasoning: List<String>, isReasoningDone: Boolean) {
  if (reasoning.isEmpty()) return
  val cs = MaterialTheme.colorScheme
  var selectedIdx by remember { mutableStateOf(-1) }

  // Auto-select the active (last) tab while streaming
  val activeIdx = reasoning.lastIndex
  LaunchedEffect(activeIdx, isReasoningDone) {
    if (!isReasoningDone) selectedIdx = activeIdx
  }

  Column(Modifier.fillMaxWidth()) {
    // Tab row
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      reasoning.forEachIndexed { idx, _ ->
        val isActive = idx == activeIdx && !isReasoningDone
        val isSelected = idx == selectedIdx
        Surface(
          modifier = Modifier.weight(1f).heightIn(min = 32.dp).clickable { selectedIdx = if (isSelected) -1 else idx },
          shape = RoundedCornerShape(12.dp),
          color = if (isSelected) Color(0xFF1A1A2E) else Color(0xFF111111),
          border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.4f)) else null,
        ) {
          Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
              Text("💭", fontSize = 11.sp)
              Spacer(Modifier.width(3.dp))
              if (isActive) {
                ShimmerText("${idx + 1}", cs)
              } else {
                Text("${idx + 1}", fontSize = 12.sp, fontWeight = FontWeight.W700, color = cs.onSurfaceVariant)
              }
            }
          }
        }
      }
    }

    // Expanded content
    AnimatedVisibility(visible = selectedIdx in reasoning.indices, enter = expandVertically(), exit = shrinkVertically()) {
      if (selectedIdx in reasoning.indices) {
        val text = reasoning[selectedIdx]
        val isActive = selectedIdx == activeIdx && !isReasoningDone
        Surface(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          shape = RoundedCornerShape(12.dp),
          color = Color(0xFF111111),
        ) {
          Box(Modifier.padding(10.dp).heightIn(max = 120.dp)) {
            val lines = text.lines()
            val display = if (isActive && lines.size > 4) lines.takeLast(4).joinToString("\n") else text
            SelectionContainer {
              Text(display, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
            if (isActive && lines.size > 4) {
              Box(
                Modifier.matchParentSize().drawWithContent {
                  drawContent()
                  drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF111111), Color.Transparent), startY = 0f, endY = size.height * 0.3f))
                },
              )
            }
          }
        }
      }
    }
  }
}
