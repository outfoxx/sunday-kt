
plugins {
  base
  id("org.jetbrains.kotlinx.kover")
}

repositories {
  mavenCentral()
}

dependencies {
  kover(project(":sunday-core"))
  kover(project(":sunday-jdk"))
  kover(project(":sunday-okhttp"))
}

tasks {

  check {
    finalizedBy(named("koverXmlReport"), named("koverHtmlReport"))
  }

}
