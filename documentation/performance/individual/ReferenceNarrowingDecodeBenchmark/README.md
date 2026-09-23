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

Six ops over two costs and three coverages:

| op | what it covers |
|---|---|
| `decodeAll` / `decodeNarrowed` / `decodeKeyNarrowed` | the deserializer alone |
| `decodeAndIndexAll` / `decodeAndIndexNarrowed` / `decodeAndIndexKeyNarrowed` | decode **plus** the `References` index build the fetch pipeline runs on the result |

The second row exists because the index is sized from the decoded array, so it is a cost the
narrowing removes without ever appearing under the serializer's own profiler frame.

The three coverages are the three things a projection can bind:

| arm | coverage | what it stands for |
|---|---|---|
| `…All` | none | the read decodes the whole record |
| `…Narrowed` | `ofNames({parameter})` | the **name** axis - the projection does not ask for `products` at all |
| `…KeyNarrowed` | `of({parameter}, {products: 20 keys})` | the **key** axis - the projection *does* ask for `products`, by exact referenced primary key |

`admittedBackReferences` (default 20) is how many of the back-references the key arms admit: a page of
them, which is what a query naming exact keys at its filter's conjunctive root produces.

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

## Results — the key axis

Same record, same harness, different question: what does it cost to decode a collection the projection
**does** ask for, when it named the exact referenced primary keys it wants?

**The baseline here is `decodeAll`, not `decodeNarrowed`.** The name axis skips `products` entirely,
which it can only do for a projection that never asked for the collection. The workload this axis exists
for asks for it and wants twenty of them, so the name axis cannot help and the honest comparison is
against the full decode.

Measured on a different day and a different JDK from the name-axis tables above - JDK 21.0.12.1, JMH
1.37, `-f 1 -wi 3 -w 1 -i 5 -r 1 -bm avgt -prof gc`, `admittedBackReferences=20`. **One fork, so the
latency column is softer than the three-fork numbers above**; the allocation column is not.

### Allocation — 3.0× on decode, 3.3× with the index build

`gc.alloc.rate.norm`, B/op:

| backReferences | op | unnarrowed | key-narrowed | reduction |
|---|---|---|---|---|
| 1 000 | decode | 415 019 | 144 736 | **−65.1 %** (2.87×) |
| 1 000 | decode + index | 455 433 | 145 720 | **−68.0 %** (3.13×) |
| 10 000 | decode | 4 123 038 | 1 368 742 | **−66.8 %** (3.01×) |
| 10 000 | decode + index | 4 508 793 | 1 369 726 | **−69.6 %** (3.29×) |
| 72 217 | decode | 29 756 489 | 9 830 302 | **−67.0 %** (3.03×) |
| 72 217 | decode + index | 32 591 947 | 9 831 290 | **−69.8 %** (3.32×) |

Flat across three orders of magnitude, same as the name axis - and for the same reason, since both are
paying per reference walked rather than per reference kept.

### Time — 1.4× to 1.5× faster

`avgt` µs/op:

| backReferences | op | unnarrowed | key-narrowed | delta |
|---|---|---|---|---|
| 1 000 | decode | 82.85 ± 1.38 | 59.04 ± 5.02 | −28.7 % |
| 1 000 | decode + index | 86.37 ± 3.16 | 61.17 ± 3.44 | −29.2 % |
| 10 000 | decode | 796.38 ± 123.66 | 587.81 ± 44.99 | −26.2 % |
| 10 000 | decode + index | 876.02 ± 37.10 | 566.75 ± 49.09 | −35.3 % |
| 72 217 | decode | 5 971.20 ± 285.93 | 4 278.99 ± 78.41 | **−28.3 %** (1.40×) |
| 72 217 | decode + index | 7 349.60 ± 1 054.91 | 4 942.57 ± 866.45 | −32.8 % (1.49×) |

Read the `decode + index` row at 72 217 with its error bars in view: ±14 % on the baseline arm is the
widest spread in either table, and a confirmation run on the same box put the same pair at 7 208 µs and
4 172 µs. **Quote the key axis as ~1.4-1.5× on latency**, not as a point estimate.

That confirmation run is worth its own sentence, because it is what says the two columns are not equally
trustworthy. Between the two runs allocation moved by **5 B/op out of 29.8 MB** on the unnarrowed arm and
**1 B/op out of 9.83 MB** on the narrowed one, while latency moved by up to 11 %. The oracle is the same
one the name axis uses.

### The two axes land on the same floor, and that is the point

At 72 217 the key axis allocates 9 830 302 B and the name axis 9 824 654 B - **0.06 % apart**, although
the key arm materializes 20 references the name arm skips outright. Both come from this section's run,
which is why the name-axis figure sits 19 B from the 9 824 635 in the name-axis table above: that one is
its own earlier run, and reading one axis out of each would fold run-to-run drift into a 0.06 % result.
Those twenty cost 5 648 B between them, about 282 B each, which is what an admitted reference genuinely
costs to build.

Everything else in that 9.8 MB is the skip path: ~136 B for every one of the 72 217 references walked
past, whichever axis is doing the walking. So the key axis does not add a new cost or remove a different
one - it reaches the same floor from the other side, and the floor is the residual the next section is
about.

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

Narrowing is worth keeping on its own terms, on both axes and with no change to a single persisted byte:

| axis | applies when | allocation | time |
|---|---|---|---|
| name | the projection never asks for the collection | 2.9–3.2× | ~1.6–1.8× |
| referenced key | it asks for the collection, by exact key | 3.0–3.3× | ~1.4–1.5× |

Both scale flat with reference count, and both stop at the same floor. Neither is the end of the line —
the per-walked-reference residual above says so — but together they are the whole of the win that was
available without a storage-format change.

Regenerate with:
`java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main 'io\.evitadb\.spike\.ReferenceNarrowingDecodeBenchmark' -f 3 -wi 3 -w 1 -i 5 -r 1 -bm avgt -prof gc`
