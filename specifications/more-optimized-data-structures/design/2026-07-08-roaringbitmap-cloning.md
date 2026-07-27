# RoaringBitmap cloning — make `PersistentRoaringBitmap.clone()` O(1) via frozen backing arrays

Issue: #760 (heap/churn refinement). The vendored `PersistentRoaringBitmap` is COW at the
container level but still copies the `keys[]`/`values[]` arrays on every `clone()`, costing
**12.2 % of all allocated bytes** (3.5 % `clone` directly + the wrapper allocations) under the
ALIVE churn profile. This plan makes `clone()` near-O(1) by sharing the `keys[]`/`values[]` backing
arrays with a copy-on-write `frozen` flag (§3.2-3.3), and does NOT touch the on-disk format. A
further `shared[]`-flags sentinel (§3.4) that would make clone *fully* O(1) is analysed but
**deferred** — it is a disproportionate correctness risk for a marginal allocation saving (see
§3.4 and §6.5).

## 1. Problem & scope

async-profiler `-e alloc` on `EvitaWarmUpInsertionTest` unique/ALIVE (1 GB / 0.4, 130.64 GB
allocated over 4.5 min) attributes **12.2 % of all allocated bytes** to RoaringBitmap:

| site | share |
|---|---|
| `PersistentRoaringBitmap.clone` | 3.5 % |
| `RoaringArray.<init>` | 2.75 % |
| `PersistentRoaringBitmap.<init>` | 2.36 % |
| `addN` | 1.18 % |
| `ArrayContainer.<init>` | 1.16 % |
| `BaseBitmap.<init>` | 1.22 % |
| `BitmapContainer.<init>` | 1.33 % |

The `clone` 3.5 % is the **per-commit MVCC clone** of the `entityIds` bitmap (and other index
bitmaps). The `EntityIdsStoragePart` bitmap (~62 KB for 500 k entities) is re-cloned PER COMMIT
and is also the #2 compaction cost (6.5 % of append bytes, 62 KB/commit — see
`partb-step4-entityindex-bitmap-eviction`).

Johnny's expectation: "*RoaringBitmap should be PersistentRoaringBitmap which should be very
optimal for cloning*." This plan investigates why it isn't — yet — and fixes it.

**Non-goals.** The EntityIds paging (`partb-step4`) reduces the serialization cost (62 KB/commit)
and is **related but distinct** — it does not address the clone allocation. The `addN` /
`ArrayContainer` / `BitmapContainer` / `BaseBitmap` allocations (mutation-path and construction
allocations) are separate from the clone and out of scope for this plan.

## 2. Investigation findings

### 2.1 `PersistentRoaringBitmap.clone()` IS copy-on-write — but copies the backing arrays

File `evita_roaring_bitmap/.../PersistentRoaringBitmap.java:3061-3074`:

```java
public PersistentRoaringBitmap clone() {
    final int size = this.highLowContainer.size();
    final char[] newKeys = Arrays.copyOf(this.highLowContainer.keys, size);       // O(containers)
    final Container[] newValues = Arrays.copyOf(this.highLowContainer.values, size); // O(containers), refs only
    final RoaringArray clonedArray = new RoaringArray(newKeys, newValues, size);
    ensureSharedCapacity(size);
    Arrays.fill(this.shared, 0, size, true);    // mark source containers shared
    final boolean[] cloneShared = new boolean[size];   // O(containers)
    Arrays.fill(cloneShared, true);             // mark clone containers shared
    return new PersistentRoaringBitmap(clonedArray, cloneShared);
}
```

The clone is **NOT a deep copy** — `Container` objects are shared by reference between source and
clone. Both sides set `shared[] = true` so the first in-place mutation of any container clones
only that one container just-in-time (`copyIfShared`, `:3717-3725`). This is the COW design folded
from the upstream `CopyOnWriteRoaringBitmapV2` prototype (vendoring plan
`2026-06-27-roaring-bitmap-vendoring-part1.md`, revision 3).

**The remaining cost is the backing-array copy.** `clone()` allocates `char[size]` (keys) +
`Container[size]` (value references) + `boolean[size]` (shared flags) per clone — O(containers).
For a bitmap with C containers this is `2*C + 8*C + C ≈ 11*C` bytes per clone. The container
**data** is never copied at clone time (only at first mutation, via `copyIfShared`).

### 2.2 `RoaringArray.clone()` (deep copy) is NOT on the EntityIds path

