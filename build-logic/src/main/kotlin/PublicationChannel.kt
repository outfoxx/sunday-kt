/** Publication destinations for stable releases and prerelease development builds. */
internal enum class PublicationChannel(val taskName: String) {
  MAVEN_CENTRAL("publishToMavenCentral"),
  GITHUB_PACKAGES("publishAllPublicationsToGitHubPackagesRepository"),
  ;

  companion object {
    fun forVersion(version: String): PublicationChannel {
      require(version.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?"""))) {
        "Expected a release or prerelease version"
      }
      return if ('-' in version) GITHUB_PACKAGES else MAVEN_CENTRAL
    }
  }
}
