# Feature Specification: Kotlin Spring Commons

## Requirements

- [REQ-001] The repository MUST provide publishable Kotlin/Spring commons modules for command handling, events, blocks, web support, exceptions, vault support, observability, timing, CRaC training, email, messaging, ArchUnit test rules, and shared test support.
- [REQ-002] Each module MUST be independently consumable so consumers can depend on only the concern they need.
- [REQ-003] Each module MUST use a short Maven coordinate in the form `dev.jorisjonkers:kotlin-commons-<name>`.
- [REQ-004] The events module MUST represent the personal-stack event concern under the short module name `events`.
- [REQ-005] The exceptions module MUST represent the personal-stack exception concern under the short module name `exceptions`.
- [REQ-006] The ArchUnit rules MUST be consumable from an `archunit-test` module.
- [REQ-007] Spring auto-configuration modules MUST carry their own `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` metadata, and that metadata MUST list only auto-configurations from the same module.
- [REQ-008] The messaging concern MUST be included as a reusable module under the short module name `messaging`.
- [REQ-009] Website or documentation-site reconciliation MUST remain out of scope for the initial extraction.
- [REQ-010] [FR-001] The messaging module MUST publish as `dev.jorisjonkers:kotlin-commons-messaging`.
- [REQ-011] [FR-002] The messaging module MUST keep the existing `com.jorisjonkers.personalstack.common.messaging` package initially to avoid consumer import churn.
- [REQ-012] [FR-003] The messaging module MUST expose Spring `@ConfigurationProperties` under `extratoast.messaging.*` for exchange, queue, routing-key, and dead-letter queue identifiers, with the existing personal-stack values documented as defaults.
- [REQ-013] [FR-004] The messaging module MUST use Spring Boot auto-configuration metadata in its own `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, listing only auto-configurations from the messaging module.
- [REQ-014] [FR-005] Messaging tests MUST run without a live broker and verify that topology declarations and publisher destinations come from configuration properties.
- [REQ-015] [FR-006] Repository CI MUST keep the required terminal check named exactly `Pipeline Complete`, and tagged publishing MUST run Maven publication serially.

## Success Criteria

- [SC-001] A consumer can depend on `dev.jorisjonkers:kotlin-commons-messaging` without taking unrelated commons modules except the event API required by the publisher contract.
- [SC-002] Overriding `extratoast.messaging.exchange`, `extratoast.messaging.dead-letter-exchange`, and configured binding names changes the exchange, queue, routing key, and dead-letter queue names used by beans and publishing.
- [SC-003] The module's tests pass with a mocked `RabbitTemplate` and no RabbitMQ broker.
- [SC-004] The repository builds with the existing 80% JaCoCo coverage gate.
- [SC-005] Release metadata is set to `0.4.0`, and a `v0.4.0` tag can publish artifacts after the PR lands.

## Scope

- In scope: the requested commons concerns as independently consumable modules with documented coordinates, including the generalized messaging module.
- Out of scope: website reconciliation and any personal-stack application migration.
