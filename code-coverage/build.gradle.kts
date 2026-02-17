
plugins {
  base
  alias(libs.plugins.kover)
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
