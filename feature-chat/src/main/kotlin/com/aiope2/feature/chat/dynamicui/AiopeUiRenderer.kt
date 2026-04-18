@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.aiope2.feature.chat.dynamicui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Theme colors ──
private val Cyan = Color(0xFF00E5FF)
private val CardBg = Color(0xFF1A1A1A)
private val DividerColor = Color(0xFF333333)

@Composable
fun AiopeUiRenderer(
  node: AiopeUiNode,
  isInteractive: Boolean = true,
  onCallback: (event: String, data: Map<String, String>) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
  frozen: FrozenSubmission? = null,
) {
  val formState = remember { mutableStateMapOf<String, String>() }
  val toggleState = remember { mutableStateMapOf<String, Boolean>() }
  LaunchedEffect(node, frozen?.values) {
    initForm(node, formState)
    frozen?.values?.let { formState.putAll(it) }
  }
  CompositionLocalProvider(LocalFrozenSubmission provides frozen) {
    Surface(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      color = CardBg,
      tonalElevation = 2.dp,
    ) {
      Column(Modifier.padding(14.dp)) {
        Render(node, isInteractive, formState, toggleState, onCallback)
      }
    }
  }
}

private val LocalFrozenSubmission = compositionLocalOf<FrozenSubmission?> { null }

private const val MAX_DEPTH = 10

@Composable
private fun Render(
  node: AiopeUiNode,
  interactive: Boolean,
  form: MutableMap<String, String>,
  toggle: MutableMap<String, Boolean>,
  cb: (String, Map<String, String>) -> Unit,
  depth: Int = 0,
) {
  if (depth > MAX_DEPTH) return
  node.id?.let { if (toggle[it] == false) return }
  when (node) {
    is ColumnNode -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } }

    is RowNode -> {
      val allStats = node.children.isNotEmpty() && node.children.all { it is StatNode }
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = if (allStats) Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } }
    }

    is CardNode -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFF222222), tonalElevation = 1.dp) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } } }

    is DividerNode -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DividerColor)

    is TextNode -> RenderText(node)

    is ButtonNode -> RenderButton(node, interactive, form, toggle, cb)

    is TextInputNode -> RenderTextInput(node, interactive, form)

    is CheckboxNode -> RenderCheckbox(node, interactive, form)

    is SelectNode -> RenderSelect(node, interactive, form)

    is SwitchNode -> RenderSwitch(node, interactive, form)

    is SliderNode -> RenderSlider(node, interactive, form)

    is RadioGroupNode -> RenderRadioGroup(node, interactive, form)

    is ChipGroupNode -> RenderChipGroup(node, interactive, form)

    is ProgressNode -> RenderProgress(node)

    is AlertNode -> RenderAlert(node)

    is TableNode -> RenderTable(node)

    is ListNode -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      node.items.forEachIndexed { i, item ->
        Row {
          Text(if (node.ordered == true) "${i + 1}. " else "\u2022 ", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF9E9E9E))
          Column(Modifier.weight(1f)) { Render(item, interactive, form, toggle, cb, depth + 1) }
        }
      }
    }

    is ImageNode -> {
      val ctx = androidx.compose.ui.platform.LocalContext.current
      coil.compose.AsyncImage(
        model = node.url,
        contentDescription = node.alt,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = {}, onLongClick = {
          kotlin.concurrent.thread {
            try {
              val bmp = java.net.URL(node.url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
              if (bmp != null) com.aiope2.feature.chat.saveImageToGallery(ctx, bmp)
            } catch (_: Exception) {}
          }
        }),
      )
    }

    is CodeNode -> RenderCode(node)

    is QuoteNode -> RenderQuote(node)

    is IconNode -> resolveIcon(node.name)?.let { Icon(it, node.name, Modifier.size((node.size ?: 24).dp), tint = resolveColor(node.color)) }

    is BadgeNode -> Surface(color = resolveColor(node.color).takeIf { it != Color.Unspecified } ?: Cyan, shape = RoundedCornerShape(12.dp)) { Text(node.value, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold) }

    is StatNode -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 72.dp)) {
      Text(node.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Cyan)
      Text(node.label, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9E9E9E))
      node.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF757575)) }
    }

    is TabsNode -> RenderTabs(node, interactive, form, toggle, cb, depth)

    is AccordionNode -> RenderAccordion(node, interactive, form, toggle, cb, depth)

    is CountdownNode -> RenderCountdown(node, interactive, form, toggle, cb)

    is AvatarNode -> RenderAvatar(node)

    is BoxNode -> if (node.children.size <= 1 && node.contentAlignment != null) {
      val align = when (node.contentAlignment) {
        "center" -> Alignment.Center
        "top_center" -> Alignment.TopCenter
        "bottom_center" -> Alignment.BottomCenter
        else -> Alignment.TopStart
      }
      Box(Modifier.fillMaxWidth(), contentAlignment = align) { node.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } }
    } else {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } }
    }
  }
}

