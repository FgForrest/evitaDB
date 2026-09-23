---
title: Decode only the reference names a projection asks for, rather than deriving reference keys from the indexes
date: 2026-09-11
updated: 2026-09-23 18:40
status: accepted
kind: optimization
issues: [1547, 1554]
prs: [1548]
areas: [evita_store/evita_store_entity, evita_store/evita_store_server, evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence, evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/predicate]
supersedes: []
superseded-by: []
relates: [2026-08-05-schema-handling-write-path-optimizations, 2026-09-12-committed-snapshot-provenance-for-enrichment, 2026-09-13-per-entity-io-statistics-attribution, 2026-09-15-bidirectional-reference-counterpart-rewrite, 2026-09-23-reference-decode-narrowing-by-referenced-key]
---

# Decode only the reference names a projection asks for

`ReferencesStoragePart` is read as a unit: one Kryo record holding every reference an entity has.
The read path now carries the projection's reference-name set into the deserializer through a
thread-bound `ReferenceDecodeCoverageContext`, so a reference whose name the caller could never see is
stepped over in the stream instead of being materialized. No persisted byte changed.

## Why

A reflected reference makes a shared, low-cardinality entity the owner of one back-reference per
entity that points at it — and those back-references live in the same all-or-nothing record as the
one or two references a projection actually wants.

On a large e-commerce catalogue, a single product-detail query returning a **6.9 kB** response read
**15** reference storage parts holding **93 313** `Reference` objects:

| entity | parts | references | share |
|---|---|---|---|
| Product | 3 | 145 | 0.2 % |
| **ParameterValue** | 6 | **85 426** | **91.5 %** |
| Parameter | 6 | 7 742 | 8.3 % |

Roughly **150** of those 93 313 are ever read. The worst single record holds **72 218** references,
of which 72 217 are back-references the query never looks at. The Parameter records decode 7 742
references and return **none**.

### Previous state

Two facts made this the default behaviour rather than an edge case.

`ReferencesStoragePart`'s own JavaDoc stated the assumption that broke: *"all references including
all their attributes are stored in single storage container because the data are expected to be
small."* At this scale it is off by three orders of magnitude.

And the load decision was a boolean, not a content check:
`DefaultEntityCollectionPersistenceService.fetchReferences` gated purely on
`ReferenceContractSerializablePredicate.isRequiresEntityReferences()` — *"TRUE if requirement
`ReferenceContent` is present in the query"*. **Any** reference requirement therefore decoded
**every** reference. Verified directly: removing `attributes { orderInParameter }` from the
projection produced a byte-identical decode trace and no measurable latency change.

The predicate already held what was needed. `referenceSet` is a `Map<String, AttributeRequest>` keyed
by reference name, and `test(ReferenceContract)` already hid every reference outside it. It decided
what the caller may **see** and was never consulted about what to **read**.

## Options considered

### Option A — carry the projection's reference names into the deserializer (chosen)

Bind the predicate's visible reference-name set to the thread for the duration of the read.
`ReferenceSerializer.read` decodes the name, tests it against the set, and either builds the
`Reference` or advances the stream past its body.

- **Pros:** no storage-format change and no migration, because Kryo decoding is *sequential* —
  skipping needs no length index. The filter is derived from the very predicate that already hides
  those references, so a skipped name is one the caller could not have observed. Correctness follows
  from an existing invariant rather than a new one.
- **Cons:** the walk is still `O(total references)` — the engine stops *materializing* the unwanted
  references but still steps over them, so the cliff is flattened, not removed.

### Option B — derive the referenced primary keys from the indexes and never read the record (declined)

Take referenced and referenced-group primary keys from `ReferenceTypeCardinalityIndex.cardinalities`,
a `LongPayloadBucketTree` whose per-tuple keys are `-pack(indexPrimaryKey, referencedEntityPrimaryKey)`.
Since `NumberUtils.pack` is monotone in its low int, one entity's tuples would occupy a contiguous key
range and a range scan would cost `O(log n + k)`.

