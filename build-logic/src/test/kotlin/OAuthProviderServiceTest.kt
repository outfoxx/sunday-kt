import org.gradle.testfixtures.ProjectBuilder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OAuthProviderServiceTest {
  @Test
  fun `macOS CI selects Java without discovering Docker`() {
    for (ci in listOf("1", "true", "yes", "github")) {
      assertEquals("keycloak-java", OAuthProviderService.backend("live", "Mac OS X", ci))
      assertEquals("keycloak-container", OAuthProviderService.backend("live", "Linux", ci))
      assertEquals("wiremock-java", OAuthProviderService.backend("replay", "Mac OS X", ci))
    }
  }

  @Test
  fun `disabled CI permits containers`() {
    for (ci in listOf("", "0", "false", "FALSE")) {
      assertEquals("keycloak-container", OAuthProviderService.backend("live", "Mac OS X", ci))
    }
  }

  @Test
  fun `invalid modes fail rather than replay`() {
    assertFailsWith<IllegalArgumentException> {
      OAuthProviderService.backend("automatic", "Mac OS X", "true")
    }
  }
  @Test
  fun `failed startup and readiness timeout remove the owned directory`() {
    val root = Files.createTempDirectory("oauth-service-test-")
    try {
      val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
      for (failCommand in listOf(false, true)) {
        val service = project.gradle.sharedServices.registerIfAbsent("oauth-$failCommand", UnreadyService::class.java) {
          parameters.mode.set("replay")
          parameters.ci.set("true")
          parameters.cache.set(root.resolve("cache").toFile())
        }.get()
        service.failCommand = failCommand
        service.startupTimeoutSeconds = 0
        val failure = assertFailsWith<IllegalStateException> { service.start() }
        assertEquals("OAuth infrastructure startup failed (wiremock-java)", failure.message)
        assertFalse(Files.exists(service.ownedDirectory))
        service.close()
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `failed artifact verification removes partial downloads and rejects corrupt caches`() {
    val root = Files.createTempDirectory("oauth-artifact-test-")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/provider.jar") { exchange ->
      val bytes = "tampered".toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
      val cache = root.resolve("cache")
      val service = project.gradle.sharedServices.registerIfAbsent("oauth-cache", OAuthProviderService::class.java) {
        parameters.mode.set("replay")
        parameters.ci.set("true")
        parameters.cache.set(cache.toFile())
      }.get()
      val url = "http://127.0.0.1:${server.address.port}/provider.jar"
      assertFailsWith<IllegalStateException> { service.artifact(url, "invalid") }
      Files.list(cache).use { assertEquals(0L, it.count()) }
      Files.writeString(cache.resolve("provider.jar"), "tampered")
      val failure = assertFailsWith<IllegalStateException> { service.artifact(url, "invalid") }
      assertEquals("OAuth artifact cache integrity failure", failure.message)
      service.close()
    } finally {
      server.stop(0)
      root.toFile().deleteRecursively()
    }
  }

  abstract class UnreadyService : OAuthProviderService() {
    var failCommand = false
    lateinit var ownedDirectory: Path
    override fun command(root: Path, port: Int, selected: String): List<String> {
      ownedDirectory = root
      if (failCommand) error("synthetic-secret")
      return listOf("sleep", "60")
    }
  }

}
