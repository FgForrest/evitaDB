---
title: Tests that pin the code's structure by scanning sources or bytecode against hand-kept lists are removed; the rule goes into the javadoc of the type it constrains, the compiler where it can, and a runtime refusal where one exists
date: 2026-10-20
updated: 2026-10-20 08:58
status: accepted
kind: process
issues: [1615]
prs: [1738]
areas:
  - evita_test/evita_functional_tests
  - evita_engine/src/main/java/io/evitadb/core/transaction
  - evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators
  - evita_engine/src/main/java/io/evitadb/index/mutation/local/handler
  - evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation
  - evita_common/src/main/java/io/evitadb/annotation
  - documentation/developer/stm
supersedes: []
superseded-by: []
relates: [2026-10-20-single-owner-partitions-as-registry-rows, 2026-08-26-warm-up-per-entity-mutation-atomicity, 2026-09-23-internal-member-annotation, 2026-09-08-jdk21-safe-modernization]
---

# A rule about the code's structure lives in the javadoc of the type it constrains, not in a test that scans the code against a list

Five tests that checked the structure of the code rather than its behaviour were removed together with their
fixtures and one production method only they called - 2,379 lines. Each scanned sources, bytecode or the classpath
and compared what it found with a hand-kept list of approved sites, names or exemptions. The rule each one enforced
now lives in the javadoc of the type a developer holds when about to break it; one became a compiler check; the
others rest on a runtime refusal that already existed and on the functional tests that exercise it. The same
reasoning removed two guards of the #1615 branch itself, recorded in
`2026-10-20-single-owner-partitions-as-registry-rows`.

## Why

At the manual review of #1615 the maintainer called `ReferencePartitionAccessGuardTest` - a bytecode scan against a
165-entry allow-list - "a very fragile thing", and asked of `VirtualReferencePartitionWriterGuardTest` what long-term
value it brought. Both were removed. A search of every test source for the same shape found five older ones. They
shared the faults that condemned the first two:

- **A red run is cleared by adding a line.** An entry's reason is free text nothing can check, so the list grows
  whenever the code does.
- **Harmless changes turn them red.** A rename, a moved call, a reformatted line, javac's numbering of synthetic
  methods or a module that compiles later in a parallel build - none changes what the code does.
- **They fail open on what they do not list.** A new method, a call spelled differently than a regex expects, or a
  class the scan cannot load passes unseen.
- **Their histories are upkeep.** Every commit after the first kept the list in step with the code; none of the five
  records a defect it caught.

### Previous state

| Test | What it pinned | History after its creation |
|---|---|---|
| `WarmUpRollbackConformanceTest` (563 lines) | regexes over every `evita_engine` source file: the single site opening a `WarmUpSavepoint`, the maintainer-absence guard on it, the traffic recording placed textually before it, an override of `supportsWarmUpRollback()` in every class resolving its own layer, and 15 approved files allowed to call `Transaction#isTransactionAvailable()` | `4e5a306c11` added the ordering check after a review found the hazard; `af596867f2` edited the approved list to turn a red run green; the rest were cleanups |
| `EngineMutationOperatorContractTest` (198) | a method *named* `replayCompletionState` declared on every class whose name ends `MutationOperator`, unless one of 6 names maps to a reason; the minimum class count derived from the size of that map | none |
| `InternalApiUsageTest` (1,020, plus 179 lines of fixtures) | its own class-file parser over every module's `target/classes`; no module outside a 7-entry allow-list may reference a member annotated `@Internal`; four minimum counts | one commit corrected the counts its javadoc quoted, one added two modules to the allow-list when a new member was marked |
| `LocalMutationDispatchCoverageTest` (205) | a hand copy of the seven `instanceof` branches of `Entity#mutateEntity`, checked against the sealed `LocalMutation` hierarchy; one of its tests exercised a `switch` that existed only in the test | none |
| `LocalMutationHandlerRegistryCoverageTest` (214) | a classpath scan of the mutation package, each concrete class asked of `LocalMutationHandlerRegistry#hasHandler`, classes it could not load skipped with a warning | one cleanup |

`hasHandler` had no production caller: its javadoc said it existed for the coverage test.

## Options considered

### Option A - remove the guards and state each rule where its reader looks (chosen)

Delete the tests. Put each rule into the javadoc of the type a developer holds when about to break it; let the
compiler enforce what it can; rely on the runtime refusals that already fail loudly at the first wrong use, and on
the functional tests that exercise every real path.

- **Pros:** no list to keep, nothing that breaks on a rename; the rule is read at the moment it matters, not after
  a red build; behaviour stays proven by tests that run it.
- **Cons:** a structural rule that no behaviour test reaches is no longer checked mechanically (see Consequences).

### Option B - keep the guards (declined)

- **Pros:** a structural mistake fails the build before any test exercises it.
- **Cons:** the faults above, at a standing cost every refactor pays.
- **Rejected because:** none of the five ever caught a defect, while each one needed edits to stay green; a red run
  they produce is cleared by a line nobody can verify, so the protection is weaker than it reads.

### Option C - rewrite each guard as a cheaper mechanical check (declined case by case)

- **A pattern `switch` with no `default` in `Entity#mutateEntity`**, so the compiler proves the dispatch complete.
  **Rejected because:** `evita_api` compiles for Java 17 (the floor of the Java driver), and a pattern `switch` is a
  Java 21 feature. **Revisit if** the driver floor moves to 21.
