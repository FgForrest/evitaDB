# Testing a `getHeapSizeInBytes` implementation

Any structure that reports its own memory footprint needs a test that compares that report against what the object
*actually* weighs on the heap. This document is for the person adding such a structure. It covers how to write the
test, the ownership rules the arithmetic must follow, and five traps that make a correct implementation look broken —
or, worse, let a broken one pass.

The tooling is `io.evitadb.utils.JolHeapSize` (test scope), built on
[JOL](https://openjdk.org/projects/code-tools/jol/). `LeafIndexHeapSizeTest` is the worked example to copy from.

## Measure, never restate

A size test can be written three ways and only one of them tests anything:

- **Deriving the expectation from the constants the implementation uses.** Passes for every formula, including a wrong
  one. This is how an `int[]` estimate that was **6× too large** once survived a full suite.
- **Asserting a literal captured on some past date.** Detects change, says nothing about correctness, and the usual
  response to a failure is to re-measure and overwrite the literal — which launders the regression into the baseline.
- **Asking the VM what the object weighs, on every run.** This is the only one worth writing.

```java
assertEquals(measuredHeapOf(index, "comparator", "pageStreamRegistry"), index.getHeapSizeInBytes());
```

## What counts as owned

The walk sees everything reachable; the arithmetic must charge only what the structure owns. Four rules decide, and
they are the same rules the production estimators follow:

1. **Never charge shared structure — but "shared" means shared *by contract*, not interned by the runtime.** Enum
   constants, `EmptyBitmap.INSTANCE`, the JVM-wide zero-length arrays and borrowed payloads are excluded. A boxed
   `Integer` is *not*: it is charged in full to **each** holder, because whether the JVM hands back a cached instance
   moves with `-XX:AutoBoxCacheMax`, and a JVM flag must not decide what a memory reading says.
2. **Structure shared with a superseded version is charged in full, in both versions.** The test is who outlives whom:
   a path-copied predecessor is garbage-in-waiting and the surviving version is its sole owner.
3. **Where several figures are defensible, report the higher one.** Under-reporting memory is the failure that leads
   to under-provisioning.
4. **Ownership is static per class.** No traversal order, no global already-counted set — each class charges what its
   own fields hold, so a figure never depends on who asked first.

Borrowed subgraphs are named at the call site, by field name, and handed to the walker as shared roots so they are
subtracted **by identity**:

```java
JolHeapSize.ownedSize(index, excluded(index, "comparator", "normalizer"));
```

Naming them in the test rather than exposing accessors is deliberate: it keeps flush bookkeeping off the production
API, and it puts each structure's ownership decision in one legible line instead of a comment that rots.

## Trap 1 — a JVM-shared instance belongs to whichever walk touches it first

This is the trap that costs the most time, because it produces a test that passes and describes something untrue.

`Integer.valueOf(0)` — which every empty structure's memoized element count resolves to — is one instance shared by
the whole JVM. A walk dedupes by identity and therefore charges it **once**, no matter how many of your fields point
at it. Rule 1 charges it to **every** holder. So for a structure with `N` holders of the same cached box:

```
reported − measured = (N − 1) boxes
```

Measured on the real fixtures: the sort index has three counters sharing one box and over-reports by **2 boxes**; the
chain index has two and over-reports by **1**; every single-holder structure measures **exactly**.

Naming the box as a shared root subtracts it from the walk and makes the gap `N` instead of `N − 1`. **No fixture in
this suite needs that**: all four single-holder structures measure exactly, and both multi-holder gaps are fully
explained by `N − 1` with nothing named. Applied blanket, naming manufactures a 16-byte divergence for every
single-holder structure that had none — and the natural next step is to write that divergence up as a property of the
code. Four assertions in `LeafIndexHeapSizeTest` were once wrong in exactly this way, and both genuine gaps were
inflated by one box each.

**Rule: do not name a JVM-shared box.** If you ever hit a measurement that genuinely flickers, the `extraRoots`
overload of `measuredHeapOf` is how to name one — but demonstrate the flicker first. Prophylactic naming is neither
free nor neutral.

The cheap way to sidestep the whole thing is to seed fixtures above the boxed-`Integer` cache — see
`AUTOBOX_CACHE_CEILING`. Only genuinely empty structures cannot, since their size really is `0`.

## Trap 2 — never traverse a `Class`

Use `ClassBlindGraphWalker`, not JOL's `GraphWalker`. JOL gates the record and the stack push on one identity set:

```java
if (e != null && visited.add(e)) { ... }        // GraphWalker
```

Every object is therefore recorded once, under **whichever path reached it first**, and a visitor can only classify
that one path — it cannot stop the descent. Filtering class-borne objects out of the *sum* afterwards is therefore not
enough: the class subgraph has already marked them visited, so the structure's own reference to the same object is
discarded as already-seen and **its owner silently stops being charged for it**.

A `Class` enters the graph as an ordinary field value (JOL skips static fields, so never through one), and descent then
continues through the class's *instance* fields — among them `reflectionData`, a `SoftReference` to a lazily built
cache of `Field[]`, `Method[]` and annotation data. Whether that cache exists depends on what reflected on the class
earlier and whether the collector has cleared it since, so a measurement that descends into it depends on the history
of the entire JVM.

Refusing to descend fixes both, in the safe direction: an object reachable *only* through a class was never charged and
still is not, while one the structure genuinely holds is now always charged to it. Measured figures can only rise.

## Trap 3 — a green single-class run is not evidence

Run the wide suite before believing a heap test. What a walk reaches depends on JVM history — the `reflectionData`
mechanism in Trap 2 is one proven route — so a figure that is stable in isolation can move inside a full run. It has
happened here: three tests once passed alone and failed by exactly 16 bytes in the full suite. That particular cause
was never isolated, so do not reason from the example; take only the rule.

```shell
mvn -o -pl evita_test/evita_functional_tests test -P unitAndFunctional \
    -Dtest='*HeapSize*,*Map*,*BPlusTree*,*Column*,*Bitmap*,*Cardinality*,*Jol*,*Champ*'
```

A flaky measurement does not merely add noise — it holds wrong assertions in place, because the run that would have
falsified them is the one that happens to agree.

## Trap 4 — an owned sub-structure's exclusions become the owner's exclusions

A structure that excludes scaffolding *for itself* still reaches that scaffolding when somebody else owns it. A
`RangeIndex` does not charge its own `pageStreamRegistry` or `ranges.transactionalLayerWrapper`, and an element-keyed
B+ tree does not charge its `keyExtractor` lambda — so an index holding either must name them again, through the
nested path:

```java
private static final String[] EXCLUSIONS = {
    "validityIndex.pageStreamRegistry", "validityIndex.ranges.transactionalLayerWrapper",
    "priceRecords.keyExtractor"
};
```

Miss one and the owner appears to **under**-report by a fixed amount, with the shortfall identical for an empty and a
seeded fixture — which is the tell that separates this from a real per-element bug. Four of these accounted for
120 of the 128 bytes missing across the first run of the price-index suite; the remaining 8 were a genuine defect (an
inherited `long` id that the arithmetic never charged). Expect this to grow with nesting depth: a container index owns
far more sub-structures than a leaf one.

## Trap 5 — state every divergence with its magnitude and its slope

Where the reported figure legitimately differs from the measurement, assert **how much**, never merely the direction:

```java
assertExceedsMeasuredHeapBy(index.getHeapSizeInBytes(), 2L * layout.sizeOfObject(Integer.BYTES), index, EXCLUDED);
```

Then pin that the gap does **not grow with the data**. A fixed gap is a documented convention; a gap that scales with
size is a defect, and only the second assertion tells them apart. Two real defects were found exactly this way — a
cardinality index under-reporting 24 bytes per B+ tree separator, and boxed-column trees over-reporting 32 bytes per
leaf boundary. Both were invisible at one leaf and both grew.

## Checklist for a new structure

1. Implement `getHeapSizeInBytes` following the four ownership rules; charge what your own fields hold.
2. Write the test against `JolHeapSize.ownedSize`, naming borrowed subgraphs by field name.
3. Seed fixtures above `AUTOBOX_CACHE_CEILING` unless the case under test is genuinely empty.
4. Name the exclusions of every sub-structure you own, through their nested paths (Trap 4).
5. Assert **exact** equality first. Only accept a divergence you can explain, and then assert its magnitude.
6. Add a second fixture an order of magnitude larger and assert the gap did not grow.
7. If the structure lazily builds a cache, measure cold **and** warm — the step up on first read is real occupancy.
8. Run the wide suite, not just your class.

## Where the pieces live

| What | Where |
|---|---|
| Measurement entry point | `io.evitadb.utils.JolHeapSize` (test scope) |
| Class-blind walker, and why it exists | `org.openjdk.jol.info.ClassBlindGraphWalker` (test scope) |
| VM layout constants, measured not assumed | `io.evitadb.utils.VMLayout` |
| Worked example | `io.evitadb.index.LeafIndexHeapSizeTest` |

`-Djol.magicFieldOffset=true` is set on the surefire `argLine`; without it JOL cannot resolve field offsets on records
and JDK-internal types, and measurements fail rather than silently under-report.
