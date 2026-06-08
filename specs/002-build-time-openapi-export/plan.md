# Build-Time OpenAPI Export Plan

## Source Specification

This plan mirrors the approved specification in `ExtraToast/api-contract-checks`
at branch `spec/build-time-openapi-export`, file
`specs/002-build-time-openapi-export/spec.md`, read on 2026-06-08.

## Objective

Add a reusable build/test support helper to `kotlin-spring-commons:test-support`
that lets Spring MVC services export springdoc OpenAPI output from a narrow
MVC slice without starting the full Spring Boot application, boot jar, embedded
server, database, message broker, or other external infrastructure.

## Recommended Approach

Use a springdoc-backed Spring MVC slice. A consuming service declares its
service-local controller slice with `@WebMvcTest`, imports its own OpenAPI
configuration, controller advice, MVC/Jackson extras, and collaborator mocks or
no-op beans, then imports the shared springdoc slice configuration from
`test-support`.

The shared helper participates in that slice by:

- importing the springdoc WebMVC API configuration required for `/v3/api-docs`
  or a service-specific docs path such as `/api/v1/api-docs`;
- invoking the docs path through `MockMvc`, so no server socket is bound;
- normalizing JSON with stable pretty printing and ordered object keys;
- supporting YAML output by converting the springdoc JSON document through
  Swagger's YAML mapper;
- writing to a caller-provided output path and failing clearly when the slice,
  docs path, serialization, or output path is invalid.

## Module Decision

Extend the existing `test-support` module and publish the helper under the
existing coordinate:

`dev.extratoast:kotlin-commons-test-support`

This matches the approved spec's recommended home. The helper is test/build
support and must not become a runtime dependency of service artifacts.

## Implementation Scope

In scope for this repository:

- add springdoc and servlet/Jackson dependencies needed by `test-support`;
- add a small ergonomic helper API for JSON/YAML export;
- add shared Spring configuration for springdoc inside a web MVC test slice;
- add a synthetic self-test controller and OpenAPI configuration proving the
  helper emits a valid expected document from a slice;
- ensure the self-test does not use a full Spring Boot app class, boot jar,
  embedded server, database, or network dependency;
- bump the release manifest to the next minor version, `0.2.0`;
- verify `publish.yml` publishes serially with `--no-parallel --max-workers=1`.

Out of scope for this repository:

- rolling the helper into `personal-stack`;
- changing `:services:assistant-api:exportOpenApiSpec`;
- replacing auth-api's boot-jar export task;
- proving assistant-api byte equality.

## personal-stack Rollout Note

After publishing this helper, `personal-stack` should adopt it behind the
existing `:services:assistant-api:exportOpenApiSpec` task and committed
`services/assistant-api/openapi.json` path. The first rollout must run the old
full-context assistant-api export and the new slice export side by side and
accept the new path only when the deterministic JSON outputs are byte-equal.
The contract validation workflow should continue exporting the spec, diffing
`services/assistant-api/openapi.json`, and then checking assistant-ui generated
types.

## Verification

- `./gradlew :test-support:test`
- `./gradlew build test`
- GitHub `Pipeline Complete` check before squash merge