`RoaringArray.clone()` (`RoaringArray.java:331-341`) is a **deep copy** — it clones every
container (`sa.values[k] = sa.values[k].clone()`). It is called only by
`PersistentLongRoaringBitmap.clone()` (`:1298`) — the 64-bit variant. The 32-bit
`PersistentRoaringBitmap.clone()` does NOT call it. EntityIds are 32-bit, so the deep-copy path
is irrelevant here.

### 2.3 Container `clone()` deep-copies the data array

- `ArrayContainer.clone()` (`ArrayContainer.java:391`) → `new ArrayContainer(card, content)` →
  constructor (`:130-133`) does `Arrays.copyOf(newContent, newCard)` — copies the `char[]`.
- `BitmapContainer.clone()` (`BitmapContainer.java:409`) → `new BitmapContainer(card, bitmap)` →
  private constructor (`:130-133`) does `Arrays.copyOf(newBitmap, newBitmap.length)` — copies the
  `long[]`.

These are called by `copyIfShared(i)` (`:3717-3725`) — the lazy COW guard — only for the
**mutated** container, not all containers. The `ArrayContainer.<init>` 1.16 % + `BitmapContainer.<init>`
1.33 % in the profile come from the mutation path (add/remove) and `BaseBitmap` construction, not
from `clone()`.

### 2.4 WHERE is the per-commit clone? — `TransactionalBitmap.java:93`

The `entityIds` field in `EntityIndex` is a **`TransactionalBitmap`** (`EntityIndex.java:130`),
not a raw `BaseBitmap`. `TransactionalBitmap` wraps a `PersistentRoaringBitmap`
(`TransactionalBitmap.java:63`, `final`).

The per-commit clone chain (write path — every commit):

1. `GlobalEntityIndex.createCopyWithMergedTransactionalMemory` (`:305`) / `ReducedEntityIndex` (`:255`)
   / `ReducedGroupEntityIndex` (`:865`) calls `transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds)`.
2. `TransactionalBitmap.createCopyWithMergedTransactionalMemory` (`:108-116`):
   - `layer == null` (bitmap untouched): returns `this` — no clone here.
   - `layer != null` (bitmap modified): returns `new BaseBitmap(layer.getMergedBitmap())` —
     `BaseBitmap(PersistentRoaringBitmap)` (`:144-147`) does **not** clone; `getMergedBitmap()`
     returns a freshly computed `PersistentRoaringBitmap` (via `or`/`andNot`).
3. The result is passed to the `EntityIndex` reconstruction constructor (`EntityIndex.java:302`):
   `this.entityIds = new TransactionalBitmap(entityIds)`.
4. **`TransactionalBitmap(Bitmap)` copy constructor (`:90-99`) ALWAYS calls
   `getRoaringBitmap().clone()` at line 93** — regardless of whether the bitmap changed.

**This is the root cause.** Even when the bitmap is UNCHANGED (`layer == null`,
`createCopyWithMergedTransactionalMemory` returns `this`), the `EntityIndex` constructor re-wraps
it in `new TransactionalBitmap(entityIds)`, which unconditionally clones the underlying
`PersistentRoaringBitmap`. The clone is the O(containers) array copy described in §2.1.

### 2.5 Is the cloned `PersistentRoaringBitmap` ever directly mutated?

`TransactionalBitmap.add()` (`:135-149`):
- **Transactional path** (a transaction is open, `layer != null`): delegates to
  `layer.addRecordId(recordId)` (`:146`) — does **NOT** touch `this.roaringBitmap`.
- **Non-transactional path** (no transaction): `this.roaringBitmap.add(recordId)` (`:142`) —
  directly mutates the `PersistentRoaringBitmap` in place.

Same split for `remove` (`:220-234`), `addAll` (`:152-180`), `removeAll` (`:237-...`).

In the **commit path** (the hot path that drives the 3.5 %), the cloned
`PersistentRoaringBitmap` is created at commit and then used as the new MVCC version. Future
mutations in the next transaction go through the `BitmapChanges` layer — the
`PersistentRoaringBitmap` is **read-only** (queried via `getRoaringBitmap()`, cloned again at the
next commit). It is never directly mutated in the transactional path.

In the **non-transactional path** (initial load, no transaction open), the cloned
`PersistentRoaringBitmap` IS directly mutated — but this path is not the ALIVE-churn hot path.

**Conclusion:** in the transactional commit path (the measured hot path), the clone's backing
arrays are **never written to** after the clone. The O(containers) array copy is pure waste. The
fix is to share the arrays and defer the copy to the first mutation (which, in the transactional
path, never comes).

### 2.6 Bitmap type map

