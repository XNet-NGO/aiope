package ngo.xnet.aiope.feature.chat.settings

/**
 * Typed router destinations for the settings/home screen tree.
 * Replaces the previous string-state routing ("list", "agent", "tools", ...) with a sealed class
 * so every destination is exhaustively checked by the compiler. The set of destinations and their
 * wiring mirrors the original string states one-to-one, plus Home-only entries (Scanner,
 * FileServer) and the Home root itself.
 */
internal sealed class CuoDestination {
  /** Root settings menu (ProfileList). */
  data object List : CuoDestination()

  /** Agent behavior / system prompt editor. */
  data object Agent : CuoDestination()

  /** Default models per task. */
  data object Tasks : CuoDestination()

  /** Per-tool enable/disable toggles. */
  data object Tools : CuoDestination()

  /** MCP tool servers. */
  data object Mcp : CuoDestination()

  /** Remote SSH servers (rendered via the serversContent passthrough). */
  data object Servers : CuoDestination()

  /** Voice / speech settings. */
  data object Voice : CuoDestination()

  /** Theme customization. */
  data object Theme : CuoDestination()

  /** Installed API providers list. */
  data object Providers : CuoDestination()

  /** RAG document knowledge base. */
  data object Rag : CuoDestination()

  /** Builtin provider template picker. */
  data object Pick : CuoDestination()

  /** Provider editor for an existing profile id. */
  data class Edit(val profileId: String) : CuoDestination()

  /** Network scanner (reachable from CuO Home). */
  data object Scanner : CuoDestination()

  /** File server (reachable from CuO Home). */
  data object FileServer : CuoDestination()

  /** CuO Home dashboard root. */
  data object Home : CuoDestination()
}
