<!--
This file is the bench agent's measurement report, copied verbatim from the (git-ignored) working notes it was
written in, with only this note prepended. Two things differ from that folder:

- The raw JMH result files (`*.json`, `*.log`), the harness scripts and the replay fixtures it cites are NOT
  kept in the record — `.claude/rules/adr.md` keeps conclusions and drops the bytes — and none of them survives
  anywhere else either. Every such name in the text below is the label of a run or a fixture, never a path to
  something present: the "Superseded files" table is a statement about which runs the numbers came from, and
  the fixtures are described in `../census/REPORT.md` §4.
- "What was not measured" says no before/after query benchmark was run. That was true of the bench chain; the
  end-to-end query replay was run separately and is quoted in `../README.md` under *Verification — End to end*,
  together with the bisect that reverted the word-batched scatter the "Scatter shapes" section investigates.
-->

# SIMD kernels for the vendored roaring bitmap — measured (issues #1541–#1544)

JMH A/B of every candidate kernel against the arm that runs today, on synthetic shapes and on container
operands replayed from a production retail catalog. Ratios are the finding; absolutes are ±10 %.

## The short version

| question | answer |
|---|---|
| #1542, ≥ 3× on the popcount kernel | **not met**. `cardinality` is 1.23×, `andCardinality` is **0.83× — a loss**. |
| #1543, ≥ 2× on equal-size 256–4096 arrays | **not met**. Best equal-size reading is 1.41×, and only two *identical* arrays reach 2.87×. On real operands no kernel beats the vendored merge at all. |
| #1544, any measured gain on the probe | **refuted**. The gather probe is 0.89× of the scalar loop on 900 real operand pairs. |
| what *is* worth doing | four things the issues never named: a lazy **array** union (**1.247×** weighted over the real union population, 4.19× on the 60 % of unions with 2–4 inputs), a constant-time container hash (**71.5×** on real containers), an empty-block-skipping extraction (**1.72×** on real pre-repair bitmaps), and a word-batched scatter (**1.42×** on the operation that is 92.7 % of all container work). |

The four wins have one thing in common: **none of them is a vector kernel.** Three are a change of algorithm and
one is a loop that stops early. The Vector API earns its place in exactly two arms measured here — the fused OR
at 1.39× and the empty-block scan that fronts the extraction — and in the first case only by a third over the
same loop written scalar.

Read that as the finding, not as a disappointment: the measurements are what redirected the work from the four
issues' kernels to the four changes above, and every one of the redirections came from replaying real operands
rather than from a synthetic shape.

## Box, build and how a run was gated

- AMD Ryzen AI 9 HX 370 (Zen 5, 12c/24t), AVX-512 with VPOPCNTDQ, VBMI2, BITALG, VP2INTERSECT.
- OpenJDK 21.0.12+8-1-24.04-Ubuntu, `jdk.incubator.vector` present, `LongVector.SPECIES_PREFERRED` = 512-bit.
- JMH 1.37, average time, one fork unless a table says otherwise, 3×1 s warmup and 5×1 s measurement unless
  stated.

Every run went through the gate script, which is fail-closed on four things: the shaded jar must contain the
benchmark class, the box must be under 20 % busy over a 15-second window with no competing JVM, the incubator
module must reach the **forked** JVM, and the result file must contain at least one measurement. Proof of the
third, from every result JSON:

```
--add-modules=jdk.incubator.vector --add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED --add-modules=jdk.incubator.vector
```

The fourth check exists because of a real failure: passing `-jvmArgsAppend` twice makes JMH hand the first
value to the fork as one unsplit token, every fork dies with `Unrecognized option`, **and JMH still exits 0**
having written a result file holding an empty array. The auto-vectoriser control ran that way and reported
success while measuring nothing. Extra fork flags now go through one `JMH_FORK_ARGS` variable folded into a
single `-jvmArgsAppend`, with the module written in its one-token `=` form.

The gate also samples `/proc/stat` **across the whole run**, not only before it, because a pre-run sample
cannot see a profiler that starts two minutes into a twenty-minute family. Every table below carries the
whole-run figure, and a family that averaged over 20 % was re-run when the box went quiet.

**The gate matches competing processes by executable.** An earlier version matched on the command line and
stalled for twenty minutes on a Node.js code review whose *prompt text* contained the word `surefire`, at 2 %
CPU. A process now counts only when its `comm` is `java` or `mvn`, or its argv[0] ends in `/bin/java`.

## How to read a number here

**Units differ by family and are stated in every table.** The synthetic grids report nanoseconds per
invocation of one kernel. The replays report **microseconds for a whole batch** — 300 array pairs, 900 probe
pairs, 300 scatter pairs, or every union in one stratum — because a per-pair benchmark would have measured
JMH's own loop. Per-pair figures are given explicitly wherever a batch is reported; there is no
`@OperationsPerInvocation`.

**Every fixture names itself in the result.** Shapes are `@Param` values, so they are part of the benchmark's
identity, reach the forked JVM and are written into the result JSON. An unusable fixture throws rather than
falling back to a default. Each fixture also re-runs every variant against the arm that runs in production at
setup and refuses to produce a timing if any of them disagrees.

