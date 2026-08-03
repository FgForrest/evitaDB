---
title: Enforce the test-tag policy from a JUnit PostDiscoveryFilter, because listener exceptions are swallowed
date: 2026-08-03
updated: 2026-08-03 22:40
status: accepted
kind: fix
issues: [1374]
prs: [1382]
areas: [evita_test/evita_test_support, evita_test/evita_functional_tests, evita_test/evita_documentation_tests]
supersedes: []
superseded-by: []
relates: []
---

# Enforce the test-tag policy from a `PostDiscoveryFilter`, not a `TestExecutionListener`

The project-wide rule that every test method must carry one layer tag and one capability tag was
enforced by `TestTagPolicyListener`, a JUnit Platform `TestExecutionListener` that threw from
`testPlanExecutionStarted` in strict mode. It never failed anything. The gate has been re-implemented
as `TestTagPolicyFilter`, a `PostDiscoveryFilter`, which is a phase whose exceptions the platform
actually propagates to the build tool.

## Why

Two documents promised a hard gate — "an untagged test fails the build before any test executes" —
and the repository was run on that assumption for the whole life of the listener. It was not true.
PR #1373 added `ComparableArrayHashMapKeyTest` with no `@Tag` at all; CI ran the full functional
suite and reported `build` green in 13m58s, with the untagged class executing normally.

The wiring was all correct, which is exactly why it went unnoticed: the service file registered the
listener, `Mode.resolve()` genuinely defaulted to `STRICT`, and the violation-collection logic
genuinely flagged the untagged test. Only the *signalling* was wrong.
`CompositeTestExecutionListener#notifyEach` catches every `Throwable` a listener raises and logs it
at WARN — a deliberate guard so a misbehaving listener cannot break a run. The strict-mode
`IllegalStateException` was caught there and execution continued.

The constraint that makes this non-obvious: **no test of the tag logic can detect this failure.**
Unit-testing `collectViolations` passes; the suite stays green; the gate reports "0 violations"
because it is never asked. Only an end-to-end build with a deliberately untagged test distinguishes
a working gate from a decorative one.

A second, quieter cost: an untagged test is invisible to any positive `-Dgroups` selection, so it may
simply never run in the lane it was written for.

### Previous state

`TestTagPolicyListener` walked the `TestPlan` at `testPlanExecutionStarted` and threw in strict mode.
`warn` mode worked as intended (it returns normally), which is why the mode switch looked functional.

## Options considered

### Option A — `PostDiscoveryFilter` (chosen)

Register the gate as a `PostDiscoveryFilter`. `EngineDiscoveryOrchestrator#applyPostDiscoveryFilters`
runs outside any `try`/`catch`, so an exception raised there propagates through `Launcher#execute`
into Surefire and fails the build. `TestDescriptor#accept` visits a node before its children, so the
first descriptor the filter receives per engine is the engine root with the whole tree already built
— which is what allows one message listing every violation rather than failing on the first.

- **Pros:** fails for real; sees the tree *before* the tag filters, so untagged tests hidden from
  `-Dgroups` are still checked; still runs before any test executes; reuses the existing violation
  logic verbatim; no new build plugin, no new scanner to maintain.
- **Cons:** relies on a JUnit-internal property (absence of a `catch` around filter application)
  rather than a documented contract — the same class of assumption that caused the original bug.
  Mitigated by an end-to-end build assertion, not by reading the source.

### Option B — keep a listener, throw from `LauncherDiscoveryListener#launcherDiscoveryFinished` (declined)

`CompositeLauncherDiscoveryListener` does *not* catch, and that callback fires after post-discovery
filtering, so a collector filter plus a throwing discovery listener would also work.

- **Pros:** keeps the "listener" shape the docs already described.
- **Rejected because:** it needs two service-loaded components sharing mutable state, and the throw
  lands in a `finally` block — a genuine discovery failure would be masked by ours. It buys nothing
  over Option A while adding a failure mode.

### Option C — enforce outside JUnit, with a Maven step scanning compiled test classes (declined)

A build step (bytecode scan or a standalone `Launcher.discover()` run) after `test-compile`.

- **Pros:** immune to JUnit-internal changes; covers every test class regardless of what the run
  selects.
- **Rejected because:** it must re-implement Jupiter's tag resolution — meta-annotations, `@Nested`
  inheritance, superclass and interface tags — and any divergence produces a gate that disagrees with
  the engine it is supposed to guard. Option A gets full coverage of the discovered tree for free
  because it *is* the engine's own resolution. **Revisit if** the filter ever proves unable to see a
  class the suite executes.

### Option D — assert the policy from an ordinary test (declined)

A `TagPolicyTest` that runs its own `Launcher.discover()` over the module and fails as a normal test.

- **Pros:** trivially propagates — a failing test is a failing build.
- **Rejected because:** it runs *among* the tests rather than before them, so a multi-hour suite
  still burns its full runtime before reporting a tagging mistake, and it needs one such test per
  test module. The shape survives as the *unit test* of the filter, not as the gate.

## Decision

**Chosen: Option A.** The driver is "fail the build, before any test runs, listing every violation",
and post-discovery filtering is the only extension point that satisfies all three without
re-implementing tag resolution. The known weakness — dependence on an undocumented absence of a
`catch` — is answered by verification policy rather than by design: this gate is only ever accepted
on evidence of a real build going red. If a future JUnit release wraps filter application in a
`catch`, Option C becomes the fallback and this record should be superseded.

