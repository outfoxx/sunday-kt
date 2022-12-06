
pluginManagement {

  repositories {
    gradlePluginPortal()
    mavenCentral()
  }

  val kotlinPluginVersion: String by settings
  val dokkaPluginVersion: String by settings
  val licenserPluginVersion: String by settings
  val kotlinterPluginVersion: String by settings
  val detektPluginVersion: String by settings
  val githubReleasePluginVersion: String by settings
  val sonarqubePluginVersion: String by settings
  val nexusPublishPluginVersion: String by settings

  plugins {
    kotlin("jvm") version kotlinPluginVersion
    id("org.jetbrains.dokka") version dokkaPluginVersion
    id("org.cadixdev.licenser") version licenserPluginVersion
    id("org.jmailen.kotlinter") version kotlinterPluginVersion
    id("io.gitlab.arturbosch.detekt") version detektPluginVersion
    id("com.github.breadmoirai.github-release") version githubReleasePluginVersion
    id("org.sonarqube") version sonarqubePluginVersion
    id("io.github.gradle-nexus.publish-plugin") version nexusPublishPluginVersion
  }

}

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
