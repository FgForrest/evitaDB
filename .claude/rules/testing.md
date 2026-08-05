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

Every test method must carry **at least one layer tag** and **at least one capability tag** from
`io.evitadb.test.TestTags`. The policy is enforced by `TestTagPolicyFilter` in **strict mode by
default** — an untagged test aborts discovery, so the build fails before any test executes. Set
`-Dtest.tag.policy=warn` to downgrade to logging-only, or `-Dtest.tag.policy=off` to silence the
check entirely (only useful for ad-hoc local iteration on a freshly-stubbed test class). Cost tags
(`slow`, `flaky`) are optional.

- **Cost (optional, mutually exclusive)**: `slow`, `flaky`
- **Layer (≥1 required)**: `contract`, `engine`, `indexing`, `storage`, `driver`, `server`,
  `external_api`, `rest`, `graphql`, `grpc`, `lab`, `system_api`, `observability_api`, `cli`,
  `test_harness`
- **Capability (≥1 required)**: `query`, `filter`, `order`, `require`, `attribute`, `hierarchy`,
  `facet`, `price`, `histogram`, `reference`, `schema`, `transaction`, `wal`, `cdc`, `cache`,
  `session`, `proxy`, `export`, `stream`, `serialization`, `expression`, `comparator`,
  `observability`, `task`, `security`, `data_type`, `traffic_engine`, `management`, `test_harness`

A facet test exercising the GraphQL surface should carry e.g.
`@Tag(GRAPHQL) @Tag(EXTERNAL_API) @Tag(FACET)`. Tags can be applied at class level (inherited by all
methods) or per method. `indexing` and `test_harness` are listed on both axes and satisfy the policy
on their own.

Reworking the gate: it must raise from a phase whose exceptions JUnit propagates — a
`TestExecutionListener` will not do, the platform swallows those. Prove it by making a real build
fail; see `documentation/adr/2026-08-03-test-tag-policy-gate-via-post-discovery-filter.md`.

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

## Reading test results — three traps

These bite when running a **targeted** class from the command line and reading the outcome. All three make a green, correct change *look* broken or unrun:

- **`@Nested`-only classes report `Tests run: 0` in the outer `.txt`.** The convention here is to consolidate methods into `@Nested` inner classes (see above), so most test classes have **no** direct `@Test` methods. Surefire then writes the outer-class report (`target/surefire-reports/io.evitadb.<...>.<Class>.txt`) as `Tests run: 0` and reports each nested class separately under its own `@DisplayName`. **Do not conclude "nothing ran" from the outer `.txt`.** The real number is the run's aggregate stdout line (`[INFO] Tests run: 138, Failures: 0, …`). Capture full stdout to a file and read that aggregate, or sum the per-`@DisplayName` lines — never trust the outer `.txt` alone.
- **The default reactor skips tests entirely.** The base surefire config sets `skipTests`, flipped on only by a profile — so a bare `mvn -pl evita_test/evita_functional_tests test` (no `-P`) runs **zero** tests and still reports `BUILD SUCCESS`. Always pass `-P unitAndFunctional` (fast loop) for functional/unit tests. Zero-count + success without the profile is the tell.
- **Stale `~/.m2` engine jar after a signature change.** A dependent test module resolves `evita_engine` from `~/.m2`, not from its freshly-compiled `target/classes`. After any signature / type-parameter / method change in `evita_engine`, reinstall it before running dependent-module tests:
  ```bash
  rtk mvn -o -pl evita_engine install -DskipTests
  ```
  Skip this and the failure surfaces as a **compile** error in the test module (e.g. `wrong number of type arguments; required 3`, `NoSuchMethodError`) that points at test code which is actually fine — the stale binary is the real cause.

## Waiting for concurrency — the asymmetry that decides flakiness

Tests run in **parallel forks that contend for CPU**. A wall-clock budget that is comfortable on an idle
laptop is not comfortable in CI, so every wait has to be chosen by what it is asserting — not by what
felt long enough when it was written.

**Positive waits ("this must happen") are the flake risk. Negative waits ("this must not happen") are not.**
A loaded machine can only make work take *longer*, so it pushes a positive wait toward expiry (false
failure) and a negative wait toward holding (still passes). The two therefore get opposite treatment:

