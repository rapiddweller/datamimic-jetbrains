# 0005 — Support IDEs from 2024.2, language server from 2025.2.1

- Status: Accepted
- Date: 2026-09-24

## Context

The plugin was compiled against IntelliJ Platform 2025.3 and required build 253, so older IDEs refused to install it.

## Decision

`since-build` is 242. The plugin compiles and tests on 2025.3, with Kotlin API 1.9 and serialization 1.6.0 as
`compileOnly` dependencies supplied by the IDE at runtime. Public `getService(Class)` calls avoid Kotlin extension
linkage to newer platform internals. A Java bridge calls the static `TaskCancellation.cancellable()` API because
Kotlin otherwise links through a `Companion` absent in 2024.2.

The Plugin Verifier matrix runs locally and on release tags: PyCharm 2024.2.6, IDEA 2024.2.6, IDEA 2025.2.1, the 2025.3
build target, and IDEA 2026.2.3.
The optional LSP module loads only where `com.intellij.modules.lsp` exists, starting with 2025.2.1.

## Consequences

2023.3 and 2024.1 are unsupported. Raising the floor later requires updating `sinceBuild`, the verifier matrix, and
this ADR.
