# P1 — the index core, measured

**Companion to [`p1-index-core.md`](p1-index-core.md), which states the plan. This states what the plan
found.** Campaign run 2026-09-17 on a quiet box; steps K1–K7 of that document's §6. **Nothing of the
fulltext core is implemented** — every structure here was built in a `main` outside the engine, from a
corpus extracted to a flat file, and thrown away when the run ended. What survives is the numbers and
the four recommendations they carry.

*Update 2026-10-01:* the core has since been implemented in `io.evitadb.index.fulltext`, with the impacts
stored inside the dictionary's leaves rather than in a sidecar beside it. Part 2.4 re-takes the RAM figure on
those production structures; everything else here still describes the spike.

The three gate criteria of §1.1 are answered. Two pass as specified. The third passes only after a
change to §5.3, and that change is the campaign's largest finding.

**Reading convention:** a bare `§` always refers to a section of
[`p1-index-core.md`](p1-index-core.md), the prototype this measures. References to *this* document say
"part". The two numbering schemes overlap and would otherwise be indistinguishable.

---

## 1. The corpora, and why there are two

§6 requires the expansion knee on **two corpora of different character**, because P8's synthetic ladder
produced a scaling law in *n* that a production corpus falsified outright. Both are production
catalogues, and they were chosen to disagree:

| | corpus A — CMS | corpus B — e-commerce |
|---|---|---|
| entities | 972,611 articles | 157,410 products, 18 collections |
| text-bearing subset | all | 118,772 products carry a name |
| locales | one (cs) | **two (cs, sk)** |
| fields measured | `title`, `perex` (attributes); `body` (**associated data**) | three attributes: a name and two descriptions |
| shape | long HTML prose, 200.7 tokens average | short names and identifiers, 7.1–7.6 tokens; descriptions 82–92 |
| value occurrences | 2,884,415 | 346,856 |

Corpus B is the first in this campaign with more than one locale, which is what exercises §4.1's
per-(collection, locale) partitioning at all. Corpus A is the only one whose principal field is
**associated data**, which turns out to decide a question of §7.5 on its own (part 5.3 below).

**Two arms were measured throughout**, raw and HTML-stripped. The gate arm is stripped: markup handling
belongs in the configured analysis chain, not in stored data, so the gate is taken over the text the
chain would actually see. Figures from the two arms are never mixed.

---

## 2. Gate criterion 1 — RAM. **Passes, with room.**

§1.1 asks for ≤ 150 MB per 1M products and locale (e-commerce) and ~250–400 MB per 100k documents
(CMS). Against corpus A that is a budget of **2.43–3.89 GB**.

Measured, gate arm, every figure a JOL deep-retained owned graph in the compressed-oops regime:

| component | bytes |
|---|---|
| term dictionary (`TransactionalBucketBPlusTree`, layout D1) | 540,458,368 |
| impact sidecar, variant **S1** | 293,076,544 |
| dense length tables | 11,671,712 |
| **total** | **845,206,624 — 0.85 GB** |

**0.85 GB against a 2.43–3.89 GB budget.** The expensive sidecar variant S2 costs 739,875,168 B and
still lands the total at 1.23 GB, inside the budget. §1.1 evaluates the goal *for the shape P1 actually
builds*, so this is the honest reading: **the budget no longer forces the sidecar choice**, and S1 is
recommended on its own merits rather than out of necessity.

### 2.1 The sidecar, and the number §4.3.2 called the softest in the table

The chunk count is a property of the corpus and nothing had measured it. It is **3,809,182** chunks on
corpus A — 6.29 per non-degenerate key, with **47.03%** of keys degenerate (a posting list of one). The
61 MB of array headers riding on a 177 MB payload is why S1 costs 1.66 B per posting rather than
§4.3.2's estimated 1 B.

Held against corpus B, the ratio is stable across a fifty-fold difference in corpus size:

