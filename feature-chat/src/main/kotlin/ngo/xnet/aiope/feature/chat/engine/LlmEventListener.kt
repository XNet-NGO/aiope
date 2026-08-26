package ngo.xnet.aiope.feature.chat.engine

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * OkHttp EventListener that traces LLM connection lifecycle for debugging.
 * Logs DNS, connect, TLS, and first-byte timing per request.
 */
class LlmEventListener : EventListener() {
  private val startNs = System.nanoTime()
  private var dnsStartNs = 0L
  private var connectStartNs = 0L
  private var tlsStartNs = 0L

  private fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

  override fun dnsStart(call: Call, domainName: String) {
    dnsStartNs = System.nanoTime()
  }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    val dnsMs = (System.nanoTime() - dnsStartNs) / 1_000_000
    Log.d("LlmTrace", "[${elapsedMs()}ms] DNS $domainName → ${inetAddressList.size} addrs (${dnsMs}ms)")
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    connectStartNs = System.nanoTime()
  }

  override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
    val connectMs = (System.nanoTime() - connectStartNs) / 1_000_000
    Log.d("LlmTrace", "[${elapsedMs()}ms] Connected to $inetSocketAddress (${connectMs}ms) proto=$protocol")
  }

  override fun secureConnectStart(call: Call) {
    tlsStartNs = System.nanoTime()
  }

  override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
    val tlsMs = (System.nanoTime() - tlsStartNs) / 1_000_000
    Log.d("LlmTrace", "[${elapsedMs()}ms] TLS handshake (${tlsMs}ms)")
  }

  override fun responseHeadersEnd(call: Call, response: Response) {
    Log.d("LlmTrace", "[${elapsedMs()}ms] First byte, status=${response.code}")
  }

  override fun callEnd(call: Call) {
    Log.d("LlmTrace", "[${elapsedMs()}ms] Call complete")
  }

  override fun callFailed(call: Call, ioe: java.io.IOException) {
    Log.w("LlmTrace", "[${elapsedMs()}ms] Call failed: ${ioe.message}")
  }

  object Factory : EventListener.Factory {
    override fun create(call: Call): EventListener = LlmEventListener()
  }
}
