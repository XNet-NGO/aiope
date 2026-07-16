package org.xnet.aiope.inference

class LlamaEngine {
    companion object {
        init { System.loadLibrary("aiope-inference") }
    }

    private var handle: Long = nativeCreate()

    val isLoaded: Boolean get() = handle != 0L

    fun loadModel(path: String, contextSize: Int = 4096, nThreads: Int = 4): Boolean {
        check(handle != 0L) { "Engine closed" }
        return nativeLoadModel(handle, path, contextSize, nThreads)
    }

    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        repeatPenalty: Float = 1.1f,
        callback: StreamCallback
    ): Boolean {
        check(handle != 0L) { "Engine closed" }
        return nativeGenerate(handle, prompt, maxTokens, temperature, topP, repeatPenalty, callback)
    }

    fun embed(text: String): FloatArray? {
        check(handle != 0L) { "Engine closed" }
        return nativeEmbed(handle, text)
    }

    fun abort() { if (handle != 0L) nativeAbort(handle) }
    fun unload() { if (handle != 0L) nativeUnload(handle) }
    fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }

    interface StreamCallback {
        fun onToken(token: String): Boolean
        fun onComplete(tokensPerSec: Float, tokenCount: Int)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadModel(handle: Long, path: String, contextSize: Int, nThreads: Int): Boolean
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, repeatPenalty: Float, callback: StreamCallback): Boolean
    private external fun nativeEmbed(handle: Long, text: String): FloatArray?
    private external fun nativeAbort(handle: Long)
    private external fun nativeUnload(handle: Long)
}
