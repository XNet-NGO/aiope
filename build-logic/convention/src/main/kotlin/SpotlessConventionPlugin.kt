import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class SpotlessConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.diffplug.spotless")

      extensions.configure<SpotlessExtension> {
        kotlin {
          target("**/*.kt")
          targetExclude("**/build/**/*.kt")
          ktlint()
            .editorConfigOverride(mapOf(
              "indent_size" to "2",
              "continuation_indent_size" to "2",
              "max_line_length" to "off",
              "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
              "ktlint_standard_no-wildcard-imports" to "disabled",
              "ktlint_standard_backing-property-naming" to "disabled",
              "ktlint_standard_mixed-condition-operators" to "disabled",
            ))
        }
        format("kts") {
          target("**/*.kts")
          targetExclude("**/build/**/*.kts")
        }
      }
    }
  }
}
