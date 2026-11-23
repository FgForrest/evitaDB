---
title: Cross-entity dispatch reads array histogram sources from storage
date: 2026-11-23
updated: 2026-11-23 12:03
status: accepted
kind: fix
issues: [1778]
prs: [2056]
areas: [evita_engine/src/main/java/io/evitadb/core/collection, evita_engine/src/main/java/io/evitadb/index/mutation,
  evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional,
  evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation]
supersedes: []
superseded-by: []
relates: [2026-08-31-cross-entity-histogram-removal-pre-pass,
  2026-09-08-conditional-histogram-per-contribution-verdicts,
  2026-11-11-reference-histogram-net-change-per-run]
---

# Cross-entity dispatch reads array histogram sources from storage

Cross-entity dispatch now reads an array-valued histogram source from storage through the executor of the running
mutation and expands it as the local path does. The source filter index folds repeated elements and has no entry for
an empty array, so it cannot supply the units the local path would write.

## Why

A reference histogram can take its values from an attribute of the referenced entity. The owner's own upsert and
the dispatch that re-evaluates owners when the referenced entity, its group or a parent changes both write that
histogram. Issue #1778 requires one unit for each array element and the histogram's default only for a missing
value. Both writers must use the same rule so either can withdraw what the other added.

### Previous state

The owner expanded the stored array. The dispatch read distinct normalized keys from `FilterIndex`, so it added
one unit for `[10, 10]` where the owner added two. For a stored `[]`, it could add the default where the owner
added nothing. A later local withdrawal could find a unit missing or leave a surplus behind.

## Options considered

### Read stored arrays through the in-flight executor (chosen)

Both writers use `ContainerizedLocalMutationExecutor#readReferencedEntityAttribute` and expand the result with
`ReferenceIndexMutator#resolveHistogramValues`. The envelope carries the executor alongside its captured verdicts.
Scalar histograms keep their filter-index reads.

- **Pros** — Shares the local read and expansion rules and uses the executor chain that can see in-flight parts.
- **Cons** — Array dispatches pay for storage reads and a memo. The envelope carries live state for the root mutation.

### Fold repeated elements in the local path (declined)

- **Pros** — About 15 lines in one file, with no added read or state.
- **Rejected because** — Empty arrays would still need a stored read. Folding a withdrawal to one unit leaves
  `n-1` behind when an earlier local write added `n`. It also reverses #1778's per-element rule.
- **Revisit if** the contribution contract changes to one unit per distinct value and a reindex is part of the change.

### Treat stored empty arrays as missing in `resolveHistogramValues` (declined)

- **Pros** — Aligns the empty-array case with the old dispatch at little cost.
- **Rejected because** — Existing local writes for `[]` contributed nothing. A later withdrawal by a histogram
  with a default would expect a unit that was never added. **Suspected, not proven.** The 2026.3 default would
  log and skip the missing value. An engine configured to refuse would reject the write.
- **Revisit if** the value contract makes empty arrays equivalent to missing values and existing indexes are
  rebuilt under that contract.

### Read an index (declined)

- **Pros** — Reuses existing index access in the dispatch.
- **Rejected because** — `FilterIndex#foldOntoDistinctIndexKeys` loses multiplicity and the index has no entry for
  `[]`. The global index's locales cannot recover either. Indexing raw multiplicities would change the storage
  format to record data already held in entity storage.
- **Revisit if** an index format already needs to preserve both raw multiplicity and empty versus missing values.

### Read the source collection's `DataStoreReader` directly (declined)

- **Pros** — Reads the stored array without carrying an executor on the envelope.
- **Rejected because** — The reader could see applied, old, missing or mixed parts in one root mutation, depending
  on which parts were trapped earlier. `DataStoreChanges#getStoragePart` returns trapped instances, and a new locale
  part can exist only in its executor until `LocalMutationExecutorCollector#finish`. **Suspected, not proven.**
  The direct read was never run, and the dispatch would need extra plumbing to reach another collection's reader.
- **Revisit if** a collection reader can supply a consistent view of every in-flight part, including newly created
  parts, across collections.

### Push the source values on the envelope (declined)

- **Pros** — The index executor can supply the values for the referenced-entity dependency family without a pull.
- **Rejected because** — Group and parent envelopes read other referenced entities and would still need a pull,
  leaving two read mechanisms for the same contribution rule.
- **Revisit if** one capture mechanism can supply the correct values for the referenced-entity, group and parent
  dependency families.

### Read all sources from storage, including scalars (declined)

- **Pros** — One value path gives local and dispatch parity by construction.
- **Rejected because** — It adds a body and attribute-part read per distinct referenced entity to scalar dispatches,
  including group and parent fan-outs over hundreds of entities, without changing what the scalar path emits.
