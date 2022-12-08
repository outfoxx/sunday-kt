
val slf4jVersion: String by project
val okhttpVersion: String by project
val jacksonVersion: String by project

dependencies {

  api(project(":sunday-core"))
  api("com.squareup.okhttp3:okhttp:$okhttpVersion")

  implementation("org.slf4j:slf4j-api:$slf4jVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  testImplementation(testFixtures(project(":sunday-core")))
}
