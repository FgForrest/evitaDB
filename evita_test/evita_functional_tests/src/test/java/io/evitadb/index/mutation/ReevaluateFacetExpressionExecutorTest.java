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
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReevaluateFacetExpressionExecutor} verifying the full execute pipeline: affected entity resolution,
 * FilterBy parameterization, bitmap set operations (add/remove split), and target index routing (global vs. reduced).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReevaluateFacetExpressionExecutor")
class ReevaluateFacetExpressionExecutorTest implements TimeBoundedTestSupport {

	private static final String REFERENCE_NAME = "testRef";
	private static final int MUTATED_ENTITY_PK = 42;
	private static final Scope TEST_SCOPE = Scope.LIVE;

	private ReevaluateFacetExpressionExecutor executor;
	private IndexMutationTarget target;
	private EntitySchema entitySchema;
	private ReferenceSchemaContract refSchema;
	private EntityIndex globalIndex;
	private ExpressionIndexTrigger trigger;

	@BeforeEach
	void setUp() {
		this.executor = new ReevaluateFacetExpressionExecutor();
		this.target = mock(IndexMutationTarget.class);
		this.entitySchema = mock(EntitySchema.class);
		this.refSchema = mock(ReferenceSchemaContract.class);
		this.globalIndex = mock(EntityIndex.class);
		this.trigger = mock(ExpressionIndexTrigger.class);

		when(this.target.getEntitySchema()).thenReturn(this.entitySchema);
	}

	/**
	 * Creates a baseline trigger FilterBy: `filterBy(referenceHaving("testRef", entityPrimaryKeyInSet(1)))`.
	 *
	 * @return the baseline FilterBy for testing
	 */
	@Nonnull
	private static FilterBy createTriggerFilterBy() {
		return new FilterBy(new ReferenceHaving(REFERENCE_NAME, new EntityPrimaryKeyInSet(1)));
	}

	/**
	 * Configures mocks for the GROUP_ENTITY_ATTRIBUTE resolution path: ReferencedTypeEntityIndex lookup,
	 * storagePK resolution, and ReducedGroupEntityIndex with facetPKs and owner bitmaps.
	 *
	 * @param storagePKs   storage primary keys returned by the ReferencedTypeEntityIndex
	 * @param facetPKs     facet primary keys for each ReducedGroupEntityIndex (parallel with storagePKs)
	 * @param ownerBitmaps owner PK bitmaps for each (storagePK, facetPK) pair (parallel with facetPKs)
	 */
	private void setupGroupEntityAttributeScenario(
		@Nonnull int[] storagePKs,
		@Nonnull int[][] facetPKs,
		@Nonnull Bitmap[][] ownerBitmaps
	) {
		final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
		final EntityIndexKey groupTypeKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
		);
		when(this.target.getIndexIfExists(groupTypeKey)).thenReturn(rtei);
		when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(storagePKs);