- **Revisit if** a parity cell shows a scalar divergence.

### Memoize expanded values (declined)

- **Pros** — Reuses the result of expansion across histograms sharing a source.
- **Rejected because** — Two histograms on the same attribute can have different defaults. In the counterfactual on
  a fresh catalog, the histogram with default 7 received 9 and its later withdrawal of 7 was refused.
- **Revisit if** reuse can distinguish every expansion input, including the descriptor's default, and measurements
  show the saved expansion is worth caching.

### Put the executor on the shared maintainer (declined)

- **Pros** — Keeps the executor off the envelope.
- **Rejected because** — It would add a second set-and-clear field beside `session` on a shared maintainer.
- **Revisit if** a dispatch-owned target can carry the executor for the required lifetime.

## Decision

Read arrays through the executor of the entity whose mutation emitted the envelope. The local path and dispatch
need the same view whether a part was trapped and mutated in place, read cold or created by the root mutation.
The collector already makes the envelope copy that carries the pre-pass verdicts. That copy carries the executor
for this fix.

## Key technical details

- **Value parity.** `ReevaluateExpressionExecutor#processHistogramTriggers` selects the array read for a referenced
  entity attribute. Preserve each element's multiplicity, zero units for an empty array and the applicable default
  for a missing value. A captured pre-mutation source value takes precedence on withdrawal. Localized dispatch
  enumerates the referenced entity's schema locales.
- **Lifetime.** `ReevaluateExpressionMutation#storageAccessor` is live only inside the
  `LocalMutationExecutorCollector#execute` that created it. The envelope no longer implements `Serializable`.
  Never queue, store, serialize or replay it outside the collector. WAL replay regenerates the envelope there.
- **Pre-pass pairing.** `LocalMutationExecutorCollector#attachPreMutationConditionState` attaches the executor only
  for an envelope captured by the pre-pass. A required stored read without it throws `GenericEvitaInternalError`
  naming the envelope. Falling back to the filter index would restore the defect.
- **Memo.** `ReevaluateExpressionExecutor.StoredReferencedEntityValues` keeps raw reads, including `null`, for one
  dispatch. Each descriptor expands its own values. The memo stays valid because dispatch writes only indexes.
- **Scalar equivalence is covered by tests.** `ReferenceHistogramPathParityFunctionalTest` covers `UNGROUPED`,
  `GROUPED` and `LOCALIZED`. `ConditionalBucketIndexingTest` covers defaults.

## Verification

- `ReferenceHistogramDispatchParityFunctionalTest` checks every cell in both catalog states after each step and
  after a restart. Successive removals next to an equal-valued sibling count the units. **16 cells** were red on
  the base and green after the change, covering repeated elements, decimals folded onto one key, repeated ranges,
  empty arrays, group, parent and reference-attribute conditions, a locale gained in the same transaction, and
  self-reference removal. The final run had **42 cells, 84 cases, all green**.
- Each counterfactual was predicted before the run. With no reader created, the results matched the base exactly.
  Removing without the reader turned **7 cells** red. Omitting the executor turned **34 of 36 cells** red with
  the missing-reader error. Memoizing expanded values turned the two-defaults cell red. Returning an empty part
  from the delegated read of an in-flight entity turned exactly the **two reflected group link cells** red
  (**4 cases**), with **80 green**. The direct `DataStoreReader` read was never run.
- In the reflected group link cells, one upsert raises a referenced entity's links to two groups. Their nested runs
  read through that entity's executor. `[10, 10]` gives two units per owner and the `[10, 11]` control gives one of
  each. The counterfactual establishes delegation of the attribute-part read. Delegation of the body read is
  inferred from the call order in `ContainerizedLocalMutationExecutor#readReferencedEntityAttribute`. Because the
  array stays unchanged, committed storage would yield the same values. The held-out cell changes the array.
- The refusal gate, run on 17 Nov while refusing was still the default, compared **520 cases** case by case. Base
  **286 green, 152 red, 82 refused**. After the change, **294 green, 148 red, 78 refused**. Exactly the
  **8 predicted cases** changed, all to green. No case newly refused.
- `ReevaluateExpressionExecutorTest#shouldCarryCapturedStateWithoutChangingIdentity` checks that the envelope copy
  carries the executor, pre-mutation source values and verdicts and preserves equality and hash code.
- Targeted classes **456 tests, 0 failures**. Beyond the plan **320 tests, 0 failures, 1 skipped** (a pre-existing
  disabled test). Final follow-up run **seven classes, 325 tests, 0 failures**.
- `-P unitAndFunctional,full clean test-compile` reported `BUILD SUCCESS` in phase 1 after the record's components
  changed. This check predates the follow-ups, whose signature changes were confined to one private nested class
  and one test class.

