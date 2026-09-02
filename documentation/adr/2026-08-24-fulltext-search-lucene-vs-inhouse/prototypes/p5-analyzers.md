# P5 — analyzers: implementation plan

> **Status: a prototype plan, not a decision.** The document follows on from the research
> [`../research.md`](../research.md), in particular from §3 (what to take from Lucene), §4.6, §7
> (prototypes) and §8 (the verifications VK6, VK7, VK8, VK12).
>
> Date: 2026-08-12. Verified against a local Lucene checkout (`/www/oss/lucene`, tags
> `releases/lucene/9.12.3` and `releases/lucene/10.5.0`), against the artifacts in `~/.m2` and against
> evitaDB's source code on the `dev` branch. The anchors into Elasticsearch point at
> `/www/oss/elasticsearch` (branch `main`, `9a100e2d0e41`, verified 2026-08-13); claims about the existing
> solution are taken from the internal analysis of the Edee CMS client, which is not published in
> this repository. Translated from Czech and moved into this record on 2026-08-24.

---

## 1. Goal and criteria

P5 is the first step of the decision gate **P5 → P1 → P2**. Its task is to build the analysis chain — the
part of fulltext that turns an attribute's value into a list of terms — and to do it before any index
structure starts being built. The reason is simple: the term dictionary, the postings and the impact
sidecar (§4.2 of the research) are all a function of what the analyzer produces. When the analysis chain
changes later, the content of all three structures changes and the catalog has to be reindexed. P5
therefore fixes the input P1 builds on.

P5's scope is deliberately narrow. The prototype **adds** no index structure, no new constraint into
EvitaQL and does not touch the write path. It delivers three things:

1. **A dependency on Lucene** introduced into the right module, including the decision about the line and
   the JPMS declarations.
2. **A registry of analyzers** — a mapping of `(collection, locale)` onto an analyzer, with a built-in
   default table for Czech, English, German, Polish and Slovak and with a seam by which the schema
   overrides it.
3. **A tokenization contract** — one method that emits terms with offsets from a string, and a unit
   harness that on real attribute values shows what that method actually produces.

The success criteria per §7 of the research, written out in a measurable form:

| Criterion                       | How it is verified                                             |
|---------------------------------|----------------------------------------------------------------|
| `attributeContains` unchanged   | the existing tests (§10) pass unchanged, including the NFC/NFD set |
| smoke quality of cs stemming    | agreement with the expectations of `TestCzechAnalyzer` and `TestCzechStemmer` |
| smoke quality of en stemming    | agreement with the expectations of `TestEnglishAnalyzer`       |
| tokenization of real data       | a manual review of the output over a sample of catalog values  |
| the dependency does not leak into the driver | the driver's `dependency:tree` does not contain `org.apache.lucene` |

The last row of the table is not a formality — the analysis in §3.2 shows that the most natural-looking
placement of the dependency would drag it into the client driver.

---

## 2. Links to the research

P5 rests on several conclusions the research already closed and is not to reopen them:

- **Lucene is a dependency, not the engine** (§3). We take the analysis chain and, from the core, the class
  `LevenshteinAutomata` as well. Lucene's index part is not used at all — no `IndexWriter`, no codecs, no
  `Directory` on the write path.
- **No vendoring** (§3, item 1). A precedent for a vendored module exists (`evita_roaring_bitmap`), but it
  was forced by the need to reach into the library's package-private internals — see
  `documentation/adr/2026-07-07-roaring-bitmap-vendoring.md`. For analyzers we need nothing of the kind:
  `Analyzer` is a public abstract class with a public contract.
- **The structures live per locale** (§4.1). Analysis is per language, so the registry of analyzers has
  exactly the same granularity as the fulltext structures P1 will build.
- **The index and query chains deliberately differ** (§1.2, VK12). Agreement is required not on the
  configuration of both chains but on the terms they produce. Synonyms typically live only in the query
  chain. P5 has to be able to express that difference from the start, even though synonyms themselves come
  only later.
- **Typo tolerance has a cap of distance 2** (§4.6, VK6). `LevenshteinAutomata` has it hard-coded
  (`MAXIMUM_SUPPORTED_DISTANCE = 2`, `lucene/core/.../util/automaton/LevenshteinAutomata.java:37`). P5 does
  not use that class yet — P3 does — but the choice of Lucene line concerns it, because it comes in the
  same jar.

---

## 3. The choice of Lucene line and a dependency analysis

### 3.1 Which line: 9.12.x, or 10.x

The research (VK8) says the 9.12.x line runs on JDK 11 and above, the 10.x line requires JDK 21, and that
with the confirmed JDK 21 baseline (Z1) both are available. **Verification against the repo shifts that
conclusion.** The root `pom.xml` on the `dev` branch still has `<java.version>17</java.version>`
(`pom.xml:124`) and that value propagates into `<release>`, `<source>` and `<target>` (`pom.xml:654-656`).
The upgrade to JDK 21 did happen and passed, but has not landed in `dev` yet. Choosing the 10.x line would
therefore condition P5 on the baseline in the pom being raised first — which is work outside the
prototype's scope and outside its control.

The other half of the answer is that it hardly matters, because **the API we will be calling is
practically identical between the two lines**. A file comparison between the tags `releases/lucene/9.12.3`
and `releases/lucene/10.5.0` gives this result:

| Class | Difference 9.12.3 → 10.5.0 |
|---|---|
| `CzechAnalyzer`, `CzechStemFilter` | unchanged |
| `EnglishAnalyzer`, `GermanAnalyzer`, `PolishAnalyzer` | unchanged |
| `TokenStream`, `CharTermAttribute`, `OffsetAttribute` | unchanged |
| `PositionIncrementAttribute`, `ASCIIFoldingFilter` | unchanged |
| `LevenshteinAutomata` | unchanged |
| `Analyzer` | the only change is a typo fix in a JavaDoc example |
| `CustomAnalyzer` | two internal refactorings (`toArray`, `HashMap.newHashMap`), the API unchanged |
| `CzechStemmer` | `public class` → `class`, `public int stem` → `int stem` |

The last row is the only real visibility change in the whole set and does not concern us: `CzechStemmer` is
called through `CzechStemFilter`, never directly. The other differences between the lines sit in Lucene's
index part, which we will not use.

The artifacts of the 9.12 line are available in the local `~/.m2` and their properties could be verified
directly. The sizes below come from what happened to be lying in the cache — i.e. from **mixed patch
levels**, not from the recommended combination: `lucene-core-9.12.3.jar` is 4.27 MB,
`lucene-analysis-common-9.12.1.jar` 1.72 MB and `lucene-analysis-stempel-9.12.1.jar` 519 kB. As an
order-of-magnitude figure that suffices, as a configuration template it does not. The class
`org.apache.lucene.analysis.Analyzer` in the 9.12.3 jar carries class file major version 55, i.e. Java 11
bytecode — on today's seventeen baseline it loads without any intervention.

**Recommendation: start on the 9.12.x line and pin all three artifacts to the same patch version** (at the
time of writing 9.12.3, the highest released in this line) through a single `lucene.version` property in
the root pom. Mixing patch levels across Lucene artifacts is exactly what a plan resting on determinism
must not let arise implicitly. The line has no functional disadvantage for us, does not commit P5 to
somebody else's work, and the upgrade to 10.x is cheap later precisely because the API used does not
differ — once the baseline in the pom really jumps to 21, the transition is a mechanical version-number
change. When exactly to do it is open question P5-3; the support window of the 9.x line against 10.x has to
be verified at the source (the research cites `endoflife.date`), not estimated.

