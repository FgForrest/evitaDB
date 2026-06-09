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

package io.evitadb.index.mutation;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedEntityResolution;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedReferenceEntry;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedReferenceGroup;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.ConditionalSplit;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Tests for {@link ReevaluateExpressionExecutor} verifying condition evaluation logic,
 * parameterization, inner data structures, and the cross-reference false positive fix.
 *
 * Uses real {@link EntitySchema}, {@link ReferenceSchema}, {@link GlobalEntityIndex},
 * {@link ReferencedTypeEntityIndex}, and {@link ReducedGroupEntityIndex} objects wherever possible.
 * Only {@link IndexMutationTarget} remains mocked (its `evaluateFilter` requires the full query engine).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReevaluateExpressionExecutor")
@Tag(INDEXING)
@Tag(SCHEMA)
class ReevaluateExpressionExecutorTest {

	private static final String REFERENCE_NAME = "parameterValues";
	private static final String GROUP_ENTITY_TYPE = "parameterGroup";
	private static final String REFERENCED_ENTITY_TYPE = "parameterValue";
	private static final String ENTITY_TYPE = "product";

	/** Counter for generating unique storage PKs for indexes. */
	private int storagePkCounter = 1000;

	private ReevaluateExpressionExecutor executor;

	@BeforeEach
	void setUp() {
		this.storagePkCounter = 1000;
		this.executor = new ReevaluateExpressionExecutor();
	}

	/**
	 * Tests for the {@link AffectedEntityResolution} record, verifying bitmap union, entry filtering,
	 * and null group PK handling.
	 */
	@Nested
	@DisplayName("AffectedEntityResolution")
	class AffectedEntityResolutionTest {

		@Test
		@DisplayName("empty sentinel returns empty bitmap")
		void shouldReturnEmptyBitmapForEmptySentinel() {
			final Bitmap result = AffectedEntityResolution.EMPTY.allOwnerPKs();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("single group returns its owner PKs directly")
		void shouldReturnOwnerPKsFromSingleGroup() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10, 20, 30)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(List.of(group));

			final Bitmap result = resolution.allOwnerPKs();

			assertEquals(3, result.size());
			assertTrue(result.contains(10));
			assertTrue(result.contains(20));
			assertTrue(result.contains(30));
		}

