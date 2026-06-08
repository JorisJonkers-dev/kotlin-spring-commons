# Kotlin Spring Commons

Publishable Kotlin/Spring commons modules extracted from personal-stack.

## Modules

Each module is independently consumable under `dev.extratoast`:

| Module | Coordinate |
| --- | --- |
| `archunit-test` | `dev.extratoast:kotlin-commons-archunit-test:0.1.0` |
| `blocks` | `dev.extratoast:kotlin-commons-blocks:0.1.0` |
| `command` | `dev.extratoast:kotlin-commons-command:0.1.0` |
| `crac` | `dev.extratoast:kotlin-commons-crac:0.1.0` |
| `email` | `dev.extratoast:kotlin-commons-email:0.1.0` |
| `events` | `dev.extratoast:kotlin-commons-events:0.1.0` |
| `exceptions` | `dev.extratoast:kotlin-commons-exceptions:0.1.0` |
| `messaging` | `dev.extratoast:kotlin-commons-messaging:0.4.0` |
| `observability` | `dev.extratoast:kotlin-commons-observability:0.1.0` |
| `test-support` | `dev.extratoast:kotlin-commons-test-support:0.1.0` |
| `timing` | `dev.extratoast:kotlin-commons-timing:0.1.0` |
| `vault` | `dev.extratoast:kotlin-commons-vault:0.1.0` |
| `web` | `dev.extratoast:kotlin-commons-web:0.1.0` |

## Usage

Add only the modules a service needs:

```kotlin
dependencies {
    implementation("dev.extratoast:kotlin-commons-command:0.1.0")
    implementation("dev.extratoast:kotlin-commons-exceptions:0.1.0")
    implementation("dev.extratoast:kotlin-commons-web:0.1.0")
    testImplementation("dev.extratoast:kotlin-commons-archunit-test:0.1.0")
}
```

Spring auto-configuration metadata is split per module. The initial extraction keeps the existing `com.jorisjonkers.personalstack.common` package names; `dev.extratoast` is the publishing group.

## Messaging Defaults

The messaging module keeps its classes under `com.jorisjonkers.personalstack.common.messaging` and exposes RabbitMQ topology names through `extratoast.messaging.*`:

```yaml
extratoast:
  messaging:
    enabled: true
    exchange: personal-stack.events
    dead-letter-exchange: personal-stack.events.dlx
    bindings:
      user-registered:
        queue: auth.user-registered
        routing-key: auth.user.registered
        dead-letter-queue: auth.user-registered.dlq
```

Website reconciliation is out of scope.
