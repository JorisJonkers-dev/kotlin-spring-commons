# Agents

Guidance for any coding agent working in this repository.

- Agent contract: [CLAUDE.md](CLAUDE.md)
- Estate conventions: https://github.com/JorisJonkers-dev/workspace/blob/main/CLAUDE.md

A multi-module Kotlin/Spring library; each top-level module (see `settings.gradle.kts`) is published as its own artifact. See [README.md](README.md) for the module list.

## Build and test

- Run all tests: ./gradlew test
- Publish (CI only): ./gradlew publish --no-daemon --no-parallel --max-workers=1 (`.github/workflows/publish.yml`)
- Re-pin the vendored principal contract: `scripts/pin-principal-contract.sh`
