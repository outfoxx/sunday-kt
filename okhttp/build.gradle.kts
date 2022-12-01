
val slf4jVersion: String by project
val okhttpVersion: String by project
val jacksonVersion: String by project

dependencies {

  api(project(":sunday-core"))

  implementation("org.slf4j:slf4j-api:$slf4jVersion")
  implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
