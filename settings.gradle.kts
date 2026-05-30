pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven(url = "https://jitpack.io")
    flatDir { dirs("${rootProject.projectDir}/feature-chat/libs") }
  }
}
rootProject.name = "aiope2"
include(":app")
include(":core-model")
include(":core-network")
include(":core-preferences")
include(":core-data")
include(":core-designsystem")
include(":core-navigation")
include(":core-terminal")
include(":feature-chat")
include(":feature-remote")
