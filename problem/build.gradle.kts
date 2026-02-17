plugins {
  id("library.conventions")
}

dependencies {

  api(project(":sunday-core"))

  testImplementation(project(":sunday-jdk"))
  testImplementation(testFixtures(project(":sunday-core")))
}
