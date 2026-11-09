# OkHttp 5 upgrade

Evaluated the current adapter implementation against published OkHttp **5.5.1**, the latest stable release in Maven Central at the time of this investigation. The runtime now uses **5.5.1**, following an isolated migration test on the existing Java 21 and Kotlin 2.3.10 baseline.

## Applied changes

| Location | Change | Reason |
|---|---|---|
| `gradle/libs.versions.toml` | Change `okhttp` from `4.12.0` to `5.5.1`. | Updates the runtime, legacy MockWebServer, and test TLS helpers together. |
| `OkHttpResponse.trailers` | Replace the `trailers()`/`IllegalStateException` fallback with `peekTrailers()`. | OkHttp 5's `trailers()` blocks and discards unread body bytes. Sunday's accessor must remain non-blocking and must preserve the body. |
| `OkHttpResponse.body` | Remove nullable chaining on the native body. | Native `Response.body` is now non-null. |
| `OkHttpOAuthTokenProvider` | Use `response.body.string()` directly. | Same non-null body contract. |
| `OkHttpAdapterExecutionTest` | Remove the obsolete native-body `!!`; add early trailer-inspection coverage in both execution modes. | Keep compilation warning-free and protect the body-ownership contract. |

The early trailer regression inspects trailers in a response adapter before body consumption, then verifies the complete body and final trailers after both buffered and streaming execution.

## Verified compatibility

The applied upgrade passed `./gradlew check :sunday-okhttp:generatePomFileForMavenPublication --no-parallel` in the runtime worktree: 549 tests, no failures/errors/skips, and passing lint and coverage gates. Generated Maven metadata references `okhttp-jvm:5.5.1`.

- Initial compilation against 5.5.1 succeeded; the only compiler warnings were obsolete nullable body accesses. The minimal migration removes those warnings.
- Native `503 Retry-After: 0` and coalesced HTTP/2 `421` recovery remain inside one Sunday-managed attempt. Tests count actual transmissions and adapter invocations; native network observers and events receive unchanged statuses.
- Cancellation, effective credential attribution after application/network interceptor rewrites, and original error identity pass the existing regressions.
- The full scratch `./gradlew check --no-parallel` passed: 549 tests, no failures/errors/skips, plus lint and coverage gates. The prior 4.12.0 runtime passed the same full check with 548 tests; the migration adds the trailer regression.
- Existing Gradle publication configuration generates a Maven dependency on `com.squareup.okhttp3:okhttp-jvm:5.5.1`. No coordinate workaround is needed in Sunday's version catalog. This verifies generated metadata, not publication or a downstream Maven build.
- Legacy `com.squareup.okhttp3:mockwebserver` remains available and the existing fixtures compile and pass. Moving the 14 fixture/test files to `mockwebserver3` can be a separate change.
- No Java or Kotlin version change was needed. The subsequent coroutine-helper adoption aligns kotlinx-coroutines to 1.11.0, as required by okhttp-coroutines 5.5.1. The published JVM artifact depends on Okio 3.18.1; the KotlinIO/Okio bridge passes the bulk-body and streaming tests.

## Cancellation and simplification follow-up

`./gradlew check :sunday-okhttp:generatePomFileForMavenPublication --no-parallel` passes after adopting `executeAsync()`, aligning coroutines to 1.11.0, removing the unused coroutine-context budget plumbing, and sharing lazy managed-client configurations. At that stage, the suite contained 552 regular tests plus four isolated single-I/O-worker regressions, with no failures, errors, or skips; lint and coverage gates pass.

The four constrained-dispatcher regressions fail with the old I/O-dispatched cancellation observer and pass with the fixed observer. They cover buffered and streaming execution in both native transports and verify that cancellation does not concurrently close the buffered source. Additional regressions cover observer registration before unconfined response adaptation and original application exception identity when native recovery replays an upload. Context-bearing exceptions avoid coroutine debug stack-trace recovery's intentional copying of plain JDK exceptions.

