# Issue #760 Part B — Phase 1a Detailed Implementation Plan (Step 0 + Steps 1–3)

Detailed, line-accurate elaboration of **Step 0 (scaffolding)** and **Phase 1a = Steps 1–3
(serializer-only slimming)** from `2026-06-22-issue-760-partB-implementation-steps.md`.
Phase 1a was confirmed by Johnny as the homogeneous, lowest-risk group: pure on-disk format
shrinking via versioned serializers, no new storage parts, no dirty-flag changes, no loader
recursion. We execute **one step at a time**, build + tests green before the next, **no commit
without Johnny's permission**.

> This plan was written after a four-agent source sweep (machinery + the three steps). The
> "Research corrections" below are facts pulled from the actual code that **change the rough
> plan** — read them first.

---

## Review hardening (applied after adversarial review, verdict GO-WITH-FIXES)

An adversarial sub-agent verified every claim against source. All headline correctness claims
CONFIRMED. Applied fixes:
- **DEFER SortIndex from Step 3.** Its `sortedRecords` is block-sorted (global delta unsafe) AND the
  serializer carries a `valuesPresent` marker + trailing `indexedDecimalPlaces` scaled-int field;
  per-element zig-zag risks size regression for ids ≥ 2^28/negative. Correct SortIndex slimming is
  delta-WITHIN-block (block lengths from `valueCardinalities`) — a dedicated later step. Step 3 now =
  Facet + Hierarchy + PriceRef (all provably globally ascending).
- **DEFER Step 2B (GlobalUnique varint) to Phase 1b** GlobalUnique shard (avoids a second UID bump on
  the same part; moots the `NO_LOCALE = -1` plain-positive-varint bug the review caught). Step 2 now =
  2A only (Unique bitmap drop + zig-zag record ids).
- **Codec empty/single spec tightened** (Step 0.1): `first`+gaps written only when `count > 0`;
  `readAscendingInts` returns `new int[0]` (never null) for count 0 (Hierarchy arrays routinely empty).
- **PriceRef provenance corrected** (Step 3): `priceIds` is NOT a RoaringBitmap array — built from a
  `TransactionalObjArray<PriceRecordContract>` sorted by `internalPriceId` then deduped; strictly
  ascending but fragile ⇒ route writes through the asserting codec (fail loud on future breakage).
- **Preserved old readers keep a WORKING `write()`** (not `throw`) so lazy-upgrade tests generate
  old-format bytes and exercise the real old→new path; production never calls them for writing (the
  dispatcher delegates `write` only to the current serializer).
- **Exact bwc literals** (verify on edit): Chain `_2025_5 = -2563092938071912295L`; Unique
  `_2025_5 = -4095785894036417656L`, `_2026_1 = -3921198859032670410L`. Facet/Hierarchy/PriceRef have
  NO existing dated reader (first one added).
- **Added tests:** Chain flush+reload round-trip over an inconsistent multi-run chain AND a circular
  chain; `Migration_2025_6` Unique re-persist round-trip under the slim format.
- **var-encoding note:** `writeInt(_,true)`⇄`writeVarInt`, `writeInts(_,false)`=zig-zag rely on Kryo's
  default `varEncoding=true`, never disabled in `ObservableOutput/Input` — do not flip it.

---

## Research corrections to the rough plan (read first)

1. **Chain runs are NOT sorted.** `ChainIndex.elements` is materialized in *insertion / relocation*
   order, not ascending PK order (proof: `ChainIndex.java` field javadoc "materialized
   (semi-consistent) order"; `getConsistencyReport()` enforces *predecessor*, not *ordering*).
   ⇒ **No delta encoding on chain runs.** Step 1's win is 100% structural (drop the per-element
   redundancy), not gap-compression.
2. **Step 1 can be serializer-only.** The in-code TODO (`ChainIndex.java:455–466`) says rebuild the
   *same* `ChainElementState` map on read so `ChainIndexStoragePart` + the load constructor +
   `AttributeIndexLoader.fetchChain` need **no change**. Confirmed against all three. We only touch
   the serializer + bump the part UID.
