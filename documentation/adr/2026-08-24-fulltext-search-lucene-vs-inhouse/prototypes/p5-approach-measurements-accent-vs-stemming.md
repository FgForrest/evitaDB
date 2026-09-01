# P5 measurements — every proposed folding/stemming mechanism, measured

> **Status: measurement record, the empirical half of
> [`p5-prior-art-accent-vs-stemming.md`](p5-prior-art-accent-vs-stemming.md).** That document
> surveyed seven engines and ranked six candidate mechanisms (its §11, M1–M6) by argument. This one
> builds all six against one Czech vocabulary — plus a seventh, **M7, minted by measurement rather
> than by the survey** — and reports what they actually score. Measured on branch
> `258-fulltext-support-p5`; the numbers below are the **2026-09-01 run (run 6)**, after the
> fixture was corrected three times — see §10: the early runs scored the palatalization rewrite wrong
> because the vocabulary could not exercise it, runs 1–3 scored dropping the `-at` paradigm as free
> because no `-ata` neuter existed to commit the drop's cost, and runs 1–4 scored *keeping* those
> entries as precision-free because no `forma`/`formát`-shaped pair existed to commit theirs. Run 6
> added mechanism M7 (A20–A22), whose first measurement exposed a **fourth** folded ambiguity — the
> epenthetic `-e-` — that no symmetric chain can see (§6).
>
> **Reproduce:**
> ```shell
> mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional \
>   -Dtest='CzechAnalysisApproachMatrixTest,CzechAccentTypingTest' -DsurefireArgLine=
> ```
> The matrix is printed to stdout by `shouldReportApproachMatrix`; read it from
> `target/surefire-reports/TEST-io.evitadb.index.fulltext.analysis.CzechAnalysisApproachMatrixTest.xml`
> (`<system-out>`), because Maven's console encoding mangles the accented forms. Every failing case
> is listed there; the excerpts below are quoted from that output rather than kept as a dump.

---

## 1. Verdict

**M7 wins — keep the production chain on the index side and absorb every folded ambiguity in a
query-side hypothesis fan-out.** The observation it turns on: **every failure of the production
chain is on the query side.** A0 scores a perfect 348/348 convergence with 0 false merges on the
index, because stored values are always spelled correctly and the accented stemmer never faces a
folded ambiguity there. Every symmetric M1 configuration pays for the query-side problem by
degrading the index — running the ambiguous folded stemmer on values that had no ambiguity to begin
with. M7 (A20) instead folds the **query**, then emits *every* stem the folded stemmer could
produce across its ambiguous-rule switch positions as OR'd terms — the query never has to commit on
an ambiguity, and the index never inherits one. It scores a **perfect 348/348** at **54 false
merges — under half of A18's 112** — with **1.00 index terms per token** and a measured query
fan-out of **1.30 terms per token on average, never more than 2** on this vocabulary. See §6.

**M1 remains the enabling insight — the query-side stemmer *is* the folded port — but every
symmetric M1 configuration above A13 is now dominated by an M7 row:**

| configuration                                 | bare+crossform | false merges | dominated by |
|-----------------------------------------------|----------------|--------------|--------------|
| A13 — M1, neither rewrite, `-at` dropped      |   310/348 (89 %) |      8     | —          |
| A22 — M7, `-at` + epenthetic forks only       |   331/348 (95 %) |     10     | —          |
| A16 — M1, palatalization, `-at` dropped       |   342/348 (98 %) |     58     | A21        |
| A17 — M1, both rewrites, `-at` dropped        |   344/348 (99 %) |     88     | A21        |
| A19 — M1, palatalization, `-at` kept, two-step |  346/348 (99 %) |     76     | A21        |
| A21 — M7, without the vowel hypothesis        |   347/348 (99.7 %) |   39     | —          |
| A18 — M1, both rewrites, `-at` kept, two-step |   348/348 (100 %) |    112     | A20        |
| A20 — M7, all hypotheses                      | **348/348** (100 %) |   54     | —          |

The Pareto frontier is now A13 → A22 → A21 → A20, all M7 above 89 % — and M7's merges are
**one-directional by construction** (§6), where a symmetric chain's are paid in both directions.

**M2 — the second lane per term — is refuted.** It buys **3 pairs of 95** (253 → 256/348) for a
**1.95× term inflation**, because a folded surface lane and a stem lane only ever meet like for like.
§3 explains why an earlier run scored it perfect; that section is the one to read if you read only one.

**Everything else is refuted with numbers:** query-side-only expansion with a *surface lane* (M5)
rescues one form of 30 and only by coincidence — M7 is the query-side expansion that works, and §6
spells out why the two differ; selective folding (M6) collapses to 21/119 accent recall; no stemmer
plus typo tolerance (M4) reaches 51 % of inflection and merges unrelated words; Hunspell stays broken
in both folding orders.

**Two decisions are left that measurement cannot make** — see §8.

## 2. What was built

Twenty-six rows. Twenty-two chains, three of them measured twice (once per matching strategy).