| site | type | notes |
|---|---|---|
| `EntityIndex.entityIds` (`:130`) | `TransactionalBitmap` | wraps `PersistentRoaringBitmap` (final) |
| `EntityIndex.entityIdsByLanguage` (`:134`) | `TransactionalMap<Locale, TransactionalBitmap>` | same, per-locale |
| `EntityIdsStoragePart.entityIds` (`:75`) | `Bitmap` (supertype) | carries the live `TransactionalBitmap`; constructor does NOT clone |
| `BaseBitmap.roaringBitmap` (`:54`) | `PersistentRoaringBitmap` | the concrete COW bitmap |

`EntityIdsStoragePart` (`:82-92`) does **not** clone — it shares the live bitmap reference with
the index. Serialization reads from the live bitmap. The part is emitted only when
`dirty.isTrue()` (`EntityIndex.java:868/892`), i.e. only when membership actually changed.

## 3. Design — frozen backing arrays (copy-on-write at the `RoaringArray` level)

### 3.1 The lever — and why the alternatives are rejected

| lever | saves | safe? | verdict |
|---|---|---|---|
| **A. Frozen arrays** (this plan) | 3.5 % clone array copy | yes — COW at array level, defrost on any write | **YES** |
| B. Skip clone in `TransactionalBitmap(Bitmap)` when unchanged | 3.5 % + wrapper allocs | **no** — shares `PersistentRoaringBitmap` between old/new MVCC versions; non-tx mutations corrupt the old version; containers not marked shared | rejected |
| C. Skip re-wrap in `EntityIndex` constructor when unchanged | same as B | **no** — shares `TransactionalBitmap` object between old/new MVCC versions; same corruption risk | rejected |
| D. EntityIds paging (`partb-step4`) | serialization cost (62 KB/commit) | yes | **related but distinct** — does not address clone allocation |

B and C are rejected because they violate MVCC isolation: the old version's
`PersistentRoaringBitmap` would be aliased by the new version, and a non-transactional direct
mutation (`TransactionalBitmap.java:142`) would corrupt the old version. They also skip the
`shared[]` bookkeeping, so `copyIfShared` would not protect container mutations.

A is the right lever: it preserves MVCC isolation (separate `PersistentRoaringBitmap` objects,
COW containers, COW arrays), is safe in both transactional and non-transactional paths, and
eliminates the O(containers) array copy in the transactional path (where the clone is never
mutated, so the frozen flag never triggers a defrost).

### 3.2 The frozen flag

Add a `boolean frozen` field to `RoaringArray` (default `false`). When `true`, the `keys[]` and
`values[]` arrays are co-owned by another `RoaringArray` and MUST be copied before any write.

**`defrost()`** — the COW guard for the array level, mirroring `copyIfShared` for containers:

```java
private void defrost() {
    if (this.frozen) {
        this.keys = Arrays.copyOf(this.keys, this.size);
        this.values = Arrays.copyOf(this.values, this.size);
        this.frozen = false;
    }
}
```

Call `defrost()` at the head of every `RoaringArray` method that writes to `keys[]` or `values[]`:

| method | line | what it writes |
|---|---|---|
| `append(char, Container)` | `:178` | `keys[size]`, `values[size]` |
| `append(RoaringArray)` | `:196` | replaces `keys`/`values` (grow) |
| `appendCopiesAfter` | `:217` | `keys[size]`, `values[size]` |
| `appendCopiesUntil` | `:239` | `keys[size]`, `values[size]` |
| `appendCopy(RoaringArray, int)` | `:257` | `keys[size]`, `values[size]` |
| `appendCopy(RoaringArray, int, int)` | `:271` | `keys[size]`, `values[size]` |
| `append(RoaringArray, int, int)` | `:287` | `keys[size]`, `values[size]` |
| `insertNewKeyValueAt` | `:946` | shifts + writes `keys[i]`, `values[i]` |
| `removeAtIndex` | `:966` | shifts + nulls `keys[size-1]`, `values[size-1]` |
| `removeIndexRange` | `:981` | shifts + nulls trailing |
| `replaceKeyAndContainerAtIndex` | `:1003` | overwrites `keys[i]`, `values[i]` |
| `resize` | `:1014` | nulls trailing `keys`/`values` |
| `setContainerAtIndex` | `:1138` | overwrites `values[i]` |
| `copyRange` | `:351` | shifts within `keys`/`values` |
| `extendArray` | `:741` | replaces `keys`/`values` (grow) |
| `trim` | `:314` | replaces `keys`/`values` (shrink) |
| `deserialize(DataInput)` | `:367` | reuses + writes `keys[k]`/`values[k]` in place (see below) |
| `deserialize(DataInput, byte[])` | `:453` | reuses + writes `keys[k]`/`values[k]` in place (see below) |
| `deserialize(ByteBuffer)` | `:640` | reuses + writes `keys[k]`/`values[k]` in place (see below) |