// ── Text with full style support ──

@Composable private fun RenderText(n: TextNode) {
  val baseStyle = when (n.style) {
    TextStyle.HEADLINE -> MaterialTheme.typography.headlineSmall
    TextStyle.TITLE -> MaterialTheme.typography.titleMedium
    TextStyle.CAPTION -> MaterialTheme.typography.bodySmall
    else -> MaterialTheme.typography.bodyLarge
  }
  val color = resolveColor(n.color).takeIf { it != Color.Unspecified } ?: when (n.style) {
    TextStyle.HEADLINE -> Cyan
    TextStyle.CAPTION -> Color(0xFF9E9E9E)
    else -> Color(0xFFE0E0E0)
  }
  // Handle "bold" and "italic" as style names from LLMs
  val isBold = n.bold == true || n.value.startsWith("**")
  val isItalic = n.italic == true
  Text(
    text = n.value.replace("**", ""),
    style = baseStyle,
    color = color,
    fontWeight = if (isBold) FontWeight.Bold else null,
    fontStyle = if (isItalic) FontStyle.Italic else null,
    lineHeight = 22.sp,
  )
}

// ── Button ──

@Composable private fun RenderButton(n: ButtonNode, interactive: Boolean, form: Map<String, String>, toggle: MutableMap<String, Boolean>, cb: (String, Map<String, String>) -> Unit) {
  val uri = LocalUriHandler.current
  val clip = LocalClipboardManager.current
  var clicked by remember { mutableStateOf(false) }
  LaunchedEffect(interactive) { if (interactive) clicked = false }
  val frozen = LocalFrozenSubmission.current
  val isPressedSnapshot = !interactive && frozen?.pressedEvent != null && run {
    val a = n.action as? CallbackAction ?: return@run false
    a.event == frozen.pressedEvent && collectFormData(a, form) == frozen.values
  }
  val showPulse = (clicked && !interactive) || (isPressedSnapshot && frozen.isPending)
  val enabled = interactive && n.enabled != false
  val onClick: () -> Unit = {
    when (val a = n.action) {
      is CallbackAction -> {
        clicked = true
        cb(a.event, collectFormData(a, form))
      }

      is ToggleAction -> toggle[a.targetId] = !(toggle[a.targetId] ?: true)

      is OpenUrlAction -> uri.openUri(a.url)

      is CopyToClipboardAction -> clip.setText(AnnotatedString(a.text))

      null -> {}
    }
  }
  val pulseMod = if (showPulse) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(0.96f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    val alpha by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
  } else {
    Modifier
  }
  if (isPressedSnapshot) {
    Button(onClick = {}, enabled = false, modifier = pulseMod, colors = ButtonDefaults.buttonColors(disabledContainerColor = Cyan, disabledContentColor = Color.Black)) { Text(n.label.ifEmpty { "Action" }, fontWeight = FontWeight.Bold) }
    return
  }
  when (n.variant) {
    ButtonVariant.OUTLINED -> OutlinedButton(onClick, enabled = enabled, modifier = pulseMod) { Text(n.label.ifEmpty { "Action" }) }
    ButtonVariant.TEXT -> TextButton(onClick, enabled = enabled, modifier = pulseMod) { Text(n.label.ifEmpty { "Action" }) }
    ButtonVariant.TONAL -> FilledTonalButton(onClick, enabled = enabled, modifier = pulseMod) { Text(n.label.ifEmpty { "Action" }) }
    else -> Button(onClick, enabled = enabled, modifier = pulseMod, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)) { Text(n.label.ifEmpty { "Action" }, fontWeight = FontWeight.Bold) }
  }
}

