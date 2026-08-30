# evitaDB write-path optimizations — three proposals from the production-catalog WARM_UP profile

> **Status: proposals only — nothing here is implemented.** Extracted from the production catalog
> 2026.2.0-vs-2026.2.2 regression check (`FINAL-REPORT.md`, 2026-08-05), which lives in the
> `warmup-bench` worktree whose pom versions must never merge — hence this standalone copy.
> Percentages are the measured cost of the **current** code; the expected wins are argued from
> reading each site, not demonstrated.
>
> Per `.claude/rules/adr.md` this folder must eventually leave `specifications/` — either promoted to
> an ADR once the work lands, or deleted with the reasoning carried into commit messages.

Derived from the two profiles, ranked by (measured share) × (cheapness and safety of the fix). None
of these is a regression; they are the remaining headroom the profiles expose.

> **Proposals below were written after reading each site, not inferred from frame names.** Every
> claim carries the file and line it came from. Treat them as designs to review, not results.

### 1. Reference-schema resolution — ~18.7% of CPU, and it allocates three objects per call

**What the profile says.** `EntitySchema.getReference` is 15.36% of CPU on its own; add
`ReferenceSchema.getIndexedComponents` (1.91%) and `isFacetedInScope` (1.43%) and it is 18.7% of
write-path CPU spent re-deriving facts that cannot change during a bulk load. `HashMap.getNode` is
the top leaf frame at 21.36% — the same cost seen from the other side. There are **36 call sites in
`io.evitadb.index.mutation`**, 15 of them in `ContainerizedLocalMutationExecutor` alone.

**What the code says.** Two compounding problems, both cheap to fix.

*Problem A — the "or throw" path allocates three objects per call.*
`EntitySchema.getReferenceOrThrowException` (`EntitySchema.java:933`) — the variant the hot path
actually calls — is:

```java
return getReference(referenceName)                                   // Optional #1 (ofNullable)
    .map(ReferenceSchema.class::cast)                                // Optional #2
    .orElseThrow(() -> new ReferenceNotFoundException(referenceName, this));  // capturing lambda
```

The lambda captures `referenceName` and `this`, so it is **not** a hoistable singleton. Three
allocations per reference-schema resolution, on a path called millions of times, purely to express
"get or throw". The allocation-free form is four lines and behaviourally identical:

```java
final ReferenceSchema schema = this.references.get(referenceName);
if (schema == null) {
    throw new ReferenceNotFoundException(referenceName, this);
}
return schema;
```

*Problem B — the map keys are never canonical, so every lookup pays a full string compare.*
`this.references` is an immutable `LinkedHashMap<String, ReferenceSchema>`
(`EntitySchema.java:707`). The `referenceName` strings arriving at it come from
`mutation.getReferenceName()` in the gRPC converters
(`InsertReferenceMutationConverter.java:51` and its four siblings) — **protobuf-decoded, a fresh
`String` instance per message, and `rg` finds no `.intern()` anywhere in that path.** So on every
lookup, `HashMap.getNode`'s `key == k` identity fast path *always* misses, forcing a full
`String.equals` char compare; and `String.hashCode()` is recomputed from scratch per message,
because each fresh instance starts with an uncached hash. That is the 21.36%.

**Proposed fix — canonicalize reference names at the mutation boundary.** Resolve
`mutation.getReferenceName()` through a canonical string table once, where the mutation is built,
so every downstream lookup gets an identity hit and a cached hash. This is the higher-leverage of
the two because it fixes *every* name-keyed lookup in the write path at once — `getReference`, the
`hppc` maps in §2, and the reference indexes — not just this accessor.

