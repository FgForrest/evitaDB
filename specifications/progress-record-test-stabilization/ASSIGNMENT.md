# Assignment — stabilize ProgressRecordTest (flaky under CPU-saturated parallel runs)

Audience: the implementation session (any model). Scope: ONE test class,
`evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/progress/ProgressRecordTest.java`
(28 tests). No production code changes are needed or permitted — the flake is entirely a test-design
problem; the production classes are sound.

## The symptom

`shouldTrackProgressFromProgressingFuture` fails with a `CompletableFuture.get()` `TimeoutException`
at `ProgressRecordTest.java:797` under `parallel=all` CPU saturation, and passes deterministically in
isolation. It is the lone `1E` in the current full-suite baseline (20029 / 0F / 1E, see
`specifications/client-session-cancellation-cascade/RESULTS.md` §Verification item 1). Fixing this
class returns the full-suite baseline to 0F / 0E.

## Root cause (code-verified 2026-07-15 — do not re-derive)

Threading model of the classes under test:

- `ProgressRecord` ctor (`ProgressRecord.java:~168-180`) wires everything SYNCHRONOUSLY and in a safe
  order: registers `whenComplete`, sets the progress consumer on the `ProgressingFuture`, publishes
  the initial `0%` to the observer, and only THEN — as the ctor's last statement — calls
  `progressingFuture.execute(executor)`.
- `ProgressingFuture.execute(executor)` (`ProgressingFuture.java:469`) just runs the stored execution
  lambda: for the single-supplier ctor that is `CompletableFuture.runAsync(→ complete(lambda.apply(this)),
  executor)`; for the nested-futures ctor it executes children then combines via
  `allOf(...).thenApply(...)` — the non-`Async` `thenApply` runs inline on the completing thread.
  Nothing in either class has thread affinity; the `executor` is a plain parameter.

The test's failure mechanism: the test hands the task to a fresh 4-thread pool and then performs
THREE cross-thread handoffs, each with a fixed **2-second** budget (`started.await(2 s)` →
`proceed.countDown()` → `future.get(2 s)` at :797). Under `parallel=all` the JVM is deliberately
CPU-saturated (that is the house test-lane policy), so a runnable-but-descheduled pool thread can be
starved past any fixed small budget — the task has already started (the `:790` assert passed) but
does not get CPU again within 2 s of the `get()` call. A 2-second budget on a cross-thread handoff
under saturation is a probabilistic assertion, which is the definition of this flake. The class
repeats the pattern: **9 occurrences of `2, TimeUnit.SECONDS`** and **7 `CountDownLatch` usages**
across 28 tests.

**Key insight that makes the fix trivial:** the flaky test's entire latch + `futureRef` machinery is
unnecessary. It exists only so the task lambda can reach the `ProgressingFuture` to call
`updateProgress(5)` — but the lambda ALREADY receives the future as its parameter
(`lambda.apply(this)`): `theFuture -> { theFuture.updateProgress(5); return "done"; }`. And because
the ctor wires the progress consumer BEFORE `execute(...)`, a same-thread executor exercises the
IDENTICAL wiring with zero concurrency.

## The fix

### 1. Rewrite `shouldTrackProgressFromProgressingFuture` synchronously

```java
final List<Integer> observed = new ArrayList<>();
final ProgressingFuture<String> future = new ProgressingFuture<>(
	9,
	theFuture -> {
		theFuture.updateProgress(5); // 5 of 10 steps = 50%
		return "done";
	}
);
new ProgressRecord<>("op", observed::add, future, Runnable::run);
assertEquals("done", future.getNow(null));
assertTrue(observed.contains(0), "Should receive initial 0%");
assertTrue(observed.contains(50), "Should receive 50% at midpoint");
```

