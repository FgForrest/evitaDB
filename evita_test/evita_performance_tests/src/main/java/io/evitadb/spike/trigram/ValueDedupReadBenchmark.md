# ValueDedupReadBenchmark — does the array container regress reads?

**Question.** The memory case for exact-sized array containers is settled. Do they cost anything on the
read path?

A representation change that saves 1.55 GB and slows every equality probe is not obviously a win, and
the question had to be answered before [#1486](https://github.com/FgForrest/evitaDB/issues/1486) could
name a design. JMH, JDK 21, 1 fork, 5×2 s measurement, structures built from census-measured value
shapes over **shared live bitmaps** — so what is compared is the key lookup, not the bitmap.

## What it measured

| probe | result |
|---|---|
| equality, primitive keys | `long[]` binary search **4–5× faster** than the live tree at every K — 1.1 vs 4.9 ns at K=1, 6.5 vs 28.2 ns at K=4096 |
| equality, string keys | exact front-coded column at **parity** through K=256; ~1.5× slower only at K=4096 (562 vs 371 ns), a stratum holding ~3% of reduced trees |
| ranges, between-bounds | ordered-column span wins **up to 120×** — 1.6 µs vs 199 µs at K=4096, 0.5 selectivity; worst case −150…−400 ns absolute at the lowest selectivity |

**Verdict: the container strictly improves the read path.** No blocking regression anywhere.

Two further findings that constrain the design rather than merely describe it:

- **The dictionary lever pays one ~330 ns owner resolution per query**, amortized across the fan-out
  reduced indexes. That constant is why the dictionary is a per-attribute opt-in for long-string domains
  and not a default.
- **The dictionary must not carry range evaluation in id space.** Ids are allocation-ordered, so a range
  predicate over them is meaningless; ranges stay in key space.

## Why it is still live

Acceptance criterion 4 of #1486 is "no read regression vs the tree baseline", measured by this
benchmark or an equivalent. It is also the read-side prototype the implementation starts from: its
equality and range search implementations over exact-sized containers are *working* code, and the
container to be built is the mutable version of what they already model.

## What it does not answer

**The write path.** Nobody has measured what a key-set mutation costs in a container against the live
tree, and that is the number that sets the representation threshold — copy-on-write of an O(K) array
against O(log K) node copies. #1486 makes that measurement its Stage 1, with an explicit gate: if
container mutation at K below the crossover is more than ~2× the tree's per-op cost, the shape has to be
reconsidered before any engine integration.

## Running it

```shell
java -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  org.openjdk.jmh.Main ValueDedupReadBenchmark -rf json -rff results.json
```

## Related

- [`ValueDedupCensus`](ValueDedupCensus.md) — supplies the value shapes the structures are built from.
