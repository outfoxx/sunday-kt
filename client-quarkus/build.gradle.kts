plugins {
  id("library.conventions")
}

dependencies {
  api(platform(libs.quarkus.bom))
  api(project(":sunday-core"))
  api(libs.mutiny)
  api("io.quarkus:quarkus-oidc-client")
  api("io.quarkus:quarkus-rest-client")
  api("io.quarkus:quarkus-smallrye-fault-tolerance")
}
