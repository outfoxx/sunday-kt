plugins {
  id("library.conventions")
  alias(libs.plugins.quarkus)
}

tasks.named("compileKotlin") {
  dependsOn("compileQuarkusGeneratedSourcesJava")
}

tasks
  .matching {
    it.name in setOf("runKtlintCheckOverMainSourceSet", "runKtlintFormatOverMainSourceSet")
  }.configureEach {
    dependsOn("compileQuarkusGeneratedSourcesJava")
  }

tasks.matching { it.name == "sourcesJar" }.configureEach {
  dependsOn("compileQuarkusGeneratedSourcesJava")
}

tasks.matching { it.name.startsWith("dokkaGeneratePublication") }.configureEach {
  dependsOn("compileQuarkusGeneratedSourcesJava")
}

dependencies {

  implementation(platform(libs.quarkus.bom))

  api(project(":sunday-validation-jakarta"))
  implementation("io.quarkus:quarkus-hibernate-validator")

  api("io.quarkus:quarkus-security:${libs.versions.quarkus.get()}")
  api("io.quarkus:quarkus-vertx-http:${libs.versions.quarkus.get()}")

  api(libs.mutiny)
  api(libs.mutiny.vertx.core)

  implementation(libs.quarkus.rest)
  implementation(libs.resteasy.reactive)
  implementation(libs.resteasy.reactive.vertx)

  testImplementation(project(":sunday-core"))
  testImplementation(libs.quarkus.junit5)
  testImplementation("io.quarkus:quarkus-rest-jackson")
  testImplementation("io.quarkus:quarkus-rest-client-jackson")
}
