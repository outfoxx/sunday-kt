plugins {
  id("library.conventions")
}

dependencies {

  api(project(":sunday-core"))

  implementation(libs.zalando.problem)
  implementation(libs.zalando.problem.jackson)
  implementation(libs.checker.qual)

  testImplementation(project(":sunday-jdk"))
  testImplementation(testFixtures(project(":sunday-core")))
}
