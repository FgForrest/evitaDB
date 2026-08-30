---
title: Share schema-derived attribute keys and resolve reference schemas once per run instead of per mutation
date: 2026-08-05
updated: 2026-08-24 10:15
status: accepted
kind: optimization
issues: [1390]
prs: [1395]
areas: [evita_api/requestResponse/schema/dto, evita_api/requestResponse/data, evita_engine/index/mutation]
supersedes: []
superseded-by: []
relates: [2026-07-27-write-path-performance-tuning, 2026-08-01-bplustree-cursor-free-insert-path, 2026-08-10-stored-value-normalization-split]
---

# Share schema-derived attribute keys and resolve reference schemas once per run instead of per mutation

The production-catalog WARM_UP profile of `2026.2.2` attributes 18.7% of write-path CPU to resolving facts from
immutable schema objects and 18.24% of write-path allocation to `AttributeKey` instances. Both are
volume, not body cost: the accessors are already plain map lookups, and the keys are re-derived from
a schema that cannot change while the mutation is being applied. This record covers the resulting
work — allocation-free "or throw" accessors, canonical `AttributeKey` instances owned by
`AttributeSchema`, an allocation-free `AttributeKey.compareTo`, and per-run hoisting of reference
schema resolution in the mutation executors — and, more importantly, the two adjacent options that
were deliberately **not** taken.

## Why

Bulk ingest applies millions of local mutations against one immutable entity schema. Two families of
cost fall out of that:

- **Resolution volume.** `EntitySchema.getReference` alone is 15.36% of write-path CPU, with
  `ReferenceSchema.getIndexedComponents` (1.91%) and `isFacetedInScope` (1.43%) behind it. An audit
  of the callee bodies found them already near-minimal — `isFacetedInScope` is a `contains` on an
  `EnumSet`-backed set, `getIndexedComponents` a plain map `get`. The share is therefore how often
  they are called, and the one genuinely fat accessor was `getReferenceOrThrowException`, which
  allocated two `Optional`s and a capturing lambda per call to express "get or throw".
- **Key churn.** Schema-derived `AttributeKey` records are built per attribute per reference per
  entity purely to be compared or looked up, then discarded. The domain is tiny and
  schema-bounded — one key per (attribute, locale) pair — while the allocation count scales with the
  number of entities ingested.

The constraint that makes this non-obvious is that the schema is **not** pinned for the duration of
an entity's mutation batch. `ContainerizedLocalMutationExecutor` reads it through
`this.schemaAccessor.get()`, a supplier re-read at every use site, because schema evolution can add
a reference or an attribute part-way through applying a batch. Any caching strategy has to survive
that.

### Previous state

