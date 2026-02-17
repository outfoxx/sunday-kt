import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  `java-library`
  `java-test-fixtures`
  kotlin("jvm")
  id("org.jetbrains.dokka-javadoc")
  id("org.jlleitschuh.gradle.ktlint")
  id("org.jetbrains.kotlinx.kover")
}


val catalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(name: String): String = catalog.findVersion(name).get().requiredVersion
fun catalogLib(name: String) = catalog.findLibrary(name).get()
fun catalogBundle(name: String) = catalog.findBundle(name).get()

val javaVersion: String = providers.gradleProperty("javaVersion").get()
val isCI: Boolean = providers.environmentVariable("CI").map { it.isNotBlank() }.getOrElse(false)

val releaseVersion: String by project

group = "io.outfoxx.sunday"
version = releaseVersion

dependencies {
  testImplementation(catalogBundle("junit"))
  testImplementation(catalogBundle("strikt"))
  testImplementation(catalogLib("kotlinx-coroutines-test"))
  testRuntimeOnly(catalogLib("junit-platform-launcher"))
}

java {
  sourceCompatibility = JavaVersion.toVersion(javaVersion)
  targetCompatibility = JavaVersion.toVersion(javaVersion)
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
  }
  compilerOptions {
    jvmTarget.set(JvmTarget.valueOf("JVM_$javaVersion"))
    javaParameters.set(true)
    freeCompilerArgs.add("-jvm-default=no-compatibility")
    freeCompilerArgs.add("-Xannotation-default-target=param-property")
  }
}

tasks {
  test {
    useJUnitPlatform()
  }

  // Ensure ktlint runs as part of the standard verification workflow.
  named("check") {
    dependsOn("ktlintCheck")
  }
}
