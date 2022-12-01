
val okioVersion: String by project
val zalandoProblemVersion: String by project
val uriTemplateVersion: String by project

val slf4jVersion: String by project
val jacksonVersion: String by project

dependencies {

  api("com.squareup.okio:okio:$okioVersion")
  api("org.zalando:problem:$zalandoProblemVersion")
  api("com.github.hal4j:uritemplate:$uriTemplateVersion")

  implementation("org.slf4j:slf4j-api:$slf4jVersion")
  implementation("org.zalando:jackson-datatype-problem:$zalandoProblemVersion")

  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

}
