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
    // Trim the key to match the chat path (StreamingOrchestrator trims too). A stored key with
    // stray whitespace/newlines (common when pasted) otherwise authenticates for chat but 401s on
    // image gen, which does not trim.
    val apiKey = apiKey.trim()
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

  /**
   * Cloudflare FLUX.2 models (klein-4b/9b, dev, pro, max) require a multipart/form-data request
   * even for a plain text prompt; FLUX.1 (schnell) and other CF image models take flat JSON.
   * Matching on "flux-2" covers the whole FLUX.2 family while excluding flux-1-schnell.
   */
  fun isCloudflareMultipartModel(cfModel: String): Boolean =
    cfModel.contains("flux-2", ignoreCase = true)

  private fun unsupportedImg2Img(provider: String): Nothing =
    throw Exception("Image-to-image is not supported for this $provider model. Use a text prompt, or switch to a model/provider that supports image input.")

  private fun openAiImages(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
    if (referenceImages.isEmpty()) {
      // Text-to-image: standard JSON /images/generations.
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
      return parseOpenAiImageResponse(client.newCall(req).execute(), url, model)
    }

    // Image-to-image. Prefer the OpenAI-standard /images/edits endpoint (multipart/form-data).
    // Some OpenAI-compatible providers (e.g. Qwen's compatible-mode) do NOT implement /images/edits
    // and return 404/405; for those we fall back to /images/generations with the reference image
    // embedded in the JSON body as a data-URL `image` array (the shape Qwen compatible-mode accepts).
    val editsUrl = "$base/images/edits"
    val mb = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
      .addFormDataPart("model", model)
      .addFormDataPart("prompt", prompt)
      .addFormDataPart("response_format", "b64_json")
    val partName = if (referenceImages.size > 1) "image[]" else "image"
    referenceImages.forEachIndexed { i, bytes ->
      mb.addFormDataPart(partName, "image_$i.png", okhttp3.RequestBody.create("image/png".toMediaTypeOrNull(), bytes))
    }
    android.util.Log.i(TAG, "openai (edits) POST $editsUrl model=$model refs=${referenceImages.size}")
    val editsReq = okhttp3.Request.Builder().url(editsUrl).post(mb.build())
      .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    val editsResp = client.newCall(editsReq).execute()
    if (editsResp.code != 404 && editsResp.code != 405) {
      return parseOpenAiImageResponse(editsResp, editsUrl, model)
    }
    editsResp.close()

    // Fallback: /images/generations with the reference image(s) embedded as data-URLs.
    val genUrl = "$base/images/generations"
    val body = org.json.JSONObject().apply {
      put("model", model)
      put("prompt", prompt)
      put("response_format", "b64_json")
      put(
        "image",
        org.json.JSONArray().apply { referenceImages.forEach { put(dataUrl(it)) } },
      )
    }.toString()
    android.util.Log.i(TAG, "openai (edits 404 -> generations+image) POST $genUrl model=$model refs=${referenceImages.size}")
    val genReq = okhttp3.Request.Builder().url(genUrl)
      .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
      .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    return parseOpenAiImageResponse(client.newCall(genReq).execute(), genUrl, model)
  }

  /** Parse an OpenAI-compatible image response (handles both {result:{image}} and {data:[{b64_json|url}]}). */
  private fun parseOpenAiImageResponse(resp: okhttp3.Response, url: String, model: String): ByteArray {
    val respBody = resp.use { it.body?.string() ?: "" }
    android.util.Log.i(TAG, "openai <- ${resp.code} ${respBody.take(200)}")
    if (!resp.isSuccessful) {
      if (resp.code == 401 || resp.code == 403) {
        throw Exception(
          "HTTP ${resp.code} @ $url (model=$model): the media provider rejected the API key. " +
            "Check Settings > Providers (Media): the key must be valid for THIS host and region " +
            "(e.g. a Qwen 'maas.qwencloudapi.com' key, matching the endpoint's region — a DashScope " +
            "key or a wrong-region key will 401). Raw: ${respBody.take(200)}",
        )
      }
      throw Exception("HTTP ${resp.code} @ $url (model=$model): ${respBody.take(240)}")
    }
    val json = org.json.JSONObject(respBody)
    val b64 = json.optJSONObject("result")?.optString("image")
      ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json") ?: ""
    val imageUrl = json.optJSONArray("data")?.optJSONObject(0)?.optString("url") ?: ""
    return decodeOrFetch(b64, imageUrl)
  }

  private fun cloudflare(base: String, model: String, prompt: String, apiKey: String, referenceImages: List<ByteArray>): ByteArray {
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

    // FLUX.2 family (klein-4b/9b, dev, pro, max) takes MULTIPART form-data input — even for a
    // plain text prompt. Sending JSON {"prompt":...} to these returns "prompt is required"
    // (code 3030), because the model reads multipart form fields, not a JSON body. flux-1-schnell
    // and other CF image models still take flat JSON.
    val req = if (isCloudflareMultipartModel(cfModel)) {
      android.util.Log.i(TAG, "cloudflare (multipart) POST $url model=$cfModel refs=${referenceImages.size}")
      val mb = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
        .addFormDataPart("prompt", prompt)
        .addFormDataPart("steps", "4")
        .addFormDataPart("width", "1024")
        .addFormDataPart("height", "1024")
      // Reference images (image-to-image) must be named input_image_0..3 as BINARY parts.
      referenceImages.take(4).forEachIndexed { i, bytes ->
        mb.addFormDataPart(
          "input_image_$i",
          "input_image_$i.png",
          okhttp3.RequestBody.create("image/png".toMediaTypeOrNull(), bytes),
        )
      }
      okhttp3.Request.Builder().url(url).post(mb.build())
        .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    } else {
      if (referenceImages.isNotEmpty()) unsupportedImg2Img("Cloudflare")
      android.util.Log.i(TAG, "cloudflare (json) POST $url model=$cfModel")
      okhttp3.Request.Builder().url(url)
        .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), org.json.JSONObject().put("prompt", prompt).toString()))
        .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }.build()
    }

    val resp = client.newCall(req).execute()
    val respBody = resp.use { it.body?.string() ?: "" }
    android.util.Log.i(TAG, "cloudflare <- ${resp.code} ${respBody.take(200)}")
    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} @ $url (model=$cfModel): ${respBody.take(240)}")
    val json = org.json.JSONObject(respBody)
    // FLUX.2 returns {"result":{"image":"<b64>"}}; schnell returns {"result":{"image":"<b64>"}} too.
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
