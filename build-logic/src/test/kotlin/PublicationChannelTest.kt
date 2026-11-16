import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicationChannelTest {
  @Test
  fun `stable releases publish to Central and GitHub Packages`() {
    for (version in listOf("2.0.0", "12.34.56")) {
      assertEquals(
        listOf("publishToMavenCentral", "publishAllPublicationsToGitHubPackagesRepository"),
        PublicationChannel.forVersion(version).taskNames,
      )
    }
  }

  @Test
  fun `betas and release candidates publish to Central and GitHub Packages`() {
    for (version in listOf("2.0.0-beta.23", "2.0.0-alpha.1", "2.0.0-rc.1")) {
      assertEquals(
        listOf("publishToMavenCentral", "publishAllPublicationsToGitHubPackagesRepository"),
        PublicationChannel.forVersion(version).taskNames,
      )
    }
  }

  @Test
  fun `snapshots remain on GitHub Packages`() {
    for (version in listOf("2.0.0-SNAPSHOT", "2.0.0-beta.23-SNAPSHOT")) {
      assertEquals(
        listOf("publishAllPublicationsToGitHubPackagesRepository"),
        PublicationChannel.forVersion(version).taskNames,
      )
    }
  }

  @Test
  fun `invalid versions fail before selecting a publisher`() {
    for (version in listOf("", "main", "v2.0.0", "2.0", "2.0.0-", "2.0.0-beta..1", "2.0.0\n")) {
      assertFailsWith<IllegalArgumentException> { PublicationChannel.forVersion(version) }
    }
  }
}
