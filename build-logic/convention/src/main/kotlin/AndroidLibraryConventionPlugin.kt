import com.android.build.api.dsl.LibraryExtension
import ngo.xnet.aiope.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.library")
      extensions.configure<LibraryExtension> {
        compileSdk = 37
        defaultConfig { minSdk = 26 }
      }
      configureKotlinAndroid()
    }
  }
}
