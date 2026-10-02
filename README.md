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
val transport = DefaultFactories.transport(
  URITemplate("https://api.example.com"), providerId = "jdk", tokenManager = tokens,
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
issuer. Managed requests disable native redirects and ambient authentication. An explicit
Bearer `invalid_token` challenge can recover once for a bodyless GET, HEAD, or OPTIONS.
403 responses and unsafe/body-carrying requests do not replay. An event subscription shares
that single recovery across reconnects; closing it cancels pending acquisition.

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
