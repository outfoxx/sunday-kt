plugins {
  id("transport-tests.conventions")
}

dependencies {

  api(project(":sunday-core"))
  api(libs.kotlinx.io.okio)
  api(libs.okhttp)

  implementation(libs.okhttp.coroutines)
  implementation(libs.slf4j.api)
  implementation(libs.jackson.module.kotlin)

  testImplementation(libs.okhttp.tls)
  testImplementation(testFixtures(project(":sunday-core")))
  testImplementation(project(":sunday-problem"))
}