**The strata of the union replay are not the workload.** The dump samples a fixed number of unions per
input-count bucket, so a per-union cost is weighted by the census population, never averaged across the file.

---

# Family 1 — bitmap word kernels (#1542)

Two random `long[1024]` arrays. `family1-cardinality-precise` ran 3 forks × 10 × 2 s; `family1-store-precise`
ran 2 forks × 10 × 1 s. Whole-run busy 10.3 % and 14.1 %.

| kernel, 50 % density | scalar (today) | Vector API | ratio |
|---|---|---|---|
| `cardinality` | 58.5 ns | 47.5 ns | **1.23×** |
| `andCardinality` | 72.4 ns | 86.9 ns | **0.83× — a loss** |
| `cardinalityInRange` | 56.6 ns | 40.7 ns | 1.39× |

At 5 % density the same three read 1.18×, 0.83× and 1.38×, so none of this is a density artefact.

## The scalar baseline is already SIMD, and that is the whole story

The auto-vectoriser control answers why the issue's 3× estimate did not survive. With `-XX:-UseSuperWord` the
*same scalar loop* goes from 58.5 ns to 216 ns.

| arm | ns | against plain scalar | against today's scalar |
|---|---|---|---|
| scalar, `-XX:-UseSuperWord` | 216 | 1.00× | — |
| scalar, as it actually runs | 58.5 | 3.7× | 1.00× |
| explicit `LongVector` kernel | 47.5 | 4.5× | 1.23× |

**C2 is responsible for 3.7× of the 4.5×.** The issue's 3× gate is met only against a baseline that does not
exist on this JVM. Against the loop that actually runs, the explicit kernel is worth 23 % on `cardinality` and
is *negative* on `andCardinality`, where the extra load per lane is not paid for by the lane-wise popcount.

## Fusion is the real win, and most of it is not vectorisation

| kernel, 50 % density | today | one fused scalar pass | one fused vector pass |
|---|---|---|---|
| AND into an existing destination | 215 ns | 157 ns (1.37×) | 143 ns (1.50×) |
| OR with the clone, allocation inside the timed region | 468 ns | 419 ns (1.12×) | 336 ns (1.39×) |

Collapsing today's count-pass-then-store-pass into one loop is worth 1.37× with no vector code at all; the
vector form adds a further 9 %. For the OR the 8 KiB allocation is most of the cost and neither arm removes it:
the vector fused pass is 1.39×, which is the best result the Vector API achieves anywhere in this report.

**Verdict.** #1542's gate is not met. `cardinality` and `cardinalityInRange` are modest wins, `andCardinality`
is a loss and must not be vectorised, and the fused store is worth shipping mostly as a scalar rewrite.

---

# Family 2 — array container intersection (#1543)

## The replay decides, and it says no

300 real `iand(Array, Array)` operand pairs from the production catalog. Median shorter side **4 values**,
which is below the block size of every vector kernel in the family. One invocation is the whole batch of 300;
3 forks × 10 × 2 s, whole-run busy 5.9 %.

| arm | batch | per pair | against today |
|---|---|---|---|
| **today, materialising** (`Util.unsignedIntersect2by2`) | 25.27 µs | **84.2 ns** | 1.00× |
| broadcast probe H, count only | 24.88 µs | 82.9 ns | 1.02× |
| hybrid (H below 64, summed above), count only | 26.17 µs | 87.2 ns | 0.97× |
| broadcast probe H, materialising | 31.14 µs | 103.8 ns | 0.81× |
| **today, count only** (`unsignedLocalIntersect2by2Cardinality`) | 36.81 µs | 122.7 ns | — |
| broadcast probe, reloading (H512b) | 80.87 µs | 269.6 ns | 0.31× |
| broadcast probe, cached max (H512c) | 86.98 µs | 289.9 ns | 0.29× |
| summed all-pairs (Ds) | 124.68 µs | 415.6 ns | 0.20× |
| branch-free merge (B) | 295.13 µs | 983.8 ns | 0.09× |

Those figures are the three-fork repeat (`replay-array-intersect-precise`); the single-fork first pass agrees
within 2 % on every arm except the two cached ones, where it read 82.5 µs with a 28 % error against the repeat's
87.0 µs at 1.3 %. The repeat is what the table quotes.

**No kernel beats the vendored merge plus galloping dispatch on the production mix.** The broadcast probe ties
it while only counting — 1.48× against today's *counting* path, which is the honest count-to-count comparison —
but the engine calls `andCardinality` nowhere, so that comparison has no caller. For the materialising path
that the engine does use, today wins.

## The synthetic grid, which explains the mechanism

Equal-size and skewed shapes, ns per invocation, against `Util.unsignedLocalIntersect2by2Cardinality`.
Whole-run busy 13.2 %.

| fixture | today (A) | all-pairs D | summed Ds | broadcast H | cached H512c |
|---|---|---|---|---|---|
| eq256_50 | 209 | 0.5–0.6× | — | 150 (1.39×) | 159 (1.31×) |
| eq1024_50 | 861 | 0.5–0.6× | — | 612 (1.41×) | 810 (1.06×) |
| eq4096_50 | 2,958 | 0.5–0.6× | — | 2,512 (1.18×) | 2,611 (1.13×) |
| eq4096_100 | 6,077 | — | — | 2,121 (2.87×) | 2,513 (2.42×) |
| skew16_4096 | 587 | — | — | 81.3 (7.22×) | **50.6 (11.60×)** |
| skew64_1024 | 192 | — | — | 196 (0.98×) | **51.7 (3.71×)** |
| skew256_4096 | 807 | — | — | 744 (1.09×) | **203 (3.97×)** |
| skew64_4096 | 647 | — | — | 593 (1.09×) | **80.3 (8.06×)** |

