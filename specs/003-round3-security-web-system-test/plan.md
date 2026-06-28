# Implementation Plan: Round 3 Security, Web, and System-Test Commons

## Technical Context

- Runtime: Kotlin JVM with Java 21 toolchain and Spring Boot 4 / Spring Framework 7 dependency versions already used by the repository.
- Build system: Gradle Kotlin DSL multi-module build. Existing publishing emits `dev.jorisjonkers:kotlin-commons-<module>`.
- Modules: extend existing `vault`, `web`, and `test-support` modules. No new module is needed.
- Constraints: reference repos are read-only; avoid hardcoded personal-stack or website values; keep direct tests in-process; do not run networked Gradle or npm commands in this sandbox.

## Architecture

- `vault`:
  - Keep the existing `VaultTransitClient` contract and Spring implementation.
  - Standardize Transit key ids as `<key-name>:v<version>`.
  - Add `VaultTransitJwkSource` with an atomic cached `JWKSet`, explicit refresh, and failure tolerance that preserves the previous set.
  - Extend `VaultTransitJwtEncoder` so the active Transit key can be supplied dynamically while preserving the existing fixed-key constructor.
  - Add `VaultJwtAutoConfiguration` for Transit-enabled `JwtEncoder`/`JWKSource` beans and local RSA fallback beans.
  - Add `JwtClaimCustomizer` and registered-client factory/customizer helpers as optional Spring Authorization Server integration points.

- `web`:
  - Add property-backed auto-configuration under `extratoast.web.*`.
  - Add validation `ProblemDetail` advice using Spring's RFC 7807 type with an `errors` extension.
  - Add OpenAPI schemas for `ApiError` and `FieldValidationError`.
  - Add a property-placeholder CSRF endpoint, defaulting to `/csrf` but enabled only by opt-in auto-configuration.
  - Add a process-local sliding-window limiter and a route-configured `OncePerRequestFilter`.

- `test-support`:
  - Add system-test utilities in `com.jorisjonkers.personalstack.common.test.system`.
  - Provide property-backed `SystemTestEnvironment`, `FqcnShardCondition`, `PlaywrightSystemTestBase`, retry helpers, an RFC 6238 TOTP generator, and `StalwartMailClient`.
  - Keep helpers Spring-free except where the existing `test-support` module already provides Spring MVC OpenAPI slice support.

## Validation

- Add direct unit tests for Vault JWT/JWKS behavior, local RSA fallback, claim/client hooks, validation problem documents, OpenAPI schema registration, CSRF endpoint behavior, limiter/filter behavior, sharding, retry, TOTP, and mail-message parsing helpers.
- Intended CI validation outside this sandbox:
  - `./gradlew :vault:check :web:check :test-support:check`
  - `./gradlew build`

## Risk Notes

- JWT/JWKS behavior is security-sensitive; tests verify emitted JWTs against the published public key and ensure JWKS refresh failure keeps cached keys.
- Rate limiting is explicitly process-local and intended for low-volume public endpoint protection. Consumers needing horizontal consistency should replace it with a shared-store limiter.
- Playwright and IMAP helpers compile against external libraries; this sandbox cannot fetch or execute those dependencies.