3. **GlobalUnique already persists no bitmap.** `GlobalUniqueIndexStoragePart` has no record-id
   field; `GlobalUniqueIndex` rebuilds `entitiesPerType` from the value→tuple map on load. ⇒ the
   "drop redundant bitmap" premise is **already satisfied** for GlobalUnique. Its only remaining win
   is varint-ing `entityPrimaryKey` + `locale` (currently fixed 4-byte ints). Minor; optional.
4. **Unique bitmap IS persisted and IS used on load**, but it equals `set(map.values())` exactly
   (proof: `OwnerUniqueIndex(map)` ctor rebuilds it as
   `new TransactionalBitmap(uniqueValueToRecordId.values()…)`). ⇒ we can drop it from the wire and
   **reconstruct it inside the serializer's `read()`**, keeping `UniqueIndexStoragePart` + the loader
   untouched (serializer-only).
5. **SortIndex `sortedRecords` is piecewise-sorted, NOT globally monotone.** Javadoc:
   "blocked per value … record ids within the same block are sorted naturally". Across block
   boundaries the value can drop. ⇒ **global delta encoding would be incorrect.** Use per-element
   zigzag varint (safe for any order/sign) for SortIndex; reserve delta encoding for the three
   provably-ascending arrays.
6. **No "writeInt(len, true)" bug in PriceRef.** Kryo's `writeInt(v, true)` / `readInt(true)` is the
   variable-length form, paired correctly. `writeVarInt`/`writeInt(_,true)` and
   `readVarInt`/`readInt(true)` are interchangeable spellings. Do not "fix" it.

---

## Verified machinery (the recipe these steps rely on)

`SerialVersionBasedSerializer<T>`
(`evita_store/evita_store_entity/.../store/entity/serializer/SerialVersionBasedSerializer.java`):

- Constructor `(Serializer<T> current, Class<T> targetClass)` captures
  `currentSerializerUID = ObjectStreamClass.lookup(targetClass).getSerialVersionUID()`.
- `write()` emits an **8-byte `output.writeLong(currentSerializerUID)` prefix**, then delegates to
  the current serializer.
- `read()` reads the long; if it equals the current UID → current serializer; else looks up
  `addBackwardCompatibleSerializer(oldUid, oldSerializer)` map; if absent →
  `StoredVersionNotSupportedException`.
- `.addBackwardCompatibleSerializer(long serialVersionUID, Serializer<T>)` is chainable.

**Therefore the format-version key is the StoragePart class's `@Serial serialVersionUID`.** To ship a
new on-disk format for a part:

1. Note the part's **current** `@Serial serialVersionUID` literal `L_old` (this is the format key of
   everything already on disk at HEAD).
2. Copy the **current** serializer to `XxxSerializer_<tag>` (read path preserved verbatim; its
   `write(...)` may `throw new UnsupportedOperationException(...)` — we never write the old format
   again; this matches the existing `*_2025_5` deprecated readers).
3. Rewrite the **current** `XxxSerializer` to read/write the new (slim) format.
4. **Bump** the part's `@Serial serialVersionUID` to a fresh literal `L_new` (any new long; even when
   the class *shape* is unchanged — here the bump is purely a format-version signal).
5. In `IndexStoragePartConfigurer`, register the current serializer and **keep every existing**
   `addBackwardCompatibleSerializer(...)` line, plus add one for `L_old → XxxSerializer_<tag>`.

Precedent already in `IndexStoragePartConfigurer` (lines 82–102): `UniqueIndexStoragePart`,
`FilterIndexStoragePart`, `SortIndexStoragePart` each register **two** bwc readers (`_2025_5`,
`_2026_1`). We extend that chain.

**Serializer I/O type:** `com.esotericsoftware.kryo.io.Output` / `Input` (Kryo native). Available:
`writeVarInt(int,boolean)`, `writeVarLong(long,boolean)`, `writeInts(int[],off,len[,boolean])`,
`readVarInt(boolean)`, `readInts(int[,boolean])`. The trailing `boolean` is `optimizePositive`
(Kryo): `true` = plain varint (5 bytes for negatives), `false` = **zig-zag** (small magnitude, any
sign). **No delta encoder exists** — Step 0 builds it.

