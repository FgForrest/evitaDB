# Proposal: split the transactional-memory interfaces, and key layers by a primitive `long`

> **Direction agreed with Johnny:** split the interfaces, rename the wrapper to an entry, and make
> `0L` a reserved sentinel for layer-less producers.

## 0. Implementation status

| step | state |
|---|---|
| 1 — reserve `0L`, drop the domain flag | **DONE** |
| 2a — interface split, widen the 38 MERGE sites | **DONE** |
| 2b — drop the vestigial `Void layer` parameter (21 classes) | **DONE** |
| 3 — `TransactionalLayerWrapper` → `TransactionalLayerEntry` + creator ref | **DONE** |
| 4 — HPPC `long`-keyed registry (§4.5) + §4.4 assertion | **DONE** |

**Verified after 4:** 6915 green on the targeted surface (`io.evitadb.core.**`, `io.evitadb.index.**`,
`io.evitadb.store.**`), and **20569 green on the full `unitAndFunctional` suite** — the sole error is
`ExportS3ServiceTest` (Testcontainers finds no Docker environment on this box), unrelated to the change.
The §4.4 one-id-one-creator assertion **never fired** across the full suite, and no
`StaleTransactionMemoryException` appeared anywhere. All work is **uncommitted** in the main tree on `pending-fixes-2026-07-20`;
this refactor is **not bound to an issue**, so commits carry no `Ref:` line.

### 0.3 The §4.4 assertion earned its keep immediately — and `clone()` was deleted

Its very first run failed 4 tests, all on `clone()` inside a transaction. `Object.clone()` copies
every field verbatim — including the `final long id` — so **the clone resolved to the original's diff
layer**. The two instances silently shared every change, which is precisely the corruption the
assertion exists to make loud. The composite `(class, id)` key it replaced could never have caught
this: the clone has the same class *and* the same id.

Four classes implemented `Cloneable`: `TransactionalMap`, `TransactionalList`, `TransactionalSet` and
`PersistentTransactionalMap` (the latter covering `PersistentTransactionalProducerMap` by
inheritance). All four `clone()` bodies documented the intent the bug defeated — *"the clone's own
layer"*, *"both instances can diverge afterward"*.