**A rejected variant: start on 10.x right away.** The only advantage is longer support and the transition
not having to be repeated. It lost because it ties the delivery of P5 to JDK 21 landing in `dev`, which
the prototype has no way of influencing, and because the value gained is zero — not one class we call
behaves differently in 10.x. It will be worth revisiting the moment `java.version` in the root pom really
is 21; until then it is merely a risk taken on without a counterpart.

### 3.2 Which module the dependency belongs in

The instinctive answer is `evita_common`. Everything derived from the locale lives there —
`LocalizedStringComparator` as well as `CollationKeyCache` in the package `io.evitadb.comparator` — and the
module is lowest in the graph, so anybody could reach the registry. **That answer is wrong** and it is
worth saying why, so that somebody does not introduce it again.

The Java driver (`evita_java_driver`) depends on `evita_api`
(`evita_external_api/evita_external_api_grpc/client/pom.xml:40`) and `evita_api` depends on `evita_common`
(`evita_api/pom.xml:38`). Lucene in `evita_common` would therefore end up in the client driver and along
with it in the shaded `evita_java_driver_all_in_one`. That is roughly six megabytes extra in an artifact
that analyzes no text — analysis runs exclusively next to the data, never on the client (§1.1 and §1.2 of
the research, the "smart client" trap). It would moreover raise a question of redistributing foreign
Apache-2.0 code in a published uber-jar, which otherwise we need not address at all.

**Recommendation: `evita_engine`.** The driver's whole dependency chain was examined to the bottom because
of this — `evita_java_driver` depends on `evita_api` and `evita_external_api_grpc_shared`, that on
`evita_api` and `evita_query`, and **`evita_engine` is nowhere on that path**. A dependency in the engine
therefore reaches neither the driver nor the uber-jar. The fulltext structures moreover live per §4.1 in
`GlobalEntityIndex`, i.e. in `evita_engine`; the translation of a query into a candidate bitmap too. The
analyzer has no consumer outside the engine and the driver must not get it. The entry into
`evita_engine/pom.xml` is one `<dependency>` block beside the existing ones (Kryo, hppc, Byte Buddy) and one
`requires` line in `evita_engine/src/main/java/module-info.java`.

**A considered variant: a separate module `evita_analysis`.** It would make sense if the analyzers had a
consumer outside the engine too, or if they were to be switchable off in a distribution. Neither holds
today, and a new module costs its own pom, its own `module-info.java` and its own line in the dependency
graph in the README. It will be worth revisiting if it turns out fulltext should be an optional part of the
server — i.e. first at the gate P5 → P1 → P2, not now.

### 3.3 JPMS

Here the news is exclusively good. All three jars carry a **native `module-info.class`**, not merely an
`Automatic-Module-Name` in the manifest; verified via `jar --describe-module` over the artifacts in `~/.m2`:

| Artifact | Module name |
|---|---|
| `lucene-core` | `org.apache.lucene.core` |
| `lucene-analysis-common` | `org.apache.lucene.analysis.common` |
| `lucene-analysis-stempel` | `org.apache.lucene.analysis.stempel` |

The packages we need are exported: `org.apache.lucene.analysis`, `org.apache.lucene.analysis.standard`,
`org.apache.lucene.analysis.tokenattributes` and `org.apache.lucene.util.automaton` from the core;
`org.apache.lucene.analysis.cz`, `.de`, `.en`, `.custom`, `.core`, `.miscellaneous` and `.hunspell` from
analysis-common; and `analysis.pl` from stempel. The module `org.apache.lucene.analysis.common` declares
`requires org.apache.lucene.core`, so a single `requires` in our module-info suffices and the core comes
with it:

```java
requires org.apache.lucene.analysis.common;
// only if the decision falls to support Polish via stempel:
requires org.apache.lucene.analysis.stempel;
```

One place deserves attention. Lucene has a service layer: `lucene-core` declares
`uses org.apache.lucene.analysis.TokenizerFactory` (and the same for `CharFilterFactory` and
`TokenFilterFactory`), analysis-common supplies them through `provides`. That layer is used by
`CustomAnalyzer` when a pipeline is composed by names (`addTokenFilter("lowercase")`). The lookup runs
through `ServiceLoader.load(clazz, classloader)`
(`lucene/core/.../analysis/AnalysisSPILoader.java:80`), which on the module path means the providers are
found only when the module is really resolved in the graph. If we want to avoid that entirely,
`CustomAnalyzer.Builder` also has overloads that take the factory class directly
(`withTokenizer(Class<? extends TokenizerFactory>, String...)`,
`lucene/analysis/common/.../analysis/custom/CustomAnalyzer.java:291`) and do not consult SPI at all.

**Recommendation: in P5 compose the pipeline through `CustomAnalyzer`'s typed overloads, not through
names.** A whole category of "the factory was not found on the module path" errors thereby falls away and
compile-time checking remains. The configuration from the schema (§4.5) is translated into classes in our
registry, not in Lucene's SPI. A name as an analyzer's identifier in the schema stays — it is merely
translated by our code.

### 3.4 Licence and attribution

Lucene is Apache License 2.0 and evitaDB is Business Source License 1.1. The two do not cross, because **it
is a dependency, not a derivative work**. Section 4 of the Apache licence imposes duties on whoever
redistributes the work in its original or a modified form; an ordinary Maven dependency the user downloads
from Central alongside our artifact is not redistribution.

There is a single `NOTICE` in the repo and it is in `evita_roaring_bitmap/` — precisely because there the
code really **is copied and changed** (renamed packages, a selected subset of classes, changed behaviour;
see `evita_roaring_bitmap/NOTICE`). For analyzers we do nothing of the kind, so no new `NOTICE` and no
change to `LICENSE` arises. Kryo, hppc, Byte Buddy and the other existing Apache-2.0 dependencies are
exactly the same case and have no special action either.

The only place where a duty would arise is the shaded artifact — and that is arranged in advance:
`evita_java_driver_all_in_one` has in its shade plugin an `ApacheLicenseResourceTransformer` and an
`ApacheNoticeResourceTransformer`
(`evita_external_api/evita_external_api_grpc/client_all_in_one/pom.xml:175-176`), which merge the NOTICE
files of packaged libraries automatically. Because per §3.2 we do not let Lucene into the driver, not even
that applies.

**The real licence question is not Lucene but the Slovak dictionary** — see §5.3.

---

## 4. The shape of the integration

### 4.1 The registry of analyzers

The registry is the single point through which an analyzer can be reached in the engine. Its key is the
pair `(collection, locale)`, because the schema may override the default choice per collection — the
language of articles in a CMS and the language of product names are analyzed with the same language, but
not necessarily with the same recipe.

In shape, the registry should follow `CollationKeyCache`
(`evita_common/src/main/java/io/evitadb/comparator/CollationKeyCache.java`): a static `ConcurrentHashMap`
keyed by locale (`:116`) and an access method `forLocale(Locale)` (`:162`) that produces the instance
lazily on the first query. Analyzers are expensive to create (for Czech 172 stopwords are loaded from a
resource in the jar, for Polish a 2.1 MB stemmer table) and cheap to share, so lazy creation and one
instance per combination is the right compromise.

