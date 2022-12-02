
plugins {
  `java-test-fixtures`
}

val okioVersion: String by project
val zalandoProblemVersion: String by project
val uriTemplateVersion: String by project

val slf4jVersion: String by project
val jacksonVersion: String by project

val junitVersion: String by project
val hamcrestVersion: String by project
val kotlinCoroutinesVersion: String by project
val okhttpVersion: String by project

dependencies {

  api("com.squareup.okio:okio:$okioVersion")
  api("org.zalando:problem:$zalandoProblemVersion")
  api("com.github.hal4j:uritemplate:$uriTemplateVersion")
  api("org.slf4j:slf4j-api:$slf4jVersion")

  implementation("org.zalando:jackson-datatype-problem:$zalandoProblemVersion")

  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  testFixturesApi("org.junit.jupiter:junit-jupiter-api:$junitVersion")
  testFixturesApi("org.hamcrest:hamcrest-library:$hamcrestVersion")
  testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinCoroutinesVersion")
  testFixturesApi("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  testFixturesApi("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
  testFixturesApi("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