private fun collectFormData(action: CallbackAction, form: Map<String, String>): Map<String, String> {
  val collected = mutableMapOf<String, String>()
  action.data?.let { collected.putAll(it) }
  action.collectFrom?.forEach { id -> form[id]?.let { collected[id] = it } }
  return collected
}

// ── Form controls ──

@Composable private fun RenderTextInput(n: TextInputNode, interactive: Boolean, form: MutableMap<String, String>) {
  OutlinedTextField(value = form[n.id] ?: "", onValueChange = { form[n.id] = it }, label = n.label?.let { { Text(it) } }, placeholder = n.placeholder?.let { { Text(it) } }, enabled = interactive, singleLine = n.multiline != true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
}

@Composable private fun RenderCheckbox(n: CheckboxNode, interactive: Boolean, form: MutableMap<String, String>) {
  val checked = form[n.id]?.toBooleanStrictOrNull() ?: false
  Row(verticalAlignment = Alignment.CenterVertically, modifier = if (interactive) Modifier.clickable { form[n.id] = (!checked).toString() } else Modifier) {
    Checkbox(checked = checked, onCheckedChange = null, enabled = interactive, colors = CheckboxDefaults.colors(checkedColor = Cyan))
    Text(n.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE0E0E0))
  }
}

@Composable private fun RenderSelect(n: SelectNode, interactive: Boolean, form: MutableMap<String, String>) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (interactive) expanded = it }) {
    OutlinedTextField(value = form[n.id] ?: "", onValueChange = {}, readOnly = true, label = n.label?.let { { Text(it) } }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, enabled = interactive, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
    ExposedDropdownMenu(expanded, { expanded = false }) {
      n.options.forEach { opt ->
        DropdownMenuItem(text = { Text(opt) }, onClick = {
          form[n.id] = opt
          expanded = false
        })
      }
    }
  }
}

@Composable private fun RenderSwitch(n: SwitchNode, interactive: Boolean, form: MutableMap<String, String>) {
  val checked = form[n.id]?.toBooleanStrictOrNull() ?: false
  Row(Modifier.fillMaxWidth().then(if (interactive) Modifier.clickable { form[n.id] = (!checked).toString() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
    Text(n.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE0E0E0))
    Switch(checked = checked, onCheckedChange = null, enabled = interactive, colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
  }
}

@Composable private fun RenderSlider(n: SliderNode, interactive: Boolean, form: MutableMap<String, String>) {
  val min = n.min ?: 0f
  val max = n.max ?: 100f
  val cur = form[n.id]?.toFloatOrNull() ?: (n.value ?: min)
  Column(Modifier.fillMaxWidth()) {
    n.label?.let {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(it, color = Color(0xFFE0E0E0))
        Text(if (cur == cur.toLong().toFloat()) cur.toLong().toString() else "%.1f".format(cur), color = Cyan)
      }
    }
    Slider(value = cur.coerceIn(min, max), onValueChange = { form[n.id] = if (it == it.toLong().toFloat()) it.toLong().toString() else "%.1f".format(it) }, valueRange = min..max, enabled = interactive, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan))
  }
}

