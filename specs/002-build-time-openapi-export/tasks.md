# Build-Time OpenAPI Export Tasks

## Plan

- [x] T001 Read the approved source spec from
  `api-contract-checks` branch `spec/build-time-openapi-export`.
- [x] T002 Inspect `personal-stack` assistant-api/auth-api export behavior and
  `contract-validate.yml` reference flow.
- [x] T003 Choose the implementation home: extend `test-support` at
  `dev.jorisjonkers:kotlin-commons-test-support`.

## Implementation

- [x] T004 Add springdoc/OpenAPI dependency coordinates to
  `gradle/libs.versions.toml`.
- [x] T005 Update `test-support/build.gradle.kts` with API dependencies needed
  by consuming test slices.
- [x] T006 Add the shared springdoc WebMVC slice import configuration.
- [x] T007 Add the OpenAPI export helper API for JSON and YAML output.
- [x] T008 Add a synthetic Spring MVC slice self-test proving JSON export.
- [x] T009 Add self-test coverage for YAML output and output-path writing.

## Release And CI

- [x] T010 Bump `.release-please-manifest.json` from `0.1.0` to `0.2.0`.
- [x] T011 Confirm `.github/workflows/publish.yml` uses serial Gradle publish
  flags `--no-parallel --max-workers=1`.
- [x] T012 Run `./gradlew :test-support:test`.
- [x] T013 Run `./gradlew build test`.
- [ ] T014 Open a PR, poll required `Pipeline Complete`, and squash-merge only
  when green.