| | A, `article/cs` | B, `Product/cs` | B, `Product/sk` |
|---|---|---|---|
| dictionary keys | 1,143,244 | 156,832 | 160,132 |
| postings | 176,999,724 | 3,382,618 | 3,714,745 |
| degenerate keys | 47.0% | 74.1% | 72.4% |
| S1 bytes per posting | 1.66 | 2.44 | 2.41 |
| S2 / S1 | 2.52× | 2.29× | 2.29× |

Bytes per posting *rise* on short fields because the directory is charged per **key**, and short
name-shaped fields make far more keys per posting than long prose — with three quarters of those keys
degenerate.

**Why S1 rather than S2, now that the budget does not decide it.** S2 buys one real property: a chunk
carries its own primary keys, so the sidecar is **self-describing** and survives losing its alignment
to the posting list. S1 has no such insurance — its correctness rests entirely on the invariant that
the *n*-th impact belongs to the *n*-th posting, and if a maintenance path ever appends to one without
the other, S1 is silently wrong where S2 would merely be redundant. That is not a small thing for a
structure P2 has to mutate transactionally.

It loses anyway, on three counts. It costs **2.3–2.5× more on every corpus measured**, stably, so the
price is structural rather than a property of one dataset. The invariant it insures against is
**checkable without it** — sealing walks the dictionary's own posting list in step with the chunks, so
a misalignment is detected at build time and reported rather than scored, which is how the campaign's
own runs were validated. And its self-description is **not free at the degenerate key**, which is 47%
of corpus A and 74% of corpus B: those keys have no chunk and therefore no container to supply the
high bits, so each must store a full primary key (part 8 below).

**S1 is the recommendation on both corpora — but the alignment invariant becomes P2's obligation**, and
if P2 finds it cannot hold it across transactional maintenance, S2 is the escape and it is affordable.

### 2.2 The build peak — §1.1's other question, and the answer is one builder

§1.1 asks for the build peak as a threshold-free figure, citing OpenSearch's split into
`OnHeapStarTreeBuilder` and `OffHeapStarTreeBuilder` as the precedent to avoid. The peak is not the
answer, because a generous heap lets the collector defer; the answer is the smallest heap at which the
build completes. Corpus A's gate-arm build completed at **8g, 6g, 5g, 4g, 3g, 2500m, 2g and 1800m**,
and failed below 1600m. Build time did not move across that **fourteen-fold** range — 312,900 to
325,352 ms, with the 2g run sitting *inside* the spread of the 28g repetitions.

**P1 needs one builder.** A 0.85 GB result that builds in 1.8 GB, with the analysis dominating so
completely that collector pressure is invisible in the total, does not motivate a second
implementation. The live set after a full collection is 1.34 GB; the accumulators do not survive
sealing.

### 2.3 Derive on open, or persist? **Persist.**

P8's precedent is "derive" — its trigram rebuild costs 4.0 s. P1's does not, because it re-analyses the
corpus rather than replaying value counts. Index build **325,352 ms** against the same catalogue's open
time measured three times on a quiet box at **33,639 / 32,862 / 32,332 ms**: a ratio of **9.9×**. Adding
five minutes to every catalogue open to avoid persisting a structure that changes only when the data
does is not a trade worth making.

**Consequence, named here because it lands on P1 rather than later:** persisting pulls §4.3.5's sidecar
format versioning into P1's scope.

### 2.4 Re-taken on the production structures (2026-10-01). **0.70 GB.**

The figures above weighed spike structures: a dictionary keyed by boxed strings and an S1 sidecar sealed
beside it. Neither shipped. The production `FulltextIndex` keeps the dictionary front-coded and stores
each posting's impact byte in an **impact column inside the dictionary's own leaves**, in the shape of the
bucket's record tier: one byte for a single record, an array for a small bucket, one chunk per roaring
container for a bitmap bucket. Length tables moved to a per-block dense/sparse layout. The same corpora
and the same gate arm were streamed through the real `addValue`, and the result weighed the same way (JOL,
compressed oops). To isolate the impact column, which has no root of its own, a **twin** dictionary
without impacts was fed the identical key sequence, so its leaves split identically. The column is then
`whole − twin − length tables`. Key and posting counts equal part 2.1's exactly, so the two sets of figures
compare directly:

| | A, `article/cs` | B, `Product/cs` | B, `Product/sk` |
|---|---|---|---|
| whole index | **700,170,512** | 21,821,936 | 23,433,816 |
| dictionary (the twin) | 476,927,864 | 15,208,648 | 16,370,448 |
| impact column | 220,292,064 | 5,970,728 | 6,450,736 |
| length tables | 2,950,584 | 642,560 | 612,632 |
| impact bytes per posting | **1.24** (S1: 1.66) | **1.77** (S1: 2.44) | **1.74** (S1: 2.41) |

On corpus A the total falls from 845,206,624 to **700,170,512 B (−17%)**. The dictionary is 12% smaller
than the boxed-key spike's, the length tables 75% smaller, and the impacts 25% cheaper per posting. That
last saving comes from the degenerate keys: a single-record bucket's impact is a cached `Byte` and costs
the leaf nothing beyond its slot. **0.70 GB against the 2.43–3.89 GB budget.** The ratio between the corpora
holds: short name-shaped fields still pay more per posting, for part 2.1's reason. The alignment invariant
part 2.1 handed to P2 is now held by construction, since every insert, split, merge and commit moves the
impact with its record.

The instrument is `FulltextIndexFootprint`, in the git-ignored spike package. The build ran on a busy box,
so its time is not quoted; heap figures do not depend on load.

---

## 3. Gate criterion 2 — phase-1 latency. **Passes only after §5.3's merge changes.**

§1.1 asks for ≤ 25 ms per 1M candidates, one thread, measured **per expansion width**. The criterion is
therefore not one number but a line the expansion width crosses somewhere, and where it crosses is the
expansion knee.

### 3.1 §5.3's linear merge makes the cost independent of the postings

§5.3 merges each expanded term's posting list against the sorted candidate array with two linear
cursors. That steps over **every candidate however short the posting list is**. Measured on corpus A: a
rare-class query with 16 expansions over 5 tokens walks **186 postings** and takes **18.5 ms** — the
postings are second-order, and the cost is `tokens × width × candidates` at roughly 190–460 µs per full
traversal of 972,609 candidates.

**25 of 36 cells pass.** The failures are all high-frequency and wide.

**The pass/fail line understates the problem, and this is the part that matters.** Read literally, the
grid passes up to a `tokens × width` product of roughly 48 for high-frequency terms, 64 for medium and
beyond 80 for rare — which looks comfortable. It is not the operating point. Part 4 below measures what a
*real* query token expands to on this corpus at distance 2 with a literal prefix of 1 over three
fields: **796 distinct terms.** Sixteen is not the operating point; 796 is, and at that width the linear
walk makes roughly 800 full candidate traversals per token — two orders of magnitude outside the budget.

So the honest statement of the second gate question is not "11 cells fail". It is: **§5.3's algorithm
does not pass at the expansion widths §5.2's own cost model produces.** The data structures are not the
problem — the first gate question passed with 2.9× headroom. The merge is.

### 3.2 A galloping merge fixes it, and restores the shape §5.2 already assumed

Replacing the linear merge with an exponential-then-binary search from the cursor — and choosing per
expansion between `postings × log2(candidates / postings)` and `candidates + postings`, whichever is
cheaper — takes it to **32 of 36 cells**, up to **45.1× faster** (rare class at width 64: 91.6 → 2.0 ms).
The rank stays free, because both merges still iterate the postings in order (§5.1 untouched).

**One wrong turn is recorded because it is easy to repeat.** A first attempt binary-searched the *whole*
candidate array. It recovered most of the rare and medium classes and left the dense ones flat, because
postings arrive ascending: the answer is nearly always just ahead of the cursor, and a full-range search
pays `log(candidates)` cache-missing probes to find what a gallop finds in two. **Gallop from the
cursor, do not search the array.**

