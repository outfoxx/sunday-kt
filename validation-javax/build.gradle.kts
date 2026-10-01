plugins {
  id("library.conventions")
}

dependencies {
  testRuntimeOnly("org.glassfish.jersey.core:jersey-common:2.41")
  api(project(":sunday-validation-core"))
  implementation(project(":sunday-core"))
  compileOnly("javax.ws.rs:javax.ws.rs-api:2.1.1")
  testImplementation("javax.ws.rs:javax.ws.rs-api:2.1.1")
  api("javax.validation:validation-api:2.0.1.Final")
  testImplementation("org.hibernate.validator:hibernate-validator:6.2.5.Final")
}
