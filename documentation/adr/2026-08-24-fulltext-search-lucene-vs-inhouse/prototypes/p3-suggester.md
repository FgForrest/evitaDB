# P3 — the suggester: prototype implementation plan

> **Status: an implementation plan, not a decision.** It follows on from the research
> [`../research.md`](../research.md) (v2, consolidated 2026-08-04, last revised 2026-08-12), namely §4.6,
> §7 (prototype P3), §8 (VK6, VK10, VK12) and open question O3. It builds over the structures proposed by
> [`p1-index-core.md`](p1-index-core.md) and over the analysis chain from
> [`p5-analyzers.md`](p5-analyzers.md).
>
> Written on 2026-08-12. The anchors into evitaDB's code were verified against the `dev` branch on the
> same day, the anchors into Lucene against the checkout `/www/oss/lucene` (branch `main`, `13796f80e`),
> the anchors into Typesense against `/www/oss/typesense` (v31, `ee7784f3`) and the anchors into
> Elasticsearch against `/www/oss/elasticsearch` (branch `main`, `9a100e2d0e41`, verified 2026-08-13).
> Claims about the existing solution are taken from the internal analyses of the Edee CMS client and
> its e-commerce layer, which are not published in this repository.
> Translated from Czech and moved into this record on 2026-08-24.

---

## 1. Goal, scope and criteria

P3 answers the question of whether **prefix and typo expansion over the term dictionary fits into the
budget of a single keystroke**. It is a latency prototype, not a quality prototype: the structures
already exist (P1), the analysis chain too (P5), and P3 builds over them a single new thing — a guided
walk of the dictionary by a Levenshtein automaton — plus scoring and selection.

The research (§7) sets a single criterion: p99 ≤ 5 ms per keystroke including typo expansion, ≤ 2 ms
without it. That number is, however, of little use for a plan, because the suggester in fact does two
very differently expensive things and measuring them together would mean not knowing which of them
consumed the budget.

### 1.1 The budget split into two legs

| Leg | What it does | Reads | Proposed budget |
|---|---|---|---|
| **Term** | prefix scan + typo expansion + scoring candidate terms | only the dictionary | ≤ 1.5 ms |
| **Entity** | an OR of the bitmaps of the top-M terms ∧ the must-match filter, top-N by composite | postings | ≤ 3.5 ms |

The split is not cosmetic. The term leg reads **only the keys and the buckets' cardinalities**, i.e. the
dictionary's pages; it never materializes postings. The entity leg by contrast reads postings and its
cost is exactly the one P1 §5.2 models — the sum of the postings lengths of all expanded terms. It means
the entity leg **has no cost model of its own**: it is phase 1 from P1 with the expansion cap set to M.
So if P1 finds the expansion knee in step K6, P3 does not seek it again; and if it does not find it, P3
has nothing to start from (see the handover P→3 in `p1-index-core.md`, §8).

The sum of 5 ms is attainable under this split only when M is small — of the order of single digits to
the low tens of terms. That is at the same time why the scoring of candidate terms (§4.4) cannot be
skimped on: it is the mechanism by which the cap M is spent on terms that bring something.

### 1.2 What P3 deliberately does not do

- **It does not deliver a DSL.** It addresses neither the constraint's shape nor the suggest endpoint's
  (O4 of the research, the parallel document `query-design.md`). It defines only the **contract** — what
  the suggester needs on input and what it returns (§4.7) — because without that not even a harness can
  be written.
- **It does not build the synonym and entity dictionary as a product.** Its producer is Sage (§1.1 of the
  research). P3 proposes the span matching mechanics and the shape of the hot swap (§4.6), but does not
  measure it: without a real artifact a measurement would have nothing to say.
- **It does not decide the rank profile.** Entity selection uses the default composite of §4.3 of the
  research, as P1 implements it. Profiles and the boost channel are P7.
- **It does not do highlighting.** That is per §4.6 of the research a re-analysis of the returned page at
  render time and has nothing to do with the index. It does, however, share with P4 the question of where
  the stored values come from — and the answer is in `p4-proximity-rerank.md`, not here.
- **It does not correct a whole phrase with a language model.** It is a capability established engines
  have and our design deliberately does not deliver, because it is paid for in the index. The reason and
  the conditions under which revising the decision would be worthwhile are in §4.8.

---

## 2. Links to the research and to the neighbouring prototypes

**What P3 adopts as its brief.** A prefix is a range scan of the sorted dictionary terminated by an OR of
the bitmaps of the found terms, with a cap on the number of expansions; a typo is a Levenshtein DFA
(`LevenshteinAutomata` from `lucene-core`, a hard cap of distance 2) intersected with the dictionary by a
guided walk of the B+ tree; the suggester is a derivative of the dictionary, not another structure —
candidate terms are scored by postings cardinality multiplied by the field's weight and entity
suggestions arise as an OR of the bitmaps of the top-M terms intersected with the must-match filter (§4.6
of the research).

**What P3 inherits from P1.** The dictionary in the shape of a `TransactionalBucketBPlusTree` with the key
`prefix(field) + term` (variant D1, `p1-index-core.md`, §4.1), postings as a bucket's value (§4.2 there)
and — most importantly — **the measured expansion knee** from step K6. P1 itself labels this item the
handover P→3 and justifies it by P3 running over the same structures with a substantially stricter latency
budget.

**What P3 inherits from P5.** The analysis chain and its order: tokenization, lowercasing, capturing the
surface form, stopwords, stemming, folding diacritics (`p5-analyzers.md`, §7.3). Further the
recommendation to leave `ASCIIFoldingFilter` after the stemmer and to have it **on by default for Czech
and Slovak** (same place). Both matter for P3, because the Levenshtein distance has to be measured in the
same space the dictionary is in.

**What P5 explicitly delegates to P3.** Two things, both at the end of its §7.3 and §6:

1. **The exact typo tolerance thresholds (O3).** P5 ensures only that they are measured in the right
   space; the thresholds themselves are to be decided by P3. Addressed in §4.3.
2. **The qualitative half of the fork "stem only versus stem and surface form".** The dictionary's size in
   both modes is measured by P1 (K3), but whether the stem variant is usable for a suggester at all is for
   P3 to say. Addressed in §4.5.

**What P3 hands on.** The decision on the thresholds (O3) is input for F1; the measured cost of the guided
walk is input for P4, because both legs share the same latency cap whenever search-as-you-type runs over a
query that also has proximity.

---

## 3. What already exists in the code — verified anchors

### 3.1 `seekCeil` over the dictionary exists and is called `cursor(K)`

The guided walk §4.6 of the research describes stands or falls with a single primitive: *place the cursor
on the first key greater than or equal to X*. That is in the repo:

```java
BucketCursor<K> cursor(@Nonnull K value);
```

`TransactionalBucketBPlusTree.java:1178`, the contract in `BucketBPlusTree.java:63-70`, and the JavaDoc is
explicit in this respect — the cursor is established at the first bucket whose value is greater than or
equal to the one passed, **which need not exist in the tree**. That is exactly the `seekCeil` the whole
`AutomatonTermsEnum` pattern from Lucene rests on (§3.6 below).