## Consequences & open follow-ups

### Two writers in one root mutation (#1925)

The held-out cell (cases **25 and 68**) was green on the base and red after `260ef1a285`. One upsert replaces
`[10, 11]` with `[10, 10]` and raises the source's links to two groups. The groups' nested dispatches add the
in-flight array. The source's own envelope runs after them, sees the contributions as absent before the batch,
and adds the same array again. Probes match after the upsert and after the subject's removal. After the last
removal, one unit per owner stays behind, including after restart. Writes completed on both builds.

On the base, two compensating errors give the right count for `[10, 10]` because each writer adds the distinct
element once. In the held-out scenario with distinct elements or a scalar, both builds would fail
(**Suspected, not proven**). The gate contains a scalar case of the same mechanism,
`OWN_SIZE_AND_TIER_KEEPING_THE_CONDITION`, red on both builds, and no array case for the overlap. #1925 owns
coordination when two writers act on the same contribution in one root mutation.

- **Suspected, not proven** — Reading the committed `[10, 11]` would also fail. The nested runs would add 11 and
  the first probe would be red. Only the in-flight read was run.
- **Suspected, not proven** — This is the only shape in which a nested dispatch reads an array changed in flight.
  The reflected-reference mutations from `ContainerizedLocalMutationExecutor` touch references on existing entities.
  `#popImplicitMutations` generates entity attributes for new entities or already-dirty attribute parts. In this
  reading, only the root changes the entity attribute, and its envelope runs after the nested dispatches.

### Catalogs indexed by earlier builds

The policy is rebuild first. This fix has no migration for old counts. The counter has no reference dimension, so
an upgrade cannot attribute a unit to the reference that supplied it. The upgrade note on #1778 instructs operators
to run a full reindex on this version or later to restore the counts. An earlier build writes the old counts again.

Since #2031, when a session write has to withdraw a reference-histogram value the index lacks, the 2026.3 default
logs an error line starting with `Cannot withdraw histogram value`, skips the value and completes. Refusal is opt-in.
Wrong counts can persist until a rebuild. The earlier code could reach a numerically correct end state in the
following three sequences. Each sequence is inferred from code.

- **Suspected, not proven** — Withdrawing each occurrence of a repeated element from an old folded contribution can
  take a sibling reference's equal-valued unit. The sibling's later withdrawal logs a missing value and skips it.
- **Suspected, not proven** — The surplus left by an old folded withdrawal could mask a missing unit elsewhere.
  Once the corrected withdrawal removes that surplus, a later withdrawal logs the missing unit and skips it.
- **Suspected, not proven** — In 2026.2 a missing source filter index could leave a default unwritten. An old default
  for a stored `[]` could mask that missing unit. The corrected write for `[]` adds nothing, so the later withdrawal
  logs the missing default and skips it.

An old default written for a stored `[]` can also remain as a surplus in the facet summary until reindex. The
withdrawal of an empty array emits no values, so this surplus produces no missing-value error.

### Cost and remaining work

- **Cost from code, unmeasured.** Scalars pay one boolean check per histogram. The collector's existing envelope
  copy carries one more reference. An array dispatch allocates one reader and a lazy memo, with one read and
  normalization per distinct `(entity, attribute, locale)` per dispatch. Group and parent fan-outs fetch the body
  and attribute parts of each referenced entity outside the mutation once per root mutation and retain them until
  it ends. To quantify this, extend the per-contribution verdicts fan-out benchmark with an array-valued histogram
  and run it in a quiet window.
- **Executor carrier.** `EntityCollection#createDispatchMaintainer` from #1924 is now on `dev`. Moving the executor
  to that per-dispatch target remains open as a refactor, preserving its lifetime and pairing with captured verdicts.
- **Reference-attribute sources (#1926).** Their cross-entity reader still needs to apply the same value rules.

## Related work

- [Cross-entity histogram removal pre-pass](2026-08-31-cross-entity-histogram-removal-pre-pass.md) captures the
  pre-mutation verdicts that travel with the executor.
- [Per-contribution verdicts](2026-09-08-conditional-histogram-per-contribution-verdicts.md) supplies the
  contribution-grained verdicts and the fan-out benchmark.
- [Net change per run](2026-11-11-reference-histogram-net-change-per-run.md) supplies local withdrawal and add
  ordering and records the rebuild-first policy and the 2026.3 session default.

## Timeline

- **2026-11-14** — array dispatch fix and nested reflected group link coverage implemented.
- **2026-11-17** — six array parity scenarios restated, because the indexing-expression validator refuses their
  schema at definition (they read a hop entity's references in a histogram condition).
- **2026-11-19** — #2031 merged, making reporting the 2026.3 default for missing histogram values.
- **2026-11-23** — decision recorded while PR #2056 was open.
