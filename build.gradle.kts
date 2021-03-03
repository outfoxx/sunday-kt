import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  java
  kotlin("jvm")
}

val mavenGroup: String by project
val mavenVersion: String by project

val slf4jVersion: String by project
val kotlinCoroutinesVersion: String by project
val uriTemplateVersion: String by project
val zalandoProblemVersion: String by project
val okHttpVersion: String by project

val jacksonVersion: String by project

val junitVersion: String by project
val hamcrestVersion: String by project

group = mavenGroup
version = mavenVersion

repositories {
  mavenCentral()
}

dependencies {

  implementation(platform("com.squareup.okhttp3:okhttp-bom:$okHttpVersion"))

  api("org.slf4j:slf4j-api:$slf4jVersion")
  api("org.zalando:problem:$zalandoProblemVersion")
  api("org.zalando:jackson-datatype-problem:$zalandoProblemVersion")
  api("com.github.hal4j:uritemplate:$uriTemplateVersion")

  api("com.squareup.okhttp3:okhttp")
  implementation("com.squareup.okhttp3:okhttp-sse")

  implementation(kotlin("stdlib"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

  //
  // TESTING
  //

  testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

  testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
  testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

  testImplementation("org.hamcrest:hamcrest-library:$hamcrestVersion")

  testImplementation("com.squareup.okhttp3:mockwebserver")
}


tasks {

  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = "11"
    }
  }

  test {
    useJUnitPlatform()
  }

}
