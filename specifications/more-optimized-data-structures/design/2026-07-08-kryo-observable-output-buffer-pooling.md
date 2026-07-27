# Kryo ObservableOutput Pooling — Eliminate 14.5% Alloc Churn on the Off-Heap WAL Path

Status: **IMPLEMENTED 2026-07-08** (v2 design, green, uncommitted). Author: Claude. Date: 2026-07-08. Branch: `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`.

> **Implementation note (2026-07-08).** Implemented exactly as designed below: `ObservableOutputKeeper`
> gained `freeOffHeapOutputs` (keyless `ConcurrentLinkedDeque`) + `borrowOffHeapOutput`/`recycleOffHeapOutput`
> + an idle-eviction hook (`lastOffHeapActivityTime` gates the free-list clear in `cutOutputCache()` so a
> busy keeper doesn't get its warm pool wiped every 5-minute cut-task tick — a refinement over the plan's
> "clear unconditionally" sketch). `WriteOnlyOffHeapWithFileBackupHandle.createInitialOutput`/`releaseOffHeapMemory`
> wired as designed. Added 3 pool-correctness oracle tests (§7.2) to `WriteOnlyOffHeapWithFileBackupHandleTest`
> + a new `ObservableOutputKeeperTest` (5 tests, reflection-based free-list/eviction probes) + fixed one
> pre-existing mock-based test (`TransactionalStoragePartPersistenceServiceTest`) that stubbed
> `ObservableOutputKeeper` as a bare Mockito mock and broke once the keeper became genuinely load-bearing on
> this path. **A speculative `synchronized` guard on `releaseOffHeapMemory` was added then reverted** after
> a concurrency scare during gating turned out to be a **pre-existing, unrelated bug** — see investigation
> note below. Gates: 264 targeted unit/functional tests + 2448-test storage/transaction/wal sweep, all
> 0F/0E. Long-running gate (§7.4) intentionally **not** used to validate this change (see below).
>
> **Investigation note — `LongRunningEvitaTransactionalFunctionalTest` red is PRE-EXISTING, not caused by
> this change.** Running the plan's §7.4 long-running gate surfaced `InvalidMutationException: There is
> already entity PRODUCT with primary key N present!` repeating during WAL replay (`TrunkIncorporationTransactionStage`),
> stalling the 28-thread test until its 120s timeout. Suspected a double-recycle race in `releaseOffHeapMemory`
> (two plausible call sites: the WAL reference's close callback vs. handle `#close()`); traced
> `TransactionWalFinalizer` and found the two paths are actually mutually exclusive per transaction (commit
> hands the WAL off to the async pipeline *without* calling `close()`; rollback calls `close()` *without*
> ever taking a reference) — the race isn't reachable. Added a `synchronized` guard anyway as a cheap
> precaution; it did **not** change the symptom (still failed, different entity PK). Definitively isolated
> by reverting both production files to git HEAD (pure baseline, zero pooling code) in-place and re-running
> the identical isolated test: **the pure baseline reproduces the same failure** (2700+ identical errors).
> Conclusion: this is a pre-existing bug in the trunk-incorporation/WAL-replay pipeline, unrelated to
> `ObservableOutput` pooling — consistent with other known soak reds on this branch (`SharedRgeiSoakTest`
> #760 regression, `Bug #2 soak reload price-persistence`). The `synchronized` guard was reverted (dead
> weight for an unreachable race). Not fixed here — out of scope; worth a dedicated investigation.

> **v2 note.** v1 proposed pooling the `ObservableOutput` as a *field inside*
> `WriteOnlyOffHeapWithFileBackupHandle`, retained across transactions. That design is void: the
> handle is constructed **fresh per transaction** (`DefaultCatalogPersistenceService.createIsolatedWalPersistenceService`,
> line 1966), so a field on it is single-use and cannot be reused across transactions — it would
> eliminate ~0 % of the churn. v2 pools the `ObservableOutput` **above** the handle, in the
> already-injected `ObservableOutputKeeper`, as a keyless free-list. The measurement (§1) and the
> `ObservableOutput` reuse mechanics (§3) are unchanged and confirmed; only the pool *scope* changed.

