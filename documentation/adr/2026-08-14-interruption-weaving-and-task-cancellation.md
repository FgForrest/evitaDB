---
title: Weave the interrupt poll with `visit` and a chained matcher union, and interrupt tasks through the executor's Future
date: 2026-08-14
updated: 2026-08-14 13:20
status: accepted
kind: fix
issues: [1416]
prs: []
areas:
  - evita_engine/src/main/java/io/evitadb/core/executor
  - evita_external_api/evita_external_api_graphql/src/main/java/io/evitadb/externalApi/graphql/async
supersedes: []
superseded-by: []
relates: [2026-07-16-client-session-cancellation-cascade]
---

# Weave the interrupt poll with `visit` and a chained matcher union, and interrupt tasks through the executor's Future

`@Interruptible` had never injected anything. The build-time ByteBuddy transformer assembled its matcher
union with `ElementMatchers.anyOf(...)`, which is an **equality** matcher over values, so it matched no
method in any module for the entire life of the annotation. Query cancellation and query timeouts were
silently disabled engine-wide. Two further links in the cancellation chain were broken independently: the
`Future` returned by the executor was discarded on submission, and `CompletableFuture#cancel(true)` ignores
`mayInterruptIfRunning`, so nothing could interrupt a running background task even once the checkpoints
existed. All three are fixed here.

## Why

`AbstractObservableTask#cancel()` interrupts the worker thread, and `CancellationSupport` wires Armeria's
`whenRequestCancelling()` to it, so a client disconnect or request timeout *did* set the interrupt flag.
Nothing read it. A runaway query kept burning CPU that no client was waiting for, and `Scheduler#cancelTask`
could mark a restore cancelled without stopping it.

What made this survive is that **a matcher that matches nothing is indistinguishable from one that works**.
The plugin logs `Transformed 1454 type(s)` either way, the build stays green, and the whole test suite
passes — nothing asserted on the woven output.

### Previous state

```java
builder.method(
    ElementMatchers.anyOf(          // ← binds to anyOf(Object...)
        ElementMatchers.isAnnotatedWith(Interruptible.class)...,
        ElementMatchers.isOverriddenFrom(Formula.class)...,
        ...
    )
).intercept(Advice.to(InterruptionAdvice.class));
```

`anyOf` has no `ElementMatcher...` overload. The call binds to `anyOf(Object... value)`, documented as
*"matches any of the given objects by the `Object#equals(Object)` method"* with an explicit warning that it
"cannot be used interchangeably with any of its overloaded versions". Handed seven
`ElementMatcher.Junction` instances, the union asked whether a `MethodDescription` **equals** one of seven
matcher objects — never true.

Four of the seven branches additionally ended in `.and(isAbstract())` instead of `.and(not(isAbstract()))`,
targeting body-less methods; the GraphQL transformer's only branch had the same defect. Real, but
secondary: `anyOf` discarded the union regardless.

## Options considered

### Option A — `builder.visit(Advice.to(...).on(matcher))` (chosen)

Rewrites the body of every **declared** matching method in place.

- **Pros:** no new methods; the check sits on the declaring implementation, where the work happens.
- **Cons:** a subclass that inherits a matched method without declaring it gets no check of its own — which
  is the desired outcome here, but is a behavioural difference worth knowing.

### Option B — keep `builder.method(matcher).intercept(Advice.to(...))` (declined)

- **Pros:** smallest possible diff; it does work.
- **Cons:** matches *invokable* methods, so ByteBuddy synthesises an overriding method in every subclass
  that merely inherits a match.
- **Rejected because:** it added a synthetic `compute()` override to every `Formula` subclass — measured
  172 woven sites versus 129 for `visit` — putting an extra frame on the hottest path in the engine for no
  additional coverage. Revisit only if a matcher branch ever needs to instrument a method at a type that
  does not declare it.

