# Tasks: Kotlin Spring Commons Messaging

## Dependencies

- T001-T004 are planning and build-layout tasks.
- T005-T007 define brokerless tests and can be done after the module skeleton exists.
- T008-T013 implement the messaging module and documentation.
- T014-T017 validate, commit, open the PR, merge on green CI, tag `v0.4.0`, and verify publish.

## Tasks

- [x] T001 [FR-001] [SC-001] Include `:messaging` in `settings.gradle.kts` and create `messaging/build.gradle.kts` with publishable Kotlin/Spring library dependencies.
- [x] T002 [FR-003] [SC-002] Add Spring AMQP dependency aliases to `gradle/libs.versions.toml`.
- [x] T003 [FR-001] [SC-001] Add `dev.jorisjonkers:kotlin-commons-messaging:0.4.0` to `README.md`.
- [x] T004 [FR-006] [SC-005] Update `.release-please-manifest.json` to `0.4.0`.
- [x] T005 [P] [FR-003] [SC-002] Add `RabbitMqMessagingPropertiesTest` covering default personal-stack values and override binding behavior.
- [x] T006 [P] [FR-004] [FR-005] [SC-002] [SC-003] Add `RabbitMqMessagingAutoConfigurationTest` using `ApplicationContextRunner` and a mock `RabbitTemplate` to assert property-derived exchanges, queues, bindings, converter, and publisher beans.
- [x] T007 [P] [FR-005] [SC-003] Add `RabbitMqEventPublisherTest` using a mock `RabbitTemplate` to assert the configured exchange is used and AMQP exceptions propagate.
- [x] T008 [FR-003] [SC-002] Implement `RabbitMqMessagingProperties` under `com.jorisjonkers.personalstack.common.messaging`.
- [x] T009 [FR-004] [SC-002] Implement `RabbitMqMessagingAutoConfiguration` with property-derived `DirectExchange`, `Declarables`, `MessageConverter`, and `RabbitMqEventPublisher` beans.
- [x] T010 [FR-005] [SC-003] Implement `RabbitMqEventPublisher` using `RabbitMqMessagingProperties.exchange`.
- [x] T011 [FR-004] [REQ-007] Add `messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing only the messaging auto-configuration.
- [x] T012 [FR-003] [SC-002] Document `extratoast.messaging.*` default properties in `README.md`.
- [x] T013 [FR-006] [SC-005] Confirm `.github/workflows/ci.yml`, `release.yml`, and `publish.yml` already satisfy the required CI, release-please, and serial Maven publish behavior.
- [x] T014 [FR-005] [SC-003] [SC-004] Run `./gradlew :messaging:check` and fix issues.
- [x] T015 [FR-005] [SC-004] Run `./gradlew build` and fix issues.
- [ ] T016 [FR-001] [FR-006] [SC-005] Commit, push `feat/messaging-module`, open a PR, and poll `Pipeline Complete`.
- [ ] T017 [FR-006] [SC-005] Squash-merge on green CI, create `refs/tags/v0.4.0` at the merged `main` SHA, and verify the publish workflow succeeds.

## Validation

- `./gradlew :messaging:check`
- `./gradlew build`
- `GH_TOKEN=$TOKEN gh pr checks <number> -R JorisJonkers-dev/kotlin-spring-commons`
- `GH_TOKEN=$TOKEN gh run list -R JorisJonkers-dev/kotlin-spring-commons --workflow Publish`

## Traceability

- [FR-001], [SC-001]: T001, T003, T016
- [FR-002]: T008-T011
- [FR-003], [SC-002]: T002, T005, T008, T009, T012
- [FR-004], [REQ-007]: T006, T009, T011
- [FR-005], [SC-003], [SC-004]: T006, T007, T010, T014, T015
- [FR-006], [SC-005]: T004, T013, T016, T017
