package com.aiope2

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

internal fun Project.configureAndroidCompose() {
  pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

  val libs = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java).named("libs")
  dependencies.add("implementation", dependencies.platform(libs.findLibrary("androidx.compose.bom").get()))

  extensions.configure<ComposeCompilerGradlePluginExtension> {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
  }
}
