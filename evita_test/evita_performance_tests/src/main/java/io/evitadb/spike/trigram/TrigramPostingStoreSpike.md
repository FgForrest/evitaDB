# TrigramPostingStoreSpike — the posting-store fork

**Two independent questions.** Which structure holds `trigram → postings`, and at what posting
cardinality does a sorted `int[]` stop beating a `RoaringBitmap`?

Only the second is still open. The first was answered here, and then **overruled by the
implementation** — see *Where this was reversed* below, and do not re-derive the key-map result
without reading it.

## How it measures

**Key maps.** Four variants hold the *same* posting objects, so the difference between them is the key
structure and nothing else: a boxed `HashMap<Long, …>` built with the no-argument constructor (a
pre-sized one would land on a table twice as large and slander the baseline), a sorted `long[]` with
parallel `Object[]` under binary search, and an open-addressing long-keyed table at two target load
factors. Both load factors are reported even when they collapse onto the same power of two, because
*that* is the finding: the achievable load factor is decided by where `K` sits between two powers of
two, not by the target.

**Posting representations.** Every posting at or below a threshold `T` is a sorted `int[]`,
everything above it a `PersistentRoaringBitmap`. `T` is swept. Both representations describe the
same set, so only the encoding changes. Two columns exist to explain the sweep rather than record it:
`cont/key` is the container count, which is the mechanism behind the whole curve — a bitmap costs one
container per 65,536-wide chunk of the value-id space it touches, so a sparse posting over a wide space
carries overhead unrelated to its cardinality, and the crossover is therefore a function of `V`, not a
constant. `B / switch` is the heap the keys that changed representation in that band cost or saved;
the band where it turns positive **is** the crossover.

Heap is JOL deep-retained against an empty structure of the same type. Key overhead is a variant's heap
minus the heap of the posting objects themselves, so every variant pays for its own reference spine —
the only way the four numbers are comparable. `runOptimize()` is deliberately not called: it mutates
the bitmaps, and the engine does not call it either.

## Conclusions it produced

**Posting representation → hybrid, `T = 128`.** Sorted `int[]` up to `T`, Roaring above: −51%
postings heap on the demo corpus, −6.4% on the CMS corpus at the knee. A follow-up sweep over six groups
spanning a 31× range of `V` **falsified the first-guess scaling rule** `T = 32 × ⌈V/65536⌉` — wrong
coefficient *and* wrong variable. The crossover is linear in the containers a posting actually spans,
`T* ≈ 26 + 103·c` (R² = 0.965), which is exactly the cost algebra of `4n+16` against `F + c·C + ~2n`.
But the heap curve is flat enough that a single `T = 128` lands within **1.7% of every per-group
optimum**, and the whole stake is ~4% of the opt-in set's heap — no scaling machinery is worth that.

**Key structure → open-addressing long-keyed table, load ≤ 0.75.** 1.1–1.6 ns/lookup against 59–95 ns
for binary search over a sorted `long[]` (40–60×) and 4.5–28 ns for a boxed `HashMap`, at +0.4% heap
on large attributes. `-1L` is a safe empty sentinel because the trigram packing fills bits 0..62, so no
legal trigram is all-ones — `0` would **not** have been safe, since three `NUL` code points pack to
exactly zero and `NUL` is a legal character in an attribute value.

## Where this was reversed

**The key-map result did not survive contact with the shipped design.** The index ships on a
`TransactionalLongBPlusTree`, not the open-addressing table. The 40–60× probe advantage was real and
was not the deciding quantity: a published flat table has no MVCC story, no persistence story and a
rehash that lands unpredictably inside a mutation. This spike's own note that "the production key
structure must be a resizable/persistable tree" already pointed there — the two halves of its result
disagreed with each other. The class JavaDoc of `TrigramKeyIndex` records that its growth path is
deliberately absent rather than merely unimplemented, for the same reason.

## Why it is still live

**The `T = 128` threshold is an open issue**
([#1455](https://github.com/FgForrest/evitaDB/issues/1455)), and the latency half of the argument was
never measured: `T` bounds the worst-case linear probe length of the intersection, so 128 is 4× the
worst case of `T = 32`. That measurement needs a quiet box and was explicitly deferred. This is the
instrument for it.

## Running it

```shell
java -Xmx24g -Djol.magicFieldOffset=true \
  -Devita.trigram.corpusFile=/path/to/corpus.tsv \
  -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  io.evitadb.spike.trigram.TrigramPostingStoreSpike
```

## Related

- [`TrigramCorpusStatistics`](TrigramCorpusStatistics.md) — supplies the posting-cardinality
  distribution this sweep is read against.
- [ADR §35.2 "Forks closed by measurement"](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p8-trigram-substring-index.md).