(19 methods — all in `RoaringArray`, all package-private or private except the three public
`deserialize` overloads. `readExternal(ObjectInput)` (`:956`) delegates to `deserialize(DataInput)`
and is covered transitively. No public-API *signature* change.)

**The three `deserialize` overloads are a special case — they do NOT take a plain `defrost()`.**
Each has a reallocate-or-reuse guard (`:382`, `:478`, `:658`):

```java
if ((this.keys == null) || (this.keys.length < this.size)) {
    this.keys = new char[this.size];       // reallocate
    this.values = new Container[this.size];
}
// ... else the existing arrays are REUSED and overwritten in place:
this.keys[k]   = keys[k];   // :437 / :618 / :715
this.values[k] = val;
```

When the existing arrays are large enough the reuse branch writes `keys[k]`/`values[k]` **in
place** — which would corrupt a frozen co-owner. A head `defrost()` is the wrong fix here: it would
copy the arrays we are about to fully overwrite (pure waste). The correct fix is to **force
reallocation when frozen**, so the write lands on a fresh private array and the flag clears:

```java
if ((this.keys == null) || (this.keys.length < this.size) || this.frozen) {
    this.keys = new char[this.size];
    this.values = new Container[this.size];
    this.frozen = false;   // fresh arrays are owned
}
```

(In the reallocating branch a frozen flag must also be cleared regardless — the newly allocated
arrays are private, so the flag would otherwise be a stale `true` on owned arrays. Deserializing
into a previously-cloned bitmap is almost certainly unreachable in evitaDB — bitmaps deserialize
into fresh instances — but the defensive-design rule (CLAUDE.md) forbids leaving the in-place write
silently unguarded.)

### 3.3 O(1) `clone()`

`PersistentRoaringBitmap.clone()` (`:3061-3074`) becomes:

```java
public PersistentRoaringBitmap clone() {
    final int size = this.highLowContainer.size();
    // Share the backing arrays — both sides frozen; first structural write defrosts (COW).
    this.highLowContainer.frozen = true;
    final RoaringArray clonedArray = new RoaringArray(
        this.highLowContainer.keys, this.highLowContainer.values, size);
    clonedArray.frozen = true;
    // Mark containers as shared (existing COW at container level — unchanged).
    ensureSharedCapacity(size);
    Arrays.fill(this.shared, 0, size, true);
    final boolean[] cloneShared = new boolean[size];
    Arrays.fill(cloneShared, true);
    return new PersistentRoaringBitmap(clonedArray, cloneShared);
}
```

The clone now allocates only: 1 `RoaringArray` object + 1 `PersistentRoaringBitmap` object +
`boolean[size]` (shared flags). The `char[size]` (keys) and `Container[size]` (values) copies are
eliminated — they are shared and frozen.

**Why this is safe in the transactional path (the hot path):** the cloned
`PersistentRoaringBitmap` is never directly mutated — `TransactionalBitmap.add/remove` delegate to
the `BitmapChanges` layer (`:146`). So `defrost()` is never called, and the shared arrays are
never written to. The frozen flag is a safety net that never triggers.

**Why this is safe in the non-transactional path:** `TransactionalBitmap.add` (`:142`) calls
`this.roaringBitmap.add(recordId)`, which calls `copyIfShared(i)` (container-level COW) then
`setContainerAtIndex(i, newContainer)` (`:1618-1619`). `setContainerAtIndex` calls `defrost()`,
which copies the arrays (one-time O(containers)) and sets `frozen=false`. Subsequent mutations
proceed on the private copy. The cost is the same as the current clone — just deferred to the
first mutation. No regression.

The clone above still allocates `cloneShared = boolean[size]`. §3.4 analyses a `null` sentinel that
would remove that last O(containers) allocation, but it is **deferred, not implemented** — the
`clone()` above (with the `cloneShared` allocation) is the one this plan ships. §3.4 is retained as
a documented option in case the residual `boolean[]` ever shows up in a re-profile.

### 3.4 `shared[]` sentinel — DEFERRED (analysis retained)

