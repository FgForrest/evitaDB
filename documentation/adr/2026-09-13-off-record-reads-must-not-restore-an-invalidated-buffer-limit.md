---
title: Off-record number reads must not restore a buffer limit the read has invalidated
date: 2026-09-13
updated: 2026-09-14 19:10
status: accepted
kind: fix
issues: [1551]
prs: [1571, 1572]
areas: [evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo, evita_engine/src/main/java/io/evitadb/spi/store/catalog/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/wal, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_engine/src/main/java/io/evitadb/core/cdc]
supersedes: []
superseded-by: []
relates: [2026-08-28-attributable-internal-error-metrics, 2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-08-24-grpc-streaming-backpressure-readiness-gate]
---

# Off-record number reads must not restore a buffer limit the read has invalidated

`ObservableInput#simpleIntRead` and `#simpleLongRead` read a bare number from between records: they cap
`limit` for the duration of the read and put the captured value back in a `finally`, unconditionally. When the
read has to call `require(int)` in between — which refills the buffer, and may compact it — `limit` has already
been set to a value describing the buffer as it now is. The restore overwrites that with a number describing a
buffer that no longer exists. After a compaction it is not even measured from the same origin, because
`position` was reset to zero and the remaining bytes shifted to the front.

Those two methods read the 4-byte content-length prefix and the 8-byte cumulative checksum that frame every
WAL transaction. They therefore run at **every transaction boundary**, which is exactly where a reader tailing
a write-ahead log sits.

## Why

A production deployment reported an internal-error health problem in **57.6 %** of probe cycles, continuously,
with `io_evitadb_errors_total` rising by 876 in 12 hours from a single origin while
`io_evitadb_client_errors_total` stayed flat at 0. The origin was a premise in
`AbstractMutationSupplier#readAndRecordTransactionMutation` building a `GenericEvitaInternalError`, which the
supplier then handled cleanly as a graceful end-of-stream — but *construction* is what the observability agent
counts (`2026-08-28-attributable-internal-error-metrics`), so the metric moved anyway.

The obvious reading, and the one issue #1551 was filed on, is that change-data-capture was reading a WAL tail
the commit pipeline had not finished writing. **That reading is wrong**, and the correction is the substance
of this record:

- The writer emits the 4-byte prefix **and** the whole leading `TransactionMutation` record from one
  `ByteBuffer` in a single write loop (`AbstractMutationLog#doAppend`). A reader cannot observe half of them.
- A file too short for the transaction is already rejected upstream by the end-of-file guards in
  `AbstractMutationSupplier#readAndRecordTransactionMutation`, which return `empty()` before the premise is
  reached.

So the premise can only fail on a file whose bytes are all present — which means the *reader* miscounted, not
the writer. It did: given an understated content length, the truncation guard at `:465` passes trivially, the
leading record still reads correctly because its bytes really are there, and the premise then compares a
too-small prefix against the record's true declared extent and fails. That is the reported error, on an intact
and fully flushed WAL, with no concurrent append involved.

### The two-year-old "probably"

`AbstractMutationLog`'s javadoc had said since 2024 that *"there is probably some bug in our observable input
implementation"*, and `avoidPartiallyFilledBuffer` existed to route around it. This is that bug. It was never
found because nothing drove the off-record reads through a partially filled buffer: the existing
`ObservableInputTest` coverage exercises **records**, and records are read through a different path
(`markStart`/`markPayloadStart`/`markEnd`) that does not perform this restore. Both the javadoc and the
workaround are removed by this change — see *The workaround* below.

## Options considered

### Option A — repair the limit bookkeeping in the two off-record readers (chosen)

Restore the pre-read cap only when the read did not move the buffer, via
`restoreLimitAfterOffRecordRead(cappedLimit, totalBeforeRead)`. The test is exact rather than heuristic:
`require`'s fill branch raises `limit` by a non-zero count, and its compaction branch raises `limit` *and*
advances `total` by the old `position`. If neither changed, `require` returned from bytes already buffered and
the capture is still valid.

- **Pros:** fixes the cause, so every symptom it produces goes with it — the miscounted prefix, the silently
  wrong bytes, and the `position > limit` state from which `require` computes a negative `remaining` and hands
  `System.arraycopy` a negative length. No new state, no configuration, no behaviour change on any path where
  the read was served from the buffer.
- **Cons:** it is a hot path shared by every reader in the database, not only the WAL, so the blast radius of
  being wrong is wide. Mitigated by the full functional suite rather than by argument.

### Option B — decide the mismatch from the record's declared extent (declined as the primary fix)

Reconstruct where the transaction must end from `recordLength()` and `walSizeInBytes`, and return `empty()`
when that end lies past the visible file. This was implemented first, before the cause was known.

- **Pros:** stops the metric moving without touching a shared read path; cheap and backportable.
- **Rejected because:** it treats a symptom of Option A's defect and would **mask** it. The state it tests for
  — a transaction extending past the end of file — is one the writer cannot produce, for the two reasons given
  under *Why*. Its own counterfactual test can only reach the premise by falsifying the prefix by hand. With
  the reader fixed the premise is no longer reached in the tailing case at all, so what remains is a genuine
  reader fault or genuine corruption, and silently returning `empty()` would hide exactly the class of defect
  this record is about. Revisit only if a mismatch is ever shown to arise from a cause the reader cannot
  control.