| id  | survey | chain                                                                                  |
|-----|--------|----------------------------------------------------------------------------------------|
| A0  | —      | **production**: `StandardTokenizer → LowerCase → Stop → CzechStemFilter → ASCIIFolding` |
| A1  | M3     | fold before `CzechStemFilter`, otherwise unchanged — the Vespa/Typesense default         |
| A2  | M1     | fold first, then `FoldedCzechStemmer`, both survey-flagged rules **off**                 |
| A3  | M1     | …plus the palatalization rewrite (`ct→ck` / `st→sk`)                                     |
| A4  | M1     | …plus the penultimate vowel shift (`u→o`)                                                |
| A5  | M1     | …plus both                                                                               |
| A6  | M2     | production plus a second lane: `KeywordRepeat → CzechStemFilter → RemoveDuplicates → fold` |
| A7  | M5     | **asymmetric** — A0 indexes, A6 queries                                                   |
| A8  | M4     | no stemmer at all: `Stop → ASCIIFolding`                                                  |
| A9  | M6     | selective folding — the letters the stemmer reads exempted, never folded back             |
| A10 | —      | Hunspell `cs_CZ` then folding                                                            |
| A11 | M1+M2  | folded-space stemmer **and** a folded surface lane                                       |
| A12 | —      | folding then Hunspell `cs_CZ`                                                            |
| A13 | M1     | A2 with the neuter `-at-` paradigm dropped — see §5                                       |
| A14 | M1     | A13 plus the penultimate vowel shift                                                     |
| A15 | M1+M2  | A13 plus a folded surface lane                                                           |
| A16 | M1     | A13 plus the palatalization rewrite                                                      |
| A17 | M1     | A13 plus both rewrites — the fullest single-pass folded port                             |
| A18 | M1     | both rewrites, `-at` entries **kept**, stemmer applied **twice** — see §5a               |
| A19 | M1     | the same without the vowel shift                                                         |
| A20 | M7     | **asymmetric** — A0 indexes; the query folds, then emits **every** folded-stemmer hypothesis — see §6 |
| A21 | M7     | A20 without the vowel-shift hypothesis                                                   |
| A22 | M7     | A20 with only the `-at` and epenthetic forks, both rewrite hypotheses off                |

Code, all test scope — nothing in `evita_engine` was touched:

- `CzechAnalysisFixture` — the vocabulary, the confusable-lemma probes, the five metrics, the two
  matching strategies. Shared, so that no mechanism is scored on its own vocabulary.
- `FoldedCzechStemmer` — the M1 prototype: `CzechStemmer`'s tables ported into folded space, with the
  **four** ambiguous rules independently switchable (run 6 made the epenthetic `-e-` removal the
  fourth switch — see §6).
- `CzechAnalysisApproachMatrixTest` — builds and measures every row; its nested `HypothesisStemFilter`
  is the M7 query-side fan-out (synonym-shaped: first hypothesis replaces the token, the rest follow
  at position increment zero). Seven tests, all passing.
- `CzechAccentTypingTest` — guards the **production** chain alone. Its accent-typing test is
  **expected to fail**; that is its job.

**Vocabulary**: 32 lemmas / 119 forms of Czech e-commerce words, every form carrying a diacritic.
The classes in it are deliberate, because **a mechanism can only be measured against a failure it is
given the chance to commit**:

- adjective genitive/locative plural `-ých`/`-ích` and instrumental `-ým`, noun dative/locative plural
  `-ám`/`-ách`/`-ům` — the classes the production folding order loses;
- the `stůl`/`stolů` vowel shift, which only the `u→o` rewrite converges;
- the **palatalized** nominative plural — `dětští`, `pánští`, `kuchyňští`, `angličtí` — the `sk`↔`št`
  and `ck`↔`čt` alternation, which only the palatalization rewrite converges;
- the **neuter `-ata` paradigm** — `rajče`/`rajčata`/`rajčat` — the class the `at`-family table
  entries exist for, present so that *dropping* those entries has a cost the harness can see.

The palatalized class was **absent from the first two runs**, which is exactly why they scored the
palatalization rewrite as buying nothing; the `-ata` class was **absent from the first three**, which
is why they scored dropping the `-at` entries as free. See §10.

Plus **10 confusable lemmas in 5 pairs** — `cesta`/`český`, `list`/`líska`, `ruka`/`rok`, `buk`/`bok`,
`forma`/`formát` — present only so the precision metric has something to find; a false-merge count
over a vocabulary with no confusable words measures nothing. The `forma`/`formát` pair probes every
configuration in which the `-at` entries fire on a genuine `-át` root (single-pass kept *and*
two-step). 152 forms in total, giving 22 524 ordered cross-lemma pairs.

---

## 3. The metrics, and the trap in them

Five numbers. The fourth was added **after** the first matrix run, and adding it inverted the ranking.

1. **accent-typed recall** — a form typed without diacritics must find the accented spelling of
   **the same form**. `panskych` → `pánských`.
2. **inflection convergence (ordered pairs)** — querying form A must find a value written as form B,
   **both accented**. Pairwise and directional, because prefix matching is directional and a
   "do all forms share a term" metric cannot express that.
3. **inflection convergence (strict)** — the older "all forms of a lemma share at least one term".
   Kept for comparability with the first run.
4. **bare-typed cross-form recall** — 1 and 2 **combined**: an accent-stripped query must find a
   value in a **different** inflection. `panskych` → `pánská`.
5. **false merges** — ordered pairs from **different** lemmas that match anyway. Without this, any
   mechanism scores perfectly by collapsing the vocabulary onto one term.

**Why metric 4 is the one that matters.** Metrics 1 and 2 can each be satisfied by **one lane of a
two-lane chain acting alone** — the folded surface lane carries accent recall, the stem lane carries
convergence — so a two-lane chain scores perfectly on both while the real query stays broken. Metric
4 can only be satisfied by a lane that does **both jobs at once**.

Concretely, this is how M2 answers a bare-typed query at all. `KeywordRepeatFilter` emits each token
twice and flags one copy `KeywordAttribute=true`; `CzechStemFilter` honours the flag and skips that
copy; `ASCIIFoldingFilter` folds both:

```
value  "pánských"  →  stem lane   : pánsk    → pansk
                      surface lane: (skipped) → panskych      folded, never stemmed
query  "panskych"  →  stem lane   : panskych   (stemmer no-ops - no accents to switch on)
                      surface lane: panskych
                                    └───────── these meet, and the stemmer was never involved
```

Accent-insensitivity is bought by folding, which needs no stemmer; convergence is bought by the stem
lane, which needs accents and always has them because the stored value is spelled correctly. But the
two lanes only ever meet **like for like**, so the combined query falls between them:

```
černý: query cernych [cernych] misses value černá [cerna, cern]
```

The bare query's surface lane holds `cernych`; the value's holds `cerna`; the value's stem lane holds
`cern`, which the bare query can never produce, because producing it requires recognising `-ých`.

