package ngo.xnet.aiope.feature.chat.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [StreamingOrchestrator.drainThinkTags], the inline chain-of-thought
 * splitter for models (e.g. minimax) that wrap reasoning in <think>/<thinking>/<thought> tags.
 *
 * The bug these guard against: raw <think>...</think> blocks rendering inside the chat answer.
 * The old single-pass parser handled one tag transition per delta and dumped everything after a
 * close tag straight into visible content, so a *following* <think> block (common when the model
 * interleaves "answer text <think>more reasoning</think>") leaked verbatim into the message body.
 */
class ThinkTagParserTest {

  /** Feed [content] through the drainer in one shot and return (visibleContent, reasoning). */
  private fun drainWhole(content: String): Pair<String, String> {
    val state = StreamingOrchestrator.Companion.ThinkTagState()
    val buf = StringBuilder()
    return StreamingOrchestrator.drainThinkTags(content, state, buf)
  }

  /** Feed [content] one character at a time (worst-case SSE token splitting). */
  private fun drainCharByChar(content: String): Pair<String, String> {
    val state = StreamingOrchestrator.Companion.ThinkTagState()
    val buf = StringBuilder()
    val c = StringBuilder()
    val r = StringBuilder()
    for (ch in content) {
      val (co, ro) = StreamingOrchestrator.drainThinkTags(ch.toString(), state, buf)
      c.append(co); r.append(ro)
    }
    return c.toString() to r.toString()
  }

  @Test
  fun singleThinkBlockIsAllReasoning() {
    val (content, reasoning) = drainWhole("<think>figuring it out</think>")
    assertEquals("", content)
    assertEquals("figuring it out", reasoning)
  }

  @Test
  fun contentThenThinkThenContent_doesNotLeakTags() {
    val (content, reasoning) = drainWhole("Here is the answer.<think>second thought</think> Done.")
    assertEquals("Here is the answer. Done.", content)
    assertEquals("second thought", reasoning)
  }

  /** The exact shape from the bug report: text, then a <think> block mid-answer. */
  @Test
  fun multipleInterleavedThinkBlocks_allRoutedToReasoning_noLeak() {
    val input =
      "<think>first</think>No direct match. Let me try angles.<think>second thought</think>" +
        "The manual doesn't document that.<think>third</think>"
    val (content, reasoning) = drainWhole(input)
    assertEquals("No direct match. Let me try angles.The manual doesn't document that.", content)
    assertEquals("firstsecond thoughtthird", reasoning)
    // Hard guarantee: no raw tag text survived into the visible content.
    assertEquals(false, content.contains("<think"))
    assertEquals(false, content.contains("</think"))
  }

  @Test
  fun sameResult_whenSplitAcrossChunks() {
    val input = "A<think>r1</think>B<thinking>r2</thinking>C"
    val whole = drainWhole(input)
    val split = drainCharByChar(input)
    assertEquals(whole, split)
    assertEquals("ABC", split.first)
    assertEquals("r1r2", split.second)
  }

  @Test
  fun tagSplitAcrossChunks_doesNotLeakPartialTag() {
    // "<thi" + "nk>reason</thi" + "nk> answer" delivered as fragments.
    val state = StreamingOrchestrator.Companion.ThinkTagState()
    val buf = StringBuilder()
    val c = StringBuilder()
    val r = StringBuilder()
    for (frag in listOf("<thi", "nk>rea", "son</thi", "nk> answer")) {
      val (co, ro) = StreamingOrchestrator.drainThinkTags(frag, state, buf)
      c.append(co); r.append(ro)
    }
    assertEquals(" answer", c.toString())
    assertEquals("reason", r.toString())
    assertEquals(false, c.toString().contains("<"))
  }

  @Test
  fun orphanCloseTag_isDroppedNotRendered() {
    val (content, reasoning) = drainWhole("plain text </think> more text")
    assertEquals("plain text  more text", content)
    assertEquals("", reasoning)
  }

  @Test
  fun plainContentWithNoTags_passesThrough() {
    val (content, reasoning) = drainWhole("just a normal answer with no tags")
    assertEquals("just a normal answer with no tags", content)
    assertEquals("", reasoning)
  }

  @Test
  fun lessThanSignInProse_isNotHeldBackForever() {
    // A '<' that is clearly not a tag (followed by enough non-tag text) must flush.
    val (content, _) = drainWhole("if a < b then c happens and we continue the sentence")
    assertEquals("if a < b then c happens and we continue the sentence", content)
  }

  /**
   * Models like minimax may emit a structured reasoning delta first and THEN switch to inline
   * <think> tags in the content field. The orchestrator now runs this drainer on ALL content
   * (it no longer disables tag parsing once structured reasoning is seen), so inline tags that
   * arrive later in the same stream are still stripped from the visible body. This test guards
   * that the drainer strips such late inline tags regardless of what came before.
   */
  @Test
  fun lateInlineThinkTags_areStrippedFromContent() {
    val state = StreamingOrchestrator.Companion.ThinkTagState()
    val buf = StringBuilder()
    val c = StringBuilder()
    val r = StringBuilder()
    // Simulate: visible answer, then an inline thought, then more answer — all via content deltas.
    for (frag in listOf("Let me try a more specific query:", "<think>", "Let me try a few", " more queries.", "</think>", " Here is the result.")) {
      val (co, ro) = StreamingOrchestrator.drainThinkTags(frag, state, buf)
      c.append(co); r.append(ro)
    }
    assertEquals("Let me try a more specific query: Here is the result.", c.toString())
    assertEquals("Let me try a few more queries.", r.toString())
    assertEquals(false, c.toString().contains("<think"))
    assertEquals(false, c.toString().contains("</think"))
  }
}