The issue's own mechanism — all-pairs 8×8 on `ShortVector.SPECIES_128` — loses at **every** size, 0.5–0.6×.
Unrolling the rotation loop onto constant shuffles, reassociating the mask ORs into a tree and dropping the
mask combination entirely in favour of summed lane counts each recover some of it, and none of them reaches
parity on equal sizes. The equal-size 2× gate is met only by `eq4096_100`, two identical arrays, which is not
a workload.

## Two things the synthetic grid got wrong, and why that matters

**The cached-max variant is the best kernel on every synthetic shape and 3.5× slower than the plain one on real
operands.** Reproduced across 3 forks with ±1.3 % error, so it is not noise. The two differ in one thing: the
plain kernel keeps the B block in a register across many probes, the cached one reloads it per probe. On a
synthetic fixture the same pair is measured millions of times and every reload is an L1 hit, so the extra loads
cost nothing — `eq4096_50` does 32× more loads at parity, which only makes sense if they are free. On the
replay, 300 different pairs stream past and those loads miss. **Best supported explanation, not proven**: a
single-pair synthetic fixture hides the cache behaviour that decides the result, and the disassembly-driven
reasoning that produced H512b and H512c optimised the regime the fixture exposed rather than the one the engine
runs.

**The disassembly says there is nothing to fix in the vector code.** Decoded from raw `[MachCode]` with
`objdump` (a capstone `hsdis` truncates at the first EVEX k-register instruction), the inner probe is six
instructions — `vmovdqu32`, `vpbroadcastw`, `vpcmpeqw` into a k-register, `kortestd`, `setne`, `add` — with the
mask never leaving a k-register and no allocation or call in the loop. The cost is the scalar scaffolding
around it: two safepoint polls, a bounds check on `b[ib + lanes - 1]` that C2 cannot eliminate, and register
pressure that parks array references in XMM registers and pulls them back through `vmovq`. The kernel executes
roughly six times fewer instructions than the scalar merge and takes the same time, because the merge runs near
five instructions per cycle while the probe's variable-trip advance loop mispredicts.

## Orientation and in-place are a genuine fork

The broadcast probe walks one side and blocks the other, so the shorter side must be the one walked. Its
materialising form writes at or behind its read cursor, which is what `ArrayContainer.iand` needs when it passes
its own `content` as both input and output. **Orienting breaks that**: when the shorter side turns out to be the
other operand, the destination is no longer the array being walked and the writes clobber blocks the probe has
not read. A caller cannot have both; it must buffer. Found by a test, not by reading. The compress-emitting
all-pairs variant does not share the problem: its store is lane-masked to the match count rather than writing a
whole vector, so it needs no slack past `min(la, lb)` and tolerates `out == a`. Checked at every width up to a
full container with identical operands - the shape in which every lane matches.

Both branch-free arms also need one element of slack past the intersection cardinality, because they store the
candidate before they know it matched.

**Verdict.** #1543's mechanism is refuted at every size, and no replacement beats today's materialising path on
real operands. The broadcast probe is the only kernel worth remembering, and only for a counting API that has no
caller and for strongly skewed inputs, where it reaches 3.7–11.6×.

---

# Family 3 — array against bitmap probe (#1544)

Today's branch-free bit test per value against a gather kernel: eight values widened to `int` lanes, their word
indices through an `int[8]` scratch, one `VPGATHERQQ`, a per-lane variable shift and an accumulate.

**On 900 real operand pairs** — the three combinations that present one sorted `char[]` and one 1024-word
bitmap, median 46 probed values — one batch of 900, whole-run busy 7.3 %:

| arm | batch | per pair | ratio |
|---|---|---|---|
| today, scalar probe | 205.59 µs | 228.4 ns | 1.00× |
| gather probe | 229.93 µs | 255.5 ns | **0.89× — a loss** |

The index round trip through memory is the reason: it is a store and a load per eight probes that the scalar
arm does not pay, and at a median of 46 values there are not enough probes to amortise it.

**Fidelity caveat.** Those three combinations share an operand *shape*, not an implementation — `iand(Array,
Bitmap)` probes and materialises, `andNot(Bitmap, Array)` clears bits. The arms price the probe kernel on real
operand shapes, not the three operations end to end.

**Verdict.** #1544 is refuted. The gather probe is a loss on the shapes the engine actually probes.

---

# Family 4 — set-bit extraction, swept by density

`Util.fillArray`'s `tzcnt`/`blsr` loop against CRoaring's `bitset_extract_setbits_avx512_uint16` expressed in
the Vector API, at the densities the callers sit at. Every `fillArray` / `fillArrayAND` site is on a branch that
has already decided the result fits an array container, so it never sees more than 4096 of 65,536 values —
6.25 %. The only uncapped site is `fillLeastSignificant16bits`, the 32-bit form behind `toArray()`.

