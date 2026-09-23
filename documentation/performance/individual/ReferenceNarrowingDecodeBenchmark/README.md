# ReferenceNarrowingDecodeBenchmark — reference-name narrowing, priced in isolation

Prices the decision recorded in [`2026-09-11-reference-name-narrowing`](../../../adr/2026-09-11-reference-name-narrowing.md):
carrying a projection's reference-name set into the Kryo deserializer so a `ReferencesStoragePart`
materializes only the names the caller can see and steps the stream past the rest.

It exists because the end-to-end effect could only be measured against a restored production
catalogue, on a shared box, where the run-to-run floor is wide enough to swallow anything under
~30 %. This benchmark takes the server out of the picture: one pre-serialized record, one decode,
two arms that differ **only** in the coverage bound by
`ReferenceDecodeCoverageContext.executeWithCoverage`.

## What is measured

The shape is the one observed in production, parameterized by `backReferences`: an entity holding a
single `parameter` reference plus N `products` back-references, written in the sorted order the
storage guarantees — so the narrowing finds the reference it keeps first and then has to walk past
everything else. Every back-reference carries an attribute, because a reflected reference inherits
its source's attributes; a skipped back-reference is **not** attribute-free, and assuming otherwise
understates the skip path. `backReferences=72217` is the worst single record seen on a large
e-commerce catalogue.

Four ops, two A/B pairs:

| op | what it covers |
|---|---|
| `decodeAll` / `decodeNarrowed` | the deserializer alone |
| `decodeAndIndexAll` / `decodeAndIndexNarrowed` | decode **plus** the `References` index build the fetch pipeline runs on the result |

The second pair exists because the index is sized from the decoded array, so it is a cost the
narrowing removes without ever appearing under the serializer's own profiler frame.

**`gc.alloc.rate.norm` is the oracle.** The decode's dominant cost is materializing `Reference`,
`ReferenceKey`, `AttributeValue` and name `String` instances that are then discarded; allocation per
op came back byte-identical across every fork and across two independent runs. Time is the secondary
signal — this box is shared, and it is the noisier of the two.

Config: `-f 3 -wi 3 -w 1 -i 5 -r 1 -bm avgt -prof gc`, JDK 17.0.20 (OpenJDK 64-Bit Server VM,
17.0.20+8), JMH 1.37, `-Xmx4g`.

## Results

### Allocation (the oracle) — 2.9× on decode, 3.2× with the index build

`gc.alloc.rate.norm`, B/op:

| backReferences | op | unnarrowed | narrowed | reduction |
|---|---|---|---|---|
| 1 000 | decode | 399 004 | 139 075 | **−65.1 %** (2.87×) |
| 1 000 | decode + index | 439 410 | 139 302 | **−68.3 %** (3.15×) |
| 10 000 | decode | 3 963 024 | 1 363 061 | **−65.6 %** (2.91×) |
| 10 000 | decode + index | 4 348 780 | 1 363 290 | **−68.7 %** (3.19×) |
| 72 217 | decode | 28 601 004 | 9 824 635 | **−65.6 %** (2.91×) |
| 72 217 | decode + index | 31 436 460 | 9 824 861 | **−68.7 %** (3.20×) |

The ratio is flat across three orders of magnitude of cardinality, which is what a per-reference
saving should look like.

### The `References` index build collapses

Subtracting the two ops isolates it:

| backReferences | index build, unnarrowed | index build, narrowed |
|---|---|---|
| 1 000 | 40 406 B/op | 227 B/op |
| 10 000 | 385 755 B/op | 229 B/op |
| 72 217 | 2 835 455 B/op | 226 B/op |

It goes from scaling with the record to a flat ~227 B — the index now covers the handful of
references the projection asked for. This is the cost that never showed under the serializer's own
frame and that the end-to-end profile later measured at 0.01 % of JVM CPU.

### Time — 1.6× to 1.8× faster

`avgt` µs/op, 3 forks:

| backReferences | op | unnarrowed | narrowed | delta |
|---|---|---|---|---|
| 1 000 | decode | 102.87 ± 21.47 | 60.63 ± 4.71 | −41.1 % |
| 1 000 | decode + index | 94.28 ± 3.50 | 62.60 ± 0.67 | −33.6 % |
| 10 000 | decode | 922.99 ± 46.51 | 549.61 ± 49.40 | −40.5 % |
| 10 000 | decode + index | 1 002.79 ± 29.56 | 618.89 ± 15.82 | −38.3 % |
| 72 217 | decode | 7 445.45 ± 307.79 | 4 599.85 ± 209.44 | −38.2 % |
| 72 217 | decode + index | 8 691.63 ± 331.43 | 4 860.17 ± 214.64 | **−44.1 %** |

The 72 217-reference `decode + index` figure is the one to compare against the end-to-end query
measurement (p50 −44.7 %) — they agree, which is the point of running this at all.

## The number that matters most is the one that did *not* go to zero

A narrowed decode still allocates **≈136 B for every reference it walks past** — 138.9, 136.3 and
136.0 B per reference at the three cardinalities. That is the reference-name `String` plus its
backing `byte[]`, and the `AttributeValue` the skip path decodes and drops.

So narrowing flattens the cliff but does not remove it: the read is still `O(total references)`, not
`O(references asked for)`. That residual is exactly what the two in-format follow-ups target —
comparing Kryo's length prefix instead of decoding the name, and a length-aware skip in
`AttributeValueSerializer` — and what [#1554](https://github.com/FgForrest/evitaDB/issues/1554)
eliminates outright by splitting the record so the unwanted references are never in the stream.

## Verdict

Narrowing is worth keeping on its own terms: 2.9–3.2× less allocation and ~1.6–1.8× faster decode,
scaling flat with reference count, with no change to a single persisted byte. It is not the end of
the line — the per-walked-reference residual above says so — but it is the whole of the win that was
available without a storage-format change.

Regenerate with:
`java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main 'io\.evitadb\.spike\.ReferenceNarrowingDecodeBenchmark' -f 3 -wi 3 -w 1 -i 5 -r 1 -bm avgt -prof gc`
