plugins {
  id("library.conventions")
}

tasks.named("check") {
  dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

val oauthMode = providers.gradleProperty("oauthTestMode")
  .orElse(providers.environmentVariable("SUNDAY_OAUTH_TEST_MODE")).orElse("replay")
require(oauthMode.get() in setOf("replay", "live")) { "oauthTestMode must be replay or live" }
val oauthProvider = gradle.sharedServices.registerIfAbsent("oauthProvider", OAuthProviderService::class) {
  parameters.mode.set(oauthMode)
  parameters.ci.set(providers.environmentVariable("CI").orElse(""))
  parameters.cache.set(rootProject.layout.projectDirectory.dir(".gradle/oauth-artifacts"))
  maxParallelUsages.set(1)
}

tasks.withType<Test>().configureEach {
  usesService(oauthProvider)
  inputs.property("oauthTestMode", oauthMode)
  if (oauthMode.get() == "live") outputs.upToDateWhen { false }
  doFirst(ConfigureOAuthTest(oauthProvider))
}
