
plugins {
  base
  alias(libs.plugins.kover)
}

dependencies {
  kover(project(":sunday-core"))
  kover(project(":sunday-jdk"))
  kover(project(":sunday-okhttp"))
  kover(project(":sunday-problem"))
  kover(project(":sunday-problem-quarkus"))
  kover(project(":sunday-problem-zalando"))
}

tasks {

  check {
    finalizedBy(named("koverXmlReport"), named("koverHtmlReport"))
  }

}