`CzechAnalysisApproachMatrixTest#shouldShowTwoLanesDoNotCoverTheCombinedCase` pins this, so the trap
cannot be walked into twice.

---

## 4. The matrix

`accent-typed` = metric 1, over 119 accented forms. `bare+crossform` = metric 4, the one to read.
`conv. pairs` / `conv. strict` = metrics 2 and 3. `false merges` = metric 5, over 22 524 ordered
cross-lemma pairs. `terms/form` = terms emitted per input word, i.e. the index-size cost. For the
asymmetric M7 rows that column is the **index** side's cost; their query-side fan-out, measured
separately, is 1.30 terms per token on average and never exceeded 2 on this vocabulary (§6).

| approach                                | matching     | accent-typed | **bare+crossform** | conv. pairs | conv. strict | false merges | terms/form |
|-----------------------------------------|--------------|--------------|--------------------|-------------|--------------|--------------|------------|
| A0 stem→fold (**production**)           | exact        |    89/119    | **253/348**        |   348/348   |    32/32     |       0      |    1.00    |
| A1 fold→stem (naive)               M3   | exact        |   119/119    |   194/348          |   194/348   |    13/32     |      18      |    1.00    |
| A2 fold→foldedStem[--]             M1   | exact        |   119/119    |   306/348          |   306/348   |    26/32     |      20      |    1.00    |
| A3 fold→foldedStem[palat]          M1   | exact        |   119/119    |   338/348          |   338/348   |    30/32     |      70      |    1.00    |
| A4 fold→foldedStem[vowel]          M1   | exact        |   119/119    |   308/348          |   308/348   |    27/32     |      50      |    1.00    |
| A5 fold→foldedStem[palat+vowel]    M1   | exact        |   119/119    |   340/348          |   340/348   |    31/32     |     100      |    1.00    |
| A6 stem→fold + surface lane        M2   | exact        |   119/119    | **256/348**        |   348/348   |    32/32     |       0      |  **1.95**  |
| A7 asymmetric query expansion      M5   | exact        |    90/119    |   256/348          |   348/348   |    32/32     |       0      |    1.00    |
| A8 fold only, no stemmer           M4   | exact        |   119/119    |    16/348          |    16/348   |     0/32     |       0      |    1.00    |
| A9 selective folding               M6   | exact        |    21/119    |    58/348          |   348/348   |    32/32     |       0      |    1.00    |
| A10 hunspell→fold                       | exact        |    28/119    |    74/348          |   344/348   |    30/32     |       0      |    1.04    |
| A11 foldedStem[--] + surf lane   M1+2   | exact        |   119/119    |   310/348          |   310/348   |    26/32     |      20      |    1.95    |
| A12 fold→hunspell                       | exact        |   119/119    |    20/348          |    20/348   |     0/32     |       0      |    1.03    |
| A13 fold→foldedStem[-at]           M1   | exact        |   119/119    |   310/348          |   310/348   |    26/32     |     **8**    |    1.00    |
| A14 fold→foldedStem[vowel,-at]     M1   | exact        |   119/119    |   312/348          |   312/348   |    27/32     |      38      |    1.00    |
| A15 foldedStem[-at] + surf lane         | exact        |   119/119    |   310/348          |   310/348   |    26/32     |       8      |    1.93    |
| **A16 fold→foldedStem[palat,-at]** M1   | exact        |   119/119    | **342/348**        |   342/348   |    30/32     |      58      |    1.00    |
| A17 fold→foldedStem[palat,vowel,-at] M1 | exact        |   119/119    |   344/348          |   344/348   |    31/32     |      88      |    1.00    |
| **A18 fold→foldedStem[palat,vowel,+at]×2** | exact     |   119/119    | **348/348**        |   348/348   |    32/32     |     112      |    1.00    |
| **A19 fold→foldedStem[palat,+at]×2**    | exact        |   119/119    | **346/348**        |   346/348   |    31/32     |      76      |    1.00    |
| **A20 A0-index/hypothesis query**  M7   | exact        |   119/119    | **348/348**        |   348/348   |    32/32     |      54      |    1.00    |
| **A21 A0-index/hypo query[-vowel]** M7  | exact        |   118/119    | **347/348**        |   347/348   |    32/32     |      39      |    1.00    |
| A22 A0-index/hypo query[at only]   M7   | exact        |   114/119    |   331/348          |   331/348   |    32/32     |      10      |    1.00    |
| A0 stem→fold (production)               | prefix+fuzzy |    99/119    |   286/348          |   348/348   |    32/32     |       9      |    1.00    |
| A2 fold→foldedStem[--]                  | prefix+fuzzy |   119/119    |   342/348          |   342/348   |    26/32     |      23      |    1.00    |
| A8 fold only, no stemmer           M4   | prefix+fuzzy |   119/119    |   178/348          |   178/348   |     0/32     |      18      |    1.00    |

## 5. M1 — it works, and every one of its three rules is a trade

The survey predicted the port would be *mostly mechanical* with **two** rules needing language
judgment. That held, and measurement found a **third**. What the corrected fixture adds is the other
half of the ledger: each of the two predicted rules **buys real recall**, which the first two runs
could not see.

### The palatalization rewrite — the biggest lever, and the most expensive

> **Terminology.** "Palatalization" names the *historical cause*, not a live process — it stopped being
> productive in Czech centuries ago. What the rule undoes is the fossilized **morphophonemic
> alternation** left behind: `sk`↔`št`, `ck`↔`čt` before the soft endings, conditioned today by which
> ending is attached rather than by what sound follows. Czech grammars say *alternace*. The word is
> kept because it is the standard Slavic-linguistics shorthand for this family (`k`~`c`~`č`,
> `h`~`z`~`ž`, `ch`~`š`~`s`) and because the codebase already uses it for the same family
> (`FulltextAnalyzerTest#shouldRewritePalatalizedConsonant`, on the `ž`→`h` rule). Despite the
> spelling the alternation is palatal on both segments: `-št-` before `í` is `[ʃc]` — `š` plus `ť`.

