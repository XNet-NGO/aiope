import com.android.build.api.dsl.ApplicationExtension
import ngo.xnet.aiope.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.application")
      extensions.configure<ApplicationExtension> {
        compileSdk = 37
        defaultConfig { minSdk = 26 }
      }
      configureKotlinAndroid()
    }
  }
}
