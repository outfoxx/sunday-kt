plugins {
  id("library.conventions")
}

dependencies {

  api(project(":sunday-core"))
  api(libs.kotlinx.io.okio)
  api(libs.okhttp)

  implementation(libs.slf4j.api)
  implementation(libs.jackson.module.kotlin)

  testImplementation(testFixtures(project(":sunday-core")))
}
