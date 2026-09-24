@file:Suppress("UnstableApiUsage")

// Nested composite builds must not share a convention-build identity.
rootProject.name = "sunday-runtime-build-logic"

dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}