The cursor itself (`TransactionalBucketBPlusTree.java:2825-2893`) exposes eight methods, of which four are
interesting for P3: `boolean next()`, `K value()`, `int size()` and `long currentLeafId()`. The third is
key for the term leg, because it gives **the bucket's cardinality without the postings being
materialized** — the scoring of terms by popularity can therefore be decided at the price of walking the
dictionary. The same outside the cursor is done by `cardinalityOf`
(`TransactionalBucketBPlusTree.java:1102`).

### 3.2 The cursor cannot be relocated, though — and that is the main cost of a guided walk

What conversely is **not** in the repo: the ability to reposition an existing cursor onto another key. The
cursor has no `seek`, no `reposition` nor anything similar; the only path to a new key is to construct a
new cursor, and that descends from the root (`createCursor(K)`, `TransactionalBucketBPlusTree.java` — the
path is captured into arrays during the descent, the counterpart of `TransactionalObjectBPlusTree.java:889`).

For a sequential walk that does not matter: `next()` goes into the next leaf sideways along the captured
path, not through the root. For a guided walk it matters a great deal, though, because that consists
**almost exclusively of seeks**. The arithmetic: a dictionary of 400–500 thousand keys with a leaf block of
64 has three to four levels, so one descent is three to four binary searches in internal nodes plus one in
the leaf. The binary search in a leaf is moreover over a front-coded column, where every probe decodes a
key from the nearest restart point, i.e. up to fifteen steps forward
(`FrontCodedStringColumn.java:471`, restart interval 16 at `:121`). Individually it is cheap; multiplied by
hundreds to thousands of seeks per keystroke it is an item.

**A mitigation somebody has to be assigned:** a large part of a guided walk's seeks target a key that lies
**in the same leaf** the cursor currently stands in — the automaton moved one term further, not half the
dictionary. A check "does X fall into the range of keys of the current leaf?" and, if so, repositioning
within the leaf via `findKeyPosition` (`FrontCodedStringColumn.java:471`, the interface
`ValueColumn.java:177`) removes the descent from the root in the vast majority of cases. All the mechanics
for it are in the tree, they are merely `protected` or `private`
(`AbstractTransactionalBPlusTree.java:765` onwards, in the bucket tree its own copy from
`TransactionalBucketBPlusTree.java:5768`).

Who writes it is a question of the order of work, not a technical question — and therefore I record it the
same way P1 did with Q9: **P3 builds it itself**, as its step K2 (§5), because without it it has nothing
to meet the criterion with, and nobody else needs it sooner.

### 3.3 Front coding is the best case for a prefix scan, the worse one for a seek

A sequential walk by keys is exactly the access pattern `FrontCodedStringColumn` is designed for: decoding
goes forward from a restart point, the shared prefix is not copied, and in variant D1 (the key
`prefix(field) + term`) neighbouring keys share the field identifier as well. Prefix expansion is therefore
a cheap operation over a cheap structure.

A random probe is by contrast O(log n × 16), as §3.2 describes. The difference between the two modes is an
order of magnitude and it is the main reason §4.2 recommends the walking variant over one that would probe
the dictionary candidate by candidate.

Two things to remember, both from the same file. The fast comparison path — an allocation-free comparison
of raw UTF-8 bytes — applies only when the column is BMP-safe, the tree in natural order and the comparator
natural (`FrontCodedStringColumn.java:477-486`). And mutating a key inside a leaf is O(block size), because
the blob is decoded, spliced and re-encoded; for P3 that is immaterial (it writes nothing), for F1 it is
not.

### 3.4 An invariant nobody may violate: the dictionary is in natural lexicographic order

This is the most important constraint of the whole plan and it needs stating before somebody reaches for
collation. `ValueColumnFactory.forKey(String.class, comparator)` (`ValueColumnFactory.java:83`) accepts a
comparator, so technically nothing prevents building the term dictionary in the order given by a
`Collator` — the way that elsewhere in the engine makes sense for ordering by language.

For a term dictionary it would be fatal, though, and silently so. A guided walk by an automaton works by
computing from the current state **the lexicographically smallest string that can still be accepted** and
seating itself on it in the dictionary (§3.6). That computation assumes the order in the dictionary is the
lexicographic order over code points. In collation order the seek would end up elsewhere than the automaton
expects and the walk would **skip terms without anything crashing** — it would simply return fewer
candidates. A collation comparator would moreover switch off the fast byte-compare path of §3.3 as well.

Recorded as an invariant: **the term dictionary is built with natural `String` order, never with a
collator.** Collation belongs in `SortIndex`, not here. The ordering differences collation addresses are
moot for a term dictionary — nobody shows a user a term dictionary sorted alphabetically.

### 3.5 From Lucene, `Automaton` and `LevenshteinAutomata` suffice — `CompiledAutomaton` does not

The research (§3, VK6) says `LevenshteinAutomata` is index-free, whereas `CompiledAutomaton` is not, and
that we therefore write the intersection with the dictionary ourselves. Reading the sources confirms it and
at the same time shows that the extent of what is adopted is **smaller than expected**.

