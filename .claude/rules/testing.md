---
paths:
  - "**/test/**/*.java"
  - "**/evita_test/**/*.java"
  - "**/evita_functional_tests/**/*.java"
  - "**/evita_performance_tests/**/*.java"
---

# Testing Conventions

- **Framework**: JUnit 5
- **Test location**: All test files are located in `evita_test/evita_functional_tests/src/test/java`
- **Test naming**: Use format `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`
- **Display names**: Use `@DisplayName` for entire class and for test methods to provide clear descriptions (do not repeat class description content in method descriptions)
- **Coverage**: Automatically generate test cases for all public methods
- When creating new test consider implementing helper interface `io.evitadb.test.EvitaTestSupport` that provides utility methods for working with folders, ports, certificates and similar helpful things
- When creating tests consolidate similar tests in nested classes using `@Nested` annotation
- **No Java `ObjectOutputStream`/`ObjectInputStream` round-trip tests.** Many core classes (e.g. `Bitmap` implementations, index data structures) implement `java.io.Serializable` and declare a `serialVersionUID`, but they are **never** persisted via the Java object-serialization stream — persistence goes through **Kryo** serializers (`evita_store`). Testing `Serializable` round-tripping asserts a contract the system does not rely on. Test the actual persistence path (Kryo) instead, where one exists.

## Tag taxonomy

Every test method must carry **at least one layer tag** and **at least one capability tag** from `io.evitadb.test.TestTags`. The policy is enforced by `TestTagPolicyListener` in **strict mode by default** — an untagged test fails the build before any test executes. Set `-Dtest.tag.policy=warn` to downgrade to logging-only, or `-Dtest.tag.policy=off` to silence the check entirely (only useful for ad-hoc local iteration on a freshly-stubbed test class). Cost tags (`slow`, `flaky`) are optional.

- **Cost (optional, mutually exclusive)**: `slow`, `flaky`
- **Layer (≥1 required)**: `contract`, `engine`, `indexing`, `storage`, `driver`, `server`, `external_api`, `rest`, `graphql`, `grpc`, `lab`, `system_api`, `observability_api`, `cli`
- **Capability (≥1 required)**: `query`, `filter`, `order`, `require`, `attribute`, `hierarchy`, `facet`, `price`, `histogram`, `reference`, `schema`, `transaction`, `wal`, `cdc`, `cache`, `session`, `proxy`, `export`, `stream`, `serialization`, `expression`, `comparator`, `observability`, `task`, `security`, `data_type`, `traffic_engine`, `management`

A facet test exercising the GraphQL surface should carry e.g. `@Tag(GRAPHQL) @Tag(EXTERNAL_API) @Tag(FACET)`. Tags can be applied at class level (inherited by all methods) or per method.

When introducing a new tag, add it to `TestTags` and register it in either `LAYER_TAGS` or `CAPABILITY_TAGS`. The taxonomy is intentionally flat — no `cap:` / `surface:` prefixes.

## Test modules

The test suite is split across four sibling modules under `evita_test/`:

| Module | Contents | Surefire default |
|---|---|---|
| `evita_test_support` | Fixtures, helpers, the tag-policy listener | n/a (helpers, no tests) |
| `evita_functional_tests` | Fast functional + unit tests | runs |
| `evita_long_running_tests` | Slow / generative / soak tests | `<skipTests>true</skipTests>` |
| `evita_documentation_tests` | Markdown + multi-language code-sample runners | `<skipTests>true</skipTests>` |

`evita_performance_tests` lives outside the default reactor (loaded via the `full` Maven profile) and is explicitly excluded from the tag taxonomy.

## Running tests

- **Default fast loop** — the `unitAndFunctional` profile excludes `slow`/`flaky`-tagged tests in functional_tests; the long-running and documentation modules are skipped via their own surefire config:
  ```bash
  rtk mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional
  ```
- **Tag-filter expressions** — pass to surefire via `-Dgroups` / `-DexcludedGroups`; JUnit 5 supports boolean expressions:
  ```bash
  rtk mvn -pl evita_test/evita_functional_tests test -Dgroups="facet & external_api"
  rtk mvn -pl evita_test/evita_functional_tests test -Dgroups="(query | indexing) & !slow"
  ```
- **Documentation runners** — `rtk mvn -P documentation`. The matching profile in the docs module flips its `skipTests` to `false`; the root profile sets `skipTests=true` everywhere else, so only documentation tests run.
- **Slow / long-running tests** — `rtk mvn -P longRunning`. Same pattern as `documentation`; selects only the long-running module.
- **Picking the right tags for a code change** — map the changed source path to layer + capability tags. For example, a change under `evita_engine/src/main/java/io/evitadb/index/facet/` calls for `(facet | indexing) & !slow`; under `evita_external_api/evita_external_api_rest/` use `rest & external_api`. The full path-to-tag mapping is documented in the `TestTags` JavaDoc and in the bulk-tagging script committed during the rollout.
