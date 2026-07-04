package ngo.xnet.aiope

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

internal fun Project.configureKotlinAndroid() {
  extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
      freeCompilerArgs.addAll(
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      )
    }
  }
}
