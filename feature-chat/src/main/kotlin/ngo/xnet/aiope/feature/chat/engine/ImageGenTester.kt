package ngo.xnet.aiope.feature.chat.engine

import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Image-generation request shaping, split out so it can be unit/device-tested directly.
 *
 * Routing is by BASE-URL HOST, never by model prefix — because an OpenAI-compatible gateway
 * (e.g. inf.xnet.ngo) proxies Cloudflare/Qwen/etc. models and expects the standard
 * /images/generations shape. Only direct provider hosts get their native shapes.
 */
object ImageGenTester {
  private const val TAG = "ImgGen"

  private val client = SafeOkHttp.builder()
    .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
    .build()

  /** Returns raw image bytes. [referenceImages] (optional) enable image-to-image where supported. */
  fun generateBytes(
    base0: String,
    model: String,
    apiKey: String,
    prompt: String,
    referenceImages: List<ByteArray> = emptyList(),
  ): ByteArray {
    val base = base0.trimEnd('/')
    val host = java.net.URI(base).host ?: base
    android.util.Log.i(TAG, "route: host=$host base=$base model=$model refs=${referenceImages.size}")

    return when {
      host.contains("bfl.ai") -> flux(base, model, prompt, apiKey, referenceImages)
      host.contains("dashscope") || host.contains("aliyuncs") -> dashscope(base, model, prompt, apiKey, referenceImages)
      // Direct Cloudflare API only (NOT a gateway that proxies CF models).
      host == "api.cloudflare.com" -> cloudflare(base, model, prompt, apiKey, referenceImages)
      // Everything else (incl. OpenAI-compatible gateways): standard images/generations.
      else -> openAiImages(base, model, prompt, apiKey, referenceImages)
    }
  }

  private fun dataUrl(bytes: ByteArray): String =
    "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

  private fun unsupportedImg2Img(provider: String): Nothing =
    throw Exception("Image-to-image is not supported for this $provider model. Use a text prompt, or switch to a model/provider that supports image input.")

