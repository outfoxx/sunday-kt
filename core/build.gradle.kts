
plugins {
  id("library.conventions")
}

dependencies {

  api(libs.bundles.kotlin.io)
  api(libs.uri.template)
  api(libs.slf4j.api)
  api(libs.kotlinx.coroutines.core.jvm)
  api(libs.bundles.jackson)
  api(libs.zalando.problem)

  implementation(libs.zalando.jacksonProblem)

  testFixturesApi(libs.junit.jupiter.api)
  testFixturesApi(libs.bundles.strikt)
  testFixturesApi(libs.kotlinx.coroutines.core.jvm)
  testFixturesApi(libs.kotlinx.coroutines.test)
  testFixturesApi(libs.okhttp.mockwebserver)
}
