package com.aiope2.feature.chat.dynamicui

/** Sealed node hierarchy for aiope-ui JSON blocks. */
sealed interface AiopeUiNode {
  val id: String?
}

// ── Layout ──
data class ColumnNode(override val id: String? = null, val children: List<AiopeUiNode> = emptyList()) : AiopeUiNode
data class RowNode(override val id: String? = null, val children: List<AiopeUiNode> = emptyList()) : AiopeUiNode
data class CardNode(override val id: String? = null, val children: List<AiopeUiNode> = emptyList()) : AiopeUiNode
data class DividerNode(override val id: String? = null) : AiopeUiNode
data class TabsNode(override val id: String? = null, val tabs: List<TabItem> = emptyList(), val selectedIndex: Int? = null) : AiopeUiNode
data class TabItem(val label: String = "", val children: List<AiopeUiNode> = emptyList())
data class AccordionNode(override val id: String? = null, val title: String = "", val children: List<AiopeUiNode> = emptyList(), val expanded: Boolean? = null) : AiopeUiNode

// ── Content ──
data class TextNode(override val id: String? = null, val value: String = "", val style: TextStyle? = null, val bold: Boolean? = null, val italic: Boolean? = null, val color: String? = null) : AiopeUiNode
enum class TextStyle { HEADLINE, TITLE, BODY, CAPTION }
data class ImageNode(override val id: String? = null, val url: String = "", val alt: String? = null) : AiopeUiNode
data class CodeNode(override val id: String? = null, val code: String = "", val language: String? = null) : AiopeUiNode
data class QuoteNode(override val id: String? = null, val text: String = "", val source: String? = null) : AiopeUiNode
data class IconNode(override val id: String? = null, val name: String = "", val size: Int? = null, val color: String? = null) : AiopeUiNode
data class BadgeNode(override val id: String? = null, val value: String = "", val color: String? = null) : AiopeUiNode
data class StatNode(override val id: String? = null, val value: String = "", val label: String = "", val description: String? = null) : AiopeUiNode

// ── Interactive ──
data class ButtonNode(override val id: String? = null, val label: String = "", val action: UiAction? = null, val variant: ButtonVariant? = null, val enabled: Boolean? = null) : AiopeUiNode
enum class ButtonVariant { FILLED, OUTLINED, TEXT, TONAL }
data class TextInputNode(override val id: String = "", val label: String? = null, val placeholder: String? = null, val value: String? = null, val multiline: Boolean? = null) : AiopeUiNode
data class CheckboxNode(override val id: String = "", val label: String = "", val checked: Boolean? = null) : AiopeUiNode
data class SelectNode(override val id: String = "", val label: String? = null, val options: List<String> = emptyList(), val selected: String? = null) : AiopeUiNode
data class SwitchNode(override val id: String = "", val label: String = "", val checked: Boolean? = null) : AiopeUiNode
data class SliderNode(override val id: String = "", val label: String? = null, val value: Float? = null, val min: Float? = null, val max: Float? = null, val step: Float? = null) : AiopeUiNode
data class RadioGroupNode(override val id: String = "", val label: String? = null, val options: List<String> = emptyList(), val selected: String? = null) : AiopeUiNode
data class ChipGroupNode(override val id: String = "", val chips: List<ChipItem> = emptyList(), val selection: String = "single") : AiopeUiNode
data class ChipItem(val label: String = "", val value: String = "")

// ── Feedback ──
data class ProgressNode(override val id: String? = null, val value: Float? = null, val label: String? = null) : AiopeUiNode
data class AlertNode(override val id: String? = null, val message: String = "", val title: String? = null, val severity: AlertSeverity? = null) : AiopeUiNode
enum class AlertSeverity { INFO, SUCCESS, WARNING, ERROR }

// ── Data ──
data class TableNode(override val id: String? = null, val headers: List<String> = emptyList(), val rows: List<List<String>> = emptyList()) : AiopeUiNode
data class ListNode(override val id: String? = null, val items: List<AiopeUiNode> = emptyList(), val ordered: Boolean? = null) : AiopeUiNode

// ── Additional ──
data class CountdownNode(override val id: String? = null, val seconds: Int = 0, val label: String? = null, val action: UiAction? = null) : AiopeUiNode
data class AvatarNode(override val id: String? = null, val name: String? = null, val imageUrl: String? = null, val size: Int? = null) : AiopeUiNode
data class BoxNode(override val id: String? = null, val children: List<AiopeUiNode> = emptyList(), val contentAlignment: String? = null) : AiopeUiNode

// ── Frozen submission state ──
data class FrozenSubmission(val values: Map<String, String> = emptyMap(), val pressedEvent: String? = null, val isPending: Boolean = false)
