---
title: Upgrade released catalogs from surviving parts, refusing collisions and warning about loss
date: 2026-10-29
updated: 2026-10-29 13:50
status: accepted
kind: fix
issues: [1696, 1699]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/attribute, evita_store/evita_store_server, evita_test/evita_functional_tests]
supersedes: []
superseded-by: []
relates: [2026-09-04-millisecond-temporal-precision, 2026-10-08-unique-indexes-normalize-like-filter-indexes, 2026-09-21-cardinality-counter-normalized-keys, 2026-10-20-single-owner-partitions-as-registry-rows, 2026-09-18-range-index-counting-kernel]
---

# Upgrade released catalogs from surviving index parts, refusing collisions and warning about irrecoverable loss

The 2026.2 → 2026.3 catalog upgrade rewrites index parts when their surviving values suffice for a lossless
conversion. It refuses provable collisions before publication, and emits one conditional warning for schemas
whose indexes may have lost information. It does not reconstruct indexes from entity bodies. These decisions
also apply to older catalogs carried through 2026.2 or reaching the same migration through an upgrade chain.

## Why

2026.3 changes temporal precision, open-range equality, localized text ordering and normalized unique keys.
A reader that immediately folds keys equal under the new rules can discard positional cardinalities before the
migration sees them. Conversely, merely decoding every value losslessly does not put a paged tree in its new
order. Some release indexes already discarded distinctions: surviving keys cannot identify the original value
of every owner. A successful load alone cannot promise query or mutation parity for such data.

The maintainer expects these unusual shapes to be absent from production data and requires the cheap answer:
no additional entity-content scan during upgrade. Correctness must be stated separately for repairable parts,
provable collisions, and information that the indexes cannot verify.

### Previous state

The 6 → 7 migration already re-keyed counters and unique indexes, rewrote owner sorts and converted single-owner
reference partitions. Release-written sort parts could fail while decoding cardinality blocks; paged unique and
filter trees could fail on their changed order after protocol 7 had been published. A repeated range key with
unchanged spelling could escape unique preflight. The old success-oriented proofs also required missing
milliseconds and folded spellings to reappear, which no index-only conversion can deliver.

## Options considered

### Option 2 — rewrite from stored parts, otherwise refuse or warn (chosen)

Use versioned readers to retain the released representation, then perform migration-local rewrites using the
necessary index root and its pages. Refuse contradictions that those parts prove; describe uncertainty where
only a schema-level warning is possible. This preserves the migration's IO boundary, with incomplete answers
explicitly identified to operators.

### Rebuild from entity bodies (declined)

A full rebuild could recover original owner assignments and missing index entries.
**Rejected because:** it adds a catalog-sized body read and rebuild machinery outside this migration's scope;
schema alteration with rebuild belongs to #409. Revisit with that machinery and an explicitly approved cost.

### Read every affected reference part to diagnose duplicate temporal representatives (declined)

Reading `ReferencesStoragePart` for every owner, including archived owners, could distinguish release-folded
representatives from duplicate rows now equal, and support exact refusals with both original values.
**Rejected because:** the maintainer requires that migration not get slower. A schema predicate supplies
conditional advice without a data-sized scan. The predicate includes unindexed references and archived owners.

### Narrow the loss warning by reading attribute, reference or price bodies (declined)

This could avoid warnings for harmless values in susceptible schemas.
**Rejected because:** the same migration-speed constraint rules out the scan. Looking only at surviving keys
cannot establish absence of loss: the missing spelling may have been folded under an ordinary-looking key.

### Refuse one owner's globally unique array twins (declined)

This treats every repeated current key uniformly.
**Rejected because:** identical complete global payloads identify the same locale, collection and owner. Folding
redundant index entries is lossless; entity content retains both original spellings. Bare owner IDs alone do not
establish that identity, and different payloads still refuse. Duplicate reference rows are different: folding
those would discard a row and its attributes.

### Rebuild decimal thresholds only when paged bucket order changes (declined)

This avoids rewriting already ascending and inline roots.
**Rejected because:** inherited 2026.1 values re-flushed by 2026.2 can have ascending keys and thresholds at the
wrong decimal scale. Both inline and paged inherited admission probes were red; native controls stayed green.
The 2026.2.18 jars were unavailable in the offline repositories, so this is synthetic storage-part evidence
modeling the released path, not a newly generated real-release fixture. Every protocol-6 decimal-range FILTER
companion is rebuilt at its persisted scale. A catalog-level native-origin guess cannot rule out inherited parts.