**No `STORAGE_PROTOCOL_VERSION` bump** (`PersistenceService.STORAGE_PROTOCOL_VERSION = 6`); that is a
catalog-level migration switch, orthogonal to per-part `serialVersionUID`.

---

## Global conventions (every step)

- **Build with Maven ≥ 3.9.0.** This box's `mvn` is 3.8.7 and cannot build the branch
  (`git-commit-id-maven-plugin:10.0.0`). Use `/tmp/apache-maven-3.9.9/bin/mvn` (re-fetch to /tmp from
  `https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz` if
  gone). Java 17 toolchain configured.
- **Module:** serializers live in `evita_store/evita_store_server`; storage-part classes live in
  `evita_engine`. Touching both ⇒ build `evita_engine` then `evita_store_server` (use `-am` to drag
  reactor deps; stale jars cause Kryo red herrings — cf. prior `-am` lesson in memory).
- **bwc UID literals are load-bearing.** A wrong literal bricks reads. Capture each `L_old` by reading
  the class's current `@Serial` line; capture released literals from the existing
  `IndexStoragePartConfigurer` registration lines verbatim.
- **Per-step gate:** focused unit tests green → `/code-quality-pipeline` on touched files → report →
  next step. **No git commit/push without Johnny's explicit permission.**
- **Per-serializer test trio:** (a) new-format round-trip; (b) lazy-upgrade — decode a legacy-format
  blob (written by the preserved `_<tag>` serializer) → assert in-memory equality → re-encode new →
  re-read; (c) the owning index's behavioural suite still green.

---

## Step 0 — shared scaffolding (small, once)

### 0.1 Sorted-int-array delta-varint codec
New util class (proposed
`evita_store/evita_store_server/.../store/index/serializer/util/SortedIntArrayCodec.java` — confirm
package fits the module; alternatively a `static` pair on an existing serializer-util holder). API:

```java
/** Writes a strictly-ascending int[] as: count (varint) → first (zig-zag varint) → gaps (unsigned varint). */
static void writeAscendingInts(Output out, int[] ascending);
/** Inverse of writeAscendingInts. */
static int[] readAscendingInts(Input in);
```

Encoding rationale:
- First element via zig-zag (`writeVarInt(first, false)`) → safe even if the smallest id is negative.
- Each subsequent element as `writeVarInt(curr - prev, true)` — gaps of a strictly-ascending distinct
  array are ≥ 1, always non-negative, so plain (unsigned) varint is optimal and **sign-robust
  regardless of absolute magnitudes**. (Do **not** subtract 1; keep it simple and obviously correct.)
- Assert-on-write (debug) that the input is non-decreasing, so a future unsorted caller fails loud
  instead of silently corrupting. (Use `Assert.isPremiseValid`; cheap, O(n) on a path that already
  iterates.)
- **Empty/single:** write `count` first; write `first` and the gap loop **only when `count > 0`**.
  `readAscendingInts` returns `new int[0]` (never `null`) for `count == 0`; `count == 1` = count +
  first, no gaps. (Hierarchy `roots`/`childrenIds`/`orphans` are routinely empty; callers expect a
  non-null array.)

This codec is consumed only by the three **globally-sorted** arrays in Step 3. Step 1 (unsorted
runs) and SortIndex (block-sorted) do **not** use it.

### 0.2 Versioned-serializer test helper
A small test-scope helper (in `evita_test/evita_functional_tests/.../store/index/serializer/`) that,
given a `SerialVersionBasedSerializer` wired exactly as production wires it (current + all bwc
readers), round-trips an object through a real Kryo `Output`/`Input` and returns the decoded object —
to cut per-step boilerplate and guarantee tests exercise the **same dispatch** prod uses (UID prefix
included). Mirror the existing `UniqueIndexStoragePartSerializerTest` setup.

### 0.3 Baseline
Confirm clean build on Maven 3.9.x and a green `evita_store_server` + relevant
`evita_functional_tests` index/serializer suites before changing anything (baseline to diff against).

**DoD:** codec + helper compile, codec has its own focused unit test (ascending arrays incl. empty,
single, negative-first, large gaps; round-trip equality), baseline suites green.