Whole-run busy 9.5 %. Byte-compress is the stronger of the two vector forms; ratios against the scalar loop:

| density | values | scalar | short compress | byte compress | 32-bit scalar | 32-bit byte compress |
|---|---|---|---|---|---|---|
| 1.6 % | 1,049 | 778 ns | 0.82× | 0.68× | 812 ns | 0.74× |
| 3.1 % | 2,031 | 1,625 ns | 1.04× | 1.32× | 1,267 ns | 1.22× |
| **6.25 %** | 4,096 | 2,456 ns | 1.27× | **1.75×** | 2,139 ns | 1.65× |
| 12.5 % | 8,192 | 4,337 ns | 2.04× | 3.13× | 4,029 ns | 1.37× |
| 25 % | 16,384 | 8,055 ns | 2.42× | 5.79× | 8,034 ns | 3.11× |
| 50 % | 32,768 | 20,058 ns | 9.23× | **10.48×** | 19,037 ns | 4.51× |

**The crossover is between 1.6 % and 3.1 %, well below the 6.25 % cap**, so the capped 16-bit sites do sit on
the winning side — byte-compress is 1.75× exactly at the cap. That reverses the expectation the family was
built on, which assumed the crossover sat at the cap itself.

It is also the wrong question, and the next section says why. The callers that reach `fillArray` in the profile
are not near the cap at all: the median real pre-repair bitmap holds 36 values of 65,536, a density of
**0.055 %**, thirty times sparser than the sparsest point swept here, and the mean over the population is
0.65 %.

That framing turned out to be the wrong question anyway — see family 6, where the real pre-repair bitmaps put
the whole population three orders of magnitude sparser than this sweep assumed.

---

# Family 6 — extraction at the density a union actually produces

A JFR profile of the production facet workload puts `Util.fillArray` at 5.0 % of query CPU, reached almost
entirely through the lazy-union repair path — so on bitmaps that are about to demote back to arrays. The census
of 300 real pre-repair bitmaps says what those look like: **median 36 values and median 12 non-zero words out of
1024**, with 89.7 % of them leaving at least 93.75 % of their words empty. That is three orders of magnitude
sparser than family 4's sweep assumed.

At that density the scalar decoder is not the cost — the 1024-word traversal looking for the handful of live
words is. One `LongVector` compare rules out eight words and its mask names the survivors.

**On the 300 real pre-repair bitmaps**, one batch of 300, whole-run busy 6.0 %:

| arm | batch | per bitmap | ratio |
|---|---|---|---|
| today, scalar `fillArray` | 193.42 µs | 644.7 ns | 1.00× |
| **empty-block skipping** | **112.56 µs** | **375.2 ns** | **1.72×** |
| VBMI2 compress | 141.86 µs | 472.9 ns | 1.36× |
| today, 32-bit form | 233.90 µs | 779.7 ns | 0.83× |
| **empty-block skipping, 32-bit** | **107.19 µs** | **357.3 ns** | **1.80×** |
| VBMI2 compress, 32-bit | 182.59 µs | 608.6 ns | 1.06× |

The synthetic sweep shows where it stops paying — it is a sparsity optimisation and it inverts on dense input:

| values of 65,536 | scalar | skipping | compress |
|---|---|---|---|
| 1 | 361 ns | 68.2 ns (5.29×) | 271 ns (1.33×) |
| 16 | 395 ns | 80.6 ns (4.90×) | 328 ns (1.20×) |
| 64 | 377 ns | 149 ns (2.53×) | 291 ns (1.30×) |
| 256 | 445 ns | 361 ns (1.23×) | 453 ns (0.98×) |
| 1024 | 799 ns | 1,588 ns (**0.50×**) | 1,084 ns (0.74×) |
| 4096 | 2,111 ns | 3,518 ns (0.60×) | 1,932 ns (1.09×) |

Crossover is around 256 values. Below it the skip is worth up to 5.3×; above it the extra vector compare is dead
weight against a word array that is mostly live. Clustered fixtures behave like the sparse ones, which matters
because a facet union tends to produce values in one stretch rather than scattered.

**Verdict.** Ship the empty-block skip with a density guard, not the compress kernel. It is 1.72× on the real
population, needs no VBMI2, and the guard is a popcount the repair already computes.

---

# Family 7 — the container hash

`ArrayContainer.hashCode` is 3.8 % of facet-query CPU, self time. The brief asked for a vector formulation. The
measurement says the loop should not be vectorised; it should stop after seven iterations.

## Why seven

The shipped loop is `hash += 31 * hash + content[k]`. That `+=` where a `=` was intended makes the recurrence
`hash = 32 * hash + c`, not `31 * hash + c`. Base 32 is `2^5`, so the coefficient of the character `j` places
from the end is `2^(5j)`, and `2^35` is zero in a 32-bit int. **Only the last seven characters can affect the
result.**

Proved, not argued: 20,000 randomised containers of 1 to 4096 values give the shipped loop and a seven-iteration
loop identical results, zero mismatches; and two 4096-value containers sharing only their last seven values both
hash to `-518437114`. The constant-time form returns the value callers already see. This is inherited from
upstream RoaringBitmap, not introduced here.

