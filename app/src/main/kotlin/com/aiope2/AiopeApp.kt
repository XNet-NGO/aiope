package com.aiope2

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiopeApp : Application(), ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
    com.aiope2.feature.chat.engine.AgentSchedulerWorker.enqueue(this)
  }

  override fun newImageLoader() = ImageLoader.Builder(this)
    .components { add(SvgDecoder.Factory()) }
    .build()
}
