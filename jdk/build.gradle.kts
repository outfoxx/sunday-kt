
val slf4jVersion: String by project
val kotlinCoroutinesVersion: String by project
val jacksonVersion: String by project

val okhttpVersion: String by project

dependencies {

  api(project(":sunday-core"))

  implementation("org.slf4j:slf4j-api:$slf4jVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$kotlinCoroutinesVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:$kotlinCoroutinesVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