The built-in default table of languages is part of the engine, not of configuration. A proposal for its
content is in §5. The key is the locale's language, not the whole locale — `cs_CZ` and `cs` are to get the
same analyzer — and for a language not in the table a defined fallback is needed. Per the rule in
`CLAUDE.md` about defensive design it **must not** be silently skipped; either an explicitly named generic
analyzer is returned, or an exception is thrown. The recommendation is to return a generic analyzer
(tokenization plus lowercasing, no stemming) and to report the fact that the language has no recipe of its
own in the log on first use — fulltext over an unknown language is still better than a query error, and the
fallback has to be named in the documentation, not derived from behaviour.

**A template for such a registry exists and it is home-grown.** The analysis of the existing Edee CMS
client (internal, §2.3) describes exactly this construction in
production: a static map of named analyzers with two families — in-house Czech extensions (`czech-fg`,
`czech-base`, `czech-edee`, `czech-hunspell`, `universal` and their `-summon` variants) and direct Lucene
analyzers for twenty languages — plus a method by which an application **registers its own analyzer at
runtime**. The Hunspell dictionary `cs_CZ.dic` and `cs_CZ.aff` is packaged right in the library and is
loaded statically once at class initialization, which is incidentally a second argument for lazy instance
creation. Two things from it are worth adopting: **a name as an analyzer's public identifier**, because it
can then be written into the schema without the schema knowing the Lucene type (§4.5), and **the registry's
openness at runtime**, without which every unusual language or unusual recipe ends up as a change in the
engine. The same analysis is at the same time the source of the test expectations (§10.3): the set of
recipes that really exist in production says what the registry has to be able to express for an existing
deployment to be transferable.

### 4.2 The tokenization contract

Lucene's `Analyzer` is a streaming API and its protocol has to be observed exactly, otherwise it returns
empty or truncated results. The calling sequence is: obtain a `TokenStream` through
`tokenStream(String fieldName, String text)` (`lucene/core/src/java/org/apache/lucene/analysis/Analyzer.java:183`),
fetch the attributes, call `reset()`, iterate through `incrementToken()`, then `end()` and finally close the
stream.

The attributes that interest us are three: `CharTermAttribute` carries the term itself, `OffsetAttribute`
the start and end offsets in the original text and `PositionIncrementAttribute` the position shift, which is
non-zero for synonyms and zero for a stopword. We need the offsets because of highlighting (§4.6 of the
research — highlighting is a re-analysis of the returned page at render time, without index support), so the
contract has to emit them, even though nobody in P5 consumes them yet.

The proposed shape is one method returning a list of records `(term, startOffset, endOffset,
positionIncrement)` — an immutable record per the project's conventions. On the hot write path a variant
with a callback that allocates no list at all will come in handy later; in P5 the simpler one suffices,
because the prototype does not measure throughput. The seam for the second one has to be left, though.

One limit is worth recording: `StandardTokenizer` has a default token length cap
(`StandardAnalyzer.DEFAULT_MAX_TOKEN_LENGTH`) and a hard limit `MAX_TOKEN_LENGTH_LIMIT = 1024 * 1024`
(`lucene/core/.../analysis/standard/StandardTokenizer.java:80`). A longer token is split. For e-commerce
attributes it will never show, for the CMS profile (Z8) neither — it is not about the text's length but
about the length of one word.

### 4.3 The analyzer's lifecycle and concurrency

`Analyzer` is thread-safe, but in a way that has consequences. It caches the stream's components in a
`CloseableThreadLocal<Object> storedValue` (`lucene/core/src/java/org/apache/lucene/analysis/Analyzer.java:91`)
and that holds, besides a `ThreadLocal` with a weak reference, a `WeakHashMap<Thread, T> hardRefs` written
into under `synchronized` (`lucene/core/src/java/org/apache/lucene/util/CloseableThreadLocal.java:52,87`).

For today's engine that is fine: evitaDB uses virtual threads nowhere (verified by searching for
`ofVirtual` and `newVirtualThreadPerTaskExecutor` across `evita_engine`, `evita_common` and
`evita_external_api` — zero occurrences) and threads from a bounded pool are recycled, so the cache really
does hit. Should analysis ever be called from virtual threads, though, both properties turn against us: a
per-thread cache stops making sense, because every task gets a new thread, and a `synchronized` block on
JDK 21 pins the carrier thread.

**A recommendation for P5: leave the default behaviour and write a comment about it at the site.** The
prototype has platform threads and changing that now would mean solving a problem we do not have. What P5
should do is leave a seam for it — a custom `ReuseStrategy` can be passed to an `Analyzer` in the
constructor (`Analyzer.java:109`), so a possible change is local. `Analyzer` is `Closeable` and the registry
has to close it when the catalog is closed, otherwise `hardRefs` holds the stream's components for the
whole life of the process.

### 4.4 The index and query chains

Per VK12 the two chains differ deliberately and agreement is required on the produced terms, not on the
configuration. The registry therefore has to be able from the start to emit **two analyzers for the same
`(collection, locale)` pair** — an index one and a query one — even though in P5 it will return them
identical.

The concrete reason the seam cannot be deferred: synonyms and entity recognition (§4.6 and §1.3 of the
research) are query-time expansions over a hot-swappable dictionary. If the registry could emit only one
analyzer, that difference would be built on later as an exception beside the registry — and that is exactly
how the drift arises that §1.2 describes as the "smart client" trap, only inside the engine.

Lucene has one more thing for the query side worth knowing before it appears as a surprise:
`Analyzer.normalize(String fieldName, String text)` (`Analyzer.java:213`) is a separate path for
*single-term* queries into which the language analyzers wire **only the filters that do not change length
and do not split tokens**. `CzechAnalyzer.normalize` is literally `new LowerCaseFilter(in)`
(`CzechAnalyzer.java:124-126`) — no stopwords, no stemming. Why Lucene does it that way and what follows for
us is addressed in §6.

**That difference is to be enforced by types, not by discipline.** Verification over the Elasticsearch
checkout (branch `main`, `9a100e2d0e41`, 2026-08-13) shows a mechanism worth adopting almost literally.
Elasticsearch has the enum `AnalysisMode` (`server/…/index/analysis/AnalysisMode.java`) with three values
`INDEX_TIME`, `SEARCH_TIME` and `ALL`. Every component of the chain — a token filter as well as a char
filter — **declares** its mode and an analyzer derives its own mode by merging the modes of all its
components. Merging `INDEX_TIME` with `SEARCH_TIME` throws an exception (`:26` and `:36`): a chain mixing an
index and a query component cannot come into being. The connection to the schema is then a one-liner — the
parameter `analyzer` has the validator `checkAllowedInMode(AnalysisMode.INDEX_TIME)`, whereas
`search_analyzer` and `search_quote_analyzer` have `SEARCH_TIME` (`TextParams.java:60`, `:77`, `:92`).

Most valuable is what follows from it for **hot-swappable artifacts**. The synonym filter has a
configuration option `updateable` and it reads like this (`SynonymTokenFilterFactory.java:199`):

```java
this.analysisMode = updateable ? AnalysisMode.SEARCH_TIME : AnalysisMode.ALL;
```

