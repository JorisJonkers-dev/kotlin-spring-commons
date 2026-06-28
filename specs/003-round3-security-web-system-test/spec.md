# Feature Specification: Round 3 Security, Web, and System-Test Commons

## Requirements

- [REQ-001] The `vault` module MUST provide reusable Vault Transit RSA JWT signing support, a Spring `JwtEncoder`, a cached JWKS source, and local in-memory RSA fallback for development and tests.
- [REQ-002] Vault Transit JWT/JWKS support MUST avoid application domains, hostnames, concrete client ids, or role mappings. Token claims and registered OAuth2 clients MUST be customizable by consumer-provided callbacks or data.
- [REQ-003] The `web` module MUST provide opt-in RFC 7807 validation error handling, OpenAPI error schemas, a configurable CSRF token bootstrap endpoint, and in-memory route-based public endpoint rate limiting.
- [REQ-004] Web utilities MUST avoid domain-specific exception types, paths, domains, and route tables. Consumers MUST configure route rules and endpoint paths.
- [REQ-005] The `test-support` module MUST provide reusable system-test helpers for FQCN sharding, Playwright browser lifecycle, navigation retry, RestAssured retry, configurable test environment values, TOTP test-code generation, and IMAP/Stalwart mail assertions.
- [REQ-006] System-test support MUST keep account setup, database mutation helpers, endpoint names, and service-specific cookies out of the shared artifact.
- [REQ-007] New code MUST follow the existing Gradle/Kotlin/Spring module layout and keep publish coordinates under `dev.jorisjonkers:kotlin-commons-<module>`.
- [REQ-008] Tests MUST be direct in-process tests wherever practical so the existing JaCoCo gate can remain green.

## Success Criteria

- [SC-001] A consumer can sign a Spring Security JWT with Vault Transit and expose matching JWKS entries using `kid=<key-name>:v<version>`.
- [SC-002] A consumer can disable transit and receive a local RSA `JwtEncoder` and `JWKSource` without Vault.
- [SC-003] A consumer can plug token claim customizers and registered-client customizers without importing application role or account code.
- [SC-004] Validation failures produce RFC 7807 problem documents with structured field errors and configurable HTTP status.
- [SC-005] CSRF and rate-limit utilities are configurable by properties and have no built-in application routes.
- [SC-006] System-test helpers can be used from Spring-free JUnit suites and do not start external services during unit tests.

## Scope

In scope:

- `vault`, `web`, and `test-support` module additions.
- Spring Boot auto-configuration metadata where the existing module boundary benefits from opt-in beans.
- Unit tests for key behavior and extraction boundaries.

Out of scope:

- Vault policy/bootstrap implementation.
- Application-specific authorization-server filter chains.
- Domain-specific exception advice, user/account helpers, database fixtures, and concrete OAuth clients.
- Network-backed builds or system-test execution in this sandbox.
