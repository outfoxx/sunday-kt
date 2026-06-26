plugins {
  id("library.conventions")
  alias(libs.plugins.quarkus)
}

tasks.named("compileKotlin") {
  dependsOn("compileQuarkusGeneratedSourcesJava")
}

tasks.matching { it.name == "runKtlintCheckOverMainSourceSet" }.configureEach {
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

  api(libs.mutiny)
  api(libs.mutiny.vertx.core)

  implementation(libs.quarkus.rest)
  implementation(libs.resteasy.reactive)
  implementation(libs.resteasy.reactive.vertx)

  testImplementation(libs.quarkus.junit5)
}