Declaring a dictionary replaceable at runtime therefore **automatically** turns it into a component that
cannot be used at indexing time — not by convention, not by documentation, but by the analyzers' type
system, which rejects the attempt. That guarantees a replaceable artifact never could have been baked into
the index and its replacement cannot make the index diverge from the data. The research claims in §4.6 that
the synonym dictionary and the entity dictionary will be hot-swappable data artifacts with no impact on
index structures — and this is the way of turning that claim into **a checked property instead of a
promise**. Without enforcement it suffices for somebody to wire synonyms into the indexing chain once, and
replacing the dictionary starts silently diverging the index from the data; the error then manifests as
inexplicably missing results, not as an exception, which is exactly the class of failure `CLAUDE.md`
prohibits with its defensive-design rule.

**Recommendation: a component of the analysis chain in evitaDB carries a mode flag and the registry refuses
to assemble a chain that mixes an index and a query component.** It is a few dozen lines and it enforces a
property everybody who reaches into the pipeline would otherwise have to police.

**There are three slots, not two.** A text field in Elasticsearch has three analyzer slots
(`TextParams.Analyzers`, `TextParams.java:33`): `analyzer` for indexing, `search_analyzer` for the query
text (the default being the value of `analyzer`, and if even that was not set, a named analyzer
`default_search` is looked up, `:66-72`) and **`search_quote_analyzer`** for query text given in quotation
marks, i.e. for a phrase query (the default being `search_analyzer`, `:82-89`). The third slot is
inconspicuous but exists for a concrete reason: an analyzer that discards stopwords damages the phrase "the
who" or "to be or not to be", because nothing remains of it or something else remains. It is a purely
server-side invention — no such concept exists in Lucene.

It concerns us in the CMS profile (Z8), where the texts are long and stopwords make sense.
**Recommendation: allow for a separate phrase chain by design already in P5**, even if in F1 it points at
the same object as the query one. The reason is the one §8 formulates in general: the analysis chain is part
of the definition of the index's content, so splitting the slots later is a change of a locked parameter,
i.e. reindexing. Splitting an unused slot today is free; splitting it later costs a catalog.

**A home-grown precedent for the hot swap exists and behaves exactly this way.** The analysis of the
existing client (internal, §2.3 and §4.6) describes synonyms that are
full-fledged and replaceable at runtime: `SynonymManager` loads a file in the **Solr synonym format**
(`SolrSynonymParser`) from an arbitrary source, builds a `SynonymMap` from it, wires it into the chain as a
`SynonymGraphFilter`, and the method `reloadSynonyms()` permits the dictionary to be replaced without a
restart and without reindexing. The Solr format supports multi-word synonyms and `SynonymGraphFilter` can
process them, so matching across several query words is there in a basic form. What is key for us is that
synonyms go in that client **exclusively to the query side** — the condition
`!isForIndexing && indexConfig.isSynonymsEnabled()` — whereas `SummonFilter` runs only at indexing time. It
is therefore the same shape `AnalysisMode` describes, only enforced by a hand-written condition in one place
instead of by the type system. Two things follow: a hot-swappable artifact is a proven thing, not design
speculation, and at the same time it is visible how fragile holding such a rule with a single `if` is —
which is precisely the reason for the mode flag a paragraph above.

### 4.5 The contract towards the schema

The schema's details are addressed by a parallel document. P5 needs only to define the boundary, and that
is:

- **The schema supplies an analyzer's identifier and its parameters.** The identifier is a string
  (`"czech"`, `"english"`, `"custom:…"`), the parameters are named values — typically a custom stopword list
  and a list of expressions exempted from stemming, because every Lucene language analyzer can do both
  through its constructor.
- **P5 supplies the registry**, which translates that identifier into an `Analyzer` instance, holds the
  default language table and takes care of the instances' lifecycle.
- **The interface between them is a resolver** to which the registry passes `(collection, locale)` and which
  returns either the identifier with parameters determined by the schema, or nothing, which means "take the
  default for the locale's language".

This shape keeps the schema independent of Lucene: a Lucene type never appears in the schema. At the same
time it leaves the door open for `"custom:…"`, where the parameters describe the pipeline as a list of steps
— `CustomAnalyzer` then applies (§3.3).

### 4.6 A catalog of filters the pipeline has to offer or deliberately reject

The analysis of the existing client (internal, §2.5, §6.5 and §6.6)
names three concrete, production-proven filters that so far have no counterpart in P5. The analysis itself
carries them as a risk that the new solution will come out poorer than the old one — and that is exactly the
class of finding the plan has to either take up or reject with a reason given. The verdict below is
therefore stated for each of them, not merely noted.

**1. `RawTermsFilter` — protecting an individual token from analysis. Take up, but by a different route.**
A term wrapped in a special marker is inserted into the text; the filter removes the marker and marks the
token with a `KeywordAttribute`, thereby **protecting it from stemming, lowercasing and diacritics
removal**. It is switched on by the flag `enableRawTermsAnalyzer` and, uniquely among the old client's
filters, runs on **both sides of the chain**, index and query — the protected form has to meet itself.

The substitute is **not** the per-attribute choice of analyzer of §4.5. That protects a whole field; this
filter protects a specific token **inside a sentence**, and that difference is practical, not academic: in a
product name the catalog number or model designation is to be protected, whereas the rest of the sentence is
to be stemmed. For e-commerce it is a common need and §6.6 of the analysis carries it as uncovered.

I do not, however, adopt the old client's route literally, and for one concrete reason: for it the marker is
expected **right in the stored value**, so a mark meant for the analyzer is written into the data. That is
unacceptable for evitaDB — an attribute's value is user data, not pipeline configuration, and nothing would
then prevent the marker from reaching the response. The same capability can be expressed without touching
the data: **with a list of expressions exempted from stemming in the analyzer's parameters**, which the
registry translates into a `SetKeywordMarkerFilter`. Lucene's language analyzers already have that filter in
their pipeline (§5.1) and `CzechAnalyzer` accepts the corresponding set in its constructor, so nothing new
is written. The difference against the old client is that the protected expressions are given in the schema
once, not in every value separately — which besides data cleanliness is better operability too.

**2. `WordWithNumberSplitFilter` — splitting a token into a word and a numeric part. Take up.** A token
beginning or ending with a digit is split into a numeric and a textual part and **both are added at the same
position**: from `123xyz` arises, besides the original token, also `123` and `xyz`. It is switched on by the
flag `enableWordNumberAnalyzer` and applied by wrapping the whole analyzer. It is a small, concrete and
production-proven filter for e-commerce catalog numbers, which §6.6 of the analysis carries as uncovered. It
belongs in the catalog as an optional pipeline step **switched off by default**: adding tokens at the same
position enlarges both the dictionary and the postings and it has no business in the CMS profile, whereas
for a field with an EAN or a catalog number it is exactly what the user expects.

**3. `DiacriticFilter` — and why it is *not* NFD. Reject as a component, adopt as a test criterion.** The
existing filter is a manual, fully written-out conversion table of European characters with diacritics onto
basic Latin, including cases decomposition does not address at all — `ß → ss`, `æ → ae` and other
multi-character conversions. **It is not Unicode NFD decomposition**, it is a `switch` over specific code
points. (It respects `KeywordAttribute` while doing so, so it leaves tokens protected per point 1 alone;
that detail has to be repeated for us, otherwise protecting a term ends at folding diacritics.)

