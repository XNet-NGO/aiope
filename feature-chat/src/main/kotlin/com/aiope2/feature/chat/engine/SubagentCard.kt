package com.aiope2.feature.chat.engine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubagentCard(task: SubagentManager.SubagentTask) {
  var expanded by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(10.dp)
  val bgColor = when (task.stage) {
    SubagentManager.Stage.FINISHED -> Color(0xFF1A2E1A)
    SubagentManager.Stage.ERROR -> Color(0xFF2E1A1A)
    else -> Color(0xFF1A1A2E)
  }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
      .clip(shape)
      .background(bgColor, shape)
      .clickable { if (task.stage == SubagentManager.Stage.FINISHED || task.stage == SubagentManager.Stage.ERROR) expanded = !expanded }
      .padding(10.dp),
  ) {
    // Header: icon + description
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      val icon = when (task.stage) {
        SubagentManager.Stage.FINISHED -> "✅"
        SubagentManager.Stage.ERROR -> "❌"
        else -> "🔍"
      }
      Text(icon, fontSize = 14.sp)
      Text(
        task.description,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }

    // Stage indicators
    Row(
      Modifier.padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val stages = listOf(
        SubagentManager.Stage.SEARCHING to "Searching",
        SubagentManager.Stage.READING to "Reading",
        SubagentManager.Stage.SUMMARIZING to "Summarizing",
        SubagentManager.Stage.FINISHED to "Finished",
      )
      stages.forEach { (stage, label) ->
        StageChip(label = label, state = chipState(task.stage, stage))
        if (stage != SubagentManager.Stage.FINISHED) {
          Text("→", fontSize = 9.sp, color = Color(0xFF555555))
        }
      }
    }

    // Expanded result
    AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically()) {
      val text = task.error ?: task.result
      if (text.isNotBlank()) {
        Text(
          text.take(2000),
          fontSize = 11.sp,
          color = Color(0xFFBBBBBB),
          modifier = Modifier.padding(top = 8.dp),
          lineHeight = 15.sp,
        )
      }
    }
  }
}

private enum class ChipState { DONE, ACTIVE, PENDING }

private fun chipState(current: SubagentManager.Stage, target: SubagentManager.Stage): ChipState {
  val order = SubagentManager.Stage.entries
  val ci = order.indexOf(current)
  val ti = order.indexOf(target)
  return when {
    current == SubagentManager.Stage.ERROR -> if (ti <= ci) ChipState.DONE else ChipState.PENDING
    ci > ti -> ChipState.DONE
    ci == ti -> ChipState.ACTIVE
    else -> ChipState.PENDING
  }
}

@Composable
private fun StageChip(label: String, state: ChipState) {
  val pulse = if (state == ChipState.ACTIVE) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
      initialValue = 0.5f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
      label = "alpha",
    )
    alpha
  } else {
    1f
  }

  val bg by animateColorAsState(
    when (state) {
      ChipState.DONE -> Color(0xFF2E7D32)
      ChipState.ACTIVE -> Color(0xFF1565C0)
      ChipState.PENDING -> Color(0xFF333333)
    },
    label = "chipBg",
  )
  val textColor = when (state) {
    ChipState.PENDING -> Color(0xFF777777)
    else -> Color.White
  }

  Text(
    label,
    fontSize = 9.sp,
    color = textColor,
    modifier = Modifier
      .alpha(pulse)
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}
