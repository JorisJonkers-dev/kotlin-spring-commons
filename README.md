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
