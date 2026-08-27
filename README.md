# Kotlin Spring Commons

Reusable Kotlin/Spring commons modules for JorisJonkers-dev services.

## What It Is

`kotlin-spring-commons` is an internal JVM library that publishes independently
consumable modules under `dev.jorisjonkers`.

## Local Use

```bash
./gradlew test
```

Use the published package coordinates from internal builds; do not copy source
between repositories. Spring auto-configuration metadata is split per module.
The current source keeps the existing
`com.jorisjonkers.personalstack.common` package names while publishing
`dev.jorisjonkers` coordinates.

## Packages

| Module | Coordinate |
| --- | --- |
| `archunit-test` | `dev.jorisjonkers:kotlin-commons-archunit-test` |
| `blocks` | `dev.jorisjonkers:kotlin-commons-blocks` |
| `command` | `dev.jorisjonkers:kotlin-commons-command` |
| `crac` | `dev.jorisjonkers:kotlin-commons-crac` |
| `email` | `dev.jorisjonkers:kotlin-commons-email` |
| `events` | `dev.jorisjonkers:kotlin-commons-events` |
| `exceptions` | `dev.jorisjonkers:kotlin-commons-exceptions` |
| `identity` | `dev.jorisjonkers:kotlin-commons-identity` |
| `messaging` | `dev.jorisjonkers:kotlin-commons-messaging` |
| `observability` | `dev.jorisjonkers:kotlin-commons-observability` |
| `system-test-harness` | `dev.jorisjonkers:kotlin-commons-system-test-harness` |
| `test-support` | `dev.jorisjonkers:kotlin-commons-test-support` |
| `timing` | `dev.jorisjonkers:kotlin-commons-timing` |
| `vault` | `dev.jorisjonkers:kotlin-commons-vault` |
| `web` | `dev.jorisjonkers:kotlin-commons-web` |

## Problem Details & OpenAPI

`web` serves RFC 9457 problem documents as `application/problem+json` and, by
default, makes the generated OpenAPI spec say so. A service that depends on this
module gets, with no configuration:

- `ProblemDetail` and `FieldError` registered as schemas
- `401`, `403` and `500` declared on every operation as `application/problem+json`
- `400`, `415` and `422` additionally declared on operations that accept a request body

A response the service declares itself always wins, so a hand-annotated `404` or
`409` is never overwritten by the baseline. Statuses a service genuinely returns
beyond this set still need their own `@ApiResponse`.

Tune or opt out through `extratoast.web.problem-details`:

```yaml
extratoast:
  web:
    problem-details:
      open-api-schemas-enabled: true          # false disables both customizers
      api-error-schema-name: ProblemDetail
      field-error-schema-name: FieldError
      open-api-universal-error-statuses: [401, 403, 500]
      open-api-request-body-error-statuses: [400, 415, 422]
```

The universal list includes the security-derived statuses because every service
in the estate sits behind authentication. A service exposing genuinely anonymous
endpoints should narrow the list rather than over-promise: declaring a status an
operation cannot return makes conformance checking unable to catch a missing
handler.

Downstream exception handlers that extend `ProblemDetailSupport` should build
responses with `problemResponse(status, body)` rather than
`ResponseEntity.status(...)`, so the media type stays consistent with what the
spec advertises.

## System Test Harness

`system-test-harness` contains reusable helpers for external system tests:
generic target URL discovery, explicit image-tag validation, Testcontainers
image resolution, Playwright browser lifecycle/sharding, and simple HTTP smoke
checks. Callers own all service names, fixture data, target URLs, credentials,
and scenarios.

```kotlin
val target = StackTarget.fromEnvironment(serviceNames = setOf("api", "ui"))
val images = ImageTags.fromEnvironment(allowedServices = setOf("api", "ui"))

StackSmokeSuite(
    target = target,
    images = images,
    checks = listOf(StackSmokeCheck("api", "/health")),
    requiredImageServices = setOf("api"),
).run()
```

## Links

- [Organization profile](https://github.com/JorisJonkers-dev)
- [Security policy](https://github.com/JorisJonkers-dev/.github/security/policy)
- [Changelog](./CHANGELOG.md)
- [License](./LICENSE)

Copyright (c) Joris Jonkers. Source available for viewing only; use, copying,
modification, redistribution, deployment, or reuse is not licensed. See
[LICENSE](./LICENSE).