## Key technical details

- `evita_test/evita_test_support/.../extension/TestTagPolicyFilter.java` — the gate. Never excludes
  anything: it returns `FilterResult.included(null)` for every descriptor and only observes. All work
  happens on the `descriptor.isRoot()` visit.
- Violations are detected for any descriptor with a `MethodSource`, not only `isTest()` descriptors.
  `@ParameterizedTest` / `@TestTemplate` / `@TestFactory` methods are *containers* at discovery time
  (their children only materialise during execution), so an `isTest()`-only check — what the old
  listener used — silently skipped every parameterized test in the repository.
- The mode is injectable via a package-private constructor. The suite runs with surefire
  `parallel=all`, so a test that mutated the `test.tag.policy` system property would be a race;
  `Mode.resolve(String)` takes the raw value instead of reading the property itself.
- `module-info.java` now carries `provides` clauses for both this filter and
  `CleaningTestExecutionListener`. The test modules are non-modular and register through
  `META-INF/services`, but a module-path run would silently drop both — and a silently absent gate is
  the exact failure this record exists to prevent.
- `TestTags.TEST_HARNESS` is registered in **both** `LAYER_TAGS` and `CAPABILITY_TAGS`, following the
  existing `INDEXING` precedent. A test of the harness has no database capability to declare, and
  forcing a false one on it would corrupt every tag-filter expression that names that capability.

## Verification

- `TestTagPolicyFilterTest` (`evita_functional_tests`) — `Tests run: 18, Failures: 0, Errors: 0`. It
  drives a real `Launcher` and asserts on what escapes `discover()`: strict mode throws
  `TestTagPolicyViolationException` for an untagged fixture, for a layer-only fixture and for an
  untagged `@ParameterizedTest`; the message names both violating methods and the count; `warn` /
  `off` return a plan that still contains the tests.
- End-to-end, and the only check that distinguishes this fix from the bug it replaces: a deliberately
  untagged probe class in `evita_functional_tests` turned a real
  `mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional` run **red** — `BUILD FAILURE`
  with `There was an error in the forked process` / `Test tag policy violated: 1 test(s) are missing
  required tags` naming `shouldTripTheTagPolicyGate()`. The probe was then deleted and the suite is
  green. **Any future rework of this gate must repeat exactly this**; a green unit test proves
  nothing, which is how the previous gate survived.
- The two modules the gate had never actually reached — `evita_documentation_tests` and
  `evita_long_running_tests`, both `skipTests` by default — were checked the same way, red then
  green, without executing anything: `-P documentation` / `-P longRunning` plus
  `-Dgroups=__no_such_tag__ -DfailIfNoTests=false`. The gate runs during discovery, *before* the tag
  filter excludes everything, so the run reports `Tests run: 0` either way and rewrites no
  documentation. Red-then-green is the required sequence: green alone is indistinguishable from the
  profile not applying at all. Note that `evita_long_running_tests` must be driven from the reactor
  root, not with `-pl`: standalone it resolves a stale `evita_functional_tests` test-jar from the
  local repository and fails to compile — a pre-existing local-build trap, unrelated to this gate.
- Full functional suite: `Tests run: 20812, Failures: 1, Errors: 3, Skipped: 37` with **no** tag
  policy violation. The four failures are environmental, not caused by this change — `ExportS3ServiceTest`
  needs a Docker daemon, and the other three (`SharedRgeiSoakTest`, two `EvitaClientReadWriteTest`
  catalog-lifecycle cases) pass in isolation, `Tests run: 77, Failures: 0, Errors: 0`. The filter
  cannot alter selection in any case — it returns `included` for every descriptor.
- Audit of the whole governed tree at the time of the fix: 5 pre-existing violations, all fixed —
  `RandomSorterTest`, `TransactionalObjectVersionTest`, `WarmUpDataStoreMemoryBufferPoisonTest`
  (layer-only), and the two documentation runners `ConstraintJavaDocExporter` and `JfrDocumentation`.

## Consequences & open follow-ups

- The gate now fires in `evita_documentation_tests` and `evita_long_running_tests` too, which had
  never been exercised because those modules skip tests by default. Their two violations are fixed;
  a future untagged addition there will fail `-P documentation` / `-P longRunning`.
- `evita_roaring_bitmap` is *not* governed — it does not depend on `evita_test_support`, so its
  vendored upstream suite keeps its untagged tests. `evita_performance_tests` depends on
  test-support but declares no JUnit test methods of any kind (no `@Test`, `@ParameterizedTest`,
  `@RepeatedTest`, `@TestTemplate` or `@TestFactory` — it does not import Jupiter at all; it is JMH
  only), so the gate has nothing to check there even under `-P full`.
- The gate still only sees what a run discovers. A test class excluded by a surefire `<includes>`
  pattern is invisible to it. That is unchanged from the previous design and has not bitten;
  Option C is the answer if it ever does.

## Timeline

- **2026-08-03** — untagged class in PR #1373 observed passing CI; issue #1374 filed
- **2026-08-03** — root cause confirmed against `junit-platform-launcher` 6.1.2 sources; gate
  re-implemented as a post-discovery filter and verified against a real red build
