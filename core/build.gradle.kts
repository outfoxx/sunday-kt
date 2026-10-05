
plugins {
  id("library.conventions")
}

dependencies {

  api(libs.bundles.kotlin.io)
  api(libs.uri.template)
  api(libs.slf4j.api)
  api(libs.kotlinx.coroutines.core.jvm)
  api(libs.bundles.jackson)

  implementation("com.nimbusds:oauth2-oidc-sdk:11.38.2")

  testFixturesImplementation("com.microsoft.playwright:playwright:1.58.0")

  testFixturesApi(project(":sunday-problem"))
  testFixturesApi(libs.junit.jupiter.api)
  testFixturesApi(libs.bundles.strikt)
  testFixturesApi(libs.kotlinx.coroutines.core.jvm)
  testFixturesApi(libs.kotlinx.coroutines.test)
  testFixturesApi(libs.okhttp.mockwebserver)
}
