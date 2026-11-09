plugins {
  id("oauth-tests.conventions")
}

tasks.test {
  systemProperty("kotlinx.coroutines.debug", "on")
  useJUnitPlatform { excludeTags("constrained-io") }
}

val constrainedDispatcherTest by tasks.registering(Test::class) {
  description = "Verifies native cancellation with a single blocking I/O worker."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("constrained-io") }
  systemProperty("kotlinx.coroutines.io.parallelism", "1")
  shouldRunAfter(tasks.test)
}

val wrappedStopTestWithoutRecovery by tasks.registering(Test::class) {
  description = "Verifies wrapped stop signals without coroutine debug stack-trace recovery."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("wrapped-stop") }
  systemProperty("kotlinx.coroutines.debug", "off")
  systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
  shouldRunAfter(tasks.test)
}

tasks.check {
  dependsOn(constrainedDispatcherTest, wrappedStopTestWithoutRecovery)
}