Our plan builds on a different foundation: `ASCIIFoldingFilter` after the stemmer (§7.3) over NFC input,
beside the NFD normalization that exists in evitaDB for a different path (§7.1). I therefore **reject**
adopting the manual table as a component — two independent mechanisms for the same thing are exactly what
§7.1 avoids, and maintaining our own table of code points is work without a counterpart when upstream does
it for us and tests it. *What would have to be different for it to be worth revisiting:* if it turned out
`ASCIIFoldingFilter` does not cover a multi-character conversion that matters in a specific language. A real
candidate is `ß → ss` for German — there, though, `GermanNormalizationFilter` handles it (§5.1), so it would
be a duplication, not a gap.

Rejecting the component does not, however, **cancel the commitment**. The results of both paths agree in
most cases, but not in all, and migrating an existing website from one to the other therefore **changes
search results**. That is not a hypothesis dismissable with a footnote: it is a parity §10.4 measures, and
its list of differences is the only material from which it can be said in advance what the change will
touch. Who informs the operator and when is a product decision — carried as P5-6 (§11).

---

## 5. Language coverage

### 5.1 Czech, English and German

All three have a ready-made analyzer in `lucene-analysis-common` and all three are built to the same
pattern: `StandardTokenizer`, `LowerCaseFilter`, `StopFilter`, optionally `SetKeywordMarkerFilter` for
expressions exempted from stemming, and finally the language stemmer.

| Language   | Analyzer          | Stemmer                                        | Handling of diacritics |
|------------|-------------------|------------------------------------------------|------------------------|
| Czech      | `CzechAnalyzer`   | `CzechStemFilter` (light, algorithmic)         | **preserves them**     |
| English    | `EnglishAnalyzer` | `PorterStemFilter` + `EnglishPossessiveFilter` | not applicable         |
| German     | `GermanAnalyzer`  | `GermanLightStemFilter`                        | **folds them**         |

That last column is more important than it looks and §7.3 returns to it. German has a
`GermanNormalizationFilter` in its pipeline, which per its own documentation replaces "ß" with "ss" and "ä",
"ö", "ü" with "a", "o", "u"
(`lucene/analysis/common/.../analysis/de/GermanNormalizationFilter.java:26-32`). The upstream test shows it
on one pair: `Schaltflächen` and `Schaltflaechen` both give `schaltflach`
(`lucene/analysis/common/src/test/.../analysis/de/TestGermanAnalyzer.java:62-63`).

Czech does nothing of the kind. `CzechAnalyzer.createComponents` is `StandardTokenizer`, `LowerCaseFilter`,
`StopFilter` and `CzechStemFilter` (`CzechAnalyzer.java:113-121`) — the háčky and čárky stay. The upstream
test: `Česká Republika` gives `česk` and `republik`
(`lucene/analysis/common/src/test/.../analysis/cz/TestCzechAnalyzer.java:46`). It means the query "cerna"
**will not find** the product "černá" unless something else is done about it — and what can be done is the
subject of open question O3 of the research, analyzed in §7.3.

The Czech stemmer is algorithmic and light, so some of its outputs are not words. Upstream says so outright
in a comment in its test class ("it's algorithmic, so some stems are nonsense") and it is visible on the
forms of the word "muž", which all converge on `muh`
(`lucene/analysis/common/src/test/.../analysis/cz/TestCzechStemmer.java:64-70`). For searching that does not
matter — what matters is that the forms converge consistently, not that the stem be a word. For P5 it is a
reminder that the term dictionary will not be readable and that the suggester (P3) must not offer stems to
the user.

### 5.2 Polish

Polish has a `PolishAnalyzer`, but in a separate artifact `lucene-analysis-stempel` (VK7 confirmed: the file
is `lucene/analysis/stempel/src/java/org/apache/lucene/analysis/pl/PolishAnalyzer.java`). The pipeline is of
the same shape as the others, only the stemmer is a `StempelFilter` over a table loaded from a resource. The
upstream test gives `studenta` and `studenci` as `student`
(`lucene/analysis/stempel/src/test/.../analysis/pl/TestPolishAnalyzer.java:34-35`).

The cost is one extra jar (519 kB) and one `requires` in module-info. Inside the jar is the stemmer's binary
table `stemmer_20000.tbl` of 2.1 MB unpacked, which is loaded when the analyzer is initialized — hence the
lazy instance creation in §4.1.

**Recommendation: support Polish right away in P5.** It is one `<dependency>` and one `requires`, the recipe
is finished and tested upstream and deferring it would mean the registry would later have to be extended
because of one language.

### 5.3 Slovak

Here there is no ready-made path. A `SlovakAnalyzer` in Lucene **does not exist** — verified by searching
all the analyzers in `lucene/analysis/` — and that is a fact VK7 records and that the search confirmed.
Three paths remain and none is free.

**Variant A — Hunspell.** `lucene-analysis-common` contains a complete implementation of Hunspell including
`HunspellStemFilter` and the class `Dictionary`, which reads the standard pair of `.aff` and `.dic` files.
The quality would be the highest of the paths offered, because a dictionary stemmer beats an algorithmic
one. Two complications: the first is that the dictionary has to come from somewhere and evitaDB does not
have it in its jar; the second is the licence — Slovak Hunspell dictionaries come from the LibreOffice
circle and their licence terms **have to be verified for the specific dictionary, not estimated**. Should
they be incompatible with evitaDB's distribution, the solution is trivial: the dictionary is not packaged
into the jar but loaded from a path in the configuration, so nothing foreign is redistributed and the
responsibility passes to the operator.

A technical note about Hunspell: the most common `Dictionary` constructor wants an
`org.apache.lucene.store.Directory` for temporary sorting files, which is a class from the core's index
part. It is not blocking — we have the core anyway — and there is moreover a constructor taking a
`SortingStrategy` directly (`lucene/analysis/common/.../analysis/hunspell/Dictionary.java:226`), and
`SortingStrategy.inMemory()` (`.../hunspell/SortingStrategy.java:147`) avoids `Directory` entirely. For a
dictionary of Slovak's size in-memory sorting is in order.

**Variant B — Czech as a substitute.** Slovak and Czech are morphologically close and `CzechStemmer` cuts
off endings that partly overlap. The cost is zero, the quality is unknown and has to be measured — that is
exactly the point of the smoke test in §10. The risk is that a stem converges wrongly and words unrelated to
each other collapse onto the same term.

**Variant C — a custom pipeline through `CustomAnalyzer`.** `StandardTokenizer`, `LowerCaseFilter`, Slovak
stopwords and **no stemmer**. Quality will be worse in recall (word forms do not converge), but never bad in
precision — nothing collapses by mistake. It is a safe lower bound.

**Recommendation: in P5 deliver variant C as the default and measure variant B.** Without a stemmer the
behaviour is predictable and needs no foreign data file. If the smoke test shows the Czech stemmer gives
sensible results on Slovak, switching the default to B is a one-line change in the table. Variant A is the
right target, but it belongs beyond the gate — it requires resolving both the origin and the licence of the
dictionary, and that is work P5 should not be delayed by.

---

## 6. Stemming versus prefix and typo

This is the least obvious fork in the whole of P5 and the research did not open it, because it shows only
on looking at the Lucene API.

The observation it starts from: `CzechAnalyzer.normalize` (`CzechAnalyzer.java:124-126`) applies **only** a
`LowerCaseFilter` — no stemming, no stopwords. Lucene does it that way for all its language analyzers and
the reason is that `normalize` serves prefix and fuzzy queries, where stemming would do harm. And it would
do harm because it changes the term's length.