Note that issue #1416 proposed switching to `visit` on the grounds that `intercept` **throws** under
redefinition. That does not apply: `byte-buddy-maven-plugin` defaults to `REBASE` (confirmed by
`Resolved entry point: REBASE` in the build log), where the original body is rebased and `SuperMethodCall`
resolves to it. `intercept` was verified working before being replaced.

### Guard against silent recurrence — asserted on the artifact (chosen) vs. inside the plugin (declined)

The plugin-side variant was implemented and did work on clean builds: it tallied matches on the matcher
ByteBuddy uses to weave and threw from `Plugin#close()` when a module wove nothing.

- **Rejected because:** the `transform` goal is **incremental** (see its `staleMilliseconds` parameter).
  A build where javac reports `Nothing to compile` processes zero types, so the assertion failed a
  perfectly good `mvn install`; a partial rebuild touching one non-matching file would trip it too. The
  plugin cannot distinguish a dead matcher from an up-to-date module. Revisit only if the goal gains a way
  to report that it processed the whole module.

`InterruptionAdviceWovenTest` asserts instead on the **built class files**, which is immune to incremental
semantics.

### Interrupting a running task — executor `Future` (chosen) vs. tracking the executing thread (declined)

- **Rejected because:** tracking a `volatile Thread` and interrupting it directly — the existing
  `AbstractObservableTask` pattern — races with completion, and an interrupt landing just after the task
  finishes poisons the *next* task on that pooled thread. That was tolerable while nothing polled the flag;
  now that the checkpoints are live it would abort an unrelated query. `FutureTask`'s cancel state machine
  only delivers the interrupt while the task is genuinely running, and `ThreadPoolExecutor.runWorker`
  clears a leftover flag before the next task. Revisit for submission paths that have no `Future` at all.

## Decision

**Chosen: Option A**, plus the artifact-level guard and the executor-`Future` cancellation.

The matcher union is now assembled by chaining `ElementMatcher.Junction#or(...)`, with
`not(isAbstract())` applied once to the whole union rather than per branch. `@Interruptible` keeps
`RetentionPolicy.CLASS` — issue #1416 suggested promoting it to `RUNTIME`, but the plugin engine resolves
types through a `TypePool`, so class-file annotations are visible to `isAnnotatedWith`; this was verified
by weaving `EvitaSession#getCatalogSchema` with the retention untouched.

## Key technical details

- `AbstractInterruptionTransformer` (new) holds the shared weaving and the reasoning; `InterruptionTransformer`
  in `io.evitadb.core.executor` and in `io.evitadb.externalApi.graphql.async` supply only their matchers.
- **Never build a matcher union with `ElementMatchers.anyOf(...)`.** It compares by `equals` against values.
  Chain `.or(...)` instead. This is the whole bug.
- `InterruptibleServerTask` (new, package-private) is the seam by which `Scheduler#submitTaskInQueue` hands a
  task the `Future` it previously discarded. `@InternallyScheduledTask` tasks deliberately get no handle —
  they run inline on the submitter's thread.
- `cancel()` cancels the result future **first**, then interrupts through the handle. Order matters: the
  status must already carry the `CancellationException` before the interrupt unwinds `executeInternal()`,
  otherwise `execute()`'s catch reports a cancelled task as failed.
- Both `execute()` implementations now return early when the future is already cancelled — without this a
  cancelled restore is logged at ERROR with a stack trace and reported as FAILED.
- An interrupt now surfaces as an **undeclared checked** `InterruptedException` from deep inside query
  internals. That is by design, and is why any stray interrupt flag aborts a query that previously ran to
  completion.

## Verification

- `InterruptionAdviceWovenTest` — four assertions, one known-woven method per module, checked with ASM for a
  `Thread.isInterrupted()` call: `AbstractFormula#compute`, `EvitaSession#getCatalogSchema`,
  `RestoreTask#readBlock`, `AsyncDataFetcher#get`. All four fail on the pre-fix tree, where **zero** classes
  in any module contained the advice.
