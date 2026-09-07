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
  mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional
  ```
- **Tag-filter expressions** — pass to surefire via `-Dgroups` / `-DexcludedGroups`; JUnit 5 supports boolean expressions:
  ```bash
  mvn -pl evita_test/evita_functional_tests test -Dgroups="facet & external_api"
  mvn -pl evita_test/evita_functional_tests test -Dgroups="(query | indexing) & !slow"
  ```
- **Documentation runners** — `mvn -P documentation`. The matching profile in the docs module flips its `skipTests` to `false`; the root profile sets `skipTests=true` everywhere else, so only documentation tests run.
- **Slow / long-running tests** — `mvn -P longRunning`. Same pattern as `documentation`; selects only the long-running module.
- **Picking the right tags for a code change** — map the changed source path to layer + capability tags. For example, a change under `evita_engine/src/main/java/io/evitadb/index/facet/` calls for `(facet | indexing) & !slow`; under `evita_external_api/evita_external_api_rest/` use `rest & external_api`. The full path-to-tag mapping is documented in the `TestTags` JavaDoc and in the bulk-tagging script committed during the rollout.

## Reading test results — the false-green traps

These bite when running a **targeted** class from the command line and reading the outcome. Some make a green,
correct change *look* broken or unrun; the worse half do the opposite — they print `BUILD SUCCESS` while proving
nothing at all. Treat every `BUILD SUCCESS` that is suspiciously fast, or that reports a test count you did not
expect, as unproven until you have read the count.

- **`@Nested`-only classes report `Tests run: 0` in the outer `.txt`.** The convention here is to consolidate methods into `@Nested` inner classes (see above), so most test classes have **no** direct `@Test` methods. Surefire then writes the outer-class report (`target/surefire-reports/io.evitadb.<...>.<Class>.txt`) as `Tests run: 0` and reports each nested class separately under its own `@DisplayName`. **Do not conclude "nothing ran" from the outer `.txt`.** The real number is the run's aggregate stdout line (`[INFO] Tests run: 138, Failures: 0, …`). Capture full stdout to a file and read that aggregate, or sum the per-`@DisplayName` lines — never trust the outer `.txt` alone.
- **The default reactor skips tests entirely.** The base surefire config sets `skipTests`, flipped on only by a profile — so a bare `mvn -pl evita_test/evita_functional_tests test` (no `-P`) runs **zero** tests and still reports `BUILD SUCCESS`. Always pass `-P unitAndFunctional` (fast loop) for functional/unit tests. Zero-count + success without the profile is the tell.
- **Stale engine jar in the local repository after a signature change.** A dependent test module resolves
  `evita_engine` from the local repository — see the next bullet for where that actually is — not from its
  freshly-compiled `target/classes`. After any signature / type-parameter / method change in `evita_engine`,
  reinstall it before running dependent-module tests:
  ```bash
  mvn -o -pl evita_engine install -DskipTests
  ```
  Skip this and the failure surfaces as a **compile** error in the test module (e.g. `wrong number of type arguments; required 3`, `NoSuchMethodError`) that points at test code which is actually fine — the stale binary is the real cause.
- **The local repository is not always `~/.m2` — check `MAVEN_OPTS` before you reason about what is installed.**
  Some environments (sandboxed agent runs among them) configure a **split** local repository, so that a build's own
  project artifacts land in an isolated directory while third-party jars still resolve from the shared host
  repository. The mechanism is two properties, normally arriving via `MAVEN_OPTS`:
  ```
  -Dmaven.repo.local=/tmp/.maven-cache            # isolated; this project's own artifacts are WRITTEN here
  -Dmaven.repo.local.tail=<host>/.m2/repository   # read-only fallback for everything else
  ```
  When they are set, `mvn install` writes to the isolated directory and **`~/.m2/repository` is never touched** —
  so "I reinstalled the engine" and "the engine jar under `~/.m2` is fresh" are different claims, and inspecting
  the wrong one will tell you the opposite of the truth. When they are **not** set (an ordinary developer machine,
  or an agent running outside its sandbox), only the shared repository exists and `settings.xml` decides it — here
  `<localRepository>${env.HOME}/.m2/repository</localRepository>`. Do not hard-code either path: read
  `MAVEN_OPTS`, and if an isolated repository is configured, look there **first**. The directory name is not
  guaranteed to be `/tmp/.maven-cache`, and it may not exist at all.
- **`evita_long_running_tests` must be built *with* `evita_functional_tests`, and `install` cannot rescue
  it.** It depends on functional_tests' **test-jar** for shared fixtures, but functional_tests sets
  `maven.install.skip=true`, so that test-jar is *never* refreshed in `~/.m2` — `mvn install` on it prints
  "Skipping artifact installation" and exits **successfully**, which is what makes this one so easy to
  misdiagnose. Building the long-running module alone therefore resolves whatever `-tests` jar happens to
  sit in the local repository, possibly months stale, and you get dozens of compile errors against helper
  signatures (`AssertionUtils.assertSavepointCommitKeeps` and friends) pointing at long-running test code
  that is perfectly correct. Always use one reactor:
  ```bash
  mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning
  ```
  The tell is a signature in the error that exists nowhere in the source: compare the compiler's
  `declared in method` line against the current declaration **before** touching a single call site.
  `javap -cp <local-repo>/.../evita_functional_tests-<version>-tests.jar io.evitadb.utils.AssertionUtils` settles
  it in one command.
- **`mvn test-compile` reports `BUILD SUCCESS` without compiling anything.** After a signature change in an
  upstream module (`evita_api`, `evita_engine`), the incremental compiler can decide the test sources are current
  and print `Nothing to compile - all classes are up to date.` — hiding every breakage the new signature caused. It
  once concealed 36 broken call sites this way. Only `clean test-compile` surfaces them. **After any upstream
  signature change, `clean` is not optional**, or the compile step proves nothing.
- **`-Dtest` with exclusions only selects nothing.** A pattern made purely of negations —
  `-Dtest='!SomeTest,!OtherTest'` — alongside `-Dgroups` matches **no** tests, prints `Tests run: 0` and exits
  `BUILD SUCCESS`. Read as a pass, it looks like the excluded classes were the whole problem. Always lead with an
  explicit include: `-Dtest='**/*Test,!**/SomeTest'`, and check the count is the one you expected.
- **`-Dtest` naming an *abstract* base class selects nothing.** Several functional suites are written as an
  abstract base with concrete per-scope subclasses (`AbstractEntityByAttributeFilteringFunctionalTest` and its
  siblings). Surefire matches the pattern against class *names* and then finds nothing runnable, so
  `-Dtest='AbstractEntityByAttributeFilteringFunctionalTest'` prints `Tests run: 0` and `BUILD SUCCESS` — measured,
  and `failIfNoTests` does **not** rescue it, since surefire treats "pattern matched a class" as tests having been
  selected. Name the concrete subclasses instead. Find them with
  `rg -l 'extends <AbstractClassName>' --glob '*.java'` before you rely on the run.
- **`-Dtest='Class#method'` selects nothing when the method lives in a `@Nested` class.** Because the convention
  here is to consolidate methods into `@Nested` inner classes, the method is almost never declared on the outer
  class, and the outer-class selector therefore matches no method: `Tests run: 0`, `BUILD SUCCESS`, again
  regardless of `failIfNoTests`. Use the nested wildcard —
  `-Dtest='TrigramSubstringSearchTest$*#shouldRejectAFalseCandidate'` — which was measured to select exactly the
  one method (`Tests run: 1`) where the plain `Class#method` form selected zero. Quote the argument so the shell
  leaves `$*` alone.