- **Positive → latch, and generous.** Use `CountDownLatch` / `join` sized to the work, awaited at **30 s**.
  A generous bound costs nothing on a passing run — the latch returns the instant the work completes —
  and still fails a genuine hang. Prefer one latch over a barrier when both would do.
- **Negative → short, and left alone.** `assertFalse(latch.await(250, MILLISECONDS))` is correct as-is.
  Do **not** lengthen it "to be safe": it cannot fail spuriously, and every extra millisecond is paid on
  every run. The real caveat is that a short window *proves less* on a loaded box — that is reduced
  sensitivity, not flakiness, and the fix is a better seam, not a bigger number.

**Never poll with `Thread.sleep`.** `for (i < N && !condition) Thread.sleep(20)` is the worst of both:
slower than needed when the code works, and expiring when the machine is busy. It is a positive wait
wearing a loop — replace it with a latch. `Thread.sleep` is acceptable only as a *detection widener*
(a 1 ms pause **inside** a task to make an overlap observable), where a slow machine makes detection
more likely and can never cause a false failure. Say which one it is in a comment.

**A stress loop belongs in `evita_long_running_tests`, never in the fast loop.** When an interleaving
cannot be hit deterministically — no seam exists between the two statements that race — the answer is not
to give up on coverage, and not to sweep the loop into `evita_functional_tests` either. In the fast loop a
probabilistic test fails once per few hundred CI runs and teaches everyone to press re-run; the *same*
test, `@Disabled` in the long-running module and executed deliberately on a quiet machine, is real
evidence. That module exists precisely for tests that need time and an idle box. Mark it
`@Disabled("<why> - enable manually when needed")`, `@Tag(SLOW)`, and in order of preference:

1. **Introduce a real seam** and test it deterministically in the fast loop, if one can exist.
2. **Stress test in `evita_long_running_tests`, `@Disabled`** — sweep the timing rather than fixing it,
   and **state the calibration**: what counterfactual makes it fail, and how fast. A stress test that no
   longer fails when the guarded code is removed has silently become decorative, and only a recorded
   calibration lets the next person notice.
3. **Leave it uncovered and say so in a comment at the site**, so the next reader does not delete it as
   dead code — last resort, and only when even a sweep cannot reach it.

Either way, name the reachable neighbour that *is* covered in the fast loop. See the `finally` re-check in
`SerialCdcExecutor.drain()` and `LongRunningSerialCdcExecutorStressTest` for the worked example.

**Name the test after the path it actually takes.** A test named for a window it never reaches is worse
than no test: it reports coverage that does not exist. Verify by reverting the code the test claims to
guard and confirming it fails — a guard that survives its own removal is guarding nothing.

**Shut fixtures down, with daemon threads.** `Executors.newSingleThreadExecutor()` creates **non-daemon**
threads; a fixture that leaks one keeps the surefire JVM alive and pollutes JVM-wide assertions made by
sibling classes in the same fork. Give fixture pools a daemon `ThreadFactory` *and* close them in a
`finally` or `@AfterEach`.

## Test performance — reuse shared datasets

Booting an embedded evitaDB instance and building a catalog is the dominant cost in a test; amortise it rather than paying it per method (see `@DataSet` / `@UseDataSet` in `io.evitadb.test.annotation`):

- Share one `@DataSet` across many `@Test` methods via `@UseDataSet` — a dataset referenced by the same name is **not** rebuilt or cleared between consecutive tests. Prefer one rich shared dataset per class over a per-method dataset.
- A test with **no** `@UseDataSet` spins up an anonymous ephemeral instance created and destroyed for that single method — convenient but slow; avoid except for genuinely one-off scenarios.
- Keep `readOnly = true` (the default) — it is what makes sharing safe. Avoid `destroyAfterTest` / `destroyAfterClass` unless the dataset was deliberately mutated; each forces a full rebuild for the next consumer.
- Request the minimum `openWebApi` — each API listed (gRPC / REST / GraphQL) starts a real server.
- Use `@IsolateDataSetBySuffix` for subclass hierarchies that alter a shared dataset differently.

See `documentation/developer/test_guidelines.md` for the fuller narrative and the dataset-annotation reference.