---

## Step 1 — ChainIndex slim format (serializer-only; retires `ChainIndex.java:455` TODO)

**Files:** `ChainIndexStoragePartSerializer.java` (rewrite),
`ChainIndexStoragePart.java` (UID bump only), `IndexStoragePartConfigurer.java` (registration).
**Part UID now:** `8894604958733971199L`. **Existing bwc readers:** verify in the configurer (Agent
referenced a `ChainIndexStoragePartSerializer_2025_5`).

### Current wire (per record, after the long UID prefix)
`entityIndexPK:int` · `storagePartPK:varlong` · `attributeIndexKey:varint(id)` ·
`elementStates.size():varint` · **per element** `{ pk:int, inChainOfHead:int, predecessor:int,
state.ordinal():int }` · `chains.length:varint` · **per chain** `{ len:varint, run:int[] raw }`.

Redundancy: `inChainOfHead == run[0]`; non-head `predecessor == run[i-1]`; non-head `state ==
SUCCESSOR`. Only the head carries non-derivable `predecessor` (HEAD_PK = −1 for a true head) +
`state` (HEAD / SUCCESSOR / CIRCULAR).

### New slim wire
`entityIndexPK:int` · `storagePartPK:varlong` · `attributeIndexKey:varint(id)` ·
`chains.length:varint` · **per chain** `{ len:varint, run:int[] (writeInts raw — see note),
headPredecessorPk:int, headState.ordinal():varint }`.

- Drops the entire `elementStates` section (~3 ints + 1 enum per element).
- `run` PKs: keep `writeInts(run,0,len)` **raw** for the safe first cut (runs are unsorted; sign of
  PKs not yet confirmed). *Optional later micro-win:* `writeInts(run,0,len,true)` (varint, positive)
  once PK positivity is confirmed — gated, not in the core step.
- `headState.ordinal()` fits a byte; varint is fine. Read back via `ElementState.values()[ord]`.

### read() reconstruction (returns an identical fat `ChainIndexStoragePart`)
For each chain: read `run`, `headPredecessorPk`, `headState`. Rebuild `elementStates`:
- `run[0]` → `ChainElementState(run[0], headPredecessorPk, headState)`.
- `run[i>0]` → `ChainElementState(run[0], run[i-1], SUCCESSOR)`.

Return `new ChainIndexStoragePart(entityIndexPK, attributeKey, elementStates, chains, storagePartPK)`
— **unchanged constructor**, so `AttributeIndexLoader.fetchChain` and the `ChainIndex(chains,
elementStates)` load constructor are untouched.

### write() derivation (from the existing fat part)
`createStoragePart` still builds the full `elementStates` map (unchanged — transient heap only). The
serializer derives slim form: for each `run` in `part.getChains()`, look up
`part.getElementStates().get(run[0])` to get the head's `predecessor` + `state`; write `run`,
that predecessor, that state ordinal.

### Mechanics
- Copy current serializer → `ChainIndexStoragePartSerializer_2026_2` (read verbatim; `write` throws).
  (Tag = next free after the existing readers — **verify** the existing reader tags for Chain first.)
- Bump `ChainIndexStoragePart` `@Serial` to a fresh `L_new`.
- Configurer: keep existing Chain bwc line(s) + add `8894604958733971199L → _2026_2`.

### Tests (`ChainIndexTest` + a new serializer test)
- Round-trip across: single true-head chain; multi-chain; inconsistent (multiple runs same
  predecessor); circular head; empty index.
- Lazy-upgrade: encode with the preserved old reader's *format* (or fixture bytes) → decode with the
  new dispatcher → assert reconstructed `elementStates`/`chains` equal the original; re-encode new →
  re-read.
- Assert reconstructed `ChainIndex` passes `getConsistencyReport()` and equals the source on lookups.
- `ChainIndexTest` (1717 lines, ~50 tests incl. `DirtyFlagStoragePartTest`) stays green.

**Expected:** ≈5× smaller chain parts (per the TODO). **Risk:** low (serializer-only, reconstruction
is the exact inverse the load constructor already trusts).

