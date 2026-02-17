plugins {
  `kotlin-dsl`
}

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.dokka.gradle.plugin)
  implementation(libs.dokka.javadoc.gradle.plugin)
  implementation(libs.ktlint.gradle.plugin)
  implementation(libs.licenser.gradle.plugin)
  implementation(libs.vanniktech.maven.publish.gradle.plugin)
  implementation(libs.kover.gradle.plugin)
}