## What it is worth

**On 5,728 real array containers from the operand dump**, mean cardinality 305, one batch:

| arm | batch | per container | ratio |
|---|---|---|---|
| today | 1,488.8 µs | 259.9 ns | 1.00× |
| **last seven** | **20.8 µs** | **3.6 ns** | **71.5×** |
| vector, 16 chars per step | 178.2 µs | 31.1 ns | 8.4× |
| vector, 32 chars per step | 153.4 µs | 26.8 ns | 9.7× |

Synthetically the gap widens with cardinality exactly as the algebra predicts — 12.6× at 64 values, 59× at 256,
240× at 1024, **794× at 4096** — while the vector arms flatten out at 13–17×, because they are still O(n).

**Verdict.** The largest single win in this report, and it contains no vector code. The vector arms are recorded
because the comparison belongs in the record: they are what "make the loop faster" is worth (9.7×) against what
"stop doing the work" is worth (71.5×).

**Separate concern, deliberately not acted on.** A hash determined by seven values collides for containers
differing in thousands. That is a distribution question, not a performance one, and changing the value is a
behaviour change; the constant-time form keeps it bit-identical.

---

# The scatter — 92.7 % of all container work

`lazyIOR(Bitmap, Array)` is **92.69 %** of every container operation the engine performs during the facet query
mix: a median of four values scattered into an already-populated bitmap. Today it touches the word array once
per value. Because the input is sorted, the values that share a word arrive consecutively, so the alternative
accumulates them in a register and writes once per **distinct word**.

**On 300 real `lazyIOR(Bitmap, Array)` operand pairs**, one batch of 300, whole-run busy 6.7 %:

| arm | batch | per pair | ratio |
|---|---|---|---|
| today, one write per value | 9.30 µs | 31.0 ns | 1.00× |
| **word-batched** | **6.55 µs** | **21.8 ns** | **1.42×** |

**Fixture caveat, stated rather than buried.** The destination bitmaps accumulate in place within an iteration
and are restored from pristine copies between iterations, outside the timed region. A per-invocation restore
would have spent most of its time in a 2.4 MB copy. That changes the data, not the work: the read-modify-write
executes whether or not the bit was already set.

**Verdict.** 1.42× on the single most frequent operation in the engine, from a loop rewrite with no vector code
and no new dependency. Per operation it is nine nanoseconds; multiplied by 48.6 million operations per query
mix it is the second-largest win here.

---

# The union strategy — the largest opportunity, and it is not SIMD

A JFR profile of the production facet workload puts roaring at 40.9 % of query CPU and the lazy-OR machinery at
26.4 % of it. The reason is not that any one operation is slow: **95 % of the 1.93 million bitmaps that get
lazily unioned demote straight back to an array container**, each having paid an 8 KiB allocation, a scatter, a
population count and a 1024-word scan to get there. 99.95 % of the 253,170 containers being folded are already
array containers.

## The branch already exists; nothing reaches it

`ArrayContainer.lazyor` keeps an array-array lazy union as an array while the combined cardinality stays under
`ARRAY_LAZY_LOWERBOUND`, and that constant is **1024 here, the same as CRoaring's**. The premise that the Java
port never had the branch is wrong. What bypasses it is `PersistentRoaringBitmap.naivelazyor`, which calls
`toBitmapContainer()` on the accumulator *before* merging and then `lazyIOR` on the bitmap, so a multi-way union
promotes on the first merge of every key and never reaches it. The change is in that method, not a new kernel.

## Why a cardinality bound alone is not enough

Every fold on the array path copies the whole accumulator, so folding `k` inputs of `s` values each costs about
`k² · s / 2` element copies while the accumulator stays under the bound — **quadratic in the input count**. The
bitmap path costs one 8 KiB allocation and `k · s` scatter writes, which is linear. A bound on *cardinality*
cannot cap a term that is quadratic in the *input count*; only a second bound on the input count can. That is
what the guard is for, and it is why the two are not interchangeable. Measured at its worst, the vendored 1024
bound with no guard runs a 256-input fold at 0.02× of today.

## Replayed on 300 real multi-way unions

The union fixture — 300 real multi-way unions, five input-count strata — whole-run busy 6.0 %. One invocation
folds every union in its stratum, so the scores below are divided by the stratum's union count. Ratios against today's per-key fold, which tracks the
real `naive_or` closely enough to read as end to end.

| stratum | unions in workload | today | T=64 | T=256 | T=1024 | guarded (T=1024, N≤64) |
|---|---|---|---|---|---|---|
| 2-4 | 176,178 | 2,594 ns | 620 (**4.19×**) | 501 (5.18×) | 1,298 (2.00×) | 1,329 (1.95×) |
| 5-16 | 80,825 | 8,504 ns | 5,019 (**1.69×**) | 4,315 (1.97×) | 5,263 (1.62×) | 5,237 (1.62×) |
| 17-64 | 19,598 | 29,193 ns | 25,410 (**1.15×**) | 29,329 (1.00×) | 41,701 (0.70×) | 41,576 (0.70×) |
| 65-256 | 10,771 | 38,423 ns | 39,011 (0.98×) | 52,644 (0.73×) | 99,119 (0.39×) | 38,055 (1.01×) |
| 257+ | 6,892 | 206,241 ns | 212,726 (0.97×) | 252,524 (0.82×) | 445,836 (0.46×) | 203,907 (1.01×) |

