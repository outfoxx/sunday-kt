import java.util.concurrent.TimeUnit

/** Waits for an owned helper and reaps it before its working directory can be removed. */
internal object OAuthProcess {
  fun waitFor(process: Process, timeoutSeconds: Long) {
    try {
      check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0) {
        "OAuth infrastructure process failed"
      }
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
      }
    }
  }
}