  private fun openAiImages(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
    if (referenceImages.isNotEmpty()) unsupportedImg2Img("OpenAI-compatible")
    val url = "$base/images/generations"
    val body = org.json.JSONObject().apply {
      put("model", model)
      put("prompt", prompt)
      put("response_format", "b64_json")
    }.toString()
    android.util.Log.i(TAG, "openai POST $url body=$body")
    val req = okhttp3.Request.Builder().url(url)
      .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
      .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    val resp = client.newCall(req).execute()
    val respBody = resp.use { it.body?.string() ?: "" }
    android.util.Log.i(TAG, "openai <- ${resp.code} ${respBody.take(200)}")
    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} @ $url (model=$model): ${respBody.take(240)}")
    val json = org.json.JSONObject(respBody)
    val b64 = json.optJSONObject("result")?.optString("image")
      ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json") ?: ""
    val imageUrl = json.optJSONArray("data")?.optJSONObject(0)?.optString("url") ?: ""
    return decodeOrFetch(b64, imageUrl)
  }

  private fun cloudflare(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
    if (referenceImages.isNotEmpty()) unsupportedImg2Img("Cloudflare")
    val cfModel = model.removePrefix("cf-image/").removePrefix("cloudflare/")
      .let { if (it.startsWith("@cf/")) it else "@cf/$it" }
    // Build the run URL, tolerating base URLs that already include /ai, /ai/run, or a trailing @cf.
    val url = when {
      base.contains("/ai/run") -> {
        // Base already points at (or past) the run path; strip anything after /ai/run.
        base.substringBefore("/ai/run") + "/ai/run/" + cfModel
      }
      base.endsWith("/ai") -> "$base/run/$cfModel"
      base.contains("/ai/v1") -> base.substringBefore("/ai/v1") + "/ai/run/" + cfModel
      base.endsWith("/v1") -> base.removeSuffix("/v1") + "/ai/run/" + cfModel
      else -> "$base/ai/run/$cfModel"
    }
    android.util.Log.i(TAG, "cloudflare POST $url")
    val req = okhttp3.Request.Builder().url(url)
      .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), org.json.JSONObject().put("prompt", prompt).toString()))
      .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    val resp = client.newCall(req).execute()
    val respBody = resp.use { it.body?.string() ?: "" }
    android.util.Log.i(TAG, "cloudflare <- ${resp.code} ${respBody.take(200)}")
    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} @ $url (model=$cfModel): ${respBody.take(240)}")
    val json = org.json.JSONObject(respBody)
    val b64 = json.optJSONObject("result")?.optString("image") ?: ""
    return decodeOrFetch(b64, "")
  }

  private fun flux(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
    val m = model.trim('/').substringAfterLast('/')
    val url = "$base/$m"
    android.util.Log.i(TAG, "flux POST $url refs=${referenceImages.size}")
    val submitBody = org.json.JSONObject().put("prompt", prompt).put("width", 1024).put("height", 1024).apply {
      // BFL Kontext / FLUX.2 accept a base64 reference image via input_image (no data: prefix).
      if (referenceImages.isNotEmpty()) {
        put("input_image", android.util.Base64.encodeToString(referenceImages.first(), android.util.Base64.NO_WRAP))
      }
    }.toString()
    val submit = okhttp3.Request.Builder().url(url)
      .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), submitBody))
      .addHeader("accept", "application/json")
      .apply { if (apiKey.isNotBlank()) addHeader("x-key", apiKey) }.build()
    val sj = client.newCall(submit).execute().use {
      val b = it.body?.string() ?: ""
      android.util.Log.i(TAG, "flux submit <- ${it.code} ${b.take(160)}")
      if (!it.isSuccessful) throw Exception("FLUX submit HTTP ${it.code} @ $url: ${b.take(200)}")
      org.json.JSONObject(b)
    }
    val poll = sj.optString("polling_url").ifBlank { throw Exception("FLUX: no polling_url") }
    repeat(120) {
      Thread.sleep(750)
      val pj = client.newCall(okhttp3.Request.Builder().url(poll).addHeader("accept", "application/json").apply { if (apiKey.isNotBlank()) addHeader("x-key", apiKey) }.build())
        .execute().use { org.json.JSONObject(it.body?.string() ?: "") }
      when (pj.optString("status")) {
        "Ready" -> return java.net.URL(pj.optJSONObject("result")?.optString("sample").orEmpty()).readBytes()
        "Error", "Failed" -> throw Exception("FLUX ${pj.optString("status")}: ${pj.toString().take(200)}")
      }
    }
    throw Exception("FLUX timed out")
  }

  private fun dashscope(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
    // Derive the DashScope host root, tolerating bases that already include /compatible-mode/v1
    // or /api/v1 so we never double the path segment.
    val host = base
      .substringBefore("/compatible-mode")
      .substringBefore("/api/v1")
      .substringBefore("/api")
      .trimEnd('/')
    val m = model.substringAfterLast('/')
    // Qwen-Image uses the SYNCHRONOUS multimodal-generation endpoint. For image-to-image, add
    // {image: <dataUrl>} content parts (1-3 supported) before the text part.
    val url = "$host/api/v1/services/aigc/multimodal-generation/generation"
    val contentArr = org.json.JSONArray().apply {
      referenceImages.take(3).forEach { put(org.json.JSONObject().put("image", dataUrl(it))) }
      put(org.json.JSONObject().put("text", prompt))
    }
    val body = org.json.JSONObject().apply {
      put("model", m)
      put("input", org.json.JSONObject().apply {
        put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
          put("role", "user")
          put("content", contentArr)
        }))
      })
      put("parameters", org.json.JSONObject().apply {
        put("size", "1328*1328")
        put("prompt_extend", true)
        put("watermark", false)
      })
    }.toString()
    android.util.Log.i(TAG, "dashscope POST $url refs=${referenceImages.size} body=${body.take(160)}")
    val req = okhttp3.Request.Builder().url(url)
      .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
      .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    val json = client.newCall(req).execute().use {
      val b = it.body?.string() ?: ""
      android.util.Log.i(TAG, "dashscope <- ${it.code} ${b.take(200)}")
      if (!it.isSuccessful) throw Exception("DashScope HTTP ${it.code} @ $url (model=$m): ${b.take(240)}")
      org.json.JSONObject(b)
    }
    val imageUrl = json.optJSONObject("output")
      ?.optJSONArray("choices")?.optJSONObject(0)
      ?.optJSONObject("message")?.optJSONArray("content")?.optJSONObject(0)
      ?.optString("image").orEmpty()
    if (imageUrl.isBlank()) throw Exception("DashScope: no image in response: ${json.toString().take(240)}")
    return java.net.URL(imageUrl).readBytes()
  }

  private fun decodeOrFetch(b64: String, url: String): ByteArray = when {
    b64.isNotBlank() -> android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    url.isNotBlank() -> java.net.URL(url).readBytes()
    else -> throw Exception("No image in response")
  }

  /** Test entrypoint: generate and persist, returning a human-readable summary. */
  fun generate(context: android.content.Context, base: String, model: String, apiKey: String, prompt: String, referenceImages: List<ByteArray> = emptyList()): String {
    return try {
      val bytes = generateBytes(base, model, apiKey, prompt, referenceImages)
      val dir = java.io.File(context.filesDir, "generated").apply { mkdirs() }
      val file = java.io.File(dir, "test_${System.currentTimeMillis()}.png")
      file.writeBytes(bytes)
      "OK ${bytes.size} bytes -> ${file.absolutePath}"
    } catch (e: Exception) {
      "FAIL: ${e.message}"
    }
  }
}
