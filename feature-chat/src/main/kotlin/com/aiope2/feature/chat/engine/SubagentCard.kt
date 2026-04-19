package com.aiope2.feature.chat.engine

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubagentCard(task: SubagentManager.SubagentTask) {
  var expanded by remember { mutableStateOf(false) }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(Color(0xFF111111))
      .clickable { if (task.stage == SubagentManager.Stage.FINISHED || task.stage == SubagentManager.Stage.ERROR) expanded = !expanded }
      .padding(horizontal = 8.dp, vertical = 5.dp),
  ) {
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Description
      Text(
        task.description,
        fontSize = 11.sp,
        color = Color(0xFF999999),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text("·", fontSize = 11.sp, color = Color(0xFF444444))
      // Stage chips
      val stages = listOf(
        SubagentManager.Stage.SEARCHING to "searching",
        SubagentManager.Stage.READING to "reading",
        SubagentManager.Stage.SUMMARIZING to "summarizing",
        SubagentManager.Stage.FINISHED to "finished",
      )
      stages.forEach { (stage, label) ->
        StageLabel(label = label, state = chipState(task.stage, stage))
      }
    }

    if (expanded && task.result.isNotBlank()) {
      Text(
        task.result.take(1500),
        fontSize = 10.sp,
        color = Color(0xFF777777),
        lineHeight = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 4.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
      )
    }
  }
}

private enum class ChipState { DONE, ACTIVE, PENDING }

private fun chipState(current: SubagentManager.Stage, target: SubagentManager.Stage): ChipState {
  if (current == SubagentManager.Stage.ERROR) return ChipState.PENDING
  val order = SubagentManager.Stage.entries
  val ci = order.indexOf(current)
  val ti = order.indexOf(target)
  return when {
    ci > ti -> ChipState.DONE
    ci == ti -> ChipState.ACTIVE
    else -> ChipState.PENDING
  }
}

@Composable
private fun StageLabel(label: String, state: ChipState) {
  val pulse = if (state == ChipState.ACTIVE && label != "finished") {
    val t = rememberInfiniteTransition(label = "p")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    a
  } else {
    1f
  }

  val color by animateColorAsState(
    when {
      label == "finished" && state == ChipState.DONE -> Color(0xFF4CAF50)
      label == "finished" && state == ChipState.ACTIVE -> Color(0xFF4CAF50)
      state == ChipState.DONE -> Color(0xFF888888)
      state == ChipState.ACTIVE -> Color(0xFFBBBBBB)
      else -> Color(0xFF444444)
    },
    label = "c",
  )

  Text(
    label,
    fontSize = 10.sp,
    fontWeight = if (state == ChipState.ACTIVE) FontWeight.Medium else FontWeight.Normal,
    color = color,
    modifier = Modifier.alpha(pulse).padding(horizontal = 2.dp),
  )
}
