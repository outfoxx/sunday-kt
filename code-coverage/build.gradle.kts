
plugins {
  base
  alias(libs.plugins.kover)
}

dependencies {
  kover(project(":sunday-core"))
  kover(project(":sunday-jdk"))
  kover(project(":sunday-okhttp"))
  kover(project(":sunday-jaxrs-quarkus"))
  kover(project(":sunday-client-quarkus"))
  kover(project(":sunday-validation-core"))
  kover(project(":sunday-validation-javax"))
  kover(project(":sunday-validation-jakarta"))
  kover(project(":sunday-broker"))
  kover(project(":sunday-problem"))
  kover(project(":sunday-problem-quarkus"))
  kover(project(":sunday-problem-zalando"))
}

tasks {

  check {
    finalizedBy(named("koverXmlReport"), named("koverHtmlReport"))
  }

}
