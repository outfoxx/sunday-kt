
pluginManagement {

  repositories {
    gradlePluginPortal()
    jcenter()
  }

  val kotlinPluginVersion: String by settings
  val dokkaPluginVersion: String by settings
  val licenserPluginVersion: String by settings
  val kotlinterPluginVersion: String by settings

  plugins {
    kotlin("jvm") version kotlinPluginVersion
    id("org.jetbrains.dokka") version dokkaPluginVersion
    id("net.minecrell.licenser") version licenserPluginVersion
    id("org.jmailen.kotlinter") version kotlinterPluginVersion
  }

}

rootProject.name = "sunday"