Let us develop that on our structures. The term dictionary (§4.2 of the research) contains what the index
analyzer produced. If the analyzer stems, the dictionary contains **stems**, and then:

- **A prefix scan** (§4.6) runs over stems. A user typing "čern" searches in a space where the stem `čern`
  is stored, not the word `černá`. Mostly it works, because stemming trims the end — but for cases where
  `normalize` in `CzechStemmer` rewrites the term further (`č` into `k`, `ž` into `h`;
  `CzechStemmer.java:136-142`), the prefix "muž" finds nothing, because the dictionary has `muh`.
- **The Levenshtein distance** is measured against stems. A term two characters shorter has a smaller budget
  for typos and the thresholds adopted from Algolia and Meilisearch (a typo from four to five characters,
  two from eight to nine) are computed from a different length than the user typed.
- **The suggester** (P3) scores candidate terms by postings cardinality. The candidates are stems, so they
  must not be shown to the user directly.

It is no coincidence that none of the three e-commerce engines of §2.1 of the research stems. Algolia,
Meilisearch and Typesense all work with surface forms and do not reach morphology at all — their domain is
short structured fields where the user types a brand and a model, and there stemming has no business. For
Czech and Slovak that does not hold, though: "pánské boty" and "pánských bot" are forms that have to meet,
otherwise recall is unusable.

Three possible shapes and their cost:

1. **Stem, the dictionary holds stems.** The cheapest on space, the best recall, but the prefix and the typo
   are computed in the space of stems with all the consequences above.
2. **Do not stem, the dictionary holds surface forms.** The prefix and the typo behave exactly as the user
   expects, but "pánských" will not find "pánské" and in Czech that is a serious loss.
3. **Both — the dictionary holds the surface form and a stem alongside it.** The prefix and the typo run
   over the surface forms, the match is evaluated through the stem. The cost is roughly a doubled term
   dictionary and one extra indirection; the postings are **not doubled**, because the surface forms of the
   same stem can share one bitmap.

**Recommendation: P5 does not decide this fork, but it has to expose it.** Concretely: the tokenization
contract (§4.2) is to emit the surface form alongside the stem, because after analysis they cannot be
recovered. The cost is zero as long as nobody consumes them, and without it P1 would have to rewrite the
chain.

The leaning is towards variant 3 for the e-commerce profile and variant 1 for the CMS profile (Z8) — for
long articles recall is more important than the prefix's precision and the dictionary is substantially
bigger there, so the doubling hurts more. P1 will decide it by measuring the dictionary's size and P3 by the
suggester's quality; it is recorded as a new open question (§11).

---

## 7. Coexistence with NFD and `attributeContains`

### 7.1 Two independent modes

"Coexistence" in P5's brief does not mean today's and the new path should agree on anything. It means the
exact opposite: **they are two independent normalization modes over the same input text and P5 must not
touch today's one.**

Today's mode is this. `FilterIndex` normalizes every stored string key into Unicode NFD
(`evita_engine/src/main/java/io/evitadb/index/attribute/FilterIndex.java:277-284`) and normalizes the sought
expression the same way — `getRecordsWhoseValuesContains` pushes the text through the normalizer and then
calls a plain `String.contains` (`FilterIndex.java:592-596`). The prefetch branch does the same
independently, so that both paths give the same result: `createCanonicalPredicate` normalizes the sought
expression once and every candidate value on comparison
(`AbstractAttributeStringSearchTranslator.java:137-141` in the package
`io.evitadb.core.query.filter.translator.attribute`).

From that follows what today's `attributeContains` is and is not. It **is** an exact substring that is
resilient to whether the text is written precomposed or decomposed. It is **not** a case-insensitive search
— the constraint's documentation says so explicitly ("Case-sensitive: 'abc' matches 'abc' but not 'ABC'",
`evita_query/src/main/java/io/evitadb/api/query/filter/AttributeContains.java:41`) and there really is no
lowercasing in the code. And it is **not** a search resilient to diacritics: NFD text merely decomposes,
combining marks stay in it, so "cerna" does not find "černá" even today.