**Outcome:** A keyless, thread-safe free-list of `ObservableOutput<OffHeapMemoryOutputStream>`
instances (each owning a 2 MB `byte[]` buffer) added to `ObservableOutputKeeper`. Each per-transaction
`WriteOnlyOffHeapWithFileBackupHandle` borrows an instance on its first off-heap write and recycles
it on release, instead of allocating a fresh one. Eliminates ~18.9 GB (= 14.5 %) of allocation per
ALIVE churn run. No on-disk format change, no BWC reader, no new public constructor parameter.

---

## 1. Problem (measured — do not re-derive)

async-profiler `-e alloc` on `EvitaWarmUpInsertionTest` unique/ALIVE churn (1 GB heap,
`minimalActiveRecordShare=0.4`, `fileSizeCompactionThresholdBytes=1 GB`, `outputBufferSize=2 MB`
default, compression **off**; 130.64 GB allocated over 4.5 min; 1.64 M records). JFR
`/tmp/alive-alloc.jfr`, collapsed `/tmp/alive-alloc-bytes.collapsed`.

**`ObservableOutput.<init>` = 14.5 % of all allocated bytes (~18.9 GB)** — the #2 allocator after
FrontCodedStringColumn. The collapsed profile attributes 100 % of `Output.<init>` allocations to
`ObservableOutput.<init>`. Summing the 5 distinct stacks (identical in their bottom 8 frames):

```
WriteOnlyOffHeapWithFileBackupHandle.execute
  → getObservableOutput
    → createInitialOutput
      → new ObservableOutput
        → new byte[outputBufferSize]     ← 2 MB per call
```

**Total across the 5 stacks: 20,246,584,039 bytes (18.86 GB).** At 2 MB/buffer that is ~10 000 fresh
`ObservableOutput` instances over the run — **one per transaction that writes to the off-heap WAL
handle** (see §2 for why exactly one).

> The `outputBufferSize` (2 MB default) is the maximum size of a single `StorageRecord` and is a
> central, load-bearing setting — it **cannot** be shrunk to attack this. The buffer count, not its
> size, is the lever: pool and recycle the buffers across transactions.

---

## 2. Why the allocation is exactly once per transaction (corrected root cause)

### 2.1 The handle is per-transaction — v1's fatal error

`DefaultCatalogPersistenceService.createIsolatedWalPersistenceService(UUID)` (line 1965) constructs a
**new handle for every transaction**, keyed to a unique per-transaction WAL path:

```java
public IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId) {
    return new DefaultIsolatedWalService(
        ...,
        new WriteOnlyOffHeapWithFileBackupHandle(         // ← fresh handle, every transaction
            this.storageSettings.transactionWorkDirectory()
                .resolve(transactionId.toString())
                .resolve(transactionId + ".wal"),          // ← unique path per transaction
            this.storageSettings.outputBufferSize(),
            ...,
            this.observableOutputKeeper,                   // ← shared, catalog-scoped (the pool home)
            this.offHeapMemoryManager,
            ...
        )
    );
}
```

`DefaultIsolatedWalService` is documented as **one instance per transaction** (`DefaultIsolatedWalService`
line 53) holding a `final` handle. So each handle's lifecycle is:

1. `new` handle (transaction start).
2. first `write()` → `getObservableOutput()` sees `offHeapMemoryOutput == null` → `createInitialOutput()`
   → **one** `new ObservableOutput` + `new byte[2 MB]` (line 467).
3. subsequent writes reuse that same field — no further allocation.
4. `getWalReference()` → `close()` → `releaseOffHeapMemory()` (line 319).
5. handle GC'd, taking its 2 MB buffer with it.