### Option C — switch CDC to the bounded "live" stream (declined)

The fix proposed by the issue itself: call `getCommittedLiveMutationStream(from, bound)` instead of the greedy
`getCommittedMutationStream(from)`, whose contract says *"DO NOT USE THIS METHOD if the WAL is being actively
written to"*.

- **Pros:** one line on the catalog side; matches the documented contract and the precedent in
  `TransactionManager`.
- **Rejected because:** it does not fix the symptom and adds a worse failure. `WriteAheadLogCorruptedException`
  is also an `EvitaInternalError` carrying no `@NotMonitored`, so it moves the same counter and raises the same
  gauge — the fix would only change the `error_type` label. The bound does not prevent the read either:
  `MutationSupplier#get()` parses transaction N+1's header before it applies the version bound, so bounding at
  N still reads N+1. At the time this option was weighed, the bounded path was in fact *more* eager than the
  greedy one, requiring the record's content but not its trailing checksum — eight bytes less on disk before
  it deserialized. **That last asymmetry no longer exists**: both paths now require the whole record (see *The
  workaround*), which removes the sharpest edge of this objection without rescuing the option, since the first
  two reasons are untouched.

### Option D — mark the tail-read failure `@NotMonitored` (declined)

Introduce a purpose-specific exception type carrying `@NotMonitored`, as the traffic recorder and the conflict
exceptions already do.

- **Pros:** an established house pattern with existing precedent.
- **Rejected because:** it would have silenced a true positive. The metric was reporting a real defect; the
  correct response to a loud alarm that turns out to be right is not a quieter alarm. `@NotMonitored` also
  silences the `ErrorOriginLogger` WARN, which is the only reason this was diagnosable at all. Revisit if a
  future recoverable condition genuinely needs to unwind a stack, where an exception is the mechanism rather
  than the report.

## Decision

**Chosen: Option A.** The metric was not lying. `io_evitadb_errors_total` was reporting a reader that returns
wrong bytes, and the work that started as "stop a recoverable condition polluting a health probe" ended as
"the condition was never recoverable and never a tail read".

Option B's guard was implemented before the cause was known and is **not** retained: see its rejection above.

### The workaround

`avoidPartiallyFilledBuffer` is removed in the same change. It was the routing-around for the defect Option A
fixes, and the alternative — keeping it as belt-and-braces — was rejected: it is the trap that produced this
issue. A caller reaching for a WAL stream met a method pair whose names describe an implementation quirk
rather than a contract, and change-data-capture picked the wrong one and stayed on it for two years. A flag
that no longer routes around anything, still consulted on the read path, is a worse hazard than the one it was
introduced for, because it invites the next reader to reason about a buffer condition that no longer exists.

What the flag *actually* controlled by the time it was deleted had nothing to do with buffer filling — that
meaning drifted away when `880e6a142` re-purposed it. It carried two behaviours, and separating them is the
substance of this record, because **only one of them turned out to be sound**:

- a caller that names a `requestedVersion` is **asserting that version is durably written**, which was taken
  to license delivering a record whose content is on disk without waiting for the trailing cumulative
  checksum. **This is removed.** See below — the premise does not survive reading the writer;
- and failing to reach that version is surfaced loudly rather than as an exhausted stream — the
  loud-vs-graceful contract `880e6a142` introduced to stop the trunk-incorporation stage misreading a
  durably-written transaction as "already processed" and spinning forever. **This is kept**, and sharpened:
  *which* exception is raised now depends on who named the version.

#### The checksum relaxation was never load-bearing

`AbstractMutationLog#doAppend` writes the record head, the content, and then the 8-byte trailing cumulative
checksum, and only afterwards calls `forceDurable`. `appendDeferringSync` defers the **force**, never the
bytes — it is `doAppend(…, false)`, and its only caller reads the returned record length immediately. So in
both append variants the record is length-complete on the channel before anything downstream can learn the
version exists.

The interval in which content is on disk and the checksum is not therefore belongs to an append that has not
returned, carrying a version nobody has been handed and no caller is in a position to name. The relaxation
guarded a window no legitimate caller could observe, and in exchange it made the reader willing to deliver a
record whose integrity it could not check. **Every read now requires the whole record**, and
`AbstractMutationSupplier#requiredEndPosition`, `#readAndRecordTransactionMutation` and
`MutationSupplier#get()` no longer branch on whether a version was named.

The one test that pinned the old behaviour asserted the opposite — that such a record *was* delivered — and
its own comment claimed the file shape it built mirrored "the moment `append()` has written everything except
the final checksum write". Per the above that moment is unreachable by a caller who can name the version, so
the test was inverted rather than deleted: the shape it constructs is reachable only by a crash mid-append or
by deliberate truncation, and in both cases it is damage.

#### Loud-vs-graceful now splits by caller, not by whether a version was named

Two real use cases were being served by one flag:

- the stream is acquired **internally**, from the transaction-processing pipeline. The version is the
  engine's own, already observed to be durable. Not finding it is damage — `WriteAheadLogCorruptedException`,
  an `EvitaInternalError`, counted by `io_evitadb_errors_total`;
