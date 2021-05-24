import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `java-library`
  jacoco
  `maven-publish`
  signing

  kotlin("jvm")
  id("org.jetbrains.dokka")

  id("net.minecrell.licenser")
  id("org.jmailen.kotlinter")
  id("io.gitlab.arturbosch.detekt")
  id("com.github.breadmoirai.github-release")
}

val releaseVersion: String by project
val isSnapshot = releaseVersion.endsWith("SNAPSHOT")

val slf4jVersion: String by project
val kotlinCoroutinesVersion: String by project
val uriTemplateVersion: String by project
val zalandoProblemVersion: String by project
val okHttpVersion: String by project

val jacksonVersion: String by project

val junitVersion: String by project
val hamcrestVersion: String by project



group = "io.outfoxx.sunday"
version = releaseVersion


repositories {
  mavenCentral()
  jcenter()
}

dependencies {

  api("org.slf4j:slf4j-api:$slf4jVersion")
  api("org.zalando:problem:$zalandoProblemVersion")
  api("org.zalando:jackson-datatype-problem:$zalandoProblemVersion")
  api("com.github.hal4j:uritemplate:$uriTemplateVersion")

  api("com.squareup.okhttp3:okhttp:$okHttpVersion")

  implementation(kotlin("stdlib"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  //
  // TESTING
  //

  testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

  testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
  testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

  testImplementation("org.hamcrest:hamcrest-library:$hamcrestVersion")

  testImplementation("com.squareup.okhttp3:mockwebserver:$okHttpVersion")
}


//
// COMPILE
//

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8

  withSourcesJar()
  withJavadocJar()
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    kotlinOptions {
      languageVersion = "1.4"
      apiVersion = "1.4"
    }
    jvmTarget = "11"
  }
}


//
// TEST
//

jacoco {
  toolVersion = "0.8.7"
}

tasks {
  test {
    useJUnitPlatform()

    finalizedBy(jacocoTestReport)
    jacoco {}
  }

  jacocoTestReport {
    dependsOn(test)
  }
}


//
// DOCS
//

tasks {
  dokkaHtml {
    outputDirectory.set(file("$buildDir/dokka/${project.version}"))
  }

  javadoc {
    dependsOn(dokkaHtml)
  }
}


//
// CHECKS
//

kotlinter {
  indentSize = 2
}

license {
  header = file("HEADER.txt")
  include("**/*.kt")
}

detekt {
  input = files("src/main/kotlin")

  config = files("src/main/detekt/detekt.yml")
  buildUponDefaultConfig = true
  baseline = file("src/main/detekt/detekt-baseline.xml")
}

tasks.withType<Detekt>().configureEach {
  jvmTarget = "11"
}


//
// PUBLISHING
//

publishing {

  publications {

    create<MavenPublication>("library") {
      from(components["java"])

      pom {

        name.set("Sunday Kotlin")
        description.set("Sunday | The framework of REST for Kotlin")
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

  repositories {
    maven {
      name = "MavenCentral"
      val snapshotUrl = "https://oss.sonatype.org/content/repositories/snapshots/"
      val releaseUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
      url = uri(if (isSnapshot) snapshotUrl else releaseUrl)
      credentials {
        username = project.findProperty("ossrhUsername")?.toString()
        password = project.findProperty("ossrhPassword")?.toString()
      }
    }
  }

}


signing {
  val signingKeyId: String? by project
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
  sign(publishing.publications["library"])
}

tasks.withType<Sign>().configureEach {
  onlyIf { !isSnapshot }
}


//
// RELEASING
//

githubRelease {
  owner("outfoxx")
  repo("sunday-kt")
  tagName(releaseVersion)
  targetCommitish("main")
  releaseName("v${releaseVersion}")
  draft(true)
  prerelease(!releaseVersion.matches("""^\d+\.\d+\.\d+$""".toRegex()))
  releaseAssets(
    files("${project.rootDir}/build/libs/sunday-${releaseVersion}*.jar")
  )
  overwrite(true)
  token(project.findProperty("github.token") as String? ?: System.getenv("GITHUB_TOKEN"))
}

tasks {

  register("publishMavenRelease") {
    dependsOn(
      "publishAllPublicationsToMavenCentralRepository"
    )
  }

  register("publishRelease") {
    dependsOn(
      "publishMavenRelease",
      "githubRelease"
    )
  }

}