### 3.3 The cost model, fitted on one corpus and held on the other

This is what §6's two-corpus condition was for. Fitting `time = a·postings + b·matched + c` over all
twelve cells of each corpus:

| | corpus A, 972,609 candidates | corpus B, 118,772 candidates |
|---|---|---|
| per posting walked | **32.1 ns** | **13.6 ns** |
| per matched document | **22.0 ns** | **20.5 ns** |
| "fixed" per query | 2.55 ms | 0.25 ms |
| the same ÷ candidates | 2.6 ns | 2.1 ns |

**Two of the three coefficients are corpus-invariant.** The matched-document term — accumulator update,
composite composition, top-N heap — is ~21 ns on both, within 7%. The "fixed" term is not fixed at all:
divided by the candidate count it is 2.1–2.6 ns on both, which is the per-query setup over the candidate
array. Only the posting coefficient moves, and it moves 2.4× in step with the candidate array's **cache
footprint** — 972,609 ints is 3.9 MB and spills L2, 118,772 ints is 475 KB and does not — while the
gallop's probe count is nearly identical in the two runs (8 against 7, from the same selector). It is
therefore a function of a quantity the planner already knows at query time, not a free parameter.

### 3.4 The cap has to be two-term, and the single knee is a special case of it

Read as a single number, the knee is **≈490,000–540,000 summed postings** per query on corpus A
(bracketed by 305,223 → 18.0 ms passing and 694,253 → 30.5 ms failing). That number is a blend of two
terms that happen to co-vary on that corpus. The cap that survives both:

> **25 ms ≈ 2.4 ns × candidates + 32 ns × summed postings + 21 ns × matched documents**

with the posting coefficient taken at the pessimistic end whenever the candidate array exceeds L2. The
case a postings-only cap waves straight through is a query matching *every* candidate: at 1M candidates
the matched-document term alone spends 21 of the 25 ms, leaving room for ~14,000 postings and no more.

**The cap must be on summed postings, never on term count.** K5 found the expansion widths so skewed —
median 146 postings, p99 1,046,822 in one cell — that a "max N terms" rule admits both the median token
and one carrying a million postings.

---

## 4. Term expansion (K5) — the three numbers §5.2 sums over

§5.2 models phase 1 as a sum of posting-list lengths over the *expanded* terms. None of the three
expansions had a corpus number beside it before this campaign.

**The M7 hypothesis fan-out is 2.157 terms per position, not the 1.30 the plan carried** from a
32-lemma fixture. Only 19% of query tokens expand to a single term. The index chain measures exactly
**1.000**, so the asymmetry costs query width and not dictionary size — which is the direction P5's
two-slot design intends.

**What one query token actually costs.** Combined over all three fields, at distance 2 and literal
prefix 1 — the shape §5.2 sums — on corpus A:

| | mean | p50 | p90 | max |
|---|---|---|---|---|
| hypotheses per token | 2.1 | 2 | 3 | 5 |
| distinct expanded terms (union) | **796** | 335 | 2,502 | 3,187 |
| terms summed per expansion | 1,037 | 408 | 3,229 | 4,915 |
| **postings walked** | **811,725** | 246,668 | 2,732,944 | 3,797,422 |

**One token already exceeds the whole-query budget of part 3.4.** Two settings fix it and both are
measured: raising the literal prefix to **2** cuts the per-token postings roughly 5× and makes the
expansion itself 19× cheaper to compute.

**§5.3's counting rule is worth 23% of the term count** — 1,037 terms summed across a token's expansions
against 796 distinct. That gap is the hypotheses' overlap, and it is free *only* if a token's expansions
are merged into one pass before the accumulator sees them, exactly as §5.3 specifies. An implementation
that let each expansion reach the accumulator separately would pay the 23% and, worse, would count one
token several times in the matched-token lane.