- the stream is acquired **over an external API**, where the caller supplies the version and the database has
  no control over what it contains. It may name a version rotated out of retention, or one that never
  existed. Not finding it is a bad argument — `EvitaInvalidUsageException`, which the error counter ignores,
  because no amount of operator attention makes a client stop sending it.

The distinction is carried by `VersionSource` (`INTERNAL` | `CLIENT`), threaded from the entry point down to
the supplier. **It has to travel down rather than be translated on the way back up**, and that is the
non-obvious part: `WriteAheadLogCorruptedException` extends `EvitaInternalError`, and the Byte Buddy advice
instruments `EvitaInternalError` *constructors* — so an exception built internally and converted at the
session boundary has already moved the counter. Constructing the right type the first time is the only fix
that works.

`EvitaSession#getMutationsHistoryForward` is the sole `CLIENT` call site today. Note the axis is the **start**
position, not `requestedVersion`: all three named-version call sites derive the ceiling internally, and what
differs is that `criteria.sinceVersion()` comes off a client request.

Two boundaries were established by measurement rather than argument, each after a test failed:

- **an empty interval is not a missing version.** When the start version is above the requested ceiling —
  which is what a mutation-history query whose time frame begins after the last committed transaction
  resolves to — the range is empty by construction and the answer is an empty stream. Raising there turned an
  ordinary "no results" page into a gRPC `INVALID_ARGUMENT`;
- **a greedy read stays silent.** Recovery, replay and change-data-capture promise nothing about where the
  log ends, so a torn tail is simply the end of the data for them. Only a caller that named a version gets
  the loud treatment.

Both survive `requestedVersion`, a `@Nullable Long` that is `null` for a greedy read. The boolean was derived
from `requestedVersion != null` at the only place it was set (`AbstractMutationLog#createSupplier`), so its
removal takes away a parameter that could disagree with the field it shadowed. `VersionSource` is an **enum
rather than a second boolean**, deliberately: a boolean that bundled two orthogonal axes is what produced
this issue, and repeating the shape would have been the same mistake one axis over.

`AbstractMutationLog#getCommittedMutationStreamAvoidingPartiallyWrittenBuffer` is renamed to
`getCommittedLiveMutationStream`, matching the SPI method it implements
(`CatalogPersistenceService#getCommittedLiveMutationStream`) so the concept carries one name the whole way
down. **Readers of `2026-07-18-paged-index-corruption-and-flush-failure-boundary` will find the old name
throughout its `bug-03-commit-progress-hang.md` scenario; it is this method.** The two entry points are *not*
collapsed into one — they are genuinely different contracts, and the greedy one is what recovery and replay
need.

## Key technical details

- `ObservableInput#restoreLimitAfterOffRecordRead` — the fix, called from the `finally` of both
  `simpleIntRead()` and `simpleLongRead()`. Its javadoc carries the reasoning, because the next person to read
  that `finally` will not be reading this record.
- **The failure is not confined to the WAL.** `simpleIntRead`/`simpleLongRead` are the shared mechanism for
  reading a number that sits outside any `StorageRecord`; the WAL is simply their heaviest user.
- **Why the crash is rarely what is seen.** `seek(FileLocation)` resets `limit`, `position`, `total` and
  `actualLimit` wholesale, so any reader that seeks before a record wipes the corrupt state. Within a
  transaction the WAL reader is **sequential** — Phase 2 mutations are not individually seeked — so corruption
  minted at a boundary carries into the following reads and surfaces there as `Buffer underflow`,
  `CorruptedRecordException`, a failed premise, or silently wrong data.
- `MutationSupplier#get()` — `currentFileLength` is now re-read after `moveToNextWalFile`. It was sampled from
  the *previous* file and then used as the new file's size, which disabled both end-of-file guards for the
  first record of every rotated file. Independent defect, found while tracing this one.
- `DefaultChangeCaptureSubscription#consumeQueue` — the queue fill is now wrapped, routing a failure to
  `onError`. It previously sat in a block guarded only by `finally { unlock }`, so a WAL read failure escaped
  entirely: `finished` stayed `false`, the subscription was never removed from its publisher, the WAL version
  it pinned was never released, and the subscriber was told neither `onError` nor `onComplete`. On the
  `request(n)` path the same throw propagated out of `Flow.Subscription#request(long)`, which
  reactive-streams forbids.
- `DefaultChangeCaptureSubscription` release path — a terminal signal now releases the subscription's
  registration, and the release is **best-effort plus a guarantee**, not either alone. The signal that wins the
  `finished` CAS is the last code that ever runs for a subscription, so a signal that skips the release pins the
  entry — and with it a WAL version — for the lifetime of the process. It cannot release inline either: it is
  raised under the subscription's lock while the publisher takes its own lock first, and `request(n)` reaches it
  from inside the `computeIfAbsent` that is registering the subscription. So it defers to the capture executor,
  and the publisher's periodic sweep (`cleanFinishedSubscriptions`, driven by the observer's existing one-minute
  cleaner) is what makes a refused deferral survivable. The non-obvious part is *why* the deferral alone is not
  enough: the capture executor is an `ObservableThreadExecutor`, and `EvitaRejectingExecutorHandler` raises
  `RejectedExecutionException` on a **full bounded queue**, not only at shutdown — so under load the release is
  refused with nothing left to retry it. The sweep runs on the `Scheduler`, whose `ScheduledThreadPoolExecutor`
  has an unbounded delay queue, so it cannot be starved by the saturation that causes the leak. `releaseRegistration`
  is idempotent because both paths can reach it for the same subscription.