- `SchedulerTest#shouldInterruptWorkerThreadOfCancelledTask` (new) observes **only**
  `Thread.currentThread().isInterrupted()`. The pre-existing `shouldCancelRunningTask` polls
  `getFutureResult().isCancelled()` — a cooperative check that passed throughout the outage, which is why it
  is not sufficient on its own.
- Woven sites measured on the clean build **immediately after the matcher fix, before the four cancellation
  defects below were addressed**: `evita_engine` 129, `evita_store_server` 7 (exactly the `@Interruptible`
  methods in `BackupTask` 5, `RestoreTask` 1, `FullBackupTask` 1), `evita_external_api_graphql` 95; all four
  classes declaring `compute()` carried exactly one check. Treat these as a snapshot of that build, not a
  current figure — later work in this same record added methods to `AbstractServerTask`, `SequentialTask` and
  `ObservableThreadExecutor`, and the transformed-type counts moved with it (1454 → 1456 in `evita_engine`).
  Re-deriving the per-method counts needs the matcher temporarily wrapped in a counting decorator; the standing
  guard against regression is `InterruptionTransformerMatcherTest`, not these numbers.
- Full functional suite with the weaving live: **21176 tests, 0 failures**, 39 skipped, 1 error
  (`ExportS3ServiceTest`, "Could not find a valid Docker environment" — environmental, no Docker available).
- `InterruptionTransformerMatcherTest` (new) asserts each of the **seven** engine matcher branches individually
  plus the GraphQL branch, and asserts that the abstract declarations and an unrelated method do **not** match.
  It needs no build artifacts and would have caught the `anyOf` defect in milliseconds. `InterruptionAdviceWovenTest`
  keeps exactly one assertion per module, owning the orthogonal claim that the plugin ran against the shipped
  classes; the two are deliberately not merged.
- Four further defects were found in the cancellation chain and fixed here, each calibrated by reverting the fix
  and confirming its test fails with the predicted symptom:
  - `AbstractServerTask#attachExecutionHandle` — a cancel arriving between `submit(...)` and the attach found no
    handle and never interrupted, silently reinstating the defect this record exists to fix.
    `SchedulerTest#shouldInterruptTaskCancelledBeforeHandleAttached`.
  - `ObservableThreadExecutor` — `AbstractObservableTask#cancel()` could deliver its interrupt after the worker had
    finished and taken the next task. `ObservableThreadExecutorCancellationTest#shouldHoldFinishingWorkerUntilConcurrentCancelDeliveredInterrupt`.
  - `BackupTask#doBackup` — cleanup caught only `RuntimeException`.
    `BackupTaskCancellationTest#shouldDeleteRegisteredExportFileWhenBackupInterrupted`.
  - `SequentialTask#execute` — the completion block ran on a cancelled sequence.
    `SequentialTaskTest#shouldStopAtStepBoundaryWhenResultFutureCancelledDirectly`.
- Tag-scoped regression run after all fixes: `-Dgroups="task & engine"` → **97 tests, 0 failures**.
- Request-path run, because the `ObservableThreadExecutor` fix executes on every task completion in the Armeria
  request path and the tag scoping above is orthogonal to it: five GraphQL and REST end-to-end functional classes
  → **123 tests, 0 failures**, and zero `InterruptedException` occurrences in the log, which is the signal that
  matters — the woven checkpoints are live throughout that path, so a stray interrupt would abort queries rather
  than pass silently.
- The full suite is left to CI, which is green at the project's standard `-Xmx8g`; it OOMs on a developer
  workstation, and raising the local heap would hide that divergence rather than explain it.

## Consequences & open follow-ups

- **Every interrupt now costs a query.** 231 checkpoints across three modules poll the flag for the first
  time. Code that restores an interrupt flag and keeps working — rather than restoring it and unwinding —
  will now abort the next `@Interruptible` method on that thread. The suite is clean, but this is the
  failure mode to suspect first if queries start aborting spuriously.
- **`Scheduler#shutdown()`/`shutdownNow()` now genuinely interrupt.** Both loop `cancel()` over the whole
  queue; that was a no-op for running work before and is a real interrupt now.
