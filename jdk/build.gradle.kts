plugins {
  id("library.conventions")
}

dependencies {

  api(project(":sunday-core"))

  implementation(libs.slf4j.api)
  implementation(libs.kotlinx.coroutines.jdk9)
  implementation(libs.jackson.module.kotlin)

  testImplementation(testFixtures(project(":sunday-core")))
}