- **A subscriber callback must never run inside the `computeIfAbsent` that registers the subscription.** This is
  the root of everything below it, and it was only understood after three separate defects traced back to it. The
  constructor of `DefaultChangeCaptureSubscription` used to call `Subscriber#onSubscribe`, and the constructor
  runs inside `subscribers.computeIfAbsent(...)`. A subscriber may legally `cancel()` or `request(n)` from there -
  `EngineStatisticsPublisher` does, and so does the gRPC one - and both re-enter the publisher while that key's
  mapping function is still running. Three consequences, all measured (`ConcurrentHashMap`, OpenJDK 17.0.20 and
  21.0.12 alike):
  - `remove(key)` of the key being computed throws `IllegalStateException("Recursive update")` whenever the bin
    holds only the `ReservationNode` - with random `UUID` keys, nearly always. `get` returns `null` in the same
    state, and `isEmpty()`, `size()`, `containsKey()` and the entry iterator all skip the reservation.
  - The escape is worse than the throw: `versionSubscribersCount` is incremented *before* the constructor inside
    the same mapping function, so an escape leaves a pinned ring-buffer version with **no** `subscribers` entry -
    and the sweep walks `subscribers`, so nothing can ever release it. The system side leaks its
    `hostEventFilters` and `mutationFilters` entries too.
  - It would surface as a server-side `IllegalStateException` on an ordinary client disconnect - new
    internal-error and health-probe pollution, which is the very thing this record exists to remove.
  **The fix is structural**: `onSubscribe` moved out of the constructor into
  `DefaultChangeCaptureSubscription#activate()`, which both publishers call only after `computeIfAbsent` has
  published the entry, rolling the registration back through `unsubscribe` if it throws. Reactive-streams
  ordering survives because no capture can reach a subscriber before then - both delivery paths gate on demand,
  and demand is raised only by `request(n)`, which a subscriber cannot call before it holds the subscription.
  It also settles a question this work raised but never recorded as a follow-up:
  `AbstractChangeCaptureSubscriber` cancels synchronously in two of its three `onSubscribe` branches while the
  third defers via `CompletableFuture.runAsync` with a comment saying a synchronous cancel "would re-enter the
  map". With registration no longer calling subscriber code, all three are safe and the gRPC module needs no
  defensive change.
- **The publisher's map is the authority on whether a registration is still held, not the subscription's own
  flag.** `releaseIfTerminated` reports that a subscription has terminated, not that this call released it, so
  the sweep re-checks `containsKey` before unsubscribing. Before `activate()` existed this guarded a sharper
  hazard - a release running before its own entry was installed, which the `ImmediateExecutorService` used
  throughout the functional suite made deterministic rather than rare.
- **The system shared publisher must not retire itself when its last subscriber leaves.** It is a singleton -
  `SystemChangeObserver` builds it once and `ChangeSystemCapturePublisher` holds it in a plain `final` field with
  no renewal, unlike `ChangeCatalogCapturePublisher#getSharedPublisher`, which re-creates a closed one - and
  `subscribe` throws `InstanceTerminatedException` once closed. `checkSubscribersLeft` therefore only trims the
  ring buffer on that side; the observer owns the lifetime and closes it in its own `close()`. What had been
  hiding this is that the engine's boot-time subscriber normally keeps the map non-empty forever.
- **Only the caller that wins the removal may do the accounting.** `unsubscribe` cancels, cancelling releases,
  and the release calls back into `unsubscribe` through the publisher's own `onCancellation` hook - so the body
  runs twice for one departing subscriber. Left ungated, `versionSubscribersCount` is decremented twice: `{V: 2}`
  becomes `{}` rather than `{V: 1}`, and a surviving subscriber's position stops being tracked at all. A single
  subscriber hides it, because two decrements of `{V: 1}` both land on "remove the key". Gating the bookkeeping
  on `remove(id) != null` settles it: the re-entrant inner call removes and accounts, the outer call's `remove`
  returns `null` and does nothing. **Reordering to remove-before-cancel is the trap, not the fix** - it was tried
  (`9fd5d6814`) and reverted, because `unsubscribe` was then reachable from inside the registering
  `computeIfAbsent` and `remove` there throws "Recursive update". The lookup must stay a `get`.
- **A subscription owns exactly one unit of version accounting, and one code path may give it back.** The queue
  fill that moves that unit forward runs on the delivery thread and reads the WAL, so it can still be in flight
  after another thread cancelled the subscription and `unsubscribe` reclaimed the unit - `cancel()` takes no
  subscription lock, `consumeQueue` does. A move landing afterwards decrements a version the subscription no
  longer holds, taking the slot of a subscriber still sitting on it, and `moveTrackedVersionsInCache` leaves a
  literal `0` in the map that the trim block then treats as free. `setTrackedVersion` and `releaseAccounting`
  therefore exclude each other and a flag settles which won - but on a lock of their own, **not** the delivery
  lock. Using the delivery lock is the obvious version of this and it is wrong: the release runs on the
  cancelling thread, which for gRPC is the transport's cancel handler, and `consumeQueue` holds the delivery
  lock across a WAL read. A cancel would then block on disk IO for as long as that fill takes, in a callback
  that must not block. The accounting lock is a leaf - the only thing called while it is held is the
  publisher's own `compute` on a `ConcurrentSkipListMap` - so it orders against nothing.