`createInitialOutput` runs exactly **once per handle = once per transaction**. Pooling a field inside
the handle (v1) therefore recycles nothing — the object owning the field dies with the transaction.
**The pool must outlive the handle.**

### 2.2 The per-record `OffsetIndex` write path is already pooled (unchanged from v1)

`OffsetIndex.doPut`/`StorageRecord` receive the `ObservableOutput` as a parameter; the caller borrows
it via `ObservableOutputKeeper.executeWithOutput`, which caches one output **per target file `Path`**
(`ObservableOutputKeeper.java:102`, single-lease, 5-min idle eviction). That path contributes zero
`ObservableOutput.<init>` allocations. It is **not** the source.

### 2.3 Why `ObservableOutputKeeper` does not already cover this path

The keeper keys by file `Path` (`cachedOutputToFiles`, line 102). Each transaction's WAL path is
unique (`transactionId + ".wal"`), so path-keyed caching yields **zero hits** on the WAL path.
Additionally the keeper only holds `ObservableOutput<FileOutputStream>` (file outputs), while the
off-heap WAL output is `ObservableOutput<OffHeapMemoryOutputStream>` created directly in
`createInitialOutput` (line 467), bypassing the keeper entirely. The fix (§4) adds a **keyless**
free-list to the keeper for exactly this gap.

---

## 3. Investigation findings — `ObservableOutput` is safely reusable (confirmed against source)

### 3.1 `setOutputStream` → `reset()` is the reuse entry point (verified for Kryo 5.6.2)

`ObservableOutput` does **not** override `setOutputStream(OutputStream)`. The inherited Kryo
`Output.setOutputStream` (disassembled from `kryo-5.6.2.jar`) is:

```
putfield outputStream        // this.outputStream = newStream
invokevirtual reset()V       // virtual → dispatches to ObservableOutput.reset()
```

`ObservableOutput.reset()` (line 616) calls `super.reset()` (position=0, total=0) and clears
`lastConsumedPosition`, `startPosition`, `payloadStartPosition`, `recordLengthPosition`,
`savedBytesByCompressionSinceReset`, `spareBuffer`, `cumulatingChecksum`, `cumulativeChecksum`. So
`setOutputStream(newRegion)` fully resets all per-record and cumulative-checksum state.

The `byte[] buffer`, `capacity`, `checksum`, `deflater`, `deflateBuffer` **survive** `reset()` (final
or untouched). `markCumulativeChecksumStart()` (line 404) must be called after `setOutputStream` to
re-arm cumulative checksumming for the new stream — exactly what `createInitialOutput` does after
construction (line 474). Reuse contract = `setOutputStream(newStream)` + `markCumulativeChecksumStart()`.

Buffer growth is permanent (`require()` doubles via `Arrays.copyOf`; `reset()` does not shrink), so a
pooled once-grown output keeps its larger buffer — a small bonus, bounded per §6.

### 3.2 Never call `ObservableOutput.close()` on a recycled instance

`Output.close()` calls `flush()` + `outputStream.close()`, killing the stream. For recycling, the
region stream is closed **separately** (§3.3); the `ObservableOutput` instance is handed back to the
pool without `close()`, so its buffer survives.

### 3.3 The mutation bytes live in the off-heap **region**, not the heap buffer — the safety keystone

This is the property that makes recycling safe while a read reference is outstanding.
`toReadOffHeapWithFileBackupReference` (line 258) hands the WAL consumer the **region's** `ByteBuffer`,
not the `ObservableOutput`'s heap buffer:

```java
final OffHeapMemoryOutputStream outputStream = this.offHeapMemoryOutput.getOutputStream();
final ByteBuffer byteBuffer = outputStream.getByteBuffer();   // ← off-heap region slice
byteBuffer.limit(this.lastConsistentWrittenPosition);
return OffHeapWithFileBackupReference.withByteBuffer(
    byteBuffer, this.lastConsistentWrittenPosition, this.lastConsistentChecksum,
    this::releaseOffHeapMemory);                                // ← region freed on reference close
```

