import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.cadixdev.gradle.licenser.LicenseExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {

  id("org.jetbrains.dokka")
  id("com.github.breadmoirai.github-release")
  id("org.sonarqube")
  id("io.github.gradle-nexus.publish-plugin")


  kotlin("jvm") apply (false)
  id("org.cadixdev.licenser") apply (false)
  id("org.jmailen.kotlinter") apply (false)
  id("io.gitlab.arturbosch.detekt") apply (false)
}

val releaseVersion: String by project
val isSnapshot = releaseVersion.endsWith("SNAPSHOT")

val kotlinCoroutinesVersion: String by project
val slf4jVersion: String by project

val junitVersion: String by project
val hamcrestVersion: String by project

val javaVersion: String by project
val kotlinVersion: String by project

val moduleNames = listOf("core", "jdk", "okhttp")

allprojects {

  group = "io.outfoxx.sunday"
  version = releaseVersion

  repositories {
    mavenCentral()
  }

}

configure(moduleNames.map { project(":sunday-$it") }) {

  apply(plugin = "java-library")
  apply(plugin = "jacoco")
  apply(plugin = "maven-publish")
  apply(plugin = "signing")

  apply(plugin = "org.jetbrains.kotlin.jvm")
  apply(plugin = "org.jetbrains.dokka")
  apply(plugin = "org.cadixdev.licenser")
  apply(plugin = "org.jmailen.kotlinter")
  apply(plugin = "io.gitlab.arturbosch.detekt")

  dependencies {

    "implementation"(kotlin("stdlib"))
    "implementation"(kotlin("reflect"))
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinCoroutinesVersion")

    //
    // TESTING
    //

    "testRuntimeOnly"("org.slf4j:slf4j-simple:$slf4jVersion")

    "testImplementation"("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    "testImplementation"("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

    "testImplementation"("org.hamcrest:hamcrest-library:$hamcrestVersion")
  }


  //
  // COMPILE
  //

  configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)

    withSourcesJar()
    withJavadocJar()
  }

  configure<KotlinJvmProjectExtension> {
    jvmToolchain {
      languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    compilerOptions {
      jvmTarget.set(JvmTarget.valueOf("JVM_$javaVersion"))
      javaParameters.set(true)
      freeCompilerArgs.add("-Xjvm-default=all")
    }
  }


  //
  // TEST
  //

  configure<JacocoPluginExtension> {
    toolVersion = "0.8.7"
  }

  tasks.named<Test>("test").configure {

    useJUnitPlatform()

    if (System.getenv("CI").isNullOrBlank()) {
      testLogging {
        events("passed", "skipped", "failed")
      }
    }

    reports.junitXml.required.set(true)

    finalizedBy("jacocoTestReport")
  }


  //
  // CHECKS
  //

  configure<LicenseExtension> {
    header.set(resources.text.fromFile(file("${rootProject.layout.projectDirectory}/HEADER.txt")))
    include("**/*.kt")
  }

  configure<DetektExtension> {
    source.from("src/main/kotlin")

    config.from("${rootProject.layout.projectDirectory}/src/main/detekt/detekt.yml")
    buildUponDefaultConfig = true
    baseline = file("src/main/detekt/detekt-baseline.xml")
  }

  tasks.withType<Detekt>().configureEach {
    jvmTarget = javaVersion
  }


  //
  // DOCS
  //

  tasks.named<DokkaTask>("dokkaHtml") {
    failOnWarning.set(true)
    suppressObviousFunctions.set(false)
    outputDirectory.set(file("${layout.buildDirectory.get()}/dokka/${project.version}"))
  }

  tasks.named<DokkaTask>("dokkaJavadoc") {
    failOnWarning.set(true)
    suppressObviousFunctions.set(false)
    outputDirectory.set(tasks.named<Javadoc>("javadoc").get().destinationDir)
  }

  tasks.named<Javadoc>("javadoc").configure {
    dependsOn("dokkaJavadoc")
  }


  //
  // PUBLISHING
  //

  configure<PublishingExtension> {

    publications {

      create<MavenPublication>("library") {
        from(components["java"])

        suppressPomMetadataWarningsFor("testFixturesApiElements")
        suppressPomMetadataWarningsFor("testFixturesRuntimeElements")

        pom {

          when (project.name) {
            "sunday-core" -> {
              name.set("Sunday - Kotlin - Core")
              description.set("Sunday | The framework of REST for Kotlin")
            }

            "sunday-okhttp" -> {
              name.set("Sunday - Kotlin - OkHttp Implementation")
              description.set(
                """
                  Sunday | The framework of REST for Kotlin
                  
                  The OkHttp implementation uses the OkHttp client library
                  to execute HTTP requests.
                """.trimIndent()
              )
            }

            "sunday-jdk" -> {
              name.set("Sunday - Kotlin - JDK 11 HTTP Client Implementation")
              description.set(
                """
                  Sunday | The framework of REST for Kotlin
                  
                  The JDK 11 HTTP Client implementation uses the JDK 11 HTTP client
                  to execute HTTP requests.
                """.trimIndent()
              )
            }
          }
          url.set("https://github.com/outfoxx/sunday-kt")

          organization {
            name.set("Outfox, Inc.")
            url.set("https://outfoxx.io")
          }

          issueManagement {
            system.set("GitHub")
            url.set("https://github.com/outfoxx/sunday-kt/issues")
          }

          licenses {
            license {
              name.set("Apache License 2.0")
              url.set("https://raw.githubusercontent.com/outfoxx/sunday-kt/main/LICENSE.txt")
              distribution.set("repo")
            }
          }

          scm {
            url.set("https://github.com/outfoxx/sunday-kt")
            connection.set("scm:https://github.com/outfoxx/sunday-kt.git")
            developerConnection.set("scm:git@github.com:outfoxx/sunday-kt.git")
          }

          developers {
            developer {
              id.set("kdubb")
              name.set("Kevin Wooten")
              email.set("kevin@outfoxx.io")
            }
          }

        }
      }

    }
  }

  configure<SigningExtension> {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)

    val publishing = project.extensions.getByType<PublishingExtension>()
    sign(publishing.publications["library"])
  }

  tasks.withType<Sign>().configureEach {
    onlyIf { !isSnapshot }
  }


  //
  // ANALYSIS
  //

  sonar {
    properties {
      property("sonar.sources", "src/main")
      property("sonar.tests", "src/test")
      property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
      property("sonar.kotlin.ktlint.reportPaths", "build/reports/ktlint/main-lint.xml")
      property("sonar.junit.reportPaths", "build/test-results/test")
      property("sonar.jacoco.reportPath", "")
      property("sonar.jacoco.reportPaths", "")
      property(
        "sonar.coverage.jacoco.xmlReportPaths",
        "$rootDir/code-coverage/build/reports/jacoco/testCoverageReport/testCoverageReport.xml",
      )
    }
  }

}