- **The registry checks its handlers against the sealed hierarchy when its class loads.** **Rejected because:**
  `LocalMutationHandlerRegistry#resolve` already refuses a mutation it has no handler for at the first attempt to
  apply one, and a new mutation type is not finished until a test applies it. A load-time walk of the hierarchy would
  guard a case no reader meets.
- **Qualified `exports` instead of `@Internal`.** **Rejected because:** JPMS exports whole packages, and `@Internal`
  exists precisely for single members of packages exported to everyone (option B of
  `2026-09-23-internal-member-annotation`).
- **A runtime test of the savepoint ordering** - a tracing implementation that throws between the opening of a
  warm-up savepoint and the `try` that closes it, followed by a mutation that must still open its own. **Not
  written:** the hazard is one statement order at the only opening site, which states it in an `INVARIANT` comment;
  the test would need a failing tracing implementation the suite does not have. **Revisit if** that block is
  restructured.

## Decision

**Chosen: Option A.** A structural rule is worth exactly what its reader sees at the site, and a list kept in a
test is seen only once the build turns red - by which time the cheapest fix is the line that clears it. Where the
compiler can carry the rule, it does: `EngineMutationOperator#replayCompletionState` lost its default, so an operator
cannot exist without deciding whether it replays. Elsewhere the javadoc states the rule and a runtime refusal that
already existed catches a wrong use at its first execution.

**Going forward:** do not add a test that scans sources, bytecode or the classpath against a hand-kept list of
approved sites or names. Reflection over a class's own declared structure that needs no list - the fields a
pre-image must copy, the fields that must not retain a formula - is a different shape and is kept.

## Key technical details

| Rule | Where it lives now |
|---|---|
| `WarmUpSavepoint#open()` has one caller, opened only where no transactional maintainer exists | javadoc of `WarmUpSavepoint#open()` |
| an index structure must not branch on `isTransactionAvailable()` to choose what it writes outside a transaction; the kinds of gate that remain | javadoc of `Transaction#isTransactionAvailable()`; `documentation/developer/stm/savepoints.md` |
| nothing throwable between opening the savepoint and its `try` | the `INVARIANT` comment in `LocalMutationExecutorCollector` |
| a structure resolving its own layer declares `supportsWarmUpRollback()` | runtime: the default is `false`, so `WarmUpSavepoint#verifyRollbackSupported` refuses an undeclared structure the first time a savepoint meets it (`WarmUpRollbackBackstopTest`) |
| every engine operator decides whether it replays forward | compiler: `replayCompletionState` is abstract; the six operators that cannot replay return `Optional.empty()` explicitly and their class javadoc says why |
| who may reach an `@Internal` member | javadoc of `Internal`; no mechanical check |
| a new `LocalMutation` needs a branch in `Entity#mutateEntity` and a handler | javadoc of `LocalMutation`; runtime: `Entity#mutateEntity` and `LocalMutationHandlerRegistry#resolve` both refuse an unknown mutation |

Two tests the search also examined were kept. `InterruptionAdviceWovenTest` reads bytecode, but it pins a positive
fact the woven class files alone can show, and it was written after the weaving matched nothing and query
cancellation was silently off. The test of `LazyAttributeIndexFamilyTest` comparing its family names with the
volatile fields of `AttributeIndex` is driven by the class itself; the suite reads those fields reflectively by
design, and the alternative - an accessor on `AttributeIndex` - would be production code only a test calls.

## Verification

The reactor up to `evita_functional_tests` compiles with `replayCompletionState` abstract and without the removed
tests, fixtures and `hasHandler`; `evita_api` builds at `release 17`, which is what rules out the production pattern
`switch`. The suites around every removed guard ran on 2026-10-20 (08:55-08:59 CEST): 358 tests in 94 classes,
0 failures, 1 skipped - the engine operator tests and the forward-replay suites (`EngineTransactionManagerForwardReplayTest`,
`CatalogRenameCrashReplayTest`), every warm-up savepoint and rollback suite including `WarmUpRollbackBackstopTest`
and the per-entity atomic rollback functional tests, `OffHeapTrafficRecorderMetricsTest`, `EvitaErrorMonitoringTest`,
and the two kept tests `LazyAttributeIndexFamilyTest` and `InterruptionAdviceWovenTest`.

## Consequences & open follow-ups

- **Unchecked by any test now:** the single opening site of a warm-up savepoint, the gates on
  `isTransactionAvailable()`, the statement order around the opening, and which modules reach an `@Internal` member.
  Each is stated at its site. **Revisit if** one of them is broken in a way that reaches a release - then write the
  behaviour test that would have caught it, not a scan.
- `EvitaErrorMonitoringTest` already showed that `MemoryNotAvailableException` is kept out of error monitoring, so the
  method of `OffHeapTrafficRecorderMetricsTest` asserting the annotation's presence, retention and target went too.

## Related work

- `2026-10-20-single-owner-partitions-as-registry-rows` - the two #1615 guards removed for the same reasons, recorded
  as declined options there.
- `2026-08-26-warm-up-per-entity-mutation-atomicity` - introduced `WarmUpRollbackConformanceTest`; its key technical
  details now point here.
- `2026-09-23-internal-member-annotation` - introduced `InternalApiUsageTest`; its enforcement half is reversed here,
  the annotation and its scoping rule stand.
- `2026-09-08-jdk21-safe-modernization` - introduced `LocalMutationDispatchCoverageTest` in place of a production
  pattern `switch`.

## Timeline

- **2026-10-19** - the #1615 access guard removed at manual review.
- **2026-10-20** - the #1615 writer guard removed; the search for the same shape; these five removed.
