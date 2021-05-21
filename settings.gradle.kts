
pluginManagement {

  repositories {
    gradlePluginPortal()
    jcenter()
  }

  val kotlinPluginVersion: String by settings
  val dokkaPluginVersion: String by settings
  val licenserPluginVersion: String by settings
  val kotlinterPluginVersion: String by settings
  val detektPluginVersion: String by settings
  val githubReleasePluginVersion: String by settings

  plugins {
    kotlin("jvm") version kotlinPluginVersion
    id("org.jetbrains.dokka") version dokkaPluginVersion
    id("net.minecrell.licenser") version licenserPluginVersion
    id("org.jmailen.kotlinter") version kotlinterPluginVersion
    id("io.gitlab.arturbosch.detekt") version detektPluginVersion
    id("com.github.breadmoirai.github-release") version githubReleasePluginVersion
  }

}

rootProject.name = "sunday"
