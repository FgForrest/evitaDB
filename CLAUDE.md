# evitaDB - Claude Code Guidelines

evitaDB is an in-memory NoSQL database that acts as a fast secondary search/lookup index for e-commerce front stores.

## Building

- **Primary**: try to use IntelliJ MCP for building and running the project, when not possible use Maven
- **CLI Build Tool**: Maven
- **Java Version**: OpenJDK 17 (requires Maven toolchains configuration)

Build command:

```shell
mvn clean install
```

Running tests: see `.claude/rules/testing.md` for the tag taxonomy, Maven profiles, and test commands.

## Libraries

Prefer the libraries already in use — Kryo (binary serialization), RoaringBitmap (bitmaps), Jackson (JSON), Netty/Armeria (web server & client), gRPC-Java & GraphQL-Java (APIs), Logback (logging), Byte Buddy (runtime codegen), MinIO (S3 storage). Don't introduce an alternative for a job one of these already does without discussion.

## Project Structure

See "How this repository is organized" in README.md for module descriptions and dependency graph.

## Code Quality Requirements

- Line coverage with unit tests must be >= 70%
- All classes and methods must have comprehensible JavaDoc
- No TODO statements in committed code
- No commented out code
- `@Deprecated(since = ...)` follows a specific convention (and has a verification tool): see `.claude/rules/deprecation-policy.md`

## Defensive Design

- **Never silently skip unexpected states.** If a code path should be unreachable (e.g., an `else` after exhaustive enum checks, a `default` in a switch over a closed enum), it must throw an exception (`GenericEvitaInternalError` or equivalent) — never `continue`, `return`, `break`, or no-op.
- Treat every unhandled enum value, unexpected type, or impossible branch as a programming error that must surface immediately at runtime.
