# CRC32C cumulative checksum — carry a bare `long` on the WAL read/replay path, eliminate `forceValue`/`reverseCrc32c` entirely

Issue: #760 (ALIVE churn / WAL replay CPU refinement, follow-up to the CRC32C combine ladder).
Not a correctness gap — `forceValue`/`reverseCrc32c` are already correct; this removes the need to
call them at all on the cumulative-checksum bookkeeping paths, rather than just making them faster.

Status: DESIGN, awaiting GO. Author dialogue: Johnny + Claude. Follow-up to
`docs/design/2026-07-08-crc32c-combine-instance-and-matrix-cache.md`, which fixed the forward
`combine()` static path but explicitly left `reverseCrc32c`/`forceValue` out of scope ("0.9%
residual... out of scope", §6 of that doc). A fresh async-profiler measurement
(`docs/reports/2026-07-09-warmup-test-remeasure-real-config.md`) shows `reverseCrc32c` now
dominates the *remaining* CRC32C CPU share (1.74pp of 2.93% total) — expected, since it's the one
part of the CRC32C machinery the ladder fix didn't touch.

**This plan proposes the bigger of the two levers discussed with Johnny**: instead of making
`reverseCrc32c` faster (a matrix-based O(1) replacement of its 32-step loop — a real but
constant-factor win, since unlike `combine` there is no redundant per-call rebuild to eliminate),
**remove every call to `forceValue`/`reverseCrc32c` from the cumulative-checksum bookkeeping paths
entirely**, by tracking the cumulative value as a bare `long` and only ever combining
independently-known, fixed-width chunks into it — mirroring exactly what
`ObservableOutput.finishRecord` (the write hot path) already does per §4.4 of the combine doc.

> **Scope grew beyond what was originally asked, flagging explicitly before implementation.**
> Johnny asked for this on the WAL **read/replay** path. While tracing every call site (§2) it turned
> out `AbstractMutationLog.java:1080-1086` — the WAL **write** path, once per transaction — *also*
> hits `forceValue`, contradicting the predecessor doc's §4.4 claim that the write side never
> touches it (that claim was true only for `ObservableOutput`'s *per-record* checksum, not this
> transaction-level cumulative layer sitting above it). So the real scope is **write + read, 4
> classes**, on the data-integrity-critical WAL chain — for a measured ~1.7%-CPU, *constant-factor*
> win on a test (`EvitaWarmUpInsertionTest`) that isn't even replay-heavy (the win should be larger
> on a replay-heavy workload — trunk incorporation, time-travel reads — not yet measured). That's a
> different risk/reward shape than "speed up one already-isolated read-path method," and worth a
> re-confirm before coding starts. **If the fuller scope doesn't clear the bar, the matrix-based
> `reverseCrc32c` speedup (§1 of the original two-option framing) remains the low-risk fallback** —
> same measured win location, far smaller blast radius, no touch to the write path at all.

> **IMPLEMENTATION UPDATE — §3's original plan was superseded before coding, per Johnny's decision.**
> §1/§2 below (the precondition analysis and the 5 call sites) remain accurate background — they're
> what motivated the fix and they're still the reason it's safe. But §3's original plan (convert
> `AbstractMutationSupplier`/`MutationSupplier`/`ReverseMutationSupplier`/`AbstractMutationLog`'s
> `Checksum`-typed fields to bare `long`, at the 4 call sites) has a correctness gap that surfaced
> during implementation, caught by the advisor before any of those 4 classes were touched: those
> fields are `Checksum`-*typed* today specifically so they dispatch polymorphically to
> `Checksum.NO_OP` when `StorageOptions#computeCRC32C()` is disabled — `NoOpChecksumCalculator`'s
> `equalsTo()` always returns `true` regardless of what's on disk. Converting the field to a bare
> `long` and comparing with `==` loses that dispatch: `MutationSupplier.get()` Phase 3's
> `Assert.isPremiseValid(checksum.equalsTo(readCumulativeChecksum), ...)` would start comparing a
> genuinely-computed CRC against the disk's `0` (what NO_OP mode always writes) and throw — breaking
> WAL reads for anyone running with checksums disabled. Fixing this the doc's original way means
> threading an explicit `computeCRC32C` guard through all 4 classes.
>
> **The chosen fix instead centralizes the optimization inside `Crc32CChecksum` itself** (the
> `Checksum` implementation those 4 classes already delegate to), rather than in its callers.
> `Crc32CChecksum` now holds a lazy dual-mode: a bare `long value` for **value mode** (`combine()`,
> `update(long)`, `update(int)`, `reset(long)` — exactly the operations §1 proves never need a live,
> arbitrarily-seeded register, so these never call `forceValue`), and the existing live
> `Crc32CWrapper` for **stream mode** (`update(byte)`, `update(byte[])`, `update(byte[], off, len)` —
> genuine streaming). The transition from value mode to stream mode seeds the live register via
> `forceValue` exactly once, lazily, *only if* a byte-stream call ever actually follows a value-mode
> op — a graceful correctness-preserving fallback that in practice is never exercised on today's WAL
> bookkeeping call patterns (verified by tracing every site in §2), since they always call
> `reset()`/`reset(long)` before starting a byte stream.
>
> This makes `Checksum.NO_OP` — and every one of the 4 call-site classes — **completely untouched**:
> they keep using the `Checksum` interface exactly as before, so checksums-off behavior is preserved
> automatically rather than by an added guard. Blast radius shrinks from 4 WAL-hot-path classes to
> essentially one file (`Crc32CChecksum.java`, plus the new `Crc32CWrapper.combineLong`/`combineInt`
> primitives §3.1 already called for). See the updated §3 below for the actual implementation; §4-§7
> are updated to match.

