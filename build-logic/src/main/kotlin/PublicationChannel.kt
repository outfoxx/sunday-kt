/** Publication destinations for tagged releases and snapshot development builds. */
internal enum class PublicationChannel(val taskNames: List<String>) {
  RELEASE(listOf("publishToMavenCentral", "publishAllPublicationsToGitHubPackagesRepository")),
  SNAPSHOT(listOf("publishAllPublicationsToGitHubPackagesRepository")),
  ;

  companion object {
    fun forVersion(version: String): PublicationChannel {
      require(version.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?"""))) {
        "Expected a release or prerelease version"
      }
      return if (version.endsWith("-SNAPSHOT")) SNAPSHOT else RELEASE
    }
  }
}
