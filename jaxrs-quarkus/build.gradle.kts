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

dependencies {

  implementation(enforcedPlatform(libs.quarkus.bom))

  api(libs.mutiny)
  api(libs.mutiny.vertx.core)

  implementation(libs.quarkus.rest)
  implementation(libs.resteasy.reactive)
  implementation(libs.resteasy.reactive.vertx)

  testImplementation(libs.quarkus.junit5)
  testImplementation(libs.quarkus.rest)
}
