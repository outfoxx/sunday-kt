plugins {
  id("library.conventions")
}

dependencies {

  api(project(":sunday-core"))

  implementation(libs.qv.problem)

  testImplementation(project(":sunday-jdk"))
  testImplementation(testFixtures(project(":sunday-core")))
  testRuntimeOnly(libs.jakarta.rest.api)
}