`getReferenceOrThrowException` was `getReference(name).map(cast).orElseThrow(() -> new
ReferenceNotFoundException(name, this))` — three allocations, none hoistable, since the lambda
captures both `name` and `this`. `AttributeKey.compareTo` delegated to
`ComparatorUtils.compareLocale(..., () -> ...)`, allocating a capturing `IntSupplier` on every
comparison inside every sorted structure keyed by an attribute key. The two largest per-reference
loops (`verifyReferenceCardinalities`, `ReferenceIndexMutator`'s facet-propagation loop) already
carried a same-name-run memo; several sibling loops did not.

## Options considered

### Option A — accessor-level de-allocation, schema-owned canonical keys, per-run hoisting (chosen)

Remove the allocation from the accessors themselves; let `AttributeSchema` own the canonical
`AttributeKey` instances so callers stop building them; and hoist reference-schema resolution into a
local wherever a loop is visible in the same method body and the entity schema is already pinned
there.

- **Pros:** every change is local and independently revertible; nothing crosses a module boundary;
  no on-disk format is touched; the caches live on objects that are already immutable and already
  outlive every use.
- **Cons:** spread over many small sites, so the win is diffuse and hard to attribute per edit;
  adds two fields to `AttributeSchema` and one to `ReferenceSchema`.

### Option B — canonicalize reference names at the mutation boundary (declined)

Reference names arrive protobuf-decoded, a fresh `String` per gRPC message, so `HashMap.getNode`'s
identity fast path always misses and `String.hashCode` is recomputed per message — visible as the
21.36% top leaf frame. Routing every name through a canonical string table where the mutation is
built would make every name-keyed lookup in the write path cheap at once, not just this accessor.

- **Pros:** strictly higher leverage than anything in Option A — it fixes `getReference`, the `hppc`
  maps in the cardinality verifier and the reference indexes together.
- **Cons:** there are two viable placements, they pull in opposite directions, and neither has been
  measured. *At the converter* — a module-private `ConcurrentHashMap<String, String>` filled with
  `computeIfAbsent(raw, Function.identity())` — catches the name where it is created and works on
  both sides of the wire, but the gRPC converters have no schema or catalog context and live in the
  module shared with the client, so they cannot reject an unknown name in the same step. *At first
  schema contact* server-side, `references.keySet()` **is** in hand and validation comes free, but
  the raw string survives longer and the blast radius is larger.
- **`String.intern()` is not a third placement.** It is a JVM-wide native table with its own
  contention and GC behaviour, carrying that cost for a domain of a few dozen reference names per
  catalog. That structural argument is the recorded reason and it needs no measurement. An
  intern-based attempt was *reported* as tried and declined before this work, and it left no
  artifact in the places that were checked: no `.intern()` call is reachable from any ref in this
  repository (`git log --all -S'.intern()' -- '*.java'` is empty), and neither #1390 nor #1395
  mentions one. That is absence where it was looked for, **not** proof the trial
  did not happen — an experiment run in a scratch worktree, like the since-removed
  `warmup-bench`, leaves nothing behind at all. Treat the report as corroboration of the
  structural argument, and if this is revisited, reach for a bounded application-owned table rather
  than the JVM's.
- **The exploration #1390 defers to is not recoverable.** That issue points at
  `specifications/write-path-optimizations/exploration-name-canonicalization.md`; no such file was
  ever tracked — the folder's only committed file was its `README.md`, retired once the shipped
  items landed. Since `/specifications/` is git-ignored, it may have been written on disk and lost
  rather than never written; either way nothing of it survives, which is why the ceiling below is
  still unmeasured.
- **Rejected because:** it needs a design exploration and a measured ceiling before anyone writes
  code — where the table lives decides whether it is a converter-local detail or a change to the
  mutation contract, and neither placement has been measured. Explicitly out of scope in #1390.
  **Revisit** once a profile puts a ceiling on it — the two candidate placements are named above,
  and the starting point is a fresh measurement rather than a document.

### Option C — memoize the resolved reference schema on the executor across mutations (declined)

A one-element `(name, schema)` memo held as a field on `ContainerizedLocalMutationExecutor` would
serve nearly every resolution from a field read, since mutations arrive in name-sorted runs.

- **Pros:** removes call volume from all 36 sites at once, including the dispatch methods where no
  loop is visible and a local cannot help.
- **Rejected because:** `schemaAccessor` is a supplier that is deliberately re-read per use, so a
  memo living longer than one method call can serve a schema version that a schema mutation applied
  in the same batch has already replaced. The correctness cost is a silently stale schema, which is
  exactly the class of bug that does not show up in tests. **Revisit** only if the executor ever
  gains an explicit per-batch schema pin with an invalidation hook.

### Option D — delete `ComparableReferenceKey` (declined)

A one-field record wrapping `ReferenceKey` whose whole body is a `compareTo` delegating to
`ReferenceKey.FULL_COMPARATOR`, allocated per lookup at sites like
`ReferenceAttributeValueProvider` — 12.34% of write-path allocation spent on a
`Comparable`-vs-`Comparator` API choice.

- **Rejected because:** it is functionally load-bearing for the non-uniform internal-primary-key
  mechanism and inseparable from the `ReferenceKey` equality redesign tracked in #1392 — whose
  `equals` is conditional and therefore non-transitive. Removing the wrapper without settling that
  first would silently move which side of the non-transitivity a mixed run lands on. **Revisit**
  after #1392 lands.

## Decision

**Chosen: Option A**, with B, C and D left standing as recorded rejections. A is the part that is
safe to land as a hotfix on a release branch: it changes no on-disk format, no query semantics and
no public contract beyond two additive `default` methods, and every edit is defensible on its own.
B remains the larger prize and should be sequenced after its exploration produces a number.

## Key technical details

- **`AttributeKey` instances handed out by a schema are shared.** `AttributeSchema` now owns a
  canonical locale-agnostic key plus a lazily-filled `ConcurrentHashMap<Locale, AttributeKey>`
  (`AttributeSchema.getAttributeKey(...)`). All engine usage is equality-based, so this is
  behaviour-neutral — **but a future change must not introduce identity-keyed structures over
  attribute keys, nor treat a key's identity as a per-mutation marker.** Both cache fields are
  `@EqualsAndHashCode.Exclude`; without that, the lazily-populated map would make two otherwise
  equal schemas compare unequal depending on which locales had been touched.
- **Kryo is unaffected.** Schema DTOs are written by hand-written serializers that enumerate fields
  explicitly and are versioned by `serialVersionUID` through `SerialVersionBasedSerializer`, so
  derived cache fields are format-neutral and **no `serialVersionUID` bump is warranted**.
  Reference tracking is off (`KryoFactory:120`, `setReferences(false)`), so sharing one key instance
  where distinct-but-equal instances used to appear does not change the serialized graph of the
  WAL-bound mutations built at `ReferenceBlock` and `ContainerizedLocalMutationExecutor`.
- **The run memo depends on the sortedness invariant, and carries the derived facts too.**
  `lastResolvedReferenceSchema` in `EntityIndexLocalMutationExecutor.indexAllReferences` /
  `unindexReferences` and in `propagateOrphanedReferenceAttributeMutations` is only correct because
  references are stored and iterated in name-sorted runs — the same invariant `ReferencesStoragePart`
  asserts and re-establishes after every modification. A change that stops sorting references breaks
  these memos silently. The two index-side loops also carry `indexedForFiltering` /
  `indexedForEntityComponent` / `indexedForGroupComponent` / `faceted` on the same boundary; that is
  where the `getIndexedComponents` and `isFacetedInScope` frames actually go. **Each derived boolean
  keeps the guard that used to gate its evaluation** (`indexedForEntityComponent` is computed only
  when `indexedForFiltering`, `faceted` only when `indexedForEntityComponent`), because
  `ReflectedReferenceSchema.isFacetedInScope` asserts on reflected-reference availability and must
  not start firing where the old control flow never called it.
- **`updateReferences` now resolves the reference schema eagerly** on every branch instead of up to
  three times lazily. A reference whose name is unknown to the entity schema cannot be stored in the
  first place (`verifyReferenceCardinalities` resolves every stored reference), so this only moves an
  unreachable failure earlier — it is deliberate, not an oversight.
- **`ReferenceSchema.getIndexedInScopes()` now returns an unmodifiable `EnumSet`**, precomputed in
  the constructor, where it previously returned a fresh mutable `HashSet` per call. Iteration order
  is consequently ordinal rather than hash order, and the result is no longer mutable. No caller
  mutated it; the audit found the accessor is **not** reached from the write path at all (its only
  non-test callers are `ClassSchemaAnalyzer` and schema-mutation code), so this is a tidy-up rather
  than a measured win — recorded here so the next reader does not repeat the audit.

## Verification

- `AttributeKeyTest` (new, `evita_api/requestResponse/data`) pins the full ordering contract of
  `AttributeKey.compareTo` — all four null/non-null locale combinations, locale-string ordering,
  the tie-break onto the attribute name, and an exhaustive cross-check of every
  (locale, name) pair against `ComparatorUtils.compareLocale`. It was written and run green
  **against the old implementation first**, so it pins the historical semantics rather than the
  rewrite's.
- Full `evita_functional_tests` suite: see the PR run.
- **No performance number is claimed here.** The production-catalog WARM_UP re-run named in #1390's
  verification section has not been executed for this change; the percentages quoted above are the
  measured cost of the *previous* code, from the profile that motivated the issue. The expected wins
  are argued from reading each site.

## Consequences & open follow-ups

- **Two `AttributeKey` allocation sites were left as-is** —
  `ReferenceIndexMutator.readReferenceAttributeValue` (`:1797`) and
  `ContainerizedLocalMutationExecutor.readReferencedEntityAttribute` (`:1280`). Both are keyed by a
  bare attribute *name* coming from a histogram descriptor that points at a **referenced entity's**
  attribute, so the owning schema is not in hand and may live in another collection. Threading one
  in would widen public signatures for a site that is not in the profile's hot frames.
- **The two `*AttributeAndCompoundSchemaProvider` classes still resolve through the `Optional`
  accessors.** Their fields are contract-typed (`EntitySchemaContract` /
  `ReferenceSchemaContract`); narrowing them to the DTOs to reach `getAttributeOrNull` would require
  widening roughly ten `ReferenceSchemaContract` parameters through `ReferenceIndexMutator`. Worth
  revisiting only together with a broader decision about DTO-vs-contract typing inside the engine.
- **`getAttributeOrNull` exists on `EntitySchema` only.** `ReferenceSchema` was deliberately left
  without one: `ReflectedReferenceSchema` overrides `getAttribute` with an availability assert, so a
  null-returning sibling would need a matching override to avoid bypassing it — more surface than
  the single DTO-typed call site justified.
- **The exploration folder that motivated this record has been retired.** Its §1 *Problem A* is
  implemented here; §2/§3 (the `verifyReferenceCardinalities` rewrite, which subsumes the
  `ObjectIntHashMap` size hint by deleting the map) shipped as the sibling commit in this same
  branch, issue #1391, whose commit message carries the run-length scan's reasoning. Only §1
  *Problem B* was never written — it is Option B above, and that is now the only surviving record of
  it. The `ComparableReferenceKey` half of its §4 is Option D, still gated on #1392.

## Related work

- `2026-07-27-write-path-performance-tuning` — same write path, same profile lineage; that record
  spent the collation-cache and trunk-merge levers, this one spends the schema-handling lever.
- `2026-08-01-bplustree-cursor-free-insert-path` — the other allocation-removal record from the same
  round of production-catalog profiling, on the index side rather than the schema side.
- `2026-08-10-stored-value-normalization-split` — same attribute-mutation write path; that record
  constrains what a mutation may do to the value itself, where this one speeds up how the schema
  around it is resolved.

## Timeline

- **2026-08-05** — issue #1390 filed from the production-catalog WARM_UP profile of `2026.2.2`
- **2026-08-05** — implemented on `1390-schema-handling-write-path-optimizations`
