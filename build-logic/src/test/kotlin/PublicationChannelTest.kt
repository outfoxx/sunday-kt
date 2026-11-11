import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicationChannelTest {
  @Test
  fun `stable releases retain Maven Central`() {
    assertEquals(PublicationChannel.MAVEN_CENTRAL, PublicationChannel.forVersion("2.0.0"))
    assertEquals(PublicationChannel.MAVEN_CENTRAL, PublicationChannel.forVersion("12.34.56"))
  }

  @Test
  fun `betas release candidates and snapshots use GitHub Packages`() {
    for (version in listOf("2.0.0-beta.22", "2.0.0-alpha.1", "2.0.0-rc.1", "2.0.0-SNAPSHOT")) {
      assertEquals(PublicationChannel.GITHUB_PACKAGES, PublicationChannel.forVersion(version))
    }
  }

  @Test
  fun `invalid versions fail before selecting a publisher`() {
    for (version in listOf("", "main", "v2.0.0", "2.0", "2.0.0-", "2.0.0-beta..1", "2.0.0\n")) {
      assertFailsWith<IllegalArgumentException> { PublicationChannel.forVersion(version) }
    }
  }
}
