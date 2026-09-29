# UniqueAttributeLookupBenchmark — does per-scope unique resolution cost the hot path?

**Question.** [#1584](https://github.com/FgForrest/evitaDB/issues/1584) rewrote how `attributeEquals` and
`attributeInSet` on a unique attribute pick their index. The lookup now walks the requested scopes in the order
`scope(...)` lists them, and in each scope it reads the unique index that scope declares: the catalog's where the
attribute is globally unique, the collection's where it is unique within the collection. Fetching one product by
its code or its URL is the hot path of every front store. Does the rewrite make it slower?

It is an A/B harness: the same class is built into a jar of the previous code (`6aaa58840`, the `dev` code path for
unique lookups) and into one of the change (`e0d66ac31`), and the two are run interleaved.

## Workload

A production e-commerce catalog: 130,033 products, 40,344 of them archived, locales `cs` and `sk`. Two
attributes, picked by the census in `main`:

| attribute | type | uniqueness in LIVE and ARCHIVED | index read | plan |
|---|---|---|---|---|
| `code` | `String` | unique within the collection | the collection's unique index | index scan |
| `url` | `String`, localized | unique within the catalog per locale | the catalog's unique index | prefetch |

1,000 LIVE owners are sampled at an even stride (every 130th in primary-key order). `EQUALS` walks 1,000
single-value queries round-robin, and `IN_SET` walks 50 queries of 20 values. `url` is queried in `cs` with
`entityLocaleEquals(cs)`. Every prepared query is checked against the owners it was built from before measuring,
and all of them matched on both jars.

## What it measured

JMH, JDK 21, 1 fork × 5 × 2 s per trial, 2 interleaved rounds, busy gate below 6 % before every launch. The table
shows the mean over both rounds in µs/op, with the min–max of all iterations in brackets:

| attribute | shape | scopes | before | after | ratio |
|---|---|---|---|---|---|
| `code` | `EQUALS` | default (LIVE) | 4.98 [4.09–6.24] | 4.89 [4.13–6.67] | 0.98 |
| `code` | `EQUALS` | `scope(LIVE, ARCHIVED)` | 5.40 [4.53–6.82] | 5.17 [4.44–6.10] | 0.96 |
| `code` | `IN_SET` ×20 | default (LIVE) | 24.0 [21.2–27.8] | 24.4 [21.8–31.3] | 1.02 |
| `code` | `IN_SET` ×20 | `scope(LIVE, ARCHIVED)` | 26.4 [22.8–33.8] | 25.7 [23.4–31.2] | 0.97 |
| `url` `cs` | `EQUALS` | default (LIVE) | 21.1 [17.7–26.8] | 19.8 [16.9–24.7] | 0.94 |
| `url` `cs` | `EQUALS` | `scope(LIVE, ARCHIVED)` | 247.8 [224.4–268.6] | 245.9 [220.0–322.6] | 0.99 |
| `url` `cs` | `IN_SET` ×20 | default (LIVE) | 262.6 [224.8–349.2] | 274.9 [223.0–379.7] | 1.05 |
| `url` `cs` | `IN_SET` ×20 | `scope(LIVE, ARCHIVED)` | 526.1 [475.9–668.3] | 537.6 [472.2–755.5] | 1.02 |

**Verdict: the rewrite is flat.** The ranges overlap in every row, and every single-value lookup stays within ±6 %
in each round.

## What it found instead: `entityLocaleEquals` across two scopes

`url` with `scope(LIVE, ARCHIVED)` costs **12×** the default-scope lookup (≈ 247 against ≈ 20 µs), while `code`
pays only 8 % for the same second scope. Both jars show it, so the rewrite did not cause it. async-profiler
(CPU every 1 ms, only stacks under the benchmark method) shows where the time goes:

| frame | default scope | `scope(LIVE, ARCHIVED)` |
|---|---|---|
| `EntityLocaleEqualsTranslator` (inclusive) | 4.0 % | 82.9 % |
| `BaseBitmap#getContentHash` (inclusive) | 0 % | 67.5 % |
| `PersistentRoaringBitmap#or` (inclusive) | 0 % | 14.4 % |

`EntityLocaleEqualsTranslator` asks every scope's global index for `EntityIndex#getRecordsWithLanguageFormula`,
a `LocaleFormula` (a `ConstantFormula`) over that index's own transactional bitmap. In the default scope there is
one such formula, and its hash token is the bitmap's id. With two scopes, `FilterByVisitor#joinFormulas` combines
them through `FormulaFactory#or(Supplier, Formula...)`, which ORs constant inputs eagerly into a new `BaseBitmap`
(the comment says this "enables prefetching for simple cases"). The `ConstantFormula` around the union computes
its hash at construction through `AbstractFormula#bitmapIdentityToken`. The union is not a
`TransactionalLayerProducer` with a stable id, so the token falls back to the content hash. The hash turns the
union of every product in the locale in both scopes into an `int[]` (`ScalarBitmapKernels#extract`, 42 % self) and
runs XXH3 over it (11 % self, plus 7 % in its `Unsafe#getLong` reads). This happens on every query. The memo in
`BaseBitmap#getContentHash` does not help, because each query creates a new union instance. The plan then
prefetches the one entity the `url` lookup found (telemetry: `prefetch=yes`), and the prefetch costs 10 % of the
time, against 67 % for the hash.

The fix belongs in how formulas identify themselves, not in this harness. An eager union of inputs that all have
stable ids could derive its token from theirs. Alternatively, the per-scope locale bitmaps could stay an
`OrFormula`, provided the plan can still prefetch. Either way it changes a result-cache key, so it needs its own
issue and tests.

## Running it

```shell
# census: the collection's locales, archived count and every attribute unique in some scope
java -Xmx24g -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  io.evitadb.spike.UniqueAttributeLookupBenchmark <storageDirectory> <catalogName> [entityType]

java -Xmx2g -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
  'io\.evitadb\.spike\.UniqueAttributeLookupBenchmark' -p storageDirectory=<dir> -p catalogName=<name> \
  -p attribute=url -p locale=cs -p scopes=LIVE,LIVE_AND_ARCHIVED -rf json -rff results.json
```

To profile, attach async-profiler through `-jvmArgs "-agentpath:<libasyncProfiler.so>=start,event=cpu,interval=1ms,collapsed,file=<out>"`
(`-prof async` fails on long file names), and keep only the stacks containing `UniqueAttributeLookupBenchmark.lookup;`.

## Related

- [`ReferenceHavingNullBenchmark`](ReferenceHavingNullBenchmark.java): the other half of the #1584
  measurements, `referenceHaving(R, attributeIsNull(a))` on the same catalog.
- `documentation/adr/2026-09-25-attribute-is-null-in-reference-having.md`: the record of the change measured
  here.