`LevenshteinAutomata` has two constructors: `(String input, boolean withTranspositions)` and `(int[] word,
int alphaMax, boolean withTranspositions)`
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/util/automaton/LevenshteinAutomata.java:57` and
`:65`). The second one's input is code points and `alphaMax` is the alphabet's maximum symbol. The hard cap
`MAXIMUM_SUPPORTED_DISTANCE = 2` is at `:37`; `toAutomaton(int n)` is at `:134`,
`toAutomaton(int n, String prefix)` at `:150`. For `n == 0` it returns a plain literal automaton
(`:152-153`), for `n > 2` it returns **`null`**, not an exception (`:156`) — that has to be borne in mind.

The `prefix` parameter is not a filter: it is a chain of states with single-character transitions placed
*before* the Levenshtein automaton (`:169-180`), so **no edit is permitted inside the prefix**. The caller
has to trim the prefix from the passed word themselves; that is exactly what
`FuzzyAutomatonBuilder.java:50-56` does, building `LevenshteinAutomata` over the *suffix* and attaching the
prefix only in `toAutomaton(i, prefix)` at `:63`.

The returned automaton is deterministic, without dead states and unminimized — the invariants are recorded
in the JavaDoc at `:126-132` and enforced at `:218-220`.

And now the essential part. Both primitives the guided walk needs are provided by **`Automaton` itself**:

- `int step(int state, int label)` (`Automaton.java:657`) — a binary search in the state's sorted
  transitions, returning the target or −1;
- `Automaton implements TransitionAccessor` (`Automaton.java:45`), i.e. `getNumTransitions`,
  `initTransition` and `getNextTransition` (`TransitionAccessor.java:27-38`) over a reusable mutable cursor
  `Transition` (`Transition.java:36-51`, five fields, no allocation in the loop).

It means P3 **needs neither `CompiledAutomaton`, nor `RunAutomaton`, nor a UTF32→UTF8 conversion, nor
determinization**. The path Lucene takes contains those steps only because its dictionary is in byte space:
`CompiledAutomaton.java:237` calls `new UTF32ToUTF8().convert(...)` and `:260` then
`Operations.determinize(binary, Integer.MAX_VALUE)` — with the work limit `Integer.MAX_VALUE`, not with the
default `DEFAULT_DETERMINIZE_WORK_LIMIT = 10000` (`Operations.java:63`). Our dictionary is in `String`
space, so that whole block is skipped. It is at the same time the best argument for the choice of space in
§4.2.2.

Two things from `CompiledAutomaton` moreover turned out to be blind alleys not worth porting:
`commonSuffixRef` is not computed at all for finite languages (`:242-243`), and fuzzy automata are always
constructed as finite (`FuzzyAutomatonBuilder.java:63` passes `finite = true`); `sinkState` has not a
single reader in the whole Lucene repo.

### 3.6 The pattern to be reimplemented: `AutomatonTermsEnum`, not `IntersectTermsEnum`

Lucene has **two** implementations of intersecting an automaton with a dictionary and the right one has to
be taken.

**`AutomatonTermsEnum`**
(`/www/oss/lucene/lucene/core/src/java/org/apache/lucene/index/AutomatonTermsEnum.java:48`) is the general
path and its algorithm is described in the class's JavaDoc at `:34-44`: while matches come out, read
sequentially; as soon as a match fails, skip to the nearest lexicographically greater string that does not
lead into a rejecting state. The core is `nextString(int state, int position)` at `:262-317`, and the answer
to the question "what is that primitive *the smallest label that can follow from state S*" reads: **a
linear scan of the state's sorted transitions while `transition.max >= c`, and then `max(c, transition.min)`
is emitted** (`:279-289`). That is followed by a minimal descent — while the state is not accepting, always
take the first outgoing transition and append its `min` (`:294-312`). The result goes straight into
`seekCeil`. A complement is `backtrack(int position)` at `:326`.

It transfers into our world without remainder: `seekCeil` is `cursor(K)` (§3.1), sequential reading is
`next()`, and `nextString` is purely automaton work without any tie to the index. Moreover on the fuzzy
path the whole cycle-detection apparatus (`setLinear`, `visited`) falls away, because it is allocated only
for infinite languages (`:90`) — a fuzzy automaton is finite.

**`IntersectTermsEnum`** (`lucene/core/.../codecs/lucene103/blocktree/IntersectTermsEnum.java:41` — note
that in the `main` branch the block tree is `lucene103`, not `lucene90`; a copy under `lucene90` lives only
in the `backward-codecs` module) is by contrast a simultaneous descent of two trees: the automaton and the
dictionary's block trie. It is faster, because it can **skip whole blocks of the dictionary** with the
automaton — the comparison `nextFloorLabel <= transition.min` decides
(`IntersectTermsEnumFrame.java:118` and `:156`).

*Rejected for P3 — why:* that trick needs the dictionary structure to emit per page "the smallest label the
page contains" and a hierarchy of floor blocks. Our B+ tree exposes nothing of the kind:
`LeafBPlusTreeNode` (`LeafBPlusTreeNode.java:42-88`) exposes the page's ordinal number, a dirty flag and an
array of values, but no label boundary; the leaves moreover do not even have sibling pointers. Introducing
a seam for it would mean reaching into the tree's nodes, which is work incommensurable with what P3
measures. **What would have to be different for it to be worth revisiting:** should it turn out that even
with the intra-leaf seek of §3.2 the cost of seeks is the dominant item of the budget — then the
simultaneous descent is the only way further and the seam pays off. That is, however, a result of P3's
measurement, not its input.

### 3.7 `FuzzySuggester` as an analogy — and its three lessons

`lucene/suggest/.../search/suggest/analyzing/FuzzySuggester.java:66` solves the same task over an FST
instead of over a dictionary. It cannot be used directly (it wants an FST, we have a B+ tree), but three of
its properties are instructive for P3.

**First — its default thresholds are market conventions in a readable form:**
`DEFAULT_MIN_FUZZY_LENGTH = 3` (`:82`), `DEFAULT_NON_FUZZY_PREFIX = 1` (`:85`), `DEFAULT_MAX_EDITS = 1`
(`:88`), `DEFAULT_TRANSPOSITIONS = true` (`:91`). Their application is at `:245-246`: if the length is less
than or equal to `nonFuzzyPrefix` or less than `minFuzzyLength`, no Levenshtein automaton is built at all
and a plain literal is used. This is direct input into §4.3.

**Second — it measures the distance in *byte* space, and that is a trap for Czech.** The constructor is
passed `unicodeAware ? Character.MAX_CODE_POINT : 255` (`:257`) and the default `unicodeAware` is `false`
(`:77`); the class's JavaDoc at `:50-52` says it outright — "the analyzed **bytes** must be at least 3 bytes
… the first 1 **byte** is not allowed to be edited". It means one character with a diacritic, which is two
bytes in UTF-8, consumes **two edit steps**. For English that is immaterial, for Czech it is not. Developed
in §4.2.2.

**Third — it admits a limit that concerns us too.** The JavaDoc at `:56-57`: "This suggester does not boost
suggestions that required no edits over suggestions that did require edits. This is a known limitation."
Our design does not have this problem, because exactness is a separate lane of the cascade (§4.4) — but it
is worth recording that it *is* a problem one can run into.

Supplementarily, `AnalyzingSuggester`, which `FuzzySuggester` inherits from, shows how the weight is baked
into the FST: as a **cost** `Integer.MAX_VALUE − weight` (`AnalyzingSuggester.java:907` and `:912`), so the
lowest cost wins (`:919`). That interests us not as mechanics but as a confirmation that a candidate's
weight is in suggesters a scalar precomputed at build time — which for us it will not be, because a
bucket's cardinality is available on read for free (§3.1).

### 3.8 Span matching of phrases: `SynonymMap` and its two caveats

VK12 of the research labels `SynonymMap` and `SynonymGraphFilter` the canonical gazetteer mechanics.
Verification confirms and refines it.

Multi-word phrases are in `SynonymMap` **one FST key with the words separated by the character U+0000** —
`WORD_SEPARATOR = 0` at `lucene/analysis/common/.../analysis/synonym/SynonymMap.java:50`, the sugar
`join(String[], CharsRefBuilder)` at `:101`, the prohibition on empty words at `:126-127`. The structure
carries also a `BytesRefHash words` (`:56`) as a shared dictionary of output words and
`maxHorizontalContext` (`:59`) as the lookahead budget.

Matching in `SynonymGraphFilter` is **greedy longest from every position**: in `parse()` every final edge
of the automaton is written into `matchOutput` instead of being returned
(`SynonymGraphFilter.java:371-376`), the walk continues only when some rule can cross a word boundary
(`:380`), and after a commit `lookaheadNextRead` moves by the whole length of the match (`:410`), so
**matches never overlap**. The authors comment on it themselves at `:42-60`: Aho–Corasick would be more
efficient, but it finds all matches and would have to be supplemented with an enforcement of greediness.

The second caveat is the hot swap. All three fields of `SynonymMap` are `final`, no mutators exist and the
only path to building one is `Builder.build()` (`:222`); `SynonymGraphFilterFactory.inform(...)` (`:134`,
the assignment at `:161`) is a one-off initialization, not a reload. Replacing it at runtime therefore means
**rebuilding the whole FST and swapping the reference**.

### 3.9 Elasticsearch: both alternatives to a dictionary derivative and what they take in return

Verification over the Elasticsearch checkout (branch `main`, `9a100e2d0e41`, 2026-08-13) is valuable for P3
from an unexpected direction. The research claims the suggester is a derivative of the dictionary and
therefore needs no additional structure; Elasticsearch offers precisely **both** counter-alternatives to
that claim and with both the code shows what they are paid for with. The package is
`server/src/main/java/org/elasticsearch/search/suggest/`.

**The completion suggester is a standalone index structure, not a derivative.** The `completion` field type
(`CompletionFieldMapper.java`, JavaDoc at `:56`) indexes the values **as a weighted finite automaton**
against which `CompletionSuggester` then searches. Three properties of that choice matter for us. The
weight is part of the indexed document — the `weight` field at `:108`, processing at `:439` — so a
suggestion's popularity is baked in at indexing time and changing it means reindexing the document. The
field has its own pair of analyzers (`:187`) and its own parameters `preserve_separators` and
`preserve_position_increments` (`:124` and `:130`), i.e. a second analysis configuration beside the one the
index already has. And suggestions can be filtered only through `contexts` (`:135`), because ordinary index
filters cannot be applied to a finite automaton; their number is limited by the constant
`COMPLETION_CONTEXTS_LIMIT` (`:80`) and exceeding it is an error (`:218`).

**`search_as_you_type` is four times the indexed fields.** The type `SearchAsYouTypeFieldMapper` (the
module `modules/mapper-extras/`) describes in its JavaDoc at `:79-82` what one such field in fact creates: a
root field, a `ShingleFieldMapper` for pairs of adjacent words, another for triples up to
`max_shingle_size`, and finally a `PrefixFieldMapper` with edge n-grams over the longest shingles. With the
default `max_shingle_size = 3` (`:95`, the permitted range 2 to 4 at `:88-89`) one logical field therefore
creates **four physically indexed fields**.

**The phrase suggester corrects a whole phrase — and pays for it with shingles.** The subpackage `phrase/`
is the most sophisticated piece of the whole package: it builds on the noisy-channel model
(`NoisyChannelSpellChecker.java`) and on a **language model over n-grams**. Candidates for correcting
individual words (`DirectCandidateGenerator`) are composed into whole phrases and those are scored by the
probability of the sequence, with three smoothing models (`Laplace`, `StupidBackoff`,
`LinearInterpolation`), each with its own scorer. The condition is that **shingles** be in the index, i.e. a
field indexed with word n-grams, introduced into the mapping in advance. Beside it stands the term
suggester (the subpackage `term/`, over Lucene's `DirectSpellChecker`), which corrects words one by one and
does not know the sentence context — and that is exactly the task P3 solves with a Levenshtein automaton.

---

## 4. The implementation design

### 4.1 Prefix expansion

The mechanics are straightforward and §3.1 gives all the parts. The cursor is established at
`cursor(fieldPrefix + p)`, where `p` is the user's prefix, and walks with `next()` while the key starts with
the string `fieldPrefix + p`. The first key that does not start with it terminates the walk — nothing
further has to be tested, because the dictionary is in lexicographic order (§3.4). The walk is repeated for
every searched field; with single digits of fields that is negligible and it is the same work a tree per
field would do (`p1-index-core.md`, §4.1).

A non-obvious property that has to be in the plan: **the first keystroke is the most expensive, not the
last.** The prefix "a" over a dictionary of 400 thousand keys matches tens of thousands of terms; the prefix
"cor" matches tens. The 5 ms budget is therefore not consumed evenly and a measurement that does not see
that will look better than reality. Three mitigations, ordered by how self-evident I consider them:

1. **A minimum prefix length before suggesting starts at all.** A product convention (two to three
   characters) and by far the cheapest measure — a single-character prefix carries no information anyway.
   *Recommended as the default.*
2. **A hard cap on the number of keys inspected.** The walk stops after N keys and returns what it
   collected. Cheap and safe from the latency point of view, but distorting: the cap truncates the
   dictionary alphabetically, so precisely the terms at the end of the range drop out. Usable only as
   insurance against pathology, not as an ordinary mode.
3. **Precomputed bitmaps of hot prefixes.** The research mentions them in the RAM budget (§4.8, "optional
   prefix bitmaps, hot prefixes only, can be switched off"). They make sense only once measurement shows
   short prefixes really do overrun the budget, and their cost is maintenance on write. *Do not build in
   P3; measure whether they would be of any use.*

The selection of the top-M from the walk is done with a bounded heap over the score of §4.4, never by
materializing all candidates. Mind the allocation: `BucketCursor.value()` emits a `String`, so a long walk
allocates one string per key. For measurement that is fine, for F1 it is the place where an allocation-free
variant of reading the key comes in handy — and `FrontCodedStringColumn` has an internal path for it (the
allocation-free comparison of raw bytes, `:477-486`), it merely is not exposed.

**What P3 thereby eliminates.** The analysis of the existing client
(internal, §2.4) and of the e-commerce layer
(internal, §4.6) shows that prefix search today has no walking path at all —
prefixes are instead **written into the index in advance**. In the CMS client that is done by
`SummonFilter`: at indexing time it generates for every token all its prefixes down to a minimum length
(configurable, default 3) and inserts them into the index at the same position, so `notebook` is stored also
as `noteboo`, `notebo`, `noteb`, `note` and `not`. A prefix query is then free, because the prefix is an
ordinary term — and a prefix query in the query language is therefore conversely switched off:
`NoPrefixQueryParser` rewrites `noteb*` into a plain `noteb`. The e-commerce layer goes the same way with
its own "index builders": the EAN and the catalog number are written with all prefixes down to length 3,
the brand name too, streets and towns word by word from length 1, and **an order number even with all its
substrings of length at least 3**, i.e. quadratically many terms for a single value.

All those constructions circumvent the same thing: the engine in that deployment cannot do prefix or infix
search at runtime. The dictionary walk of §4.1 does at query time what is paid for there at write time, so
**P3 eliminates this whole class of hacks** — and the cost moves from index size and the write path into
query latency. Both are measurable and §6.2 adds a separate comparison for it. Besides the space saving,
that trade has one more consequence worth stating: expansion **dilutes the frequency statistics of terms**,
because the prefix `not` has the cardinality of the sum of all the words beginning with it. Our scoring of
candidates by postings cardinality (§4.4) does not have this deformation, because it reads the cardinality
over the real term, not over its artificially indexed prefix.

### 4.2 Typo expansion: intersecting a DFA with the dictionary

#### 4.2.1 Three paths and a recommendation

**Variant T1 — naive enumeration of the dictionary and a test by the automaton.** Walk all the keys in the
field's range and run each through the automaton as an acceptor.
- *For:* trivial, no new mechanics, independent of the dictionary's order.
- *Against:* linear in the dictionary's size. With 400–500 thousand keys and a budget of single-digit
  milliseconds it is out, even if the test itself were free.
- *Where it nevertheless has a place:* as a reference implementation in the tests. The guided walk has to
  return **exactly the same set** as the naive enumeration, and that is a property easy to test only when
  there is something to compare against. I recommend writing it precisely for that, not as a production
  path. (`p1-index-core.md`, §6, step K5, permits the same for measuring phase 1's latency.)

**Variant T2 — a port of `AutomatonTermsEnum` over `cursor(K)` (recommended).** The walk alternates
sequential reading and seeks per §3.6: read `next()` while the automaton accepts; on a rejection compute
`nextString` and seat a new `cursor(K)`.
- *For:* the only primitive it requires is already in the tree (§3.1); the automaton part is purely adopted
  and has no tie to the index; the complexity is proportional to the number of "candidate boundaries", not
  to the dictionary's size.
- *Against:* every seek is a descent from the root, until the intra-leaf fast path is finished (§3.2).
- *Recommended.*

**Variant T3 — a simultaneous descent after the pattern of `IntersectTermsEnum`.** Rejected in §3.6: it
requires label boundaries of pages, which the B+ tree does not expose. Left as a path in case measurement
shows the cost of seeks dominant even after the intra-leaf fast path is introduced.

#### 4.2.2 The space the distance is measured in

A fork `FuzzySuggester` and `FuzzyQuery` each solve differently (§3.7) and which for Czech is decided
unambiguously.

**Byte space** (`alphaMax = 255`, the `FuzzySuggester` path): every character with a diacritic costs two
bytes in UTF-8, so substituting `ě` for `e` is a distance of **two**, not one. With the cap
`MAXIMUM_SUPPORTED_DISTANCE = 2` a user writing without diacritics would consume the whole budget on a
single character. That is exactly the interaction O3 warns of and which `p5-analyzers.md`, §7.3 develops.

**Code point space** (`alphaMax = Character.MAX_CODE_POINT`, the `FuzzyQuery` path via
`FuzzyAutomatonBuilder.java:55`): substituting `ě` for `e` is a distance of one, as the user expects.

**Recommendation: code point space.** Besides correctness for Czech it has two further advantages. The
dictionary is in `String` space, so the UTF32→UTF8 conversion and the determinization Lucene does only
because of its byte dictionary fall away (§3.5) — it is therefore *less* code at the same time. And the
thresholds of §4.3, formulated in characters, then have the same meaning for all languages.

The cost is a single one and it is small: were a dense transition table after the pattern of `RunAutomaton`
ever built, the step for symbols above 255 would be a binary search instead of an array index
(`RunAutomaton.java:174-178`). P3 builds no table — it walks `Automaton.step` directly (§3.5) — so it does
not concern it at all.

An assumption following from that and which has to hold: the dictionary is in natural `String` order (§3.4).
The order of code points and the order of UTF-16 code units diverge only for supplementary characters above
the BMP; for a term dictionary of European languages that is a non-existent case, but it is an assumption,
not a truth, and it belongs in the tests (`FrontCodedStringColumn` has its own flag `bmpSafe` for the same,
`:243`).

#### 4.2.3 Combining a prefix and a typo

The research mentions both expansions side by side; in fact **their product** is needed too, because
search-as-you-type over a mistyped word is a common case ("blakc" as the half-written "black"). Lucene has
the ready-made shape for it: `toAutomaton(int n, String prefix)` (`LevenshteinAutomata.java:150`) produces
one automaton in which the prefix is a literal chain of states and edits are possible only beyond it.

Two different roles of the word "prefix" follow for P3, which must not be confused:

- **A non-editable prefix** (`nonFuzzyPrefix` in `FuzzySuggester`) is *the first k characters in which no
  typo is permitted*. It shrinks the candidate set and is both a performance and a quality measure.
- **An open search-as-you-type prefix** is *the unfinished end of a word*, where any continuation must be
  permitted.

The query's last token needs both at once: a fixed beginning, tolerance to a typo in the middle and an open
end. The open end does not get into a Levenshtein automaton — it describes the language of strings within
distance n of a given word, not their extensions. Three options:

1. **Union the automaton with its "prefix" variant** — for every accepting state add a loop over the whole
   alphabet. It produces an infinite language, by which the simplification of §3.6 (finite languages need no
   cycle detection) is lost and the walk has to introduce the `visited` apparatus.
2. **Two independent expansions and a union of their results** — a prefix scan without typos (§4.1) plus a
   typo expansion of the whole written token (without an open end). The candidates are unioned, the better
   exactness class is taken.
3. **Typo expansion only from the second token from the end, the last token prefix-only.** The cheapest, but
   it will not find "blakc".

**Recommendation: option 2.** It is two simple things side by side instead of one complex one, both are
already in the plan, they are measured separately (which §1.1 requires anyway) and it does not bring an
infinite language into the walk. The disadvantage — it will not find a term that has a typo *and* is
unfinished — can be made up by running the typo expansion over the prefix itself too; whether that is worth
it will be shown by the qualitative evaluation (§6.3).

#### 4.2.4 A cascade of automata instead of a single one

`FuzzyAutomatonBuilder.buildAutomatonSet()` (`FuzzyAutomatonBuilder.java:59`) returns **`maxEdits + 1`
automata**, where index *i* is the automaton for distance up to *i*. `FuzzyTermsEnum` does two things with
that and both are worth adopting.

It enumerates with the widest one and **classifies with the narrower ones as mere acceptors**: after finding
a term it tries `matches(term, ed − 1)` and lowers while that works out (`FuzzyTermsEnum.java:242-248`,
`matches` at `:277`). The term's real edit distance is thereby learned for free — and that is precisely the
value lane 2 of the composite needs (§4.3 of the research, "255 − the weighted sum of typos") as does the
suggester's exactness class (§4.4).

The second thing it does is **lowering `maxEdits` at runtime** when a wider distance can no longer beat the
worst candidate in the heap (`bottomChanged` at `:194`, the core at `:204-210`). That does not transfer,
because it rests on Lucene scoring with a boost derived from the edit distance; our suggester scores by
postings cardinality (§4.4), so "ed = 2 can no longer win" does not hold for us — a rare term at distance 1
may be a worse candidate than a frequent one at distance 2. **A transferable variant of the same is
different:** run ed = 0, then ed = 1, and ed = 2 only when the preceding rounds did not fill M candidates.
The automaton for ed = 1 has orders of magnitude fewer states and accepts orders of magnitude fewer terms,
so in the ordinary case ed = 2 is not built at all. That is an optimization with a large expected benefit
and zero risk — it belongs in the prototype, not in "when there is time".

### 4.3 O3: typo tolerance thresholds

The research states question O3 as "length thresholds, the first letter, diacritics versus a typo" and
`p5-analyzers.md` (§7.3) explicitly leaves it to P3, saying it will only ensure that the spaces agree. Here
is a proposed answer.

**Parity with the existing solution is not maintained here, because there is nothing to maintain.** The
analysis of the Edee CMS client (internal, §4.10) searched for
`FuzzyQuery`, `DirectSpellChecker` and `LevenshteinDistance` in the Java code of all fourteen modules and
**found nothing**: typo tolerance does not exist at all in the existing solution and the only softness of
matching comes from stemming and from the summon prefixes (§4.1). Anything P3 delivers in this area is
therefore a pure increment — no regression is threatened and there is nothing to be measured against. The
thresholds below are therefore a choice, not a compromise with established behaviour.

**Diacritics are solved before the thresholds are reached.** P5 recommends `ASCIIFoldingFilter` after the
stemmer, on by default for Czech and Slovak. If that is done, "cerna" against "černá" is distance **zero**,
not two, and the whole typo budget stays for real typos. P3 changes nothing about it and adopts P5's
decision. The rest of this section therefore applies **in the folded space**.

One unfinished detail P5 named and nobody wrote up remains, though: the recommended variant 3 of §6 of P5
(the dictionary holds the surface form as well as the stem) captures the surface form **before** the
folding. The suggester therefore has two spaces available and has to choose which it expands in. The answer
is that it **expands in the folded one and displays the unfolded one** — a candidate is found as `cerna`,
the user is shown `černá`. That requires the folded → surface form link to be traceable, which is the same
indirection variant 3 introduces anyway.

**Length thresholds.** The market conventions of §4.6 of the research (Algolia, Meilisearch) and Lucene's
defaults (§3.7) converge on this:

| Term length (characters, after folding) | Permitted distance | Source of the convention |
|---|---|---|
| 1–3 | 0 | `DEFAULT_MIN_FUZZY_LENGTH = 3` (`FuzzySuggester.java:82`) |
| 4–7 | 1 | Algolia/Meilisearch: one typo from ~4–5 characters |
| 8 and more | 2 | the same: two typos from ~8–9 characters, the class's cap is 2 anyway |

**The first letter does not count**, i.e. `nonFuzzyPrefix = 1` (`FuzzySuggester.java:85`). The reason is half
qualitative and half performance-related: a typo in the first letter is rare (the user knows what the word
starts with), and excluding it cuts off an enormous part of the candidate space, because the seek is
immediately limited to one subtree of the dictionary. `LevenshteinAutomata.toAutomaton(n, prefix)` supports
it directly (§3.5).

**Switch transpositions on** (`DEFAULT_TRANSPOSITIONS = true`, `FuzzySuggester.java:91`). Swapping adjacent
characters is the most frequent type of typo when typing on a keyboard and without transpositions it costs
two edits instead of one.

**What is really open about those thresholds** and what P3 is to measure: the thresholds 4 and 8 are adopted
from engines working with **surface forms of English**. Czech after stemming and folding gives terms shorter
than what the user typed — and P5 warns of it (§6, "a term two characters shorter has a smaller budget for
typos"). The question therefore reads whether the thresholds are to be computed from the length of the
**typed token**, or from the length of the **term in the dictionary**. Proposal: **from the typed token**,
because the thresholds model the user's behaviour at the keyboard, not morphology. The practical consequence
is that the thresholds are evaluated once over the input and enter the expansion as an already decided
distance.

### 4.4 Scoring candidate terms

The research (§4.6) says "scored by postings cardinality (popularity) × the field's weight". That is the
right list of inputs, but a product is the wrong composition — and it is worth saying why, because it is the
same argument by which §2.1 of the research rejects BM25.

A product requires both quantities to be on comparable scales, and they are not: postings cardinality is an
integer across five orders of magnitude, a field's weight is a small coefficient. Nobody can explain the
resulting scalar and tuning it is a search for constants. That is exactly what the design avoids everywhere
else.

**Recommendation: score terms with the same cascade the design uses on entities** — a lexicographic
comparison of several discrete criteria, not a single number:

| Order | Criterion | Source of the value |
|---|---|---|
| 1 | exactness class: exact > prefix > fuzzy(1) > fuzzy(2) | which automaton accepted the term (§4.2.4) |
| 2 | the best weight of a field the term is in | the schema, overridable by the query |
| 3 | postings cardinality (popularity) | `BucketCursor.size()` (§3.1) |

The precedent for the third row is direct: Typesense orders the candidates of typo and prefix expansion by
`rank_tokens_by = FREQUENCY` and it is its only frequency statistic at all (§8 of the research, confirmed at
the Typesense verification). The precedent for the first is the whole cascade of §2.1. The advantage is that
it composes into the same 64-bit composite as everything else and that it is explainable ("it ranks higher
because it is an exact match in the name").

One trap that has to be in the tests: cardinality as popularity **favours terms close to stopwords**. If the
analyzer did not filter stopwords out (and for short e-commerce fields we sometimes do not want to filter
them out), they float to the top. The cascade partly solves it by the field's weight being above
cardinality, but not entirely. Watch it with the qualitative evaluation (§6.3), not with a constant.

### 4.5 The fork "stem versus surface form" from the suggester's point of view

P5 (§6) leaves open whether the dictionary will carry only stems, or stems and surface forms, and sends the
qualitative half of the decision here. From the suggester's point of view the answer is unambiguous and has
to be stated without circumlocution: **stems cannot be shown to a user.**

It is not aesthetics. The Czech stemmer is algorithmic and its outputs are not words — upstream says so
directly in a comment in its own test and it is visible on the forms of the word "muž", which all converge
on `muh` (`p5-analyzers.md`, §5.1, with a reference to `TestCzechStemmer.java:64-70`). A suggester offering
the user "muh" is a defective product.

Three possible configurations and their cost for the suggester follow:

1. **A dictionary with stems only.** The suggester would have to translate the stem back into a displayable
   form, and that is impossible — the information was discarded by the analysis. The only substitute would be
   a second structure mapping a stem onto its most frequent surface form, which is *the same* cost as
   variant 3, only built on the side. **Unusable for a suggester.**
2. **A dictionary with surface forms only.** The suggester is ideal — the prefix, the typo and the display
   all work with what the user sees. The cost is the recall of ordinary searching, which in Czech drops
   unacceptably (`p5-analyzers.md`, §6).
3. **A dictionary with both.** Prefix and typo run over the surface forms, the match is evaluated through the
   stem, the surface form is displayed. **The only good variant for a suggester.**

**P3's recommendation into that fork: variant 3 for every profile where a suggester is deployed.** P5 leans
towards variant 1 for the CMS profile on the grounds that there the dictionary is substantially bigger and
the doubling hurts more; that is legitimate, but it means that **in the CMS profile there will either be no
suggester, or it will run over a separately maintained dictionary of surface forms for selected fields only**
(typically headings, not the article's body). I consider that possibility a reasonable compromise and it is
worth measuring — headings are a fraction of the text's volume.

### 4.6 Synonyms and entities: one mechanism, two artifacts

The research (§4.6) wants span matching over a hot-swappable dictionary and points at `SynonymMap` as the
model; §1.3 adds a second consumer of the same mechanics — the gazetteer of recognized entities. The design
is to have **one implementation and two artifacts**, exactly as the research says.

A non-obvious conclusion from the verification (§3.8) is that **an FST is not needed for it**. A phrase in
`SynonymMap` is one key with the words separated by U+0000; a key composed of several words separated by a
separator is, however, exactly what our term dictionary can do — it is a
`TransactionalBucketBPlusTree<String>` with front-coded keys. And a greedy longest match requires a single
operation: *does a key beginning with these k words exist in the dictionary?* For that `cursor(K)` and one
`next()` with a prefix test suffice (§4.1).

Against an FST it has three advantages and one disadvantage. The advantages: the hot swap is a swap of a
reference to an ordinary object instead of compiling an FST (§3.8 shows that in Lucene the hot swap is a
rebuild + a swap, so for us the same without that rebuild); the structure is transactional and persistable
by the same route as the term dictionary; it is one technology instead of two. The disadvantage: an FST is
markedly more compact in memory, which for a dictionary of hundreds of thousands of phrases may start to
matter — and that is precisely the point where the decision is made by measurement, not by reasoning.

The caveat about greediness from Lucene (`SynonymGraphFilter.java:42-60`: properly it would be Aho–Corasick
with enforced greediness) we adopt along with its limitation: a non-overlapping greedy longest match from
every position. For a gazetteer of brands and categories that is the right behaviour and it is also what
Typesense does (trie + dynamic programming, `synonym_index.cpp`, VK10).

**Scope for P3:** design, do not build. Without a real artifact from Sage a measurement would have no input
and populating the artifact is F1 work intertwined with O8.

### 4.7 The suggest API contract

The detailed DSL shape is handled by `query-design.md` and O4; here it is only about **what the suggester
needs and what it returns**, because without that neither a harness can be written nor latency estimated.

**Input.** The collection, the locale and the scope (they determine which structures are used — §4.1 of the
research); the raw query text, where **the last token is taken as a prefix** and the preceding ones as whole
terms; a list of searched fields with weights; the caps M (candidate terms) and N (suggested entities); a
typo expansion switch. And one item that is surprising at first sight and is at the same time the most
important: **the must-match filter**.

Without it the suggester would offer entities an ordinary query would never return — unavailable price
lists, invalid products, the wrong scope. It means **suggest is not a standalone endpoint over the
dictionary** but a query planned and evaluated like any other: the filtering formula is computed and its
bitmap intersected with the OR of the terms' bitmaps. That has a consequence for the latency budget — part
of those 5 ms is consumed by evaluating the filter, not by the suggester — and it is an argument for suggest
being a `require` over an ordinary `filterBy`, not a separate endpoint. That is for O4 to decide, though; P3
merely shows why that choice is not arbitrary.

**Output: both, terms and entities.** Not because it would be richer, but because they are two different
things for two different parts of the screen. Terms serve the "complete the query" line and are cheap — they
arise from the term leg, which does not read postings at all (§1.1). Entities serve the "products right
away" line and are expensive. A caller wanting only completion must therefore not pay for the entity leg;
the contract has to be able to express that, and therefore they are two separately requestable parts of the
response.

The proposed shape of the items:

- **Term:** the displayable surface form (§4.5), the field it was found in, the exactness class (exact /
  prefix / fuzzy with the distance given) and the postings cardinality. The last two items are at the same
  time what explain and feature export need (§4.3 of the research).
- **Entity:** the primary key and the composite score; let the ordinary fetch path do the enrichment into a
  full entity, as for any other result.

### 4.8 Two properties for free and one deliberately undelivered

§3.9 describes what Elasticsearch pays for suggestions. This is the other side of the same coin: two
properties that follow of themselves from the choice "the suggester is a derivative of the dictionary" and
were not visible in the plan so far, and one capability that choice deprives us of.

**The freshness of popularity is a property for us, not configuration.** The completion suggester receives
the weight when the document is indexed (§3.9), so "suggestions by popularity" are for it exactly as fresh
as the last reindex is — a rise in a product's sales shows up in the suggester only when that product is
reindexed. Our suggester scores by the postings cardinality read by `BucketCursor.size()` at the moment of
the query (§3.1, §4.4), so it is always consistent with the catalog's current state and it costs nothing
extra. It is worth writing down, because it does not follow of itself from the mechanics and it is a selling
point, not an implementation detail.

**Filtering suggestions is also free for us and without a cap.** The completion suggester had to invent
`contexts` with a hard limit on their number, because ordinary index filters cannot be run through a finite
automaton. For us the entity suggestion is defined as the OR of the bitmaps of the top-M terms intersected
with the must-match filter (§4.7), i.e. the same bitmap algebra as for any other query, with an arbitrarily
complex filter and without a new concept. It is at the same time a second, independent justification of what
§4.7 insists on: a must-match filter is not an optional luxury for the suggester, it is the default path —
what is a limited extension in Elasticsearch is for us the ordinary variant.

**A deliberately undelivered feature: correcting a whole phrase with a language model.** It remains to state
what we cannot do because of that choice. Our design corrects words independently with a Levenshtein
automaton (§4.2), so it cannot decide that "leather jacket" is a more probable sequence than "leather
jackety" — the context of neighbouring words does not enter the scoring of candidates at all. Elasticsearch
can do it with the phrase suggester, but pays for it with **shingles in the index** (§3.9): a field indexed
with word n-grams, introduced into the mapping in advance and not switchable on retroactively without
reindexing. That is exactly the positional tax the research avoids in §4.7, and exactly the kind of
structure whose absence P4 defends from the other side. I record it as **a rejected variant with the reason
given**, so that nobody proposes it again without knowing the price: correcting a phrase with a language
model requires an n-gram structure in the index, and that does not occur in our design even for proximity.

*What would have to be different for it to be worth revisiting:* were the positional seam of
`p4-proximity-rerank.md` (§7) activated for another reason and some positional or forward structure already
in the index, the calculation changes — an n-gram model would then not be a new tax but a consumer of
something already paid for. Until then the rejection holds.

---

## 5. The realization procedure, step by step

The steps are ordered so that each builds on a finished and measurable predecessor. K1–K3 form the term leg,
K4 the entity leg, K5–K6 the measurement and evaluation.

**K1 — prefix expansion and its cap.** The walk per §4.1 over the dictionary P1 built (step K3 of plan P1),
with a bounded heap over the score of §4.4. Parameters: the minimum prefix length, the cap on keys
inspected, M. *This is at the same time the cheapest way of verifying that P1's dictionary can be read the
way P3 thinks.*

**K2 — the cursor's intra-leaf seek.** Extend `BucketCursor` with repositioning onto a key with a fast path
inside the current leaf and with a descent from the root only when the key lies outside it (§3.2). A separate
step because it can be measured in isolation — a microbenchmark "seek to a random key" versus "seek to a
nearby key" — and because without it K3 has no chance of meeting the budget.

**K3 — the guided walk by an automaton.** A port of `AutomatonTermsEnum` per §3.6 and §4.2: `nextString`,
the minimal descent, backtrack, over the `Automaton` from `LevenshteinAutomata` in code point space. A
cascade of automata ed = 0, 1, 2 per §4.2.4. Part of the step is **a naive reference implementation (T1) and
a test that both return an identical set** — without that the guided walk's correctness is not verified,
because its errors manifest as missing candidates, not as a crash.

**K4 — the entity leg.** An OR of the postings bitmaps of the top-M terms, an intersection with the
must-match filter, the composite and the top-N. Almost nothing new is written here: it is a call to the
phase 1 scorer from P1 (step K6 of plan P1) with the expansion cap M. *Should P1 not have K6 finished at the
time of realization*, K4 can be built anyway, but the number from it will not be comparable and the cap M
will be sought blindly — it is therefore an ordering dependency, not a technical one.

**K5 — a harness of keystroke sequences.** Per §6.

**K6 — qualitative evaluation and write-up.** Per §6.3, plus recommendations on O3 and on the fork of §4.5.

---

## 6. The harness and measurement

### 6.1 Shape and placement

The module `evita_test/evita_performance_tests`, as with P1 (`p1-index-core.md`, §7.1). Measure latency with
a JMH benchmark, do not put the dictionary build into JMH — it is a one-off operation of the order of
minutes and belongs in `@Setup`. The input data will be supplied by the corpus extractor of step K1 of plan
P1; P3 needs no extraction of its own.

### 6.2 What is measured and against what

The criterion is p99, not the mean, and that matters for the measurement's shape. The suggester's mean
latency is a worthless quantity, because the distribution is strongly skewed — short prefixes are orders of
magnitude more expensive than long ones (§4.1). Measure therefore **the distribution, not the centre**, and
report p50, p95 and p99 separately.

**The input is a sequence of keystrokes, not a single query.** A realistic harness takes a real query
("black leather jacket"), writes it out character by character and measures every intermediate state
separately. Only thereby is the curve "latency versus prefix length" seen, which is P3's main output — and
only over it does p99 make sense, because it corresponds to what the user actually experiences.

Parameters that have to be varied separately:

| Parameter | Range | Why |
|---|---|---|
| the last token's prefix length | 1 to the whole word | the main axis, §4.1 |
| typo on / off | both | the research's two criteria, 5 ms and 2 ms |
| the number of query tokens | 1–3 | more tokens = more expansions |
| M (the cap on candidate terms) | single digits to tens | it divides the budget between the legs, §1.1 |
| the terms' frequency class | rare / medium / high-frequency | the entity leg's cost, P1 §5.2 |
| the data profile | e-commerce and CMS | the gate measures both (Z8) |

**Report both legs separately.** Without that the number cannot tell whether the budget was consumed by
walking the dictionary or by reading postings — and those are two entirely different subsequent decisions.

**Two extra microbenchmarks**, because they carry information p99 averages away: the cost of one seek before
the intra-leaf fast path is introduced and after (K2), and the number of seeks per typo expansion as a
function of the term's length and the permitted distance. That second quantity is the best predictor of
scaling and cannot be estimated — it follows from the dictionary's shape.

**One measurement that is not about latency and nevertheless belongs here: the dictionary's size against
expansion.** The existing solution buys prefix search by writing prefixes into the index (§4.1). For it to be
possible to claim that P3 eliminates that class of hacks **and saves as well**, both have to be computed over
the same data: the number of keys and bytes of the term dictionary as P1 builds it, against the number of
keys and bytes of the same dictionary with a prefix expansion down to a minimum length of 3 (the
`SummonFilter` rule) and with a substring expansion for one field of the "order number" kind (the
`AllCombinationsIndexBuilder` rule). It is a one-off computation over the corpus extractor from step K1 of
plan P1, not a JMH benchmark, and it is the cheapest available number for the ADR's argument — because it
shows the price the existing solution pays for a capability P3 delivers by walking the dictionary.

The usual traps of measurement runs recorded in the repo apply: the benchmark must demonstrably do work
(check that the number of returned candidates does not collapse to a constant, typically zero), and the run
must not share the machine with anything else.

### 6.3 Quality

P3's criterion is latency, but without a rough quality check latency is worthless — a suggester that finds
nothing is very fast. A proposed minimum that does not warrant a separate project:

- **A set of written-out queries with a known target.** For twenty to thirty real queries record after how
  many keystrokes the correct term appears in the top-5 and in the top-10. In the field this is called
  "keystrokes to result" and it is the only number that says anything about a suggester from the user's point
  of view.
- **A set of typos.** Ten to fifteen real typos (best from production logs) and a check that the correct term
  is found. It has to contain at least one typo in a word with diacritics — otherwise the trap of §4.2.2 will
  not show in the test, exactly as the NFD trap does not show in P5 over ASCII input.
- **A check that stems are not displayed.** Trivial to write, easy to forget, and it shows up on the user
  (§4.5).

Record it machine-readably so that it can later be run through the Sage comparison harness, once it is
confirmed to exist (question Q6 in `p1-index-core.md`, §8).

---

## 7. Open questions and handovers

The division is the same as in `p1-index-core.md`, §8: **questions** are things P3 cannot decide and
somebody has to; **handovers** are results P3 produces that somebody else consumes.

### Questions

**Q1 — who owns the cursor's intra-leaf seek.** P3 builds it as its step K2 (§3.2), because without it it
will not meet the budget. It is, however, an extension of a shared structure, not fulltext code, and once it
exists other readers of the B+ tree benefit from it too. The question is whether it should come about as part
of P3, or as a separate change to `TransactionalBucketBPlusTree` with its own tests. I lean towards the
second and merely consuming it in P3 — it is a pure increment to an existing structure and it ought to pass
its own review.

**Q2 — the minimum prefix length as a product decision.** §4.1 recommends two to three characters. It is a
choice, not a mechanism (test 4 of §1.2 of the research), so it belongs to the client — but the engine has to
have a sensible default, because a single-character prefix is a pathology it makes no sense to serve
quickly.

**Q3 — behaviour on overflowing the cap.** When a prefix matches more terms than the cap, the top-M per §4.4
is returned, but the response says nothing about it. Should the suggester signal that the result was
truncated? For debugging yes; for a client it is noise. Related to feature export (§4.3 of the research), so
the answer ought to be the same.

**Q4 — is a suggester in scope for the CMS profile at all?** §4.5 shows the suggester needs surface forms,
whereas P5 leans for the CMS profile towards a dictionary with stems. The proposal (a suggester over headings
only) is reasonable, but it is a product decision, not a technical one.

**Q5 — where to get real typos.** The qualitative evaluation (§6.3) rests on them and synthetic typos (a
random character substitution) do not have the right distribution — real typos are largely adjacent keys and
omitted diacritics. Without production search logs it cannot be substituted, and it is the same
organizational dependency as Q5 of plan P1 (the availability of a CMS dataset).

### Handovers — P3's results somebody else is waiting for

**P→O3 — the typo tolerance thresholds and their space.** §4.3 gives a proposal (0 / 1 / 2 by the length
1–3 / 4–7 / 8+, the first character non-editable, transpositions on, computed from the typed token's length
in the folded space). Confirmation or refutation will come from the quality measurement (§6.3). The consumer
is F1 and `p5-analyzers.md`, which reserved this item for itself as open.

**P→P5 — the qualitative half of the fork "stem versus surface form".** §4.5 answers: variant 3 everywhere a
suggester is deployed, because stems are not displayable. The other half (the dictionary's size) is measured
by P1 in K3.

**P→P4 — the cost of the guided walk and the number of seeks per expansion.** P4 shares the same latency cap
with P3 whenever a proximity query runs in search-as-you-type mode, and P4 itself has a budget the expansion's
cost has to fit into.

**P→F1 — the suggest API contract.** §4.7 defines what the suggester needs and what it returns, including the
non-trivial finding that the must-match filter is a mandatory input and suggest is therefore not a standalone
endpoint. The DSL's shape will be derived from it by O4 and `query-design.md`.

**Relation to the research's open questions.** P3 **answers** O3 (§4.3) and **prepares input** for O4 (§4.7).
It does not touch O1 (the default rank profile) or O8 (the boost channel) — the suggester uses the default
composite as P1 builds it, and the boost map will enter it by the same route as for an ordinary query, once
P7 delivers it.
