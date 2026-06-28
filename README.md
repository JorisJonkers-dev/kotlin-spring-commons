# Kotlin Spring Commons

Publishable Kotlin/Spring commons modules extracted for reusable Spring services.

## Modules

Each module is independently consumable under `dev.jorisjonkers`:

| Module | Coordinate |
| --- | --- |
| `archunit-test` | `dev.jorisjonkers:kotlin-commons-archunit-test:0.1.0` |
| `blocks` | `dev.jorisjonkers:kotlin-commons-blocks:0.1.0` |
| `command` | `dev.jorisjonkers:kotlin-commons-command:0.1.0` |
| `crac` | `dev.jorisjonkers:kotlin-commons-crac:0.1.0` |
| `email` | `dev.jorisjonkers:kotlin-commons-email:0.1.0` |
| `events` | `dev.jorisjonkers:kotlin-commons-events:0.1.0` |
| `exceptions` | `dev.jorisjonkers:kotlin-commons-exceptions:0.1.0` |
| `messaging` | `dev.jorisjonkers:kotlin-commons-messaging:0.4.0` |
| `observability` | `dev.jorisjonkers:kotlin-commons-observability:0.1.0` |
| `test-support` | `dev.jorisjonkers:kotlin-commons-test-support:0.1.0` |
| `timing` | `dev.jorisjonkers:kotlin-commons-timing:0.1.0` |
| `vault` | `dev.jorisjonkers:kotlin-commons-vault:0.1.0` |
| `web` | `dev.jorisjonkers:kotlin-commons-web:0.1.0` |

## Usage

Add only the modules a service needs:

```kotlin
dependencies {
    implementation("dev.jorisjonkers:kotlin-commons-command:0.1.0")
    implementation("dev.jorisjonkers:kotlin-commons-exceptions:0.1.0")
    implementation("dev.jorisjonkers:kotlin-commons-web:0.1.0")
    testImplementation("dev.jorisjonkers:kotlin-commons-archunit-test:0.1.0")
}
```

Spring auto-configuration metadata is split per module. The initial extraction keeps the existing `com.jorisjonkers.personalstack.common` package names; `dev.jorisjonkers` is the publishing group.

## Messaging Defaults

The messaging module keeps its classes under `com.jorisjonkers.personalstack.common.messaging` and exposes RabbitMQ topology names through `extratoast.messaging.*`:

```yaml
extratoast:
  messaging:
    enabled: true
    exchange: application.events
    dead-letter-exchange: application.events.dlx
    bindings:
      user-registered:
        queue: auth.user-registered
        routing-key: auth.user.registered
        dead-letter-queue: auth.user-registered.dlq
```

## Round 3 Additions

- `vault` adds Vault Transit JWT signing, cached JWKS, local RSA fallback, and Spring Authorization Server claim/client customization hooks under `auth.transit.*`.
- `web` adds opt-in validation ProblemDetail advice, OpenAPI error schemas, CSRF bootstrap, and route-based in-memory rate limiting under `extratoast.web.*`.
- `test-support` adds Spring-free system-test helpers under `com.jorisjonkers.personalstack.common.test.system`.

Website reconciliation is out of scope.
