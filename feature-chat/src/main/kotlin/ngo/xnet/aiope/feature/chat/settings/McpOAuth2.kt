package ngo.xnet.aiope.feature.chat.settings

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OAuth2TokenResult(
  val accessToken: String,
  val refreshToken: String,
  val expiresAt: Long,
)

object McpOAuth2 {

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  /**
   * Fetch a token using client_credentials or refresh_token grant.
   * If refreshToken is provided, uses refresh_token grant first;
   * falls back to client_credentials.
   */
  fun fetchToken(
    tokenUrl: String,
    clientId: String,
    clientSecret: String,
    scopes: String,
    refreshToken: String? = null,
  ): OAuth2TokenResult {
    val body = if (!refreshToken.isNullOrBlank()) {
      FormBody.Builder()
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .add("client_id", clientId)
        .add("client_secret", clientSecret)
        .apply { if (scopes.isNotBlank()) add("scope", scopes) }
        .build()
    } else {
      FormBody.Builder()
        .add("grant_type", "client_credentials")
        .add("client_id", clientId)
        .add("client_secret", clientSecret)
        .apply { if (scopes.isNotBlank()) add("scope", scopes) }
        .build()
    }

    val request = Request.Builder()
      .url(tokenUrl)
      .post(body)
      .header("Accept", "application/json")
      .build()

    val response = client.newCall(request).execute()
    val responseBody = response.body?.string() ?: throw Exception("Empty response from token endpoint")

    if (!response.isSuccessful) {
      val errMsg = try {
        val j = JSONObject(responseBody)
        j.optString("error_description", j.optString("error", responseBody))
      } catch (_: Exception) {
        responseBody.take(200)
      }
      throw Exception("Token request failed (${response.code}): $errMsg")
    }

    val json = JSONObject(responseBody)
    val accessToken = json.optString("access_token", "")
    if (accessToken.isBlank()) throw Exception("No access_token in response")

    val expiresIn = json.optLong("expires_in", 3600)
    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
    val newRefresh = json.optString("refresh_token", refreshToken ?: "")

    return OAuth2TokenResult(
      accessToken = accessToken,
      refreshToken = newRefresh,
      expiresAt = expiresAt,
    )
  }
}