**Status: NOT in this plan's implementation.** Deferred because it is a disproportionate correctness
risk (§3.4.2, §6.5) for a marginal saving — it removes only the `boolean[size]` on top of §3.3's
~90 % reduction. Revisit only if a post-§3.3 re-profile shows the residual `cloneShared` allocation
still registers. The full analysis below is what it would take to do it *safely*, so the option is
shovel-ready.

#### 3.4.1 What it is and why it is not "just a null check"

The `boolean[size]` allocation (`cloneShared`, the per-container COW flags) is still O(containers).
For the small-container common case (the 500 k-entity `entityIds` bitmap has ~8 containers) the
`size` data bytes are trivial, but the **array object header** (~16 B) is the same order as the
two wrapper objects we cannot avoid — so eliminating it removes roughly a quarter of the
*post-§3.3* clone footprint for small bitmaps. This is the last O(size) term; removing it makes
`clone()` truly O(1).

The lever is a sentinel: `shared == null` means **"every container is shared"** (equivalent to a
`boolean[size]` filled `true`, without allocating it). A clone sets both sides' `shared` to
`null` — or, for the source, keeps its existing array and fills it `true` as today (the source
already owns an allocated array; only the *clone's* fresh allocation is eliminated).

**This is NOT a two-line change to `isShared`.** `shared` is dereferenced at **nine** sites, and
`shared == null` is the *opposite* default from the existing "undersized array" tolerance (see
§3.4.2). Every write site must first **materialize** the array; every read site must treat `null`
as all-`true`. This is a second copy-on-write guard, structurally symmetric to `defrost()` in
§3.2 but for the `shared[]` array.

**`materializeShared()`** — the COW guard for the shared-flags array:

```java
private void materializeShared() {
    if (this.shared == null) {
        final int size = this.highLowContainer.size();
        this.shared = new boolean[size];
        Arrays.fill(this.shared, true);   // sentinel meant "all shared"
    }
}
```

Every access site in `PersistentRoaringBitmap`, and how the sentinel changes it:

| site | line | access | sentinel handling |
|---|---|---|---|
| `isShared(i)` | `:3701` | read `.length`, `[i]` | `this.shared == null \|\| (i < shared.length && shared[i])` |
| `copyIfShared(i)` | `:3718-3723` | read `[i]`, **write `[i]=false`** | `materializeShared()` first (it writes `shared[i]=false`) |
| `ensureSharedCapacity` | `:3707-3711` | read `.length`, reassign | `materializeShared()` first — called by in-place `and`/`andNot`/`select` on **operands** |
| `sharedInsertAt` | `:3738-3742` | arraycopy + **write** | `materializeShared()` first |
| `sharedRemoveAt` | `:3749-3758` | arraycopy + **write** | `materializeShared()` first |
| `sharedRemoveRange` | `:3765-3774` | arraycopy + **fill** | `materializeShared()` first |
| `borrowAndInsert` | `:3792-3793` | **write `x2.shared[pos2]=true`** on operand | `x2.materializeShared()` first |
| `andNot` inline merge | `:1822`, `:1831` | raw read `this.shared[pos1]`, arraycopy | reached via `ensureSharedCapacity` materialize (`:1802`) |
| static extraction (`highlowcontainer`) | `:1466-1467` | **write `rb.shared[i]=true`** on operand | reached via `rb.ensureSharedCapacity` materialize (`:1466`) |

The two operand-mutation sites (`borrowAndInsert:3793`, extraction `:1467`) are the subtle ones: a
freshly-cloned (sentinel) bitmap can be passed as an **input** to a static binary op, and those ops
raise `shared[]` flags on their operands as a documented side effect (class JavaDoc, `:70-72`). Both
reach the operand's array through `ensureSharedCapacity`, so routing the materialize through
`ensureSharedCapacity` covers them — but this must be verified, not assumed, by the tests in §7.

#### 3.4.2 Semantic clash — "undersized = owned" vs "null = shared"

This is the highest-risk aspect of the sentinel and must be implemented with care. The existing code
**deliberately tolerates an undersized `shared[]`** and treats entries beyond `shared.length` as
**`false` (owned)** — see the comments at `:1799-1801`, `:3751-3755`, and `sharedRemoveAt`. Static
all-owned builders (`flip`, `orNot`, `addOffset`) leave `shared[]` shorter than the container array
on purpose, and the guards read "beyond length ⇒ owned."

The sentinel introduces a **second "absent" encoding — `null` — meaning the exact opposite:
`true` (shared) everywhere.** Both encodings now coexist in the same guards. `isShared` must
distinguish them:

- `shared == null` → **shared** (freshly cloned, nothing mutated yet)
- `shared != null && i >= shared.length` → **owned** (undersized array from an all-owned builder)

`materializeShared()` allocates `boolean[size]` filled `true`, which is the correct expansion of the
`null` sentinel (a just-cloned bitmap has every container shared). It must never be confused with the
`ensureSharedCapacity` grow path, which pads with `false` (owned). Getting one branch wrong aliases a
container across an MVCC boundary and mutates it in place — the §6.1 corruption, now with two failure
modes. Each branch gets an explicit test in §7.

#### 3.4.3 The fully-O(1) `clone()` (only if the sentinel is later adopted)

If the sentinel were implemented, `PersistentRoaringBitmap.clone()` (`:3061-3074`) would become:

```java
public PersistentRoaringBitmap clone() {
    final int size = this.highLowContainer.size();
    // (1) Array-level COW (§3.3): share keys[]/values[], freeze both sides.
    this.highLowContainer.frozen = true;
    final RoaringArray clonedArray = new RoaringArray(
        this.highLowContainer.keys, this.highLowContainer.values, size);
    clonedArray.frozen = true;
    // (2) Container-level COW (existing): mark the source's containers shared. The source already
    //     owns an allocated shared[] — fill it, no allocation. materializeShared() is a no-op here
    //     unless the source is itself a sentinel (double-clone, §7), in which case it allocates once.
    this.materializeShared();
    Arrays.fill(this.shared, 0, size, true);
    // (3) Shared-flags sentinel (§3.4): the CLONE shares all containers — encode with null, no
    //     boolean[size] allocation. First structural/container write on the clone materializes it.
    return new PersistentRoaringBitmap(clonedArray, null);
}
```

The clone now allocates only **1 `RoaringArray` + 1 `PersistentRoaringBitmap`** — no `char[]`, no
`Container[]`, no `boolean[]`. Truly O(1). The `PersistentRoaringBitmap(RoaringArray, boolean[])`
constructor (`:1503`) and the `shared` field (`:107`) must drop their `@Nonnull` and become
`@Nullable`.

## 4. Lifecycle correctness

### 4.1 Container-level COW is unchanged

`copyIfShared(i)` (`:3717-3725`) and the `shared[]` bookkeeping are NOT modified by this plan (the
sentinel of §3.4 is deferred). Containers are still shared by reference between clone and source,
and the first container mutation clones only that container. The `frozen` flag operates one level
above — at the backing-array level — and is orthogonal to the container COW.

### 4.2 `setContainerAtIndex` defrosts before writing

`setContainerAtIndex(i, c)` is called after `copyIfShared(i)` clones the container. It writes
`values[i] = c`. If `values[]` is shared (frozen), this write would corrupt the co-owner. The
`defrost()` call at the head of `setContainerAtIndex` copies the arrays first. This means: the
first container mutation after a clone pays O(containers) for the defrost (same as the current
clone cost). In the transactional path, no container mutation ever happens on the cloned bitmap,
so the defrost never fires.

### 4.3 Structural mutations defrost before writing

`insertNewKeyValueAt`, `removeAtIndex`, `resize`, etc. all call `defrost()` first. A structural
mutation (new 16-bit chunk → new container) on a frozen bitmap copies the arrays. This is correct
and matches the current behavior (where the arrays were already copied at clone time).

### 4.4 `clear()` replaces the `RoaringArray` — no defrost needed

`PersistentRoaringBitmap.clear()` (`:2023-2026`) replaces `this.highLowContainer` with a fresh
`RoaringArray`. The old (frozen) `RoaringArray` is abandoned. The co-owner's `frozen` flag stays
`true`, but its arrays are never written to (the co-owner is a committed MVCC snapshot). No
defrost needed — `clear()` does not write to the old arrays.

### 4.5 Static binary ops create fresh `RoaringArray`s — no frozen flag

`or`/`xor`/`andNot`/`and` (`:602-892`) create `new RoaringArray()` and append into it. The new
array starts with `frozen = false`. The inputs' arrays are read-only during the merge. No defrost
needed.

## 5. Wire format & BWC — none

The frozen flag is a **transient in-memory field** — it is not part of the Roaring serialization
format (`RoaringArray.serialize` / `deserialize` use the portable little-endian Roaring format,
unaffected). The `shared[]` array is also in-memory only.

- No on-disk format change.
- No `serialVersionUID` bump (per intra-dev no-bump policy —
  `serialVersionUID-bump-policy.md`).
- No BWC reader needed.
- No Kryo serializer change (bitmaps serialize via their own `serialize(DataOutput)` /
  `deserialize(DataInput)`, not via Kryo).

The `RoaringArray.serialVersionUID = 8L` and `PersistentRoaringBitmap.serialVersionUID = 6L` are
unchanged — the serialized form is identical.

## 6. Risks & mitigations

### 6.1 Missed mutation method → silent corruption

`keys[]`/`values[]` are copy-on-write, guarded by `defrost()` (§3.2). If any method that writes
those arrays is not instrumented, a frozen bitmap is silently corrupted. **Mitigation:** the §3.2
table enumerates all 19 write methods — including the three `deserialize` overloads whose in-place
reuse branch was originally missed (now guarded by force-reallocation, §3.2) — re-verified by
reading `RoaringArray.java` end-to-end. The §7 unit tests exercise clone-then-mutate for every
container operation (add, remove, add-range, remove-range, flip, clear, and, or, xor, andNot, orNot,
trim, limit) plus the deserialize-into-frozen case, to catch any missed method. (The `shared[]`
array keeps its existing per-clone allocation — the sentinel of §3.4 is deferred — so it needs no
new guard.)

### 6.2 `getRoaringBitmap()` contract — "modifications affect this bitmap directly"

`RoaringBitmapBackedBitmap.getRoaringBitmap()` (`:194`) returns the internal reference and its
contract says "modifications to it will affect this bitmap directly." If a caller mutates the
returned bitmap after a clone (where arrays are shared/frozen), the `defrost()` guard fires and
copies the arrays — the mutation proceeds on the private copy, and the co-owner is unaffected.
This preserves the contract: the mutation affects "this bitmap" (the one whose
`getRoaringBitmap()` was called), not the co-owner. No contract violation.

### 6.3 Thread-safety — unchanged

The class JavaDoc (`:70-72`) already states: "a static op writes `shared[]` flags on its operands
as a side effect, it must not run concurrently with any other access to those operands." The
`frozen` flag has the same constraint — it is not thread-safe and must not be written/read
concurrently. This matches the existing COW design. No new thread-safety risk.

### 6.4 Performance regression on non-transactional path

In the non-transactional path, the first mutation after a clone pays O(containers) for the
`defrost()` (copy `keys[]`/`values[]`) — the same as the current clone cost, just moved from
clone-time to first-mutation-time. There is no net regression. In the transactional path (the hot
path) `defrost()` never fires on the clone, so the array copy is eliminated for the clone's whole
MVCC lifetime (the `boolean[size]` shared-flags allocation remains — see §6.5).

### 6.5 Why the `shared[]` sentinel (§3.4) is deferred, not shipped

The sentinel would remove the last O(containers) allocation (`cloneShared = boolean[size]`) but is
judged not worth its risk **in the initial implementation**:

- **Marginal benefit.** §3.3 already removes the `char[]` + `Container[]` copies (~90 % of the clone
  cost). The sentinel removes only the `boolean[size]` — for the ~8-container `entityIds` bitmap
  that is a single ~24 B array header per clone, on top of two unavoidable wrapper objects.
- **Disproportionate, silent-corruption-class risk.** It adds a second COW guard across 9 access
  sites (§3.4.1) and introduces a two-default clash — `null` = shared vs. undersized array = owned
  (§3.4.2) — inside the same guards. A single wrong branch aliases a container across an MVCC
  boundary and mutates it in place: no exception, only detectable by soak/oracle divergence.
- **§3.3 is self-contained.** Shipping it alone captures nearly all the win and lets the
  async-profiler re-run (§8, step 6) reveal whether the residual `cloneShared` even registers before
  the sentinel risk is ever taken on.

Revisit §3.4 only if that re-profile shows the `boolean[size]` allocation is still material.

## 7. Test plan (TDD — red first)

Unit (`evita_roaring_bitmap` module, `TestPersistentRoaringBitmap.java`):

- **Clone-then-add isolation** — clone a multi-container bitmap; add a value to the clone that
  falls in an existing container; assert the source is unchanged (container COW) AND the source's
  `keys[]`/`values[]` arrays are not corrupted (array COW via defrost). Verify the clone's
  `frozen` flag is cleared after the mutation.
- **Clone-then-add-new-chunk isolation** — clone; add a value in a new 16-bit chunk (structural
  mutation → `insertNewKeyValueAt`); assert source's `keys[]` is not corrupted (defrost fired).
- **Clone-then-remove isolation** — clone; remove a value that empties a container (structural
  removal → `removeAtIndex`); assert source is not corrupted.
- **Clone-then-clear** — clone; `clear()` the clone; assert source still reads correctly (clear
  replaces the `RoaringArray`, does not write to the shared arrays).
- **Clone-then-and/or/xor/andNot** — clone; in-place binary op on the clone; assert source is not
  corrupted (these ops call `copyIfShared` + `setContainerAtIndex`/`replaceKeyAndContainerAtIndex`
  → defrost fires).
- **Clone-then-trim** — clone; `trim()` the clone; assert source's arrays are not shrunk (trim
  calls defrost).