		@Test
		@DisplayName("multiple groups unions their owner PKs")
		void shouldUnionOwnerPKsFromMultipleGroups() {
			final AffectedReferenceGroup group1 = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10, 20)
			);
			final AffectedReferenceGroup group2 = new AffectedReferenceGroup(
				5, 2, new BaseBitmap(20, 30)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(group1, group2)
			);

			final Bitmap result = resolution.allOwnerPKs();

			// union of {10,20} and {20,30} = {10,20,30}
			assertEquals(3, result.size());
			assertTrue(result.contains(10));
			assertTrue(result.contains(20));
			assertTrue(result.contains(30));
		}

		@Test
		@DisplayName("entriesForOwnerPKs filters by membership bitmap")
		void shouldFilterEntriesByOwnerPKMembership() {
			final AffectedReferenceGroup group1 = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10, 20, 30)
			);
			final AffectedReferenceGroup group2 = new AffectedReferenceGroup(
				5, 2, new BaseBitmap(20, 40)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(group1, group2)
			);

			// only include entries with ownerPK 20 or 30
			final Bitmap filter = new BaseBitmap(20, 30);
			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(filter)
			);

			// group1: PK 20 and 30 match; group2: PK 20 matches
			assertEquals(3, entries.size());
			assertEntry(entries.get(0), 3, 1, 20);
			assertEntry(entries.get(1), 3, 1, 30);
			assertEntry(entries.get(2), 5, 2, 20);
		}

		@Test
		@DisplayName("entriesForOwnerPKs returns empty when no PKs match")
		void shouldReturnNoEntriesWhenFilterExcludesAll() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10, 20)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(group)
			);

			final Bitmap filter = new BaseBitmap(99);
			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(filter)
			);

			assertTrue(entries.isEmpty());
		}

		@Test
		@DisplayName("entriesForOwnerPKs preserves null groupPK for ungrouped references")
		void shouldPreserveNullGroupPKForUngroupedReferences() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, null, new BaseBitmap(10)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(group)
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(10))
			);

			assertEquals(1, entries.size());
			assertNull(entries.get(0).groupPK());
		}
	}

	/**
	 * Tests for the filtered entry iterator returned by
	 * {@link AffectedEntityResolution#entriesForOwnerPKs(Bitmap)}.
	 */
	@Nested
	@DisplayName("FilteredEntryIterator")
	class FilteredEntryIteratorTest {

		@Test
		@DisplayName("iterator throws NoSuchElementException when exhausted")
		void shouldThrowWhenExhausted() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(group)
			);
			final Iterator<AffectedReferenceEntry> iterator =
				resolution.entriesForOwnerPKs(new BaseBitmap(10)).iterator();

			// consume the single entry
			assertTrue(iterator.hasNext());
			iterator.next();

			assertFalse(iterator.hasNext());
			assertThrows(NoSuchElementException.class, iterator::next);
		}

		@Test
		@DisplayName("iterator skips groups with no matching owner PKs")
		void shouldSkipGroupsWithNoMatchingOwnerPKs() {
			final AffectedReferenceGroup emptyGroup = new AffectedReferenceGroup(
				1, 1, new BaseBitmap(100, 200)
			);
			final AffectedReferenceGroup matchingGroup = new AffectedReferenceGroup(
				2, 2, new BaseBitmap(10, 20)
			);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(emptyGroup, matchingGroup)
			);

			// filter only includes PKs from matchingGroup
			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(10, 20))
			);

			assertEquals(2, entries.size());
			assertEquals(2, entries.get(0).referencedEntityPK());
			assertEquals(2, entries.get(1).referencedEntityPK());
		}

		@Test
		@DisplayName("iterator handles empty resolution")
		void shouldHandleEmptyResolution() {
			final Iterator<AffectedReferenceEntry> iterator =
				AffectedEntityResolution.EMPTY.entriesForOwnerPKs(new BaseBitmap(1, 2, 3)).iterator();

			assertFalse(iterator.hasNext());
		}
	}

	/**
	 * Tests for the {@link ConditionalSplit} record that holds disjoint indexed/non-indexed bitmaps.
	 */
	@Nested
	@DisplayName("ConditionalSplit")
	class ConditionalSplitTest {

		@Test
		@DisplayName("stores disjoint shouldBeIndexed and shouldNotBeIndexed bitmaps")
		void shouldStoreDisjointBitmaps() {
			final Bitmap shouldBe = new BaseBitmap(10, 20);
			final Bitmap shouldNotBe = new BaseBitmap(30, 40);

			final ConditionalSplit split = new ConditionalSplit(shouldBe, shouldNotBe);

			assertEquals(2, split.shouldBeIndexed().size());
			assertEquals(2, split.shouldNotBeIndexed().size());
			assertTrue(split.shouldBeIndexed().contains(10));
			assertTrue(split.shouldBeIndexed().contains(20));
			assertTrue(split.shouldNotBeIndexed().contains(30));
			assertTrue(split.shouldNotBeIndexed().contains(40));
		}
	}

	/**
	 * Tests for the main {@link ReevaluateExpressionExecutor#execute} method, verifying per-group
	 * vs global evaluation paths, cross-reference false positive prevention, and trigger skip logic.
	 */
	@Nested
	@DisplayName("Condition evaluation via execute()")
	class ConditionEvaluationTest {

		/**
		 * Tests the critical cross-reference false positive fix. When a product has references
		 * in multiple groups (PV=3 in group=2/CHECKBOX, PV=5 in group=1/INTERVAL), a change
		 * to PV=3 must NOT match a condition `groupHaving(attributeEquals('inputWidgetType', 'INTERVAL'))`
		 * for group=2 because group=2 is CHECKBOX, not INTERVAL.
		 *
		 * Before the fix, the `and` of `groupHaving(INTERVAL)` + `entityHaving(PK=3)` would match
		 * across different references within the same entity -- `groupHaving` matched via group=1
		 * while `entityHaving` matched via a different reference in group=2.
		 */
		@Test
		@DisplayName("per-group evaluation prevents cross-reference false positive")
		void shouldPreventCrossReferenceFalsePositive() {
			// Arrange: product PK=100 has:
			//   - PV=3 in group=2 (CHECKBOX) — should NOT be indexed
			//   - PV=5 in group=1 (INTERVAL) — not relevant to this trigger (PV=3 changed)
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			// affected: product 100 has PV=3 in group=2
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 2, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// Pre-seed: facet was previously indexed (will be removed after re-evaluation)
			seedFacet(testTarget.globalIndex(), testTarget.refSchema(), 3, 2, 100);

			// Per-group evaluation injects groupPK=2 → filter won't match (group=2 is CHECKBOX)
			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap()); // empty = no match

			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: evaluateFilter was called with a filter that includes BOTH entity PK and group PK scoping
			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(target).evaluateFilter(filterCaptor.capture(), eq(Scope.LIVE));

			final FilterBy capturedFilter = filterCaptor.getValue();
			assertNotNull(capturedFilter);
			final FilterConstraint[] topChildren = capturedFilter.getChildren();
			assertTrue(topChildren.length > 0, "Filter should have children");

			boolean foundReferenceHaving = false;
			for (final FilterConstraint child : topChildren) {
				if (child instanceof ReferenceHaving rh) {
					foundReferenceHaving = true;
					assertContainsGroupHavingWithPK(rh, 2);
					assertContainsEntityHavingWithPK(rh, 3);
				}
			}
			assertTrue(foundReferenceHaving, "Filter should contain ReferenceHaving");

			// Since evaluateFilter returned empty → all PKs in shouldNotBeIndexed → no facets present
			assertNoFacets(testTarget.globalIndex());
		}

		@Test
		@DisplayName("per-group evaluation correctly includes matching group")
		void shouldIncludeMatchingGroupInPerGroupEvaluation() {
			// Product PK=100 has PV=3 in group=1 (INTERVAL) — SHOULD be indexed
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// The parameterized filter with group=1 (INTERVAL) MATCHES product 100
			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100));
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: addFacet was called on the global index for product 100
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 100);
		}

		@Test
		@DisplayName("per-group evaluation with mixed results across groups")
		void shouldSplitCorrectlyAcrossMultipleGroups() {
			// Product A (PK=100): PV=3 in group=1 (INTERVAL) — should be indexed
			// Product B (PK=200): PV=3 in group=2 (CHECKBOX) — should NOT be indexed
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group1 = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedReferenceGroup group2 = new AffectedReferenceGroup(
				3, 2, new BaseBitmap(200)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(
				List.of(group1, group2)
			);

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// Pre-seed: product 200 was previously indexed in group=2 (will be removed)
			seedFacet(testTarget.globalIndex(), testTarget.refSchema(), 3, 2, 200);

			// Per-group evaluation:
			// - group=1 filter returns {100} (matches)
			// - group=2 filter returns {} (no match)
			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100))  // group=1 matches product 100
				.thenReturn(new BaseBitmap());    // group=2 matches nothing

			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: product 100 should be indexed, product 200 should not be indexed
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 100);
			assertFacetAbsent(testTarget.globalIndex(), REFERENCE_NAME, 3, 200);
		}

		@Test
		@DisplayName("unconditional trigger puts all affected PKs into shouldBeIndexed")
		void shouldPutAllPKsInShouldBeIndexedWhenUnconditional() {
			// Trigger without a FilterBy condition — unconditional
			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, null, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100, 200)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: both PKs should be added (unconditional = all shouldBeIndexed)
			// evaluateFilter should NOT be called (no condition to evaluate)
			verify(target, never()).evaluateFilter(any(), any());
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 100);
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 200);
		}

		@Test
		@DisplayName("global evaluation used when filter has no groupHaving")
		void shouldUseGlobalEvaluationWhenNoGroupHaving() {
			// Filter without groupHaving — only entityHaving
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new EntityHaving(
						new AttributeEquals("isActive", true)
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			// Even though groups have non-null groupPK, the filter has no groupHaving
			// so global evaluation should be used (single evaluateFilter call)
			final AffectedReferenceGroup group1 = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedReferenceGroup group2 = new AffectedReferenceGroup(
				3, 2, new BaseBitmap(200)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(
				List.of(group1, group2)
			);

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// Pre-seed: product 200 was previously indexed in group=2 (will be removed)
			seedFacet(testTarget.globalIndex(), testTarget.refSchema(), 3, 2, 200);

			// Global evaluation returns product 100 as matching
			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100));
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: evaluateFilter called exactly ONCE (global evaluation, not per-group)
			verify(target, times(1)).evaluateFilter(any(), any());

			// Product 100 should be added, product 200 should not be present
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 100);
			assertFacetAbsent(testTarget.globalIndex(), REFERENCE_NAME, 3, 200);
		}

		@Test
		@DisplayName("global evaluation used for GROUP_ENTITY_ATTRIBUTE even with groupHaving")
		void shouldUseGlobalEvaluationForGroupEntityAttribute() {
			// Filter WITH groupHaving, but dependency is GROUP_ENTITY_ATTRIBUTE
			// (parameterize already handles group scoping for this case)
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.GROUP_ENTITY_ATTRIBUTE
			);

			// For GROUP_ENTITY_ATTRIBUTE, the mutatedEntityPK is the group PK (1).
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				5, 1, new BaseBitmap(100)
			);

			final TestTarget testTarget = createTestTargetForGroupEntityAttribute(
				group, ReferenceIndexType.FOR_FILTERING
			);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 1, DependencyType.GROUP_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100));
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: evaluateFilter called exactly ONCE (global path, not per-group)
			verify(target, times(1)).evaluateFilter(any(), any());

			// Verify the parameterized filter injects groupHaving(and(condition, entityPrimaryKeyInSet(1)))
			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(target).evaluateFilter(filterCaptor.capture(), eq(Scope.LIVE));
			final FilterBy captured = filterCaptor.getValue();

			// For GROUP_ENTITY_ATTRIBUTE, parameterize() injects PK into GroupHaving
			boolean foundGroupPKScope = false;
			for (final FilterConstraint child : captured.getChildren()) {
				if (child instanceof ReferenceHaving rh) {
					foundGroupPKScope = containsGroupHavingWithPK(rh, 1);
				}
			}
			assertTrue(foundGroupPKScope,
				"Filter should contain GroupHaving with entityPrimaryKeyInSet(1) for group entity scoping");

			// Product 100 should be indexed
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 5, 100);
		}

		@Test
		@DisplayName("global evaluation used when all groups have null groupPK (ungrouped reference)")
		void shouldUseGlobalEvaluationWhenAllGroupsUngrouped() {
			// Ungrouped reference — the schema has NO group type.
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new EntityHaving(
						new AttributeEquals("isActive", true)
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			// Ungrouped reference: group PK is null
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, null, new BaseBitmap(100)
			);

			final TestTarget testTarget = createTestTargetUngrouped(
				group, ReferenceIndexType.FOR_FILTERING
			);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100));
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: evaluateFilter called exactly ONCE (global path, not per-group)
			verify(target, times(1)).evaluateFilter(any(), any());
		}

		@Test
		@DisplayName("skips processing when no facet or histogram triggers exist")
		void shouldSkipProcessingWhenNoTriggersExist() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(null); // no facet trigger
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList()); // no histogram triggers

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: no filter evaluation, no facets added
			verify(target, never()).evaluateFilter(any(), any());
			assertNoFacets(testTarget.globalIndex());
		}

		@Test
		@DisplayName("skips processing when no affected owner entities exist")
		void shouldSkipProcessingWhenNoAffectedEntities() {
			final AffectedEntityResolution affected = AffectedEntityResolution.EMPTY;

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: no trigger lookups needed, no filter evaluation
			verify(target, never()).getFacetTrigger(any(), any(), any());
			verify(target, never()).getHistogramTriggers(any(), any());
			verify(target, never()).evaluateFilter(any(), any());
		}

		@Test
		@DisplayName("per-group evaluation for REFERENCED_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldUsePerGroupEvaluationForReferencedEntityReferenceAttribute() {
			// REFERENCED_ENTITY_REFERENCE_ATTRIBUTE should also trigger per-group evaluation
			// when groupHaving is present and groups have non-null groupPK
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE
			);

			final AffectedReferenceGroup group1 = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedReferenceGroup group2 = new AffectedReferenceGroup(
				3, 2, new BaseBitmap(200)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(
				List.of(group1, group2)
			);

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, Scope.LIVE
			);

			// Pre-seed: product 200 was previously indexed in group=2 (will be removed)
			seedFacet(testTarget.globalIndex(), testTarget.refSchema(), 3, 2, 200);

			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap(100))
				.thenReturn(new BaseBitmap());
			when(target.getFacetTrigger(
				REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, Scope.LIVE
			)).thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Assert: evaluateFilter called TWICE (per-group: once per group)
			verify(target, times(2)).evaluateFilter(any(), any());

			// Product 100 faceted, product 200 not
			assertFacetPresent(testTarget.globalIndex(), REFERENCE_NAME, 1, 3, 100);
			assertFacetAbsent(testTarget.globalIndex(), REFERENCE_NAME, 3, 200);
		}

		/**
		 * Reproduces production `INVALID_ARGUMENT` failure (issue #1233): catalog `senesi`,
		 * `ParameterValue` upsert blowing up with `A total of 2 constraints were found in a query,
		 * but expected is only one!`.
		 *
		 * `ReevaluateExpressionExecutor.evaluateCondition` decides whether per-group evaluation is
		 * required by calling `FinderVisitor.findConstraint(filter, GroupHaving.class::isInstance)`.
		 * The singular variant throws `MoreThanSingleResultException` when more than one match is
		 * found in the constraint tree — but the caller only wants an existence check.
		 *
		 * `ExpressionToQueryTranslator` wraps each sibling `$reference.groupEntity?.…` predicate in
		 * its own `groupHaving(...)`. When a reference declares two such predicates (e.g.
		 * `bucketedPartially` and `assignedWhen` both probing group attributes), the resulting
		 * `FilterBy` contains two `GroupHaving` siblings and the existence check incorrectly throws.
		 */
		@Test
		@DisplayName("evaluateCondition must not throw when filter has multiple GroupHaving siblings")
		void shouldNotThrowWhenFilterHasMultipleGroupHavingConstraints() {
			// Simulates ExpressionToQueryTranslator output: two GroupHaving siblings inside the same
			// ReferenceHaving — exactly the production shape that triggered the INVALID_ARGUMENT.
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					),
					new GroupHaving(
						new AttributeEquals("bucketedPartially", true)
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			// Pre-stub evaluateFilter so the per-group code path (reachable once the fix lands)
			// has a deterministic empty result and does not NPE on a missing stub.
			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap());
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Before the fix this call propagates `MoreThanSingleResultException` with message
			// "A total of `2` constraints were found in a query, but expected is only one!"
			assertDoesNotThrow(() ->
				ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target)
			);
		}

		/**
		 * Companion regression for issue #1233: once the existence check stops throwing on multiple
		 * sibling `GroupHaving` constraints, the per-group evaluation must still produce a
		 * **correctly scoped** filter — i.e. the group PK must be injected into **every** sibling
		 * `GroupHaving`, not just the first one. Otherwise the unscoped sibling would match across
		 * all groups, producing the same cross-reference false positive that
		 * `shouldPreventCrossReferenceFalsePositive` proves the per-group path must prevent.
		 */
		@Test
		@DisplayName("per-group evaluation injects group PK into every sibling GroupHaving")
		void shouldInjectGroupPkIntoEverySiblingGroupHaving() {
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving(
					REFERENCE_NAME,
					new GroupHaving(
						new AttributeEquals("inputWidgetType", "INTERVAL")
					),
					new GroupHaving(
						new AttributeEquals("bucketedPartially", true)
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap());
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Capture the parameterized filter sent to evaluateFilter.
			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(target).evaluateFilter(filterCaptor.capture(), eq(Scope.LIVE));

			// Both sibling GroupHaving constraints must contain entityPrimaryKeyInSet(groupPK=1).
			final List<GroupHaving> groupHavings = FinderVisitor.findConstraints(
				filterCaptor.getValue(), GroupHaving.class::isInstance
			);
			assertEquals(
				2, groupHavings.size(),
				"Parameterized filter should preserve both sibling GroupHaving constraints"
			);
			for (final GroupHaving groupHaving : groupHavings) {
				assertTrue(
					containsPKInSet(groupHaving.getChildren(), 1),
					"GroupHaving must contain entityPrimaryKeyInSet(1) after per-group injection"
				);
			}
		}

		/**
		 * Pre-existing limitation surfaced while fixing issue #1233: `parameterize` /
		 * `parameterizeWithGroupScope` previously walked only the top-level direct children of
		 * `FilterBy`. When the trigger filter wrapped its `ReferenceHaving` instances in an
		 * `Or` (e.g. expression `attrA == X || attrB == Y` on the same reference), neither inner
		 * `ReferenceHaving` was reached and PK scope injection was silently skipped — producing
		 * cross-reference false positives identical to the in-group sibling case.
		 *
		 * The fix routes PK injection through `ConstraintCloneVisitor`, which recurses through
		 * any container (`Or`, `And`, `Not`, ...) so every matching `ReferenceHaving` is rewritten
		 * regardless of nesting depth.
		 */
		@Test
		@DisplayName("per-group evaluation injects group PK into every Or-branch ReferenceHaving")
		void shouldInjectGroupPkIntoEveryOrBranchReferenceHaving() {
			final FilterBy triggerFilter = new FilterBy(
				new Or(
					new ReferenceHaving(
						REFERENCE_NAME,
						new GroupHaving(
							new AttributeEquals("inputWidgetType", "INTERVAL")
						)
					),
					new ReferenceHaving(
						REFERENCE_NAME,
						new GroupHaving(
							new AttributeEquals("bucketedPartially", true)
						)
					)
				)
			);

			final StubFacetTrigger facetTrigger = new StubFacetTrigger(
				REFERENCE_NAME, triggerFilter, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			);

			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(100)
			);
			final AffectedEntityResolution affected = new AffectedEntityResolution(List.of(group));

			final TestTarget testTarget = createTestTarget(affected, ReferenceIndexType.FOR_FILTERING);
			final IndexMutationTarget target = testTarget.target();
			final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
				REFERENCE_NAME, 3, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE
			);

			when(target.evaluateFilter(any(FilterBy.class), eq(Scope.LIVE)))
				.thenReturn(new BaseBitmap());
			when(target.getFacetTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Scope.LIVE))
				.thenReturn(facetTrigger);
			when(target.getHistogramTriggers(REFERENCE_NAME, Scope.LIVE))
				.thenReturn(Collections.emptyList());

			// Act
			ReevaluateExpressionExecutorTest.this.executor.execute(mutation, target);

			// Capture the parameterized filter sent to evaluateFilter.
			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(target).evaluateFilter(filterCaptor.capture(), eq(Scope.LIVE));

			// Both ReferenceHavings nested under the Or must have been rewritten so their inner
			// GroupHaving contains the group PK (=1). Before the fix, neither was touched.
			final List<GroupHaving> groupHavings = FinderVisitor.findConstraints(
				filterCaptor.getValue(), GroupHaving.class::isInstance
			);
			assertEquals(
				2, groupHavings.size(),
				"Parameterized filter should preserve both Or-branch GroupHaving constraints"
			);
			for (final GroupHaving groupHaving : groupHavings) {
				assertTrue(
					containsPKInSet(groupHaving.getChildren(), 1),
					"GroupHaving must contain entityPrimaryKeyInSet(1) after per-group injection"
				);
			}
		}
	}

	/**
	 * Tests for the {@link AffectedReferenceGroup} record field storage and null group PK handling.
	 */
	@Nested
	@DisplayName("AffectedReferenceGroup record")
	class AffectedReferenceGroupTest {

		@Test
		@DisplayName("stores referenced entity PK, group PK, and owner PKs")
		void shouldStoreAllFields() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, 1, new BaseBitmap(10, 20)
			);

			assertEquals(3, group.referencedEntityPK());
			assertEquals(1, group.groupPK());
			assertEquals(2, group.ownerPKs().size());
		}

		@Test
		@DisplayName("allows null groupPK for ungrouped references")
		void shouldAllowNullGroupPK() {
			final AffectedReferenceGroup group = new AffectedReferenceGroup(
				3, null, new BaseBitmap(10)
			);

			assertNull(group.groupPK());
		}
	}

	/**
	 * Tests for the {@link AffectedReferenceEntry} record field storage and null group PK handling.
	 */
	@Nested
	@DisplayName("AffectedReferenceEntry record")
	class AffectedReferenceEntryTest {

		@Test
		@DisplayName("stores all fields correctly")
		void shouldStoreAllFields() {
			final AffectedReferenceEntry entry = new AffectedReferenceEntry(3, 1, 100);

			assertEquals(3, entry.referencedEntityPK());
			assertEquals(1, entry.groupPK());
			assertEquals(100, entry.ownerPK());
		}

		@Test
		@DisplayName("allows null groupPK")
		void shouldAllowNullGroupPK() {
			final AffectedReferenceEntry entry = new AffectedReferenceEntry(3, null, 100);

			assertNull(entry.groupPK());
		}
	}

	// -- holder record --

	/**
	 * Bundles a mock {@link IndexMutationTarget} with the real {@link GlobalEntityIndex} and
	 * {@link ReferenceSchemaContract} it was configured with, so tests can assert state directly.
	 *
	 * @param target      the mock target
	 * @param globalIndex the real global index wired into the target
	 * @param refSchema   the real reference schema wired into the target
	 */
	private record TestTarget(
		@Nonnull IndexMutationTarget target,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull ReferenceSchemaContract refSchema
	) {
	}

	// -- target factory methods --

	/**
	 * Creates a mock {@link IndexMutationTarget} backed by real indexes and schemas for grouped
	 * references. The target's `evaluateFilter`, `getFacetTrigger`, and `getHistogramTriggers`
	 * remain as mock stubs for per-test configuration.
	 *
	 * @param affected  the desired affected entity resolution
	 * @param indexType the reference index type (FOR_FILTERING or FOR_FILTERING_AND_PARTITIONING)
	 * @return holder with mock target, real global index, and real reference schema
	 */
	@Nonnull
	private TestTarget createTestTarget(
		@Nonnull AffectedEntityResolution affected,
		@Nonnull ReferenceIndexType indexType
	) {
		final IndexMutationTarget target = mock(IndexMutationTarget.class);

		final ReferenceSchema refSchema = createRealReferenceSchema(indexType);
		final EntitySchema entitySchema = createRealEntitySchema(refSchema);
		when(target.getEntitySchema()).thenReturn(entitySchema);

		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		when(target.getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)))
			.thenReturn(globalIndex);

		if (!affected.groups().isEmpty()) {
			setupResolutionIndexes(target, affected);
		}

		return new TestTarget(target, globalIndex, refSchema);
	}

	/**
	 * Creates a mock target for GROUP_ENTITY_ATTRIBUTE resolution. Builds real
	 * {@link ReferencedTypeEntityIndex} and {@link ReducedGroupEntityIndex} objects populated to
	 * match the desired affected group.
	 *
	 * @param group     the desired affected group (groupPK = mutatedEntityPK for this dependency type)
	 * @param indexType the reference index type
	 * @return holder with mock target, real global index, and real reference schema
	 */
	@Nonnull
	private TestTarget createTestTargetForGroupEntityAttribute(
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferenceIndexType indexType
	) {
		final IndexMutationTarget target = mock(IndexMutationTarget.class);

		final ReferenceSchema refSchema = createRealReferenceSchema(indexType);
		final EntitySchema entitySchema = createRealEntitySchema(refSchema);
		when(target.getEntitySchema()).thenReturn(entitySchema);

		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			2, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		when(target.getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)))
			.thenReturn(globalIndex);

		// resolveForGroupEntityAttribute: RTEI lookup by group PK
		final ReferencedTypeEntityIndex groupRtei = new ReferencedTypeEntityIndex(
			nextStoragePk(), ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
		when(target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		)).thenReturn(groupRtei);

		// create RGEI and register with RTEI
		final int rgeiPk = nextStoragePk();
		final ReducedGroupEntityIndex rgei = createReducedGroupEntityIndex(rgeiPk, group.groupPK());
		groupRtei.insertPrimaryKeyIfMissing(rgeiPk, group.groupPK());

		// populate RGEI with owner PKs for the referenced entity
		for (final int ownerPK : group.ownerPKs()) {
			rgei.insertPrimaryKeyIfMissing(ownerPK, group.referencedEntityPK());
		}

		when(target.getIndexByPrimaryKeyIfExists(rgeiPk)).thenReturn(rgei);

		return new TestTarget(target, globalIndex, refSchema);
	}

	/**
	 * Creates a mock target for an ungrouped reference (no group type in schema). Uses real
	 * {@link ReferencedTypeEntityIndex} and {@link ReducedEntityIndex} objects.
	 *
	 * @param group     the desired affected group (groupPK should be null)
	 * @param indexType the reference index type
	 * @return holder with mock target, real global index, and real reference schema
	 */
	@Nonnull
	private TestTarget createTestTargetUngrouped(
		@Nonnull AffectedReferenceGroup group,
		@Nonnull ReferenceIndexType indexType
	) {
		final IndexMutationTarget target = mock(IndexMutationTarget.class);

		// Schema WITHOUT a group type
		final ReferenceSchema refSchema = createRealReferenceSchemaUngrouped(indexType);
		final EntitySchema entitySchema = createRealEntitySchema(refSchema);
		when(target.getEntitySchema()).thenReturn(entitySchema);

		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			3, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		when(target.getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)))
			.thenReturn(globalIndex);

		// resolveForRefEntityAttrUngrouped: RTEI lookup
		final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
			nextStoragePk(), ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
		when(target.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		)).thenReturn(rtei);

		final int reducedPk = nextStoragePk();
		final ReducedEntityIndex reducedIndex = createReducedEntityIndex(reducedPk, group.referencedEntityPK());
		rtei.insertPrimaryKeyIfMissing(reducedPk, group.referencedEntityPK());

		for (final int ownerPK : group.ownerPKs()) {
			reducedIndex.insertPrimaryKeyIfMissing(ownerPK);
		}

		when(target.getIndexByPrimaryKeyIfExists(reducedPk)).thenReturn(reducedIndex);

		return new TestTarget(target, globalIndex, refSchema);
	}

	/**
	 * Sets up real index objects on the mock target for the grouped reference resolution path.
	 * Builds real {@link ReferencedTypeEntityIndex} and {@link ReducedGroupEntityIndex} instances
	 * populated with the data matching the desired {@link AffectedEntityResolution}.
	 *
	 * @param target   the mock target to configure
	 * @param affected the desired resolution
	 */
	private void setupResolutionIndexes(
		@Nonnull IndexMutationTarget target,
		@Nonnull AffectedEntityResolution affected
	) {
		final boolean hasGroupedEntries = affected.groups().stream()
			.anyMatch(g -> g.groupPK() != null);

		if (hasGroupedEntries) {
			final ReferencedTypeEntityIndex groupRtei = new ReferencedTypeEntityIndex(
				nextStoragePk(), ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			);
			when(target.getIndexIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			)).thenReturn(groupRtei);

			for (final AffectedReferenceGroup group : affected.groups()) {
				if (group.groupPK() == null) {
					continue;
				}
				final int rgeiPk = nextStoragePk();
				final ReducedGroupEntityIndex rgei = createReducedGroupEntityIndex(rgeiPk, group.groupPK());
				groupRtei.insertPrimaryKeyIfMissing(rgeiPk, group.groupPK());

				for (final int ownerPK : group.ownerPKs()) {
					rgei.insertPrimaryKeyIfMissing(ownerPK, group.referencedEntityPK());
				}

				when(target.getIndexByPrimaryKeyIfExists(rgeiPk)).thenReturn(rgei);
			}
		} else {
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				nextStoragePk(), ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			);
			when(target.getIndexIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			)).thenReturn(rtei);

			for (final AffectedReferenceGroup group : affected.groups()) {
				final int reducedPk = nextStoragePk();
				final ReducedEntityIndex reducedIndex = createReducedEntityIndex(
					reducedPk, group.referencedEntityPK()
				);
				rtei.insertPrimaryKeyIfMissing(reducedPk, group.referencedEntityPK());

				for (final int ownerPK : group.ownerPKs()) {
					reducedIndex.insertPrimaryKeyIfMissing(ownerPK);
				}

				when(target.getIndexByPrimaryKeyIfExists(reducedPk)).thenReturn(reducedIndex);
			}
		}
	}

	// -- schema factory methods --

	/**
	 * Creates a real {@link ReferenceSchema} for a **grouped** reference with the specified index type.
	 *
	 * @param indexType the reference index type
	 * @return a real reference schema with group type set
	 */
	@Nonnull
	private static ReferenceSchema createRealReferenceSchema(@Nonnull ReferenceIndexType indexType) {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			REFERENCED_ENTITY_TYPE,
			true,
			Cardinality.ZERO_OR_MORE,
			GROUP_ENTITY_TYPE,
			true,
			new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.LIVE, indexType)},
			new Scope[]{Scope.LIVE}
		);
	}

	/**
	 * Creates a real {@link ReferenceSchema} for an **ungrouped** reference (no group type).
	 *
	 * @param indexType the reference index type
	 * @return a real reference schema without group type
	 */
	@Nonnull
	private static ReferenceSchema createRealReferenceSchemaUngrouped(@Nonnull ReferenceIndexType indexType) {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			REFERENCED_ENTITY_TYPE,
			true,
			Cardinality.ZERO_OR_MORE,
			null,
			false,
			new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.LIVE, indexType)},
			new Scope[]{Scope.LIVE}
		);
	}

	/**
	 * Creates a real {@link EntitySchema} containing the given reference schema.
	 *
	 * @param refSchema the reference schema to include
	 * @return a real entity schema
	 */
	@Nonnull
	private static EntitySchema createRealEntitySchema(@Nonnull ReferenceSchemaContract refSchema) {
		return EntitySchema._internalBuild(
			1, ENTITY_TYPE, null, null,
			false, false, null, false, null, 2,
			Collections.emptySet(), Collections.emptySet(),
			Collections.emptyMap(), Collections.emptyMap(),
			Map.of(REFERENCE_NAME, refSchema),
			Collections.emptySet(), Collections.emptyMap()
		);
	}

	// -- index factory methods --

	/**
	 * Creates a new {@link ReducedGroupEntityIndex} for the given group PK.
	 *
	 * @param storagePk the storage primary key for this index
	 * @param groupPK   the group primary key used in the discriminator
	 * @return a new empty RGEI
	 */
	@Nonnull
	private static ReducedGroupEntityIndex createReducedGroupEntityIndex(int storagePk, int groupPK) {
		return new ReducedGroupEntityIndex(
			storagePk, ENTITY_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(
					new ReferenceKey(REFERENCE_NAME, groupPK), new Serializable[0]
				)
			)
		);
	}

	/**
	 * Creates a new {@link ReducedEntityIndex} for an ungrouped reference.
	 *
	 * @param storagePk   the storage primary key for this index
	 * @param refEntityPK the referenced entity primary key used in the discriminator
	 * @return a new empty reduced entity index
	 */
	@Nonnull
	private static ReducedEntityIndex createReducedEntityIndex(int storagePk, int refEntityPK) {
		return new ReducedEntityIndex(
			storagePk, ENTITY_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(
					new ReferenceKey(REFERENCE_NAME, refEntityPK), new Serializable[0]
				)
			)
		);
	}

	/**
	 * Returns the next unique storage primary key.
	 *
	 * @return a unique integer for use as an index storage PK
	 */
	private int nextStoragePk() {
		return this.storagePkCounter++;
	}

	// -- collection helpers --

	/**
	 * Collects all entries from an iterable into a list.
	 *
	 * @param iterable the iterable to collect from
	 * @return list of collected entries
	 */
	@Nonnull
	private static List<AffectedReferenceEntry> collectEntries(
		@Nonnull Iterable<AffectedReferenceEntry> iterable
	) {
		final List<AffectedReferenceEntry> result = new ArrayList<>(8);
		for (final AffectedReferenceEntry entry : iterable) {
			result.add(entry);
		}
		return result;
	}

	/**
	 * Asserts that the entry has the expected field values.
	 *
	 * @param entry           the entry to verify
	 * @param expectedRefPK   expected referenced entity PK
	 * @param expectedGroupPK expected group PK (nullable)
	 * @param expectedOwnerPK expected owner PK
	 */
	private static void assertEntry(
		@Nonnull AffectedReferenceEntry entry,
		int expectedRefPK,
		@Nullable Integer expectedGroupPK,
		int expectedOwnerPK
	) {
		assertEquals(expectedRefPK, entry.referencedEntityPK());
		assertEquals(expectedGroupPK, entry.groupPK());
		assertEquals(expectedOwnerPK, entry.ownerPK());
	}

	// -- facet pre-seeding helper --

	/**
	 * Pre-seeds a facet into the global index so that `removeFacet` does not fail with an
	 * assertion error. In production, the facet was already indexed before the re-evaluation;
	 * this method simulates that prior state.
	 *
	 * @param globalIndex the real global index
	 * @param refSchema   the reference schema
	 * @param facetPK     the facet (referenced entity) primary key
	 * @param groupPK     the group primary key (nullable for ungrouped)
	 * @param ownerPK     the owner entity primary key
	 */
	private static void seedFacet(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull ReferenceSchemaContract refSchema,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		globalIndex.addFacet(
			refSchema, new ReferenceKey(REFERENCE_NAME, facetPK), groupPK, ownerPK
		);
	}

	// -- facet state assertion helpers --

	/**
	 * Asserts that a facet is present in the global index for the given owner PK.
	 *
	 * @param globalIndex   the real global index to inspect
	 * @param referenceName the reference name
	 * @param groupPK       the group PK (nullable for ungrouped)
	 * @param facetPK       the facet (referenced entity) PK
	 * @param ownerPK       the owner entity PK that should be indexed
	 */
	private static void assertFacetPresent(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull String referenceName,
		@Nullable Integer groupPK,
		int facetPK,
		int ownerPK
	) {
		final Map<String, FacetReferenceIndex> facetingEntities = globalIndex.getFacetingEntities();
		final FacetReferenceIndex refIndex = facetingEntities.get(referenceName);
		assertNotNull(refIndex, "Expected FacetReferenceIndex for reference '" + referenceName + "'");

		final FacetGroupIndex groupIndex;
		if (groupPK != null) {
			groupIndex = findGroupedFacetIndex(refIndex, groupPK);
			assertNotNull(groupIndex,
				"Expected FacetGroupIndex for group PK " + groupPK);
		} else {
			groupIndex = refIndex.getNotGroupedFacets();
			assertNotNull(groupIndex, "Expected ungrouped FacetGroupIndex");
		}

		final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(facetPK);
		assertNotNull(facetIdIndex,
			"Expected FacetIdIndex for facet PK " + facetPK + " in group " + groupPK);
		assertTrue(facetIdIndex.getRecords().contains(ownerPK),
			"Expected owner PK " + ownerPK + " in facet " + facetPK + " of group " + groupPK);
	}

	/**
	 * Asserts that a facet is NOT present in the global index for the given owner PK. The facet
	 * group or facet ID may not exist at all, or the owner PK may be absent from the records.
	 *
	 * @param globalIndex   the real global index to inspect
	 * @param referenceName the reference name
	 * @param facetPK       the facet (referenced entity) PK
	 * @param ownerPK       the owner entity PK that should NOT be indexed
	 */
	private static void assertFacetAbsent(
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull String referenceName,
		int facetPK,
		int ownerPK
	) {
		final Map<String, FacetReferenceIndex> facetingEntities = globalIndex.getFacetingEntities();
		final FacetReferenceIndex refIndex = facetingEntities.get(referenceName);
		if (refIndex == null) {
			return; // no facets at all — absence confirmed
		}

		// check all group indexes for this facet
		for (final FacetGroupIndex groupIndex : refIndex.getGroupedFacets()) {
			final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(facetPK);
			if (facetIdIndex != null) {
				assertFalse(facetIdIndex.getRecords().contains(ownerPK),
					"Owner PK " + ownerPK + " should NOT be present in facet " + facetPK);
			}
		}
		final FacetGroupIndex notGrouped = refIndex.getNotGroupedFacets();
		if (notGrouped != null) {
			final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(facetPK);
			if (facetIdIndex != null) {
				assertFalse(facetIdIndex.getRecords().contains(ownerPK),
					"Owner PK " + ownerPK + " should NOT be present in ungrouped facet " + facetPK);
			}
		}
	}

	/**
	 * Asserts that no facets have been indexed at all in the global index.
	 *
	 * @param globalIndex the real global index to inspect
	 */
	private static void assertNoFacets(@Nonnull GlobalEntityIndex globalIndex) {
		final Map<String, FacetReferenceIndex> facetingEntities = globalIndex.getFacetingEntities();
		assertTrue(facetingEntities.isEmpty(),
			"Expected no faceting entities, but found: " + facetingEntities.keySet());
	}

	/**
	 * Finds a {@link FacetGroupIndex} with the given group ID among the grouped facets.
	 *
	 * @param refIndex the facet reference index to search
	 * @param groupPK  the group primary key to find
	 * @return the matching group index, or null if not found
	 */
	@Nullable
	private static FacetGroupIndex findGroupedFacetIndex(
		@Nonnull FacetReferenceIndex refIndex,
		int groupPK
	) {
		for (final FacetGroupIndex groupIndex : refIndex.getGroupedFacets()) {
			if (groupIndex.getGroupId() == groupPK) {
				return groupIndex;
			}
		}
		return null;
	}

	// -- filter assertion helpers --

	/**
	 * Asserts that a {@link ReferenceHaving} constraint contains a {@link GroupHaving} clause
	 * with an {@link EntityPrimaryKeyInSet} constraint matching the given group PK.
	 *
	 * @param rh      the reference having constraint to inspect
	 * @param groupPK the expected group primary key
	 */
	private static void assertContainsGroupHavingWithPK(@Nonnull ReferenceHaving rh, int groupPK) {
		assertTrue(
			containsGroupHavingWithPK(rh, groupPK),
			"ReferenceHaving should contain GroupHaving with entityPrimaryKeyInSet(" + groupPK + ")"
		);
	}

	/**
	 * Asserts that a {@link ReferenceHaving} constraint contains an {@link EntityHaving} clause
	 * with an {@link EntityPrimaryKeyInSet} constraint matching the given entity PK.
	 *
	 * @param rh       the reference having constraint to inspect
	 * @param entityPK the expected entity primary key
	 */
	private static void assertContainsEntityHavingWithPK(@Nonnull ReferenceHaving rh, int entityPK) {
		assertTrue(
			containsEntityHavingWithPK(rh, entityPK),
			"ReferenceHaving should contain EntityHaving with entityPrimaryKeyInSet(" + entityPK + ")"
		);
	}

	/**
	 * Checks whether a {@link ReferenceHaving} contains a {@link GroupHaving} with an
	 * {@link EntityPrimaryKeyInSet} constraint matching the given PK.
	 *
	 * @param rh      the constraint to inspect
	 * @param groupPK the expected PK value
	 * @return true if found
	 */
	private static boolean containsGroupHavingWithPK(@Nonnull ReferenceHaving rh, int groupPK) {
		for (final FilterConstraint child : rh.getChildren()) {
			if (child instanceof GroupHaving gh) {
				if (containsPKInSet(gh.getChildren(), groupPK)) {
					return true;
				}
			} else if (child instanceof And and) {
				for (final FilterConstraint andChild : and.getChildren()) {
					if (andChild instanceof GroupHaving gh) {
						if (containsPKInSet(gh.getChildren(), groupPK)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Checks whether a {@link ReferenceHaving} contains an {@link EntityHaving} with an
	 * {@link EntityPrimaryKeyInSet} constraint matching the given PK.
	 *
	 * @param rh       the constraint to inspect
	 * @param entityPK the expected PK value
	 * @return true if found
	 */
	private static boolean containsEntityHavingWithPK(@Nonnull ReferenceHaving rh, int entityPK) {
		for (final FilterConstraint child : rh.getChildren()) {
			if (child instanceof EntityHaving eh) {
				if (containsPKInSet(eh.getChildren(), entityPK)) {
					return true;
				}
			} else if (child instanceof And and) {
				for (final FilterConstraint andChild : and.getChildren()) {
					if (andChild instanceof EntityHaving eh) {
						if (containsPKInSet(eh.getChildren(), entityPK)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Recursively checks whether the given constraint children contain an
	 * {@link EntityPrimaryKeyInSet} with the specified PK value.
	 *
	 * @param children the constraints to inspect
	 * @param pk       the expected PK value
	 * @return true if an {@link EntityPrimaryKeyInSet} containing the PK is found
	 */
	private static boolean containsPKInSet(@Nonnull FilterConstraint[] children, int pk) {
		for (final FilterConstraint child : children) {
			if (child instanceof EntityPrimaryKeyInSet pkSet) {
				for (final int foundPK : pkSet.getPrimaryKeys()) {
					if (foundPK == pk) {
						return true;
					}
				}
			} else if (child instanceof And and) {
				if (containsPKInSet(and.getChildren(), pk)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Stub implementation of {@link FacetExpressionTrigger} that returns pre-configured
	 * filter and dependency metadata. Used instead of Mockito mocks for the trigger to
	 * maintain real object behavior for the constraint tree operations.
	 */
	private static class StubFacetTrigger implements FacetExpressionTrigger {
		private final String referenceName;
		private final FilterBy filterBy;
		private final DependencyType dependencyType;

		/**
		 * @param referenceName name of the reference
		 * @param filterBy      the pre-translated filter, or null for unconditional triggers
		 * @param dependencyType the dependency type
		 */
		StubFacetTrigger(
			@Nonnull String referenceName,
			@Nullable FilterBy filterBy,
			@Nonnull DependencyType dependencyType
		) {
			this.referenceName = referenceName;
			this.filterBy = filterBy;
			this.dependencyType = dependencyType;
		}

		@Nonnull
		@Override
		public String getOwnerEntityType() {
			return ENTITY_TYPE;
		}

		@Nonnull
		@Override
		public String getReferenceName() {
			return this.referenceName;
		}

		@Nonnull
		@Override
		public Scope getScope() {
			return Scope.LIVE;
		}

		@Nullable
		@Override
		public String getMutatedEntityType() {
			return REFERENCED_ENTITY_TYPE;
		}

		@Nullable
		@Override
		public DependencyType getDependencyType() {
			return this.dependencyType;
		}

		@Nullable
		@Override
		public String getDependentReferenceName() {
			return null;
		}

		@Nonnull
		@Override
		public Set<String> getDependentAttributes() {
			return Set.of();
		}

		@Override
		public boolean hasFilterByConstraint() {
			return this.filterBy != null;
		}

		@Nonnull
		@Override
		public FilterBy getFilterByConstraint() {
			if (this.filterBy == null) {
				throw new UnsupportedOperationException("Unconditional trigger has no FilterBy");
			}
			return this.filterBy;
		}

		@Override
		public boolean evaluate(
			int ownerEntityPK,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
			@Nonnull Function<String, EntitySchemaContract> schemaResolver,
			@Nonnull Scope scope
		) {
			throw new UnsupportedOperationException("Per-entity evaluation not used in cross-entity tests");
		}
	}
}