**The strata are not the workload** — the dump samples 80/70/60/50/40 unions from buckets whose real populations
are the column above — so the answer is the weighted cost, never the average across the file. Today is 12,070 ns
per union.

| configuration | weighted ns/union | ratio |
|---|---|---|
| T=64, no guard | 9,852 | 1.225× |
| T=256, no guard | 11,280 | 1.070× |
| **T=64, guard N≤64** | **9,679** | **1.247×** |
| T=256, guard N≤64 | 9,675 | 1.247× |
| T=64, guard N≤16 | 9,931 | 1.215× |

## Why 64 rather than 256, when the weighted numbers tie

With the guard at 64 the two are indistinguishable. They separate on where they fail.

- **Without the guard**, T=64's worst stratum is 0.97× and T=256's is 0.73×.
- **T=256's regressions sit inside the guard's range, so the guard does not protect them.** At 64 inputs the
  synthetic grid reads 0.23× at `n64_s4`, 0.40× at `n64_mixed` and 0.57× at `n64_s16`, against 0.81×, 0.96× and
  0.99× for T=64. The replay's 17-64 stratum has a median of 27 inputs and does not land in that pocket, which
  is exactly why the two look equal there.
- **T=64 has no pocket anywhere below the guard.** Across all 27 synthetic shapes its worst reading is 0.75×
  (`n256_s1`, above the guard and therefore never taken).

Moving the guard to 16 costs the 17-64 stratum its 1.15× and buys nothing: 1.215× against 1.247×.

**Verdict.** `LAZY_ARRAY_UNION_BOUND = 64` with the input guard at 64. Weighted 1.247× on the real union
population, 4.19× on the 2-4 stratum that is 60 % of all unions.

**Scope caveat on the headline.** That 1.247× is the union fold only. The profile puts the lazy-OR machinery at
26.4 % of facet-query CPU, so it is worth roughly 5 % of query CPU if nothing else changes — arithmetic on a
profile share, not a measured query.

## The corrected synthetic grid

Three baselines, because one was not enough: the real `naive_or`, today's per-key fold, and the array lazy union
the vendored code already contains once nothing force-promotes. `unionTodayPerKey` and `unionTodayEndToEnd` agree
within 0.82–1.18× across every shape, which is what makes the container-level table readable as end-to-end.

| shape | today per key | existing 1024 bound | T=64 | T=256 | T=1024 |
|---|---|---|---|---|---|
| n2_s4 | 736 ns | 20.9 (35.2×) | 19.2 (**38.4×**) | 55.2 (13.3×) | 241 (3.05×) |
| n4_s4 | 798 ns | 52.2 (15.3×) | 37.5 (**21.3×**) | 72.4 (11.0×) | 279 (2.86×) |
| n8_s4 | 820 ns | 142 (5.79×) | 91.3 (**8.98×**) | 112 (7.29×) | 327 (2.51×) |
| n16_s4 | 872 ns | 397 (2.20×) | 297 (**2.94×**) | 308 (2.83×) | 510 (1.71×) |
| n16_s16 | 952 ns | 1,409 (0.68×) | 1,127 (0.84×) | 1,814 (0.52×) | 2,022 (0.47×) |
| n64_s4 | 1,332 ns | 4,663 (0.29×) | 1,646 (0.81×) | 5,761 (**0.23×**) | 6,000 (0.22×) |
| n64_s16 | 2,102 ns | 22,297 (0.09×) | 2,119 (0.99×) | 3,686 (0.57×) | 26,858 (0.08×) |
| n64_mixed | 1,674 ns | 11,074 (0.15×) | 1,748 (0.96×) | 4,146 (**0.40×**) | 10,406 (0.16×) |
| n256_s4 | 3,338 ns | 152,359 (**0.02×**) | 3,587 (0.93×) | 8,830 (0.38×) | 94,041 (0.04×) |

The `n256_s4` cell is the quadratic term at full size: 256 inputs of 4 values total exactly 1024, so the vendored
bound keeps every one of them on the array path and the fold copies a growing accumulator 256 times.

---

# Scatter shapes — per operation, unrepeated operands

The batch replay put the word-batched scatter at 1.42×. An end-to-end run on a quiet box then read the
catalog-wide facet summary **7.3 % slower** with that change in the tree, and reverting the single commit
recovered it (20,577 → 19,470 ms against a 19,178 ms before-arm). Both cannot describe the same operation, so
this probe measures **one scatter per invocation**, never a batch, with every shape advancing through a pool of
4,096 distinct operands so no invocation repeats its predecessor's.

3 forks × 5 warmup × 10 measurement iterations, whole-run busy 5.9 %. Nanoseconds for one
`lazyIOR(Bitmap, Array)`.

