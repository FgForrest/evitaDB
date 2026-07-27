# Option B, generalized: Stage 1 JMH gate — results

Companion to `2026-07-09-frontcoded-keyat-flush-path-plan.md`. That doc scoped Option B (skip the
boxed intermediate on the granular leaf-page flush/load path) to `FrontCodedStringColumn`. Johnny
asked whether the same idea generalizes to `InstantValueColumn`/`LongValueColumn`/`IntValueColumn`;
this doc is the Stage 1 (JMH) half of that generalized proposal, run to completion.

Benchmarks: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/
PrimitiveColumnSerializationBenchmark.java` and `FrontCodedSerializationBenchmark.java`.
Both compare `write_today`/`read_today` (box, then `kryo.writeClassAndObject` — mirrors
`keyAt` + today's serializer loop) against `write_bypass`/`read_bypass` (raw primitive bytes,
no boxing, no per-entry class tag).

## Methodology note — three real bugs caught before trusting any number

1. **Buffer-overflow in pre-sized `Output`s** (both files): a flat per-entry byte budget for the
   "today" (and, in FrontCoded's case, "bypass") variants didn't leave enough room once the
   benchmark actually ran, throwing `KryoBufferOverflowException` on the very first `@Setup` call.
   Fixed by making the affected `Output`s grow-on-demand (`maxBufferSize=-1`); growth happens once
   in `@Setup`, never inside a timed `@Benchmark` method, so B/op is unaffected.
2. **Instant round-trip self-check summed the wrong quantity on each side**
   (`readInstantToday` summed `epochSecond` only, `readInstantBypass` summed `sec+nano`) — a false
   mismatch that the self-check correctly caught and blocked on. Fixed to sum the same quantity.
3. **The benchmark's Kryo was unregistered** (`new Kryo()` + `setRegistrationRequired(false)`),
   while production's `KryoFactory.initializeKryo` registers `Instant`/`Long`/`Integer`/`String`
   (and ~90 other types) with fixed IDs and `setRegistrationRequired(true)`. Unregistered, every
   `writeClassAndObject` call pays a full ASCII class-name string on top of Kryo's per-call
   `autoReset` (which clears the transient name cache) — production never pays this. This inflated
   the first-pass numbers significantly (see below). Fixed by registering the same classes.
4. **`intKeys` fixture never left the JDK's `Integer` cache** (`i - blockSize/2` stays within
   `[-128,127]` for every `blockSize` tested), silently testing only the free-boxing case. Fixed by
   scaling (`(i - blockSize/2) * 50`) so most values fall outside the cache.

All four were caught by actually running the benchmark and cross-checking with a `general-purpose`
research pass into `KryoFactory`/`IndexStoragePartConfigurer`, not by re-reading the benchmark code —
consistent with this project's decision-gate discipline (a benchmark that looks right on paper and a
benchmark that measures the right thing are not the same claim).

## Corrected, production-representative numbers

`ns/op` (avgt) and `B/op` (`gc.alloc.rate.norm`), 5 measurement iterations, blockSize 64 and 256
(64 = InvertedIndex/FilterIndex leaf size, 256 = GlobalUniqueIndex/OwnerUniqueIndex leaf size).

| Column | Direction | today ns/op (64/256) | bypass ns/op (64/256) | speedup | today B/op (64/256) | bypass B/op |
|---|---|---|---|---|---|---|
| Instant | write | 602 / 3290 | 137 / 538 | 4.4× / 6.1× | ~0* / 6144 | ~0 |
| Instant | read | 607 / 2358 | 129 / 507 | 4.7× / 4.7× | 1664 / 6272 | ~0 |
| Long | write | 498 / 1924 | 96 / 382 | 5.2× / 5.0× | 1536 / 6144 | ~0 |
| Long | read | 399 / 1845 | 99 / 397 | 4.0× / 4.6× | 1664 / 6272 | ~0 |
| Int | write | 480 / 1743 | 44 / 156 | 10.9× / 11.2× | 944 / 4016 | ~0 |
| Int | read | 342 / 1194 | 50 / 159 | 6.8× / 7.5× | 1072 / 4144 | ~0 |
| Boxed (control) | write | 473 / 1916 | n/a | n/a | ~0 / ~0 | n/a |
| Boxed (control) | read | 413 / 1869 | n/a | n/a | 1664 / 6272 | n/a |

\* `write_today_instant`'s B/op at blockSize=64 measured ≈0 (0.004), inconsistent with the same
benchmark at blockSize=256 (6144) and with the read-side numbers at both sizes (which scale
consistently with entry count). This smells like a JIT escape-analysis artifact at the smaller,
more-inlinable loop size, not a real effect — flagged rather than hidden. It does not change the
conclusion (the 256 number and every read-side number confirm the same ~24 B/entry allocation),
but it's a reason not to over-trust any single microbenchmark number without Stage 2.

FrontCoded (blockSize=256 only — production unique-index leaf size for String attributes):

| Direction | today ns/op | bypass ns/op | speedup | today B/op | bypass B/op |
|---|---|---|---|---|---|
| write | 14801 | 7974 | 1.86× | 20480 | 0.055 |
| read (raw bytes only, not a column — informational, not the load path) | 5150 | 813 | 6.33× | 21736 | 128 |
| **read (real load path: builds a usable column)** | **1,542,327** | **5,721** | **269.6×** | **629,050** | **14,424** |

The last row is the one that matters and required a second iteration to get right (see below):
`read_today_intoColumn` reproduces `insertKeyAt`'s actual behavior — decode every already-inserted
entry back to bytes, append the new one, re-encode the whole column — once per entry, so O(n) work
×n inserts = O(n²) for n=256. `read_bypass_intoColumn` does the equivalent job in one O(n) bulk
`encode()` call.

**This is not an assumption — the production call chain was traced and confirmed**, since advisor
correctly flagged that claiming `insertKeyAt`-per-entry as "the load path" without verifying it would
repeat the same mistake as the unverified Kryo-registration guess. Confirmed chain: on catalog open,
`DefaultCatalogPersistenceService.readCatalogIndex` (`evita_store_server/.../DefaultCatalogPersistenceService.java:1656-1682`)
deserializes each leaf page's raw `values`/`payloads` arrays unchanged and calls
`GlobalUniqueIndex.fromPersistedPages` (`GlobalUniqueIndex.java:269-309`), which creates one *empty*
leaf tree per page then loops `for (int j = 0; j < values.length; j++) pageTree.addLongRecord(values[j], payloads[j])`
(lines 293-298) — **one call per value, not a bulk construction**. `addLongRecord` →
`TransactionalBucketBPlusTree.insertNewSingleBucket` (line 4193) → `this.keys.insertKeyAt(position, value)`
— confirmed via repo-wide grep to be the *only* production call site of `ValueColumn.insertKeyAt`.
So yes: loading one 256-entry leaf page really does perform 256 sequential `insertKeyAt` calls, each
re-decoding and re-encoding the entire blob so far. The 270× number is real, not a benchmark artifact.

## What this means

**Instant/Long/Int all pass Stage 1 cleanly**, write and read, both block sizes: real ns/op wins
(4–11×) backed by real, consistent B/op elimination (allocation drops to ~0 on the bypass side).
Int's fixture fix mattered — with the original (buggy) all-cached fixture this column would have
looked like a non-win; corrected, it shows the largest ns/op ratio of the three, because even
though the JDK cache made its *allocation* cheap for cached values, the per-entry Kryo class-tag
resolution cost (independent of boxing) still dominated `today`'s time.

**FrontCoded passes cleanly on both write and read/load-path** — this section originally concluded
the opposite (see "revision history" below); that conclusion was wrong and has been corrected.
The write comparison is solid (1.86×, real allocation elimination to near-zero). For read, the
`read_bypass_toBytes` (813 ns) vs `read_today_toStrings` (5150 ns) comparison is real but not the
load-path answer, since neither side builds a column — it only tells you the raw-byte format is
cheaper to produce/parse than Kryo-tagged strings, which was never in doubt. The load-path question
is `read_today_intoColumn` vs `read_bypass_intoColumn`, and it isn't close: today's real path
(sequential `insertKeyAt`, O(n²) for n=256) is **1.54 ms/op**; the bulk bypass path (one O(n)
`encode()` call) is **5.7 μs/op** — a **270× speedup**, with a **44× reduction** in allocation
(629,050 → 14,424 B/op). Bulk front-coding recomputing common-prefix compression and restart
offsets once is dramatically cheaper than doing it 256 times, once per insert — exactly the
quadratic-vs-linear gap `insertKeyAt`'s own doc comment already predicted. **This is the strongest
single number in the whole Stage 1 pass and squarely justifies building the hard, new-scope half of
Option B (bulk-encode-from-raw-bytes on load) for `FrontCodedStringColumn`.**

### Revision history — a real error caught by advisor, not a hedge

The first version of this doc concluded the opposite: it compared `read_bypass_intoColumn`
(5821 ns, builds a column) against `read_today_toStrings` (5115 ns, does **not** build a column) and
called bypass "14% slower." That comparison is invalid — the two sides do different amounts of work,
so "bypass loses" was comparing apples to a cheaper orange, not evidence of anything. advisor caught
this before it reached Johnny. The fix was to add `read_today_intoColumn`, a benchmark that
reproduces `insertKeyAt`'s actual O(n²) sequential-rebuild cost (byte-level decode of every prior
entry, no `String` round trip, matching production exactly), giving both sides of the comparison the
same job. Once compared fairly, bypass wins by 270×, not by "0.88×." The earlier "drop the read-side
from scope" recommendation is retracted.

Registering Kryo's classes to match production roughly **halved** the apparent ns/op advantage for
the primitives (e.g. Instant read: unregistered run showed 27–29×, registered shows 4.7×) and left
FrontCoded's numbers almost unchanged (its ~33-byte string payload already dwarfed the ~18-byte
class-name-string artifact, so the contamination was a much smaller fraction of the total). The
lesson generalizes: **the smaller the payload, the more a microbenchmark's Kryo registration
config matters** — for `Instant`/`Long`/`Int` it was the dominant factor in the first pass.

## An independent finding, not just an Option-B number

Because the 270× reflects a real, confirmed production call chain, it should be read as two separate
facts, not one: (1) Option B's bypass load path is a strong win, and (2) **today's leaf-page load for
`FrontCodedStringColumn` is O(n²) in entries-per-page, independent of whether Option B ever ships.**
This is a latent scalability cliff — larger leaf block sizes make it worse quadratically — and is
worth flagging to Johnny as a finding in its own right, not folded silently into "Option B's numbers
look good." The same `addLongRecord`→`insertKeyAt` load pattern is the only call site for
`ValueColumn.insertKeyAt` in the codebase, so it applies to every column kind on every leaf-page load
(`GlobalUniqueIndex`, `OwnerUniqueIndex`, `InvertedIndex`, ...) — whether the *other* columns'
`insertKeyAt` is similarly expensive per call (i.e. whether this is a FrontCoded-specific
compression-re-derivation cost or a general array-shift cost that's O(n²) for every column
regardless of Option B) was not checked this session and is an open question worth answering,
possibly as part of Stage 2.

## Gate verdict

Per the two-stage gate (see the sibling design doc's "decision gate must not repeat H2's trap"
section): **Stage 1 passes for every column and every direction tested** — Instant/Long/Int
(write + read) and FrontCoded (write + the real load-path read). Nothing is being dropped from
scope; all five staged targets from `option-b-generalized-design.md`'s "per-column override cost"
list remain live candidates.

A passing Stage 1 authorizes Stage 2 (production-shaped ALIVE-churn allocation profile, caller-chain
traced, under both default and tuned config) — **not** the SPI/refactor itself. Not yet run.

Status: Stage 1 complete. Stage 2 not started.