The consumer reads the mutation bytes directly from the off-heap region (which stays alive until the
reference is closed → `releaseOffHeapMemory` frees the region slot). The `ObservableOutput`'s heap
`byte[]` was only a staging buffer whose finalized contents were already flushed into the region; it
is **not** referenced by the outstanding reference. Therefore recycling the heap buffer at
release-time — even while another transaction immediately borrows it — cannot corrupt any in-flight
read. No aliasing exists between the pooled heap buffer and the reference. (Contrast: v1's aliasing
risk §6 was a hand-wave; here it is structurally impossible.)

### 3.4 Region acquire/release is independent of the heap buffer

`OffHeapMemoryManager.acquireRegionOutputStream()` (line 113) constructs a fresh lightweight
`OffHeapMemoryOutputStream` wrapper (with its own small `Checksum`) and slices a region `ByteBuffer`
from the shared direct block — no large heap array. `OffHeapMemoryOutputStream.close()` (line 263)
fires the finalizer that frees the region slot (`usedRegions` CAS to null) and nulls the wrapper's
buffer. Region count defaults to **256** (`transactionMemoryRegionCount`), so at most 256 off-heap
outputs are ever concurrently live → the free-list is naturally bounded at ≤ 256 (§6).

---

## 4. Core design — a keyless `ObservableOutput` free-list in `ObservableOutputKeeper`

### 4.1 Principle

Add to the catalog-scoped `ObservableOutputKeeper` (already injected into every WAL handle) a
**keyless, thread-safe free-list** of recyclable `ObservableOutput<OffHeapMemoryOutputStream>`
instances. The per-transaction handle:

- **borrows** on its first off-heap write (`createInitialOutput`): pop a free instance and rebind it
  to the freshly acquired region via `setOutputStream` + `markCumulativeChecksumStart`; if the
  free-list is empty, construct a new instance (the only allocation, now amortized across the pool).
- **recycles** on clean release (`releaseOffHeapMemory`): close the region stream (frees the slot),
  then push the `ObservableOutput` instance back onto the free-list. The handle field is **nulled**
  (keeping today's idempotent double-release behavior — the handle stays single-use; nothing is
  retained across transactions).

The free-list piggybacks on the keeper's existing `cutTask` (5-min inactivity eviction) and `close()`
orchestration, so idle 2 MB buffers are released off-peak exactly like the file-output cache.

### 4.2 New API on `ObservableOutputKeeper`

```java
/** Free-list of recyclable off-heap WAL outputs (keyless — WAL paths are unique per transaction). */
private final java.util.concurrent.ConcurrentLinkedDeque<ObservableOutput<OffHeapMemoryOutputStream>>
    freeOffHeapOutputs = new ConcurrentLinkedDeque<>();

/**
 * Borrows a recyclable off-heap output rebound to {@code stream}, or builds one via {@code createFct}
 * when the free-list is empty. The returned output has fresh per-record and cumulative-checksum state.
 */
@Nonnull
public ObservableOutput<OffHeapMemoryOutputStream> borrowOffHeapOutput(
    @Nonnull OffHeapMemoryOutputStream stream,
    @Nonnull Supplier<ObservableOutput<OffHeapMemoryOutputStream>> createFct
) {
    this.cutTask.schedule();
    final ObservableOutput<OffHeapMemoryOutputStream> recycled = this.freeOffHeapOutputs.pollFirst();
    if (recycled != null) {
        recycled.setOutputStream(stream);          // reset() → clears all per-record/cumulative state
        recycled.markCumulativeChecksumStart();    // re-arm cumulative checksum for the new region
        return recycled;
    }
    return createFct.get();                          // cold path: allocate (createFct arms checksum)
}

/** Returns a cleanly-released off-heap output to the free-list for reuse. Must NOT be leased/aliased. */
public void recycleOffHeapOutput(@Nonnull ObservableOutput<OffHeapMemoryOutputStream> output) {
    this.freeOffHeapOutputs.offerFirst(output);
}
```