**So P1's recommendation on phase 1 is three things, not one:** gallop the merge, cap on summed
postings per §3.4, and default typo expansion to literal prefix 2 rather than 0 or 1.

---

## 5. Gate criterion 3 — quality. **The two-way result §7.5 asked for.**

§7.5 asks for ~50 queries run both ways with their **result sets** compared, split into a single-word
group where the comparison is honest in both directions and a multi-word group that is a difference of
capabilities rather than a score. It forbids reporting latency: both paths are fast on their own terms.

**The baseline needed no catalogue and no schema change**, contrary to the plan's expectation.
`AttributeContainsTranslator#createPredicate` is `value.contains(textToSearch)` — plain,
**case-sensitive**, no normalisation — and the trigram index P8 shipped accelerates that predicate while,
by its own javadoc, being unable to lose a match. Since latency is out of scope, the baseline's *result
set* is reproducible exactly by running the same predicate over the same stored values.

Query sets were **derived from each corpus with a fixed seed** rather than hand-picked, covering §7.5's
nine categories at roughly 27 single-word and 25 multi-word each. Production search logs would be
better and were not available; the fallback is at least reproducible and nobody chose the words.

### 5.1 What fulltext finds and the baseline cannot

| | A `article/cs` | B `Product/cs` | B `Product/sk` |
|---|---|---|---|
| single-word: fulltext / baseline | 26 / 17 of 27 | 26 / 15 of 27 | 24 / 13 of 27 |
| multi-word, **all tokens**: fulltext / baseline | 20 / 8 of 20 | 24 / 10 of 26 | 22 / 9 of 24 |
| typo + missing-diacritics queries: fulltext / baseline | 10 / 2 of 11 | **11 / 0** of 11 | **11 / 0** of 11 |
| phrase and typo-in-phrase queries: fulltext / baseline | **8 / 0** of 8 | — | — |

Every typo and missing-diacritics query on corpus B is answered by fulltext and by nothing else,
because the baseline is a literal case-sensitive substring test. On corpus A the baseline answers two of
eleven, Czech prose sometimes carrying the unaccented form literally.

**The multi-word rows use the conjunction, not §5.3's ranked OR.** Phase 1 puts a document matching one
token of three into `matchedDocuments`; setting that beside a contiguous-substring baseline would
overstate recall by an order of magnitude. Ranking is what separates them in practice — the composite's
top lane is the matched-token count.

### 5.2 What the baseline finds and fulltext cannot — the regression, named

A literal match a tokenized index does not reach has **two** causes, and only one is §7.5's regression:

- **the tokenizer did not cut there** — the substring survives markup stripping. A real capability gap.
- **the string exists only inside markup** the arm removed: an image filename in a `src`, a class name,
  a URL slug. No tokenizer could have indexed it and no user was looking for it.

| | A | B/cs | B/sk |
|---|---|---|---|
| missed, tokenizer did not cut | 36,631 | 27,332 | 25,599 |
| missed, markup only | 3,359 | 834 | 417 |
| queries the baseline answered and fulltext could not at all | **0** | **0** | **0** |

The regression is real, bounded, and concentrated exactly where §7.5 predicted: the worst query on
corpus A returns 41,105 documents to the baseline and the walk misses 21,861 of them in text, because
the digits sought live inside longer tokens the tokenizer kept whole. **Neither path absorbs the
other**, which is what P8's brief §33 point 4 concluded and this measures.

**The distinction is not academic.** Reported as one number the first run said 52.3% of baseline
documents were missed. Spot-checking a single query — 102 baseline hits, 1 fulltext hit — found **all
102** occurrences inside `src="…"` image URLs: the baseline was "winning" on image filenames. Split
properly that query scores 102 markup-only and **zero** real.

### 5.3 The finding that owes nothing to ranking

Corpus A's principal field is **associated data**, and `attributeContains` addresses attributes only.
Running the identical predicate over both kinds of field:

