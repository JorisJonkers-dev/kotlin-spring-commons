# Feature Specification: Kotlin Spring Commons

## Requirements

- [REQ-001] The repository MUST provide publishable Kotlin/Spring commons modules for command handling, events, blocks, web support, exceptions, vault support, observability, timing, CRaC training, email, ArchUnit test rules, and shared test support.
- [REQ-002] Each module MUST be independently consumable so consumers can depend on only the concern they need.
- [REQ-003] Each module MUST use a short Maven coordinate in the form `dev.extratoast:kotlin-commons-<name>`.
- [REQ-004] The events module MUST represent the personal-stack event concern under the short module name `events`.
- [REQ-005] The exceptions module MUST represent the personal-stack exception concern under the short module name `exceptions`.
- [REQ-006] The ArchUnit rules MUST be consumable from an `archunit-test` module.
- [REQ-007] Spring auto-configuration modules MUST carry their own `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` metadata, and that metadata MUST list only auto-configurations from the same module.
- [REQ-008] The messaging concern MUST remain deferred and MUST NOT be included in the initial module set.
- [REQ-009] Website or documentation-site reconciliation MUST remain out of scope for the initial extraction.

## Scope

- In scope: the requested commons concerns as independently consumable modules with documented coordinates.
- Out of scope: messaging and website reconciliation.
