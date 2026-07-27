# CHAMP Persistent Hash Map

`ChampMap<K, V>` (`io.evitadb.dataType.champ.ChampMap`, module `evita_common`) is an **immutable,
persistent** hash map. Every "mutation" returns a brand-new map that structurally shares the bulk of
its predecessor; the original is never touched. It is the purest expression of design principle #3 of
the STM layer -- [structural sharing / path copying](overview.md#design-principles) -- but, unlike the
structures catalogued in [data-structures.md](data-structures.md), it is **not** a
`TransactionalLayerProducer`: it has no diff overlay, no thread-local, and no commit/rollback. It is a
plain persistent value that hands you a new instance on `updated`/`removed`.

That distinction is why it lives in `evita_common/dataType/` next to the B+ trees rather than in the
transactional-memory package. It is a general-purpose persistent value, built for a **single-writer,
multi-version** access pattern (see [Role](#role)); the [OffsetIndex](../indexes/offset-index.md) is one
such consumer, but the map knows nothing about it.

## Table of contents

| Section | Content |
|---|---|
| [Role](#role) | The single-writer, multi-version access pattern this map serves |
| [What CHAMP is](#what-champ-is) | The data structure and its canonical form |
| [Public API](#public-api) | Factories, persistent mutators, read surface, the Builder |
| [Cost model](#cost-model) | Per-operation complexity and the snapshot-retention payoff |
| [Per-version snapshots](#per-version-snapshots) | Structural sharing for cheap multi-version retention |
| [Transactional vs. persistent](#transactional-vs-persistent) | Contrast with the STM diff-layer structures |
| [Correctness crux & tests](#correctness-crux--tests) | Canonicalization-on-delete, the generational oracle |
| [Credit & licensing](#credit--licensing) | Clean-room provenance |

---

## Role

`ChampMap` serves an access pattern that recurs whenever a key→value map must be published as a
sequence of immutable, version-stamped snapshots:

- **single-writer** -- exactly one thread at a time produces a new version of the map;
- **publish-then-read-only** -- once a version is published it is never mutated in place, and lock-free
  readers share it freely;
- **incremental** -- each new version applies only the `M` changes accumulated since the previous one,
  where `M` is far smaller than the `N` entries already present;
- **multi-version** -- readers resolve as of some logical version, so several recent versions of the map
  must coexist at once.

`ChampMap` is built for exactly this. Applying the `M` changes path-copies only the `O(M)` affected
tree nodes and shares everything else with the previous version, so producing the next version is cheap.
The property that matters most for MVCC follows directly: **retaining the previous version costs a single
extra reference**, because the two versions share the bulk of their nodes. That is what makes a
versioned-roots design -- one immutable root retained per logical version -- affordable.

---

## What CHAMP is

CHAMP -- **C**ompressed **H**ash-**A**rray **M**apped **P**refix-tree (Steindorfer & Vinju, OOPSLA'15)
-- is a 32-way hash trie. The 32-bit key hash is consumed five bits at a time (`BranchingFactor = 32`,
`BitPartitionSize = 5`, `MaxDepth = 7`); at each level the relevant 5-bit slice selects one of 32
slots.

Compared to a plain Hash-Array Mapped Trie (Bagwell), each node keeps **two** 32-bit bitmaps instead of
one:

- `datamap` -- which slots hold an inline `(key, value)` payload, and
- `nodemap` -- which slots hold a child sub-node.

Keeping payloads and children in separate, densely-packed regions of a single `Object[]` improves cache
locality and -- more importantly -- guarantees a **canonical form**: for any given set of entries there
is exactly one tree shape. Deletes therefore *compact* (a sub-node that collapses to a single survivor
is inlined back into its parent; a 2-way hash-collision node that loses one entry shrinks back to an
inline payload). Canonicalization is what makes structural `equals` **sub-linear**: two maps that share
structure can short-circuit on reference identity of whole subtrees. Hash collisions (distinct keys with
the same 32-bit hash) are held in a dedicated collision node.

`ChampMap` rejects `null` keys and `null` values (its keys/values -- `RecordKey`/`FileLocation` -- are
always non-null), which removes the reference implementation's special null-slot handling.

---

## Public API

`ChampMap` implements the **read surface** of `java.util.Map` so it drops into code that expects a
`Map<K, V>`, but all in-place `Map` mutators throw `UnsupportedOperationException` to document its
immutability.

**Construction (static factories):**

| Factory | Result |
|---|---|
| `ChampMap.empty()` | the shared empty map |
| `ChampMap.of(k, v)` | a single-entry map |
| `ChampMap.from(Map)` | a CHAMP copy of an existing map, built in `O(M)` via a `Builder` |
| `ChampMap.builder()` | a fresh, empty `Builder` (transient) |

**Persistent mutators** (each returns a *new* `ChampMap`; the receiver is unchanged):

| Method | Result |
|---|---|
| `updated(key, value)` | a map with `key` inserted or overwritten |
| `removed(key)` | a map with `key` gone (with compaction) |
| `merged(that, MergeResolver)` | the union of two maps, resolver deciding key collisions |

**Read surface** (`java.util.Map`): `get`, `containsKey`, `containsValue`, `size`, `isEmpty`,
`keySet`, `values`, `entrySet`, `equals`, `hashCode`, `toString`.

**In-place mutators** (`put`, `remove`, `putAll`, `clear`, `putIfAbsent`, `replace`): all throw
`UnsupportedOperationException`.

**`ChampMap.Builder<K, V>` (the "transient").** Applying changes one-at-a-time through `updated` would
allocate one intermediate map per change. When building a map from scratch -- or applying a batch under
the serialized writer -- the `Builder` mutates a single tree in place and seals it with `build()`,
making bulk construction `O(M)` rather than `O(M log M)`. The `Builder` is **single-threaded and must be
confined to one thread**; the sealed `ChampMap` is freely shareable (published behind a final-field /
volatile happens-before).

---

## Cost model

`N` = entries in the map, `M` = changes applied.

| Operation | Cost |
|---|---|
| `get` / `containsKey` | `O(log₃₂ N)` -- tree depth ≤ 7; a small constant factor over a flat hash lookup |
| Apply `M` changes (`updated`/`removed`, or a `Builder` batch) | `O(M · log₃₂ N)` -- only the affected nodes are path-copied |
| Retain a prior version | one extra root reference (structural sharing) |
| Structural `equals` | sub-linear -- canonical form lets shared subtrees short-circuit on identity |

The read is a hair slower than a hash-bucket lookup -- an easy trade for a **read-heavy, version-bound**
structure that must produce a fresh immutable version on every change batch and keep many recent versions
live at once.

---

## Per-version snapshots

The single property that makes `ChampMap` worth its slightly slower reads is that **each published
version costs only one extra root reference**. A consumer that keeps a registry of `(version → root)`
gets cheap MVCC for free:

- **Produce the next version:** take the latest published root, apply the `M` changes through a
  `Builder`, and `build()` the new root -- path-copying only the `O(M)` affected nodes.
- **Retain history:** appending a fresh root per logical version is affordable because successive roots
  share the bulk of their nodes; a historical read is a lock-free `root.get(key)` against the
  version's own immutable root, with no diff reconstruction.
- **Drop history:** releasing a version is just dropping its root reference; the now-exclusive CHAMP
  nodes are reclaimed by the GC through structural sharing.

The [`OffsetIndex`](../indexes/offset-index.md#mvcc-via-versioned-roots) is the canonical consumer of
this pattern -- it keeps one `ChampMap<RecordKey, FileLocation>` root per catalog version.

---

## Transactional vs. persistent

`ChampMap` and the [transactional data structures](data-structures.md) both achieve copy-on-write with
structural sharing, but they are used in opposite ways:

| | Transactional structures (e.g. `TransactionalMap`) | `ChampMap` |
|---|---|---|
| Change isolation | thread-local **diff overlay** against an immutable baseline | none -- each call returns a new value |
| Visibility model | uncommitted changes invisible until commit; discarded on rollback | a returned map simply *is* the new state |
| Lifecycle | `createCopyWithMergedTransactionalMemory` at commit | explicit `updated`/`removed`/`merged` / `Builder.build()` |
| Implements | `TransactionalLayerProducer<D, C>` | the read surface of `java.util.Map` |
| Primary user | in-memory query indexes (`EntityIndex` family) | persistence layer (e.g. `OffsetIndex`) |

In short: the STM structures defer the new snapshot to commit time; `ChampMap` produces it eagerly and
hands it back. A consumer reaches for the latter when it must publish a version-stamped state at each
step of a single-writer sequence, rather than at the end of a thread-local transaction.

---

## Correctness crux & tests

The hard part of any CHAMP implementation is **canonicalization-on-delete**: inlining a single-survivor
sub-node back into its parent and shrinking a 2-way collision node to an inline payload. Getting this
wrong yields two trees that hold the same entries but compare unequal, breaking the sub-linear `equals`
and silently corrupting MVCC version comparisons.

It is guarded by:

- **`ChampMapTest`** (`evita_functional_tests`, `io.evitadb.dataType.champ`) -- the
  canonicalization-on-delete suite plus collision-forcing key cases.
- **`LongRunningChampMapTest`** (`evita_long_running_tests`) -- a generational oracle that runs random
  `updated`/`removed` against a `java.util.HashMap` reference, asserting equivalence after every op
  **and** retained-snapshot immutability (older `build()` snapshots must still equal their own oracle
  after later mutations -- the persistence property).

---

## Credit & licensing

`ChampMap` is a **clean-room** reimplementation. A plain Hash-Array Mapped Trie (HAMT) was the initial
candidate, but CHAMP was chosen for its more advanced design -- the canonical form that delivers
sub-linear `equals` and the compaction-on-delete that a HAMT lacks. The algorithm was studied from the
CHAMP paper and the Scala standard library's `scala.collection.immutable.HashMap` (Apache-2.0); **no code
was copied**. Credit is recorded in the class JavaDoc to Michael J. Steindorfer and Jürgen J. Vinju
(CHAMP), Phil Bagwell (HAMT), and the Scala standard-library authors.

---

*See also:*
[STM Overview](overview.md#design-principles) |
[Transactional Data Structures](data-structures.md) |
[OffsetIndex](../indexes/offset-index.md)
