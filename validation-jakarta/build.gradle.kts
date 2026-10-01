plugins {
  id("library.conventions")
}

dependencies {
  testRuntimeOnly("org.glassfish.jersey.core:jersey-common:3.1.11")
  api(project(":sunday-validation-core"))
  implementation(project(":sunday-core"))
  compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
  testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
  api("jakarta.validation:jakarta.validation-api:3.1.1")
  testImplementation("org.hibernate.validator:hibernate-validator:8.0.5.Final")
}