Eviction & shutdown fold into the existing lifecycle:
- `cutOutputCache()` (line 297): after the existing file-cache sweep, drop idle off-heap buffers.
  Simplest correct policy mirroring the file cache — if the whole keeper has been idle past the
  threshold, `this.freeOffHeapOutputs.clear()` (the instances are plain heap buffers; GC reclaims
  them; no stream to close since recycled instances already had their region closed).
- `close()` (line 220): `this.freeOffHeapOutputs.clear()` alongside the file-cache teardown.

> Type note: `OffHeapMemoryOutputStream` (package `io.evitadb.store.offsetIndex.io`) is importable
> from `ObservableOutputKeeper` (package `io.evitadb.store.kryo`) — same module, and `ObservableOutput`
> already depends on `io.evitadb.store.offsetIndex.model.StorageRecord`, so no new module edge.

### 4.3 Handle changes — borrow on create, recycle on release

**File:** `WriteOnlyOffHeapWithFileBackupHandle.java`

| Site | Line | Change |
|------|------|--------|
| `createInitialOutput()` | 467–475 | Replace the `new ObservableOutput<>(...)` + `markCumulativeChecksumStart()` with `this.offHeapMemoryOutput = this.observableOutputKeeper.borrowOffHeapOutput(offHeapRegion.get(), () -> { final ObservableOutput<OffHeapMemoryOutputStream> o = new ObservableOutput<>(offHeapRegion.get(), this.outputBufferSize, 0, this.checksumFactory.createChecksum(), this.compressionFactory.createCompressor().orElse(null)); o.markCumulativeChecksumStart(); return o; });`. The file-fallback branch (line 460–465) is unchanged. |
| `releaseOffHeapMemory()` | 319–324 | Null the field **first** (idempotency), then close the region stream, then recycle the instance (see below). |
| `offloadMemoryToDisk()` | 428 | Unchanged: `this.offHeapMemoryOutput = null;`. **Do not recycle** on overflow (§6). |
| `close()` | 240–246 | Unchanged. It already routes to `releaseOffHeapMemory()` when `offHeapMemoryOutput != null`; recycling now happens inside that method. |

New `releaseOffHeapMemory()`:

```java
private void releaseOffHeapMemory() {
    final ObservableOutput<OffHeapMemoryOutputStream> out = this.offHeapMemoryOutput;
    if (out != null) {
        // null the field first so a second release (reference-close AND handle-close both call this)
        // is a no-op and never double-recycles the same instance
        this.offHeapMemoryOutput = null;
        // release the off-heap region: close the OffHeapMemoryOutputStream (finalizer frees the slot)
        // WITHOUT closing the ObservableOutput, so its byte[] buffer / checksum / deflater survive
        out.getOutputStream().close();
        // hand the reusable ObservableOutput back to the catalog-scoped free-list
        this.observableOutputKeeper.recycleOffHeapOutput(out);
    }
}
```

This preserves today's exact region-release timing (region freed only when the reference is
closed / the handle is closed — unchanged correctness), and adds recycling at that same point. Because
the mutation bytes live in the region, not `out`'s heap buffer (§3.3), a concurrent transaction may
re-borrow `out` immediately with no read hazard.

### 4.4 `getObservableOutput()` — no change