> **SECOND IMPLEMENTATION UPDATE — the single dual-mode class above was itself split in two, per
> Johnny's review, and one factual claim in this doc was wrong.** Johnny's objection: a single class
> that silently switches behavior based on which method was last called is a "not much clean from OOP
> standpoint" pattern — and asked whether two separate `Checksum` implementations, dispatched by
> `ChecksumFactory`'s two existing creation methods, would be cleaner.
>
> An exhaustive trace of **every** `Checksum` instance in the codebase (not just the 5 call sites
> above) found the honest answer is *"yes, but only if the factory contract is documented precisely,
> because it doesn't split as simply as `createChecksum()` = stream, `createCumulativeChecksum()` =
> value"*: three instances — `AbstractMutationLog.appendTransaction` (WAL write, ~line 1019-1086),
> `AbstractMutationLog.scanWalFile` (WAL recovery scan, ~line 463-492), and `Migration_2026_1` (WAL
> format upgrade) — **genuinely mix stream and value operations on the same instance with no
> intervening `reset()`**, e.g. `update(int)` → `update(byte[])` [streams the serialized transaction
> bytes] → `combine()` → `update(long)`, all constructed via `createCumulativeChecksum(...)`. This
> directly contradicts §2.5's own claim (echoed in the first update note above) that the
> stream-fallback path "is never exercised in practice" — it is, on the WAL write and scan paths,
> **that claim was wrong** and is corrected here. `AbstractMutationSupplier`'s read-side
> `cumulativeChecksum` field, by contrast, never streams at all (`update(byte)`/`update(byte[])` are
> never called on it) — genuinely pure value-mode.
>
> The resolution: **two classes**, dispatched by which `ChecksumFactory` method constructed them —
> - `Crc32CChecksum` (`createChecksum()`): simplified back to the original always-live-object
>   implementation, but now **throws** (`GenericEvitaInternalError`) on `combine()`/`update(int)`/
>   `update(long)`/`reset(long)` rather than silently paying `forceValue` for them, since nothing that
>   holds a `createChecksum()`-obtained instance needs those operations (verified exhaustively).
> - `CumulativeCrc32CChecksum` (`createCumulativeChecksum(long)`): the dual-mode value/stream class
>   from the first update, renamed, Javadoc corrected to state plainly that the WAL write and scan
>   paths *do* exercise the stream-fallback (one `forceValue` call at the transition, same as
>   originally measured — not eliminated on those two sites, only on the ones that stay pure value).
>
> `ChecksumFactory`'s Javadoc now documents this as an explicit, load-bearing contract ("picking the
> wrong one... is a programming error, not a style choice") rather than an implementation detail.
>
> **One production call site needed a change as a result** — `ObservableInput.this.checksum` (§2.5,
> the dominant per-mutation-record hot path) is constructed via `createChecksum()` by every caller,
> yet its finalize block (`combine()`/`update(long)` at lines 864-866) needs value-mode capability.
> Grepping every caller of `StorageRecord.readWithChecksum(...)` (the only path that ever sets
> `cumulatingChecksum = true`) found it is invoked **exclusively** from
> `AbstractMutationSupplier`/`MutationSupplier`/`ReverseMutationSupplier` — never from
> `AbstractMutationLog.createObservableInput`, `OffsetIndex`, `ReadOnlyFileHandle`,
> `WriteOnlyOffHeapWithFileBackupHandle`, or `Migration_2025_1`, all of which also construct
> `ObservableInput` via `createChecksum()` but never touch cumulative tracking on it. So exactly the 2
> `ObservableInput` construction sites in `AbstractMutationSupplier.java` (constructor + `moveToNextWalFile`)
> switched from `this.storageSettings.createChecksum()` to `this.storageSettings.createCumulativeChecksum(0L)`
> — everywhere else stayed on `createChecksum()` unchanged. (Several test files that construct
> `ObservableInput` directly to exercise its cumulative-checksum feature — `ObservableInputTest`,
> `StorageRecordTest` — needed the same switch for the same reason.)
>
> See the rewritten §3 below for the final implementation; §4/§6/§7 are updated to match.

