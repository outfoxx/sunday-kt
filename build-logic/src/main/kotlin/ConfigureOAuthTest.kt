import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/** Injects build-owned provider endpoints without capturing a Gradle script in the configuration cache. */
class ConfigureOAuthTest(private val service: Provider<OAuthProviderService>) : Action<Task> {
  override fun execute(task: Task) {
    val test = task as Test
    val provider = service.get()
    provider.start()
    test.systemProperty("sunday.oauth.mode", provider.mode)
    test.systemProperty("sunday.oauth.base", provider.base)
    test.systemProperty("sunday.oauth.issuer", provider.issuer)
    test.systemProperty("sunday.oauth.callback", provider.callback)
  }
}