**Resolution (Johnny's call): `clone()` is gone entirely.** No production code anywhere in the repo
called it — only tests did — so the capability, the `Cloneable` marker and the 8 clone tests were all
removed rather than repaired. Nothing tests `instanceof Cloneable`, so no class was forced to keep the
marker and no `UnsupportedOperationException` stub was needed: with `Cloneable` gone, `Object.clone()`
is `protected` and a call site becomes a **compile error**, which beats any runtime guard.

This also made `MapChanges#copyState`, `ProducerMapChanges#copyState` and `SetChanges#copyState` dead
(package-private, and `clone()` was their only caller) — removed too.

**A rejected earlier fix**, recorded so it is not re-attempted: dropping `final` from `id` and
assigning a fresh `SEQUENCE.nextId()` in `clone()`. It worked and its safe-publication argument held
(committed graphs publish through `CatalogWrapper.replaceCatalogReference` → `AtomicReference.set`),
but deleting the feature is strictly better — `id` stays `final`, and a *copy constructor* replacement
was rejected in turn because the layer copy cannot run in a constructor body: `PersistentTransactional`
`ProducerMap` overrides `createLayer()` using its own fields, which are unassigned while the super
constructor runs.

**Test count moved 6923 → 6915** — exactly the 8 deleted clone tests, no other change.

### 0.4 Measured on senesi WAL replay (2026-07-21)

**Verdict: the targeted allocation term is real and is gone. Total allocation and throughput are
unchanged within measurement error.**

Fixture: 542 production transactions / 41 254 mutations replayed against an embedded instance booted
from a senesi snapshot. Deterministic — every one of the 8 runs replayed exactly 542. Baseline is
HEAD `78512d0ad`, built in a separate worktree; sides were built and run **sequentially** (shared
`~/.m2`), each with its own copy of the WAL source (the fixture opens it as a live
`CatalogWriteAheadLog`, which can rotate/purge).

**Direct attribution (JFR `ObjectAllocationSample`, 1 run/side) — the reliable measurement:**

| | baseline | refactor |
|---|---|---|
| `TransactionalLayerCreatorKey` | **15.44 GB/replay (3.19%)**, 1712 samples | **0.00 GB** |

That is **28.5 MB per transaction**, ≈890 000 key allocations/tx at 32 B — roughly **2x the ~460 000
this proposal projected**. The projection came from the merge-cascade census, which counted copies at
*commit*; the key was allocated on every registry **visit** — every read and write of every
transactional structure. So the census systematically under-counted the target.

**A/B of totals (`gc.alloc.rate.norm`, 3 forks/side) — underpowered, do not quote alone:**

| | baseline | refactor | delta |
|---|---|---|---|
| allocation | 495.9 ± 5.0 GB/op | 484.6 ± 10.9 GB/op | −11.3 GB (−2.28%), t=1.63, **95% CI −8.0..+30.6** |
| wall-clock | 217.2 ± 88.2 s/op | 217.6 ± 36.0 s/op | indistinguishable |

**Why the two disagree, and why it is not a contradiction.** 15.4 GB sits inside the A/B's confidence
interval, so the methods agree — but differencing two ~490 GB totals cannot resolve a 3% term when
unrelated sites vary more than that between runs. In the single JFR pair the total moved only −2.6 GB
even though −15.44 GB provably left, because untouched sites drifted the other way: `String` +3.14,
`byte[]` +2.02, `CumulativeWeightBPlusTree$Cursor` +1.65 GB, plus +6.6 GB spread across many sub-1 GB
classes. None of these is on any path this refactor touches.

**Honest bottom line.** A 3.2% allocation term was removed and nothing replaced it (`long[]` rose
+1.13 GB, part of which is HPPC's open-addressed key array — still ~13x smaller than what it
replaced). **No throughput change is claimed or observed**: round 2 already established this workload
is not GC-bound, so the refactor should never be sold as a speed-up on this path. Its value is the
correctness property — the one-id-one-creator assertion, which caught a real `clone()` layer-sharing
bug on its first run — plus a simpler design and less GC pressure.

### 0.1 ⚠️ What must NOT be committed

`TransactionalLayerCopyCensus` and its two hooks in `TransactionalLayerMaintainer` have been
**removed** — it had answered its question, it is explicitly not this refactor's acceptance metric,
and after the split its hook site (`copyWithOwnLayer`) sees only layer-owning producers, so its
numbers were no longer comparable to the baseline. It is parked at
`TransactionalLayerCopyCensus.java.parked` with restore instructions in
`TRUNK_MERGE_CASCADE_INVERSION_ASSIGNMENT.md` §8.

Still measurement-only and never to be committed: the senesi JMH harness, `spike/*`, and every
`*_ASSIGNMENT.md` / `*_RESULTS.md` / `*.parked` under `evita_test/evita_performance_tests/`.

### 0.2 Deviations from the plan as written below

- **The §4.4 assertion found a real pre-existing bug on its first run** — see §0.3.
- **§4.1.2 is wrong about the 17 `query/algebra` sites** — corrected in §4.1.2-pre; they are IDENTITY
  sites and were deliberately left untouched.
- `Transaction`'s isolated-tx constructor and the `AssertionUtils` helpers lost their now-unused
  `X` (diff-type) type parameter rather than keeping it unused — agreed with Johnny.
- `TransactionalContainerChanges` and `PriceListAndCurrencyPriceIndex` retain a now-unused
  `DIFF_PIECE` type parameter; **removing those is an open cleanup**, not yet done.
- `TransactionalObject` no longer extends `TransactionalLayerCreator`; `TransactionalComplexObjArray`
  keeps accepting non-producer elements (guarded via a `removeLayerIfProducer` helper) because
  `shouldCommitArrayWithNonProducerElementsWithoutClassCastException` pins that behaviour.
- `TransactionalMemory.suppressTransactionalMemoryLayerForWithResult` now accepts a layer-less
  producer and only suppresses the creators it maintains.

## 1. Why

`TransactionalLayerMaintainer` keys its diff-layer registry with a `TransactionalLayerCreatorKey`
record allocated on **every** lookup — ~460 000 times per transaction — making the
`TransactionalLayerCreatorKey` + `HashMap` cluster **~25 % of the trunk-incorporation thread's wall
time**. Every producer that actually populates that map draws its id from one JVM-wide counter, so
the composite `(class, id)` key can become a primitive `long`, removing both the allocation and the
composite `equals` from the hottest path in commit.

But making that safe exposes a real defect, which is what the bulk of this proposal is about.

## 2. The defect: copy-production is welded to layer-ownership

`TransactionalLayerCreator.getId()` states a hard contract:

> *"Each instance of the class must return unique id that doesn't change in time."*

`VoidTransactionMemoryProducer` breaks it, deliberately and in writing:

```java
public interface VoidTransactionMemoryProducer<S> extends TransactionalLayerProducer<Void, S> {
    @Override default long getId() { return 1L; }
    @Override default Void createLayer() {
        throw new UnsupportedOperationException("This object doesn't handle changes directly!");
    }
}
```

Every instance of every such class reports `1L`; some ByteBuddy index proxies report `0L`.

It has no choice. Its own JavaDoc explains these objects *"maintain transactionally modifiable
internal data fields but cannot be modified by themselves"* — they must supply
`createCopyWithMergedTransactionalMemory` so they can rebuild from new children, but they own no diff
layer. Because `TransactionalLayerProducer extends TransactionalLayerCreator`, the only way to
express *"I produce a copy but own no layer"* is to **satisfy the layer-owner contract with lies**: a
constant id and a factory that throws.

**Two orthogonal capabilities are welded into one hierarchy:**

| capability | methods | who needs it |
|---|---|---|
| **layer ownership** | `getId()`, `createLayer()`, `removeLayer()` | *some* participants |
| **copy production** | `createCopyWithMergedTransactionalMemory()` | *every* participant |

The second currently requires the first. The composite key has been **masking** the consequence:
`(class, 1L)` keeps the fake ids apart, so nothing has ever failed.

## 3. ⚠️ Correction: the sequence does **not** start at 1, and 0L is **not** currently reserved

This proposal originally assumed the class discriminator was doing no work. A review challenged that,
and checking the source changed the plan — recording it here so the assumption is not re-made.

The concrete failure mode a bare `long` key would expose:

> The cascade calls `getStateCopyWithCommittedChanges(rangePoint)` for every child. Today that is a
> lookup of `(TransactionalRangePoint.class, 1L)` — a guaranteed miss because the class differs.
> Under a bare `long` key it becomes `get(1L)`, and if any real creator held id `1L` the lookup would
> **hit that entry and hand its diff layer to the wrong object.**

So the class component *is* load-bearing on the lookup path. What the sequence actually does
(`TransactionalObjectVersion.java:57`, unchanged since the file was introduced in `383a36d273`):

```java
private final AtomicLong version = new AtomicLong(Long.MIN_VALUE);
...
public long nextId() {
    final long id = this.version.incrementAndGet();
    if (!this.positiveDomain && id >= 0) { this.positiveDomain = true; }
```

- **The first id is `Long.MIN_VALUE + 1`**, not `1L`.
- **`0L` is not guaranteed unassigned.** The `positiveDomain` flag *explicitly anticipates* the
  counter crossing zero, so `0L` is a permitted value — just 9.223 × 10¹⁸ increments away
  (~29 000 years at 10 M ids/s), with the overflow guard throwing before any wrap.

**Net effect: the bare `long` key would be safe today by accident, not by design** — safe only
because the sentinels sit 2⁶³ increments from the sequence's start. Had the counter started at zero
(the common idiom), this change would have been broken on day one.

**Therefore §4.3 makes the reservation real rather than emergent.** That is the correct version of
Johnny's instinct: `0L` *should* be a guaranteed-never-assigned sentinel — it simply is not one yet.

## 4. The proposal

### 4.1 Split the interfaces

```java
/** Can produce its committed form. Every participant in the cascade implements this. */
public interface TransactionalStateProducer<COPY> {
    @Nonnull COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer maintainer);
}

/** Owns a diff layer; MUST have a globally unique id. */
public interface TransactionalLayerCreator<DIFF> {
    long getId();
    DIFF createLayer();
    void removeLayer(@Nonnull TransactionalLayerMaintainer maintainer);
}

/** The common case: owns a layer AND produces a copy. */
public interface TransactionalLayerProducer<DIFF, COPY>
    extends TransactionalLayerCreator<DIFF>, TransactionalStateProducer<COPY> {

    @Nonnull COPY createCopyWithMergedTransactionalMemory(
        @Nullable DIFF layer, @Nonnull TransactionalLayerMaintainer maintainer);
}
```

`VoidTransactionMemoryProducer` then implements **only** `TransactionalStateProducer` — no fake id,
no throwing factory. A type with no id **cannot be looked up in an id-keyed registry**, so the §3
collision becomes *structurally impossible* rather than reserved-around.

**Call sites need not change shape.** The cascade can keep calling one uniform method if
`TransactionalLayerProducer` carries a default that resolves its own layer and delegates:

```java
default COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer maintainer) {
    return createCopyWithMergedTransactionalMemory(maintainer.getLayerFor(this), maintainer);
}
```

Then `TransactionalMap`'s merge loop and every other caller keep invoking the single-argument form,
and the *producer* decides whether a registry lookup is needed at all.

**Bonus:** layer-less producers stop performing a map lookup entirely — today every one of them pays
a key allocation plus a hash lookup that is structurally guaranteed to miss.

#### 4.1.1 Resolved: polymorphic dispatch, with layer handling still centralised

**Decision: the producer decides by its type; the maintainer still owns layer resolution and
`discard()`.** (This supersedes an earlier recommendation of a central `instanceof`, retained below
as the rejected alternative.)

```java
interface TransactionalStateProducer<COPY> {                  // Void family implements this directly
    @Nonnull COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer maintainer);
}

interface TransactionalLayerProducer<DIFF, COPY>
        extends TransactionalLayerCreator<DIFF>, TransactionalStateProducer<COPY> {

    @Override
    default COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer maintainer) {
        return maintainer.copyWithOwnLayer(this);              // lookup + 2-arg call + discard
    }

    @Nonnull COPY createCopyWithMergedTransactionalMemory(
        @Nullable DIFF layer, @Nonnull TransactionalLayerMaintainer maintainer);
}
```

The maintainer's entry point becomes pure dispatch, and `copyWithOwnLayer` stays the **single** place
that resolves a layer and discards it:

```java
public <S> S getStateCopyWithCommittedChanges(@Nonnull TransactionalStateProducer<S> producer) {
    return producer.createCopyWithMergedTransactionalMemory(this);
}

<DIFF, COPY> COPY copyWithOwnLayer(@Nonnull TransactionalLayerProducer<DIFF, COPY> producer) {
    final TransactionalLayerEntry<DIFF> entry = this.transactionalLayer.get(producer.getId());
    final COPY copy = producer.createCopyWithMergedTransactionalMemory(
        entry == null ? null : entry.item(), this);
    if (!this.avoidDiscardingState.get() && entry != null) {
        entry.discard();
    }
    return copy;
}
```

Why this over the `instanceof` form:

- A new implementor writes only the two-argument method and **cannot forget `discard()`** — they
  never touch it.
- No `instanceof` on the hottest path in commit; the layer-less producers skip the registry entirely.
- Layer-less producers still recurse into layer-owning children normally — the object's own copy
  method runs in both shapes, so nothing about children is lost (see the note below).

**Residual risk, and why it is acceptable:** Java cannot seal a `default`, so an implementor could
override the one-argument method on a layer owner and bypass layer resolution. **That failure is
loud** — the layer is never discarded, stays `ALIVE`, and `verifyLayerWasFullySwept()` throws
`StaleTransactionMemoryException` at commit. Contrast with §4.1.2, whose failure mode is silent.

**Note on a discriminator that turns out not to discriminate:** some `VoidTransactionMemoryProducer`s
(e.g. `TransactionalRangePoint`, whose `dirty`/`starts`/`ends` children are layer owners) own no
layer while their children do. This does *not* decide between the two shapes: in both, the maintainer
only classifies **the object in front of it** — a static type fact — and the object's own copy method
runs regardless, recursing into children through the maintainer as usual. The choice rests on the
`discard()` ownership argument above, not on child structure.

#### 4.1.1-alt Rejected alternative: central `instanceof` dispatch

`getStateCopyWithCommittedChanges` does three things today — resolve the layer, produce the copy,
then `discard()` the layer. The discard is load-bearing: `verifyLayerWasFullySwept` throws
`StaleTransactionMemoryException` if any layer is left un-discarded, which is what prevents silently
losing committed data.

**Do it with a single `instanceof` in the maintainer**, not with a default method on the producer:

```java
public <S> S getStateCopyWithCommittedChanges(@Nonnull TransactionalStateProducer<S> producer) {
    if (producer instanceof TransactionalLayerProducer<?, ?> layerOwner) {
        // resolve layer by id -> createCopyWithMergedTransactionalMemory(layer, this) -> discard
    } else {
        return producer.createCopyWithMergedTransactionalMemory(this);   // no lookup, no discard
    }
}
```

This shape also works and is impossible to bypass, since no implementor can opt out of the
maintainer's classification. It was rejected because it puts an `instanceof` on the hottest path in
commit and reads less naturally than letting the type decide.

**Common to both shapes:** no call site changes are needed — every caller already goes *through the
maintainer* (`transactionalLayer.getStateCopyWithCommittedChanges(x)`), so widening that parameter to
the new supertype keeps existing calls compiling.

#### 4.1.2-pre ✅ VERIFIED classification of the 56 references — §4.1.2 below was partly wrong

§4.1.2 asserted *"almost all of those `instanceof` checks mean 'can this produce a copy?' and gate
whether a child is merged"*. **That is false for 17 of them.** Verified by reading every site:

| group | count | classification | action |
|---|---|---|---|
| `core/query/algebra/base/*` — `AbstractBitmapCacheableFormula` (3), `ConstantFormula` (1), `DisentangleFormula` (6), `JoinFormula` (3), `NotFormula` (4) | 17 | **IDENTITY** — the match exists only to call `getId()` for transactional-id gathering and cache hashes | **leave untouched** |
| `TransactionalMap`, `MapChanges`, `ProducerMapChanges`, `PersistentTransactionalMap`, `PersistentTransactionalProducerMap`, `TransactionalList`, `TransactionalSet`, `SetChanges`, `ComplexObjArrayChanges`, `PriceIndexComponent` | 26 | **MERGE** — gates whether a child is merged | **widen to `TransactionalStateProducer`** |
| `TransactionalLayerProducer.class` in `TransactionalComplexObjArray`, `TransactionalObjectBPlusTree`, `TransactionalBucketBPlusTree`, `TransactionalLongBPlusTree`, `TransactionalMap`, `MapChanges`, `PersistentTransactionalProducerMap` | 13 | **MERGE** (`isAssignableFrom` value-type gates) | **widen to `TransactionalStateProducer.class`** |

**Why the 17 IDENTITY sites are safe to leave alone — this is the load-bearing fact.** Every one of
them tests an expression whose declared static type is `io.evitadb.index.bitmap.Bitmap`
(`DisentangleFormula:89,93`; `ConstantFormula:54`; `AbstractBitmapCacheableFormula:56`). The complete
set of `Bitmap` implementations is `ArrayBitmap`, `BaseBitmap`, `EmptyBitmap`, `SingleRecordBitmap`
and `TransactionalBitmap` — and **the only one that is a producer at all is `TransactionalBitmap`,
which is a genuine layer owner** (`TransactionalBitmap.java:57-62`, `TransactionalLayerProducer<BitmapChanges, Bitmap>`
with `@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId()`).

**No `Bitmap` implementation is a `VoidTransactionMemoryProducer`**, so these sites can never observe
one, and the split cannot change their behaviour. This matters because they are exactly the sites that
would have failed *silently*: `DisentangleFormula:196-202` has an `else` branch that hashes array
contents instead, which would make a formula stop being invalidated when its bitmap changes — stale
cached query results, invisible to `verifyLayerWasFullySwept`.

**Consequence for the design: `getId()` stays owner-only on `TransactionalLayerCreator`.** The Void
family loses it entirely, exactly as §4.1 intends. The alternative considered — hoisting `getId()`
onto the shared supertype because "identity is universal" — is unnecessary.

Two supporting facts, also verified:

- **The MERGE sites fail LOUDLY, not silently.** Most of the `.class` references are runtime
  `Assert.isTrue(TransactionalLayerProducer.class.isAssignableFrom(valueType), …)` guards
  (`TransactionalMap:109`, `MapChanges:131`, `PersistentTransactionalProducerMap:114`), and Void
  producers *are* used as map value types (e.g. `UniqueIndex`). If one is missed, the assert throws
  on construction.
- **The fake `1L` is never read.** No caller anywhere invokes `getId()` on a Void producer; its only
  consumer was the registry key, which the split removes. Note also that most Void implementors are
  *not* on the fake id at all — `AbstractReducedEntityIndex` inherits a real one from
  `EntityIndex:114`, and `RangeIndex:143`, `HistogramIndex:82`,
  `AbstractPriceListAndCurrencyPriceIndex:79` declare their own (used for their own formula
  construction, e.g. `RangeIndex:603`). Those `id` fields and their generated getters must be
  **kept** — they simply stop overriding an interface method.

#### 4.1.2 ⚠️ The real hazard: the split fails **silently**, and the safety net does not cover it

There are **43 `instanceof TransactionalLayerProducer` sites**, 13 `TransactionalLayerProducer.class`
references and 11 `VoidTransactionMemoryProducer` implementors. Almost all of those `instanceof`
checks mean *"can this produce a copy?"* and gate whether a child is merged at all, e.g.
`TransactionalMap`:

```java
if (value instanceof TransactionalLayerProducer<?,?> transactionalLayerProducer) {
    value = ...getStateCopyWithCommittedChanges(transactionalLayerProducer);
}
```

The moment `VoidTransactionMemoryProducer` stops extending `TransactionalLayerProducer`, that check
**still compiles and simply returns `false`** for every layer-less producer — so those children are
never merged and the committed state silently keeps stale sub-objects.

**`verifyLayerWasFullySwept` does NOT catch this.** It detects un-consumed *layers*; a layer-less
producer has no layer, so nothing is left unswept and the check passes. Earlier in this document I
described that verifier as the safety net for step 2 — **it is not, for this failure mode.**

**Mitigation — make the compiler the safety net.** Rename the copy-production role rather than
silently re-pointing the hierarchy:

- the *new* layer-free supertype takes the name `TransactionalStateProducer`;
- every existing `instanceof TransactionalLayerProducer` / `.class` reference then **fails to
  compile** until it is consciously widened or narrowed.

This converts ~56 potential silent-corruption sites into ~56 compile errors that must each be
reviewed. It is the difference between a refactor that can go quietly wrong and one that cannot.

### 4.2 Rename the wrapper to what it becomes

`TransactionalLayerWrapper<T>` → **`TransactionalLayerEntry<T>`**, holding `(creator, item, state)`.

Once the key is a bare `long`, something must retain the creator, because
`verifyLayerWasFullySwept` → `StaleTransactionMemoryException` needs the **live object** (it calls
`getId()`, `getClass().getSimpleName()`, `toString()`). The registry entry is the only object whose
lifetime matches the layer's.

The current name is accurate for what the class does *today* — *"envelopes the object that
TransactionalObject uses to track the changes"* — and would become a lie the moment a creator
reference is added. `TransactionalLayerEntry` states the real concept: **one registered diff layer —
who owns it, the diff, its lifecycle state.** It is allocated once per *layer creation* (~4 600/tx),
not per lookup, so the added field does not reintroduce the allocation being removed.

*Rejected alternative:* capturing the diagnostic strings eagerly instead of the reference. That would
run `toString()` on every layer creation — the exact mistake fixed in `6b2bb635b` (eager span-name
construction on a hot path). Hold the reference; stringify only on the error path.

### 4.3 Redesign the sequence: `1L → MAX → MIN → -1L`, with `0L` reserved for "none"

Agreed design. The counter starts at `0L` so the first handed-out id is `1L`, runs up through
`Long.MAX_VALUE`, wraps naturally (two's complement) to `Long.MIN_VALUE`, and continues to `-1L`.
Returning to `0L` means the entire `2⁶⁴ − 1` non-zero space has been exhausted.

```java
private final AtomicLong version = new AtomicLong(0L);

public long nextId() {
    final long id = this.version.incrementAndGet();
    if (id == 0L) {
        // the whole 2^64-1 id space has been handed out; poison the counter so every subsequent
        // call lands on 0 again and fails identically rather than silently reusing id 1
        this.version.set(-1L);
        log.error("Transactional object version sequence exhausted! ...");
        throw new IdentifierOverflowException("Transactional object version sequence exhausted!");
    }
    return id;
}
```

Why this is better than what is there now:

- **`0L` becomes reserved by construction**, so "no layer" is representable and can never be confused
  with a real creator — the invariant §3 shows is currently only an accident.
- **The `volatile boolean positiveDomain` flag disappears.** Today every `nextId()` performs a
  volatile read on a JVM-wide singleton hit by every transaction thread; the new form needs only a
  compare against zero. **Simpler *and* cheaper.**
- **Larger usable space**: `2⁶⁴ − 1` ids versus today's effective `2⁶³` before the overflow guard
  fires.
- **Ids become human-readable.** Today the first id is `-9223372036854775807`; it becomes `1`. That
  is a real quality-of-life gain in logs, diagnostics and `StaleTransactionMemoryException` messages.
- **Poisoning by `set(-1L)`** makes exhaustion permanent without any flag or extra read — the next
  `incrementAndGet()` returns `0L` and throws again.

Document the reservation on both `TransactionalObjectVersion` and `TransactionalLayerCreator.getId()`:
`0L` denotes "no layer" and is never emitted by the sequence. Use `0L` (not `1L`) wherever a
layer-less producer must still expose an id during the transition — the ByteBuddy proxies already
return `0L`, so this aligns them with the rule instead of leaving a second special case.

After 4.1 this is defence-in-depth rather than load-bearing — layer-less producers have no id at all
in the end state — but it protects the transition and anyone who later reintroduces an
id-bearing-but-layer-less type.

### 4.4 Assert the invariant — always on, and free

The invariant that actually protects data is not "ids look unique" but:

> **A given id must map to one and only one live creator instance.**

```java
final TransactionalLayerEntry<?> entry = this.transactionalLayer.get(id);
if (entry != null && entry.creator() != creator) {
    throw new GenericEvitaInternalError(
        "Transactional layer id " + id + " is claimed by two distinct creators: " +
        entry.creator().getClass().getName() + " and " + creator.getClass().getName() + "."
    );
}
```

- **Reference identity (`!=`), not `equals`** — two distinct live objects are distinct owners.
- **Throws**, per the project's defensive-design rule: this is a programming error and must surface.
- **Costs nothing.** The check runs only on a **hit**; a miss returns `null` first. Hits are the ~1 %
  of visits that own a layer, so this is ~4 600 comparisons per transaction — the same order as
  insertion-only placement — while covering every caller. There is no coverage/throughput trade-off.
- **Strictly stronger than the composite key it replaces**: a collision becomes loud instead of
  silent, and it also catches id reuse *within* a single class, which the composite key never could.

### 4.5 Use HPPC `LongObjectHashMap`, not `HashMap<Long, …>`

**`HashMap<Long, …>` would be a non-fix.** Ids are drawn from a JVM-wide counter and therefore sit far
outside `Long.valueOf`'s −128..127 cache, so every lookup would box the key — trading one allocation
per visit for one allocation per visit. The primitive-keyed map is what makes §4.4 pay off at all.

`com.carrotsearch:hppc:0.10.0` is **already a dependency** of `evita_engine` and already declared in
its `module-info.java`; `IntObjectHashMap` is used 18× in the module and
`LongObjectHashMap`/`LongObjectCursor` are already used in `core/buffer/DataStoreChanges.java`. No new
library, no JPMS change, established idiom.

Two secondary benefits:

- **No per-entry `Node`.** HPPC is open-addressed, so the ~4 600 live layers per transaction stop
  allocating `HashMap.Node` objects as well (~150 kB/tx) — on top of the ~460 000 key allocations.
- **Key `0` alignment.** `LongObjectHashMap` cannot store key `0` in the open-addressed table (it is
  the empty-slot marker) and diverts it to a side slot behind a `hasEmptyKey` flag. Because §4.3
  reserves `0L` as never-emitted, that branch is provably never taken here.

**Applicability verified.** The registry is a plain `HashMap` (`TransactionalLayerMaintainer.java:108`)
that is thread-confined per transaction, so HPPC's lack of concurrency support costs nothing. HPPC's
one sharp edge — iterators do not tolerate structural modification — does not apply either: of the
three iteration sites, `verifyLayerWasFullySwept` (`:347`) is read-only, and `commitSavepoint`
(`:444`) / `rollbackSavepoint` (`:477`) iterate `mementos` while mutating `transactionalLayer`, a
*different* map. Nothing iterates a map it structurally modifies.

**Cost.** The field type becomes `LongObjectHashMap<TransactionalLayerEntry<?>>` (HPPC does not
implement `java.util.Map`) and the three `entrySet()` loops become cursor loops, allocating one cursor
per loop rather than per entry. `Savepoint.mementos` converts identically to `LongObjectHashMap<Object>`
— §7.2 already established it has no ordering dependency, so the different hash distribution is
invisible. All of this lands inside step 4 and moves no risk into steps 1–3.

## 5. Sequencing

The interface split is the correctness fix; the `long` key is the performance fix. **They should not
be one commit.**

| step | change | risk |
|---|---|---|
| 1 | §4.3 reserve `0L` + document | very low |
| 2 | §4.1 interface split, `VoidTransactionMemoryProducer` stops faking id/`createLayer` | **high** — touches every layer-less implementor and the central dispatch |
| 3 | §4.2 rename wrapper → entry, add creator | low |
| 4 | HPPC `long`-keyed registry (§4.5) + §4.4 assertion | low, once 1–3 land |

Step 2 is the one to be careful with: this is the seam behind the stale-twin bugs, the warm-up flush
bug and the dirty-scope NPE, and a *correct* change here has already been reverted once for
introducing an invariant nothing enforced (`SENESI_WAL_REPLAY_BOTTLENECK_ASSIGNMENT.md` §6.2). Land
it on its own, with `verifyLayerWasFullySwept` active throughout — it fails loudly if a layer is
missed, which is exactly the safety net this refactor needs.

## 6. Expected payoff — stated honestly

- Removes a per-visit allocation and composite `equals` from a path executed ~460 000 times per
  transaction; the affected bookkeeping is ~25 % of the trunk-incorporation thread.
- Removes the registry lookup entirely for layer-less producers.
- **This is a latency and multi-writer-scaling win, not single-writer throughput.** Replay throughput
  improved 0.6 → 1.5 tx/s from *apply-side* fixes with the trunk stage untouched; the writer blocks
  on WAL append (~30 ms), decoupled from trunk incorporation by a deep queue. The benefit shows up in
  visibility lag (currently 20–45 s and unbounded under sustained write load).
- **Acceptance metric:** `gc.alloc.rate.norm` over JMH `-f 3` plus a trunk-thread wall profile, via
  the senesi harness. Never a single-shot wall-clock number — an earlier round removed provably dead
  work that the profiler attributed 18.9 % to and moved the real metric by **−0.2 %**.

## 7. Still open

### 7.1 ⚠️ There is a **third** capability: `removeLayer` propagation

The split as drafted assumes two capabilities. There are three. `removeLayer` on a *layer-less*
producer does two jobs — `TransactionalRangePoint.java:191`:

```java
public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
    transactionalLayer.removeTransactionalMemoryLayerIfExists(this);   // (1) own layer
    this.dirty.removeLayer(transactionalLayer);                        // (2) propagate to children
    this.starts.removeLayer(transactionalLayer);
    this.ends.removeLayer(transactionalLayer);
}
```

Job (2) — **propagating removal through the object graph** — is needed by *every* participant, not
only layer owners. So:

| capability | who needs it |
|---|---|
| copy production (`createCopyWithMergedTransactionalMemory`) | everyone |
| **removal propagation (`removeLayer`)** | **everyone** |
| layer ownership (`getId`, `createLayer`) | owners only |

`removeLayer` currently lives on `TransactionalLayerCreator`, which is the wrong home once the split
happens — it must move to `TransactionalStateProducer`. Owners then additionally remove their own
layer, exactly mirroring the `copyWithOwnLayer` arrangement in §4.1.1.

**Silver lining:** for layer-less producers, job (1) is a call that is *structurally guaranteed to
miss* — they never have a layer. After the split it becomes a **compile error** (they are no longer
`TransactionalLayerCreator`), so the compiler locates every one and deleting them is strictly
correct. Another instance of §4.1.2's "make the compiler the safety net".

**RESOLVED — keep own-layer removal explicit in the owner's `removeLayer`.** Moving the method to
the right interface is the whole fix; the maintainer should *not* take it over. Unlike the copy path,
`removeLayer`'s body is inherently object-specific (it names which children to propagate to), so it
cannot be expressed as a `default` the way `copyWithOwnLayer` can — there is no seam for the
maintainer to own. Owners therefore keep their existing
`transactionalLayer.removeTransactionalMemoryLayerIfExists(this)` line unchanged, and the only edit
is **deleting** that line from layer-less producers, where the compiler points at every occurrence.
Net diff for this capability: one method moved between interfaces, ~11 deletions.

### 7.2 RESOLVED — the savepoint map converts mechanically

Researched. `Savepoint.mementos` can become `long`-keyed with **no structural change**:

- **The creator object is never read from the key in the savepoint path.**
  `key.transactionalLayerCreator()` is called in exactly one place in the whole file —
  `verifyLayerWasFullySwept` (`:350`) — which is a commit-time check, not a savepoint method.
- **Rollback never calls `createLayer()`.** All three memento kinds (`CREATED_IN_SAVEPOINT`,
  `RemovedLayer`, plain snapshot) operate on an **already-captured** wrapper/memento carried as the
  map *value*; the key is used only for `get`/`put`/`remove` against `mementos` and the main
  registry. A bare `long` suffices for every branch (`:479-503`).
- **Savepoint keys are always registry-resident.** Every recording site fires either while the key is
  already in `transactionalLayer` or one line after it is inserted (`:219`, `:234-236`, `:183/:201`),
  so `mementos` lives in exactly the same id-uniqueness domain as the main registry — whatever
  justifies converting one justifies converting the other.
- **No ordering dependency.** Both `commitSavepoint` (`:444`) and `rollbackSavepoint` (`:477`)
  iterate `entrySet()` treating each entry independently, consistent with `Snapshotable`'s
  nested-layer-boundary invariant. A different hash distribution changes nothing.

This adds no new constraint — it inherits the one already known from §4.2 (the creator must be
retained on the entry for `StaleTransactionMemoryException`).

### 7.3 RESOLVED — naming

**`TransactionalLayerEntry`** (replacing `TransactionalLayerWrapper`) and
**`TransactionalStateProducer`** (the new layer-free supertype). Both verified to have **zero**
existing references in the codebase, so neither collides and `TransactionalStateProducer` is
genuinely new — which is what makes the compiler surface all 43 `instanceof` sites per §4.1.2.

*(Former open question — central `instanceof` vs. producer-side dispatch — is resolved in §4.1.1:
producer-side dispatch with layer handling centralised via `copyWithOwnLayer`.)*