- **Pros:** would be `O(kept)` outright, with no decode at all.
- **Cons:** rests on a false premise.
- **Rejected because:** `indexPrimaryKey` is **not the owner entity's primary key** — it is the
  identity of an *index instance*, allocated from `EntityCollection.indexPkSequence`
  (`ReferenceIndexMutator.referenceInsertPerComponent:765-767`). A range scan over those keys answers
  *"which referenced entities does index #4711 hold"*, not *"which entities does owner X reference"*.
  Nothing in the engine answers the latter: the mapping is stored the other way round throughout
  (`ReducedEntityIndex.entityIds` holds owner PKs per referenced entity). Reconstructing the forward
  direction means probing every reduced index of the reference — `O(#distinct referenced entities)`
  per owner — which replaces one cliff with another. **Revisit if** a persisted reverse index is
  wanted for other reasons; building one only for this would add heap proportional to the whole
  reference graph, a second write path to maintain, and would turn index/storage divergence into a
  user-visible wrong answer.

### Option C — random access within the record (deferred, not rejected)

Seek straight to a name's run using the sorted order.

- **Pros:** the only option that makes the read `O(kept)` rather than `O(total)`.
- **Rejected because:** it needs a persisted-format change and a migration, and Option A delivers
  most of the win without one. Tracked as #1554, which supersedes this trade-off with a layout that
  splits the record by reference name.

## Decision

**Chosen: Option A.** It was the only option that could ship against the current on-disk format, and
the measurement says it was enough: the query halved. An earlier version of this plan asserted that a
predicate-aware decoder *"requires a storage-format change and migration"* — that was wrong, and
noticing it is what made the cheap option available. Only *random access* needs a format change;
*skipping* does not.

**There is deliberately no kill switch.** An off position that restores a known-slower read is a
standing invitation to flip it during an unrelated incident, and every consumer reasoning about part
completeness would have to carry the ambiguity forever. Unrestricted reads are expressed by binding a
`null` filter, which is also what a caller that cannot enumerate its reference names must pass — every
read states its own requirement.

## Key technical details

- **Entry point:** `ReferenceDecodeCoverageContext.executeWithCoverage(ReferenceDecodeCoverage, Supplier)` binds
  the filter; `DefaultEntityCollectionPersistenceService.fetchReferences` is the only production
  caller, and it passes `ReferenceContractSerializablePredicate.getDecodeCoverage()`.
- **Named vs unnamed reference content is the trap.** `EvitaRequest.getReferenceEntityFetch()` routes
  *named* requirements — `referenceContent(<instanceName>, '<referenceName>', …)`, which GraphQL and
  REST **always** emit — into a separate `namedEntityFetchRequirements` map and returns only the
  unnamed ones. The first implementation read `referenceSet` alone, so `getVisibleReferenceNames()`
  came back empty for every externally issued query and the whole mechanism was inert while its unit
  tests stayed green. `namedReferenceNames` exists to close that gap; anything else deriving a
  requirement set from a request must account for both maps.
- **A narrowed part is not the entity's reference set.** `ReferencesStoragePart#getDecodeCoverage()`
  records what was decoded, and every operation that reasons about the *absence* of a reference —
  emptiness, locale presence, internal primary key assignment, any modification — is guarded by
  `assertComplete` / `assertReferenceNameDecoded`. The write path must never receive one; the
  serializer's `write` refuses it.
- **Re-fetch is decided on decode coverage, not on a boolean.** `shouldFetchReferences` compares the
  coverage the previous read brought in against the one the new predicate asks for, through
  `ReferenceDecodeCoverage#covers` - which spans both the name axis and the referenced-key axis.
  "References were fetched before" no longer implies "all references are present", and answering an
  enrichment from a narrowed part would report the entity as having no such reference — a plausible
  wrong answer rather than a failure.
- **`toBinaryEntity` is deliberately not narrowed.** Its container is re-serialized verbatim into the
  binary entity handed to the client, so it must carry everything.