| | documents matched | queries |
|---|---|---|
| `title` + `perex` — what the constraint **can** address | 110,974 | 25 of 47 |
| the article body — what it **cannot** | **1,307,128** | 27 of 47 |

**11.8× more literal matches live in the field the constraint cannot reach**, and for **5 of 47 queries
the only literal matches are there** — the baseline returns nothing while the content plainly exists.

This is the strongest single result of the quality step and it depends on no stemmer, no typo tolerance
and no ranking. It also sharpens §4.4 of the research: on a CMS profile, keeping `attributeContains`
alongside fulltext is not a consolation for the infix regression — **the two address disjoint storage
kinds**, and only one of them reaches the body.

---

## 6. Three numbers the gate did not need, and the next prototypes do

### 6.1 What the two analysis filters are worth — the raw arm's whole purpose

The gate arm strips markup because §7's ruling is that a real deployment configures the profile to (a)
strip HTML and (b) extract only the fulltext-relevant parts of the JSON the CMS corpus carries in
`data-` attributes. The raw arm exists to price that configuration, and on corpus A it prices it at
almost half the structure:

| | gate arm (stripped) | raw arm |
|---|---|---|
| dictionary keys | 1,143,244 | 5,549,814 |
| postings | 176,999,724 | 284,165,619 |
| chunks | 3,809,182 (6.29 per non-degenerate key) | 6,540,690 (2.94) |
| degenerate keys | 47.03% | 59.87% |
| dictionary + postings | 540,458,368 | 1,061,536,712 |
| sidecar S1 | 293,076,544 (1.66 B/posting) | 500,206,000 (1.76 B/posting) |
| **total with S1** | **845,206,624 (0.85 GB)** | **1,573,414,424 (1.57 GB)** |

**Configuring those two filters is worth 0.72 GB and 3.1 minutes of build time on this corpus** — 46% of
the footprint and 39% of the build. Analysed raw, the paragraph tag reaches a document frequency of
**100%** and the embedded payload's attribute vocabulary reaches **82%**, putting 19 of the 25 widest
posting lists into markup rather than prose. Markup terms are numerous and shallow where prose terms are
fewer and deep, which is why the raw arm has 4.9× the keys but only 1.6× the postings.

**Neither filter exists in the shipped chain, and they are not equally easy.**
`HTMLStripCharFilter` is already inside the pinned `lucene-analysis-common`, so (a) is a small addition.
For (b) there is nothing — and it runs into a structural obstacle rather than a missing class:

> **The analyzer assignment seam is per (entity type, locale), not per attribute**
> (`AnalyzerAssignmentResolver#resolveAnalyzers`). That is fine for an HTML stripper, which is a no-op on
> plain text. It is **not** fine for a JSON path extractor, because a title and a body in one collection
> and one locale share an analyzer, and a path expression meant for the body would be applied to the
> title.

**This resolves P5's open question P5-4 with evidence rather than speculation.** That question asks
whether the registry's key is really `(collection, locale)` or whether a need for a per-attribute
override will show. It has shown: not as a preference about recipes for short and long fields, but as a
filter that is *only correct* on one attribute of a collection. The 0.72 GB above is what it is worth.

### 6.2 Indexing the surface form beside the stem — P3's number

§2 of the prototype describes a second dictionary mode that indexes the original surface form alongside
the stem, so a suggester can complete to words a user recognises. It had never been measured. Gate arm,
corpus A:

| | stem only | stem + surface form | ratio |
|---|---|---|---|
| distinct terms | 1,143,244 | 3,292,753 | **2.88×** |
| postings | 176,999,724 | 349,642,121 | 1.98× |
| dictionary + postings heap | 540,458,368 B | 1,257,145,416 B | **2.33×** |
| bulk build | 277,729 ms | 561,625 ms | 2.02× |

The surface form adds 201,741,366 postings, 88% on top of the stems, and **+717 MB on the dictionary
alone**. Since the postings roughly double, the sidecar would follow: the projection is **~1.85 GB
against 0.85 GB**. Still inside the CMS budget — but it more than doubles the structure, and **P3 should
have this number before designing a suggester that assumes the mode is available.**

