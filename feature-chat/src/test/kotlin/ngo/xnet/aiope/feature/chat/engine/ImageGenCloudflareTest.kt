package ngo.xnet.aiope.feature.chat.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare's FLUX.2 image models require multipart/form-data input; sending flat JSON yields
 * "prompt is required" (code 3030). FLUX.1 (schnell) still takes JSON. This guards the routing.
 */
class ImageGenCloudflareTest {

  @Test
  fun flux2ModelsUseMultipart() {
    assertTrue(ImageGenTester.isCloudflareMultipartModel("@cf/black-forest-labs/flux-2-klein-4b"))
    assertTrue(ImageGenTester.isCloudflareMultipartModel("@cf/black-forest-labs/flux-2-klein-9b"))
    assertTrue(ImageGenTester.isCloudflareMultipartModel("@cf/black-forest-labs/flux-2-dev"))
    assertTrue(ImageGenTester.isCloudflareMultipartModel("flux-2-max"))
  }

  @Test
  fun flux1SchnellStaysJson() {
    assertFalse(ImageGenTester.isCloudflareMultipartModel("@cf/black-forest-labs/flux-1-schnell"))
    assertFalse(ImageGenTester.isCloudflareMultipartModel("flux-1-schnell"))
  }

  @Test
  fun otherCfImageModelsStayJson() {
    assertFalse(ImageGenTester.isCloudflareMultipartModel("@cf/bytedance/stable-diffusion-xl-lightning"))
    assertFalse(ImageGenTester.isCloudflareMultipartModel("@cf/lykon/dreamshaper-8-lcm"))
  }
}
