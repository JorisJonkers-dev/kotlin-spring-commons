# Tasks: Round 3 Security, Web, and System-Test Commons

## Spec

- [x] T001 Read round-3 assignment and application/infra analysis.
- [x] T002 Create spec-kit files under `specs/003-round3-security-web-system-test/`.

## Vault Transit JWT/JWKS

- [x] T003 Add dependency aliases needed for Nimbus JOSE, Spring Authorization Server starter, and system-test helpers.
- [x] T004 Add cached Vault Transit JWKS source.
- [x] T005 Extend Transit JWT encoder and key id format.
- [x] T006 Add local RSA fallback and Vault JWT auto-configuration.
- [x] T007 Add claim and registered-client customization hooks.
- [x] T008 Add direct unit tests.

## Web Utilities

- [x] T009 Add RFC 7807 validation advice and properties.
- [x] T010 Add OpenAPI error schemas.
- [x] T011 Add configurable CSRF bootstrap controller.
- [x] T012 Add in-memory route-based public auth rate limiting.
- [x] T013 Add direct unit tests.

## Test Support

- [x] T014 Add configurable system-test environment and FQCN sharding.
- [x] T015 Add Playwright lifecycle base and navigation retry.
- [x] T016 Add RestAssured retry, TOTP code generation, and Stalwart IMAP assertions.
- [x] T017 Add direct unit tests for helpers that do not require external services.

## Verification

- [ ] T018 Run allowed local non-network checks.
- [ ] T019 Report commands that could not be run because Gradle dependency resolution would require network access in this sandbox.
