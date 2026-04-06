package com.aiope2.feature.chat

import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun MessageBubble(message: ChatMessage) {
  val isUser = message.role == Role.USER
  val alignment = if (isUser) Arrangement.End else Arrangement.Start
  val bgColor = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
  val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurface
  val textColorArgb = textColor.toArgb()
  val ctx = LocalContext.current
  val markwon = remember { Markwon.create(ctx) }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = alignment) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = bgColor,
      modifier = Modifier.widthIn(max = 300.dp)
    ) {
      if (isUser) {
        SelectionContainer {
          Text(
            text = message.content,
            color = textColor,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
          )
        }
      } else {
        // Markdown for assistant messages
        AndroidView(
          factory = { context ->
            TextView(context).apply {
              setTextColor(textColorArgb)
              textSize = 14f
              setTextIsSelectable(true)
              setPadding(32, 24, 32, 24)
            }
          },
          update = { tv ->
            markwon.setMarkdown(tv, message.content)
          },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}
