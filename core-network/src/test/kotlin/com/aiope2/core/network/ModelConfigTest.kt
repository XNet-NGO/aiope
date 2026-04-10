package com.aiope2.core.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelConfigTest {

  @Test
  fun `toJson includes required modelId field`() {
    val config = ModelConfig(modelId = "gpt-4o")
    val json = config.toJson()
    assertEquals("gpt-4o", json.getString("modelId"))
  }

  @Test
  fun `toJson omits blank endpointOverride`() {
    val config = ModelConfig(modelId = "model", endpointOverride = "")
    val json = config.toJson()
    assertFalse(json.has("endpointOverride"))
  }

  @Test
  fun `toJson includes non-blank endpointOverride`() {
    val config = ModelConfig(modelId = "model", endpointOverride = "https://api.example.com/v1")
    val json = config.toJson()
    assertEquals("https://api.example.com/v1", json.getString("endpointOverride"))
  }

  @Test
  fun `toJson omits null optional fields`() {
    val config = ModelConfig(
      modelId = "m",
      toolsOverride = null,
      visionOverride = null,
      temperature = null,
      topP = null,
      topK = null,
      maxTokens = null,
      reasoningEffort = null,
      systemPromptOverride = null,
    )
    val json = config.toJson()
    assertFalse(json.has("toolsOverride"))
    assertFalse(json.has("visionOverride"))
    assertFalse(json.has("audioOverride"))
    assertFalse(json.has("videoOverride"))
    assertFalse(json.has("temperature"))
    assertFalse(json.has("topP"))
    assertFalse(json.has("topK"))
    assertFalse(json.has("maxTokens"))
    assertFalse(json.has("reasoningEffort"))
    assertFalse(json.has("systemPromptOverride"))
  }

  @Test
  fun `toJson includes present optional boolean overrides`() {
    val config = ModelConfig(modelId = "m", toolsOverride = true, visionOverride = false)
    val json = config.toJson()
    assertEquals(true, json.getBoolean("toolsOverride"))
    assertEquals(false, json.getBoolean("visionOverride"))
  }

  @Test
  fun `toJson includes temperature and topP`() {
    val config = ModelConfig(modelId = "m", temperature = 0.7f, topP = 0.9f)
    val json = config.toJson()
    assertEquals(0.7, json.getDouble("temperature"), 0.001)
    assertEquals(0.9, json.getDouble("topP"), 0.001)
  }

  @Test
  fun `toJson includes contextTokens and autoCompact`() {
    val config = ModelConfig(modelId = "m", contextTokens = 50_000, autoCompact = true)
    val json = config.toJson()
    assertEquals(50_000, json.getInt("contextTokens"))
    assertEquals(true, json.getBoolean("autoCompact"))
  }

  @Test
  fun `fromJson roundtrip preserves all fields`() {
    val original = ModelConfig(
      modelId = "gpt-4o",
      endpointOverride = "https://api.example.com/v1",
      toolsOverride = true,
      visionOverride = false,
      audioOverride = true,
      videoOverride = false,
      temperature = 0.7f,
      topP = 0.9f,
      topK = 50,
      maxTokens = 4096,
      reasoningEffort = "high",
      contextTokens = 50_000,
      autoCompact = true,
      systemPromptOverride = "You are helpful.",
    )
    val restored = ModelConfig.fromJson(original.toJson())
    assertEquals(original.modelId, restored.modelId)
    assertEquals(original.endpointOverride, restored.endpointOverride)
    assertEquals(original.toolsOverride, restored.toolsOverride)
    assertEquals(original.visionOverride, restored.visionOverride)
    assertEquals(original.audioOverride, restored.audioOverride)
    assertEquals(original.videoOverride, restored.videoOverride)
    assertEquals(original.temperature, restored.temperature)
    assertEquals(original.topP, restored.topP)
    assertEquals(original.topK, restored.topK)
    assertEquals(original.maxTokens, restored.maxTokens)
    assertEquals(original.reasoningEffort, restored.reasoningEffort)
    assertEquals(original.contextTokens, restored.contextTokens)
    assertEquals(original.autoCompact, restored.autoCompact)
    assertEquals(original.systemPromptOverride, restored.systemPromptOverride)
  }

  @Test
  fun `fromJson with minimal JSON applies defaults`() {
    val json = JSONObject().apply { put("modelId", "my-model") }
    val config = ModelConfig.fromJson(json)
    assertEquals("my-model", config.modelId)
    assertEquals("", config.endpointOverride)
    assertNull(config.toolsOverride)
    assertNull(config.visionOverride)
    assertNull(config.audioOverride)
    assertNull(config.videoOverride)
    assertEquals(0.6f, config.temperature)
    assertNull(config.topP)
    assertNull(config.topK)
    assertNull(config.maxTokens)
    assertNull(config.reasoningEffort)
    assertEquals(10_000_000, config.contextTokens)
    assertFalse(config.autoCompact)
    assertNull(config.systemPromptOverride)
  }

  @Test
  fun `fromJson restores null overrides when fields absent`() {
    val json = JSONObject().apply { put("modelId", "m") }
    val config = ModelConfig.fromJson(json)
    assertNull(config.toolsOverride)
    assertNull(config.visionOverride)
  }

  @Test
  fun `fromJson restores reasoningEffort`() {
    val original = ModelConfig(modelId = "m", reasoningEffort = "medium")
    val restored = ModelConfig.fromJson(original.toJson())
    assertEquals("medium", restored.reasoningEffort)
  }

  @Test
  fun `default temperature is zero point six`() {
    val config = ModelConfig(modelId = "m")
    assertEquals(0.6f, config.temperature)
  }

  @Test
  fun `default contextTokens is ten million`() {
    val config = ModelConfig(modelId = "m")
    assertEquals(10_000_000, config.contextTokens)
  }
}
