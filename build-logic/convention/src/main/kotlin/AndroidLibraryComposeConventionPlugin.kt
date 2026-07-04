import com.android.build.api.dsl.LibraryExtension
import ngo.xnet.aiope.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.android.library")
      configureAndroidCompose()
    }
  }
}
