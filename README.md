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

- `sunday-core`: Core types and client abstractions.
- `sunday-okhttp`: OkHttp-based request factory implementation.
- `sunday-jdk`: JDK `HttpClient`-based request factory implementation.
- `sunday-problem`: Default RFC7807 problem type (`SundayHttpProblem`) and adapters.
- `sunday-problem-quarkus`: Quarkus `HttpProblem` integration.
- `sunday-problem-zalando`: Zalando `problem` integration.

### Dependency Declaration

##### Gradle

```kotlin
implementation("io.outfoxx.sunday:sunday-core:$version")
implementation("io.outfoxx.sunday:sunday-okhttp:$version") // or sunday-jdk
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

Request factory and problem modules now register SPI providers for automatic discovery.
Use `DefaultFactories` to create instances without wiring implementations manually:

```kotlin
val requestFactory = DefaultFactories.requestFactory(
  URITemplate("https://api.example.com"),
)
```

If multiple providers are on the classpath, specify which one to use:

```kotlin
val requestFactory = DefaultFactories.requestFactory(
  URITemplate("https://api.example.com"),
  providerId = "okhttp", // "okhttp" or "jdk"
)

val problemFactory = DefaultFactories.problemFactory(
  providerId = "quarkus", // "sunday", "quarkus", or "zalando"
)
```

Problem providers are chosen by highest priority when multiple are present; if there is a
tie, or if multiple request providers are present, specify `providerId` explicitly.

Major Changes Since 1.0.0-beta.24
---------------------------------

- New problem abstraction (`Problem`, `ProblemFactory`, `ProblemAdapter`) decouples Sunday from
  any specific problem library while still allowing integration modules.
- Failure responses now decode RFC7807 problems into registered types or `ProblemFactory`-built
  problems; non-problem error bodies are attached as `responseText` or `responseData` extensions.
- Added `io.outfoxx.sunday.http.Status` to model HTTP status codes and reason phrases consistently
  across core and problem modules.


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