Generated Maven metadata includes `okhttp-coroutines:5.5.1` as a runtime dependency. Validation log: `/tmp/sunday551-fixes-check.log`; before-fix regression evidence: `/tmp/sunday551-cancellation-before.log`.

## Body repeatability and wrapped stop signals

`Request.isBodyRepeatable` separates body reuse from content length and retry policy. OkHttp one-shot and duplex bodies cannot be reused, including zero-length bodies. Redirects that discard the body may proceed. JDK publishers retain their native repeat-subscription contract unless an adapter declares non-repeatability; managed execution then guards against a second subscription and propagates a request stop signal. Native integration regressions cover zero, known, and unknown lengths, HTTP/2 duplex bodies, retained-body redirects, body-discarding redirects, and authentication recovery in both execution modes.

Kotlin stop detection walks the complete cause chain with identity-based cycle protection. Native diagnostic wrappers and coroutine recovery copies may retain the original stop as a cause; Sunday does not add another wrapper after detecting it. Only leading `CompletionException` and `ExecutionException` wrappers are unwrapped. Detection precedes token refresh fallback and SSE reconnect classification, including failures otherwise classified as temporary. The obsolete previous-credentials authorization branch has been removed; recovery authorizes the prepared request anew.

Wrapped-stop regressions run in the normal transport suites with coroutine debugging enabled and in separate `wrappedStopTestWithoutRecovery` JVMs with recovery disabled. Both variants, plus the constrained-dispatcher cancellation suites, are part of `check`.

The final `./gradlew check :sunday-okhttp:generatePomFileForMavenPublication --no-parallel` passed with 564 regular tests, four constrained-dispatcher tests, and six additional wrapped-stop tests with recovery disabled: **574 total**, no failures, errors, or skips. Lint and coverage gates passed. Unknown request implementations default to non-repeatable; a regression verifies that zero content length alone does not authorize recovery or consume its retry budget. Validation log: `/tmp/sunday-repeat-stop-final-check.log`.

## Scope of the adapter contract

Adapters surround each Sunday-managed native call. Native recovery may resend the adapted request and consume intermediate responses. Sunday handles its own redirects and authentication recovery after adapting the returned response. Managed clients still disable connection-failure retries; native status-based recovery is retained. Credentialed calls disable native authentication so `401 invalid_token` reaches Sunday's token owner.

The effective request comes from `networkResponse.request`, falling back to the application request when there is no network response. Native responses are not rewritten or assigned substitute statuses.

## Upgrade maintenance

The migration above is applied, including the trailer regression in both execution modes. For future updates, run formatting/lint, the adapter and trailer regressions, then the full check suite and generated Maven POM inspection. Keep the native retry behavior and body/cancellation regressions as upgrade gates.

Do not bundle a new HTTP engine or broad MockWebServer migration into this update. `OkHttpRequest` now awaits headers using `okhttp-coroutines` 5.5.1 and restores original upload failures around `executeAsync()`. Sunday retains cancellation through response adaptation and body consumption; its observer resumes without requiring an I/O worker. The `constrainedDispatcherTest` tasks exercise both transports in buffered and streaming modes with I/O parallelism set to one and are included in `check`. Managed client configurations are lazy and shared across transport requests and copies, including the credentialed variant.

## Evidence

- [Maven Central release metadata](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml)
- [OkHttp changelog](https://github.com/square/okhttp/blob/parent-5.5.1/CHANGELOG.md)
- [5.5.1 response and trailer contracts](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Response.kt)
- [5.5.1 coroutine helper](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp-coroutines/src/main/kotlin/okhttp3/coroutines/ExecuteAsync.kt)

Local investigation artifacts:

- Scratch checkout: `/private/tmp/sunday-kt-okhttp551-yx42ulev`
- Applied upgrade full check: `/tmp/sunday-okhttp551-upgrade-check.log`
- Prior 4.12.0 runtime full check: `/tmp/sunday-native-retries-check.log`
- Upgrade full check: `/tmp/sunday-okhttp551-check.log`
- Publication metadata: `okhttp/build/publications/maven/pom-default.xml` in both the runtime worktree and the scratch checkout