### Consolidate every aggregate record's range contributions (declined)

This initially looked like the natural repair for shared endpoints.
**Rejected because:** merging dev's #1826 exposed a difference from fresh `OwnerFilterIndex` writes. Scalar
contributions retain each distinct range, while array contributions consolidate their union per record. The
migration must reproduce that distinction, including touching borders; unconditional consolidation broke the
78-case fresh-write parity oracle. Replaying unrepaired endpoint bitmaps is also insufficient: they lost
multiplicity at shared borders. Rebuild from bucket memberships under the current counting semantics.

### Keep conversions in ordinary loading, or let post-publication loaders refuse (declined)

Both reduce eager migration work.
**Rejected because:** multi-page order and owner collisions must be resolved before current tree construction,
and an upgrade must not publish protocol 7 before discovering a provable refusal. Legacy compatibility stays in
versioned readers and the migration. Reader-local decoding retains representation; it does not invent owners.

### Refuse every temporal representative, merge colliding partitions, or pick a unique winner (declined)

These avoid distinguishing safe and unsafe cases.
**Rejected because:** a blanket refusal rejects healthy catalogs; partition merges span dependent indexes and
cannot choose between duplicate reference rows without losing data; last-wins uniqueness silently drops an owner.
Only complete-key collisions proven from manifests or unique entries are refused. Aggregate type-index record
IDs identify partitions, not unique owners, so the range-unique scan excludes those aggregate families.

### Reconstruct an erased FILTER from counters, or treat unchecked counters as clean (declined)

**Rejected because:** a counter without its sibling supplies no bucket membership oracle. Retain the separate
unchecked report and its conditional REINDEX advice. Paged siblings remain explicitly unchecked by this audit.
No missing root is manufactured from partial evidence.

### Read reduced partitions again to repair aggregate ranges (declined)

**Rejected because:** each aggregate FILTER's own value-to-record rows already retain the required partition or
referenced-record contributions. A second walk adds mapping and IO without adding evidence. Read the root and
its listed bucket pages; do not materialize live indexes or use counters to invent endpoint multiplicity.

### Diagnose partitions through compressed dictionary keys or type-level counts (declined)

**Rejected because:** dictionary entries may be stale and the reverse map has already folded equal keys;
type-level counts identify partitions/targets, not owners or reference rows. The header's live numeric IDs lead
to manifests that preserve original tuples, and membership parts supply owner details with an older inline fallback.

### Revisit schema warnings on every catalog load (declined)

**Rejected because:** this would add a permanent legacy-data cost or persistent reminder state for a one-time
migration. Report after successful publication and keep the startup log; current-protocol loads do not repeat it.

## Decision

**Chosen: option 2.** The unit of repair is surviving index information, including a root's listed pages when
needed. A refusal is planned before any migration rewrite or virtual-partition conversion. A warning states the
condition under which republishing is needed and never claims that schema susceptibility proves actual damage.