- **`catch (RuntimeException)` is now a class of latent bug, wherever it guards cleanup.** The woven advice
  raises `InterruptedException` — a *checked* exception, thrown from bytecode out of methods whose signatures
  never declare it, so it is invisible at the catch site and slips past any `RuntimeException`-only handler.
  `BackupTask` was one such site and left a structurally valid but truncated archive registered and listed to
  users as fetchable. Its sibling `FullBackupTask` already caught `Exception`, which is what settled it as an
  oversight rather than a choice. Any other cleanup block narrowed to `RuntimeException` on a path that can now
  be interrupted deserves the same look — this was not audited exhaustively.
- **The thread-tracking pattern this record rejects for new code was still live in the request path**, and is
  now fixed rather than merely noted. `AbstractObservableTask#cancel()` reads a `volatile Thread` and interrupts
  it directly; nothing ordered that interrupt against the worker clearing its flag and taking the next task, so
  a cancel could abort unrelated work — reachable from `CancellationSupport.wireCancellation`, i.e. every Armeria
  client disconnect and request timeout. It now carries a `PENDING → RUNNING → {CANCELLING → INTERRUPTED | DONE}`
  state machine, giving it the delivery guarantee `FutureTask` provides and this class previously lacked, with a
  `deliverInterrupt` seam that makes the race deterministically testable.
- **`TaskStatus` has no CANCELLED state.** A cancelled task lands in `FAILED` carrying a
  `CancellationException`. Preserved as-is rather than widened here, but it makes cancellation
  indistinguishable from failure to an API consumer reading `simplifiedState()` alone.
- **`EvitaSession` carries 55 `@Interruptible` methods**, including trivial accessors such as
  `getCatalogSchema()`. Left as found — narrowing the annotation to methods that actually do work is a
  judgement call the original author may have made deliberately.
- **The six structural branches still name their methods with string literals**, and a rename of
  `Formula#compute`, `Sorter#sortAndSlice`, `ExtraResultProducer#fabricate`,
  `FilteringConstraintTranslator#translate` or `OrderingConstraintTranslator#createSorter` is
  compile-checked across every implementation while the matcher silently stops matching — the same
  shape of failure that hid this bug for the annotation's whole lifetime. Deferred rather than
  ignored: `InterruptionTransformerMatcherTest` asserts all seven branches individually, so a rename
  now goes red in milliseconds without needing build artifacts.

  The fix, when it is worth doing, is **not** to annotate the matched methods. Java does not inherit
  method annotations (`@Inherited` covers class annotations only), so `@Interruptible` on an
  interface method weaves nothing — the declaration is abstract and correctly excluded — and moving
  it onto implementations would require every one of them to remember it. 56 types are in the
  `Formula` lineage and only 4 declare a `compute()` body; the next implementation that declares one
  and omits the annotation would be silently uninstrumented, which is exactly the failure this record
  exists to prevent, merely relocated from one matcher to dozens of call sites.

  What does work is annotating the **interface** method and replacing the six branches with a single
  custom matcher — *"overrides a method declared in a supertype carrying `@Interruptible`"*. One
  annotation per contract, no string literals, renames carried along by the compiler, and future
  implementations covered by construction. ByteBuddy has no built-in for it, so it needs roughly
  twenty-five lines walking supertypes through a `TypePool` (CLASS retention is visible there, which
  is how `isAnnotatedWith` already resolves it).
- **`Interruptible`'s JavaDoc does not say the annotation must sit on the concrete implementation.**
  Given that method annotations are not inherited, and that `visit` rewrites declared methods only,
  an override that omits it is not woven. That is a contract others code against.

## Related work

- `2026-07-16-client-session-cancellation-cascade` — built the client-facing half of this chain (Armeria
  request cancellation wired through to executor tasks). That work delivered the interrupt correctly; this
  record fixes the fact that nothing at the far end ever read it.

## Timeline

- **2026-08-14** — issue #1416 investigated, root cause corrected to the `anyOf` misuse, all three links
  fixed, guard added
