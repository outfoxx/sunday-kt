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
}