- **Double-clone** — clone A from B, then clone C from A; mutate C; assert A and B are both
  uncorrupted (frozen flag propagates correctly through a chain).
- **Clone eliminates the array copies — allocation assertion** — using JOL or a byte-counter,
  assert that `clone()` on a 1000-container bitmap does NOT allocate `char[1000]` (keys) or
  `Container[1000]` (values) — only the two wrapper objects + the `boolean[1000]` shared-flags
  array (`cloneShared`, retained; the sentinel that would remove it is deferred, §3.4/§6.5). This
  is the regression guard for the primary optimization.

_(Sentinel-specific tests — `shared == null` isolation, materialize-on-mutation, operand-mutation
sites, two-default disambiguation — are specified in the §3.4 analysis but NOT part of this plan's
test set; they land only if §3.4 is ever adopted.)_

Unit (`evita_roaring_bitmap` module, `RoaringArrayTest.java`):

- **Frozen array defrost** — construct a `RoaringArray`, freeze it, call each of the 19 write
  methods (§3.2, incl. the three `deserialize` overloads), assert the source array is not modified
  and the method succeeds on a private copy.
- **Deserialize into a frozen array** — freeze a `RoaringArray` whose `keys[]` is large enough to
  be reused, `deserialize` into it, and assert the frozen co-owner's arrays are untouched (the
  force-reallocation guard, §3.2, fired instead of an in-place overwrite) and `frozen` is cleared.