### 6.3 The impact estimate in §4.3.2 is low, and the chunk count is why

S1 costs **1.66 B per posting** against §4.3.2's "1 B per pair + array overhead". The overhead is
3,809,182 array headers at 16 B — **61 MB on a 177 MB payload** — and it is driven by the chunk *count*,
exactly as that section warned when it called the count the softest number in its table. An estimate
written per posting cannot see it; the count is a property of how many roaring containers a term's
postings span, which is a property of the corpus.

## 7. What this campaign settled

- **RAM passes at 0.85 GB against a 2.43–3.89 GB budget**, and the sidecar choice is free. Re-taken on the
  production structures (part 2.4): **0.70 GB**, impacts in the dictionary's leaves at 1.24 B per posting.
- **S1 over S2**, 2.3–2.5× smaller across a fifty-fold corpus-size range.
- **One builder, not two**; the build completes in 1.8 GB for a 0.85 GB result.
- **Persist, do not derive**, on a 9.9× ratio; sidecar format versioning enters P1.
- **§5.3's linear merge must become a galloping one**, with a per-expansion cost selector.
- **Phase 1's budget is a three-term expression**, and the cap is on summed postings.
- **Typo expansion defaults to literal prefix 2.**
- **Quality is a genuine two-way result**, with the infix regression measured and bounded.
- **Configuring §7's two analysis filters is worth 46% of the footprint** and 39% of the build on the
  CMS corpus; the second of them needs a per-attribute analyzer seam that does not exist.
- **A searchable field must carry a locale** (decided 2026-09-17). The rule, its three rejected
  alternatives and the migration cost it accepts are the last row of *Decisions taken* in the parent
  record; it must reach F1's `AttributeSchemaContract#validate` next to the `acceleratedFor(...)`
  refusal precedent.

## 8. Handed to P2, per §4.3.4

- **The degenerate directory is dense over all ranks and need not be.** S1's `single` plus `singleKeys`
  arrays cost 5 B per rank while only 47% of ranks use them on corpus A — and 74% on corpus B, where the
  waste inverts. A sparse representation past a measured occupancy threshold is the obvious saving.
- **The dense length table is charged over the primary-key *span*, not the population.** Corpus B spreads
  118,772 documents across a 1.4M-slot span and pays 16.6 MB per locale — roughly 12× what the data
  needs. Corpus A hid this completely, its keys being near-contiguous. A span-to-population test at build
  time selecting a sparse representation is the fix, and it is invisible on any corpus with dense keys.
- **S2's degenerate keys must store the full primary key, not its low 16 bits.** A chunk's container
  supplies the high bits; a degenerate key has no chunk and therefore no container, so low bits alone
  cannot identify the document — and being self-describing is the one property S2 is bought for. Costs
  2 B per rank across the whole directory.

## 9. What this campaign opened

