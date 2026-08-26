package ngo.xnet.aiope

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class AiopeApp :
  Application(),
  ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
  }

  override fun newImageLoader() = ImageLoader.Builder(this)
    .components { add(SvgDecoder.Factory()) }
    .build()
}
