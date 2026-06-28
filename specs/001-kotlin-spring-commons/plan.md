# Implementation Plan: Kotlin Spring Commons Messaging

## Technical Context

- Runtime: Kotlin JVM on Java 21 with Spring Boot 4/Spring Framework 7 conventions already used by the repository.
- Build system: Gradle Kotlin DSL multi-module build; root publishing config emits `dev.jorisjonkers:kotlin-commons-<module>`.
- Dependencies: `:messaging` depends on `:events` for the `DomainEvent` publisher contract, includes Jackson modules for the JSON AMQP converter, uses Spring AMQP/Spring Boot auto-configuration as compile-only dependencies, and uses JUnit, AssertJ, MockK, and Spring Boot test support for brokerless tests.
- Constraints: Keep the existing `com.jorisjonkers.personalstack.common.messaging` package, avoid hardcoded topology identifiers outside configuration-property defaults, keep each Spring auto-configuration module's imports local to that module, and satisfy the existing 80% JaCoCo gate.

## Architecture

- Add a new `messaging` Gradle subproject included from `settings.gradle.kts`.
- Introduce `RabbitMqMessagingProperties` with prefix `extratoast.messaging`. The properties own the default exchange, dead-letter exchange, and a map of named bindings. The default binding is `user-registered`, matching the original personal-stack exchange, routing key, queue, and dead-letter queue values.
- Introduce `RabbitMqMessagingAutoConfiguration` as the module's only auto-configuration. It registers:
  - durable direct events and dead-letter exchanges from `RabbitMqMessagingProperties`;
  - property-derived queue, dead-letter queue, and binding `Declarables` for every configured binding;
  - a `Jackson2JsonMessageConverter`;
  - a `RabbitMqEventPublisher` that publishes to the configured exchange.
- Keep `RabbitMqEventPublisher.publish(routingKey, event)` so callers can continue to supply the routing key while the exchange is controlled by properties.
- Put the auto-configuration import in `messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Module And File Layout

- `messaging/build.gradle.kts`: Kotlin/Spring library module, Maven publishing, `api(project(":events"))`, Jackson implementation dependencies, compile-only Spring AMQP/autoconfigure/context dependencies, brokerless test dependencies.
- `messaging/src/main/kotlin/com/jorisjonkers/personalstack/common/messaging/RabbitMqMessagingProperties.kt`: configuration properties and binding model.
- `messaging/src/main/kotlin/com/jorisjonkers/personalstack/common/messaging/RabbitMqMessagingAutoConfiguration.kt`: auto-configuration and property-derived AMQP declarations.
- `messaging/src/main/kotlin/com/jorisjonkers/personalstack/common/messaging/RabbitMqEventPublisher.kt`: publisher using `RabbitMqMessagingProperties.exchange`.
- `messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: module-local auto-configuration import.
- `messaging/src/test/kotlin/com/jorisjonkers/personalstack/common/messaging/*Test.kt`: no-broker tests for defaults, overrides, auto-configuration, and publisher behavior.
- `README.md`: add the messaging coordinate and documented default property values.
- `.release-please-manifest.json`: bump repository release manifest to `0.4.0`.

## Requirement Mapping

- [FR-001], [REQ-003], [REQ-010], [SC-001]: `settings.gradle.kts` includes `:messaging`; root publishing config gives coordinate `dev.jorisjonkers:kotlin-commons-messaging`.
- [FR-002], [REQ-011]: all messaging source remains under `com.jorisjonkers.personalstack.common.messaging`.
- [FR-003], [REQ-012], [SC-002]: `RabbitMqMessagingProperties` owns exchange, dead-letter exchange, queue, routing key, and dead-letter queue names under `extratoast.messaging.*`.
- [FR-004], [REQ-007], [REQ-013]: `AutoConfiguration.imports` contains only `RabbitMqMessagingAutoConfiguration`.
- [FR-005], [REQ-014], [SC-003], [SC-004]: tests use `ApplicationContextRunner` plus MockK `RabbitTemplate`; Gradle `check` enforces JaCoCo.
- [FR-006], [REQ-015], [SC-005]: existing CI/release/publish workflows already meet the terminal check and serial publish requirements; update only if verification finds drift.

## Validation

- Run `./gradlew :messaging:check`.
- Run `./gradlew build`.
- Inspect workflow names locally; CI performs network-backed GitHub validation after the PR is opened.
- After merge, create `refs/tags/v0.4.0` at the merged `main` SHA and verify the publish workflow succeeds.

## Risks And Decisions

- Decision: use a map of named bindings instead of fixed `userRegistered*` bean methods. This makes queue/routing/dead-letter identifiers genuinely reusable while preserving the original personal-stack values as defaults.
- Decision: keep the publisher API shape but make the exchange property-driven. Routing keys remain caller-selected values so application code can route arbitrary events without module-specific constants.
- Risk: consumers still need Spring AMQP on the runtime classpath. This matches the repository's existing compile-only Spring starter pattern for optional Spring modules and avoids forcing unrelated runtime dependencies on consumers.
