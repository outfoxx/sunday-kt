@file:Suppress("UnstableApiUsage")

import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

includeBuild("build-logic")

rootProject.name = "sunday"

include(
  "core",
  "okhttp",
  "jdk",
  "code-coverage",
)

project(":core").name = "sunday-core"
project(":okhttp").name = "sunday-okhttp"
project(":jdk").name = "sunday-jdk"