The new fulltext mode is by contrast lowercasing, stopwords, stemming — and for German the folding of
umlauts on top. Those two modes are not to meet and P5 is not to improve today's behaviour in any way.
`attributeContains` has a different contract and the research confirms it (§4.4: "Today's
`attributeContains`/`attributeStartsWith` remain unchanged").

**And it is stronger than a convention — it is an on-disk format.** The record
`documentation/adr/2026-08-10-stored-value-normalization-split.md` records that bucket keys are persisted
**already normalized** (`InvertedIndex` applies the normalizer in `addRecord` and `AttributeIndexLoader`
pours the stored points into the tree unchanged), so *changing the normalizer is an on-disk format change
for the given attribute type*. There is even a precedent of a migration for it: `Migration_2026_2` rekeys
the string (NFD) filtering parts on upgrade. Whoever wanted to "unify normalization" between fulltext and
`FilterIndex` is not writing a refactor — they are writing a catalog migration. For P5 it means today's NFD
mode must not be touched even indirectly.

**A pattern that gives it a name.** Verification over the Elasticsearch checkout (branch `main`,
`9a100e2d0e41`, 2026-08-13) shows that this concept has its own naming in an established engine. A field of
type `keyword`, i.e. an unanalyzed field where the whole value is one term, has no analyzer — but it does
have a **normalizer** (`KeywordFieldMapper.java:291`): a chain of char filters and token filters that **must
not contain a tokenizer** and always produces exactly one token. It serves precisely to make an unanalyzed
field tolerate letter case or diacritics without falling apart into words.

What matters, though, is a detail a few lines away: in `KeywordFieldMapper.java:512` the same normalizer is
used **simultaneously as the search and as the phrase analyzer**. Normalizing the query and normalizing the
value are by definition the same code, and it is the only way of guaranteeing that both sides meet. Today's
`attributeContains` is our normalizer in exactly that sense — NFD over the stored key as well as over the
sought expression, plus a third, independently written copy of the same consideration in the prefetch branch
(`createCanonicalPredicate`).

Two things follow for P5 that must not be confused. The new fulltext path is to **share normalization of the
query and of the value as one implementation** from the start: the registry emits the index and the query
analyzer from the same recipe (§4.4), so it happens by itself unless somebody splits it — and this pattern
says why it must not be split. Today's NFD path, by contrast, is **not** to be unified, even though it is
written three times: per the paragraph above it is an on-disk format and unification is not a refactor but a
catalog migration. The pattern therefore applies forwards, to what P5 builds, not backwards to what it
inherited. P5's criterion "`attributeContains` unchanged in behaviour" thereby gets a concrete shape: share
the implementation inside the new path, and do not touch the old path at all.

### 7.2 A trap: NFD silently switches the Czech stemmer off

This is the most important technical finding of the whole of P5 and it has to be written down before
somebody writes the first line of code.

`CzechStemmer` has an explicit precondition in the documentation of its `stem` method: "Input is expected to
be in lowercase, but with diacritical marks"
(`lucene/analysis/common/src/java/org/apache/lucene/analysis/cz/CzechStemmer.java:35`). And it is not a
pious wish — the whole implementation compares against **precomposed** characters. The method `removeCase`
tests endings such as `"ěte"` (`:61`) and switches on the individual characters `'á'` (`:103`) and `'ě'`
(`:107`), each of which is a single `char`.

Were the input NFD text, `'á'` would not be in it at all — instead an `'a'` followed by a combining acute
U+0301. None of those conditions would ever match. **The stemmer would not break, would not throw an
exception and nobody would notice** — it would merely stop stemming all words with diacritics, i.e. in Czech
by far the majority. The word `Česká` would emit a decomposed and unhandled form instead of `česk` and the
match with `české` would disappear.

A qualitative degradation a green test suite does not catch is exactly the class of error against which the
research sets smoke tests. The concrete consequences for P5:

1. **The analyzer is fed NFC, not NFD.** The text has to pass at the analyzer's boundary through
   `Normalizer.normalize(text, Normalizer.Form.NFC)`. It is one line and it has to have a comment saying
   why — otherwise somebody removes it during a code cleanup as superfluous, an operation after which all
   the tests still pass.
2. **There is no shared normalization between the paths.** Fulltext wants NFC, `FilterIndex` stores NFD.
   Whoever wanted to "unify normalization in one place" breaks one of the two paths.
3. **The smoke test has to contain a word with diacritics.** A test over pure ASCII would never uncover this
   trap. The pair `Česká` → `česk` from upstream (§5.1) is precisely that guard.

### 7.3 Diacritics versus a typo (O3)

Open question O3 of the research asks for attention to "the interaction of diacritics and a typo". With the
analyzers' verified behaviour it can be formulated concretely.

If we **keep** the diacritics (which `CzechAnalyzer` does), then "cerna" against "černá" is two characters
away in the Levenshtein metric — `e` against `ě` and `a` against `á`. That is the whole budget
`LevenshteinAutomata` can even do (`MAXIMUM_SUPPORTED_DISTANCE = 2`), so a user who does not type háčky
spends their typo tolerance on diacritics and has nothing left for a real typo. A longer word with three
diacritic characters will not be found at all.

If we **fold** the diacritics (by adding an `ASCIIFoldingFilter` into the pipeline,
`lucene/analysis/common/.../analysis/miscellaneous/ASCIIFoldingFilter.java`), the pair "cerna" and "černá"
falls to distance zero and the whole typo budget stays for typos. The cost is a loss of distinction where
diacritics carry meaning.

What is key is **where** the folding is done, because the term dictionary and the distance metric have to be
in the same space:

- Folding **inside the analyzer, before the stemmer** is wrong — the Czech stemmer would receive text
  without diacritics and fall into the same silent degradation as in §7.2.
- Folding **inside the analyzer, after the stemmer** is consistent: the dictionary holds folded stems, the
  distance is measured over folded stems, the query goes through the same chain. Diacritics are then lost
  entirely and cannot be used even as a signal for ranking.
- Folding as a **separate lane** — the dictionary holds forms with diacritics, the folded form serves as a
  second key — preserves the distinction and allows an exact match with diacritics to be rated above a
  folded one. It is variant 3 of §6 in a different coat and shares its cost.

**A link to §6 that P1 would otherwise break.** The recommendation of §6 (variant 3: the dictionary holds
the surface form as well as the stem) and the recommendation to fold diacritics hold together only if **the
surface form is captured before the folding**. Were it folded too, the distinction between "černá" and
"cerna" would disappear entirely — and with it the signal the exactness lane of the default rank profile
reads from (§4.3 of the research, "exact > prefix > fuzzy"). The order inside the chain therefore is:
tokenization, lowercasing, capturing the surface form, stopwords, stemming, folding diacritics.

**Recommendation: P5 leaves `ASCIIFoldingFilter` after the stemmer as an optional pipeline step, on by
default for Czech and Slovak.** Typing without háčky is the norm in a Czech e-shop, not a fringe, and the
typo budget is too expensive to be spent by the keyboard. German is not to have it, because
`GermanNormalizationFilter` already handles umlauts its own way and double folding would clash. The exact
typo tolerance thresholds stay open (O3) and P3 will decide them — P5 only ensures they are measured in the
same space the dictionary is in.

---

## 8. Determinism and versioning

The analysis chain is part of the definition of the index's content: for the same attribute value it has to
emit the same terms until the catalog is reindexed. Two things follow.

**The Lucene version freezes at a specific patch version** and it will be a value in the root pom's
`<properties>` beside the others (`kryo.version`, `roaringbitmap.version`). A minor version upgrade of
Lucene may change stopwords or the stemmer's behaviour, which changes the term dictionary's content — which
is exactly the reason the research (§3) says freezing is acceptable for analyzers for years. Automatic bumps
by Dependabot on this dependency are therefore undesirable and have to be excluded.

**A change of tokenization is a trigger for reindexing.** The mechanism by which that is recognized and
performed belongs in the schema and a parallel document addresses it; P5 only names the requirement. The
practical shape is to version the analyzer's *recipe*, not the library's version — a reindex is to be
triggered when the recipe for a specific `(collection, locale)` combination changes, not every time Lucene
is bumped.

---

## 9. The realization procedure

The steps are ordered so that each can be verified independently and so that the first three add not a line
of production code to the engine that could be broken.

1. **Introduce the dependency.** Add `lucene.version` into the root `pom.xml`'s `<properties>`,
   `lucene-analysis-common` and `lucene-analysis-stempel` into `<dependencyManagement>`, both as a
   `<dependency>` into `evita_engine/pom.xml` and the corresponding `requires` into
   `evita_engine/src/main/java/module-info.java`. Verification: `mvn -pl evita_engine compile` passes and
   `mvn dependency:tree` on `evita_java_driver` does not contain `org.apache.lucene`.
2. **Exclude the dependency from automatic bumps** per §8.
3. **Write the tokenization contract** (§4.2) — the term record and the method emitting it — and a unit test
   with it verifying the `reset` / `incrementToken` / `end` / `close` protocol on one fixed string.
4. **Build the registry** (§4.1) with the built-in language table, lazy instance creation, a fallback for an
   unknown language and with two paths — index and query (§4.4).
5. **Add NFC normalization at the analyzer's boundary** (§7.2), with a comment explaining why, and with a
   test directly comparing the output over the NFC and NFD forms of the same word. That test is the only
   insurance against somebody removing the line.
6. **Add the languages** per §5, including the Slovak variant C and the optional folding of diacritics per
   §7.3, and with it **the catalog of optional filters** per §4.6 (protecting exempted expressions from
   stemming and splitting a token into a word and a numeric part).
7. **Write the smoke quality tests** per §10, including a set over real attribute values and the parity of
   diacritics folding against the existing solution (§10.4).
8. **Verify neutrality towards `attributeContains`** by running the existing tests (§10).

Steps 1 to 5 form a usable whole; 6 and 7 are incremental and can be done in parallel.

---

## 10. The harness and tests

### 10.1 A neutrality test: `attributeContains` must not move

Because P5 touches neither `FilterIndex` nor the constraint translator, this test is a regression insurance,
not new evidence. It suffices to run the existing suite; there is no point in writing new tests here. The
relevant files, found by searching the test module:

| File | What it covers |
|---|---|
| `AttributeStringSearchUnicodeNormalizationFunctionalTest` | NFC/NFD on both paths |
| `AbstractEntityByAttributeFilteringFunctionalTest` | the functional behaviour of filters over attributes |
| `AttributeBitmapFilterTest` | the prefetch branch and its predicates |
| `AttributeContainsTest` | the constraint's own contract |
| `EvitaQLFilterConstraintVisitorTest` | parsing `attributeContains` in EvitaQL |
| `QuerySerializationTest` | Kryo serialization of the constraint |

They all lie under `evita_test/evita_functional_tests/src/test/java/`. The first is the most valuable — it
is written precisely for the NFC/NFD contract and verifies that the index and prefetch paths give the same
result, including the branch with a collation comparator. It is run by the ordinary fast run
(`-P unitAndFunctional`, see `.claude/rules/testing.md`).

### 10.2 Smoke quality of stemming

The set of word pairs **is not to be invented**. Upstream Lucene has tests with concrete expectations for
every language and those are at the same time the best available specification of behaviour; adopting part
of them moreover gives detection that the behaviour changed between Lucene versions. Concrete candidates,
verified in the sources:

**Czech** (`lucene/analysis/common/src/test/.../analysis/cz/`):

| Input | Expected output | Source | What it guards |
|---|---|---|---|
| `Česká Republika` | `česk`, `republik` | `TestCzechAnalyzer.java:46` | **the NFC trap** of §7.2 |
| `Pokud mluvime o volnem` | `mluvim`, `voln` | `TestCzechAnalyzer.java:39` | stopwords |
| `pán`, `páni`, `pánové`, `pána` | all `pán` | `TestCzechStemmer.java:41-44` | case convergence |
| `muž`, `muži`, `muže` | all `muh` | `TestCzechStemmer.java:64-66` | the rewrite of `ž` into `h` |
| `hrad`, `hradu`, `hradech` | all `hrad` | `TestCzechStemmer.java:54-59` | the hard pattern |

The first row is the most important: it is the only pair in the table containing both a diacritic and a
capital letter, so it fails precisely when the analyzer is fed NFD or when `LowerCaseFilter` is lost from
the pipeline.

**English** (`TestEnglishAnalyzer.java:34-41`): `books` to `book`, `steven's` to `steven` and `the` to
nothing. The last two cover `EnglishPossessiveFilter` and stopwords.

**German** (`TestGermanAnalyzer.java:32-34,62-63`): `Tisch`, `Tische` and `Tischen` to `tisch`;
`Schaltflächen` and `Schaltflaechen` to `schaltflach` — the second pair is proof of the umlaut folding and a
contrast against Czech from §7.3.

**Polish** (`TestPolishAnalyzer.java:34-35`): `studenta` and `studenci` to `student`.

**Slovak** has no upstream expectations of its own and will not have. A test here therefore cannot be
regression; it has to be *decisional*. A set of roughly twenty to thirty Slovak nouns and adjectives in
several forms is assembled, run through both variant B (the Czech stemmer) and variant C (no stemmer) and
the result assessed by hand: how many forms converge correctly and how many unrelated words got glued
together by mistake. That second metric decides — false convergence is worse in an e-shop than insufficient
convergence.

### 10.3 Real attribute values

Smoke tests over artificial words will not show how the analyzer behaves on real content. The harness
therefore needs a sample of real values: product names and short descriptions from a production e-commerce
catalog (the same dataset P1 will then use) and a sample of long texts for the CMS profile (Z8).

The output is not an assert but **material for a manual review**: for every value the terms it decomposed
into are printed, and a human looks at whether the tokenization did something unexpected. Typical findings
this step uncovers and a unit test does not: dimensions like "150×70 cm", codes with hyphens and slashes,
units after numbers, brands written with a combination of capital letters and digits. For every such case a
decision then arises about whether it belongs in the pipeline's own rules — and that decision is a value P5
delivers to P1. The last two cases named are at the same time exactly the ones the existing client answers
with the filters of §4.6, so it is immediately visible with them whether the filter catalog is sufficient.

### 10.4 Parity of diacritics folding against the existing `DiacriticFilter`

§4.6 rejects adopting the old client's manual conversion table as a component, but does not reject the
commitment to know where exactly the two paths diverge. This test is therefore **decisional, not
regression** — the same as the Slovak set in §10.2 and for the same reason: there is no expected output,
there is only the question of whether the difference is acceptable.

A set of characters and words is assembled where the manual table and `ASCIIFoldingFilter` may diverge.
Three groups, each for a different reason: **multi-character conversions** (`ß`, `æ` and ligatures), where
the manual table replaces one character with two; **characters outside the Czech and Slovak alphabets** that
really occur in catalogs (the Nordic `ø` and `å`, German umlauts, the Polish `ł`); and a control sample of
ordinary Czech words, where both paths **are to** agree and a difference would mean an error in the wiring,
not in the table.

The output is not an assert but **a list of differences**, and it has two consumers. The first is the
decision of whether any difference is serious enough for the pipeline to be supplemented with a targeted
step — §4.6 names as the only real case `ß → ss`, which German, though, handles with
`GermanNormalizationFilter`, so it would be a duplication. The second is operations: migrating an existing
website from the manual table to our path **changes search results over the same data**, and this list is
the only material from which it can be said in advance by what and by how much. Who informs the operator and
when is open question P5-6 (§11).

---

## 11. Open questions

The numbered ones follow on from the research; the new ones carry the P5 designation.

- **O3 (a refinement, §7.3)** — the exact typo tolerance thresholds stay with P3. P5 adds a concrete input
  to them: if the diacritics are folded after the stemmer, the distance is measured over folded stems and the
  thresholds adopted from Algolia and Meilisearch are computed from the stem's length, not from the length of
  what the user typed.
- **P5-1 — stems versus surface forms in the dictionary (§6).** Three variants with different costs on
  memory and different behaviour of the prefix, the typo and the suggester. P1 will decide by the
  dictionary's size and P3 by the suggester's quality. The leaning: variant 3 for e-commerce, variant 1 for
  CMS.
- **P5-2 — the origin and licence of the Slovak Hunspell dictionary (§5.3).** The licence of the specific
  dictionary has to be verified, not estimated. Should it turn out incompatible with the distribution, the
  solution is loading from a configured path instead of packaging into the jar. Until then variant C holds.
- **P5-3 — when to move to the Lucene 10.x line (§3.1).** Tied to `<java.version>` in the root pom really
  being 21. The transition is mechanical, but it should be done consciously, because it is at the same time
  an opportunity to reconsider the frozen version. The material still missing: how long the 9.x line will
  receive fixes — verify at the source, do not estimate.
- **P5-4 — the granularity of the override from the schema (§4.5).** Is the registry's key really
  `(collection, locale)`, or will a need for a per-attribute override show? A real case stands behind it: a
  product's name and a long description in the same collection and the same language may want a different
  recipe, because one is a short structured field and the other continuous text. The parallel document about
  the schema addresses it.
- **P5-5 — the registry's behaviour on an unknown language (§4.1).** The recommendation is a generic
  analyzer plus a log message, but it is a product decision: the alternative is to reject the query. Name it
  in the documentation before somebody discovers it in production.
- **P5-6 — who informs about the change of results on migrating from the existing `DiacriticFilter`, and
  when (§4.6, §10.4).** The old client's manual conversion table is equivalent neither to NFD decomposition
  nor to `ASCIIFoldingFilter`; the differences are small but they exist, and they manifest as **different
  search results over unchanged data**. The list of differences will be produced by the test of §10.4. What
  to do with it — whether the differences are levelled with a targeted pipeline step, or merely announced as
  a behaviour change on migration — is a product decision, not a technical one, and it has to fall before
  the first existing website is switched over. Not according to complaints, because at that moment the way
  back is a reindex.