| shape | sharing with predecessor | RMW reduction | perValue (today) | perWord | ratio |
|---|---|---|---|---|---|
| **`n4_distinct`** — the median real operation | **0.0 %** | **1.00×** | **4.04 ns** | **6.25 ns** | **0.65×** |
| `n4_clustered` — 4 values in one word | 75.0 % | 4.00× | 3.91 ns | 4.91 ns | **0.80×** |
| `n64_distinct` — 64 values, 64 words | 0.0 % | 1.00× | 29.03 ns | 41.44 ns | **0.70×** |
| `n64_clustered8` — 64 values, 8 words | 87.5 % | 8.00× | 31.31 ns | 28.92 ns | 1.08× |
| `real_opweighted` — 300 real arrays, one per invocation | 81.3 % | 5.35× | 30.78 ns | 22.81 ns | 1.35× |
| `repeated_n4_distinct` — control, one fixed operand | 0.0 % | 1.00× | 3.64 ns | 5.98 ns | 0.61× |

## What the table says

**The per-word loop loses on the shape the engine actually performs.** At four values in four words — the
median real operation, and the shape of 121 of the 187 sampled arrays holding eight values or fewer — it
performs *exactly the same* read-modify-writes as the per-value loop, printed as a 1.00× reduction, and adds a
data-dependent branch per value. That costs 0.65×.

**It loses even where it does save work, until the saving gets large.** `n4_clustered` gives it a 4× reduction
in read-modify-writes and it is still 0.80×: at four values the branch costs more than three saved writes are
worth. Parity arrives somewhere past an 8× reduction — `n64_clustered8` is the first winning generated shape at
1.08×.

**Operand repetition is not the mechanism.** The control replays one fixed array every invocation and differs
from `n4_distinct` in one field. It is worth 9.9 % to the per-value arm (3.64 against 4.04 ns) and 4.3 % to the
per-word arm (5.98 against 6.25), and the ratio barely moves, 0.61× against 0.65×. The batch replay's 1.42×
cannot be attributed to a trained branch.

**The 1.42× was value-weighted.** The fixture line proves it directly: across the 300 real arrays, 81.3 % of
*values* share a word with their predecessor, for a 5.35× reduction in read-modify-writes — but that figure is
carried by the tail, arrays of up to 2,250 values, while the median operation has four and shares nothing.

## What it does not explain, stated as such

`real_opweighted` reads 1.35× **per operation**, and its 300 arrays are a uniform reservoir sample of the 48.6
million real operations. A uniform sample's mean estimates the population mean, so the aggregate should have
improved by about that factor. It did not; it regressed. **So the weighting explains why the median shape loses
while the sampled mean wins, but it does not explain the end-to-end regression**, and this probe therefore
localises the discrepancy to a condition the fixture does not model rather than to the operand distribution.

The leading candidate was destination cache residency: this fixture cycles 256 destination bitmaps of 8 KiB
each, 2 MB that spills L2 and makes every read-modify-write a likely miss, which would flatter whichever loop
performs fewer of them. In the engine one key's bitmap receives a long run of arrays in succession and stays
L1-hot, so a saved read-modify-write should be worth far less.

## The residency hypothesis, tested and refuted

Same benchmark, same run, a destination pool of 256 against a pool of one, so both settings share a JIT and a
box. 3 forks x 5 warmup x 10 measurement iterations, whole-run busy 5.8 %.

| shape | destinations | perValue | perWord | ratio |
|---|---|---|---|---|
| `n4_distinct` | 256 | 4.11 ns | 6.24 ns | 0.66x |
| `n4_distinct` | **1 (hot)** | 3.72 ns | 5.87 ns | **0.63x** |
| `n64_distinct` | 256 | 28.89 ns | 41.28 ns | 0.70x |
| `n64_distinct` | **1 (hot)** | 23.06 ns | 36.61 ns | **0.63x** |
| `real_opweighted` | 256 | 30.84 ns | 22.92 ns | 1.35x |
| `real_opweighted` | **1 (hot)** | 30.51 ns | 20.35 ns | **1.50x** |

The `real_opweighted` cell at 256 destinations needs its footnote. Its pooled score is 32.50 ns with an error of
15.67, because **one fork of three diverged**: fork 3 averages 51.53 ns against 23.05 and 22.92 for forks 1 and
2, with a within-fork maximum of 91.92 ns. Forks 1 and 2 agree with the previous run's independent reading of
22.81 ns, so the table quotes their mean and names the outlier rather than a pooled number describing neither
mode. The divergence was not chased.

**The hypothesis is refuted, and in the opposite direction.** Making the destination L1-hot makes the per-word
loop *better*, not worse: on the real operands its advantage widens from 1.35x to 1.50x, because the per-value
arm barely moves (30.84 to 30.51 ns) while the per-word arm gains (22.9 to 20.35 ns). Had the cold pool been
flattering the loop that performs fewer read-modify-writes, cheapening those writes would have narrowed the
gap. It widened it.

**What survives every configuration.** The per-word loop loses 0.63-0.70x on distinct-word shapes at both
residencies, and wins 1.35-1.50x on the sampled real distribution at both. Residency shifts both arms a little
and changes no verdict.