| Stored shape | Outcome |
|---|---|
| Reflected reference whose source is itself reflected | Bind the source chain first; independent of collection binding order. |
| Cardinality keys becoming equal, including open-range twins | Versioned reader sums counter contributions without discarding them. |
| Sort blocks equal today, including inherited parts | Preserve positional lengths, then merge and re-sort. |
| Surviving temporal, localized compound or decimal keys in a changed order, inline or paged | Re-sort the relevant stored structures; preserve records and identities. |
| Globally unique range twins of one complete owner | Fold and persist once; lookup, replacement and removal preserve that owner. |
| Global unique range twins of different owners | Refuse, naming field, raw values and decoded owners. |
| Unique FILTER range twins, or scalar temporal values meeting within one millisecond | Refuse before publication, including nonadjacent and cross-page buckets; name both values and owners. |
| Separate representative partitions becoming one current key, including two duplicate rows of one owner | Refuse from complete manifests and membership; name collection, reference, target, scope, values and owners. |
| The same representative collision entering through protocols 4 or 5 | Preflight before intermediate migrations publish; retain the original bootstrap, including protocol 4. |
| Whole-second date-time range thresholds or release-folded fractional range keys | Succeed with a conditional warning naming scalar, array, reference and compound fields; republish if sub-second values occur. |
| Whole-second price validity thresholds, including extreme closed bounds | Succeed; warning names price validity and the sub-second condition. |
| Collator-equal localized spellings folded in plain FILTER/SORT, or inherited from 2026.1 | Succeed; warning names fields and the collator-equality condition. Reordering surviving compound keys remains repairable. |
| Duplicate-capable references with a date-time range representative | Succeed; warning names the reference and rows to one target differing only by offset or below one second; edits may fail. Includes unindexed and archived rows. |
| Erased referenced-type FILTER with a surviving counter | Succeed; separate unchecked-counter report names the missing sibling and recommends conditional REINDEX. No promised query repair. |
| Shared range borders in referenced-type, group-type and reduced-group FILTERs (#1699) | Rebuild the range companion from its surviving bucket rows, using their stored partition/referenced-record identity. Preserve distinct scalar contributions; consolidate arrays per record. |
| Decimal-range thresholds, including inherited mixed scales with already ascending keys | Rebuild every companion at its frozen scale; rewrite bucket pages only when ordering or coincidence requires it. |
| Decimal-range bounds irreversibly folded by 2026.1 or older | Succeed; the same conditional warning names every filterable or unique decimal-range field and the decimal-point-position condition. No split or query repair is promised. |
| Healthy reference, group, withdrawn representative, range and uniqueness controls | Preserve their existing successful queries and mutations. |

## Key technical details

- Entry points are `Migration_2026_3`, `LegacyRepresentativeKeyPreflight`, the released cardinality/sort readers,
  `LegacyOwnerSortIndexCorrection`, `LegacyDecimalRangeFilterIndexCorrection` and `LegacyRangeFilterIndexCorrection`.
- `DefaultCatalogPersistenceService` invokes representative preflight before the 4 → 5 → 6 chain as well as inside
  6 → 7. A refused protocol-6 upgrade retains protocol 6; the chained fixture correctly retains protocol 4.
- Global unique folds compare the complete payload, not just the primary key. Collision detection survives
  unchanged spelling and retains the original first entry for diagnostics. Unique refusals preserve the existing
  detailed private/logged message and short public summary directing the operator to the log.
- The sort part's UID selects the released reader. Its dense cardinality column shares the value array's identity,
  allowing the migration to distinguish positional lengths from a sparse map whose equality may have changed.
  The 2026.1 and 2025.5 readers match sparse entries by released representation independently of map iteration order.
  Non-decimal sort-only parts were not rewritten by the 5 → 6 migration, so a protocol-6 catalog can still carry
  the 2026.1 UID. These readers remain necessary for untouched parts even after a successful protocol-7 upgrade.
  If an older localized map uses another representative spelling and exact attribution is impossible, retain its
  sparse columns and the existing conditional loss warning.
- Range reconstruction preserves the bucket axis, stable value IDs, record identities, locale, scope and frozen
  scale. New range pages allocate above the previous high-water; retired pages leave the new offset index.
- Nothing becomes durable until the bootstrap publishes the catalog header. Appends before that are inert on
  failure. No file reachable through the old bootstrap is deleted; opened collection services close on refusal.
- The one loss warning is schema-only, sorted, deduplicated and uncapped across collections. It emits one WARN
  and one identical console line after successful publication, and no reminder on later loads. The existing
  counter audit is separate. A failed publication emits no successful-upgrade loss warning.
- Warning predicates cover all entity scopes, applicable indexed reference scopes and active compound components.
  Duplicate temporal reference representatives require no index/scope gate. Price risk requires indexed prices.

## Verification

Real release fixture copies exercise the actual upgrade. `Release2026_2IndexUpgradeTest` retains the successful
query oracles; the committed release suite checks fixture origins, successful publication and fresh equivalence
only where that outcome is promised. `LegacyRepresentativeKeyPreflightTest.shouldRefuseCollidingPartitionsBeforePublishing`
and `Migration_2026_3_RangeUniqueUpgradeRoundTripTest.shouldRefuseEqualUniqueValuesBeforePublication` verify the
unchanged bootstrap and diagnostics. `Migration_2026_3_UpgradeReportTest` covers conditional fields, duplicate
references, missing siblings, one console line and no warning on reopen.

Earlier test-first evidence: 26 unique/representative cases with five counterfactuals; 78 range-companion cases
initially had 76 reds and two native decimal controls green. Both inherited decimal probes failed before repair
and passed after it. After the dev merge, the strengthened 78-case range oracle had **38 red, 40 green**: all 36 scalar
matrix rows and the two real-release shared-start rows exposed the mismatch. Preserving distinct scalar
contributions restored **78/78**. `Migration_2026_3_RangeCompanionUpgradeTest.shouldPublishAscendingDecimalThresholds`
pins the four inherited/native decimal cases; `shouldPublishAggregateIntervalsAsFreshWrites` and
`shouldPublishReleasedSharedStartIntervals` compare complete range points to fresh current writes. Removing the warning caller made 18 of 23 report cases fail; restoration passed 23/23.
Final step-7 selection after assigning the decimal fold: **1,919 tests, all green, zero skipped**. The two
original decimal-fold query expectations are replaced by warning coverage; the other **1,917** case outcomes
are unchanged. The report class has **25/25 green** cases, including real inherited decimal folds and the
already repaired paged decimal fixture. Removing only the decimal warning predicate makes those two cases
fail (**23 green, 2 red**); exact source restoration and reactor reinstall restore **25/25 green**.

The code-quality pass fixed two defects test-first, each red before its fix:
`Migration_2026_3_UpgradeReportTest.shouldNameOnlyCompoundElementsTheReflectedReferenceInherits` (the loss report
threw after publication on a reflected reference inheriting a compound without one of its attributes) and
`Migration_2026_3_RangeCompanionUpgradeTest.shouldNameAttributeAndPageWhenListedBucketPageIsMissing` (a missing
listed leaf page failed with a bare `NullPointerException`). It trimmed the aggregate range matrix from 72 to 12
cases that still sample every axis - each range subtype as scalar and array, each index family with all four
bucket/range storage combinations - so that class now runs 19 cases.

The final full functional module recorded **29,845 tests: 0 failures, 5 errors, 65 skipped**. Four errors were
HTTPS subscription session timeouts and one a catalog-handover timing case outside this change; all five passed
when re-run alone. The root install and the Javadoc build passed, production callers were audited, and the Kryo
audit reported no findings.

## Consequences & open follow-ups

The decimal-range fold follows the same warning policy as lost temporal precision and localized spellings.
Every filterable or unique `BigDecimalNumberRange` attribute qualifies: entity attributes in all scopes,
reference attributes in their indexed scopes, including array declarations via the plain type. The existing
schema traversal uses `hasFilterIndexInScope`; no body read or new data diagnostic is added. A 2026.2 catalog
cannot exclude 2026.1-or-older provenance. The exact field description is:

> attribute `<name>` (decimal range; only if values were first written by 2026.1 or older and two of them differ
> only in the position of the decimal point, such as `[12, 90]` and `[1.2, 9]`).

The grouped warning supplies the existing conditional republish recommendation. It is emitted after publication
and is absent on reopen, even when decimal threshold repair succeeded: surviving thresholds and irreversibly
folded bucket identities are separate outcomes.

The warning is deliberately broader than diagnosed damage, because proving absence would require the rejected
content scan. Operators must preserve the upgrade log and republish affected collections from source when its
conditions apply. Missing or folded information is not reconstructed; #409 owns a general rebuild. A successful
upgrade does not certify legacy query completeness for warned fields. REINDEX advice remains conditional for
unchecked counters, distinct from confirmed missing-entry errors.

Storage protocol remains 7 for all 2026.3 changes. Development catalogs already stamped 7 skip migration and
remain unsupported; recreate them. Downgrade to 2026.2 requires a pre-upgrade backup. This work does not expand
into unproven stale membership/locale cases or histogram and price reconstruction.

## Related work

- [Millisecond precision](2026-09-04-millisecond-temporal-precision.md) defines current equality and the released
  readers' temporal bounds; this record completes release-data handling without changing that contract.
- [Unique normalization](2026-10-08-unique-indexes-normalize-like-filter-indexes.md) requires one current key space
  and refusal over different owners; this work closes range and folded-FILTER gaps and allows complete-owner folds.
- [Cardinality normalization](2026-09-21-cardinality-counter-normalized-keys.md) defines summation and the existing
  audit; unchecked counters now carry conditional REINDEX advice and susceptible fields get the separate warning.
- [Single-owner partitions](2026-10-20-single-owner-partitions-as-registry-rows.md) fixes the one-publication 6 → 7
  boundary and representation conversion; preflight must precede it and range rewrites must compose with it.
- [Range counting kernel](2026-09-18-range-index-counting-kernel.md) depends on correct endpoint contributions;
  shared-border repair restores those contributions instead of changing query counting to hide missing endpoints.

## Timeline

- **2026-10-28** — real release failures frozen; part-local repair/refusal/warning mechanism accepted; lossless
  readers and ordering rewrites verified.
- **2026-10-29** — collision preflight, same-owner fold and range companions implemented; dev #1826 integrated;
  no-content-read warning and conditional REINDEX wording accepted; assigned proofs converted to their outcomes,
  with the inherited decimal fold subsequently assigned the same schema-only warning policy.
