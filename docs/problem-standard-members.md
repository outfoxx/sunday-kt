# RFC 9457 standard members

`SundayHttpProblem` uses Jackson property annotations for `type`, `title`, `status`, `detail`, and `instance`. Null and wrong JSON categories are discarded before Kotlin creator defaults. The annotations are `ProblemString`, `ProblemReference`, and `ProblemStatus`; redeclared creator properties should use the corresponding annotation. They include `Nulls.SKIP` and property-level `NON_NULL` inclusion, so nullable extensions remain serializable.

Regenerated Problems require Sunday Kotlin 2.0.0-beta.17 or later.

No additional Jackson module registration is required beyond normal Jackson/Kotlin setup. The implementation is tested with Jackson and jackson-module-kotlin 2.21.0. Numeric status values must fit an exact integer: strings and booleans are ignored; fractional values and overflow fail. Correctly typed URI conversion errors remain errors.

Direct `{}` decoding produces `about:blank` with absent title/status/detail/instance. In HTTP transport decoding, body status wins when valid; absent status uses response status, and only `about:blank` receives a default title. Wrong-typed standard fields never become extensions. Exception bookkeeping is excluded, while explicitly supplied extensions with those names are retained.

The shared transport uses an annotated wire representation for generic Problems. Registered classes use their own annotations. Ordinary models and unrelated Throwable classes are unchanged. Companion Generator output adds annotations to creator redeclarations and normalizes standard-member constraints while retaining extension validation.

## Deferred upstream adapters

Sunday's transport normalization is separate from standalone upstream decoding. No compatibility module, wrapper, dependency migration, or upstream patch is included.

| Library | Audited dependency | Standalone decoding limitations |
| --- | --- | --- |
| Quarkus HTTP Problem | 3.21.0 | Wrong-typed string members cause casts to fail; null status causes a null-pointer failure; absent status becomes 500. Its primitive status representation cannot express absent status independently of these decoding bugs. |
| Zalando Problem | 0.27.1 | Numeric and boolean values are coerced to strings; other wrong categories are rejected. |

[RFC 9457 §3.1](https://www.rfc-editor.org/rfc/rfc9457.html#section-3.1) explicitly requires ignoring wrong-typed standard members. [RFC 7807 §3.1](https://www.rfc-editor.org/rfc/rfc7807.html#section-3.1) did not contain that explicit requirement.

Quarkus's [migration PR #62](https://github.com/quarkiverse/quarkus-http-problem/pull/62) described headers, builder flexibility, integration, and performance as reasons for introducing its own Problem class. Its later [RFC discussion #315](https://github.com/quarkiverse/quarkus-http-problem/issues/315) discussed compatibility of validation-error payloads and versioning. Neither establishes that these wrong-type decoding defects were an intentional RFC tradeoff.

Zalando core has a [1.0.0 release](https://github.com/zalando/problem/releases/tag/1.0.0); that is distinct from [problem-spring-web](https://github.com/zalando/problem-spring-web)'s maintenance status. This change retains the pinned 0.27.1 adapter dependency.
