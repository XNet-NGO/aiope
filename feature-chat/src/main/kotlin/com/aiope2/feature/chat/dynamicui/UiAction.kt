package com.aiope2.feature.chat.dynamicui

/** Actions that buttons/countdowns can trigger. */
sealed interface UiAction

data class CallbackAction(val event: String = "", val data: Map<String, String>? = null, val collectFrom: List<String>? = null) : UiAction

data class ToggleAction(val targetId: String = "") : UiAction
data class OpenUrlAction(val url: String = "") : UiAction
data class CopyToClipboardAction(val text: String = "") : UiAction
