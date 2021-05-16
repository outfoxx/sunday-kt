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
}

val mavenGroup: String by project
val mavenVersion: String by project

val slf4jVersion: String by project
val kotlinCoroutinesVersion: String by project
val uriTemplateVersion: String by project
val zalandoProblemVersion: String by project
val okHttpVersion: String by project

val jacksonVersion: String by project

val junitVersion: String by project
val hamcrestVersion: String by project

group = mavenGroup
version = mavenVersion

val isSnapshot = "$version".endsWith("SNAPSHOT")

repositories {
  mavenCentral()
  jcenter()
}

dependencies {

  implementation(platform("com.squareup.okhttp3:okhttp-bom:$okHttpVersion"))

  api("org.slf4j:slf4j-api:$slf4jVersion")
  api("org.zalando:problem:$zalandoProblemVersion")
  api("org.zalando:jackson-datatype-problem:$zalandoProblemVersion")
  api("com.github.hal4j:uritemplate:$uriTemplateVersion")

  api("com.squareup.okhttp3:okhttp:$okHttpVersion")
  implementation("com.squareup.okhttp3:okhttp-sse")

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

  testImplementation("com.squareup.okhttp3:mockwebserver")
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

tasks {

  withType<KotlinCompile> {
    kotlinOptions {
      kotlinOptions {
        languageVersion = "1.4"
        apiVersion = "1.4"
      }
      jvmTarget = "11"
    }
  }

}


//
// TEST
//

jacoco {
  toolVersion = "0.8.5"
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
    outputDirectory.set(file("$buildDir/javadoc/${project.version}"))
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


//
// PUBLISHING
//

publishing {

  publications {

    create<MavenPublication>("mavenJava") {
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
            url.set("https://raw.githubusercontent.com/outfoxx/sunday-kt/master/LICENSE.txt")
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
  gradle.taskGraph.whenReady {
    isRequired = hasTask("publishMavenJavaPublicationToMavenRepository")
  }
  sign(publishing.publications["mavenJava"])
}