---

## Step 2 — drop redundant bitmap (Unique) + zig-zag record ids (Unique only)

One part (Unique), **serializer-only** (reconstruct on read; part shape + loader untouched).
**GlobalUnique (old 2B) is DEFERRED to Phase 1b** — fold into the GlobalUnique Tier-1 shard; avoids a
second UID bump and moots the `NO_LOCALE = -1` varint bug.

### 2A — UniqueIndexStoragePart
**Files:** `UniqueIndexStoragePartSerializer.java`, `UniqueIndexStoragePart.java` (UID bump),
configurer. **Part UID now:** `8200588488685516906L`. **Existing bwc:** `_2025_5`
(`-4095785894036417656L`), `_2026_1` (`-3921198859032670410L`) — **keep both**.

Current `write()` (owner mode, `dataPresent==true`): writes `recordIds` bitmap via
`kryo.writeObject` **then** the `value→id` map with `writeInt(id)` (fixed 4B). View mode
(`dataPresent==false`) already writes neither — unchanged.

New format (owner mode only):
- **Stop writing** the `recordIds` bitmap.
- Write the map with `output.writeVarInt(id, false)` (zig-zag — record ids are PKs; sign not yet
  confirmed, zig-zag is safe; revisit to plain-positive varint if confirmed ≥ 0).
- `read()` (owner mode): read the map, then **reconstruct** the bitmap:
  `new TransactionalBitmap(map.values().stream().mapToInt(Integer::intValue).toArray())`, and return
  the **same** `UniqueIndexStoragePart(…, map, recordIds, pk)`. Loader unchanged.

Correctness: `recordIds == set(map.values())` exactly (proven by the `OwnerUniqueIndex(map)` ctor,
whose javadoc states the bitmap need not be persisted). Bitmap is a set → value iteration order
irrelevant.

Mechanics: copy current → `UniqueIndexStoragePartSerializer_2026_2` (read verbatim; write throws),
bump part UID, configurer keeps `_2025_5` + `_2026_1` and adds `8200588488685516906L → _2026_2`.

### 2B — GlobalUniqueIndexStoragePart — **DEFERRED to Phase 1b**
No bitmap is persisted (already optimal). The only win is varint-ing `entityPrimaryKey` (zig-zag) and
`locale` (zig-zag — note `NO_LOCALE = -1`, so plain-positive varint would be WRONG). Tiny win; folded
into the Phase-1b GlobalUnique Tier-1 shard to avoid two UID bumps on the same part.

### Tests
Extend `UniqueIndexStoragePartSerializerTest` (239 lines, already covers owner + view round-trips):
add new-format round-trip (assert reconstructed bitmap equals the original) + lazy-upgrade from
`_2026_1`/`_2025_5`. `UniqueIndexTest` (600) + `GlobalUniqueIndexTest` (156) stay green.

---

## Step 3 — delta-varint the globally-sorted int arrays (Facet, Hierarchy, PriceRef)

Each serializer: copy current → preserved old reader (read verbatim; **keep a working `write()`** for
lazy-upgrade tests), bump part UID, configurer adds the HEAD literal → preserved reader. None of these
three has an existing dated reader (this adds the first). Tag the preserved reader with the next free
`_YYYY_N` per the existing convention (verify no collision when editing the configurer).

| Serializer | Array(s) | Ordering (verified) | Encoding |
|---|---|---|---|
| `FacetIndexStoragePartSerializer` | `referencingEntityIds` (inside `writeGroup`/`readGroup`, used by BOTH the noGroup block and the per-group loop) | globally ascending distinct — RoaringBitmap `getArray()`/`toSignedArray` | **`SortedIntArrayCodec` (delta)** |
| `HierarchyIndexStoragePartSerializer` | `childrenIds` per level, `roots`, `orphans` | globally ascending distinct — `TransactionalIntArray` ("unique, strictly ordered ascending"); arrays routinely EMPTY | **`SortedIntArrayCodec` (delta)** on all three |
| `PriceListAndCurrencyRefIndexStoragePartSerializer` | `priceIds` | strictly ascending — built from `TransactionalObjArray<PriceRecordContract>` sorted by `internalPriceId` then deduped (NOT a RoaringBitmap; fragile invariant) | **`SortedIntArrayCodec` (delta)** — route through the asserting codec |