- **The Czech and Slovak fan-outs differ by 2×, and that is by design (resolved 2026-10-01).** Over
  corpus B, each locale resolving to its own shipped chain with no generic fallback taken, the M7 fan-out
  is **cs 2.057 against sk 1.043** terms per position; 96.3% of Slovak positions take a single term,
  against 28.0% of Czech ones. Re-measured on 2026-10-01 against the analyzers as merged (PR #1453) with
  the same seed: identical to the third decimal. This was first recorded as a probable defect — either
  weak Slovak recall or Czech over-generation — on the claim that P5's fixtures were Czech-only. **That
  claim was wrong.** P5 swept every language's whole Hunspell lexicon
  ([`p5-prior-art-sk-pl-ro.md`](p5-prior-art-sk-pl-ro.md) §9.8) and found the same split there: 77–87%
  of Czech, Romanian and Polish words have at least two distinct stem variants once diacritics are
  folded, against **4.4%** of Slovak ones, and Slovak alone needed neither step gates nor the surface
  variant that adds a term whenever stemming changed the token. Slovak recall is not weaker either:
  `SlovakVariantStemmerLexiconTest` asserts that the variant set reaches the index-side stem for every
  word of the `sk_SK` lexicon, with zero exceptions. The corpus ratio is the lexicon ratio, seen through
  product text. What remains is a cost, not a defect: a Czech query token walks about twice the
  posting lists of a Slovak one, which the phase-1 budget in part 3 already absorbs.
- **The aggressive stemmer collapses unrelated lemmas, and that lands on phase-2 ranking.** Among corpus
  A's twenty-five widest posting lists, `muh` merges *můžete/můžou* with *muž/muže* — "can" with "man" —
  and `cl` merges *celý/celé* with *čelo/čelit*. Ordinary behaviour for an aggressive stemmer rather than
  a defect, but it is a precision cost, and it should be named here rather than discovered in P7.
- **A short stem is not a word, and reading one as one costs a day.** The term `lt` reaches a document
  frequency of 370,229 on corpus A and was initially read as a double-encoded `&lt;`, producing a false
  open item and a false work request to the export side. It is the Czech stem of *let / letech / letos /
  lety / letů*. **Any term read off a posting list must be resolved to its surface forms before a story
  is built on it** — `FulltextAnalyzer#analyze` hands the surface form to the consumer for exactly this.
- **One catalogue's `keywords` attribute is locked out of fulltext, permanently and knowingly.** It is
  filterable and not localized; localizing it would move its filter index into the per-locale partition
  and break a downstream consumer that filters without a locale, so the export side declined — correctly,
  the measurement-baseline invariant binding harder. Under the locale rule that field can never take
  `searchable()` as it stands, so fulltext over keywords there needs a second migration **plus** the
  query-side `entityLocaleEquals` change that was just declined. The cheap moment to do it has passed.

## 10. How the figures were taken, and what would invalidate them

- **Heap figures are JOL deep-retained owned graphs**, shared roots subtracted, in the **compressed-oops
  regime**. `-Xmx` above ~32 GB drops compressed oops and makes every reference 8 B instead of 4 B;
  nothing fails and nothing warns, and a figure taken that way cannot be summed with one taken below the
  threshold. The instruments print `VM layout: reference=NB` as their second line. **Read it before
  quoting a byte count.**
- **Build times need a quiet box.** On a shared one the same build over byte-identical input has moved
  41%. On a quiet box, three repetitions per cell spread 1–4%. Ratios taken within one session survive a
  shared box; absolute seconds do not.
- **Latency is JMH, `@Fork(1)`, single-threaded**, over a workload file of real posting lists and real
  impact bytes exported by the sidecar step — because JMH re-runs trial setup per parameter combination
  in its own JVM and a per-cell index rebuild would mean the grid never runs. What is *not* real in that
  workload is the grouping of terms into a token's expansions, which are drawn from the same frequency
  class rather than being true Levenshtein neighbours. **That bounds the claim: the latency step answers
  "what does width *w* cost", never "how wide is it".** The expansion step answers the second, and the
  two must be read together.
- **The quality step reports no latency at all**, deliberately, per §7.5.
- **Query sets are corpus-derived, not user-derived.** They carry the corpora's vocabulary and
  typos-by-construction, not a real distribution of what people search for; query length and brand skew
  are where the two would differ most.
- **What would invalidate the RAM verdict:** a different analysis chain. Every key count here is the
  output of P5's shipped chains, and the dictionary is the dominant term.

**One defect was filed along the way.** [#1600](https://github.com/FgForrest/evitaDB/issues/1600) —
`TransactionalBucketBPlusTree`'s convenience constructors reject every **even** block size. Latent
today, since every current caller passes an odd one, but `InvertedIndex.VALUE_BLOCK_SIZE` is 256, so
the natural call is precisely the one that cannot work. Every instrument in this campaign derives its
block sizes the long way around for that reason.
