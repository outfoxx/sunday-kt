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
  api(platform(libs.quarkus.bom))
  api(project(":sunday-core"))
  api(libs.mutiny)
  api("io.quarkus:quarkus-oidc-client")
  api("io.quarkus:quarkus-rest-client")
  api("io.quarkus:quarkus-smallrye-fault-tolerance")

  testImplementation(libs.quarkus.junit5)
  testImplementation(libs.okhttp.mockwebserver)
}