**Part UIDs now (verify on edit):** Facet `-2348533783771242845L`, Hierarchy `-3223754922135567923L`,
PriceRef `-1687563151524978160L`. None has an existing bwc reader.

> **SortIndex is DEFERRED** (was in this step). `sortedRecords` is block-sorted (global delta unsafe),
> and its serializer also carries a `valuesPresent` marker + trailing `indexedDecimalPlaces`
> scaled-int field; per-element zig-zag risks size regression for ids ≥ 2^28/negative. The correct
> SortIndex slimming is delta-WITHIN-block (block lengths from `valueCardinalities`) — a dedicated
> later step, not Phase 1a.

> **Other arrays:** the sweep found none beyond those listed. Re-confirm during edit; do not touch
> non-listed primitive arrays.

### Tests
- Facet / Hierarchy / PriceRef serializers have **no dedicated tests** today. Add focused
  serializer round-trip + lazy-upgrade tests for each (Step 0.2 helper). Cover empty arrays,
  single-element, large-gap, and (Hierarchy) all three arrays + multiple levels.
- Behavioural suites: `FacetIndexTest`, `HierarchyIndexTest`, price-ref index tests green.

---

## Open questions / risks (resolve before/within implementation)

1. **PK sign & range.** Are entity primary keys (chain runs, unique record ids, sort records) always
   ≥ 0? Memory hints "negative PKs" exist somewhere (OffsetIndex context). If PKs can be negative,
   `writeVarInt(_, true)` (plain) would *expand* them to 5 bytes — use zig-zag (`false`) defensively
   where sign is unproven. **Delta encoding of ascending arrays is sign-robust regardless** (only the
   first element needs zig-zag), so Step 3's delta targets are safe; the exposure is Step 1's optional
   run varint, Step 2A record ids, Step 2B PKs, and Step 3 SortIndex — all specified as zig-zag.
2. **SortIndex global-vs-block ordering** — RESOLVED by review (block-sorted, confirmed in source);
   SortIndex deferred out of Phase 1a (needs delta-within-block).
3. **HEAD-format preservation vs fail-loud.** The current HEAD format of each part (the `L_old`
   literal) is unreleased-dev (post-`_2026_1`). The histogram precedent fails loud on unreleased
   formats. We instead **preserve** HEAD as a `_2026_2` reader (cheap, no data loss for existing
   dev/senesi catalogs). Confirm Johnny is fine paying one extra reader class per part for that
   safety (recommended).
4. **bwc tag collision.** Verify `_2026_2` is free per part (some parts may already use it); pick the
   next free `_YYYY_N` following the existing convention.
5. **Step 2B** — RESOLVED: deferred to the Phase-1b GlobalUnique shard.
6. **Codec package placement** — `evita_store_server` serializer-util vs a shared store module;
   ensure no cyclic module dep and that all four serializers can import it.
7. **View/owner symmetry** — Unique view-mode parts (`dataPresent==false`) must remain byte-identical
   in behaviour (they already skip bitmap+map); only the owner-mode branch changes.

---

## Sequencing & cadence

`Step 0` → `Step 1` (Chain) → `Step 2A` (Unique) → `Step 3` (Facet → Hierarchy → PriceRef).
SortIndex slimming and Step 2B (GlobalUnique) are DEFERRED to a later step / Phase 1b. Each step:
implement → focused tests green on Maven 3.9.x → quality pipeline → next. Phase-1a exit = Chain +
Unique + Facet + Hierarchy + PriceRef parts slimmer, all suites green; then the GATE re-measure
(separate) decides Phase 1b / §3.

### Status anchors
- Rough plan: `2026-06-22-issue-760-partB-implementation-steps.md`.
- Design (v3): `2026-06-22-issue-760-partB-granular-storage-parts.md`.
- Nothing implemented yet; working tree clean except pre-existing
  `PriceListAndCurrencyPriceSuperIndex.java` (M) + untracked docs/clojure, docs/scala.