//
// ANALYSIS
//

sonar {
  properties {
    property("sonar.projectName", "sunday-kt")
    property("sonar.projectKey", "outfoxx_sunday-kt")
    property("sonar.organization", "outfoxx")
    property("sonar.host.url", "https://sonarcloud.io")
  }
}


//
// DOCS
//

tasks.dokkaHtmlMultiModule.configure {
  outputDirectory.set(layout.buildDirectory.dir("dokka/${releaseVersion}"))
}


//
// RELEASING
//

githubRelease {
  owner = "outfoxx"
  repo = "sunday-kt"
  tagName = releaseVersion
  targetCommitish = "main"
  releaseName = "🚀 v${releaseVersion}"
  generateReleaseNotes = true
  draft = false
  prerelease = !releaseVersion.matches("""^\d+\.\d+\.\d+$""".toRegex())
  releaseAssets.from(
    moduleNames.flatMap { moduleName ->
      listOf("", "-javadoc", "-sources").map { suffix ->
        file("$rootDir/$moduleName/build/libs/sunday-$moduleName-$releaseVersion$suffix.jar")
      }
    }
  )
  overwrite = true
  authorization = "Token " + (project.findProperty("github.token") as String? ?: System.getenv("GITHUB_TOKEN"))
}

nexusPublishing {
  repositories {
    sonatype()
  }
}
