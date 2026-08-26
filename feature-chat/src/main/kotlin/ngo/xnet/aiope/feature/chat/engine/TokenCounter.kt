package ngo.xnet.aiope.feature.chat.engine

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/** Token counter using jtokkit BPE encodings. Thread-safe, lazy-loaded. */
object TokenCounter {
  private val registry by lazy { Encodings.newLazyEncodingRegistry() }
  private val cl100k by lazy { registry.getEncoding(EncodingType.CL100K_BASE) }
  private val o200k by lazy { registry.getEncoding(EncodingType.O200K_BASE) }

  /** Count tokens for a given text and model ID. */
  fun count(text: String, modelId: String = ""): Int {
    if (text.isEmpty()) return 0
    val enc = if (isO200kModel(modelId)) o200k else cl100k
    return enc.countTokens(text)
  }

  /** Count tokens for a list of chat messages (adds ~4 tokens overhead per message). */
  fun countMessages(messages: List<Pair<String, String>>, modelId: String = ""): Int {
    val enc = if (isO200kModel(modelId)) o200k else cl100k
    var total = 3 // every reply is primed with <|start|>assistant<|message|>
    for ((role, content) in messages) {
      total += 4 // <|start|>{role}<|sep|>...
      total += enc.countTokens(role) + enc.countTokens(content)
    }
    return total
  }

  private fun isO200kModel(modelId: String): Boolean {
    val m = modelId.lowercase()
    return m.contains("gpt-4o") || m.contains("o1") || m.contains("o3") || m.contains("o4")
  }
}