`CzechStemmer.normalize()` undoes the alternation `sk`↔`št` and `ck`↔`čt`, so that one stem spelled two
ways lands on one term:

```java
if (endsWith(s, len, "čt")) { … "ck" }   // angličt- → anglick-
if (endsWith(s, len, "št")) { … "sk" }   // dětšt-   → dětsk-
```

```
dětský → dětsk  ─┐
dětští → dětšt ──┴→ dětsk
```

Folding erases the distinction the rule keys on: `š→s` and `č→c`, so folded `št` and a genuine `st`
are one string. **Buys 32 pairs** (A2 → A3: 306 → 338; strict 26 → 30). **Costs 50 false merges**:

```
query cesta (cesta) [cesk] matches value český (český) [cesk]
query listy (list)  [lisk] matches value líska (líska) [lisk]
```

**Declining it is not free either** — and this is the finding that makes it a genuine dilemma. With
the rewrite off, the palatalized form is left unrewritten and lands on the plain `st` stem, colliding
from the other side. A2 shows **20 false merges with both rules disabled** — 8 of them from this
ambiguity:

```
query čeští (český) [cest] matches value cesta (cesta) [cest]
```

(the other 12 are the kept `-at` entries eating `formát`, the third rule's own cost — see below).
So the folded `st`/`št` ambiguity produces false merges whichever way the switch is set. The rewrite
moves them and adds more; it does not create the problem.

### The penultimate vowel shift — small lever, moderate cost

`ů→o` (folded: `u→o`), which makes `dům`/`domu` converge. **Buys 2 pairs** — the entire `stůl`/`stolů`
paradigm, and nothing else in this fixture. **Costs 30 false merges**:

```
query ruka (ruka) [rok] matches value rok (rok) [rok]
query buk  (buk)  [bok] matches value bok (bok) [bok]
```

It is the difference between A16's 342/348 and A17's 344/348.

### The third rule the survey missed: the neuter `-at-` paradigm

Five entries of `CzechStemmer`'s tables — `atech`, `atům`, `ata`, `aty`, `at` — exist for neuter nouns
in `-ata` (`kuřata → kuř`), whose paradigm carries a **short** `a`.

**In the original accented stemmer the two spellings never meet.** The entries are spelled with a
short `a` and `StemmerUtil.endsWith` compares characters exactly, so a word in accented `-át` never
matches them — `kabát`, `kabáty`, `kabátech` are stripped by the final-vowel and `ech` rules instead
and converge on `kabát`, while `kuřata` takes the `ata` entry to `kuř`. Two paradigms, two disjoint
rule sets, no interaction.

**Folded, `-át` and `-at` are the same string, and the two paradigms fight over one rule set.** The
entries now also eat the far more common `-át` masculines, splitting that paradigm down the middle:

```
kabát: query kabat  [kab] misses value kabátu [kabat]     (the `at` entry fires on the folded form)
kabát: query kabaty [kab] misses value kabátu [kabat]     (the `aty` entry, same cause)
```

**Dropping the entries is a trade, not a fix — both switch positions lose recall.** Keeping them
splits `kabát` (8 ordered pairs); dropping them splits the `-ata` neuters' singular from their
plural, because nothing strips the `at` any more:

```
rajče: query rajce   [rajk]   misses value rajčata [rajcat]   (singular stems to rajk...)
rajče: query rajcata [rajcat] misses value rajče   [rajk]     (...the plural family to rajcat)
```

Within a single pass this one cannot be paid for in false merges — it is recall against recall,
`kabát`'s 8 pairs against `rajče`'s 4, and which loss is smaller is a corpus-frequency question the
fixture cannot answer. On this vocabulary dropping nets **+4 pairs and −12 false merges**
(A2 → A13: 306 → 310, merges 20 → 8 — kept single-pass entries also eat genuine `-át` roots:
`formát → form` ≡ `forma`). Runs 1–4 could not see one or the other side of this ledger — run 1–3's
vocabulary had no `-ata` neuter to commit the drop's cost, run 4's had no `forma`/`formát` pair to
commit the keep's precision cost; see §10. **Two-step stemming dissolves the recall half of the
dilemma entirely — see §5a — and M7 dissolves the whole dilemma by never asking the index side to
answer it — see §6.**

### 5a — two-step stemming: the `-at` dilemma dissolved, paid in precision

Keeping the `-at` entries **and applying the whole stemmer twice** (A18/A19) recovers both
paradigms at once. The oblique `-át` forms catch up with their truncated nominative in the second
pass instead of splitting from it, and the `-ata` neuters keep their own entries:

```
kabatu  → kabat → kab      (final vowel, then the `at` entry — converges with kabat → kab)
rajcata → rajc  → rajk     (the `ata` entry, then normalize — converges with rajce → rajk)
```

**A18** (both rewrites, two-step) is the only configuration that scores **348/348 with 32/32 strict
convergence** — recall-perfect on the whole vocabulary. **A19** (palatalization only, two-step)
scores 346/348 and **dominates A17 on both axes** (346 > 344 recall, 76 < 88 merges): two-step buys
`kabát`+`rajče` (+4 pairs for +18 merges) where the vowel shift buys only `stůl` (+2 for +30).

The price is precision, in two forms. First, every root genuinely ending in folded `-at` is
truncated — `formát → form` ≡ `forma → form`, 18 of A18's merges — which is the same class of word
the *kept single-pass* entries already damage (they split `formát` **and** merge its nominative);
two-step merely makes the damage consistent. Second, the second pass reaches stems the first pass
left alone: with the vowel shift on, `ruce → ruk → rok` adds 6 more merges (A18's 112 = A17's 88 +
18 `formát` + 6 `ruce`). And a second pass doubles every rule's exposure in ways this fixture only
samples — a stem that *ends like an inflected form* after pass one gets stripped again wherever it
stands (`stanov → stan` via the possessive rule is already single-pass behaviour; two passes widen
that class). The 112 is a floor with wider error bars than the single-pass floors.

## 6. M7 — fold the ambiguity into the query, never into the index

Mechanism M7 was not in the survey; it fell out of reading the matrix. **A0's row is perfect
everywhere except the query-typed metrics** — 348/348 convergence, 32/32 strict, 0 false merges —
because the stored value is always spelled correctly, so the accented stemmer never meets a folded
ambiguity on the index side. Every symmetric M1 row buys back the query-side recall by running the
ambiguous folded stemmer over *values*, i.e. by injecting ambiguity into the one side that had none.
M7 refuses that trade:

- **Index side: the production chain, unchanged.** Accented stem, then fold. One term per token,
  and the term for `formát` stays `format`, distinct from `forma`'s `form` — the accented stemmer
  saw the `á` and never fired the `-at` entries.
- **Query side: fold first, then emit *every* stem the folded stemmer could produce** across the
  switch positions of its ambiguous rules, as OR'd terms at one position — the shape a synonym
  filter uses. A bare `formaty` emits `{format, form}`; a bare `detsti` emits `{detst, detsk}`;
  an unambiguous `panskych` emits just `{pansk}`. The query never commits on an ambiguity it cannot
  resolve; it hedges, and the hedge is cheap because it is paid per query token, not per indexed
  document. Measured fan-out over all 152 bare-typed forms: **1.30 terms per token on average,
  never more than 2** (`shouldShowHypothesisQueriesDominateTwoStepStemming` prints and pins it).

**Why this is not the refuted M5.** A7 also expanded only the query, but with a folded *surface*
lane — and a surface form can only meet a surface form, which the one-lane index does not hold, so
it rescued one coincidence. M7 expands with folded-space *stem hypotheses*, and one of them is by
construction the folded image of what the accented stemmer did to the value. The expansion has
something to hit because it speaks the index's language; that is the whole difference.

**Why its false merges are one-directional.** A symmetric chain that maps `cesta → cesk` does so on
both sides, so `cesta` queries find `český` values *and* `český` queries find `cesta` values — every
ambiguity is paid twice. Under M7 the index keeps `cesta → cest` and `český → cesk` distinct; only
the query fan-out can cross lemmas, and only where a hypothesis happens to equal the other lemma's
index term. The full composition of A20's 54 merges, from the run's detail output:

| direction                  | merges | cause                                                      |
|----------------------------|--------|------------------------------------------------------------|
| `cesta` → `český`          |   20   | palatalization hypothesis: `cest` → `cesk`                 |
| `čeští` → `cesta`          |    4   | the *unrewritten* hypothesis `cest` — the folded `st`/`št` ambiguity's irreducible half |
| `list` → `líska`           |    9   | palatalization hypothesis: `list` → `lisk`                 |
| `ruka`,`ruky` → `rok`      |    6   | vowel hypothesis: `ruk` → `rok`                            |
| `buk` → `bok`              |    9   | vowel hypothesis: `buk` → `bok`                            |
| `formát`,`formáty` → `forma` |  6   | `-at` hypothesis: `format` → `form`                        |

`líska`, `rok`, `bok` and `forma` queries reach nothing foreign — their stems are unambiguous, so
they emit one term and it is theirs. Note also what this does to the *worst-ratio* rule: the vowel
shift costs 15 merges here (A20 − A21) against 30+ in every symmetric row, for the same 2 pairs
(`stůl`) plus 1 accent-typed form.

### How the hypothesis set is computed — a union of configurations, which is one branching stemmer

The prototype (`HypothesisStemFilter`, nested in `CzechAnalysisApproachMatrixTest`) holds a fixed
list of `FoldedCzechStemmer` instances — one per combination of the four ambiguous-rule switches
(`-at` family, `st→sk` palatalization, `u→o` vowel shift, epenthetic `-e-`), i.e. 2⁴ = 16, built
once when the chain is constructed. **Every** query token is run through **all** of them,
sequentially, and the outputs are deduplicated into a set; the first survivor replaces the token
and the rest follow at position increment zero. There is no per-query or per-word choice of
configuration anywhere.

The set collapses per word because a switch only changes the output when the rule it controls
actually *fires* on that word's ending. For `panskych` none of the four rules fires, all sixteen
runs return `pansk`, and one term is emitted. For `formaty` only the `-at` switch matters — the
eight configurations with the entries on return `form`, the eight with them off return `format` —
so sixteen runs yield exactly two distinct strings. No word in the fixture reaches more than one
fork, which is where the measured 1.30-average/2-maximum fan-out comes from.

The sixteen-run union is **provably equivalent to a single branching stemmer** — one that walks the
ending tables once and, at each ambiguous decision point it actually reaches, takes *both* branches
instead of committing. The equivalence holds because each fork point is controlled by exactly one
flag, so the union of outputs over all flag combinations is exactly the set of all branch outcomes.
That equivalence matters twice: it is what makes the lazy prototype valid evidence for the
mechanism, and it is what licenses a production implementation to be a branching pass rather than
sixteen passes (see the performance subsection below).

### The fourth folded ambiguity, and why only M7 could find it

The first M7 measurement scored 336/348: every `dřevěný` pair missed, because the folded query stem
was `drevn` while the index held `dreven`. The epenthetic `-e-` rule keys on a literal `e`; in
accented space `dřevěn` ends `ěn` and the rule stays quiet, folded it ends `en` and the rule fires.
**Every symmetric folded chain mis-stems both sides identically** (`drevn` = `drevn`) **and still
converges, so no symmetric row could ever surface this** — the survey's "two rules need language
judgment", already corrected once to three (`-at`), is now corrected to **four**. The rule became
the fourth independent switch on `FoldedCzechStemmer`, the hypothesis chain forks on it
(`sluchatek` emits `{sluchatek, sluchatk}`), and A20 went to 348/348. The `-at` and epenthetic
forks are not optional in M7 — both are needed to reach an accented-stemmer index at all — which is
why even A22 carries them.

### What M7 costs, and the invariant it creates

- **The query pipeline must support several OR'd terms per query token** — synonym-shaped
  expansion. This is a query-side capability, not a term-dictionary layout change; the index stays
  one term per token.
- **Two stemmers instead of one, bound by an invariant**: for every word, the query-side hypothesis
  set must contain the folded image of whatever the accented stemmer emits for that word's forms.
  The fixture is the regression harness for exactly this — the epenthetic finding is what a breach
  looks like.
- **What it buys operationally**: the index format and every indexed catalog are untouched — the
  production chain keeps indexing exactly as today, and every future tuning of the query-side
  stemmer or its hypothesis set is a **query-time change needing no reindex**. A symmetric M1
  deployment re-indexes every catalog on every stemmer-table correction.
- **Ranking headroom**: hypothesis terms are distinguishable from the primary stem at query
  construction, so a scoring layer could down-weight hypothesis-only matches later. The binary
  match model measured here treats them as equals; the 54 is therefore the *unranked* ceiling.

### Performance: why sixteen stemmer passes are not a concern

The obvious objection to the mechanism is the cost of running the stemmer sixteen times per token.
It does not survive inspection, for three reasons in increasing order of importance:

1. **The absolute cost is noise.** One stem pass is a cascade of `endsWith` comparisons over a
   roughly ten-character buffer plus a couple of in-place character writes — no allocation, no
   table lookup, no IO — i.e. tens of nanoseconds. Sixteen passes plus the set deduplication land
   around a microsecond per token, and the `String` allocations for the set dominate that, not the
   stemming. Each emitted term then pays a term-dictionary lookup and a posting-list traversal,
   which are microseconds to milliseconds — the analysis cost disappears under them.
2. **It is paid in the cheapest possible place.** The fan-out runs per **query token at query
   time**; a query has a handful of tokens. Contrast M2, whose 1.95× cost is paid per **indexed**
   token, into index size, memory and merge time, forever. Expensive analysis matters when it
   multiplies across millions of stored tokens; M7's multiplies across the words a user typed.
3. **Sixteen passes is the prototype's shortcut, not the design.** The union-of-configurations
   exists so the prototype could reuse `FoldedCzechStemmer` unchanged, flags and all — the cheapest
   way to *measure* the mechanism. The equivalence proved above licenses a production
   implementation as a single **branching pass**: walk the tables once, fork only where one of the
   four ambiguous rules actually fires. A typical token takes zero forks and costs exactly one
   pass — the same as today's production analysis — and an ambiguous token costs roughly 1.2
   passes' worth of work.

The performance question actually worth watching is **downstream of the stemmer**: each extra
hypothesis is one more term-dictionary lookup and one more posting list OR'd into the query. That
is bounded by the measured fan-out — 1.30 average, never more than 2 on this vocabulary — i.e. at
worst the cost of a two-word synonym expansion, which is machinery the query pipeline wants for
synonyms anyway. If a real corpus ever surfaced an ending that stacks several forks (none exists in
the fixture, and §9 records this as a vocabulary-bound number), a cap on the emitted hypothesis
count is the natural knob; nothing in the mechanism depends on the fan-out being unbounded.

## 7. Everything else, refuted with numbers

- **M2, second lane per term (A6).** 253 → **256/348**: three pairs out of ninety-five, for a
  **1.95× term inflation**. The mechanism the survey called "the only shipped both-axes mechanism"
  turns out to cover both *metrics* rather than both *axes*. See §3.
  Note the `terms/form` figure is measured over single-word inputs, where surface ≠ stem almost
  always, so 1.95 is an upper bound — but the recall gain does not improve with corpus shape.
- **M1+M2 together are worse than M1 alone** (A11 and A15 both 310 at ~1.95 terms/form, against
  A17's 344 at 1.00). Folding first weakens the stem lane, and a surface lane cannot repair
  `stůl`/`stolů` or the palatalized plural because those differ in surface form too. Belt-and-braces
  is a net loss.
- **M5, query-side expansion only (A7).** Rescues **exactly one form of 30** — `kabát`, and only by
  the accident that the bare query's folded surface (`kabat`) equals the value's stem. The survey's
  "buys nothing" was right within one coincidence. Metric 4 is unchanged from A6's 256, confirming
  the extra query term has nothing to hit in a one-lane index. **What is refuted is expanding with a
  surface lane, not query-side expansion as such** — M7 (§6) expands the query with folded-space
  *stem hypotheses* and reaches 348/348, precisely because its extra terms are spelled in the
  index's language. A7's one rescue was the degenerate case where the surface happened to be one.
- **M6, selective folding (A9).** **21/119.** Exempting the letters the stemmer reads is exempting
  precisely the letters the bare-typed query lacks. Confirms the survey: it reconciles folding with a
  native-orthography stemmer by surrendering the bare-typing case entirely.
- **M4, no stemmer (A8).** Under exact matching inflection collapses completely — 16/348, 0/32
  lemmas. Prefix plus typo tolerance lifts it to **178/348 (51 %)** and introduces 18 false merges
  (`forma` is a prefix of `formát`, `cesta` within one edit of `česká`):
  ```
  query cesta (cesta) [cesta] matches value česká (český) [ceska]
  query listy (list)  [listy] matches value lísky (líska) [lisky]
  ```
  Length thresholds, not grammar, decide: `bota → botách` works, `černých → černý` does not (equal
  length, 2 edits, no prefix relation). Erratic on exactly our failure classes, as the survey said.
- **Fuzzy does not rescue the production chain.** A0 under prefix+fuzzy goes 89 → **99/119** and
  253 → 286/348. Ten of 30 accent misses recovered; the 3+-edit accent gap stays unreachable,
  consistent with the 2-edit ceiling the survey found in every engine.
- **Hunspell stays refuted in both orders.** A10: 28/119 accent, 74/348 combined. A12: folding first
  destroys the dictionary lookup entirely — 20/348 convergence, 0/32 lemmas.
- **Naive fold-before-stem (A1) is not even precision-safe.** 194/348, 13/32 lemmas, **and 18 false
  merges** — the Vespa/Typesense default loses inflection *and* collides `čeští` with `cesta` and
  `formát` with `forma` (the original stemmer's own `at` entries fire on the folded input).

---

## 8. What the team has to decide

Measurement narrowed this to two questions it cannot answer, plus two consequences.

1. **Do we own a Czech stemmer?** Yes under M1 and M7 alike — `FoldedCzechStemmer` has no upstream to
   track; the survey established that no `CzechNormalizationFilter`, no folded-space Czech stemmer
   and no Czech Snowball algorithm exists anywhere. Against that: **up to 348/348 versus the 253/348
   we ship today**, at one term per token in the index. The prototype is ~230 lines and its tables
   are a mechanical rewrite of Lucene's; the fixture is the regression harness. **M7 changes what
   owning it means**: the stemmer serves only queries, its four ambiguous rules become hypothesis
   forks instead of decisions (§6), a table error can never corrupt an index, and every correction
   is a query-time change needing no reindex. A symmetric M1 deployment re-indexes every catalog on
   every stemmer correction.

2. **Where on the recall/precision curve do we sit?** Still the real decision, but run 6 moved the
   whole curve. A16–A19 are dominated by M7 rows (§1) and are off the menu; the frontier is:

   | configuration | bare+crossform | false merges | what it means |
   |---|---|---|---|
   | A13 `[-at]` (M1, symmetric) | 310/348 (89 %) |   8 | palatalized plurals miss; `stůl`, `rajče` split |
   | A22 M7 `[at,epenthetic]`    | 331/348 (95 %) |  10 | palatalized plurals miss; `stůl` splits; nothing else does |
   | A21 M7 `[palat,at,epenthetic]` | 347/348 (99.7 %) | 39 | only `stůl` splits; `cesta`→`český`, `formát`→`forma` one-way |
   | A20 M7 `[all hypotheses]`   | 348/348 (100 %) |  54 | nothing splits; adds `ruka`→`rok`, `buk`→`bok` one-way |

   Two things to hold in mind while choosing. First, **the `st`/`št` ambiguity has an irreducible
   half**: even A22, with the palatalization hypothesis off, pays 4 merges for `čeští`'s unrewritten
   `cest` hypothesis. Second, **the merge counts come from 5 hand-picked confusable pairs**, so they
   are a floor rather than a rate; the comparisons are directionally sound but their magnitudes are
   not transferable to production. Settling this properly needs a real catalogue, and an e-commerce
   corpus may well contain far fewer `-st`/`-št` collisions than a general one — product titles are
   nouns and colour adjectives, not toponyms. How often `formát`-shaped roots (`-át`/`-at` nouns like
   `salát`, `automat`, `plakát`) collide with `-a` feminines is the corpus question the `-at`
   hypothesis adds — though under M7 the collision is one-way and re-tunable without a reindex.

   A defensible interim answer is **A21**, and the vowel-shift call got cheaper both ways: adding it
   (A21 → A20) buys the last pair (`stůl`) plus the last accent-typed form for 15 merges — half the
   symmetric price — and because it is a query-side switch, flipping it later costs nothing. The
   deeper choice is M7's precondition: the query pipeline must expand one query token into OR'd
   terms (§6). If that capability is rejected, the fallback curve is the symmetric one and the old
   A16/A19 recommendation stands.

3. **Consequence for P1.** The survey flagged M2 as *"the mechanism with a claim on the term
   dictionary layout"* and said the second-lane question must be settled before the layout freezes.
   Measurement changes that: **M2 is not worth its cost** (3 pairs of 95 for 1.95× the terms), so the
   layout is **not** obliged to support a second lane per token for accent recall. If a surface lane is
   wanted later it should be argued from exact-phrase or highlighting, on its own merits.
   `AnalyzedTerm.surfaceForm()` should stay regardless; it costs nothing and remains the only place
   the accented original survives.

4. **Consequence for the query planner.** M7 moves the requirement M2 tried to put on the term
   dictionary into the query pipeline instead: **one query token must be expandable into a small set
   of OR'd terms** (measured: ≤ 2 on this vocabulary, average 1.30). That is the same shape synonym
   expansion needs, so it is capability the engine likely wants anyway — but it must exist before M7
   can ship, and phrase/position semantics for hypothesis terms (they share one position) need a
   decision at design time.

## 9. Limits of this measurement

State these before quoting any number in a decision.

- **Single-word inputs only.** No multi-token, phrase or positional behaviour is measured, and
  `terms/form` is therefore an upper bound on real-text term inflation.
- **False-merge counts are a floor, not a rate.** They come from 5 deliberately confusable lemma
  pairs. A mechanism showing 0 here is "did not merge the five traps we set", not "merges nothing" —
  and the two-step rows' floors are the least trustworthy of all, because a second pass widens every
  rule's exposure beyond what any hand-picked probe set samples (§5a).
- **The prefix+fuzzy strategy flatters M4.** It uses Meilisearch's 5/9 length thresholds but applies
  prefix matching to every token, where Meilisearch only expands the last one.
- **32 lemmas is a fixture, not a corpus.** It was built to contain the known failure classes, so it
  over-represents them by construction. It is the right instrument for comparing mechanisms against
  each other and the wrong one for predicting absolute production recall. In particular the
  `kabát`-versus-`rajče` trade of §5 is decided here by which forms the fixture happens to hold; only
  corpus frequencies can decide it for production.
- **A fixture can only measure failures it can commit — and this bit twice.** The palatalization
  rewrite scored zero for two runs because no form in the vocabulary alternated `sk`/`št`; dropping
  the `-at` paradigm scored as *free* for three runs because no `-ata` neuter existed to commit the
  drop's cost — see §10. Before trusting any "rule X buys nothing" or "removing X costs nothing"
  result from this harness, check that the vocabulary contains a case that rule X would fix *and* a
  case it exists for. The same doubt applies to rules **not** listed in §5: run 5's warning that "a
  fourth folded ambiguity could be sitting in the tables unexercised" was vindicated in run 6 — the
  epenthetic `-e-` (§6), found only because the asymmetric M7 stopped both sides from mis-stemming
  identically. A **fifth** could still be sitting there; only a mechanism or vocabulary that breaks
  the symmetry over it would show it.
- **M7's fan-out and merge numbers are vocabulary-bound in a specific way.** The 1.30 average /
  2 maximum terms per token holds for words where at most one ambiguous rule fires; a word ending
  that stacks several forks would emit more, and no such word is in the fixture. And the merge
  counts assume the binary OR match model — hypothesis terms are distinguishable at query
  construction, so a ranking layer could push false merges below the counted ceiling, which this
  harness cannot measure.
- **M7's correctness is an invariant between two stemmers, verified only here.** The query-side
  hypothesis set must cover the folded image of the accented stemmer's output for every word (§6);
  the fixture proves it for 152 forms, not for Czech. Any change to either stemmer needs this
  harness re-run — that is what it is the regression harness *for*.
- **`FoldedCzechStemmer` is a prototype.** Its tables were reviewed against `CzechStemmer` by hand,
  not proven equivalent; the `-at-` and epenthetic findings are evidence that hand review misses
  things, and both were found by measurement rather than by reading.

---

## 10. Corrections to the earlier record

Six runs, each correcting the previous one. Recorded because the *pattern* of error matters more
than any single number here: every one of them was a measurement artifact, not a code defect.

1. **Run 1 (2026-08-27) reported M2 as perfect on every metric and ranked it above M1.** Artifact of
   an incomplete metric set: metrics 1 and 2 could each be satisfied by a *different lane* of the
   same two-lane chain, so the chain scored perfectly while the real query stayed broken. Fixed by
   adding metric 4 (bare-typed cross-form). Lesson: a metric set in which each number can be
   satisfied by a different component of the chain does not measure the chain.
2. **Run 2 reported the palatalization rewrite as buying zero recall for 50 false merges**, and §8
   concluded it "can be declined on the evidence here". Artifact of the **fixture**: every adjective
   in the vocabulary was spelled `dětský`/`dětská`/`dětské`/`dětských` — all `sk`, never `št` — so the
   rewrite had no pair to converge and scored zero for the wrong reason. Adding `dětští`, `pánští`,
   `kuchyňští` and a new `anglický` lemma (for `ck`↔`čt`) shows it **buys 32 pairs**, the largest
   single lever in the whole matrix. The "decline it" conclusion is **withdrawn**; see §8.2.
   This was caught by a reader asking what `palat` meant, not by the harness.
3. **Run 3 (2026-08-28)** also added the two missing combinations, A16 and A17 — the isolated
   variants had been measured but never the configuration that combines the rules each was shown to
   be worth. It reported A17 as a perfect 342/342, which run 4 revised down.
4. **Run 4 (2026-09-01) withdrew run 3's "dropping the `-at` paradigm is a pure win" and A17's
   perfect score.** Artifact of the fixture again, in mirror image to run 2: the justification
   ("Accented `-át` never reached them") described the *original* stemmer without saying so, and
   the drop's own cost — the `-ata` neuters' singular splitting from their plural — needed an
   `-ata` neuter in the vocabulary to be committed, and there was none. Adding
   `rajče`/`rajčata`/`rajčat` shows the drop trades `kabát`'s 8 pairs for `rajče`'s 4, and A17
   landed at 344/348 instead of perfect. Like run 2's error, this was caught by a reader
   questioning the written justification, not by the harness.
5. **Run 5 (2026-09-01, the numbers above) added two-step stemming (A18/A19, §5a) and the
   `forma`/`formát` probes — and the probes convicted the *kept* `-at` entries too.** Every
   single-pass row that keeps them had been hiding 12 false merges (`formát → form` ≡ `forma`):
   A2 8 → 20, A5 88 → 100, and even naive A1 6 → 18. So runs 1–4 understated the keep side's
   *precision* cost exactly as runs 1–3 had missed the drop side's *recall* cost — the same
   fixture-blindness, now on both sides of one switch. With the probes in place, A18 measures
   348/348 at 112 merges and A19 dominates A17. The run-4 sentence "dropping nets +4 pairs at
   unchanged precision" was itself corrected: dropping nets +4 pairs **and −12 merges** against
   single-pass keeping.
6. **Run 6 (2026-09-01) added mechanism M7 (A20–A22, §6) and withdrew run 5's verdict that M1 wins.**
   M7 was not in the survey; it was minted from the matrix itself — A0's index-side row was already
   perfect, so the folded ambiguities never needed to reach the index at all. Its first measurement
   scored A20 at 336/348, and the twelve missing pairs were all `dřevěný`'s: the epenthetic `-e-`
   removal turned out to be a **fourth folded ambiguity**, invisible to every symmetric chain
   because both sides mis-stem `dreveny` identically to `drevn` and still converge. Unlike runs 2–5,
   this artifact was not fixture blindness — it was **mechanism blindness**: no symmetric
   configuration *could* commit the failure, so no vocabulary could expose it. The rule became the
   stemmer's fourth switch, the hypothesis chain forks on it, and A20 measures 348/348 at 54 merges,
   dominating A18 (112) — `shouldShowHypothesisQueriesDominateTwoStepStemming` pins the domination
   and the ≤ 4-terms fan-out bound.

Smaller corrections along the way: two assertions written from the survey's predictions were
disproved by measurement and now carry the measured behaviour with a comment at the site (M1 does not
match production convergence outright without its rewrites; M5 buys one form rather than none). Run
1's `hodinky` lemma was mislabelled — it carried the forms of the derived agent noun `hodinkář` — and
was relabelled with no change to any number.