- **A publisher's `close()` must cancel its subscriptions after releasing its own lock.** Cancelling reaches
  `unsubscribe` and from there the subscription's lock; holding the publisher lock across that adds a
  publisher → subscription edge to the subscription → publisher one `consumeQueue` already has through
  `checkSubscribersLeft()`. Both directions existing is a deadlock waiting for load. Snapshot, unlock, then
  cancel.
- **Registration and retirement take the publisher's lock, because otherwise neither can see the other.** The
  observer's one-minute cleaner retires a publisher from `subscribers.isEmpty()`, and a registration still inside
  `computeIfAbsent` is invisible to every read of that map (measured above). Unsynchronised, the cleaner closes
  and drops a publisher between `assertActive()` and the insertion, after which `processMutation` never reaches
  the new subscription and the client waits forever on a `subscribe` call that reported success -
  `getSharedPublisher()` cannot rescue it, having tested `isClosed()` before the close. Both publishers now hold
  the lock across the `computeIfAbsent` loop and the `closed` re-check that follows it.
  **An earlier revision of this record declined that lock**, on the grounds that it reintroduces the
  publisher → subscription edge the previous bullet removes. **That objection had expired and the decision is
  reversed.** It rested on two things that are no longer true: the mapping function calling subscriber code, and
  the version accounting sharing the delivery lock. With `onSubscribe` moved into `activate()` outside the lock,
  and the accounting moved onto a leaf lock of its own, nothing reachable while the publisher's lock is held
  takes a subscription's delivery lock - the edge no longer exists to reintroduce. Holding it across
  `activate()` *would* recreate it, which is why the re-check takes the registration back inside the lock
  rather than keeping the lock one line longer.
- **Undoing a refused registration is not `unsubscribe`, and the difference is visible to the client.**
  `unsubscribe` cancels, cancelling releases, and the release closes an `AutoCloseable` subscriber - the gRPC
  one is, and its `close()` sends the client `UNAVAILABLE`. Rolling back that way ends the client's stream and
  *then* hands the caller a refusal to retry, so the retry cannot help: the subscriber it would retry with is
  already finalised. `retractRegistration` takes the entry and the version slot back and signals the subscriber
  nothing, which is correct precisely because the subscriber was never given this subscription - `activate()`
  has not run. The rollback for an `activate()` that *throws* is the opposite case and does go through
  `unsubscribe`: there the subscriber already holds the subscription and is owed the terminal signal.
- **The retry is narrow in both directions, and one attempt is only enough because the factory was fixed.**
  `ChangeCatalogCapturePublisher#subscribe` retries only when the publisher that refused really is closed - the
  same `InstanceTerminatedException` can come out of a subscriber's own `onSubscribe`, and retrying there would
  call `onSubscribe` a second time on a subscriber whose transport the activation rollback has already closed.
  And a renewal has to actually renew: `close()` removes the publisher from `CatalogChangeObserver`'s map
  through its `onClose` hook at the *tail* of the close, so between marking itself closed and being forgotten
  there, a `computeIfAbsent` factory hands the closed instance straight back and the retry is refused again for
  no reason but losing that race. The factory is therefore a `compute` that counts a retired entry as absent.

## Verification

Every test was run against the unfixed code first and shown failing, so none can pass for the wrong reason.

- `ObservableInputTest$BoundaryReadTests` — drives the WAL's own boundary sequence (8-byte checksum, 4-byte
  content length, 8-byte checksum, payload) over a trickle stream across 6 buffer sizes x 9 chunk sizes.
  **Single-threaded; no WAL, no concurrency, no Kryo pool.** Before the fix: **93 failing assertions** — 68
  `ArrayIndexOutOfBoundsException: arraycopy: length -N is negative`, 68 `position` past `limit`, and **50
  cases of a wrong value returned with no exception at all**. After: passes.
- Whole `ObservableInputTest` class after the fix: **27 tests, 0 failures**, including the pre-existing
  trickle-stream, compressed, uncompressed and partially-filled-buffer suites.
- `LongRunningConcurrentWalTailReadStressTest` — one writer appending 400 transactions of varied sizes while
  three readers tail through the greedy stream, asserting that no internal error is ever *constructed*. It is
  the only test in the repository that drives a real writer and real readers against one file, and it is what
  exposed the fixture fault recorded below. It lives in `evita_long_running_tests`: it costs ~7 s alone but was
  measured at **28.9 s** inside a full functional run, and tagging it `slow` where it was first written would
  have meant it never ran at all, since `unitAndFunctional` excludes that tag.
  **It is not a regression guard for this fix, and an earlier revision of this record wrongly said it was.**
  Re-measured against the counterfactual — the unconditional restore this change superseded — it stayed green
  over **three consecutive runs** on an idle box (`escapedFailures=0; constructedErrors=0`, all 400 versions
  returned in order), while `ObservableInputTest$BoundaryReadTests` failed hard on that same mutant in that same
  reactor. A `KryoException: Buffer underflow` at `ObservableInput.require` *was* observed from this scenario
  before the fix, under a fixture and a machine load that have both since changed; it has not reproduced since,
  and why is unsettled — see the open follow-up below.
