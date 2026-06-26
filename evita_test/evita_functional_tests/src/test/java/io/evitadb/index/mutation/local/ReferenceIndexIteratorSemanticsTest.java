/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down the per-reference iteration semantics of the two iterator entry points in
 * [ReferenceIndexMutator](ReferenceIndexMutator.java):
 *
 * - `forEachReferenceIndex` — fires the consumer once per qualifying reference, even when N
 *   references share a single target {@link AbstractReducedEntityIndex} instance. Selected via an
 *   {@link ReferenceIndexMutator.IterationPath} (`REDUCED_ENTITY`, `GROUP`, or `BOTH`).
 * - `forEachUniqueReferenceIndex` — fires the consumer at most once per unique target index
 *   instance. Used for entity-scoped work on a shared RGEI (cardinality bookkeeping, price
 *   set-semantic leaves). Implemented as `forEachReferenceIndex` plus an internal identity dedup.
 *
 * ## Identity-vs-cardinality contract
 *
 * The per-reference iterator fires the consumer once per reference, not once per unique target
 * index. When N references on the same entity point at the same group, the iterator hands the
 * consumer the SAME `ReducedGroupEntityIndex` Java instance N times. Callers performing
 * entity-scoped work (e.g. cardinality counters, price bitmaps) must use
 * `forEachUniqueReferenceIndex` so the iterator dedups by identity for them.
 *
 * These tests pin both modes' contracts in place so future refactors cannot silently change
 * cardinality, identity, or coverage semantics.
 *
 * ## Invariants under test
 *
 * For each entry point and a known scenario, every test asserts:
 *
 * 1. **Cardinality** — `forEachReferenceIndex` fires exactly once per qualifying reference (in
 *    storage order); `forEachUniqueReferenceIndex` fires exactly once per unique target index.
 * 2. **Identity** — when N references share an underlying RGEI/REI, `forEachReferenceIndex` passes
 *    the *same* Java instance for all N invocations (verified via `assertSame`).
 * 3. **Reference coverage** — the per-ordinal index discriminator matches the expected
 *    (referenceName, referencedPK or groupPK) derived from the seeded references.
 * 4. **Target-index coverage** — the number of unique target indexes visited matches the number of
 *    unique target keys among references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceIndexMutator iterator semantics — cardinality and shared-index identity")
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferenceIndexIteratorSemanticsTest extends AbstractMutatorTestBase {

	/**
	 * Reference name used throughout the tests. A managed-by-Evita reference is configured with both
	 * `REFERENCED_ENTITY` and `REFERENCED_GROUP_ENTITY` indexed components so that all six iterator entry
	 * points have something to iterate over.
	 *
	 * The name must not collide with any reference wired by the sample product schema in
	 * `DataGenerator` (which already declares `CATEGORY`, `BRAND`, `STORE`) under any naming
	 * convention — `sharedRef` was chosen because it is unique across all three conventions.
	 */
	private static final String REFERENCE_NAME = "sharedRef";

	/**
	 * Group entity type used for group-related index lookups. Must match the `groupType` configured on
	 * the reference schema.
	 */
	private static final String GROUP_TYPE = "sharedRefGroup";

	/**
	 * Owning entity primary key — kept constant across all tests; matches the value the test executor
	 * is initialized with.
	 */
	private static final int ENTITY_PK = 1;

	/**
	 * Replaces the constant-mock storage accessor from the base class with one that lets the test
	 * preload arbitrary `Reference[]` arrays into a `ReferencesStoragePart`.
	 */
	@Nonnull private final TestStorageContainerAccessor testContainerAccessor =
		new TestStorageContainerAccessor();

	/**
	 * Replaces the constant-mock index maintainer from the base class with one that returns a distinct
	 * `EntityIndex` instance per `EntityIndexKey`. Without this, identity assertions would be
	 * meaningless because all keys would map to the same singleton.
	 */
	@Nonnull private final KeyedEntityIndexMaintainer testEntityIndexMaintainer =
		new KeyedEntityIndexMaintainer();

	/**
	 * Executor wired with the keyed index maintainer and the test container accessor — replaces
	 * `this.executor` from the base class for the entire suite.
	 */
	@Nonnull private final EntityIndexLocalMutationExecutor testExecutor;

	{
		final AtomicInteger sequencer = new AtomicInteger(1);
		this.testExecutor = new EntityIndexLocalMutationExecutor(
			this.testContainerAccessor,
			ENTITY_PK,
			this.testEntityIndexMaintainer,
			new MockEntityIndexCreator<>(this.catalogIndex),
			() -> this.productSchema,
			sequencer::getAndIncrement,
			() -> {
				throw new UnsupportedOperationException("Not supported in the test.");
			},
			null,
			null,
			null
		);
	}

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schema) {
		// configure a reference that:
		// - allows multiple referenced entities (ZERO_OR_MORE) so we can attach 3-4 refs to one entity
		// - has an external group type configured, so REFERENCED_GROUP_ENTITY indexing is legal
		// - is indexed in both REFERENCED_ENTITY and REFERENCED_GROUP_ENTITY components, so all six
		//   iterator entry points have qualifying references to iterate over
		schema.withReferenceTo(
			REFERENCE_NAME,
			REFERENCE_NAME,
			Cardinality.ZERO_OR_MORE,
			thatIs -> thatIs
				.withGroupType(GROUP_TYPE)
				.indexedForFilteringAndPartitioning()
				.indexedWithComponents(
					ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				)
		);
	}

	@Test
	@DisplayName("forEachReferenceIndex (REDUCED_ENTITY) — once per ref; distinct refs → distinct REIs")
	void shouldFireOncePerReferenceAndProduceDistinctReducedEntityIndexesWhenReferencesDiffer() {
		// Scenario B for entity-level path: 3 references to distinct referenced entities, each yields
		// its own ReducedEntityIndex because EntityIndexKey discriminator is the referenced PK.
		final List<Reference> seeded = seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.REDUCED_ENTITY
		);

		assertEquals(3, recorder.invocationCount(), "3 references → 3 invocations");
		assertEquals(3, recorder.uniqueIndexInstances(), "distinct referenced PKs → distinct REIs");
		recorder.assertAllIndexesAreType(ReducedEntityIndex.class);
		recorder.assertReferenceCoverageOnEntityPath(seeded);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (REDUCED_ENTITY, predicate) — filters refs; REI identity preserved")
	void shouldFilterByPredicateAndPreserveIndexIdentityWhenSomeReferencesShareNothing() {
		// 4 references; predicate keeps only refs with primaryKey >= 20 → expect 3 invocations.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100),
			buildReference(REFERENCE_NAME, 40, 4, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING,
			this.testExecutor,
			recorder,
			reference -> reference.getReferenceKey().primaryKey() >= 20,
			false,
			ReferenceIndexMutator.IterationPath.REDUCED_ENTITY
		);

		assertEquals(3, recorder.invocationCount());
		assertEquals(3, recorder.uniqueIndexInstances());
		recorder.assertAllIndexesAreType(ReducedEntityIndex.class);

		// expected referenced PKs at each ordinal after filtering: 20, 30, 40
		recorder.assertEntityPathReferencedPrimaryKeysInOrder(20, 30, 40);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (GROUP) — N refs sharing one group share ONE RGEI instance")
	void shouldShareSingleRGEIInstanceWhenAllReferencesPointAtSameGroup() {
		// Scenario A — the canonical bug-class case. 3 references with distinct referenced PKs but all
		// pointing at the same group (group PK 100). For ZERO_OR_MORE cardinality, the RGEI key is
		// derived from (referenceName, groupPK, []) — identical for all three — so all three calls
		// MUST hand the consumer the same Java instance.
		final List<Reference> seeded = seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);

		// (1) cardinality: 3 references → 3 invocations
		assertEquals(3, recorder.invocationCount());

		// (2) reference coverage on the group path: the ordinal-N invocation must correspond to
		// reference-N's group PK (all 100 in this scenario).
		recorder.assertReferenceCoverageOnGroupPath(seeded);

		// (3) identity: all 3 invocations received the SAME RGEI instance
		assertEquals(
			1, recorder.uniqueIndexInstances(),
			"All references sharing a group must yield the same RGEI Java instance"
		);
		recorder.assertAllInvocationsPassSameInstance();

		// (4) the shared index is an RGEI, not an REI
		recorder.assertAllIndexesAreType(ReducedGroupEntityIndex.class);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (GROUP) — distinct groups yield distinct RGEI instances")
	void shouldYieldDistinctRGEIsWhenReferencesPointAtDifferentGroups() {
		// Scenario B — 3 refs, 3 different groups → 3 different RGEIs, 3 invocations.
		final List<Reference> seeded = seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 200),
			buildReference(REFERENCE_NAME, 30, 3, 300)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);

		assertEquals(3, recorder.invocationCount());
		assertEquals(3, recorder.uniqueIndexInstances());
		recorder.assertAllIndexesAreType(ReducedGroupEntityIndex.class);
		recorder.assertReferenceCoverageOnGroupPath(seeded);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (GROUP, predicate) — 4 refs, 2 unique RGEI instances")
	void shouldHandleMixedSharingWhenSomeReferencesShareGroupAndOthersDoNot() {
		// Scenario C — 4 refs: two share group 100, two share group 200.
		// Expected: consumer fires 4 times, but sees only 2 unique RGEI instances.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 200),
			buildReference(REFERENCE_NAME, 40, 4, 200)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING,
			this.testExecutor,
			recorder,
			reference -> true,
			false,
			ReferenceIndexMutator.IterationPath.GROUP
		);

		assertEquals(4, recorder.invocationCount());
		assertEquals(
			2, recorder.uniqueIndexInstances(),
			"References sharing a group must collapse to the same RGEI instance"
		);

		// Per-ordinal identity check: refs 10 and 20 share group 100 → invocations 0 and 1 must share;
		// refs 30 and 40 share group 200 → invocations 2 and 3 must share; the two groupings must
		// be different instances. This verifies the iterator does NOT collapse N references with a
		// shared group into a single invocation while still respecting EntityIndexKey discriminator
		// equality semantics in `getOrCreateReferencedGroupEntityIndex`.
		assertSame(
			recorder.indexAtInvocation(0), recorder.indexAtInvocation(1),
			"Refs 10 and 20 share group 100 → must share RGEI"
		);
		assertSame(
			recorder.indexAtInvocation(2), recorder.indexAtInvocation(3),
			"Refs 30 and 40 share group 200 → must share RGEI"
		);
		assertNotSame(
			recorder.indexAtInvocation(0), recorder.indexAtInvocation(2),
			"Different groups → different RGEIs"
		);

		recorder.assertAllIndexesAreType(ReducedGroupEntityIndex.class);
		recorder.assertGroupPathGroupPrimaryKeysInOrder(100, 100, 200, 200);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (GROUP) — references with no group are skipped")
	void shouldSkipReferencesWithoutGroupOnGroupPath() {
		// Mixed: two refs with groups (shared group 100), one ref without a group at all.
		// Group iterator must skip the group-less ref.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReferenceWithoutGroup(REFERENCE_NAME, 30, 3)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);

		assertEquals(2, recorder.invocationCount(), "the group-less ref must be skipped");
		assertEquals(1, recorder.uniqueIndexInstances());
		recorder.assertAllIndexesAreType(ReducedGroupEntityIndex.class);
		recorder.assertGroupPathGroupPrimaryKeysInOrder(100, 100);
	}

	@Test
	@DisplayName("forEachReferenceIndex (BOTH) — fires for both REI and RGEI; correct multiplicity")
	void shouldFireForBothEntityAndGroupPathsInOrder() {
		// 3 refs sharing group 100. Combined iterator runs REI path (3 invocations, 3 distinct REIs)
		// then RGEI path (3 invocations, 1 shared RGEI). Total: 6 invocations, 4 unique instances.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.BOTH
		);

		assertEquals(6, recorder.invocationCount(), "REI path fires 3× then RGEI path fires 3× = 6 total");

		// Index instances: 3 distinct REIs + 1 shared RGEI = 4 unique instances.
		assertEquals(4, recorder.uniqueIndexInstances());

		// First 3 invocations come from the REI path; last 3 from the RGEI path.
		recorder.assertSliceTypes(0, 3, ReducedEntityIndex.class);
		recorder.assertSliceTypes(3, 6, ReducedGroupEntityIndex.class);

		// REI slice: distinct referenced PKs 10, 20, 30.
		recorder.assertEntityPathReferencedPrimaryKeysInSlice(0, 3, 10, 20, 30);

		// RGEI slice: same group PK 100 each time, and all three are the same Java instance.
		recorder.assertGroupPathGroupPrimaryKeysInSlice(3, 6, 100, 100, 100);
		assertSame(recorder.indexAtInvocation(3), recorder.indexAtInvocation(4));
		assertSame(recorder.indexAtInvocation(4), recorder.indexAtInvocation(5));
		assertInstanceOf(ReducedGroupEntityIndex.class, recorder.indexAtInvocation(3));

		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachReferenceIndex (BOTH, predicate) — applies to both REI and RGEI paths")
	void shouldApplyPredicateToBothPathsWhenUsingForEachReferenceIndex() {
		// 4 refs; predicate keeps only refs with primaryKey >= 20. Refs 20, 30 share group 100; ref 40
		// has group 200. The combined iterator should fire:
		//   - REI path: 3 invocations (refs 20, 30, 40), 3 distinct REIs
		//   - RGEI path: 3 invocations (refs 20, 30 share, ref 40 distinct), 2 unique RGEIs
		// Total: 6 invocations, 5 unique instances.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100),
			buildReference(REFERENCE_NAME, 40, 4, 200)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING,
			this.testExecutor,
			recorder,
			reference -> reference.getReferenceKey().primaryKey() >= 20,
			false,
			ReferenceIndexMutator.IterationPath.BOTH
		);

		assertEquals(6, recorder.invocationCount());
		assertEquals(5, recorder.uniqueIndexInstances(), "3 REIs + 2 RGEIs = 5 unique instances");

		// REI slice: all distinct
		final AbstractReducedEntityIndex rei20 = recorder.indexAtInvocation(0);
		final AbstractReducedEntityIndex rei30 = recorder.indexAtInvocation(1);
		final AbstractReducedEntityIndex rei40 = recorder.indexAtInvocation(2);
		assertNotSame(rei20, rei30);
		assertNotSame(rei30, rei40);
		assertNotSame(rei20, rei40);

		// RGEI slice: refs 20 and 30 share group 100, ref 40 has group 200
		final AbstractReducedEntityIndex rgei20 = recorder.indexAtInvocation(3);
		final AbstractReducedEntityIndex rgei30 = recorder.indexAtInvocation(4);
		final AbstractReducedEntityIndex rgei40 = recorder.indexAtInvocation(5);
		assertSame(rgei20, rgei30);
		assertNotSame(rgei20, rgei40);

		recorder.assertSliceTypes(0, 3, ReducedEntityIndex.class);
		recorder.assertSliceTypes(3, 6, ReducedGroupEntityIndex.class);
		recorder.assertEntityPathReferencedPrimaryKeysInSlice(0, 3, 20, 30, 40);
		recorder.assertGroupPathGroupPrimaryKeysInSlice(3, 6, 100, 100, 200);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("All entry points — dropped references are skipped")
	void shouldSkipDroppedReferencesAcrossAllEntryPoints() {
		// 3 refs: middle one is dropped. All iterator entry points must skip it.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildDroppedReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		// REI path
		final Recorder reiRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, reiRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.REDUCED_ENTITY
		);
		assertEquals(2, reiRecorder.invocationCount());
		reiRecorder.assertEntityPathReferencedPrimaryKeysInOrder(10, 30);

		// RGEI path — fresh recorder ensures invocations from the REI path don't leak in.
		final Recorder rgeiRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, rgeiRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);
		assertEquals(2, rgeiRecorder.invocationCount());
		rgeiRecorder.assertGroupPathGroupPrimaryKeysInOrder(100, 100);

		// Combined path
		final Recorder allRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, allRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.BOTH
		);
		assertEquals(4, allRecorder.invocationCount(), "2 (REI) + 2 (RGEI) = 4");
	}

	@Test
	@DisplayName("All entry points — empty reference set yields zero invocations")
	void shouldNotInvokeConsumerWhenNoReferencesExist() {
		seedReferences();

		final Recorder reiRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, reiRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.REDUCED_ENTITY
		);
		assertEquals(0, reiRecorder.invocationCount());

		final Recorder rgeiRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, rgeiRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);
		assertEquals(0, rgeiRecorder.invocationCount());

		final Recorder allRecorder = new Recorder();
		ReferenceIndexMutator.forEachReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, allRecorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.BOTH
		);
		assertEquals(0, allRecorder.invocationCount());
	}

	@Test
	@DisplayName("forEachUniqueReferenceIndex (GROUP) — N refs sharing one RGEI yield ONE invocation")
	void shouldDedupByTargetIndexIdentityWhenReferencesShareSharedRgei() {
		// Canonical dedup scenario: 3 refs sharing group 100 resolve to the same RGEI instance.
		// `forEachReferenceIndex` would fire 3 times; `forEachUniqueReferenceIndex` folds to 1.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachUniqueReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.GROUP
		);

		assertEquals(1, recorder.invocationCount(), "3 shared-RGEI refs must collapse to 1 invocation");
		assertEquals(1, recorder.uniqueIndexInstances());
		recorder.assertAllIndexesAreType(ReducedGroupEntityIndex.class);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachUniqueReferenceIndex (BOTH) — dedup spans REI and RGEI paths")
	void shouldDedupAcrossBothPathsWhenUsingForEachUniqueReferenceIndex() {
		// 3 refs, all sharing group 100. REI path yields 3 distinct REIs; RGEI path yields 1 shared RGEI.
		// `forEachUniqueReferenceIndex` should fire once per unique target index = 3 (REIs) + 1 (RGEI) = 4.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachUniqueReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.BOTH
		);

		assertEquals(4, recorder.invocationCount(), "3 distinct REIs + 1 shared RGEI = 4 invocations");
		assertEquals(4, recorder.uniqueIndexInstances());
		recorder.assertSliceTypes(0, 3, ReducedEntityIndex.class);
		recorder.assertSliceTypes(3, 4, ReducedGroupEntityIndex.class);
		recorder.assertEveryInvocationPassesSameInstanceForBothSlots();
	}

	@Test
	@DisplayName("forEachUniqueReferenceIndex (REDUCED_ENTITY) — distinct refs unique, no dedup change")
	void shouldBehaveLikeForEachReferenceIndexWhenNoTargetIndexIsShared() {
		// When every reference has its own unique target index (REI keyed by distinct referenced PK),
		// `forEachUniqueReferenceIndex` and `forEachReferenceIndex` must produce the same invocation
		// count, ordering, and target-index identity coverage.
		seedReferences(
			buildReference(REFERENCE_NAME, 10, 1, 100),
			buildReference(REFERENCE_NAME, 20, 2, 100),
			buildReference(REFERENCE_NAME, 30, 3, 100)
		);

		final Recorder recorder = new Recorder();

		ReferenceIndexMutator.forEachUniqueReferenceIndex(
			ReferenceIndexType.FOR_FILTERING, this.testExecutor, recorder,
			Functions.alwaysTrue(), false, ReferenceIndexMutator.IterationPath.REDUCED_ENTITY
		);

		assertEquals(3, recorder.invocationCount(), "3 unique REIs → 3 invocations (no dedup needed)");
		assertEquals(3, recorder.uniqueIndexInstances());
		recorder.assertAllIndexesAreType(ReducedEntityIndex.class);
		recorder.assertEntityPathReferencedPrimaryKeysInOrder(10, 20, 30);
	}

	// ---------------------------------------------------------------------------------------------
	// Shared helper methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Loads the given references into the test storage container and returns them as a `List` so the
	 * caller can use them for ordinal-based coverage assertions.
	 *
	 * @param references the references to seed; iteration order is preserved
	 * @return the same references as a `List`
	 */
	@Nonnull
	private List<Reference> seedReferences(@Nonnull Reference... references) {
		this.testContainerAccessor.setReferences(references);
		return new ArrayList<>(Arrays.asList(references));
	}

	/**
	 * Builds a reference with a group attached. The internal primary key (`internalPK`) is set to a
	 * positive value so the reference is treated as `isKnownInternalPrimaryKey() == true`, which routes
	 * `forEachReferenceIndex` through the `bothKeys.stored()` branch — matching the production path
	 * for previously-persisted references.
	 *
	 * @param referenceName the reference name
	 * @param primaryKey    the referenced entity primary key
	 * @param internalPK    the internal (persisted) primary key for this reference instance; must be > 0
	 * @param groupPK       the group entity primary key the reference points at
	 * @return a `Reference` instance suitable for placement in a `ReferencesStoragePart`
	 */
	@Nonnull
	private Reference buildReference(
		@Nonnull String referenceName,
		int primaryKey,
		int internalPK,
		int groupPK
	) {
		final ReferenceSchema referenceSchema =
			this.productSchema.getReferenceOrThrowException(referenceName);
		return new Reference(
			this.productSchema,
			referenceSchema,
			new ReferenceKey(referenceName, primaryKey, internalPK),
			new GroupEntityReference(GROUP_TYPE, groupPK)
		);
	}

	/**
	 * Builds a reference with NO group assigned. Used to verify that the group path of
	 * `forEachReferenceIndex` skips group-less references.
	 *
	 * @param referenceName the reference name
	 * @param primaryKey    the referenced entity primary key
	 * @param internalPK    the internal (persisted) primary key for this reference instance; must be > 0
	 * @return a `Reference` instance with a `null` group
	 */
	@Nonnull
	private Reference buildReferenceWithoutGroup(
		@Nonnull String referenceName,
		int primaryKey,
		int internalPK
	) {
		final ReferenceSchema referenceSchema =
			this.productSchema.getReferenceOrThrowException(referenceName);
		return new Reference(
			this.productSchema,
			referenceSchema,
			new ReferenceKey(referenceName, primaryKey, internalPK),
			null
		);
	}

	/**
	 * Builds a tombstoned (dropped) reference. The iterator entry points must skip references where
	 * `exists()` returns `false`.
	 *
	 * @param referenceName the reference name
	 * @param primaryKey    the referenced entity primary key
	 * @param internalPK    the internal (persisted) primary key for this reference instance; must be > 0
	 * @param groupPK       the group entity primary key (still set so the test exercises drop-skip
	 *                      independently of group-skip)
	 * @return a `Reference` instance marked as dropped
	 */
	@Nonnull
	private Reference buildDroppedReference(
		@Nonnull String referenceName,
		int primaryKey,
		int internalPK,
		int groupPK
	) {
		final ReferenceSchema referenceSchema =
			this.productSchema.getReferenceOrThrowException(referenceName);
		return new Reference(
			this.productSchema,
			referenceSchema,
			2,
			new ReferenceKey(referenceName, primaryKey, internalPK),
			new GroupEntityReference(GROUP_TYPE, groupPK),
			true
		);
	}

	// ---------------------------------------------------------------------------------------------
	// Test infrastructure
	// ---------------------------------------------------------------------------------------------

	/**
	 * Identity-tracking record of a single `ReferenceIndexConsumer.accept` invocation. Stores both the
	 * `indexForRemoval` and `indexForUpsert` so the test can assert that the iterator passes the same
	 * instance for both slots in the no-RRK-migration case (the canonical scenario for all six entry
	 * points).
	 */
	private record Invocation(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex indexForRemoval,
		@Nonnull AbstractReducedEntityIndex indexForUpsert
	) {}

	/**
	 * `ReferenceIndexConsumer` that records every invocation it receives. All assertion helpers live
	 * here so individual tests stay focused on the scenario.
	 *
	 * Reference identity on each invocation is derived from the captured `EntityIndexKey`'s
	 * `RepresentativeReferenceKey` discriminator: for an REI it carries the *referenced* entity PK; for
	 * an RGEI it carries the *group* PK. Assertion helpers expose both so tests can verify coverage
	 * either way.
	 */
	private static final class Recorder implements ReferenceIndexConsumer {
		@Nonnull private final List<Invocation> invocations = new ArrayList<>(8);

		@Override
		public void accept(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull AbstractReducedEntityIndex indexForRemoval,
			@Nonnull AbstractReducedEntityIndex indexForUpsert
		) {
			this.invocations.add(new Invocation(referenceSchema, indexForRemoval, indexForUpsert));
		}

		/**
		 * Returns the total number of `accept` invocations seen.
		 */
		int invocationCount() {
			return this.invocations.size();
		}

		/**
		 * Counts the number of distinct `AbstractReducedEntityIndex` instances passed across all
		 * invocations (by identity, not by `equals`).
		 */
		int uniqueIndexInstances() {
			final IdentityHashMap<AbstractReducedEntityIndex, Boolean> seen = new IdentityHashMap<>();
			for (Invocation invocation : this.invocations) {
				seen.put(invocation.indexForUpsert(), Boolean.TRUE);
			}
			return seen.size();
		}

		/**
		 * Returns the index instance from a specific (0-based) invocation in iteration order — used by
		 * combined-path tests to inspect the boundary between the REI and RGEI slices.
		 *
		 * @param invocationIndex the 0-based ordinal of the invocation
		 * @return the `indexForUpsert` passed at that invocation
		 */
		@Nonnull
		AbstractReducedEntityIndex indexAtInvocation(int invocationIndex) {
			return this.invocations.get(invocationIndex).indexForUpsert();
		}

		/**
		 * Asserts that all invocations pass exactly one and the same index instance — the shared-RGEI
		 * invariant in its strongest form.
		 */
		void assertAllInvocationsPassSameInstance() {
			assertFalse(this.invocations.isEmpty(), "no invocations to check");
			final AbstractReducedEntityIndex first = this.invocations.get(0).indexForUpsert();
			for (int i = 1; i < this.invocations.size(); i++) {
				assertSame(
					first, this.invocations.get(i).indexForUpsert(),
					"invocation " + i + " passed a different index instance than invocation 0"
				);
			}
		}

		/**
		 * For each invocation, asserts that `indexForRemoval` and `indexForUpsert` are the same Java
		 * instance. The current iterators always pass `indexToUse` twice, so the two slots must be
		 * identical for every scenario covered by this test class. The two-slot API exists only to
		 * support `attributeUpdate` representative-key migrations, which the iterators themselves do
		 * not exercise.
		 */
		void assertEveryInvocationPassesSameInstanceForBothSlots() {
			for (int i = 0; i < this.invocations.size(); i++) {
				final Invocation invocation = this.invocations.get(i);
				assertSame(
					invocation.indexForRemoval(), invocation.indexForUpsert(),
					"invocation " + i + " — iterator must pass the same instance into both slots"
				);
			}
		}

		/**
		 * Asserts that every invocation received an index of the expected concrete subclass.
		 *
		 * @param expectedType the concrete subclass of `AbstractReducedEntityIndex`
		 */
		void assertAllIndexesAreType(@Nonnull Class<? extends AbstractReducedEntityIndex> expectedType) {
			for (int i = 0; i < this.invocations.size(); i++) {
				final AbstractReducedEntityIndex index = this.invocations.get(i).indexForUpsert();
				assertTrue(
					expectedType.isInstance(index),
					"invocation " + i + " expected " + expectedType.getSimpleName()
						+ " but got " + index.getClass().getSimpleName()
				);
			}
		}

		/**
		 * Asserts that invocations in `[from, to)` all received an index of the expected concrete
		 * subclass — used by combined-path tests to verify that the REI path runs before the RGEI
		 * path.
		 *
		 * @param from         start index (inclusive)
		 * @param to           end index (exclusive)
		 * @param expectedType the concrete subclass of `AbstractReducedEntityIndex`
		 */
		void assertSliceTypes(
			int from, int to, @Nonnull Class<? extends AbstractReducedEntityIndex> expectedType
		) {
			for (int i = from; i < to; i++) {
				final AbstractReducedEntityIndex index = this.invocations.get(i).indexForUpsert();
				assertTrue(
					expectedType.isInstance(index),
					"invocation " + i + " expected " + expectedType.getSimpleName()
						+ " but got " + index.getClass().getSimpleName()
				);
			}
		}

		/**
		 * Verifies that the REI-path invocations match the supplied seeded references in storage
		 * order. Each REI index key carries a `RepresentativeReferenceKey` whose primary key is the
		 * *referenced entity PK* — those must match the seeded references' `referenceKey.primaryKey()`
		 * in order. Also checks the reference schema name on each invocation.
		 *
		 * @param seeded the seeded references in storage order (assumed all qualify for the REI path)
		 */
		void assertReferenceCoverageOnEntityPath(@Nonnull List<Reference> seeded) {
			// match the iterator's filter: skip dropped refs
			final List<Reference> qualifying = new ArrayList<>(seeded.size());
			for (Reference reference : seeded) {
				if (reference.exists()) {
					qualifying.add(reference);
				}
			}
			assertEquals(
				qualifying.size(), this.invocations.size(),
				"invocation count does not match number of qualifying seeded refs"
			);
			for (int i = 0; i < qualifying.size(); i++) {
				final Reference expected = qualifying.get(i);
				final Invocation actual = this.invocations.get(i);
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					actual.indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expected.getReferenceKey().referenceName(), rrk.referenceName(),
					"invocation " + i + " — reference name mismatch"
				);
				assertEquals(
					expected.getReferenceKey().primaryKey(), rrk.primaryKey(),
					"invocation " + i + " — REI must be keyed by referenced entity PK"
				);
				assertEquals(
					expected.getReferenceKey().referenceName(), actual.referenceSchema().getName(),
					"invocation " + i + " — schema name mismatch"
				);
			}
		}

		/**
		 * Verifies that the RGEI-path invocations match the supplied seeded references in storage
		 * order. Each RGEI index key carries a `RepresentativeReferenceKey` whose primary key is the
		 * *group PK* — those must match the seeded references' group PKs in order. Seeded refs with no
		 * group are skipped from the comparison (since the iterator skips them).
		 *
		 * @param seeded the seeded references in storage order
		 */
		void assertReferenceCoverageOnGroupPath(@Nonnull List<Reference> seeded) {
			final List<Integer> expectedGroupPks = new ArrayList<>(seeded.size());
			for (Reference reference : seeded) {
				// match the iterator's filter: skip dropped refs and refs without an existing group
				if (!reference.exists()) {
					continue;
				}
				reference.getGroup().ifPresent(group -> {
					if (group.exists()) {
						expectedGroupPks.add(group.getPrimaryKey());
					}
				});
			}
			assertEquals(
				expectedGroupPks.size(), this.invocations.size(),
				"invocation count must equal number of seeded refs with an existing group"
			);
			for (int i = 0; i < expectedGroupPks.size(); i++) {
				final Invocation actual = this.invocations.get(i);
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					actual.indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expectedGroupPks.get(i).intValue(), rrk.primaryKey(),
					"invocation " + i + " — RGEI must be keyed by group PK"
				);
			}
		}

		/**
		 * Asserts that the consecutive REI-path invocations have referenced PKs matching the expected
		 * values. Useful when the caller has already filtered references via a predicate and so we
		 * can't compare against the full seeded list.
		 *
		 * @param expectedReferencedPks the expected referenced entity PKs in invocation order
		 */
		void assertEntityPathReferencedPrimaryKeysInOrder(@Nonnull int... expectedReferencedPks) {
			assertEquals(
				expectedReferencedPks.length, this.invocations.size(),
				"invocation count does not match expected REI sequence length"
			);
			for (int i = 0; i < expectedReferencedPks.length; i++) {
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					this.invocations.get(i).indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expectedReferencedPks[i], rrk.primaryKey(),
					"invocation " + i + " — REI PK mismatch"
				);
			}
		}

		/**
		 * Asserts that the consecutive RGEI-path invocations have group PKs matching the expected
		 * values.
		 *
		 * @param expectedGroupPks the expected group PKs in invocation order
		 */
		void assertGroupPathGroupPrimaryKeysInOrder(@Nonnull int... expectedGroupPks) {
			assertEquals(
				expectedGroupPks.length, this.invocations.size(),
				"invocation count does not match expected RGEI sequence length"
			);
			for (int i = 0; i < expectedGroupPks.length; i++) {
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					this.invocations.get(i).indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expectedGroupPks[i], rrk.primaryKey(),
					"invocation " + i + " — RGEI group PK mismatch"
				);
			}
		}

		/**
		 * Slice variant of {@link #assertEntityPathReferencedPrimaryKeysInOrder} for combined-path
		 * tests that interleave REI and RGEI invocations.
		 *
		 * @param from                  start invocation index (inclusive)
		 * @param to                    end invocation index (exclusive)
		 * @param expectedReferencedPks the expected referenced entity PKs in slice order
		 */
		void assertEntityPathReferencedPrimaryKeysInSlice(
			int from, int to, @Nonnull int... expectedReferencedPks
		) {
			assertEquals(
				expectedReferencedPks.length, to - from,
				"slice length must equal expected sequence length"
			);
			for (int i = 0; i < expectedReferencedPks.length; i++) {
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					this.invocations.get(from + i).indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expectedReferencedPks[i], rrk.primaryKey(),
					"slice invocation " + (from + i) + " — REI PK mismatch"
				);
			}
		}

		/**
		 * Slice variant of {@link #assertGroupPathGroupPrimaryKeysInOrder} for combined-path tests.
		 *
		 * @param from             start invocation index (inclusive)
		 * @param to               end invocation index (exclusive)
		 * @param expectedGroupPks the expected group PKs in slice order
		 */
		void assertGroupPathGroupPrimaryKeysInSlice(int from, int to, @Nonnull int... expectedGroupPks) {
			assertEquals(
				expectedGroupPks.length, to - from,
				"slice length must equal expected sequence length"
			);
			for (int i = 0; i < expectedGroupPks.length; i++) {
				final RepresentativeReferenceKey rrk = (RepresentativeReferenceKey)
					this.invocations.get(from + i).indexForUpsert().getIndexKey().discriminator();
				assertEquals(
					expectedGroupPks[i], rrk.primaryKey(),
					"slice invocation " + (from + i) + " — RGEI group PK mismatch"
				);
			}
		}
	}

	/**
	 * Extension of {@link MockStorageContainerAccessor} that lets tests inject a fully-populated
	 * `ReferencesStoragePart` directly, bypassing the base mock's lazy-initialization of an empty
	 * part.
	 */
	private static final class TestStorageContainerAccessor extends MockStorageContainerAccessor {

		@Nonnull private ReferencesStoragePart referencesStoragePart = new ReferencesStoragePart(ENTITY_PK);

		/**
		 * Replaces the current references storage part with one preloaded with the supplied
		 * `Reference[]`. Calling this method between tests resets prior state.
		 *
		 * References must be supplied in `Reference.FULL_COMPARATOR` order; the tests in this file
		 * pass them in ascending `(referenceName, primaryKey)` order, which satisfies the comparator
		 * for any single reference name. `lastUsedPrimaryKey` is set to `references.length` so any
		 * subsequent insert path would generate non-conflicting internal IDs.
		 *
		 * @param references the references to seed; may be empty to test the no-references case
		 */
		void setReferences(@Nonnull Reference... references) {
			this.referencesStoragePart = new ReferencesStoragePart(
				ENTITY_PK,
				/* lastUsedPrimaryKey */ references.length,
				references,
				/* sizeInBytes */ -1
			);
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			return this.referencesStoragePart;
		}
	}

	/**
	 * Test-only {@link IndexMaintainer} that returns a distinct `EntityIndex` instance per
	 * `EntityIndexKey`. Indexes are created lazily on first request and cached, so repeated
	 * `getOrCreateIndex(key)` calls with the same key always return the same Java instance — exactly
	 * the contract the production `IndexMaintainer` provides. This is what makes the "shared instance
	 * across N references" identity check meaningful.
	 */
	private final class KeyedEntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {

		@Nonnull private final Map<EntityIndexKey, EntityIndex> indexes = new HashMap<>(16);
		@Nonnull private final AtomicInteger primaryKeySequencer = new AtomicInteger(1000);

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexes.computeIfAbsent(entityIndexKey, this::createIndex);
		}

		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexes.get(entityIndexKey);
		}

		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			for (EntityIndex index : this.indexes.values()) {
				if (index.getPrimaryKey() == indexPrimaryKey) {
					return index;
				}
			}
			return null;
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			final EntityIndex existing = getIndexByPrimaryKeyIfExists(indexPrimaryKey);
			if (existing == null) {
				throw new UnsupportedOperationException(
					"Cannot create index by primary key alone in test fixture: " + indexPrimaryKey
				);
			}
			return existing;
		}

		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			this.indexes.remove(entityIndexKey);
		}

		/**
		 * Creates a concrete `EntityIndex` instance for the given key. Picks the matching subclass
		 * based on `EntityIndexType` — the iterators only ever request `REFERENCED_ENTITY` /
		 * `REFERENCED_GROUP_ENTITY` keys, but `GLOBAL` is supported as a defensive fallback so the
		 * executor's startup probes don't blow up.
		 *
		 * @param key the entity index key to build an index for
		 * @return a fresh `EntityIndex` instance keyed by `key`
		 */
		@Nonnull
		private EntityIndex createIndex(@Nonnull EntityIndexKey key) {
			final int pk = this.primaryKeySequencer.getAndIncrement();
			final String entityType = ReferenceIndexIteratorSemanticsTest.this.productSchema.getName();
			return switch (key.type()) {
				case REFERENCED_ENTITY -> new ReducedEntityIndex(pk, entityType, key);
				case REFERENCED_GROUP_ENTITY -> new ReducedGroupEntityIndex(pk, entityType, key);
				case GLOBAL -> new GlobalEntityIndex(pk, entityType, key);
				default -> throw new UnsupportedOperationException(
					"Test fixture does not create indexes of type " + key.type()
				);
			};
		}
	}

}
