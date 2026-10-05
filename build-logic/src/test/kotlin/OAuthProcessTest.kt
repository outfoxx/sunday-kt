import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OAuthProcessTest {
  @Test
  fun `timeout kills and reaps an owned process`() {
    val process = ProcessBuilder("sleep", "60").start()
    assertFailsWith<IllegalStateException> { OAuthProcess.waitFor(process, 0) }
    assertFalse(process.isAlive)
  }

  @Test
  fun `unsuccessful helper cannot be treated as ready`() {
    val process = ProcessBuilder("false").start()
    assertFailsWith<IllegalStateException> { OAuthProcess.waitFor(process, 10) }
    assertFalse(process.isAlive)
  }

  @Test
  fun `successful helper is reaped`() {
    val process = ProcessBuilder("true").start()
    OAuthProcess.waitFor(process, 10)
    assertFalse(process.isAlive)
  }
}