**Where, concretely — and it is not against the schema.** The obvious phrasing ("canonicalize against
`references.keySet()`") does not fit the site: `InsertReferenceMutationConverter.convert` takes only
the proto message, has no schema or catalog context, and lives in the **shared** module used by both
client and server. So there are two viable placements and they should not be confused:

- **At the converter (recommended):** a module-private `ConcurrentHashMap<String,String>` acting as a
  canonical table — `name = CANON.computeIfAbsent(raw, Function.identity())`. No schema needed, works
  on both sides, and catches the string at its point of creation, which is what makes every
  downstream lookup cheap.
- **At first schema contact:** canonicalize when the mutation is first resolved against the entity
  schema server-side, where `references.keySet()` *is* in hand. Later, so the raw string survives
  longer, but it can reject unknown names in the same step.

**Do not use `String.intern()`** for either: it is a JVM-wide native table with its own contention
and GC behaviour, and the domain here is a few dozen names per catalog.

**Risk:** none to the on-disk format, none to query semantics. Problem A is a pure refactor of a
single method. Problem B changes only *which instance* of an equal string is retained.

### 2. `verifyReferenceCardinalities` — ~21% alloc / ~11% CPU, and it can be allocation-free

`ContainerizedLocalMutationExecutor.verifyReferenceCardinalities`
(`ContainerizedLocalMutationExecutor.java:3044`) is the **#1 allocator and #2 CPU consumer**, and it
builds no index and writes no byte. Per entity it allocates:

| line | allocation | per entity |
|---|---|---|
| 3053 | `new ObjectIntHashMap<>(references.size())` — counts per reference *name* | 1, correctly presized |
| 3077 | `new ObjectIntHashMap<>()` — counts per `ReferenceKey`, **no size hint** | 1, and it grows (see §3) |
| 3084+ | `LinkedList` of violations | only on violation — already lazy, good |

**The key insight: both maps are unnecessary, because the input is already sorted.**
`ReferencesStoragePart.getReferences()` returns an array sorted by
`ReferenceContract.FULL_COMPARATOR`, which delegates to `ReferenceKey.FULL_COMPARATOR`
(`ReferenceKey.java:226`): **`referenceName` → `primaryKey` → `internalPrimaryKey`**. The invariant
is not incidental — `ReferencesStoragePart:216` asserts it (*"References must be sorted in ascending
order according to their business key"*) and `:245` / `:772` re-establish it with `Arrays.sort`
after every modification. The method already relies on it: the reflected-reference skip at 3066
advances `i` while the *next* element shares a name, and the `referenceSchema` memo at 3061 assumes
same-name references form a contiguous run.

Given that ordering:

- **Counting per name needs no map** — same-name references are contiguous, so a run-length count
  over the array gives the cardinality directly, and the run boundary is where you check it.
- **Duplicate detection needs no map** — within a name run, equal keys are *adjacent*, so a duplicate
  is one comparison against the previous element instead of an `ObjectIntHashMap<ReferenceKey>` and
  all its hashing. **⚠ Use `ReferenceKey.equals`, not `primaryKey ==` — see the hazard below.**
- **The min-cardinality check** (schemas required but absent) is the only part needing cross-run
  state. A reused small array of seen names is enough at ~20 references. *Not* a bitmask over
  `referenceNameIndex` — that is a `Map<String, ReferenceSchema[]>` of **name variants**, not stable
  ordinals, so a bitmask would need an ordinal index that does not exist yet. And note `references`
  is a `LinkedHashMap`: its iteration order is *insertion* order, not sorted, so a merge-join against
  the runs would be wrong without sorting it first.

> **⚠ Hazard to settle before implementing: `ReferenceKey.equals` is conditional and non-transitive.**
> `ReferenceKey.java:129` compares `(referenceName, primaryKey)` and *then*, **only if both sides have
> `internalPrimaryKey > 0`**, requires those to match — otherwise it returns `true` ("we don't know
> the internal PK, assume the keys are equal"). `hashCode` (`:160`) covers only
> `(referenceName, primaryKey)`, which it must, given that.
>
> So `A(n,1,0) == B(n,1,5)` and `A(n,1,0) == C(n,1,7)` but `B != C`. **Equality is not transitive**,
> which makes the *existing* `ObjectIntHashMap<ReferenceKey>` grouping order-dependent whenever a run
> mixes known and unknown internal PKs — first-inserted key wins as canonical. That is a pre-existing
> sharp edge in a validator, not something the rewrite introduces, but the rewrite must not silently
> change which side of it we land on.
>
> The good news: `FullReferenceKeyComparator` (`:226`) carries the *same* `isUnknownReference()`
> guard, so sort order and equality agree — equal keys really are adjacent, and an adjacent
> `equals` comparison reproduces today's behaviour for any run that is not internally
> mixed. **Decide the intended semantics for mixed runs explicitly** rather than translating the
> current code mechanically; that decision is the one thing here that is not a pure refactor.

That removes **both** per-entity map allocations, all `String` hashing, all `ReferenceKey` map-key
boxing, and all rehashing — turning the #1 allocator into a linear scan with no allocation on the
success path. It is a self-contained rewrite of one private method with an exact behavioural
contract, and the sortedness it depends on is already assert-enforced.

**Second, independent angle — skip it entirely on a trusted bulk load.** A full reindex from a
system of record is precisely the case where re-validating every cardinality buys least. That would
be the single largest win available, but it trades a safety net for speed and is a decision for the
team, not something to infer from a profile. **Note the rewrite above makes this much less
attractive** — validation that costs nearly nothing is not worth a config flag and a class of bugs
that only appear when it is off. Sequence the rewrite first, then re-measure before considering it.

### 3. The `hppc` `rehash` cost — 2.95% of CPU — is one missing size hint, inside §2

`ObjectIntHashMap` costs 9.2% of CPU as *leaf* frames — `indexOf` (3.27%), `rehash` (2.95%),
`mixPhi` (1.82%), `EntryIterator.fetch` (1.17%).

**Attribution is measured, not argued.** Walking `runs/prof-cpu-2262/cpu.collapsed` and charging each
sample to the nearest enclosing `io/evitadb` frame:

| stacks containing | samples | of 104,462 | sole owner |
|---|---|---|---|
| any `ObjectIntHashMap` frame | 13,341 | **12.77%** *(inclusive)* | `verifyReferenceCardinalities` — **100%** |
| `ObjectIntHashMap.rehash` | 5,168 | **4.95%** *(inclusive)* | `verifyReferenceCardinalities` — **100%** |

Not one sample of either sits anywhere else in the engine. (Inclusive shares exceed the leaf shares
above because they include the callees — `String.hashCode`, `BitMixer`, array allocation.)

The `rehash` cost has a single cause:

`ContainerizedLocalMutationExecutor.java:3077` constructs `new ObjectIntHashMap<>()` with **no size
hint**. hppc's no-arg constructor uses `Containers.DEFAULT_EXPECTED_ELEMENTS = 4`
(verified in `hppc-0.10.0` sources). That map then takes **one entry per reference on the entity** —
and `Product`, the collection that dominates this dataset, is the reference-heavy one. A Product
with N references forces `⌈log₂(N/4)⌉` growth cycles, each reallocating both backing arrays and
rehashing every key already inserted. At N≈200 that is 6 rehashes **per entity**.

The sibling map at 3053 *is* correctly presized (`references.size()`, and it can never exceed that),
which is why the fix is one line and not a sweep:

```java
duplicatedReferenceFound = new ObjectIntHashMap<>(cntReferences.length);
```

**But prefer the §2 rewrite**, which deletes this map rather than presizing it. The one-liner is
worth landing only if the rewrite is deferred — it is a strictly smaller win (it removes the
rehashing, not the hashing, the allocation or the iteration).

**This is why §3 is not really a third bottleneck** — and that is now measured, not inferred. Every
`ObjectIntHashMap` sample in the profile belongs to `verifyReferenceCardinalities`, so items 2 and 3
are one method; and §1's canonicalization feeds both, because the `hppc` `mixPhi`/`indexOf` cost is
*the same uncanonicalized-`String` hashing* as §1's `HashMap.getNode`. **Three profile entries, two
edits** — and the §2 rewrite subsumes §3 entirely.

### 4. Revisit `AttributeKey` / `ComparableReferenceKey` lifetime — 30.6% of allocation

Short-lived key objects created per reference per entity purely to be compared and discarded.
Sequence this after (1)–(3), which may remove a good share of them as a side effect — but one half of
it is smaller than it looks:

**`ComparableReferenceKey` is a one-field record wrapping `ReferenceKey`**
(`ComparableReferenceKey.java:37`) whose entire body is a `compareTo` delegating to
`ReferenceKey.FULL_COMPARATOR`. It exists only to attach an ordering to a key that already has that
comparator as a static. So call sites allocate a wrapper per lookup purely to select a comparator —
e.g. `this.referenceIndex.get(new ComparableReferenceKey(referenceKey))`
(`ReferenceAttributeValueProvider.java:166`). That is **12.34% of write-path allocation spent on a
`Comparable`-vs-`Comparator` API choice.**

The fix is the standard refactor: key those maps on `ReferenceKey` directly and pass
`ReferenceKey.FULL_COMPARATOR` explicitly at construction (`new TreeMap<>(FULL_COMPARATOR)` and
friends), deleting the wrapper. No behaviour change — same ordering, same equality — and it removes
an allocation from every lookup, not just every insert. `AttributeKey` (18.24%) is the genuinely
harder half and is the one to defer.

### Not a problem, and worth recording as such

- **The collation-key cache is behaving.** `CollationKeyCache.keyFor` is 1.20% of allocation and
  2.56% of CPU, while `char[]` retains ~495 MB. High retention with negligible churn is a populated
  bounded cache, which is exactly what `5f4cfc287` intended. The `char[]` volume is explained and is
  neither a leak nor a regression.
- **Storage compression is cheap here.** `libz` is 2.16% of CPU at `storage.compress=true`.
- **GC is not a constraint.** 0 full collections, 0.38% of wall-clock in pause, live set 8.1 GiB in a
  23 GiB heap. The writer heap could be reduced substantially before GC became interesting.