**The mechanism of the end-to-end regression is therefore not attributed.** This probe establishes what the
kernel costs per operation on every shape asked of it, and rules out two candidate explanations: operand
repetition, worth at most 10 % with the ratio unmoved, and destination residency, which moves the ratio the
wrong way. It does not explain why a kernel measuring 1.35-1.50x on a uniform sample of the engine's own
operations made the engine slower. The remaining candidate, untested, is that the operations on the regressing
query path are not distributed like the uniform reservoir, being dominated by the median four-value shape where
the loop is measurably 0.65x while the reservoir's mean is carried by a tail arising elsewhere. **That is a
conjecture with no measurement behind it and must not be read as a finding.** The finding is the revert:
removing the commit recovered the regression end to end, on a quiet box, with the commit as the only variable.

---

# Anomalies, and things that are not what they look like

Recorded rather than smoothed, because each one changed a conclusion.

**The cached broadcast probe wins every synthetic shape and loses 3.5× on real operands.** Reproduced across
three forks at ±1.3 %. See family 2; the best-supported explanation is cache residency, and it is not proven.

**The container-level "today" baseline was not today.** The first union grid folded through
`Container.lazyIOR` starting from an empty `ArrayContainer`, which dispatches array against array to the
vendored `ArrayContainer.lazyor` — so that arm *was* an array-first policy with the vendored 1024 bound, and
every policy comparison drawn against it was circular. Corrected to promote on the first merge the way
`naivelazyor` does. The superseded `family5-union` and `family8-union-replay` files remain on disk and must not
be quoted.

**`ARRAY_LAZY_LOWERBOUND` already exists in the vendored code.** `ArrayContainer.lazyor` keeps an array-array
lazy union as an array below 1024, CRoaring's own constant. The design note's premise that the Java port never
had the branch is wrong. What bypasses it is `PersistentRoaringBitmap.naivelazyor` calling `toBitmapContainer()`
on the accumulator before every merge.

**A 300-pair batch reported in microseconds reads like a per-pair figure in nanoseconds.** Every batch table
here states both.

**The engine calls `andCardinality` nowhere.** Confirmed by the census: zero invocations in 52.5 million
container operations. Every count-only ratio in this report is therefore a measurement without a caller, kept
because #1540 would create one.

**A run can finish, exit 0, and contain nothing.** See the gate section.

# Superseded files — do not quote these

| file | superseded by | why |
|---|---|---|
| `family5-union.json` | `family5-union-v2.json` | its "today, container" baseline was itself an array-first policy, so every comparison was circular |
| `family8-union-replay.json`, `-gc`, `-gc-rerun` | `family8-union-replay-v2.json`, `-gc-v2` | same defect in the replayed per-key fold |
| `family2-array-count.json`, `family2-array-materialise.json` | the `-v2` files | ran before `Du`/`Dt`/`Ds`/`Es`/`Gs`/H512b/H512c and the 256× skew shape existed; the count file also measured contended |
| `family1-bitmap-words-nosuperword.json` | `family1-nosuperword-v2.json` | 6 bytes: every fork died on the `-jvmArgsAppend` defect and JMH still exited 0 |
| `family2-array-materialise-v2` first attempt | the re-queued run | its wrapper was killed by hand during a gate false positive; the zero-byte file was overwritten |

They are kept on disk because a superseded measurement is evidence about the harness, and deleting it would
make the corrections in this report unverifiable.

# What was not measured, and why

- **End-to-end query impact.** Nothing here says what share of a facet query these kernels are. The JFR profile
  says roaring is 40.9 % of facet-query CPU and the lazy-OR machinery 26.4 % of that, but no before/after query
  benchmark was run, so every ratio above is a kernel ratio and must not be read as a query ratio.
- **AVX2-only and non-AVX-512 hardware.** Every number is from one Zen 5 box. The auto-vectoriser control
  suggests the scalar baselines would be slower elsewhere and the vector ratios correspondingly better, but
  that is an inference, not a measurement.
- **Run containers.** The replay harness refuses them; the census found none among the union inputs.
- **Multi-threaded behaviour.** All single-threaded.
- **`xor`, `iandNot`, `ior`, `intersects`.** Never invoked by the workload.

---

# Where each number came from

Result files are in this folder; each `.log` carries its run's whole-run CPU figure, the fork's `jvmArgs` and
the fixture's own description of what it built.

| section | result files |
|---|---|
| Family 1 | `family1-cardinality-precise`, `family1-store-precise`, `family1-nosuperword-v2`, `family1-or-gc` |
| Family 2 | `family2-array-count-v2`, `family2-array-materialise-v2`, `family2-broadcast-variants`, `replay-array-intersect-precise` |
| Family 3 | `family3-probe`, `replay-array-vs-bitmap` |
| Family 4 | `family4-extract-v2` |
| Family 6 | `family6-sparse`, `family6-sparse-repaired`, `family6-sparse-replay` |
| Family 7 | `family7-hash`, `family7-hash-replay` |
| Scatter | `replay-scatter` (batch), `scatter-shape` (per operation), `scatter-hot` (destination residency) |
| Union | `family5-union-v2`, `family5-union-gc-v2`, `family8-union-replay-v2`, `family8-union-replay-gc-v2` |

Harness, none of it retained: a fail-closed gate script (the four checks in *Box, build and how a run was
gated*), a grid driver with per-wave scripts, a quiet-repeat rerun for contended results, two summarisers that
produced the tables above, and a differential validator — 55 assertions over every kernel, run fail-closed
before each wave.
Fixtures: the operand, union and repaired dumps described in `../census/REPORT.md` §4.