@Composable private fun RenderRadioGroup(n: RadioGroupNode, interactive: Boolean, form: MutableMap<String, String>) {
  val sel = form[n.id] ?: ""
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    n.label?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Cyan) }
    n.options.forEach { opt ->
      Row(Modifier.fillMaxWidth().then(if (interactive) Modifier.clickable { form[n.id] = opt } else Modifier), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = sel == opt, onClick = null, enabled = interactive, colors = RadioButtonDefaults.colors(selectedColor = Cyan))
        Text(opt, Modifier.padding(start = 8.dp), color = Color(0xFFE0E0E0))
      }
    }
  }
}

@Composable private fun RenderChipGroup(n: ChipGroupNode, interactive: Boolean, form: MutableMap<String, String>) {
  val isMulti = n.selection == "multi"
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    n.chips.forEach { chip ->
      val v = chip.value.ifEmpty { chip.label }
      val selected = (form[n.id] ?: "").split(",").contains(v)
      FilterChip(selected = selected, onClick = {
        if (!interactive) return@FilterChip
        val cur = (form[n.id] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        form[n.id] = (
          if (isMulti) {
            if (selected) cur - v else cur + v
          } else {
            if (selected) emptySet() else setOf(v)
          }
          ).joinToString(",")
      }, label = { Text(chip.label) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
    }
  }
}

// ── Feedback ──

@Composable private fun RenderProgress(n: ProgressNode) {
  Column(Modifier.fillMaxWidth()) {
    n.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0), modifier = Modifier.padding(bottom = 4.dp)) }
    if (n.value != null) {
      LinearProgressIndicator(progress = { n.value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Cyan, trackColor = Color(0xFF333333))
    } else {
      LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan, trackColor = Color(0xFF333333))
    }
  }
}

@Composable private fun RenderAlert(n: AlertNode) {
  val (bg, fg) = when (n.severity) {
    AlertSeverity.SUCCESS -> Color(0xFF1B3A1B) to Color(0xFFC8E6C9)
    AlertSeverity.WARNING -> Color(0xFF3D2600) to Color(0xFFFF9100)
    AlertSeverity.ERROR -> Color(0xFF3B1010) to Color(0xFFCF6679)
    else -> Color(0xFF0D2B3E) to Cyan
  }
  Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      val icon = when (n.severity) {
        AlertSeverity.SUCCESS -> Icons.Default.CheckCircle
        AlertSeverity.WARNING -> Icons.Default.Warning
        AlertSeverity.ERROR -> Icons.Default.Close
        else -> Icons.Default.Info
      }
      Icon(icon, null, Modifier.size(20.dp))
      Spacer(Modifier.width(12.dp))
      Column {
        n.title?.let { Text(it, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
        Text(n.message, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

// ── Data ──

@Composable private fun RenderTable(n: TableNode) {
  val cols = maxOf(n.headers.size, n.rows.maxOfOrNull { it.size } ?: 0)
  if (cols == 0) return
  Column(Modifier.fillMaxWidth()) {
    if (n.headers.isNotEmpty()) {
      Surface(color = Color(0xFF222222), shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) { (0 until cols).forEach { Text(n.headers.getOrElse(it) { "" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Cyan, fontWeight = FontWeight.Bold) } }
      }
      HorizontalDivider(color = DividerColor)
    }
    n.rows.forEachIndexed { idx, row ->
      val rowBg = if (idx % 2 == 0) Color.Transparent else Color(0xFF1A1A1A)
      Row(Modifier.fillMaxWidth().background(rowBg).padding(horizontal = 10.dp, vertical = 6.dp)) { (0 until cols).forEach { Text(row.getOrElse(it) { "" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0)) } }
    }
  }
}

@Composable private fun RenderCode(n: CodeNode) {
  val clip = LocalClipboardManager.current
  Surface(color = Color(0xFF111111), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
    Box(Modifier.padding(12.dp)) {
      Column {
        n.language?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Cyan.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 4.dp)) }
        Text(n.code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = Color(0xFFE0E0E0), modifier = Modifier.horizontalScroll(rememberScrollState()))
      }
      Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(RoundedCornerShape(6.dp)).clickable { clip.setText(AnnotatedString(n.code)) }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp), tint = Color(0xFF757575)) }
    }
  }
}