		for (int i = 0; i < storagePKs.length; i++) {
			final ReducedGroupEntityIndex rgei = mock(ReducedGroupEntityIndex.class);
			when(this.target.getIndexByPrimaryKeyIfExists(storagePKs[i])).thenReturn(rgei);

			final Set<Integer> facetPKSet = new java.util.LinkedHashSet<>();
			for (int fpk : facetPKs[i]) {
				facetPKSet.add(fpk);
			}
			when(rgei.getReferencedEntityPrimaryKeys()).thenReturn(facetPKSet);

			for (int j = 0; j < facetPKs[i].length; j++) {
				when(rgei.getOwnerPKsForReferencedEntity(facetPKs[i][j])).thenReturn(ownerBitmaps[i][j]);
			}
		}
	}

	/**
	 * Configures mocks for the REFERENCED_ENTITY_ATTRIBUTE resolution path: ReferencedTypeEntityIndex lookup,
	 * storagePK resolution, EntityIndex with getAllPrimaryKeys, and optional FacetReferenceIndex for group PK.
	 *
	 * @param storagePKs   storage primary keys returned by the ReferencedTypeEntityIndex
	 * @param ownerBitmaps owner PK bitmaps for each reduced EntityIndex (parallel with storagePKs)
	 * @param groupPK      the group PK to configure via FacetReferenceIndex, or null for ungrouped
	 */
	private void setupReferencedEntityAttributeScenario(
		@Nonnull int[] storagePKs,
		@Nonnull Bitmap[] ownerBitmaps,
		@Nullable Integer groupPK
	) {
		final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
		final EntityIndexKey refTypeKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
		);
		when(this.target.getIndexIfExists(refTypeKey)).thenReturn(rtei);
		when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(storagePKs);

		for (int i = 0; i < storagePKs.length; i++) {
			final EntityIndex reducedIndex = mock(EntityIndex.class);
			when(this.target.getIndexByPrimaryKeyIfExists(storagePKs[i])).thenReturn(reducedIndex);
			when(reducedIndex.getAllPrimaryKeys()).thenReturn(ownerBitmaps[i]);
		}

		// configure FacetReferenceIndex on global index for group PK resolution
		if (groupPK != null) {
			final FacetReferenceIndex facetRefIndex = new FacetReferenceIndex(REFERENCE_NAME);
			// add a facet so getGroupIdForFacet returns the expected groupPK
			facetRefIndex.addFacet(MUTATED_ENTITY_PK, groupPK, 999);
			when(this.globalIndex.getFacetingEntities()).thenReturn(Map.of(REFERENCE_NAME, facetRefIndex));
		} else {
			when(this.globalIndex.getFacetingEntities()).thenReturn(Map.of());
		}
	}

	/**
	 * Configures the global EntityIndex, EntitySchema reference lookup, reference schema, and trigger on the target.
	 *
	 * @param indexType the {@link ReferenceIndexType} to return from the reference schema
	 */
	private void setupGlobalIndexAndSchema(@Nonnull ReferenceIndexType indexType) {
		final EntityIndexKey globalKey = new EntityIndexKey(EntityIndexType.GLOBAL, TEST_SCOPE);
		when(this.target.getIndexIfExists(globalKey)).thenReturn(this.globalIndex);
		when(this.entitySchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(this.refSchema));
		when(this.refSchema.getReferenceIndexType(TEST_SCOPE)).thenReturn(indexType);

		when(this.target.getTrigger(REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE))
			.thenReturn(this.trigger);
		when(this.target.getTrigger(REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE))
			.thenReturn(this.trigger);
		when(this.trigger.getFilterByConstraint()).thenReturn(createTriggerFilterBy());
	}

	/**
	 * Tests verifying early return conditions where the executor short-circuits without performing any facet operations
	 * or filter evaluation.
	 */
	@Nested
	@DisplayName("Early returns")
	class EarlyReturnsTest {

		@Test
		@DisplayName("Should return early when no affected entities exist")
		void shouldReturnEarlyWhenNoAffectedEntities() {
			// setup GROUP_ENTITY_ATTRIBUTE with RTEI returning empty storagePKs
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(groupTypeKey)).thenReturn(rtei);
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[0]);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}

		@Test
		@DisplayName("Should return early when trigger is null")
		void shouldReturnEarlyWhenTriggerIsNull() {
			// setup GROUP_ENTITY_ATTRIBUTE with affected entities but null trigger
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getTrigger(REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE))
				.thenReturn(null);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}

		@Test
		@DisplayName("Should return early when dependency type is unsupported")
		void shouldReturnEarlyWhenDependencyTypeIsUnsupported() {
			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}

		/**
		 * Verifies that when `target.getIndexIfExists()` returns null for the
		 * REFERENCED_GROUP_ENTITY_TYPE key, the executor returns EMPTY resolution.
		 */
		@Test
		@DisplayName("Should return early when RTEI is null for GROUP_ENTITY_ATTRIBUTE")
		void shouldReturnEarlyWhenRteiIsNullForGroupEntityAttribute() {
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(groupTypeKey)).thenReturn(null);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}

		/**
		 * Verifies that when `target.getIndexIfExists()` returns null for the
		 * REFERENCED_ENTITY_TYPE key, the executor returns EMPTY resolution.
		 */
		@Test
		@DisplayName("Should return early when RTEI is null for REFERENCED_ENTITY_ATTRIBUTE")
		void shouldReturnEarlyWhenRteiIsNullForReferencedEntityAttribute() {
			final EntityIndexKey refTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(refTypeKey)).thenReturn(null);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}
	}

	/**
	 * Tests verifying the GROUP_ENTITY_ATTRIBUTE resolution path through ReferencedTypeEntityIndex and
	 * ReducedGroupEntityIndex.
	 */
	@Nested
	@DisplayName("GROUP_ENTITY_ATTRIBUTE resolution")
	class GroupEntityAttributeResolutionTest {

		@Test
		@DisplayName("Should resolve affected entities through group index and perform facet operations")
		void shouldResolveAffectedEntitiesThroughGroupIndex() {
			// facetPK=5, ownerPKs={10,20}; truePKs={10} => add 10, remove 20
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10, 20)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
		}

		@Test
		@DisplayName("Should skip null reduced group index without error")
		void shouldSkipNullReducedGroupIndex() {
			// RTEI returns storagePKs but getIndexByPrimaryKeyIfExists returns null
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(groupTypeKey)).thenReturn(rtei);
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[]{100});
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(100)).thenReturn(null);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			// no affected entities => no evaluateFilter, no facet ops
			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).addFacet(any(), any(), any(), anyInt());
		}

		/**
		 * Verifies that when a single `ReducedGroupEntityIndex` contains multiple facet PKs (e.g., facetPK=5
		 * and facetPK=7), both produce `AffectedFacetGroup` entries and both receive add/remove operations.
		 */
		@Test
		@DisplayName("Should handle multiple facet PKs in a single ReducedGroupEntityIndex")
		void shouldHandleMultipleFacetPKsInSingleReducedGroupIndex() {
			// one RGEI (storagePK=100) with two facetPKs: 5 and 7
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5, 7}},
				new Bitmap[][]{{new BaseBitmap(10), new BaseBitmap(20)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// PK 10 matches (facet 5), PK 20 does not match (facet 7) => add 10, remove 20
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey refKey5 = new ReferenceKey(REFERENCE_NAME, 5);
			final ReferenceKey refKey7 = new ReferenceKey(REFERENCE_NAME, 7);

			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(refKey5), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(refKey7), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
		}

		@Test
		@DisplayName("Should skip non-ReducedGroupEntityIndex without error")
		void shouldSkipNonGroupEntityIndex() {
			// storagePK maps to a regular EntityIndex (not ReducedGroupEntityIndex)
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(groupTypeKey)).thenReturn(rtei);
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[]{100});
			// return a plain EntityIndex mock (not ReducedGroupEntityIndex)
			final EntityIndex plainIndex = mock(EntityIndex.class);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(100)).thenReturn(plainIndex);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			// non-RGEI is skipped => no affected entities => no evaluateFilter
			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}
	}

	/**
	 * Tests verifying the REFERENCED_ENTITY_ATTRIBUTE resolution path through ReferencedTypeEntityIndex and
	 * EntityIndex with getAllPrimaryKeys.
	 */
	@Nested
	@DisplayName("REFERENCED_ENTITY_ATTRIBUTE resolution")
	class ReferencedEntityAttributeResolutionTest {

		@Test
		@DisplayName("Should resolve affected entities through referenced entity index with group PK from FacetReferenceIndex")
		void shouldResolveAffectedEntitiesThroughReferencedEntityIndex() {
			final int groupPK = 99;
			setupReferencedEntityAttributeScenario(
				new int[]{200},
				new Bitmap[]{new BaseBitmap(10, 20)},
				groupPK
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(groupPK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(groupPK)), eq(20)
			);
		}

		@Test
		@DisplayName("Should resolve null group PK for ungrouped facet")
		void shouldResolveNullGroupPKForUngroupedFacet() {
			// no FacetReferenceIndex => groupPK is null
			setupReferencedEntityAttributeScenario(
				new int[]{200},
				new Bitmap[]{new BaseBitmap(10)},
				null
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK);
			// groupId should be null for ungrouped facet
			verify(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), isNull(), eq(10));
		}

		/**
		 * Verifies that when `target.getIndexByPrimaryKeyIfExists()` returns null during
		 * REFERENCED_ENTITY_ATTRIBUTE resolution, that storagePK is skipped without error.
		 */
		@Test
		@DisplayName("Should skip null reduced index in REFERENCED_ENTITY_ATTRIBUTE path")
		void shouldSkipNullReducedIndexInReferencedEntityPath() {
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey refTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(refTypeKey)).thenReturn(rtei);
			// RTEI returns two storagePKs; the first maps to null, the second to a valid index
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[]{200, 201});
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(200)).thenReturn(null);

			final EntityIndex validReducedIndex = mock(EntityIndex.class);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(201)).thenReturn(validReducedIndex);
			when(validReducedIndex.getAllPrimaryKeys()).thenReturn(new BaseBitmap(10));

			// global index for group PK resolution
			final EntityIndexKey globalKey = new EntityIndexKey(EntityIndexType.GLOBAL, TEST_SCOPE);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(globalKey)).thenReturn(ReevaluateFacetExpressionExecutorTest.this.globalIndex);
			when(ReevaluateFacetExpressionExecutorTest.this.globalIndex.getFacetingEntities()).thenReturn(Map.of());

			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			// should not throw even though storagePK=200 maps to null
			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK);
			verify(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), isNull(), eq(10));
		}

		/**
		 * Verifies that when RTEI returns multiple storagePKs, each contributes owner PKs
		 * to the resolution and all owners get facet operations.
		 */
		@Test
		@DisplayName("Should resolve owner PKs from multiple storagePKs in REFERENCED_ENTITY_ATTRIBUTE path")
		void shouldResolveOwnerPKsFromMultipleStoragePKs() {
			final int groupPK = 99;
			setupReferencedEntityAttributeScenario(
				new int[]{200, 201},
				new Bitmap[]{new BaseBitmap(10), new BaseBitmap(20)},
				groupPK
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// both PKs match evaluation => both should be added
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10, 20));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(groupPK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(groupPK)), eq(20)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, times(2)).addFacet(any(), any(), any(), anyInt());
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).removeFacet(any(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("Should skip reduced index with empty primary keys")
		void shouldSkipReducedIndexWithEmptyPrimaryKeys() {
			// RTEI returns storagePK but the reduced index has no primary keys
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey refTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(refTypeKey)).thenReturn(rtei);
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[]{200});

			final EntityIndex emptyIndex = mock(EntityIndex.class);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(200)).thenReturn(emptyIndex);
			when(emptyIndex.getAllPrimaryKeys()).thenReturn(new BaseBitmap());

			// global index for group PK resolution
			final EntityIndexKey globalKey = new EntityIndexKey(EntityIndexType.GLOBAL, TEST_SCOPE);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(globalKey)).thenReturn(ReevaluateFacetExpressionExecutorTest.this.globalIndex);
			when(ReevaluateFacetExpressionExecutorTest.this.globalIndex.getFacetingEntities()).thenReturn(Map.of());

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			// empty ownerPKs => no affected => no evaluateFilter
			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).evaluateFilter(any());
		}
	}

	/**
	 * Tests verifying bitmap AND / ANDNOT operations that split affected entities into add and remove sets
	 * based on the evaluation result.
	 */
	@Nested
	@DisplayName("Bitmap set operations")
	class BitmapSetOperationsTest {

		@Test
		@DisplayName("Should add all affected when all match evaluation")
		void shouldAddAllAffectedWhenAllMatchEvaluation() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10, 20)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// all affected PKs are in truePKs
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10, 20));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).removeFacet(any(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("Should remove all affected when none match evaluation")
		void shouldRemoveAllAffectedWhenNoneMatchEvaluation() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10, 20)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// no affected PKs in truePKs
			when(ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap());

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).addFacet(any(), any(), any(), anyInt());
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
		}

		@Test
		@DisplayName("Should split affected into adds and removes based on evaluation")
		void shouldSplitAffectedIntoAddsAndRemoves() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10, 20, 30)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// only 10 and 30 match
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10, 30));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(30)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, times(2)).addFacet(any(), any(), any(), anyInt());
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, times(1)).removeFacet(any(), any(), any(), anyInt());
		}
	}

	/**
	 * Tests verifying index routing: global-only vs. global + reduced entity index targeting based on
	 * the reference schema's {@link ReferenceIndexType}.
	 */
	@Nested
	@DisplayName("Target index routing")
	class TargetIndexRoutingTest {

		@Test
		@DisplayName("Should target only global index when reference index type is FILTERING")
		void shouldTargetOnlyGlobalIndexWhenFilteringOnly() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			// global index should be used
			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);

			// no lookup for REFERENCED_ENTITY_TYPE (reduced indexes) should happen for FOR_FILTERING
			final EntityIndexKey refEntityTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.target, never()).getIndexIfExists(refEntityTypeKey);
		}

		/**
		 * Verifies that when `FOR_FILTERING_AND_PARTITIONING` is active and entities should be REMOVED,
		 * `removeFacet` is called on both the global AND reduced indexes.
		 */
		@Test
		@DisplayName("Should call removeFacet on both global and reduced index when REMOVE path is taken")
		void shouldRemoveFacetOnBothGlobalAndReducedIndex() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);

			// setup REFERENCED_ENTITY_TYPE index for reduced targeting
			final ReferencedTypeEntityIndex refTypeRtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey refEntityTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(refEntityTypeKey)).thenReturn(refTypeRtei);

			// reduced index for facetPK=5
			final EntityIndex reducedIndex = mock(EntityIndex.class);
			when(refTypeRtei.getAllReferenceIndexes(5)).thenReturn(new int[]{300});
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(300)).thenReturn(reducedIndex);

			// no PKs match evaluation => all should be removed
			when(ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap());

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			// both global and reduced should receive removeFacet
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(reducedIndex).removeFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			// no addFacet calls
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).addFacet(any(), any(), any(), anyInt());
			verify(reducedIndex, never()).addFacet(any(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("Should target both global and reduced index when FOR_FILTERING_AND_PARTITIONING")
		void shouldTargetGlobalAndReducedWhenFilteringAndPartitioning() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);

			// setup REFERENCED_ENTITY_TYPE index for reduced targeting
			final ReferencedTypeEntityIndex refTypeRtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey refEntityTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(refEntityTypeKey)).thenReturn(refTypeRtei);

			// reduced index for facetPK=5
			final EntityIndex reducedIndex = mock(EntityIndex.class);
			when(refTypeRtei.getAllReferenceIndexes(5)).thenReturn(new int[]{300});
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(300)).thenReturn(reducedIndex);

			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ReferenceKey expectedRefKey = new ReferenceKey(REFERENCE_NAME, 5);
			// both global and reduced should receive addFacet
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(reducedIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(expectedRefKey), eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
		}
	}

	/**
	 * Tests verifying that the FilterBy constraint is correctly parameterized with PK-scoping clauses
	 * depending on the dependency type.
	 */
	@Nested
	@DisplayName("FilterBy parameterization")
	class FilterByParameterizationTest {

		@Test
		@DisplayName("Should inject GroupHaving for GROUP_ENTITY_ATTRIBUTE")
		void shouldInjectGroupHavingForGroupEntityAttribute() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(ReevaluateFacetExpressionExecutorTest.this.target).evaluateFilter(filterCaptor.capture());

			final FilterBy capturedFilter = filterCaptor.getValue();
			final FilterConstraint[] topChildren = capturedFilter.getChildren();
			assertEquals(1, topChildren.length, "FilterBy should have one top-level child");
			assertInstanceOf(ReferenceHaving.class, topChildren[0]);

			final ReferenceHaving rh = (ReferenceHaving) topChildren[0];
			assertEquals(REFERENCE_NAME, rh.getReferenceName());

			// the ReferenceHaving should have an And wrapping the original child + GroupHaving
			final FilterConstraint[] rhChildren = rh.getChildren();
			assertEquals(1, rhChildren.length, "ReferenceHaving should have one child (And)");
			assertInstanceOf(And.class, rhChildren[0]);

			final And andConstraint = (And) rhChildren[0];
			final FilterConstraint[] andChildren = andConstraint.getChildren();
			assertEquals(2, andChildren.length, "And should have original child + GroupHaving");

			// original child: EntityPrimaryKeyInSet(1)
			assertInstanceOf(EntityPrimaryKeyInSet.class, andChildren[0]);

			// injected: GroupHaving(EntityPrimaryKeyInSet(42))
			assertInstanceOf(GroupHaving.class, andChildren[1]);
			final GroupHaving groupHaving = (GroupHaving) andChildren[1];
			final FilterConstraint[] ghChildren = groupHaving.getChildren();
			assertEquals(1, ghChildren.length);
			assertInstanceOf(EntityPrimaryKeyInSet.class, ghChildren[0]);
			final EntityPrimaryKeyInSet pkInSet = (EntityPrimaryKeyInSet) ghChildren[0];
			assertArrayEquals(new int[]{MUTATED_ENTITY_PK}, pkInSet.getPrimaryKeys());
		}

		@Test
		@DisplayName("Should inject EntityHaving for REFERENCED_ENTITY_ATTRIBUTE")
		void shouldInjectEntityHavingForReferencedEntityAttribute() {
			setupReferencedEntityAttributeScenario(
				new int[]{200},
				new Bitmap[]{new BaseBitmap(10)},
				99
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(ReevaluateFacetExpressionExecutorTest.this.target).evaluateFilter(filterCaptor.capture());

			final FilterBy capturedFilter = filterCaptor.getValue();
			final FilterConstraint[] topChildren = capturedFilter.getChildren();
			assertEquals(1, topChildren.length, "FilterBy should have one top-level child");
			assertInstanceOf(ReferenceHaving.class, topChildren[0]);

			final ReferenceHaving rh = (ReferenceHaving) topChildren[0];
			final FilterConstraint[] rhChildren = rh.getChildren();
			assertEquals(1, rhChildren.length, "ReferenceHaving should have one child (And)");
			assertInstanceOf(And.class, rhChildren[0]);

			final And andConstraint = (And) rhChildren[0];
			final FilterConstraint[] andChildren = andConstraint.getChildren();
			assertEquals(2, andChildren.length, "And should have original child + EntityHaving");

			// original child: EntityPrimaryKeyInSet(1)
			assertInstanceOf(EntityPrimaryKeyInSet.class, andChildren[0]);

			// injected: EntityHaving(EntityPrimaryKeyInSet(42))
			assertInstanceOf(EntityHaving.class, andChildren[1]);
			final EntityHaving entityHaving = (EntityHaving) andChildren[1];
			final FilterConstraint[] ehChildren = entityHaving.getChildren();
			assertEquals(1, ehChildren.length);
			assertInstanceOf(EntityPrimaryKeyInSet.class, ehChildren[0]);
			final EntityPrimaryKeyInSet pkInSet = (EntityPrimaryKeyInSet) ehChildren[0];
			assertArrayEquals(new int[]{MUTATED_ENTITY_PK}, pkInSet.getPrimaryKeys());
		}

		/**
		 * Verifies that when the FilterBy contains a ReferenceHaving for a DIFFERENT reference name,
		 * only the matching reference name gets the injected scope; the non-matching one is left unchanged.
		 */
		@Test
		@DisplayName("Should leave non-matching ReferenceHaving unchanged")
		void shouldLeaveNonMatchingReferenceHavingUnchanged() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new Bitmap[][]{{new BaseBitmap(10)}}
			);
			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);

			// override the trigger FilterBy AFTER setupGlobalIndexAndSchema to use two ReferenceHaving children
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving("otherRef", new EntityPrimaryKeyInSet(99)),
				new ReferenceHaving(REFERENCE_NAME, new EntityPrimaryKeyInSet(1))
			);
			when(ReevaluateFacetExpressionExecutorTest.this.trigger.getFilterByConstraint()).thenReturn(triggerFilter);

			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			final ArgumentCaptor<FilterBy> filterCaptor = ArgumentCaptor.forClass(FilterBy.class);
			verify(ReevaluateFacetExpressionExecutorTest.this.target).evaluateFilter(filterCaptor.capture());

			final FilterBy capturedFilter = filterCaptor.getValue();
			final FilterConstraint[] topChildren = capturedFilter.getChildren();
			assertEquals(2, topChildren.length, "FilterBy should have two top-level children");

			// first child ("otherRef") should be unchanged — still plain ReferenceHaving
			assertInstanceOf(ReferenceHaving.class, topChildren[0]);
			final ReferenceHaving otherRh = (ReferenceHaving) topChildren[0];
			assertEquals("otherRef", otherRh.getReferenceName());
			final FilterConstraint[] otherRhChildren = otherRh.getChildren();
			// "otherRef" should NOT have an And wrapper — still just the original child
			assertEquals(1, otherRhChildren.length);
			assertInstanceOf(EntityPrimaryKeyInSet.class, otherRhChildren[0]);

			// second child (REFERENCE_NAME) should have the injected scope
			assertInstanceOf(ReferenceHaving.class, topChildren[1]);
			final ReferenceHaving matchingRh = (ReferenceHaving) topChildren[1];
			assertEquals(REFERENCE_NAME, matchingRh.getReferenceName());
			final FilterConstraint[] matchingRhChildren = matchingRh.getChildren();
			assertEquals(1, matchingRhChildren.length);
			assertInstanceOf(And.class, matchingRhChildren[0]);
		}
	}

	/**
	 * Tests verifying idempotency and edge cases including multiple groups in a single execution and
	 * missing FacetReferenceIndex.
	 */
	@Nested
	@DisplayName("Idempotency and edge cases")
	class IdempotencyAndEdgeCasesTest {

		@Test
		@DisplayName("Should handle multiple groups in single execution")
		void shouldHandleMultipleGroupsInSingleExecution() {
			// two ReducedGroupEntityIndexes: storagePK=100 has facetPK=5 with owners {10},
			// storagePK=101 has facetPK=7 with owners {20}
			final ReferencedTypeEntityIndex rtei = mock(ReferencedTypeEntityIndex.class);
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexIfExists(groupTypeKey)).thenReturn(rtei);
			when(rtei.getAllReferenceIndexes(MUTATED_ENTITY_PK)).thenReturn(new int[]{100, 101});

			final ReducedGroupEntityIndex rgei1 = mock(ReducedGroupEntityIndex.class);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(100)).thenReturn(rgei1);
			when(rgei1.getReferencedEntityPrimaryKeys()).thenReturn(Set.of(5));
			when(rgei1.getOwnerPKsForReferencedEntity(5)).thenReturn(new BaseBitmap(10));

			final ReducedGroupEntityIndex rgei2 = mock(ReducedGroupEntityIndex.class);
			when(ReevaluateFacetExpressionExecutorTest.this.target.getIndexByPrimaryKeyIfExists(101)).thenReturn(rgei2);
			when(rgei2.getReferencedEntityPrimaryKeys()).thenReturn(Set.of(7));
			when(rgei2.getOwnerPKsForReferencedEntity(7)).thenReturn(new BaseBitmap(20));

			setupGlobalIndexAndSchema(ReferenceIndexType.FOR_FILTERING);
			// all affected PKs match
			when(
				ReevaluateFacetExpressionExecutorTest.this.target.evaluateFilter(any(FilterBy.class))).thenReturn(new BaseBitmap(10, 20));

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(mutation, ReevaluateFacetExpressionExecutorTest.this.target);

			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(new ReferenceKey(REFERENCE_NAME, 5)),
				eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(10)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex).addFacet(
				eq(ReevaluateFacetExpressionExecutorTest.this.refSchema), eq(new ReferenceKey(REFERENCE_NAME, 7)),
				eq(Integer.valueOf(MUTATED_ENTITY_PK)), eq(20)
			);
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, times(2)).addFacet(any(), any(), any(), anyInt());
			verify(ReevaluateFacetExpressionExecutorTest.this.globalIndex, never()).removeFacet(any(), any(), any(), anyInt());
		}

	}
}
