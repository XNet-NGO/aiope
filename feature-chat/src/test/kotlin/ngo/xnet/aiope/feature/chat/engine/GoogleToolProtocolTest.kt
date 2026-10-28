package ngo.xnet.aiope.feature.chat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the recurring "Google models lose context every round and loop" bug.
 *
 * History: a sanitizer deleted assistant tool_calls (and their results) that lacked a Google
 * thought_signature. Gemma NEVER emits signatures, so on the default chat model every tool round
 * was wiped from history — the model never saw the result and re-called the same tool forever.
 *
 * The fix: never delete the exchange. Use the native tool_calls protocol only when it is safe
 * (non-Google models, or Google models where every call carries a signature); otherwise record a
 * plain assistant+user text turn that no provider rejects. [StreamingOrchestrator.useNativeToolProtocol]
 * encodes that decision.
 */
class GoogleToolProtocolTest {

  private val SIG = """{"google":{"thought_signature":"abc123"}}"""
  private val NO_SIG = """{"google":{}}"""

  @Test
  fun gemmaIsDetectedAsGoogle() {
    assertTrue(StreamingOrchestrator.isGoogleModelName("google-ai-studio/gemma-4-31b-it"))
    assertTrue(StreamingOrchestrator.isGoogleModelName("gemini-2.5-flash"))
    assertTrue(StreamingOrchestrator.isGoogleModelName("google/whatever"))
  }

  @Test
  fun nonGoogleModelsAreNotGoogle() {
    assertFalse(StreamingOrchestrator.isGoogleModelName("openai-gpt-oss"))
    assertFalse(StreamingOrchestrator.isGoogleModelName("minimax-m3"))
    assertFalse(StreamingOrchestrator.isGoogleModelName("deepseek-r1"))
  }

  @Test
  fun nonGoogle_alwaysUsesNativeProtocol_evenWithoutSignatures() {
    assertTrue(StreamingOrchestrator.useNativeToolProtocol("minimax-m3", listOf(null, null)))
  }

  /** The core regression: Gemma has no signatures, so it must NOT use native protocol. */
  @Test
  fun gemmaWithoutSignatures_usesPlainTextFallback() {
    assertFalse(StreamingOrchestrator.useNativeToolProtocol("gemma-4-31b-it", listOf(null)))
    assertFalse(StreamingOrchestrator.useNativeToolProtocol("gemma-4-31b-it", listOf(NO_SIG, NO_SIG)))
  }

  @Test
  fun geminiWithAllSignatures_usesNativeProtocol() {
    assertTrue(StreamingOrchestrator.useNativeToolProtocol("gemini-2.5-flash", listOf(SIG, SIG)))
  }

  @Test
  fun geminiWithPartialSignatures_usesPlainTextFallback() {
    // If even one call is missing a signature, the native turn would be rejected — fall back.
    assertFalse(StreamingOrchestrator.useNativeToolProtocol("gemini-2.5-flash", listOf(SIG, null)))
  }

  @Test
  fun googleWithNoCalls_doesNotClaimNativeProtocol() {
    // Empty list must not vacuously pass `all {}` into native protocol for Google.
    assertFalse(StreamingOrchestrator.useNativeToolProtocol("gemini-2.5-flash", emptyList()))
  }
}