@Composable private fun RenderQuote(n: QuoteNode) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp)))
    Spacer(Modifier.width(12.dp))
    Column {
      Text(n.text, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface)
      if (n.source != null) {
        Spacer(Modifier.height(2.dp))
        Text("— ${n.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

// ── Tabs ──

@Composable private fun RenderTabs(n: TabsNode, interactive: Boolean, form: MutableMap<String, String>, toggle: MutableMap<String, Boolean>, cb: (String, Map<String, String>) -> Unit, depth: Int) {
  if (n.tabs.isEmpty()) return
  var sel by remember { mutableIntStateOf((n.selectedIndex ?: 0).coerceIn(0, n.tabs.lastIndex)) }
  val pillShape = RoundedCornerShape(50)
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      Row(Modifier.clip(pillShape).background(Color(0xFF222222), pillShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        n.tabs.forEachIndexed { i, tab ->
          Box(Modifier.height(32.dp).clip(pillShape).then(if (sel == i) Modifier.background(Cyan.copy(alpha = 0.15f), pillShape) else Modifier).clickable { sel = i }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(tab.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel == i) FontWeight.SemiBold else FontWeight.Normal, color = if (sel == i) Cyan else Color(0xFF9E9E9E), maxLines = 1)
          }
        }
      }
    }
    n.tabs.getOrNull(sel)?.let { tab -> Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { tab.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } } }
  }
}

// ── Accordion ──

@Composable private fun RenderAccordion(n: AccordionNode, interactive: Boolean, form: MutableMap<String, String>, toggle: MutableMap<String, Boolean>, cb: (String, Map<String, String>) -> Unit, depth: Int) {
  var expanded by remember { mutableStateOf(true) }
  Surface(onClick = { expanded = !expanded }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Color(0xFF222222)) {
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Color(0xFFE0E0E0))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF757575))
      }
      AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) { Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { n.children.forEach { Render(it, interactive, form, toggle, cb, depth + 1) } } }
    }
  }
}

// ── Helpers ──

@Composable private fun RenderCountdown(n: CountdownNode, interactive: Boolean, form: MutableMap<String, String>, toggle: MutableMap<String, Boolean>, cb: (String, Map<String, String>) -> Unit) {
  val targetMs = remember { System.currentTimeMillis() + n.seconds * 1000L }
  var remaining by remember { mutableLongStateOf(n.seconds.toLong()) }
  var expired by remember { mutableStateOf(false) }
  LaunchedEffect(targetMs) {
    while (true) {
      val diff = (targetMs - System.currentTimeMillis()) / 1000L
      remaining = diff.coerceAtLeast(0L)
      if (diff <= 0L) {
        if (!expired) {
          expired = true
          (n.action as? CallbackAction)?.let { cb(it.event, collectFormData(it, form)) }
        }
        break
      }
      kotlinx.coroutines.delay(1000L)
    }
  }
  Column(Modifier.fillMaxWidth()) {
    n.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0)) }
    val m = remaining / 60
    val s = remaining % 60
    Text("${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}", style = MaterialTheme.typography.headlineMedium, color = if (expired) MaterialTheme.colorScheme.error else Cyan)
  }
}

