
plugins {
  alias(libs.plugins.sonarqube)
  alias(libs.plugins.dokka)
  alias(libs.plugins.github.release)
  alias(libs.plugins.kotlin.jvm) apply false // For dokka even though plugins are applied by conventions
}

val releaseVersion: String by project

group = "io.outfoxx.sunday"
version = releaseVersion

val moduleNames = listOf("core", "jdk", "okhttp")

//
// ANALYSIS
//

sonar {
  properties {
    property("sonar.projectName", "sunday-kt")
    property("sonar.projectKey", "outfoxx_sunday-kt")
    property("sonar.organization", "outfoxx")
    property("sonar.host.url", "https://sonarcloud.io")
  }
}


//
// DOCS
//

dokka {
  dokkaPublications.html {
    outputDirectory.set(layout.buildDirectory.dir("dokka/${releaseVersion}"))
  }
}


//
// RELEASING
//

githubRelease {
  owner = "outfoxx"
  repo = "sunday-kt"
  tagName = releaseVersion
  targetCommitish = "main"
  releaseName = "🚀 v${releaseVersion}"
  generateReleaseNotes = true
  draft = false
  prerelease = !releaseVersion.matches("""^\d+\.\d+\.\d+$""".toRegex())
  releaseAssets.from(
    moduleNames.flatMap { moduleName ->
      listOf("", "-javadoc", "-sources").map { suffix ->
        file("$rootDir/$moduleName/build/libs/sunday-$moduleName-$releaseVersion$suffix.jar")
      }
    }
  )
  overwrite = true
  authorization = "Token " + (project.findProperty("github.token") as String? ?: System.getenv("GITHUB_TOKEN"))
}