`Runnable::run` executes the task inline inside the `ProgressRecord` ctor — deterministic, no
latches, no timeouts, no `futureRef`, no `CopyOnWriteArrayList` needed, and it verifies exactly the
same semantic property (observer receives initial 0 and mid-flight 50). This is safe BECAUSE the
ctor wires the consumer and `whenComplete` before `execute` — state that fact in the test's JavaDoc
so a future reordering of the ctor breaks this test loudly.

### 2. Sweep the remaining 8 × `2, TimeUnit.SECONDS` and 7 latch usages

Classify every test in the class into exactly two buckets:

- **Semantic tests** (the property holds regardless of which thread runs the task — progress
  arithmetic, observer notification, completion/exceptional-completion state, nested-future
  aggregation): convert to the same-thread `Runnable::run` style above and delete their latches and
  timeouts. Note for exceptional paths: with `Runnable::run`, `completeExceptionally` happens inline,
  so `assertThrows(ExecutionException.class, () -> future.get())` works without any timeout. Note
  for the nested-futures ctor: children execute inline too (`thenApply` runs on the completing
  thread), so aggregation tests are equally synchronous.
- **Genuinely concurrent tests** (the property IS the cross-thread behaviour — e.g. cancelling a
  task that is mid-flight and parked on a latch): keep the real executor, but follow the house rule
  for explicitly-async tests — **generous budgets, 60 s minimum** on every `await`/`get`. A
  test-controlled latch the test itself releases may use the unbounded `await()` (the 60 s
  `get`/`assertTrue(await(60 s))` around it is the backstop). A 2-second budget must not survive
  anywhere in the class.

Expected outcome of the classification: most of the 28 are semantic; only cancellation/mid-flight
tests (if any assert states before completion) legitimately need a thread.

One behavioural caution when converting to `Runnable::run`: execution completes INSIDE the
`ProgressRecord` ctor / `execute()` call. Any test asserting a NOT-yet-complete state between
construction and completion cannot be converted — that is precisely the "genuinely concurrent"
bucket.

### 3. Fix the executor leak in `tearDown` (real bug, Java-17 specific)

```java
if (this.executor instanceof AutoCloseable) { ... }
```

is dead code on this project's runtime: `ExecutorService` implements `AutoCloseable` only since
JDK 19, and evitaDB builds/tests on **OpenJDK 17** — so the guard is always false and every test
method leaks its 4-thread pool. Replace with
`if (this.executor instanceof ExecutorService es) { es.shutdownNow(); }`. If after step 2 only a few
tests still need a real pool, move pool creation out of `@BeforeEach` into those tests (or a nested
class with its own lifecycle) so semantic tests allocate nothing.

## Ground rules

Test-only change; TDD does not apply (there is no new behaviour), but run the class BEFORE the
change to record the baseline. Maven via `rtk mvn ...`, never piped through grep/head (`tail -N` or
surefire `.txt`/`.xml` reports). Targeted run:
`rtk mvn -pl evita_test/evita_functional_tests test -Dtest=ProgressRecordTest -Dtest.tag.policy=off`.
Repo rules: tabs, JavaDoc on every test method (Markdown, no HTML), no TODOs, no commented-out code,
no issue numbers or plan-doc references in code comments, `final` locals.

## Acceptance criteria

1. `ProgressRecordTest` green in isolation: 28/0/0 (or the adjusted count if tests are merged —
   do not silently drop coverage; every previously asserted property must survive somewhere).
2. `rg -n "2, TimeUnit.SECONDS" ProgressRecordTest.java` returns nothing; any remaining bounded
   wait is ≥ 60 s and lives only in a genuinely-concurrent test.
3. The `tearDown` shutdown actually executes on Java 17 (step 3).
4. Saturation check: the full `unitAndFunctional` suite (or at minimum the functional-tests module
   under `parallel=all`) runs with `ProgressRecordTest` contributing 0F/0E — restoring the 0F/0E
   full-suite baseline. Update `specifications/client-session-cancellation-cascade/RESULTS.md`
   §Verification item 1's "known flake" note to record that the flake was fixed by this assignment.