- **`-DsurefireArgLine` silently replaces JaCoCo's agent rather than appending to it.** The surefire `argLine` is
  built from `${surefireArgLine}`, which `jacoco:prepare-agent` populates. Passing `-DsurefireArgLine=...` on the
  command line is a *user property* and therefore **wins outright**, dropping the coverage agent from the forked
  JVM. Coverage silently reports nothing — and, because the agent changes timing and retained memory, any
  behaviour that depends on it changes too: at least one heap-accounting assertion only fails when the agent is
  present. If you need to add a JVM flag, add it without displacing that variable, and state in your findings
  whether the agent was in the fork.
- **`-DfailIfNoTests=false` does not cover a reactor run, and `-Dsurefire.failIfNoSpecifiedTests=false` does.**
  When `-pl` spans a module with no matching tests — `evita_engine` alongside a `-Dtest=` pattern naming only
  functional-test classes — that module's surefire aborts the build with `No tests matching pattern ... were
  executed`, and `-DfailIfNoTests=false` does **not** suppress it. Measured: a run that printed `BUILD FAILURE`
  having executed **zero** tests. The flag that works is `-Dsurefire.failIfNoSpecifiedTests=false`. Confirmed
  independently by two agents in the same session, each of whom lost a run to it first.
- **A backgrounded `mvn ... > log 2>&1; echo "EXIT=$?"` reports the `echo`'s status, not Maven's.** The wrapper
  exits 0 while the build failed, so a harness that surfaces the wrapper's code announces success over a
  `BUILD FAILURE`. Measured twice in one session — once on a 22,256-test sweep that ended in 8 errors and was
  reported as exit 0. Capture Maven's own code (`mvn ...; echo "EXIT=$?"` as the *last* command, or test `$?`
  immediately) and, either way, grep the log for `BUILD SUCCESS` / `BUILD FAILURE` rather than trusting an exit
  code that passed through a wrapper. Pairs badly with the flag above: together they make "zero tests, build
  failed" read as green.
- **Concurrent agents in one working tree corrupt each other silently.** Two Mavens over one
  `evita_engine/target/` produced a byte-buddy `Cannot resolve type description for io.evitadb.core.Evita` and a
  `mvn clean` that could not delete `target/classes` — neither a code fault. Worse, a counterfactual harness that
  snapshots a source file, mutates it, and restores it will **erase** anything another agent wrote to that file in
  the window, with no error anywhere; `cp -p` preserves mtime, so a timestamp check cannot even detect it
  afterwards. Serialize builds, keep exactly one writer per file, verify a restore by CONTENT (`cmp` against the
  snapshot, or grep for the edits you expect) and never by mtime, and build counterfactuals into an isolated
  local repository so a mutated jar cannot reach the shared `-Dmaven.repo.local` other agents resolve from.
- **An execution agent that reports nothing has not necessarily done nothing.** An empty `git diff --stat` and an
  empty `ListAgents` can BOTH be true while the agent is still mid-turn; neither is a completion signal, and two
  false signals agreeing is not confirmation. Only the task-completion notification is. Acting on the pair once
  produced two writers on one file in this repo.
- **`unzip` is not installed here, and fails silently through a pipe.** `unzip -l some.jar | grep -c Foo` returns
  `0` whether or not `Foo` is present, because the pipeline swallows the missing-binary error and `grep` counts an
  empty stream. That reads as "the class is absent" and can convince you the wrong artifact is installed. Use
  `jar tf` for archive listings, and `javap` to inspect what a class actually contains.
- **`evita_test/evita_performance_tests` builds only under the `full` profile.** A plain
  `mvn clean install` never compiles that module, so it reports `BUILD SUCCESS` having compiled *none* of it —
  a change that does not compile at all looks verified. Measured: a rename touching 63 classes in that module
  passed a full reactor build that silently skipped every one of them. To actually compile it:
  `mvn -o -P full -pl evita_test/evita_performance_tests test-compile`. The general form of the trap is that a
  green reactor build only proves the modules the *active profiles* selected, so whenever a change lands outside
  the default set, name the module you compiled rather than trusting the top-level result.
- **A JMH benchmark jar shades its constants at package time.** Editing a constant in `evita_engine` and
  re-running an existing `benchmarks.jar` measures the OLD value, and the run looks entirely healthy — a gate
  constant that changed from admitting to declining will silently take the other branch and the two "arms" then
  measure the same path. Before trusting any benchmark that depends on a constant, `javap -p -constants` the
  packaged jar and confirm the value, then repackage. This has fired twice.

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
probabilistic test fails once per few hundred CI runs and teaches everyone to press re-run. That module
exists precisely for tests that need time and an idle box. Tag it `@Tag(SLOW)`, and in order of preference:

1. **Introduce a real seam** and test it deterministically in the fast loop, if one can exist.
2. **Stress test in `evita_long_running_tests`, ENABLED** — sweep the timing rather than fixing it,
   and **state the calibration**: what counterfactual makes it fail, and how fast. A stress test that no
   longer fails when the guarded code is removed has silently become decorative, and only a recorded
   calibration lets the next person notice.
3. **Leave it uncovered and say so in a comment at the site**, so the next reader does not delete it as
   dead code — last resort, and only when even a sweep cannot reach it.

**`@Disabled` is not the default here, and it used to be.** The module is reached only by the weekly
`long-running-tests` workflow, which already *is* the isolation the fast-loop objection asks for; disabling
on top of that buys nothing but invisibility. It cost exactly that once: a stress test whose javadoc said
"enable after touching X" was not run by the change that touched X, and by the time anyone did, its race
window had narrowed and it no longer failed on its own counterfactual. Reserve `@Disabled` for a test that
genuinely cannot run unattended — one that needs a truly idle box to mean anything, or that runs long
enough to blow the workflow's budget — and say which in the annotation. Tests already carrying `@Disabled`
are worth revisiting **one at a time**, not bulk-enabling: each needs its counterfactual re-measured first,
because a disabled test has had no opportunity to tell you it went blunt.

**Split the obligation, because running it more often does not cover all of it.** Weekly CI catches the
guarded code being removed or weakened. It cannot catch the test going blunt — a narrowed window passes
just as green — and nothing automatic can, short of mutation-testing the guarded method. So the
calibration is a standing human obligation, and it has to be stated **at the guarded code**, not only in
the test: whoever edits that method is not reading your test's javadoc. Name the test, give the command
that runs it, and say that making the code *faster* can be enough to decalibrate it.

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
