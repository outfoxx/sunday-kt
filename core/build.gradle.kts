
plugins {
  id("library.conventions")
}

dependencies {

  api(libs.bundles.kotlin.io)
  api(libs.uri.template)
  api(libs.slf4j.api)
  api(libs.kotlinx.coroutines.core.jvm)
  api(libs.bundles.jackson)

  implementation(libs.nimbus.oauth)

  testFixturesImplementation("com.microsoft.playwright:playwright:1.58.0")

  testFixturesApi(project(":sunday-problem"))
  testFixturesApi(libs.junit.jupiter.api)
  testFixturesApi(libs.bundles.strikt)
  testFixturesApi(libs.kotlinx.coroutines.core.jvm)
  testFixturesApi(libs.kotlinx.coroutines.test)
  testFixturesApi(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
  inputs
    .file(rootProject.layout.projectDirectory.file("test-fixtures/oauth/cases.json"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