> **THIRD IMPLEMENTATION UPDATE — the two-class split was reverted; back to one dual-mode class,
> per Johnny's review and an advisor consult.** Two things surfaced when Johnny re-read the split:
>
> 1. **A bug the split itself caused.** `Crc32CChecksum.update(int)`/`update(long)` were made to
>    throw, on the (wrong) assumption that they're "value-fold operations" like `combine()`/
>    `reset(long)`. They aren't: `Crc32CWrapper.withInt`/`withLong` write the primitive's bytes
>    directly into the *live* register (`crc32C.update(buffer, 0, N)`) — no `forceValue` involved,
>    ever, since `Crc32CChecksum`'s wrapper is always constructed fresh (`new Crc32CWrapper()`,
>    never seeded from a value). They're structurally identical in cost to `update(byte[])`. Throwing
>    on them was an overgeneralization from the interface's method names, not a real cost the split
>    was protecting against.
> 2. **"The internal switch still smells."** Splitting the class didn't remove the value/stream mode
>    branch Johnny originally objected to — it's still there, unchanged, inside
>    `CumulativeCrc32CChecksum`. The split added a second class and a documented factory contract
>    without touching the thing it was meant to fix.
>
> An advisor consult (given the whole conversation transcript) recommended collapsing back to a
> single class, for three reasons: (a) the split didn't remove the mode switch, it just relocated it
> — paying for a second class and a contract to fix nothing; (b) the `update(int)`/`update(long)`
> bug is direct evidence the class boundary was artificial — the two classes only ever genuinely
> differed in `combine()`/`reset(long)`, and that "difference" was just "throw"; (c) the split added
> a footgun (wrong factory method → runtime `GenericEvitaInternalError`) for a "misuse" that, once
> every operation is supported by one class, simply isn't a misuse. On the "still smells" point
> itself: a private boolean + branches behind a clean public interface, in perf-critical low-level
> code, is normal and matches this project's own perf-critical-code convention (avoid unnecessary
> allocation/dispatch, avoid premature abstraction) — a State-pattern extraction would add allocation
> and virtual dispatch to a hot WAL path to prettify a detail no caller ever observes. Johnny's
> original OOP objection was about a *public* class behaving inconsistently depending on which
> factory method built it — that's resolved by collapsing back to one class (both factory methods
> now return an object with the identical, full capability set), not by pushing the flag into
> strategy objects.
>
> **Final architecture**: one class, `Crc32CChecksum`, holding the same lazy value/stream dual mode
> as `CumulativeCrc32CChecksum` did, with the `update(int)`/`update(long)` bug fixed (both now work
> in both modes, never throwing). `CumulativeCrc32CChecksum.java` deleted.
> `ChecksumFactory#createChecksum()` and `#createCumulativeChecksum(long)` both construct
> `Crc32CChecksum`, differing only in constructor argument (`0L` vs. a caller-supplied value) — they
> are now fully interchangeable when the supplied value is `0L`, and the interface's Javadoc no
> longer claims a capability difference, only an intent/starting-value one.
> `AbstractMutationSupplier`'s two `ObservableInput` construction sites keep calling
> `createCumulativeChecksum(0L)` rather than reverting to `createChecksum()` — no longer required for
> correctness (both are identical now), kept purely because it documents that those instances are
> used for value-folding (`combine()`/`update(long)`), matching actual usage.
>
> **Addendum to the third update — `update(byte)` was asymmetric with `update(int)`/`update(long)`,
> also caught by Johnny.** In the collapsed class, `update(byte)` unconditionally called
> `enterStreamMode()` (forcing a `forceValue` transition immediately), while `update(int)`/
> `update(long)` stayed in value mode when possible. Johnny asked why, since nothing in the
> `Checksum` interface's contract distinguishes a 1-byte fold from a 4/8-byte one - "by definition
> these should behave the same way." Correct: unlike the earlier `update(int)`/`update(long)` throw
> (an outright bug), this asymmetry had an empirical justification - every real `update(byte)` call
> site traced (`OffsetIndexSerializationService`, `ObservableOutput.calculateChecksum`,
> `ObservableInput`'s finalize block, `OffHeapMemoryOutputStream.write(int)`) either fires
> immediately after a byte-array stream call on the same instance (already in stream mode by then)
> or is itself part of a genuine byte-by-byte `OutputStream`/`InputStream` write/read loop - so the
> asymmetry never actually costs an avoidable `forceValue` today. But that's a fact about today's
> callers, not something the interface guarantees, and the fix is cheap: added
> `Crc32CWrapper.combineByte(long, byte)`, the 1-byte counterpart to `combineLong`/`combineInt`
> (same thread-local-scratch pattern), and made `update(byte)` check `valueMode` exactly like the
> other two. `update(byte[])`/`update(byte[], off, len)` deliberately still don't get this treatment
> - see the class Javadoc (§3.2) for why (arbitrary-length chunks are typically streamed as several
> consecutive calls per record, where paying `combine()`'s cost on every call would be worse than
> paying `forceValue` once at the mode transition). `Crc32CWrapperTest` gained boundary + 20,000-pair
> randomized coverage for `combineByte` alongside the existing `combineLong`/`combineInt` tests.

## 1. The core insight — and the precondition it depends on

**Precondition (verified, not assumed): every cumulative-checksum operation on these paths is
either "fold in an already-independently-computed checksum of a known-length chunk" or "fold in a
fixed-width primitive" — never open-ended streaming of a variable-length, not-independently-known
byte range into the cumulative accumulator.** This is the premise the entire "bare long suffices"
argument rests on, so it was checked directly at *every* identified call site, not inferred from the
dominant one: `AbstractMutationLog:1080` (`combine(walReference.getChecksum(),
walReference.getContentLength())`), `AbstractMutationSupplier.readAndRecordTransactionMutation:447-450`
(`combine(txMutationWithChecksum.checksum(), ...recordLength())` — confirmed by reading the method
body, not just its javadoc), `MutationSupplier.readMutation:227-230` (identical shape, the actual
per-mutation hot path), and `ReverseMutationSupplier.get():176-182` (Phase 3 replay: `reset(txChecksums[0])`
then a loop of `combine(txChecksums[i+1], mappedPositions[i].recordLength())` — every value is a
pre-captured checksum from Phase 2's `storageRecord.checksum()` reads, never a raw byte range). All
four hold. The only genuine byte-level streaming anywhere in this area is the *per-record forward*
checksum computation (`ObservableInput.this.checksum`'s real `update(byte[], off, len)` calls,
§2.5) — untouched by this plan, exactly as designed.

`forceValue` (and therefore `reverseCrc32c`, which it depends on) exists for exactly one reason:
**`java.util.zip.CRC32C` has no public setter.** The only way to make `getValue()` return an
arbitrary target value is to feed the (real, hardware-backed) CRC32C state machine 4 "injection"
bytes computed by inverting its state-transition function. This is only *necessary* when a caller
needs a **live, further-updatable** checksum object seeded to an arbitrary starting state — i.e.
when more bytes will be streamed through `update()` afterward and the object must behave as if it
had already processed some prior, no-longer-available data.

**That need never actually arises on the cumulative-checksum bookkeeping paths.** Every operation
performed on a "cumulative checksum" object in this codebase is one of:

| operation | today | replacement (no live object needed) |
|---|---|---|
| fold in an independently-computed checksum of a known-length chunk (`checksum.combine(x, len)`) | `combine(getValue(), x, len)` then `forceValue(combined)` | `cumulative = Crc32CWrapper.combine(cumulative, x, len)` — **already a pure static long function**, this is what `combine()` calls internally *before* the (unnecessary, for this use) `forceValue` |
| fold in a raw fixed-width primitive (`checksum.update(long)` / `update(int)`) | streams the value's bytes through the live object | compute `crcOfValue` = CRC32C of that value's little-endian bytes **from a fresh, zero-state checksum** (cheap — 4 or 8 bytes, no `forceValue` involved, `java.util.zip.CRC32C`'s native zero-state is directly usable), then `cumulative = combine(cumulative, crcOfValue, len)` |
| read the current value (`getValue()`) / compare (`equalsTo()`) | reads the live object's state | read/compare the bare `long` variable directly |
| reset (`reset()` / `reset(long initialValue)`) | `crc32C.reset()` / `forceValue(initialValue)` | `cumulative = 0` / `cumulative = initialValue` |

None of these require an object whose internal register is actually seeded to an arbitrary value —
they only require *pure value-level* GF(2) arithmetic, which `Crc32CWrapper.combine(long, long,
long)` (the ladder-fixed static method) already provides. **The only genuinely live, streaming use
of a `Checksum` object anywhere on these paths is computing the forward, per-record/per-chunk
checksum of real payload bytes as they are written or read** (`ObservableOutput`'s per-record CRC,
`ObservableInput.this.checksum`'s per-record forward accumulation) — that part is untouched by this
plan; it's the *cumulative* bookkeeping layered on top that this plan converts to bare longs.

## 2. Call sites (verified by direct reading, not the design doc it supersedes)

### 2.1 `AbstractMutationLog.java:1080-1086` — WAL write, once per transaction
```java
this.checksum.combine(walReference.getChecksum(), walReference.getContentLength());
final long cumulativeChecksum = this.checksum.getValue();
this.contentLengthBuffer.clear();
this.contentLengthBuffer.putLong(cumulativeChecksum);
this.checksum.update(cumulativeChecksum);   // <-- forces the on-disk value itself into the chain
this.contentLengthBuffer.flip();
```
`this.checksum` here is a persistent `Checksum` field spanning the WAL writer's lifetime. Both
`combine()` (line 1080) and `update(long)` (line 1086, folding the just-computed cumulative value
itself back in — a self-referential chain link so a corrupted stored value is caught by the *next*
transaction's verification) hit `forceValue`. Lower frequency than the read side (once/transaction)
but non-zero, and — contrary to this plan's own predecessor doc's assumption that the write path
never touches `forceValue` — it does, just at a much lower rate than reads (§2.4 of the predecessor
doc's §4.4 only covered `ObservableOutput`'s *per-record* checksum, not this WAL-transaction-level
cumulative layer).

### 2.2 `AbstractMutationSupplier.java:368-375` (`moveToNextWalFile`) — once per WAL file
```java
final long initialChecksum = this.observableInput.readLong();
if (this.cumulativeChecksum == null) {
    this.cumulativeChecksum = this.storageSettings.createCumulativeChecksum(initialChecksum);  // ctor -> forceValue
} else {
    this.cumulativeChecksum.reset(initialChecksum);   // -> forceValue
}
this.cumulativeChecksum.update(initialChecksum);      // -> forceValue again (reuses the live object)
```
Rare (once per WAL file, not per transaction/mutation) — low value on its own, but the *same*
`this.cumulativeChecksum` field is the one hit at high frequency by §2.3, so it has to move to a
bare `long` together with that call site.

### 2.3 `AbstractMutationSupplier.java:427` (`readAndRecordTransactionMutation`) — once per transaction
```java
final int contentLength = theObservableInput.simpleIntRead();
this.cumulativeChecksum.update(contentLength);   // -> forceValue
```
Plus (per the method's own JavaDoc, step 4) "Combines the mutation's checksum into the running
cumulative checksum" further down in the same method — another `combine()` call on the same field.

### 2.4 `MutationSupplier.java:142-157` (`get()`, Phase 3) — once per transaction
```java
final long readCumulativeChecksum = getObservableInput().simpleLongRead();
final Checksum checksum = Objects.requireNonNull(this.cumulativeChecksum);
Assert.isPremiseValid(checksum.equalsTo(readCumulativeChecksum), ...);
this.transactionMutation.withCumulativeChecksum(readCumulativeChecksum);
checksum.update(readCumulativeChecksum);   // -> forceValue, chains the verified value forward
```

### 2.5 `ObservableInput.java:757-760` (`markPayloadStart`) and `:840-867` (checksum finalize) — **once per mutation record, the dominant call site**
```java
// markPayloadStart — snapshot the header-bytes forward checksum into the cumulative long, then reset
this.checksum.update(this.buffer, this.startPosition, this.position - this.startPosition);
this.cumulativeChecksum = this.checksum.getValue();          // already a bare `long` field!
this.cumulativeChecksumLength += ...;
this.checksum.reset();
...
// later, after the payload's forward checksum (payloadChecksum) is known and verified against disk:
if (this.cumulatingChecksum) {
    this.checksum.reset();                                                              // -> plain reset, cheap
    this.checksum.combine(this.cumulativeChecksum, this.cumulativeChecksumLength);       // -> forceValue
    this.checksum.combine(payloadChecksum, this.expectedPayloadLength);                  // -> forceValue
    this.checksum.update(loadedChecksum);                                                // -> forceValue
}
```
This is the **per-mutation-record** hot path the fresh profile's 1.74% CPU traces to — it fires
once for every mutation read during forward WAL replay, reverse replay, and trunk incorporation
(confirmed by the subagent research: `MutationSupplier.readMutation`, `ReverseMutationSupplier`'s
replay loop, and trunk incorporation all funnel through `StorageRecord.readWithChecksum` →
`ObservableInput`'s `markCumulativeChecksumStart`/`markPayloadStart`/finalize bracket). **`this.cumulativeChecksum` is already declared as a bare `long` field on `ObservableInput`** — the
object-reuse dance (`this.checksum.reset()` + three more calls) exists only because the *combine*
step is currently only exposed through the stateful `Checksum` interface, not because the data is
naturally object-shaped. This is the strongest, most direct evidence the conversion is a natural
fit, not a forced one: half of the plumbing (the `long` field) is already there.

**Important distinction preserved by this plan**: `this.checksum` in `ObservableInput` also does
the **genuine** forward per-record checksum computation (streaming the actual payload bytes,
`checksum.update(this.buffer, this.payloadStartPosition, payloadLength)` at line 845, and the
verification via `equalsTo(loadedChecksum)` at line 854, both *before* the block above) — that part
is real, unavoidable, streaming byte-level work and is **not** touched by this plan. Only the
*cumulative bookkeeping* that reuses the same object afterward (lines 862-867) is converted.

## 3. The fix (as implemented — single dual-mode `Crc32CChecksum`, chosen by constructor argument)

### 3.1 New pure-long primitives on `Crc32CWrapper`

```java
/**
 * Folds a fixed-width primitive's byte representation into a cumulative CRC32C value, without ever
 * seeding a live {@link java.util.zip.CRC32C} to an arbitrary state. Computes the CRC32C of the
 * value's little-endian bytes from a genuinely fresh (zero) checksum — the ONE case
 * {@link java.util.zip.CRC32C} supports natively, no {@link #forceValue} involved — then folds that
 * chunk checksum into {@code cumulative} via the existing ladder-based {@link #combine}.
 */
public static long combineLong(long cumulative, long value) { ... }   // len2 = 8
public static long combineInt(long cumulative, int value) { ... }     // len2 = 4
public static long combineByte(long cumulative, byte value) { ... }   // len2 = 1
```
Implemented as-is, with a `ThreadLocal<CRC32C>` + `ThreadLocal<byte[8]>` scratch pair (avoids a
per-call allocation without needing thread synchronization) rather than a fresh throwaway `CRC32C`
per call. `combineByte` was added slightly later (§ addendum above, Johnny caught the missing
symmetry with `update(byte)`) but follows the exact same pattern. `Crc32CWrapperTest` gates
correctness by cross-checking all three against the old forceValue-based path
(`new Crc32CWrapper(cumulative).withLong(value)/.withInt(value)/.withByte(value).getValue()`) across
boundary values and 20,000 random `(cumulative, value)` triples.

### 3.2 `Crc32CChecksum` — single class, lazy value/stream dual mode, no operation throws

Returned by both `ChecksumFactory#createChecksum()` (starting value `0L`) and
`#createCumulativeChecksum(long)` (caller-supplied starting value) — same class, same capability
set, only the constructor argument differs. Three fields:

```java
private final Crc32CWrapper crc32Wrapper;  // live register, used only in stream mode
private long value;                         // bare-long cumulative value, used only in value mode
private boolean valueMode;                  // which of the two is authoritative right now
```

- **Value mode** (the initial mode, and the mode entered by `reset()`/`reset(long)`): `combine()`,
  `update(long)`, `update(int)` all do pure `long` arithmetic via `Crc32CWrapper.combine`/
  `combineLong`/`combineInt` — never touch `crc32Wrapper`, never call `forceValue`. This is the
  *only* mode `AbstractMutationSupplier`'s read-side `cumulativeChecksum` field ever needs
  (confirmed: it never calls `update(byte)`/`update(byte[])`).
- **Stream mode** (`update(byte)`, `update(byte[])`, `update(byte[], off, len)`): the genuine
  streaming path, needed by `ObservableInput.this.checksum` (per-record forward checksum) and by the
  WAL write/scan/migration sites (§2.1, and `scanWalFile`/`Migration_2026_1`) that stream real bytes
  directly into their cumulative accumulator. `update(int)`/`update(long)` also work correctly in
  this mode — they delegate straight to `crc32Wrapper.withInt`/`withLong`, which write the
  primitive's bytes into the already-live register; no `forceValue` involved on that path either,
  since the register only ever needed seeding once, at the mode transition below.
- **Transition**: entering stream mode from value mode lazily seeds `crc32Wrapper` from `value` (via
  `forceValue`, skipped entirely when `value == 0`). This is genuinely exercised, not hypothetical:
  the WAL write path (`AbstractMutationLog.appendTransaction`) and WAL recovery scan (`scanWalFile`)
  both hit this transition once per transaction/scan-iteration, since they fold a scalar, then
  stream real transaction bytes, then fold more scalars, with no `reset()` in between. On those two
  call sites this plan reduces (not eliminates) the `forceValue` count — from one call per
  value-mode operation under the old always-live implementation, down to exactly one call at the
  value→stream boundary, since every operation on either side of that boundary now runs on its cheap
  native mode. On call sites that stay purely value-mode for their whole lifetime
  (`AbstractMutationSupplier`'s read-side field, `ObservableInput`'s finalize block once reset has
  run) it's eliminated entirely, as originally designed.
- **`getValue()`/`equalsTo()`**: read `value` directly in value mode, `crc32Wrapper.getValue()` in
  stream mode.

No operation throws. Every `Checksum` method is supported regardless of which factory method or
constructor produced the instance — there is no wrong choice to make, only a starting-value choice.

A useful side effect, beyond the original §2 scope: the constructor no longer calls `forceValue`
either (it just sets `value = initialChecksum & 0xFFFFFFFFL`), so every caller across the codebase
that constructs a cumulative checksum (`AbstractMutationLog:706`, `Migration_2026_1.java:415`, any
future one) gets that part of the elimination for free.

### 3.3 `ChecksumFactory` — two starting-value conveniences, not two capability tiers

`createChecksum()`'s Javadoc states it returns a fresh instance initialized to zero.
`createCumulativeChecksum(long)`'s Javadoc states it's equivalent to `createChecksum()` followed by
`reset(initialChecksum)`, minus the redundant zero-initialization — a convenience for call sites that
know their starting value up front (resuming a persisted checksum, or seeding a running total).
Both return the identical `Crc32CChecksum` class; when the supplied value is `0L` the two calls are
bit-for-bit interchangeable. `Crc32CChecksumFactory` dispatches `new Crc32CChecksum()` /
`new Crc32CChecksum(initialChecksum)` accordingly. `ChecksumFactory.NO_OP` is untouched — both its
methods keep returning the same `Checksum.NO_OP` singleton regardless.

### 3.4 One production call site, kept for documentation value (not correctness)

`AbstractMutationSupplier`'s two `ObservableInput` construction sites (the constructor's initial
WAL-file open, and `moveToNextWalFile`) call `this.storageSettings.createCumulativeChecksum(0L)`
rather than `createChecksum()`. This was load-bearing during the two-class split (§ update notes
above) to avoid the throw on `combine()`/`update(long)` that `ObservableInput`'s finalize block
needs. After the collapse it is no longer required for correctness — both factory methods now
produce an identical object for a `0L` starting value — but it's kept because it correctly documents
that these instances are used for value-folding, matching `ObservableInput`'s actual finalize-block
usage (`combine()`/`update(long)` at lines 864-866). `ObservableInput` itself: zero changes.

### 3.5 What does NOT change
- On-disk WAL format: byte-identical (same values, same combine math, same self-referential chain
  structure) — this is a pure computation-path change.
- The forward per-record checksum computation (`ObservableInput.this.checksum`'s real
  `update(byte[], off, len)` streaming calls, `ObservableOutput`'s per-record checksum,
  `OffsetIndex`'s per-record verification): untouched — all still constructed via `Crc32CChecksum`,
  same class as everywhere else.
- `Crc32CWrapper.forceValue`/`reverseCrc32c` themselves: left in place (still needed by
  `Crc32CChecksum`'s lazy value→stream transition, and by `Crc32CWrapper(long initialChecksum)`/
  `reset(long)`, which remain valid, still-used public API for any caller that genuinely needs a
  live, further-streamable object seeded to a value).
- `ObservableInput`, `MutationSupplier`, `ReverseMutationSupplier`, `AbstractMutationLog`: zero
  changes. Only `AbstractMutationSupplier` changed, and only at 2 `ObservableInput` construction call
  sites (§3.4, kept for documentation value, not correctness) — no logic changes anywhere in that
  class either.

## 4. Risks

| risk | mitigation |
|---|---|
| **NO_OP/checksums-off dispatch loss** — the original 4-class rewire plan would have broken `computeCRC32C=false` (see the first update note). | Resolved structurally, not by a guard: `Crc32CChecksum` is only ever selected by `Crc32CChecksumFactory`, which is only ever selected over `ChecksumFactory.NO_OP` by `StorageSettings` based on `computeCRC32C()`. `Checksum.NO_OP` itself is untouched. Checksums-off dispatch is preserved by construction. |
| **Bit-identical correctness** — a wrong `combineLong`/`combineInt` byte-order or a sign-extension slip on `int`/`long` values changes the computed checksum silently. | `Crc32CWrapperTest` cross-checks `combineLong`/`combineInt` against the old forceValue-based path for boundary values and 20,000 random pairs. `Crc32CChecksumTest` additionally cross-checks the *whole* class against a `LegacyChecksumOracle` (a minimal re-implementation of the pre-refactor always-live-object semantics) across 200 runs × 50 randomized operations each, mixing value-mode and stream-mode ops in every order — including the WAL write-path pattern (value fold → stream → combine → value fold, no reset) confirmed genuinely mixed by the wider trace. |
| **Mode-transition correctness** — the lazy value→stream `forceValue` fallback must be bit-identical to always-live-object behavior, and this path is genuinely exercised (not hypothetical). | Directly tested (`shouldTransitionFromValueModeToStreamModeCorrectly`, `shouldTrackWalWritePathPatternCorrectly`) and covered by the randomized oracle sweep. |
| **Class-boundary artificiality (retired risk)** — the earlier two-class split introduced a "wrong factory method → throws" footgun and a real bug (`update(int)`/`update(long)` wrongly threw, since they never needed `forceValue` in the first place). | Resolved by collapsing back to one class (§ third update note): every `Checksum` operation is supported regardless of which factory method or constructor produced the instance, so there is no wrong choice to make and nothing to throw on. |
| **Private mode-switch "smell"** — a boolean flag + branches inside `Crc32CChecksum` is an internal implementation detail some reviewers may want extracted into a State-pattern (per-mode strategy objects). | Deliberately not done: the flag is fully private, never leaks through the public `Checksum` contract, and this is perf-critical low-level code where the project's own conventions favor avoiding extra allocation/dispatch over premature abstraction (matches how the JDK's own codec/zip classes are built). Documented on the class Javadoc instead of objectified. |

## 5. Backward compatibility

None required. Bit-identical cumulative-checksum values (§2/§3's mitigation), no on-disk format
change, no `serialVersionUID` bump (intra-dev computation-path change, per the project's
`serialVersionUID` bump policy). The `Checksum` interface's public API is unchanged.
`Crc32CChecksum`'s public API is unchanged in both signature and behavior — every operation that
worked before still works, for both factory methods and both constructors, nothing throws that
didn't already throw before this whole plan started.

## 6. Test plan

- **`Crc32CWrapperTest`**: new `combineLong`/`combineInt`/`combineByte` correctness tests —
  cross-check against the "old way" (stateful object combine + update + getValue) for a spread of
  values including boundary patterns (0, -1, `Long.MIN_VALUE`/`Byte.MIN_VALUE`, alternating-bit
  patterns), plus 20,000 random `(cumulative, value)` triples. **Done — 74/74 green.**
- **`Crc32CChecksumTest`** (single test class for the single dual-mode class, after the two-class
  split was reverted): plain streaming correctness (byte/byte-array/slice), `equalsTo`/`reset`/
  reuse, plus a full cross-check of the value/stream dual mode against a `LegacyChecksumOracle` (a
  minimal re-implementation of the pre-refactor always-live-object semantics) — the exact WAL
  bookkeeping patterns from §2 (`reset()`→`combine`→`combine`→`update(long)`;
  `constructor(initial)`→`update(initial)`; the WAL write-path value→stream→combine→value pattern
  with no reset), the value→stream transition, and a 200-run × 50-op randomized sequence mixing
  every operation. **Done — 12/12 green.**
- **`ObservableInputTest`/`ObservableOutputTest`/`StorageRecordTest`**: cumulative-checksum
  round-trip tests already exist — construct their `ObservableInput` instances via
  `createCumulativeChecksum(0L)` where they exercise cumulative tracking (§3.4; no longer required
  for correctness after the collapse, kept for documentation value), otherwise unchanged.
  **Done — 230/230 green** (combined with `OffsetIndexTest`, `OffsetIndexSerializationServiceTest`,
  `Crc32CWrapperTest`, `Crc32CChecksumTest`; 18 pre-existing skips unrelated to this change).
- **WAL round-trip** (`@Tag(wal)`): `EngineMutationLogTest`, `CatalogWriteAheadLogTest`,
  `CatalogWriteAheadLogIntegrationTest`, `LongRunningCatalogWriteAheadLogIntegrationTest` — write,
  forward-replay, and reverse-replay a transaction sequence; verify cumulative checksums validate
  correctly and corruption is still detected. **Done — full `wal`-tagged group 80/80 green**
  (functional, 1 pre-existing skip) **+ 3/3 green** (long-running), including
  `EngineMutationLogTest`'s pre-existing corruption-detection test.
- **`StorageOptions#computeCRC32C()=false` regression**: since the bug the first pivot avoided was
  specifically a checksums-off breakage, added `EngineMutationLogTest.CumulativeCrc32Tests
  .shouldKeepCumulativeChecksumAtZeroWhenChecksumsDisabled` — writes 2 real transactions with
  `computeCRC32(false)`, asserts the cumulative checksum stays `0`, reads them back via the
  committed-mutation stream without throwing, and verifies the on-disk trailing 8 bytes are literally
  zero. **Done — green**, as part of the `EngineMutationLogTest` suite (19/19).
- **Measurement gate**: re-run async-profiler `-e cpu` on `EvitaWarmUpInsertionTest` to confirm
  `reverseCrc32c` drops out of (or shrinks sharply in) the profile's leaf list, and quantify the
  updated CRC32C CPU share. **Deferred** — Johnny asked to skip re-profiling for now (machine
  load); mechanism correctness is otherwise fully verified by the test suite above, but the actual
  CPU-share number for the report is not yet re-measured.

## 7. Step-by-step

1. ~~Add `Crc32CWrapper.combineLong(long, long)` / `combineInt(long, int)` (§3.1) +
   `Crc32CWrapperTest` correctness gate.~~ **Done.**
2. ~~Rewrite the single dual-mode class; split into two classes per Johnny's first OOP review
   (`Crc32CChecksum` stream-only + `CumulativeCrc32CChecksum` dual-mode); then, after Johnny caught a
   bug the split introduced (`update(int)`/`update(long)` wrongly throwing) and flagged that the
   internal mode-switch still smelled, consult the advisor and collapse back to a single dual-mode
   `Crc32CChecksum` class (§3.2-§3.4).~~ **Done.**
3. ~~Full WAL/replay/OffsetIndex/ObservableInput/Output regression sweep, including the
   `computeCRC32C=false` check (§6), re-run after the collapse.~~ **Done, all green.**
4. Re-profile (§6's measurement gate) — **deferred**, resume when the machine is free.
5. Leave `forceValue`/`reverseCrc32c` in `Crc32CWrapper` untouched — still valid public API for
   genuine seed-then-stream use cases and for `Crc32CChecksum`'s lazy-transition fallback; do not
   delete.
