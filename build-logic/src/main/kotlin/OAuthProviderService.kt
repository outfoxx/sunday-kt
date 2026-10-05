import groovy.json.JsonOutput
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Owns a disposable OAuth provider for the build, including failures and teardown. */
abstract class OAuthProviderService : BuildService<OAuthProviderService.Parameters>, AutoCloseable {
  interface Parameters : BuildServiceParameters {
    val mode: Property<String>
    val ci: Property<String>
    val cache: DirectoryProperty
  }

  private var process: Process? = null
  private var directory: Path? = null
  private var container: String? = null
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL).build()
  internal var startupTimeoutSeconds = 120L
  private var started = false
  lateinit var issuer: String
    private set
  lateinit var base: String
    private set
  val realm = "sunday-${UUID.randomUUID()}"
  val callback = "http://127.0.0.1:49173/callback"
  val mode: String get() = parameters.mode.get()
  val backend: String get() = backend(mode, System.getProperty("os.name"), parameters.ci.get())

  @Synchronized
  fun start() {
    if (started) return
    val selected = backend
    val root = Files.createTempDirectory("sunday-oauth-")
    directory = root
    try {
      val port = ServerSocket(0).use { it.localPort }
      base = "http://127.0.0.1:$port"
      issuer = "$base/realms/$realm"
      val command = command(root, port, selected)
      process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("provider.log").toFile()).start()
      val ready = if (mode == "replay") "$base/__admin/mappings" else "$issuer/.well-known/openid-configuration"
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds)
      while (System.nanoTime() < deadline) {
        check(process?.isAlive == true)
        val status = runCatching {
          client.send(HttpRequest.newBuilder(URI(ready)).timeout(Duration.ofSeconds(1)).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode()
        }.getOrNull()
        if (status == 200) {
          started = true
          return
        }
        Thread.sleep(100)
      }
      error("Provider readiness timeout")
    } catch (failure: Exception) {
      close()
      throw IllegalStateException("OAuth infrastructure startup failed ($selected)")
    }
  }

  protected open fun command(root: Path, port: Int, selected: String): List<String> = when (selected) {
    "wiremock-java" -> listOf("java", "-jar", artifact(WIREMOCK_URL, WIREMOCK_SHA).toString(),
      "--bind-address", "127.0.0.1", "--port", port.toString())
    else -> keycloak(root, port, selected)
  }

  private fun keycloak(root: Path, port: Int, selected: String): List<String> {
    val imports = Files.createDirectory(root.resolve("import"))
    Files.writeString(imports.resolve("$realm-realm.json"), JsonOutput.toJson(realmConfiguration()))
    val options = listOf("start-dev", "--import-realm", "--http-port", port.toString(), "--hostname", base)
    if (selected == "keycloak-java") {
      val archive = artifact(KEYCLOAK_URL, KEYCLOAK_SHA)
      val extraction = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", root.toString())
        .redirectErrorStream(true).redirectOutput(root.resolve("extract.log").toFile()).start()
      OAuthProcess.waitFor(extraction, 60)
      val distribution = root.resolve("keycloak-26.2.5")
      Files.createDirectories(distribution.resolve("data/import"))
      Files.copy(imports.resolve("$realm-realm.json"), distribution.resolve("data/import/$realm-realm.json"))
      return listOf(distribution.resolve("bin/kc.sh").toString()) + options + listOf("--http-host", "127.0.0.1")
    }
    // No Docker discovery or invocation occurs before backend selection.
    container = "sunday-oauth-${UUID.randomUUID()}"
    return listOf("docker", "run", "--rm", "--name", container!!, "-p", "127.0.0.1:$port:$port",
      "-v", "$imports:/opt/keycloak/data/import:ro", KEYCLOAK_IMAGE) + options
  }

  private fun realmConfiguration(): Map<String, Any> = mapOf(
    "realm" to realm, "enabled" to true, "sslRequired" to "none", "revokeRefreshToken" to true,
    "refreshTokenMaxReuse" to 0,
    "clients" to listOf("public", "basic", "post").map { name -> mapOf(
      "clientId" to name, "enabled" to true, "publicClient" to (name == "public"),
      "secret" to "synthetic-secret", "clientAuthenticatorType" to "client-secret",
      "standardFlowEnabled" to true, "serviceAccountsEnabled" to (name != "public"),
      "redirectUris" to listOf(callback), "protocol" to "openid-connect",
      "attributes" to mapOf("pkce.code.challenge.method" to "S256"),
    ) },
    "users" to listOf(mapOf("username" to "synthetic-user", "enabled" to true,
      "email" to "synthetic@example.invalid", "emailVerified" to true, "firstName" to "Synthetic", "lastName" to "User",
      "credentials" to listOf(mapOf("type" to "password", "value" to "synthetic-password", "temporary" to false)))),
  )

  internal fun artifact(url: String, checksum: String): Path {
    val cache = parameters.cache.get().asFile.toPath()
    Files.createDirectories(cache)
    val target = cache.resolve(url.substringAfterLast('/'))
    if (Files.exists(target)) {
      check(digest(target) == checksum) { "OAuth artifact cache integrity failure" }
      return target
    }
    val temporary = Files.createTempFile(cache, "download-", ".part")
    try {
      val response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(3)).GET().build(),
        HttpResponse.BodyHandlers.ofFile(temporary))
      check(response.statusCode() == 200 && digest(temporary) == checksum) { "OAuth artifact download integrity failure" }
      Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      return target
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun digest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  @Synchronized
  override fun close() {
    container?.let { name -> runCatching {
      val removal = ProcessBuilder("docker", "rm", "-f", name).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
      OAuthProcess.waitFor(removal, 20)
    } }
    container = null
    process?.let { owned ->
      owned.destroy()
      if (!owned.waitFor(10, TimeUnit.SECONDS)) {
        owned.destroyForcibly()
        owned.waitFor(10, TimeUnit.SECONDS)
      }
    }
    process = null
    directory?.toFile()?.deleteRecursively()
    directory = null
    started = false
  }

  companion object {
    fun backend(mode: String, os: String, ci: String): String {
      require(mode in setOf("replay", "live")) { "oauthTestMode must be replay or live" }
      if (mode == "replay") return "wiremock-java"
      val enabled = ci.lowercase() !in setOf("", "0", "false")
      return if (os.lowercase().contains("mac") && enabled) "keycloak-java" else "keycloak-container"
    }
    const val WIREMOCK_URL = "https://repo.maven.apache.org/maven2/org/wiremock/wiremock-standalone/3.13.1/wiremock-standalone-3.13.1.jar"
    const val WIREMOCK_SHA = "bdf4c705e7fd61c778e59a19f75396eac4520efeabfac97643a53979bd4d5716"
    const val KEYCLOAK_URL = "https://github.com/keycloak/keycloak/releases/download/26.2.5/keycloak-26.2.5.tar.gz"
    const val KEYCLOAK_SHA = "e99e5f8783ea8f1cc04140b7033ea7291ff9898a088f37399b88651d81238f88"
    const val KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak@sha256:4883630ef9db14031cde3e60700c9a9a8eaf1b5c24db1589d6a2d43de38ba2a9"
  }
}