- `ChangeCaptureSubscriptionFillFailureTest` — with the catch removed, fails with the fill exception escaping
  `Subscription#request(long)`. Its release tests drive the **production** `EvitaRejectingExecutorHandler` on a
  genuinely saturated pool (one occupied worker, one filled queue slot) rather than a hand-written throwing
  executor, so the premise behind the sweep is measured rather than assumed. Two mutants pin it: dropping the
  idempotence CAS releases twice (`expected: <[id]> but was: <[id, id]>`), and a sweep that recognises
  termination without releasing leaves the registration held (`but was: <[]>`).
- **Every lifecycle fix is pinned by a mutant — six, all killed in one pass**, each restore verified by `cmp`
  against a pristine copy rather than by timestamp:
  - `setTrackedVersion` ignoring the release flag, and `releaseAccounting` not being once-only, each fail
    `ChangeCaptureSubscriptionFillFailureTest#shouldGiveBackTheVersionSlotExactlyOnce` on the ordering they
    break (`expected: <false> but was: <true>`; `expected: <0> but was: <1>`).
  - `onSubscribe` back in the constructor without a rollback fails **two** -
    `shouldRollBackTheRegistrationWhenOnSubscribeThrows` (the version slot stays at `1` instead of being
    released) and `shouldUnregisterASubscriberThatTerminatesFromInsideOnSubscribe` (the subscription stays
    registered).
  - The facade propagating the refusal instead of renewing fails
    `shouldRenewTheSharedPublisherWhenARegistrationIsRefused` with `InstanceTerminatedException` reaching the
    client.
  - The retirement rollback going through `unsubscribe` instead of `retractRegistration` fails
    `shouldRetractSilentlyWhenThePublisherIsRetiredMidRegistration` on the subscriber having been closed
    (`expected: <false> but was: <true>`) - the mutant that proves the retry would be inert.
  - Removing the post-registration retirement check entirely fails that same test with
    *"Expected InstanceTerminatedException to be thrown, but nothing was thrown"*.
- **The post-registration `closed` re-check is covered, and an earlier revision of this record wrongly said it
  could not be.** The claim was that reaching it needs a production seam, since moving `onSubscribe` out of the
  constructor removed the last place where registration calls test-controllable code. That is false:
  `assertActive()` is package-private and non-final on a non-final class, so a test subclass closes the
  publisher from inside it - between the pre-check and the insertion, which is exactly the interleaving the
  guard exists for. `shouldRetractSilentlyWhenThePublisherIsRetiredMidRegistration` drives it, and the two
  mutants above are killed by it. No stress loop is involved; the test is deterministic.
- `wal | cdc | serialization | transaction` sweep: **3,246 tests, 0 failures, 2 skipped** (818 test classes),
  re-run after the capture-lifecycle fixes landed.
  Narrow `wal | cdc` tag sweep: **652 tests, 0 failures, 1 skipped**, including the gRPC and GraphQL
  subscription functional tests that exercise CDC end to end.
- The two behaviours introduced by *The workaround* are each pinned by a mutant, run in this reactor:
  collapsing `VersionSource` so every failure is a `WriteAheadLogCorruptedException` fails exactly
  `CatalogWriteAheadLogTest$DryReadVisibilityRaceTests#shouldReportAClientSuppliedBoundAsInvalidUsageRatherThanCorruption`
  (1 of 50); disabling the constructor's not-found guard fails **3** of 50, adding
  `#shouldRaiseRatherThanGoDryForLastAppendedVersionMissingOnlyTrailingChecksum` and
  `CatalogWriteAheadLogIntegrationTest$MultiFileWalTests#shouldNotRaiseARawKryoFailureWhenTheNextWalFileIsStillAnEmptyStub`.
  The `CLIENT` test and its `INTERNAL` twin run against byte-identical on-disk state and differ only in the
  declared source, so a change that collapses the two arms cannot leave both green.
- Full functional suite, re-run after the capture-lifecycle fixes landed: **23,930 tests, 0 failures**,
  39 skipped, with the only error the Docker-dependent `ExportS3ServiceTest` that does not run in this
  environment. That single error is what makes the reactor report `BUILD FAILURE`, so the result has to be
  read from the aggregate rather than from the exit status.
- **The early-host-capture park** — a capture raised while a subscription is still registering is held in a
  single slot and flushed once `onSubscribe` returns, rather than the registering thread waiting on the
  subscriber. `SharedPublisherConcurrencyTest` — **11 tests, 0 failures** — covers the four terminal paths that
  must clear the slot (cancel before activation, retract, an `onSubscribe` that throws, and a finished
  subscription) plus the ordering assertion that a parked capture does not overtake one already queued.
  A `CountDownLatch` design was written first and **rejected because** it parks an engine thread on arbitrary
  subscriber code: the saturation fallback at `ChangeSystemCaptureSharedPublisher:392-401` runs delivery on the
  engine thread, so an `onSubscribe` that blocks would have stalled the publisher rather than one subscriber.