`getObservableOutput()` (line 441) is unchanged: `offHeapMemoryOutput != null ? it : (fileOutput != null ? it : createInitialOutput())`.
Within a single transaction the field is set once by `createInitialOutput` and reused; the borrow
happens inside `createInitialOutput`. (v1's rebind branch here is deleted — it was the broken part.)

### 4.5 `ObservableOutput.java` — no change

The inherited `setOutputStream` + `ObservableOutput.reset()` + `markCumulativeChecksumStart()` provide
the reuse primitive (§3.1). No new method needed.

### 4.6 Alternative considered and rejected — recycle the whole handle

Johnny asked to weigh recycling the entire `WriteOnlyOffHeapWithFileBackupHandle` across transactions.
Rejected: the handle embeds the **unique per-transaction WAL path** (`transactionId + ".wal"`),
resolved and `final` at construction in the WAL factory. Reusing the handle would require making the
path mutable, re-resolving the work directory per reuse, and maintaining a keyed handle cache — a
larger, more invasive surface than pooling the single expensive field. The only costly part of a
handle is its `ObservableOutput` buffer, which §4 targets directly. Pooling the buffer strictly
dominates.

### 4.7 Alternative considered and rejected — pool only the `byte[]` (not the instance)

Retaining only the buffer (via the 4-arg `ObservableOutput(stream, buffer, checksum, deflater)` ctor)
still allocates a `Checksum`, `Deflater`, and — when compression is on — a second `deflateBuffer`
(`new byte[buffer.length]`, 2 MB) per transaction. The ALIVE test runs compression off, but
production commonly runs it on, where `deflateBuffer` churn would re-introduce ~14.5 %. Pooling the
whole instance covers both and is no more complex.

---

## 5. Backward compatibility

- **Intra-dev change** (`#760`). No `serialVersionUID` bump, no BWC reader (per the serialVersionUID
  bump policy — BWC only across released minors).
- **No on-disk format change.** Pooling is purely in-memory lifecycle; the bytes written to the region
  and WAL file, the checksums, and the record framing are byte-identical.
- **No public constructor change.** `borrowOffHeapOutput` / `recycleOffHeapOutput` are new public
  methods on `ObservableOutputKeeper`, which the handle already holds. No new dependency is threaded
  through `DefaultCatalogPersistenceService`.

---

## 6. Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| **Heap-buffer aliasing with an outstanding read reference** | **None (structural)** — the reference holds the off-heap **region** `ByteBuffer` (§3.3, `toReadOffHeapWithFileBackupReference:263-266`), never `out`'s heap buffer. Recycling `out` cannot affect an in-flight read. | Documented invariant; a test asserts read-after-recycle correctness (§7.2). |
| **Double-recycle** — both the reference-close callback and `DefaultIsolatedWalService.close()` call `releaseOffHeapMemory`. Recycling twice would hand one instance to two borrowers. | None — `releaseOffHeapMemory` nulls the field **before** recycling; the second call sees `null` and no-ops (same idempotency as today's null-guard). | Field-null-first ordering; enforced by test (§7.2). |
| **Concurrency** — multiple transactions build isolated WALs on different threads; the free-list is shared. | Real (unlike v1's single-writer claim, which held only for one handle). | `ConcurrentLinkedDeque` is lock-free and thread-safe; borrow/recycle are single push/pop. Each `ObservableOutput` is only ever owned by one handle at a time (removed from the deque on borrow). |
| **Unbounded buffer retention** — free-list holds 2 MB buffers when idle. | Bounded — ≤ 256 instances (region count), so ≤ 512 MB worst case, and only if 256 transactions were concurrently live (that memory was already committed at peak). | 5-min idle eviction via existing `cutTask` clears the free-list; `close()` clears it. No net peak increase vs. today. |
| **Capacity-growth retention** — a large transaction grows a buffer; the grown buffer persists in the pool. | Acceptable — growth is monotonic and rare; a once-grown buffer avoids re-growth. | Optional future refinement: on recycle, drop instances whose `buffer.length > outputBufferSize * 2`. Not needed now. |
| **Overflow path recycling a half-written instance** — `offloadMemoryToDisk` fires mid-record on `BufferOverflowException`; the instance holds partial state. | N/A by choice — v2 **drops** the off-heap instance on overflow (does not recycle), letting GC reclaim it. | Overflow is exceptional (region full), not the steady-state hot path; the pooling win is on the common path. `offloadMemoryToDisk` keeps its plain `offHeapMemoryOutput = null`. |
| **Checksum contamination across reuse** | None — `setOutputStream` → `reset()` clears `cumulativeChecksum`/`cumulatingChecksum` (line 627-628); `markPayloadStart` resets the per-record checksum; the region brings its own fresh `Checksum`. | Verified against `ObservableOutput.reset()` (line 616) and `markCumulativeChecksumStart` (line 404). |

---

## 7. Test gates

### 7.1 Unit / functional (must pass, 0F/0E)

- **`WriteOnlyOffHeapWithFileBackupHandleTest`** — primary handle test: sequential-transaction reuse,
  overflow → `offloadMemoryToDisk`, file-fallback.
- **`DefaultIsolatedWalServiceTest`** — the WAL service that owns the handle.
- **`CatalogWriteAheadLogIntegrationTest`** — WAL integration: mutations written and readable after the change.
- **`StorageRecordTest`** — records written through a reused `ObservableOutput` (no state leakage).
- **`OffsetIndexTest` / `OffsetIndexSerializationServiceTest`** — regression gate for the untouched
  per-record path.
- A keeper-focused test (`ObservableOutputKeeperTest` if present, else add) — borrow/recycle/evict/close
  of the new free-list.

### 7.2 New tests — pool correctness oracles

Because the pool now lives in `ObservableOutputKeeper`, the oracle targets the keeper + handle
interaction, not "same instance on one handle" (v1's untestable premise — two transactions use two
handles):

1. **Recycle-then-reuse identity**: with a shared keeper, run transaction A through a handle to
   completion (write → `toReadOffHeapWithFileBackupReference` → close the reference), then run
   transaction B through a **second** handle sharing the same keeper. Assert (via a keeper
   free-list-size probe / a `create`-count spy) that B's off-heap output was the **recycled** instance
   from A — i.e. `createFct` ran for A but not for B.
2. **Read-after-recycle correctness (the safety keystone)**: hold A's `OffHeapWithFileBackupReference`
   open (do not close it), run B through a second handle that borrows a fresh (or recycled) output and
   writes **different** mutations, then read A's reference and assert its bytes are still A's — proving
   B's writes did not corrupt A's region/data. Then close both and assert no double-free.
3. **Overflow drop**: force `offloadMemoryToDisk`; assert the free-list did not receive the
   half-written instance and the subsequent file path still reads back correctly.

### 7.3 Measurement gate (before merge)

Re-run async-profiler `-e alloc` on `EvitaWarmUpInsertionTest` unique/ALIVE (same config). Expected:
- `ObservableOutput.<init>` drops from 14.5 % (~18.9 GB) to **< 0.1 %** (only ≤ 256 cold-start
  constructions + rare overflow re-creations remain).
- Total allocated drops ~18.9 GB (~130 GB → ~111 GB).
- GC share (currently 31.1 %) drops proportionally.

Also run **one** profile with **compression on** to confirm the `deflateBuffer` churn (§4.7) is
likewise eliminated (instance pooling retains the deflater + deflateBuffer).

### 7.4 Long-running (0F/0E)

- **`EvitaWarmUpInsertionTest`** (the measurement vehicle).
- **`LongRunningEvitaTransactionalFunctionalTest`** — broad concurrent churn with WAL (exercises the
  free-list under real thread concurrency — the key correctness surface).
- **`LongRunningCatalogWriteAheadLogIntegrationTest`** — WAL-specific.

---

## 8. Step-by-step implementation

### Step 1 — `ObservableOutputKeeper`: add the free-list + borrow/recycle

**File:** `evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo/ObservableOutputKeeper.java`

- Add field `freeOffHeapOutputs` (`ConcurrentLinkedDeque<ObservableOutput<OffHeapMemoryOutputStream>>`).
- Add `borrowOffHeapOutput(OffHeapMemoryOutputStream, Supplier<...>)` and
  `recycleOffHeapOutput(ObservableOutput<OffHeapMemoryOutputStream>)` (§4.2).
- In `cutOutputCache()` (line 297) and `close()` (line 220): `freeOffHeapOutputs.clear()` when the
  keeper is idle / closing.
- Import `io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream` and `java.util.function.Supplier`.
- JavaDoc on all new members.

### Step 2 — `WriteOnlyOffHeapWithFileBackupHandle`: borrow in `createInitialOutput`

**File:** `.../offsetIndex/io/WriteOnlyOffHeapWithFileBackupHandle.java`, `createInitialOutput()` (line 467–475)

Replace the direct `new ObservableOutput<>(...)` off-heap branch with
`this.observableOutputKeeper.borrowOffHeapOutput(offHeapRegion.get(), () -> { ... construct + markCumulativeChecksumStart ... })`
(§4.3). Leave the file-fallback branch untouched.

### Step 3 — `WriteOnlyOffHeapWithFileBackupHandle`: recycle in `releaseOffHeapMemory`

**File:** same, `releaseOffHeapMemory()` (line 319–324)

Replace with the null-first / close-region / recycle body (§4.3). Leave `offloadMemoryToDisk` (line 428)
and `close()` (line 240) unchanged — overflow drops, close routes through `releaseOffHeapMemory`.

### Step 4 — Tests

Add §7.2 oracles to `WriteOnlyOffHeapWithFileBackupHandleTest` (sharing a single `ObservableOutputKeeper`
across two handles) and free-list unit coverage to the keeper test.

### Step 5 — Run gates

```shell
# Install the engine first (build gotcha from memory)
mvn install -DskipTests -pl evita_engine -am

mvn test -pl evita_test/evita_functional_tests \
  -Dtest='WriteOnlyOffHeapWithFileBackupHandleTest,DefaultIsolatedWalServiceTest,CatalogWriteAheadLogIntegrationTest,StorageRecordTest,OffsetIndexTest,OffsetIndexSerializationServiceTest,ObservableOutputKeeperTest' \
  -DfailIfNoTests=false

mvn test -pl evita_test/evita_long_running_tests \
  -Dtest='EvitaWarmUpInsertionTest,LongRunningEvitaTransactionalFunctionalTest,LongRunningCatalogWriteAheadLogIntegrationTest' \
  -DfailIfNoTests=false
```

### Step 6 — Measurement gate

Re-run the async-profiler alloc profile (§7.3), compression off **and** on. Confirm
`ObservableOutput.<init>` → < 0.1 %.

### Step 7 — Commit

```
perf: pool off-heap WAL ObservableOutput in ObservableOutputKeeper, drop 14.5% alloc churn

Each transaction builds a fresh WriteOnlyOffHeapWithFileBackupHandle whose first
off-heap write allocated a new ObservableOutput (2MB byte[]) — ~10k/run = 18.9GB
(14.5% of allocations). The buffer cannot be pooled inside the handle (single-use
per transaction), so add a keyless thread-safe free-list of off-heap ObservableOutput
instances to the catalog-scoped ObservableOutputKeeper (already injected into every
handle). The handle borrows on first write (rebind a recycled instance via
setOutputStream + markCumulativeChecksumStart, or construct on miss) and recycles on
release. Safe because the WAL read reference holds the off-heap region ByteBuffer, not
the ObservableOutput heap buffer, so recycling never aliases an in-flight read. Idle
buffers evict via the keeper's existing 5-min cut task.

Ref: #760
```

---

## 9. Out of scope

- **Pooling the `OffHeapMemoryOutputStream` wrapper / its `Checksum`** — lightweight, no large arrays
  (region is sliced from the shared direct block); per-transaction allocation negligible.
- **Pooling `OffsetIndex.copySnapshotTo` / `AbstractMutationLog` outputs** — already reused via
  `reset()` within a single call / per WAL file; negligible and rare.
- **Shrinking `outputBufferSize`** — rejected: it is the central max-`StorageRecord`-size setting.
- **Recycling the whole handle** — rejected (§4.6); the per-transaction unique path makes it more
  invasive than pooling the one expensive field.
