@file:Suppress("UnstableApiUsage")

import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
  includeBuild("build-logic")
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

rootProject.name = "sunday"

include(
  "core",
  "validation-core",
  "validation-javax",
  "validation-jakarta",
  "okhttp",
  "jdk",
  "jaxrs-quarkus",
  "client-quarkus",
  "broker",
  "problem",
  "problem-quarkus",
  "problem-zalando",
  "code-coverage",
)

project(":core").name = "sunday-core"
project(":okhttp").name = "sunday-okhttp"
project(":jdk").name = "sunday-jdk"
project(":jaxrs-quarkus").name = "sunday-jaxrs-quarkus"
project(":broker").name = "sunday-broker"
project(":problem").name = "sunday-problem"
project(":problem-quarkus").name = "sunday-problem-quarkus"
project(":problem-zalando").name = "sunday-problem-zalando"

project(":validation-core").name = "sunday-validation-core"
project(":validation-javax").name = "sunday-validation-javax"
project(":validation-jakarta").name = "sunday-validation-jakarta"

project(":client-quarkus").name = "sunday-client-quarkus"