- **The WAL supplier's constructor releases what it acquired** — its forward scan was guarded by
  `catch (BufferUnderflowException)`, a clause nothing on that path can reach: `ObservableInput extends` Kryo's
  `Input`, so a short fill arrives as `KryoException("Buffer underflow.")` from `ObservableInput#require`, and
  `java.nio.BufferUnderflowException` has never been thrown anywhere in this repository. It had been dead since
  it was written, because `javac` rejects an unreachable catch only for *checked* exceptions. A premature end
  therefore escaped the constructor unconverted, and since nothing escapes a constructor that throws, it took
  the pooled Kryo and the open file with it. Two mutants pin the repair against
  `CatalogWriteAheadLogIntegrationTest$MisalignedReadSwallowTests#shouldReportNamedVersionAndReleaseKryoWhenConstructorScanUnderflows`:
  restoring the old constructor fails it on the exception type, and disabling only the release fails it on the
  pool count. `wal` tag group: **181 tests, 0 failures**. The release is a `finally` guarded by a completion
  flag rather than a trailing `catch`, because the not-found verdict is thrown *inside* the `try` and a sibling
  catch-all would hand the same Kryo to two callers.

### A test fixture was corrupting the evidence, and that is worth recording

The concurrent stress test originally failed for a reason that had nothing to do with any of the above. Every
WAL test built its Kryo pool as `new Pool<>(false, false, 1)` — an unsynchronized `ArrayDeque` — while
production uses `new Pool<>(true, false, 16)`, a `LinkedBlockingQueue`
(`DefaultCatalogPersistenceService:505`, `DefaultEnginePersistenceService:134`). The writer and every reader
draw from that one pool, so a racing `obtain()` handed **one Kryo to two threads**, and a Kryo is not
thread-safe. An identity-tracking probe added to the pool recorded four such violations in a single run
(`DOUBLE OBTAIN` / `FREE OF UNOBTAINED`), and the resulting garbage — a `BigDecimal` serializer dispatched at a
`LocalMutation` slot, a currency code read as `" g"` — is indistinguishable from a WAL read defect while
proving nothing about one. It cost a day of misattributed analysis.

The pool is now pinned to the production shape with no opt-out, and `KRYO_POOL_VIOLATIONS` stays in the fixture
so a recurrence is reported rather than inferred. **Removing the pool fault did not make the stress test pass**,
which is what ruled the fixture out as the whole story at the time and sent the search back to the reader. It is
why the probe earns its place: a concurrency fixture that differs from production silently converts every result
into a question. Note the limit of that argument in light of the re-measurement above — it establishes that the
fixture was not the *only* thing wrong then, not that the run still fails for the reason it failed then.

## Consequences & open follow-ups

- **The capture-lifecycle work is not backported to 2026.2, and the one gate that was backportable was
  declined with it.** The release branch carries a genuine double decrement: `unsubscribe` cancels the
  departing subscription, the cancel releases it, and the release re-enters `unsubscribe` through the
  publisher's own `onCancellation` hook while the map entry is still present — so one departing subscriber
  decrements `versionSubscribersCount` twice. A single subscriber hides it completely, because two decrements
  of `{V: 1}` both land on "remove the key" and the result is right by accident. It takes two subscribers
  sharing a tracked version to corrupt the count, and a third to collect the cost: the survivor stops being the
  lowest key, so `fillBuffer`'s `clearAllUntil(versionSubscribersCount.firstKey())` trims past captures it has
  not read, and the survivor falls back to a WAL read or stalls where retention has already reclaimed that
  segment. A gate on winning the removal — `remove` returning null means the re-entrant call already did the
  work — was written and verified on the release branch: **460 `cdc` tests, 0 failures**, pinned by a
  counterfactual that fails `CatalogChangeObserverTest#shouldReleaseOnlyTheDepartingSubscribersVersionSlot`.
  **Rejected because** that branch is a hotfix line, where the risk of introducing a new defect outweighs
  fixing a latent one. The gate touches no lock, but shipping it alone leaves the accounting still racy —
  `getTrackedVersion()` stays unsynchronised against a fill in flight — so it buys a partial repair at non-zero
  risk. Revisit only if the trim gate is observed to misfire in production.
  The rest is held back for reasons that outlive this record, and each is a trap for anyone who tries again:
  moving `onSubscribe` out of the subscription constructor restructures the registration protocol on a branch
  with no periodic sweep to catch a mistake; taking the publisher's lock across registration is safe on `dev`
  **only** because `activate()` moved subscriber code out of the mapping function *and* the version accounting
  moved onto a leaf lock — without both it reintroduces the publisher→subscription lock edge that `close()`
  avoids by cancelling after it releases the lock; `retractRegistration`, the facade's renew-and-retry and the
  observer factory's `compute` exist only to serve that lock and are dead code without it; and cancelling after
  releasing the publisher lock addresses a hazard that is latent there rather than live, because `cancel()`
  takes no subscription lock on that branch.

