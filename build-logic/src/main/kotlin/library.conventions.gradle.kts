import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  `java-library`
  `java-test-fixtures`
  kotlin("jvm")
  id("org.jetbrains.dokka-javadoc")
  id("org.jlleitschuh.gradle.ktlint")
  id("org.jetbrains.kotlinx.kover")
  id("com.vanniktech.maven.publish")
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
    freeCompilerArgs.add("-Xemit-jvm-type-annotations")
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

private val publicationChannel = PublicationChannel.forVersion(releaseVersion)

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/outfoxx/sunday-kt")
      credentials(PasswordCredentials::class)
    }
  }
}

// The CLI task selects the destination for every library without changing its coordinates.
tasks.register("publishRelease") {
  group = "publishing"
  description = "Publishes this version to its release channel."
  dependsOn(publicationChannel.taskName)
}

mavenPublishing {
  pom {
    name.set(project.name)
  }
}