Functional (`evita_functional_tests`):

- `BaseBitmapTest` — existing clone/isolation tests (unchanged behavior, must stay green).
- `TransactionalBitmapTest` — existing transactional bitmap tests (unchanged behavior).
- `EntityIndexBitmapEvictionTest` — EntityIndex bitmap eviction (flush+reload round-trip).
- `EntityIndexRoundTripTest` — EntityIndex flush+reload (the `LegacyInlineBitmapManifestReloadTest`
  inner class validates BWC).

Full gate: `EvitaWarmUpInsertionTest` (the measured workload) — verify the 12.2 % alloc share
drops (re-run async-profiler `-e alloc` post-fix; expect the `clone` 3.5 % to drop to ~0 and the
total RoaringBitmap share to drop by ~3-4 percentage points).

## 8. Implementation steps

This plan ships the array-level COW only (§3.2-3.3). The `shared[]` sentinel (§3.4) is deferred
(§6.5) and is NOT implemented here.

1. **`RoaringArray` frozen field + `defrost()`** — add `boolean frozen` (default `false`) and the
   `defrost()` method. Add the `defrost()` call at the head of the 16 in-place write methods in the
   §3.2 table, and add the **force-reallocation guard** (`|| this.frozen`, resetting the flag) to
   the three `deserialize` overloads (`:367`, `:453`, `:640`) — NOT a plain `defrost()` there, per
   §3.2. JavaDoc on `frozen` and `defrost()` explaining the COW-at-array-level design.

2. **`PersistentRoaringBitmap.clone()`** — replace the `Arrays.copyOf(keys)` /
   `Arrays.copyOf(values)` calls with shared references + `frozen = true` on both sides (§3.3),
   keeping the `cloneShared = boolean[size]` allocation and the existing `shared[]` bookkeeping
   unchanged. Update the `clone()` JavaDoc.

3. **`PersistentRoaringBitmap` class-level JavaDoc** — update the copy-on-write note (`:53-78`) to
   describe the array-level COW (`frozen` flag, §3.2-3.3) alongside the existing container-level COW
   (`shared[]`). Clarify that `clone()` no longer copies `keys[]`/`values[]` — it shares them and
   defers the copy to the first structural mutation, which in the transactional path never comes.

4. **Tests** — §7 (red first, then green).

5. **Verify** —
   ```shell
   mvn -pl evita_roaring_bitmap test
   mvn -pl evita_test/evita_functional_tests test \
     -Dtest='BaseBitmapTest,TransactionalBitmapTest,EntityIndexBitmapEvictionTest,EntityIndexRoundTripTest'
   ```
   Then re-run the async-profiler `-e alloc` measurement on `EvitaWarmUpInsertionTest` to confirm
   the alloc share drop.