@Composable private fun RenderAvatar(n: AvatarNode) {
  val sizeDp = (n.size ?: 40).coerceIn(24, 80).dp
  if (n.imageUrl != null) {
    Surface(shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(sizeDp)) {
      coil.compose.AsyncImage(model = n.imageUrl, contentDescription = n.name, modifier = Modifier.size(sizeDp))
    }
  } else {
    val initials = (n.name ?: "?").split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Surface(color = Cyan.copy(alpha = 0.2f), shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(sizeDp)) {
      Box(Modifier.size(sizeDp), contentAlignment = Alignment.Center) { Text(initials, fontWeight = FontWeight.Bold, color = Cyan) }
    }
  }
}

private fun initForm(node: AiopeUiNode, form: MutableMap<String, String>) {
  when (node) {
    is TextInputNode -> node.value?.let { if (node.id !in form) form[node.id] = it }
    is CheckboxNode -> if (node.id !in form) form[node.id] = (node.checked ?: false).toString()
    is SwitchNode -> if (node.id !in form) form[node.id] = (node.checked ?: false).toString()
    is SelectNode -> node.selected?.let { if (node.id !in form) form[node.id] = it }
    is SliderNode -> if (node.id !in form) form[node.id] = (node.value ?: node.min ?: 0f).toString()
    is RadioGroupNode -> node.selected?.let { if (node.id !in form) form[node.id] = it }
    is ChipGroupNode -> if (node.id !in form) form[node.id] = ""
    is ColumnNode -> node.children.forEach { initForm(it, form) }
    is RowNode -> node.children.forEach { initForm(it, form) }
    is CardNode -> node.children.forEach { initForm(it, form) }
    is ListNode -> node.items.forEach { initForm(it, form) }
    is TabsNode -> node.tabs.forEach { t -> t.children.forEach { initForm(it, form) } }
    is AccordionNode -> node.children.forEach { initForm(it, form) }
    else -> {}
  }
}

private fun resolveColor(name: String?): Color = when (name) {
  "primary", "cyan" -> Cyan
  "secondary", "gray", "grey" -> Color(0xFF9E9E9E)
  "error", "red" -> Color(0xFFCF6679)
  "success", "green" -> Color(0xFF81C784)
  "warning", "orange", "amber" -> Color(0xFFFF9100)
  "violet", "purple" -> Color(0xFFBB86FC)
  "blue" -> Color(0xFF64B5F6)
  "yellow" -> Color(0xFFFFD54F)
  "white" -> Color(0xFFE0E0E0)
  else -> Color.Unspecified
}

private fun resolveIcon(name: String): ImageVector? = when (name) {
  "home" -> Icons.Default.Home
  "settings" -> Icons.Default.Settings
  "search" -> Icons.Default.Search
  "add" -> Icons.Default.Add
  "delete" -> Icons.Default.Delete
  "edit" -> Icons.Default.Edit
  "check", "done" -> Icons.Default.Check
  "check_circle" -> Icons.Default.CheckCircle
  "close" -> Icons.Default.Close
  "star" -> Icons.Default.Star
  "favorite" -> Icons.Default.Favorite
  "share" -> Icons.Default.Share
  "info" -> Icons.Default.Info
  "warning" -> Icons.Default.Warning
  "person" -> Icons.Default.Person
  "email" -> Icons.Default.Email
  "phone" -> Icons.Default.Call
  "location" -> Icons.Default.LocationOn
  "refresh" -> Icons.Default.Refresh
  "download" -> Icons.Default.Download
  "upload" -> Icons.Default.Upload
  "code" -> Icons.Default.Code
  "build" -> Icons.Default.Build
  "lock" -> Icons.Default.Lock
  "play" -> Icons.Default.PlayArrow
  "pause" -> Icons.Default.Pause
  "stop" -> Icons.Default.Stop
  "cloud" -> Icons.Default.Cloud
  "bolt" -> Icons.Default.Bolt
  "link" -> Icons.Default.Link
  "copy" -> Icons.Default.ContentCopy
  "notifications" -> Icons.Default.Notifications
  "music", "music_note" -> Icons.Default.MusicNote
  "image" -> Icons.Default.Image
  else -> null
}
