Sunday 🙏 The framework of REST for Kotlin
===

![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/outfoxx/sunday-kt/ci.yml?branch=main)
![Coverage](https://sonarcloud.io/api/project_badges/measure?project=outfoxx_sunday-kt&metric=coverage)
![Maven Central](https://img.shields.io/maven-central/v/io.outfoxx.sunday/sunday-core.svg)
![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/https/oss.sonatype.org/io.outfoxx.sunday/sunday-core.svg)

Kotlin framework for generated REST clients.

### [Read the Documentation](https://outfoxx.github.io/sunday)

---

Maven
-----

Sunday is delivered as a set of Maven artifacts and releases are available from Maven Central.
Since 1.0.0-beta.24, the previous monolithic `sunday` artifact has been split into modules.

Artifacts
---------

- `sunday-core`: Core types, client abstractions, and credential lifecycle contracts.
- `sunday-validation-jakarta` / `sunday-validation-javax`: Native Bean Validation adapters; choose the namespace used by your application.
- `sunday-validation-core`: Shared schema constraint support used by both validation adapters.
- `sunday-okhttp`: OkHttp-based transport implementation.
- `sunday-jdk`: JDK `HttpClient`-based transport implementation.
- `sunday-jaxrs-quarkus`: Quarkus REST/JAX-RS support utilities.
- `sunday-client-quarkus`: Native named OIDC acquisition and bounded recovery for generated Quarkus clients.
- `sunday-broker`: Protocol-neutral broker operation support for generated AsyncAPI clients.
- `sunday-problem`: Default RFC7807 problem type (`SundayHttpProblem`) and adapters.
- `sunday-problem-quarkus`: Quarkus `HttpProblem` integration.
- `sunday-problem-zalando`: Zalando `problem` integration.

### Dependency Declaration

##### Gradle

```kotlin
implementation("io.outfoxx.sunday:sunday-core:$version")
implementation("io.outfoxx.sunday:sunday-okhttp:$version") // or sunday-jdk
implementation("io.outfoxx.sunday:sunday-jaxrs-quarkus:$version") // Quarkus REST/JAX-RS server support
implementation("io.outfoxx.sunday:sunday-problem:$version") // or sunday-problem-quarkus / sunday-problem-zalando
```

##### Maven

```xml
<dependency>
  <groupId>io.outfoxx.sunday</groupId>
  <artifactId>sunday-core</artifactId>
  <version>${sunday.version}</version>
</dependency>
<dependency>
  <groupId>io.outfoxx.sunday</groupId>
  <artifactId>sunday-okhttp</artifactId>
  <version>${sunday.version}</version>
</dependency>
<dependency>
  <groupId>io.outfoxx.sunday</groupId>
  <artifactId>sunday-problem</artifactId>
  <version>${sunday.version}</version>
</dependency>
```

Default Factories
-----------------

Transport and problem modules now register SPI providers for automatic discovery.
Use `DefaultFactories` to create instances without wiring implementations manually:

```kotlin
val transport = DefaultFactories.transport(
  URITemplate("https://api.example.com"),
)
```

If multiple providers are on the classpath, specify which one to use:

```kotlin
val transport = DefaultFactories.transport(
  URITemplate("https://api.example.com"),
  providerId = "okhttp", // "okhttp" or "jdk"
)

val problemFactory = DefaultFactories.problemFactory(
  providerId = "quarkus", // "sunday", "quarkus", or "zalando"
)
```

Problem providers are chosen by highest priority when multiple are present; if there is a
tie, or if multiple transport providers are present, specify `providerId` explicitly.

Broker Decode Recovery
----------------------

`sunday-broker` provides `Flow<BrokerRawDelivery>.decodeDeliveries` for consumers that
need to handle an undecodable message and then continue consuming. It accepts a
`BrokerDecodeFailureHandler` with the consume specification, original raw delivery,
and decoding exception. Without a handler, it rethrows the decoding exception.

The handler owns recovery and settlement. Returning skips the failed delivery and
allows consumption to continue; throwing stops collection. The operator never
acknowledges or negatively acknowledges a delivery itself. If recovery transfers
the message to durable quarantine, the handler must wait for the required transfer
confirmation before acknowledging the original delivery. A failed transfer must
propagate, leaving reconnect/requeue policy to the application transport.

Only decoding failures reach this handler. Transport errors and downstream processing
errors propagate without invoking recovery. Cancellation and JVM errors also propagate,
including when wrapped by a codec. Handlers should honor coroutine cancellation and
must not swallow failed transfers. Retry policy, quarantine storage, and reconnection
remain application responsibilities, not runtime defaults.

Major Changes Since 1.0.0-beta.24
---------------------------------

- New problem abstraction (`Problem`, `ProblemFactory`, `ProblemAdapter`) decouples Sunday from
  any specific problem library while still allowing integration modules.
- Failure responses now decode RFC7807 problems into registered types or `ProblemFactory`-built
  problems; non-problem error bodies are attached as `responseText` or `responseData` extensions.
- Added `io.outfoxx.sunday.http.Status` to model HTTP status codes and reason phrases consistently
  across core and problem modules.


Environment-aware credentials
-----------------------------

Generated clients select logical security bindings from the explicitly chosen profile.
Register the matching provider names on the application's `TokenManager`, and supply that
manager to the transport. Credentials and token storage stay in application configuration.

```kotlin
val provider = JdkOAuthTokenProvider(
  OAuthTokenProvider.Configuration(
    identity = "external-service",
    clientId = applicationClientId,
    clientSecret = applicationClientSecret,
    authentication = OAuthTokenProvider.Authentication.ClientSecretBasic,
  ),
)
val tokens = TokenManager(mapOf("external" to provider))
val authentication = AuthenticationAdapter(mapOf("external" to tokens))
val transport = DefaultFactories.transport(
  URITemplate("https://api.example.com"), providerId = "jdk",
  requestAdapter = authentication, responseAdapter = authentication,
)
```

Use `OkHttpOAuthTokenProvider` with the OkHttp backend. Both native providers support
client credentials and application-managed authorization code/PKCE. Interactive sessions
need a distinct `grantIdentity` and an authorization callback returning a fresh code,
redirect URI, and PKCE verifier. Failed refreshes require fresh application authorization;
a consumed authorization code is never reused. External/static credentials can implement
`TokenProvider` directly. Implement `TokenProvider.Refreshing` when renewal is supported.

`TokenManager` partitions credentials by provider/client identity, profile, acquisition
endpoints, scopes, audience/resource, and grant identity. It coalesces concurrent renewal,
applies expiry skew, preserves refresh-token rotation, and cancels acquisition when its
last waiting caller cancels. Close it when the owning application/session ends. Supply a
`TokenStore` for application-managed persistence; tokens must not appear in generated code.

Endpoint overrides change acquisition only; discovery verifies the independently configured
issuer on acquisition and renewal. Public authorization-code/PKCE sessions configured with
`Authentication.None` accept discovery that omits `none` (as Keycloak does), including an
absent authentication-method list. Any supplied list must still contain only strings.
Confidential clients require their configured method to be advertised; an absent list
defaults to `client_secret_basic`. Endpoint overrides never bypass issuer validation.
Credential-bearing requests disable native redirects and ambient authentication. Transports do not
classify bearer challenges or retry authentication. An explicitly installed authentication adapter reports
rejections to their owners; a subsequent application request may use renewed credentials.

Native model validation
-----------------------

Generated Kotlin models use Bean Validation rather than a second generated validation API.
Use `validator.validate(model, ModelMode.Request::class.java)` for client sends/server inputs
and `ModelMode.Response::class.java` for server outputs/client reads. Both groups include
ordinary schema constraints. Unknown declared fallbacks are strict in request mode unless
the schema explicitly allows them there. Constructors use response mode. Always select a payload
mode explicitly: dynamic extension containers cascade that mode to their current model values,
including values inside nested lists and maps. The native `Default` group alone does not select
a payload mode. Bind `ModelValidation.validatorProvider` when using a customized validator so
constructor and dynamic-container adapters share the application's configuration.

Select `io.outfoxx.sunday.validation.jakarta.ModelMode` with Jakarta/Hibernate Validator 8+
or the `javax` package with Hibernate Validator 6. A compatible Bean Validation provider
must be present. Framework integrations bind `ModelValidation.validatorProvider` to their
managed `Validator`; standalone applications may use its default factory. Generated request
hooks validate current values on every execution, including deferred operations.

Native Quarkus clients
----------------------

Profiled generated clients use `sunday-client-quarkus` and application-configured named OIDC
providers. Quarkus owns token acquisition, caching, expiry renewal, and refresh-token rotation.
The generated configuration source isolates clients by the selected profile, endpoints, and scopes;
credentials remain in application configuration.

Call the generated public operation. Its native `WithRetry` boundary and concrete `Transport`
method share one `ClientInvocation`; no second retry loop is added. Authentication can recover
once for a bodyless GET, HEAD, or OPTIONS with a Bearer `invalid_token` challenge. Recovery
consumes the declared native retry budget, or one authentication-only retry when no policy is
declared. Repeated 401s, 403s, unsafe requests, and missing challenges never cause an additional
authentication replay. Applications receive the original native exceptions.

Each deferred subscription receives a fresh invocation. Cancellation reaches the native
completion-stage or reactive transport. Streamed subscriptions are not automatically replayed.
Generated helpers are implementation boundaries; native method-specific policy configuration
must name the corresponding `WithRetry` or `Transport` method.

License
-------

    Copyright 2021 Outfox, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

Credentials are isolated by logical security scheme as well as provider and acquisition inputs.
Discovery metadata is fetched and verified on each acquisition or renewal. Temporary provider outages
allow event connections to reconnect; a rejected refresh grant triggers fresh client credentials only
for the client-credentials flow. Interactive sessions require fresh application authorization.
Built-in OAuth providers retain at most 1,024 consumed authorization-code hashes per provider instance.
After this limit, create a provider for a newly authorized application session; old hashes are never
evicted to allow code reuse. Refresh exchanges do not consume this history.

### Request and response adapters

`RequestAdapter` and `ResponseAdapter` are independent suspendable interfaces on Sunday's
shared `Request<TReq>` and `Response<TRes>` wrappers. Configure either slot on `JdkTransport`, `OkHttpTransport`,
`TransportConfig`, `DefaultFactories.transport`, or the client-settings factory extensions.
No response behavior is inferred from a request adapter, even when it implements both interfaces.

```kotlin
val trace = object : RequestAdapter {
  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
    request.copy(headers = request.headers + ("X-Trace" to traceId))
}
val observe = object : ResponseAdapter {
  override suspend fun <TRes> adapt(response: Response<TRes>, request: Request<*>): Response<TRes> {
    recordStatus(request.uri, response.statusCode)
    return response
  }
}
val transport = DefaultFactories.transport(
  URITemplate("https://api.example.com"), providerId = "okhttp",
  requestAdapter = trace.chainTo(signRequest),
  responseAdapter = observe.chainTo(validateResponse),
)
```

The native type is preserved through copies, adapter chains, authorization and request export.
Adapters implement generic methods so the same instance can serve different transports; Kotlin SAM
lambdas cannot express this contract. `Transport<TReq, TRes>`, `Operation<B, R, TReq, TRes>` and `EventSource<TReq, TRes>`
carry native types through execution and export. For example, an OkHttp export's `request.transport` is statically an
`okhttp3.Request`, including when an adapter returns a delegating wrapper. Decorators must preserve
their behavior in `copy` and `withBody` overrides.

`Response<TRes>.transport` exposes the underlying native response. Adapted status, headers and body
remain authoritative through the common response interface; changing them does not rebuild the native
response. `Response.copy`, response chains and `OperationResponse<R, TRes>` retain a known native type.
Execution returns `Response<TRes>`, decoded results retain `OperationResponse<R, TRes>`, and streaming
returns `Flow<ResponseEvent<TRes>>`. Shared decoding and policy helpers use `Response<*>` when they
only need the common HTTP fields.

`chainTo` and the `RequestAdapters` / `ResponseAdapters` constructors build flat, ordered chains.
Errors stop the remaining adapters. An exception implementing `RequestCancellationException` is an
unconditional stop signal anywhere in the cause chain: Sunday prevents retry and SSE reconnect and
preserves the signal through exception causes. Diagnostic wrappers, native wrappers and coroutine
debug recovery copies are supported, so top-level reference identity is not guaranteed.
`Throwable.isRequestCancelled` recognizes request and coroutine cancellation through the cause chain.
`Throwable.causes()` traverses the exception and its causes safely, including cyclic chains. A request
stop does not cancel the parent coroutine. Coroutine `CancellationException` also stops work;
ordinary failures and returned responses remain subject to normal transport and reconnect policy.

Body repeatability is independent of content length and operation idempotency. Follow-ups retaining
a body require `Request.isBodyRepeatable`; a redirect that drops the body does not. OkHttp one-shot
and duplex bodies cannot be replayed, including zero-length bodies. JDK native recovery may resubscribe
to repeatable publishers. For adapter-declared non-repeatable bodies, managed execution permits only
one subscription; a second fails with a request stop signal before reopening the body. The JDK may
skip subscribing to a zero-length publisher entirely. Custom request implementations must explicitly declare
repeatability; the default is conservative. Adapter-declared non-repeatability survives effective-request
copies and is also passed to OkHttp's native one-shot body flag, including for zero-length bodies. These checks do not broaden authentication recovery policy.

An adapter runs once per Sunday-managed attempt: adapt the request, execute a native client call,
adapt the returned response, then apply Sunday's body collection, redirects, status validation and
authentication recovery. Native recovery can retransmit the adapted request and consume intermediate
responses inside that call. Request adapters do not regenerate credentials, signatures or nonces for
those transmissions; response adapters see the returned response. Sunday retry budgets count managed
attempts, not wire transmissions. A stop exception takes effect when the adapter throws it. The execution loop
belongs to the transport; requests carry only native settings and immutable credential metadata.
Use `transport.transportResponse(request)` or `transport.start(request, budget)` for managed execution.
`Request<TReq>` represents request fields and body resources; it does not execute requests or own
HTTP clients or redirect policy. `RequestExecution<TReq, TRes>` uses native-send and redirect callbacks
supplied by the transport. Native wrappers contain only the native request; credential metadata lives
in a shared decorator that preserves it across copies. Standalone `EventSource` construction requires
an explicit `requestStarter` callback.

`transportRequest()` exports an optionally adapted/authorized request exactly once. The consumer
executes its typed `request.transport` through their own native client and owns subsequent handling.
For example, an OkHttp consumer can call `client.newCall(request.transport).execute().use { ... }`.
Passing an export back through managed execution invokes adapters again; it is not the export path.

Install `AuthenticationAdapter` explicitly in both slots to use providers. Settings convenience factories
compose this adapter outside transport execution. Providers own acquisition, invalidation and renewal;
provide a `TokenManager` only when Sunday should cache. Rejection feedback runs once without causing
a transport retry, including for POST. A subsequent explicit request can acquire replacement credentials.

`Request.copy` preserves native settings and body ownership. `withBody(StreamingBody.source { ... })`
installs a replacement replayable source factory. For native changes at a transport-specific boundary, use the typed `request.transport`
with `HttpRequest.newBuilder(original) { _, _ -> true }` (or `copyToBuilder`), or
`request.transport.newBuilder()` for OkHttp, then return `request.copy(transport = rebuilt)`.
`Response.copy` preserves the source and lazy trailers. Copies do not consume or duplicate streams.
OkHttp trailer inspection uses `peekTrailers()` so it never blocks or discards unread body bytes.
An adapter that consumes a source must replace it before returning; Sunday never reconstructs a consumed
resource on the adapter's behalf. Body I/O inside an adapter should run on `Dispatchers.IO`.

JDK managed calls use the standard streaming body subscriber; OkHttp retains its native body source.
RPC bodies are collected in bulk, and SSE reads bounded chunks. Coroutine cancellation cancels the
OkHttp call or closes the JDK subscriber input stream; the reader then closes its buffered source.
Cancellation follows each returned response through composed adapters and final body collection.
A replacement response that owns different I/O must implement thread-safe `cancel()` for that I/O;
its reader retains responsibility for closing the buffered source. This also covers body reads inside
response adapters. JDK request copies
use the platform's complete builder copy. OkHttp source transfer uses Okio directly. Body-factory stop exceptions survive native callback/future wrappers unchanged.

The JDK client is immutable and cannot disable redirects per call. Custom clients must be configured
with `HttpClient.Redirect.NEVER`; set `JdkTransport.redirectPolicy` to `NORMAL` or `ALWAYS` to follow
redirects after response adaptation. The default transport configures this automatically. Credentialed
JDK calls also require no ambient authenticator or cookie handler. OkHttp derives clients that share
its connection pool and dispatcher while disabling automatic redirects and connection retries.
Credentialed calls suppress native redirects and ambient authentication/cookies, ensuring that
`401 invalid_token` reaches Sunday's authentication adapter. OkHttp's native status-based recovery
remains enabled: a `503 Retry-After: 0` or a `421` on a coalesced HTTP/2 connection can result in another
transmission within the same Sunday attempt. Native body replay restrictions still apply; a replayable
POST can be resent. Native observers receive the real responses, including intermediate statuses.
Raw exported requests retain their native behavior.
Response adapters receive the effective request after native interceptor changes, so token rejection
is attributed only to the credential actually sent. The unused `OkHttpRequest.requestDispatcher`
constructor parameter has been removed; blocking response collection uses `Dispatchers.IO`.

### Typed request parameters

`OperationSpec.parameterValidation` and the transport overloads accept a `ParameterValidator` for
captured typed parameters. JDK and OkHttp transports invoke it before encoding on every request build,
including bodyless requests and event streams. Custom transports must invoke the callback at that same
boundary. Generated callbacks delegate to native Bean Validation in request mode.

The JAX-RS `ClientModelValidation` provider validates generated tolerant scalar parameters, including
Kotlin covariant collection elements, during wire conversion. Register it on non-MicroProfile clients;
generated MicroProfile clients register it automatically. Server parameter constraints use the native
validator before application invocation.

Parameter validation failures throw `ParameterValidator.Failure` with the native error as the cause.
Event sources close on this failure, and event flows fail instead of reconnecting. Custom transports
should invoke `ParameterValidator.validateRequest()` before encoding typed parameters.

## Partial updates

Use `UpdateOp<T>` for fields that can be set but not deleted, and `PatchOp<T>` for fields that can
also be deleted. `PatchOp.none()` leaves a field unchanged, `PatchOp.set(value)` supplies an update,
and `PatchOp.delete()` writes JSON null to delete a member. Choose the operation type according to
whether the member may be removed, independently of whether its value may be null. Annotate patch models with Jackson's
`@JsonInclude(NON_EMPTY)` to omit unchanged fields. Jackson rejects JSON null for `UpdateOp` with a
mapping error and preserves it as deletion for `PatchOp`, including root and collection positions.

## URI template variables

`URITemplate.resolve` expands missing and `null` variables as undefined under RFC 6570.
For an undefined `id`, `/items{/id}` becomes `/items`, while `/items/{id}` becomes
`/items/` because the literal slash remains. Empty strings remain defined: an empty
`id` in `/items{/id}` produces `/items/`. Explicit `null` overrides a parameter
stored on the template; omitting the override keeps the stored value. This applies
to template expressions in both the base URI and the operation path.

## Explicit standard authentication

Choose standard authentication once in the transport factory supplied to generated clients.
`StandardAuthenticationAdapter` composes credential attachment, missing-provider checks, rejection
feedback, and terminal bearer-rejection cancellation. It delegates to the existing adapters, including
their body ownership rules, and ignores public/detached operation responses. Feedback runs before
cancellation. There is no hidden replay; a later explicit request can obtain renewed credentials.

The standard settings helper selects this policy on both sides. Lower-level `AuthenticationAdapter`
remains available when an application wants feedback without the standard stop policy. Ordinary
transport constructors perform no authentication and do not install this composition.

```kotlin
// Pass this factory to the generated configuration-based client factory.
val standardFactory: (ClientSettings) -> JdkTransport = { settings ->
  settings.jdkTransport(SundayHttpProblem.Factory)
}
// OkHttp applications can use settings.okhttpTransport(...) instead.

// Or construct the generated service with this caller-owned transport.
val customTransport = JdkTransport(
  baseURL, SundayHttpProblem.Factory,
  requestAdapter = auth0Link, responseAdapter = auth0Link,
)
```

Caching is a separate explicit choice: construct `TokenManager` around a Sunday OAuth provider and
supply that manager through provider credentials to the generated factory. This retains expiry renewal,
refresh rotation, and application-selected storage. A provider returned by an OAuth credential factory
can likewise be an explicitly constructed manager. Supplied external providers are used unchanged;
standard composition never wraps them, creates a second cache, closes them, or clears their storage.
Keep managers at application/session scope, share them across clients using the same credential lifecycle,
and settle outstanding requests before closing managers or clearing credentials.

Static bearer, basic, and API-key credentials use their declared placement without an OAuth cache.
Only an explicit Bearer `invalid_token` challenge for a sent bearer credential invokes the bearer stop
policy; 403s and unrelated challenges retain ordinary response handling. Authentication selection and
credential validation in generated settings remain active for the standard path. The direct-transport
path lets a custom adapter own those choices using operation metadata.

## Application-owned token lifecycle

Transport constructors perform no authentication or authorization by default. They accept request and response
adapters, with no token-provider option or authentication retry loop. External authentication frameworks can
implement these adapters directly, without using Sunday's token or security classes.

The optional `AuthenticationAdapter` acquires credentials for declared bindings, reports missing providers,
and sends matching rejection feedback to the credential owner. Install it explicitly in each desired slot;
installing a request adapter alone does not install response behavior. It adds no cache.

`BearerTokenRejectionAdapter` is a separate, opt-in response policy. It throws the cancellation marker when
an initiating request sent a bearer credential and its response is 401 with an explicit Bearer `invalid_token`
challenge. It leaves all other responses and all bodies untouched. Compose it after `AuthenticationAdapter`
when provider feedback should run before cancellation. It has no counter, renewal, or replay behavior.
The removed `AuthenticationRecoveryBudget` has no implicit replacement: applications choose their own
recovery policy, and a cancellation-marked error always stops the exchange, retries, and SSE reconnections.
An external framework (for example Auth0) remains responsible for storage, renewal and logout.
Return only its access token and optional expiry; keep its refresh token inside that framework.

The provider-settings convenience `settings.jdkTransport(...)` and `settings.okhttpTransport(...)` explicitly installs the standard authentication composition.
Use the ordinary transport constructor with your own adapters when you want to own the exchange entirely.

For Sunday's caching and refresh lifecycle, explicitly supply a `TokenManager` as the provider:

```kotlin
val tokens = TokenManager(provider = oauthProvider, store = applicationStore,
  expirySkew = Duration.ofSeconds(30), clock = applicationClock, scope = applicationScope)
val settings = ClientSettings.resolve(baseURL, alternatives,
  mapOf("identity" to ProviderCredentials(tokens)))
// Supply an external framework's provider directly instead to keep its lifecycle authoritative.
val transport = settings.jdkTransport(SundayHttpProblem.Factory)
```

Managers retain expiry skew, coalesced renewal, refresh rotation and application-selected persistence.
Call `tokens.close()` after all clients sharing the manager finish.
Transport closure does not erase the store or close a shared provider. Separate managers do not
coordinate refreshes merely because they share a store. Reuse the same manager across clients that
share a credential lifecycle, and distinguish users/environments with provider and grant identities.

Providers can implement `reject` to handle an invalid bearer token. It receives the original request
context and rejected access token, and returns whether recovery can proceed. Compare the rejected
credential with the current session before renewing: a late rejection must not invalidate a newer token
or a new login. Providers without this capability do not automatically retry. Acquisition and rejection
must not start interactive login automatically; return an authorization-required failure to the caller.
For an Auth0 adapter, retrieve through its credentials manager and force renewal through that same
manager on rejection, serializing concurrent rejection handling without maintaining a second token cache.

Request export materializes an optionally authorized native request once and hands its lifecycle to
the consumer. Exported requests retain no Sunday-managed recovery state. Executing an operation through
Sunday invokes the request adapter once per managed attempt. Authentication responses do not trigger an implicit retry;
an application may make another request after its credential owner handles the rejection.

Migration: replace transport `tokenManager`/`token_manager` and `tokenProviders`/`token_providers` arguments
with explicit request/response adapters. Replace `TokenManagerFactory` hooks with explicitly constructed
managers supplied through provider credentials. Direct built-in OAuth providers are not implicitly cached;
wrap them explicitly when Sunday should retain and refresh their tokens. For logout, stop outstanding
requests and settle refresh commits before clearing the owning framework's or application's storage.

Custom transports must honor `RequestPurpose.Export`: adapt once and detach managed execution state.
Normal operation execution and event requests adapt immediately before each Sunday-managed attempt.