- **Resolve the schema *after* the skip decision.** `EntitySchemaContext.getEntitySchema()` builds
  three `Optional`s per call. Hoisted above the filter it ran for every reference the narrowing exists
  to avoid touching — 7.5 % of the query's entire allocation, spent on objects immediately discarded.

## Verification

- `ReferencesStoragePartSerializerTest` pins the mechanism: a filtered decode yields only the
  projected names, the skip stays stream-synchronised, and a narrowed part refuses to be written back.
- `ReferenceNarrowingDecodeBenchmark` (JMH, `io.evitadb.spike`) prices the decode in isolation;
  results in `documentation/performance/individual/ReferenceNarrowingDecodeBenchmark`.
- End-to-end A/B on a restored production catalogue, byte-identical responses:
  **min −32.9 %, p50 −44.7 %, p90 −45.8 %**.
- Full functional suite: 21 088 tests, the only failures being the known environment-dependent set
  (Docker-less S3 export plus three contention timeouts that pass in isolation).

## Consequences & open follow-ups

The shape of the remaining cost has **inverted**: the query no longer pays to materialize 93 313
references, it pays to walk past them. A warm `ctimer` profile at 158 req/s puts reference storage
part decode at **25.7 % of JVM CPU** (42.5 % of the evitaDB subtree) — the largest single engine cost
— split between decoding names to test them (7.8 %) and stepping over rejected bodies (11.9 %, of
which 5.0 % is attribute values decoded and then dropped). **40.8 % of everything the query allocates
is reference data it discards.** The `References` index build, previously a headline cost invisible
under the serializer's own frame, fell to 0.01 %.

Three follow-ups, ranked by measured cost:

- **Do not materialize the name of a reference that will be rejected** — 7.8 % of JVM CPU, 24 % of
  all allocation. **The mechanism first recorded here does not exist.** Kryo does not length-prefix
  these strings: `Output.writeString` (5.6.2, line 688) writes an ASCII string of 2 to 32 characters
  raw and unprefixed, terminating it by setting the high bit on its last byte - and a reference name is
  exactly that shape. Only an empty or single-character name, one longer than 32 characters, or a
  non-ASCII one takes the prefixed `writeVarIntFlag` branch. The idea survives in a different form:
  scan to the high-bit terminator to learn the length without allocating a `String` or its backing
  `byte[]`, then compare. That is a scan rather than a read, and because the encoding is mixed a fast
  path has to handle both branches. Costs a dependency on Kryo's string encoding, which must be pinned
  by a test that writes with Kryo and reads with the fast path.
- **Skip attribute values without decoding them** — 5.0 % of JVM CPU, 16.6 % of all allocation. Needs
  a length-aware skip in `AttributeValueSerializer`; same format-coupling argument. Note that skipped
  back-references are **not** attribute-free: a reflected reference inherits its source's attributes.
- **Split the record itself** — #1554. Both items above become unnecessary if the record stops being
  all-or-nothing, and only that removes the `O(total references)` term.

Unrelated but found by the same profile: `FacetGroupIndex.size()` sums a `HashMap`'s values through
an `IntStream` on every call — 2.1 % of JVM CPU.

## Related work

- #1554 — the follow-up that splits `ReferencesStoragePart` by reference name and pages large runs;
  it is the format change Option C needed and subsumes the two skip optimizations above.
- `2026-08-05-schema-handling-write-path-optimizations` — same shape of win on the write path
  (resolve a reference schema once per run rather than per mutation); `ReferenceSerializer` here
  stopped resolving it twice per reference for the same reason.

## Timeline

- **2026-09-09** — query shapes profiled against a restored production catalogue; the all-or-nothing
  reference blob identified as one of three read-path costs (#1547)
- **2026-09-10** — index-derived design proposed, then rejected on `indexPrimaryKey` semantics;
  narrowing implemented
- **2026-09-11** — named-reference-content gap found and closed, kill switch removed, re-profiled;
  follow-up #1554 opened