- **`VersionSource` is chosen per read, not per bound, and that was a decision rather than an oversight.**
  `EvitaSession#getMutationsHistoryForward` passes two version bounds with different origins — the floor is the
  client's `criteria.sinceVersion()`, the ceiling is the engine's own `catalog.getVersion()` — and labels the
  whole read `CLIENT`. Splitting them was considered: the supplier's two constructor guards are about reaching
  the *floor* and `MutationSupplier#get()`'s throws are about reaching the *ceiling*, so a second field would
  map cleanly and would let genuine damage met on the way to the engine's own version report as corruption.
  **Rejected because** it re-opens the door this record exists to shut — a client-initiated request that can
  raise an operator alarm — in exchange for better attribution of a failure that is rare on that path. The cost
  is real and accepted: damage discovered while serving a mutation-history query is reported as invalid usage
  and lands in `io_evitadb_client_errors_total` rather than the internal-error metric. Revisit only if that
  under-counting is ever observed to hide a real incident; the call site carries the same note.

- **The invariant now holds and is what the greedy stream rests on**: *a block that is safely written is
  immediately safe to read*. What guards it is `ObservableInputTest$BoundaryReadTests`, deterministically, in
  the fast loop — **not** the concurrent stress test, which does not fail on this defect's counterfactual. Do
  not read a green stress run as cover for a change to the buffer bookkeeping. The case for deleting
  `avoidPartiallyFilledBuffer` does not rest on the stress test either: it rests on the cause being found and
  covered, and on the flag carrying nothing about buffer filling by the time it was removed.
- **Why the stress test does not reproduce the defect is open, and worth settling before it is relied on.**
  Four experiments, cheapest first: re-run the counterfactual against the old unsynchronized
  `new Pool<>(false, false, 1)` fixture, which distinguishes a fixture fault in a single run; raise
  `TRANSACTION_COUNT` by an order of magnitude; run it under full-suite contention, where it was measured at
  28.9 s against ~7 s alone, since descheduling mid-read is what widens the window the defect needs. The fourth
  answers the question rather than bisecting it: instrument `require(int)` to count boundary reads that land in
  its fill or compaction branch during a stress run. Zero would settle it outright — it would mean no run length
  can help, because the supplier's `StorageRecord` reads realign the buffer to record boundaries and a WAL
  reader's 4- and 8-byte boundary reads then never straddle a refill.
- **Two further bookkeeping inconsistencies in `ObservableInput` were found by inspection and could not be made
  to fire.** They are recorded here so the next reader does not have to re-derive them, and **not** changed,
  because a hot path does not get edited on an argument:
  - `require():502` does `this.limit += count` after filling at the *local* `limit`, which is `actualLimit`
    when a record cap is in force; `this.limit = limit + count` would be correct on every path and identical
    wherever the two agree. The divergent path looks hard to reach — `constraintLimitWithRecordLength` caps
    precisely when the buffer holds more than the record still needs, in which case `require` returns early
    without filling.
  - The compaction branch at `:511-535` renumbers `position` and `total` but never `actualLimit`. `markEnd`'s
    `finally` and `seek()` both clear it, which is probably why it does not bite.
- **`TransactionLocations` has two defects in the same area**, found while tracing and out of scope here.
  `register()` does its lazy `locations == null` allocation *outside* the lock that `cut()` holds while setting
  the field to null — so two registrations can lose an array, and a `cut()` landing between the check and the
  `getLast()` inside the lock throws NPE. Separately `register()` uses a bare `tryLock()` and silently skips
  when it loses; because an entry is only appended when `last.version + 1 == version`, **one** skipped
  registration makes every later version un-appendable for the life of the file, and `findNearestLocation` then
  falls back to the file start, so every later supplier rescans the whole file.
- **`checkEvitaErrors` debouncing was deliberately not touched.**
  `2026-08-28-attributable-internal-error-metrics` records leaving it alone on purpose — *"with origins now
  logged, a flap is diagnosable rather than mysterious"*. Reversing that needs its own record.
- **Production confirmation is owed.** `increase(io_evitadb_errors_total[12h])` should fall to ~0 and
  `io_evitadb_probe_health_problem{problem_type="EVITA_DB_INTERNAL_ERRORS"}` should stay at 0 rather than
  57.6 % of cycles. It could not be checked while this was written — the observability MCP server would not
  connect.

## Related work

- `2026-08-28-attributable-internal-error-metrics` — established that error *construction* is what moves the
  counter, and shipped the `ErrorOriginLogger` that made this diagnosable at all. Its own consequences section
  predicted this class of report; what it did not predict is that the first such report would be a true
  positive.
- `2026-07-18-paged-index-corruption-and-flush-failure-boundary` — its `bug-03-commit-progress-hang.md`
  scenario root-caused a race on the *safe* stream variant, fixed by `880e6a142`. That fix worked around the
  reader defect for one caller; this record removes it for all of them.
- `2026-08-24-grpc-streaming-backpressure-readiness-gate` — records that CDC demand gating is blocked on #1446,
  a subscriber whose WAL pointer falls outside retention stalling silently. The `consumeQueue` catch here
  removes one of the two ways a CDC subscription can stall without telling anyone.

## Timeline

- **2026-09-11** — issue #1551 filed from production metrics
- **2026-09-13** — the issue's own proposed fix refuted; the tail-read framing refuted; the reader defect
  found, fixed and verified by a single-threaded counterfactual
- **2026-09-14** — the capture registration lifecycle family found and closed over three adversarial review
  rounds, the last of which replaced a latch with the early-host-capture park; the 2026.2 backport of the
  lifecycle work declined on hotfix risk; the WAL supplier constructor's dead catch removed and its resource
  release closed; PR #1571 merged to `dev`
