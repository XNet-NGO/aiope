package com.aiope2.feature.chat.dynamicui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses aiope-ui fenced JSON blocks into [AiopeUiNode] trees.
 * Tolerant of LLM mistakes: broken keys, unclosed braces, missing fields.
 */
object AiopeUiParser {

  fun parse(raw: String): AiopeUiNode? {
    val repaired = sanitizeJson(fixBrokenKeys(raw.trim()))
    if (repaired.isBlank()) return null
    // NDJSON: multiple complete objects, one per line (not pretty-printed JSON)
    val lines = repaired.lines().map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }
    if (lines.size > 1) {
      val children = lines.mapNotNull { tryParseLine(it) }
      return if (children.isNotEmpty()) ColumnNode(children = children) else null
    }
    return try {
      parseNode(JSONObject(repaired))
    } catch (_: Exception) {
      null
    }
  }

  private fun tryParseLine(line: String): AiopeUiNode? = try {
    parseNode(JSONObject(line))
  } catch (_: Exception) {
    try {
      parseNode(JSONObject(sanitizeJson(line)))
    } catch (_: Exception) {
      null
    }
  }

  // ── Node dispatcher ──

  private fun parseNode(o: JSONObject): AiopeUiNode? = when (o.optString("type", "")) {
    "column" -> ColumnNode(o.id, o.nodeList("children"))

    "row" -> RowNode(o.id, o.nodeList("children"))

    "card" -> CardNode(o.id, o.nodeList("children"))

    "divider" -> DividerNode(o.id)

    "tabs" -> {
      // Support both {"tabs":[...]} and {"children":[{"type":"tab",...}]}
      val tabItems = o.tabList("tabs")
      if (tabItems.isNotEmpty()) {
        TabsNode(o.id, tabItems, o.optIntNull("selectedIndex"))
      } else {
        val children = o.optJSONArray("children")
        if (children != null) {
          val tabs = (0 until children.length()).mapNotNull { i ->
            val ch = children.optJSONObject(i) ?: return@mapNotNull null
            if (ch.optString("type") == "tab") {
              TabItem(ch.str("label"), ch.nodeList("children"))
            } else {
              null
            }
          }
          TabsNode(o.id, tabs, o.optIntNull("selectedIndex"))
        } else {
          TabsNode(o.id)
        }
      }
    }

    "accordion" -> AccordionNode(o.id, o.str("title"), o.nodeList("children"), o.boolNull("expanded"))

    "tab" -> ColumnNode(o.id, o.nodeList("children"))

    // standalone tab treated as column
    "text" -> {
      val styleStr = o.optString("style", "")
      val parsedStyle = textStyle(styleStr)
      // LLMs sometimes use "bold" or "italic" as style values
      val isBold = o.boolNull("bold") ?: (styleStr == "bold")
      val isItalic = o.boolNull("italic") ?: (styleStr == "italic")
      TextNode(o.id, o.str("value"), parsedStyle, isBold.takeIf { it }, isItalic.takeIf { it }, o.strNull("color"))
    }

    "image" -> ImageNode(o.id, o.str("url").ifEmpty { o.str("src") }, o.strNull("alt"))

    "code" -> CodeNode(o.id, o.str("code"), o.strNull("language"))

    "quote" -> QuoteNode(o.id, o.str("text"), o.strNull("source"))

    "icon" -> IconNode(o.id, o.str("name"), o.optIntNull("size"), o.strNull("color"))

    "badge" -> BadgeNode(o.id, o.str("value"), o.strNull("color"))

    "stat" -> StatNode(o.id, o.str("value"), o.str("label"), o.strNull("description"))

    "button" -> ButtonNode(o.id, o.str("label"), o.action("action"), buttonVariant(o.optString("variant", "")), o.boolNull("enabled"))

    "text_input" -> TextInputNode(o.str("id"), o.strNull("label"), o.strNull("placeholder"), o.strNull("value"), o.boolNull("multiline"))

    "checkbox" -> CheckboxNode(o.str("id"), o.str("label"), o.boolNull("checked"))

    "select" -> SelectNode(o.str("id"), o.strNull("label"), o.stringList("options"), o.strNull("selected"))

    "switch" -> SwitchNode(o.str("id"), o.str("label"), o.boolNull("checked"))

    "slider" -> SliderNode(o.str("id"), o.strNull("label"), o.floatNull("value"), o.floatNull("min"), o.floatNull("max"), o.floatNull("step"))

    "radio_group" -> RadioGroupNode(o.str("id"), o.strNull("label"), o.stringList("options"), o.strNull("selected"))

    "chip_group" -> ChipGroupNode(o.str("id"), o.chipList("chips"), o.optString("selection", "single"))

    "progress" -> ProgressNode(o.id, o.floatNull("value"), o.strNull("label"))

    "alert" -> AlertNode(o.id, o.str("message"), o.strNull("title"), alertSeverity(o.optString("severity", "")))

    "table" -> TableNode(o.id, o.stringList("headers"), o.tableRows("rows"))

    "list" -> ListNode(o.id, o.nodeList("items"), o.boolNull("ordered"))

    "countdown" -> CountdownNode(o.id, o.optInt("seconds", 0), o.strNull("label"), o.action("action"))

    "avatar" -> AvatarNode(o.id, o.strNull("name"), o.strNull("imageUrl"), o.optIntNull("size"))

    "box" -> BoxNode(o.id, o.nodeList("children"), o.strNull("contentAlignment"))

    "" -> inferBare(o)

    else -> null
  }

  private fun inferBare(o: JSONObject): AiopeUiNode? {
    val textKey = listOf("value", "content", "text", "title", "label").firstOrNull { o.has(it) }
    if (textKey != null) return TextNode(o.id, o.str(textKey), textStyle(o.optString("style", "")), o.boolNull("bold"), o.boolNull("italic"), o.strNull("color"))
    if (o.has("children")) return ColumnNode(o.id, o.nodeList("children"))
    return null
  }

  // ── Field readers ──

  private val JSONObject.id get() = strNull("id")
  private fun JSONObject.str(k: String) = optString(k, "")
  private fun JSONObject.strNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
  private fun JSONObject.boolNull(k: String): Boolean? = if (has(k) && !isNull(k)) optBoolean(k) else null
  private fun JSONObject.floatNull(k: String): Float? = if (has(k) && !isNull(k)) optDouble(k).let { if (it.isNaN()) null else it.toFloat() } else null
  private fun JSONObject.optIntNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k, Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it } else null

  private fun JSONObject.stringList(k: String): List<String> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it, "") }
  }

  private fun JSONObject.nodeList(k: String): List<AiopeUiNode> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
      val el = a.opt(i)
      when (el) {
        is JSONObject -> parseNode(el)
        is String -> TextNode(value = el)
        else -> null
      }
    }
  }

  private fun JSONObject.tableRows(k: String): List<List<String>> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).map { i ->
      val row = a.opt(i)
      when (row) {
        is JSONArray -> (0 until row.length()).map { row.optString(it, "") }
        is JSONObject -> row.keys().asSequence().map { row.optString(it, "") }.toList()
        else -> listOf(row?.toString() ?: "")
      }
    }
  }

  private fun JSONObject.chipList(k: String): List<ChipItem> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
      val el = a.opt(i)
      when (el) {
        is String -> ChipItem(el, el)
        is JSONObject -> ChipItem(el.str("label"), el.optString("value", el.str("label")))
        else -> null
      }
    }
  }

  private fun JSONObject.tabList(k: String): List<TabItem> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
      val el = a.opt(i)
      when (el) {
        is String -> TabItem(el)
        is JSONObject -> TabItem(el.str("label"), el.nodeList("children"))
        else -> null
      }
    }
  }

  private fun JSONObject.action(k: String): UiAction? {
    if (!has(k) || isNull(k)) return null
    val el = opt(k)
    if (el is String) return CallbackAction(event = el)
    if (el !is JSONObject) return null
    val type = el.optString("type", "")
    return when {
      type == "toggle" || el.has("targetId") -> ToggleAction(el.str("targetId"))

      type == "open_url" || (!el.has("event") && el.has("url")) -> OpenUrlAction(el.str("url"))

      type == "copy_to_clipboard" -> CopyToClipboardAction(el.str("text"))

      else -> {
        val data = el.optJSONObject("data")?.let { d -> d.keys().asSequence().associate { it to d.optString(it, "") } }
        val collect = el.optJSONArray("collectFrom")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        CallbackAction(el.str("event"), data, collect)
      }
    }
  }

  // ── Enum parsers ──

  private fun textStyle(s: String) = when (s) {
    "headline" -> TextStyle.HEADLINE
    "title" -> TextStyle.TITLE
    "body" -> TextStyle.BODY
    "caption" -> TextStyle.CAPTION
    else -> null
  }
  private fun buttonVariant(s: String) = when (s) {
    "filled" -> ButtonVariant.FILLED
    "outlined" -> ButtonVariant.OUTLINED
    "text" -> ButtonVariant.TEXT
    "tonal" -> ButtonVariant.TONAL
    else -> null
  }
  private fun alertSeverity(s: String) = when (s) {
    "info" -> AlertSeverity.INFO
    "success" -> AlertSeverity.SUCCESS
    "warning" -> AlertSeverity.WARNING
    "error" -> AlertSeverity.ERROR
    else -> null
  }

  // ── JSON repair (adapted from Kai) ──

  private val BROKEN_KEY = Regex(""""(\w+)=([{\[])""")
  private fun fixBrokenKeys(raw: String) = BROKEN_KEY.replace(raw) { "\"${it.groupValues[1]}\":${it.groupValues[2]}" }

  private fun sanitizeJson(raw: String): String {
    if (raw.isEmpty() || (raw[0] != '{' && raw[0] != '[')) return raw
    val stack = mutableListOf<Char>()
    val result = StringBuilder()
    var inString = false
    var escaped = false
    for (c in raw) {
      if (escaped) {
        escaped = false
        result.append(c)
        continue
      }
      if (c == '\\' && inString) {
        escaped = true
        result.append(c)
        continue
      }
      if (c == '"') {
        inString = !inString
        result.append(c)
        continue
      }
      if (inString) {
        result.append(c)
        continue
      }
      when (c) {
        '{', '[' -> {
          stack.add(c)
          result.append(c)
        }

        '}' -> if (stack.isNotEmpty() && stack.last() == '{') {
          stack.removeAt(stack.lastIndex)
          result.append(c)
        }

        ']' -> if (stack.isNotEmpty() && stack.last() == '[') {
          stack.removeAt(stack.lastIndex)
          result.append(c)
        }

        else -> result.append(c)
      }
      if (stack.isEmpty()) return result.toString()
    }
    // Close unclosed structures
    var s = result.toString().trimEnd()
    while (s.isNotEmpty() && s.last() in listOf(',', ':')) s = s.dropLast(1).trimEnd()
    return buildString {
      append(s)
      for (i in stack.indices.reversed()) append(if (stack[i] == '{') '}' else ']')
    }
  }
}
