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
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
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
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReevaluateFacetExpressionExecutor} verifying the full execute pipeline: affected entity resolution,
 * FilterBy parameterization, bitmap set operations (add/remove split), and target index routing (global vs. reduced).
 *
 * Uses real production index instances ({@link GlobalEntityIndex}, {@link ReferencedTypeEntityIndex},
 * {@link ReducedGroupEntityIndex}) and test doubles ({@link TestIndexMutationTarget},
 * {@link TestExpressionIndexTrigger}) instead of Mockito mocks — state-based assertions verify the actual facet
 * index contents after execution.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReevaluateFacetExpressionExecutor")
class ReevaluateFacetExpressionExecutorTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "testRef";
	private static final String REFERENCED_ENTITY_TYPE = "testRefType";
	private static final String GROUP_ENTITY_TYPE = "testGroup";
	private static final int MUTATED_ENTITY_PK = 42;
	private static final Scope TEST_SCOPE = Scope.LIVE;

	private ReevaluateFacetExpressionExecutor executor;
	private TestIndexMutationTarget target;
	private GlobalEntityIndex globalIndex;

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
	 * Builds a real {@link EntitySchema} with a reference configured for the given index type.
	 *
	 * @param forFilteringAndPartitioning `true` for FOR_FILTERING_AND_PARTITIONING, `false` for FOR_FILTERING
	 * @return built entity schema with the reference (including group type)
	 */
	@Nonnull
	private static EntitySchema buildEntitySchema(boolean forFilteringAndPartitioning) {
		return buildEntitySchema(forFilteringAndPartitioning, true);
	}

	/**
	 * Builds a real {@link EntitySchema} with a reference configured for the given index type
	 * and optionally with a group type.
	 *
	 * @param forFilteringAndPartitioning `true` for FOR_FILTERING_AND_PARTITIONING, `false` for FOR_FILTERING
	 * @param withGroupType `true` to include group type, `false` for ungrouped reference
	 * @return built entity schema with the reference
	 */
	@Nonnull
	private static EntitySchema buildEntitySchema(boolean forFilteringAndPartitioning, boolean withGroupType) {
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			"testCatalog",
			NamingConvention.generate("testCatalog"),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return Set.of();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Optional.empty();
				}
			}
		);
		final InternalEntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
			catalogSchema, EntitySchema._internalBuild(ENTITY_TYPE)
		);
		if (forFilteringAndPartitioning) {
			builder.withReferenceTo(
				REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (withGroupType) {
						whichIs.withGroupType(GROUP_ENTITY_TYPE);
					}
					whichIs.facetedInScope(TEST_SCOPE)
						.indexedForFilteringAndPartitioningInScope(TEST_SCOPE);
				}
			);
		} else {
			builder.withReferenceTo(
				REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (withGroupType) {
						whichIs.withGroupType(GROUP_ENTITY_TYPE);
					}
					whichIs.facetedInScope(TEST_SCOPE);
				}
			);
		}
		return (EntitySchema) builder.toInstance();
	}

	/**
	 * Asserts that a facet entry exists in the given index for the specified reference, facet PK, group PK, and
	 * owner PK.
	 *
	 * @param index         the entity index to inspect
	 * @param referenceName the reference name
	 * @param facetPK       the facet primary key
	 * @param groupPK       the group primary key, or null for ungrouped
	 * @param ownerPK       the owner entity primary key
	 */
	private static void assertFacetExists(
		@Nonnull EntityIndex index,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final FacetReferenceIndex refIndex = index.getFacetingEntities().get(referenceName);
		assertNotNull(refIndex, "FacetReferenceIndex for '" + referenceName + "' should exist");
		final FacetGroupIndex groupIndex = refIndex.getFacetsInGroup(groupPK);
		assertNotNull(groupIndex, "FacetGroupIndex for group " + groupPK + " should exist");
		final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(facetPK);
		assertNotNull(facetIdIndex, "FacetIdIndex for facet " + facetPK + " should exist");
		assertTrue(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should be present in facet " + facetPK + " (group " + groupPK + ")"
		);
	}

	/**
	 * Asserts that a facet entry does NOT exist in the given index for the specified reference, facet PK, group PK,
	 * and owner PK. The assertion passes if any level of the facet hierarchy is missing (reference, group, facet, or
	 * owner PK not in bitmap).
	 *
	 * @param index         the entity index to inspect
	 * @param referenceName the reference name
	 * @param facetPK       the facet primary key
	 * @param groupPK       the group primary key, or null for ungrouped
	 * @param ownerPK       the owner entity primary key
	 */
	private static void assertFacetNotExists(
		@Nonnull EntityIndex index,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final FacetReferenceIndex refIndex = index.getFacetingEntities().get(referenceName);
		if (refIndex == null) {
			return; // no facets for this reference => not exists
		}
		final FacetGroupIndex groupIndex = refIndex.getFacetsInGroup(groupPK);
		if (groupIndex == null) {
			return; // no facets in this group => not exists
		}
		final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(facetPK);
		if (facetIdIndex == null) {
			return; // no facet with this PK => not exists
		}
		assertFalse(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be present in facet " + facetPK + " (group " + groupPK + ")"
		);
	}

	/**
	 * Returns the total number of facet entries (owner PKs) across all groups and facets for a given reference
	 * in the index.
	 *
	 * @param index         the entity index to inspect
	 * @param referenceName the reference name
	 * @return total count of owner PK entries
	 */
	private static int countFacetEntries(@Nonnull EntityIndex index, @Nonnull String referenceName) {
		final FacetReferenceIndex refIndex = index.getFacetingEntities().get(referenceName);
		if (refIndex == null) {
			return 0;
		}
		int count = 0;
		final FacetGroupIndex notGrouped = refIndex.getNotGroupedFacets();
		if (notGrouped != null) {
			for (final Bitmap bitmap : notGrouped.getAsMap().values()) {
				count += bitmap.size();
			}
		}
		for (final FacetGroupIndex groupIndex : refIndex.getGroupedFacets()) {
			for (final Bitmap bitmap : groupIndex.getAsMap().values()) {
				count += bitmap.size();
			}
		}
		return count;
	}

	@BeforeEach
	void setUp() {
		this.executor = new ReevaluateFacetExpressionExecutor();
		this.globalIndex = new GlobalEntityIndex(
			1, ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.GLOBAL, TEST_SCOPE)
		);
		this.target = new TestIndexMutationTarget(buildEntitySchema(false));
		this.target.registerIndex(new EntityIndexKey(EntityIndexType.GLOBAL, TEST_SCOPE), this.globalIndex);
		this.target.setTrigger(
			REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE,
			new TestExpressionIndexTrigger(createTriggerFilterBy())
		);
		this.target.setTrigger(
			REFERENCE_NAME, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE,
			new TestExpressionIndexTrigger(createTriggerFilterBy())
		);
	}

	/**
	 * Configures real indexes for the GROUP_ENTITY_ATTRIBUTE resolution path: ReferencedTypeEntityIndex containing
	 * storagePKs, and ReducedGroupEntityIndex instances populated with facetPKs and owner PKs.
	 *
	 * @param storagePKs   storage primary keys for the ReducedGroupEntityIndexes
	 * @param facetPKs     facet primary keys for each ReducedGroupEntityIndex (parallel with storagePKs)
	 * @param ownerPKs     owner PK arrays for each (storagePK, facetPK) pair (parallel with facetPKs)
	 */
	private void setupGroupEntityAttributeScenario(
		@Nonnull int[] storagePKs,
		@Nonnull int[][] facetPKs,
		@Nonnull int[][] ownerPKs
	) {
		final EntityIndexKey groupTypeKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
		);
		final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
			50, ENTITY_TYPE, groupTypeKey
		);

		for (int i = 0; i < storagePKs.length; i++) {
			final EntityIndexKey rgeiKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
			);
			final ReducedGroupEntityIndex rgei = new ReducedGroupEntityIndex(
				storagePKs[i], ENTITY_TYPE, rgeiKey
			);

			// register the storagePK -> mutatedEntityPK mapping in RTEI
			rtei.insertPrimaryKeyIfMissing(storagePKs[i], MUTATED_ENTITY_PK);

			// populate the RGEI with facetPK -> ownerPKs
			for (int j = 0; j < facetPKs[i].length; j++) {
				for (final int ownerPK : ownerPKs[j]) {
					rgei.insertPrimaryKeyIfMissing(ownerPK, facetPKs[i][j]);
				}
			}

			this.target.registerIndexByPK(storagePKs[i], rgei);
		}

		this.target.registerIndex(groupTypeKey, rtei);
	}

	/**
	 * Configures real indexes for the REFERENCED_ENTITY_ATTRIBUTE resolution path. When `groupPK` is non-null,
	 * creates a `REFERENCED_GROUP_ENTITY_TYPE` `ReferencedTypeEntityIndex` with `ReducedGroupEntityIndex` instances
	 * (the grouped resolution path). When `groupPK` is null, creates a `REFERENCED_ENTITY_TYPE`
	 * `ReferencedTypeEntityIndex` with `ReducedEntityIndex` instances and switches to an ungrouped schema
	 * (the ungrouped resolution path).
	 *
	 * @param storagePKs storage primary keys for the reduced indexes
	 * @param ownerPKs   owner PK arrays for each reduced index (parallel with storagePKs)
	 * @param groupPK    the group PK for grouped resolution, or null for ungrouped resolution
	 */
	private void setupReferencedEntityAttributeScenario(
		@Nonnull int[] storagePKs,
		@Nonnull int[][] ownerPKs,
		@Nullable Integer groupPK
	) {
		if (groupPK != null) {
			// grouped path: use REFERENCED_GROUP_ENTITY_TYPE + ReducedGroupEntityIndex
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				60, ENTITY_TYPE, groupTypeKey
			);

			for (int i = 0; i < storagePKs.length; i++) {
				final EntityIndexKey rgeiKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, groupPK))
				);
				final ReducedGroupEntityIndex rgei = new ReducedGroupEntityIndex(
					storagePKs[i], ENTITY_TYPE, rgeiKey
				);

				// register the storagePK -> groupPK mapping in RTEI
				rtei.insertPrimaryKeyIfMissing(storagePKs[i], groupPK);

				// populate the RGEI with facetPK (= MUTATED_ENTITY_PK) -> ownerPKs
				for (final int ownerPK : ownerPKs[i]) {
					rgei.insertPrimaryKeyIfMissing(ownerPK, MUTATED_ENTITY_PK);
				}

				this.target.registerIndexByPK(storagePKs[i], rgei);
			}

			this.target.registerIndex(groupTypeKey, rtei);
		} else {
			// ungrouped path: use REFERENCED_ENTITY_TYPE + ReducedEntityIndex + schema without group type
			this.target.setEntitySchema(buildEntitySchema(false, false));

			final EntityIndexKey refTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				60, ENTITY_TYPE, refTypeKey
			);

			for (int i = 0; i < storagePKs.length; i++) {
				final EntityIndexKey reducedKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				);
				final ReducedEntityIndex reducedIndex = new ReducedEntityIndex(
					storagePKs[i], ENTITY_TYPE, reducedKey
				);

				// register the storagePK -> mutatedEntityPK mapping in RTEI
				rtei.insertPrimaryKeyIfMissing(storagePKs[i], MUTATED_ENTITY_PK);

				// populate the reduced index with owner PKs
				for (final int ownerPK : ownerPKs[i]) {
					reducedIndex.insertPrimaryKeyIfMissing(ownerPK);
				}

				this.target.registerIndexByPK(storagePKs[i], reducedIndex);
			}

			this.target.registerIndex(refTypeKey, rtei);
		}
	}

	/**
	 * Pre-populates the global index with facet entries that should be removed during execution. This is necessary
	 * because `removeFacet` on real indexes asserts the entry exists.
	 *
	 * @param facetPK the facet primary key
	 * @param groupPK the group primary key, or null for ungrouped
	 * @param ownerPKs owner PKs to pre-populate
	 */
	private void prepopulateGlobalFacets(int facetPK, @Nullable Integer groupPK, @Nonnull int... ownerPKs) {
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, facetPK);
		for (final int ownerPK : ownerPKs) {
			this.globalIndex.addFacet(null, refKey, groupPK, ownerPK);
		}
	}

	/**
	 * Reconfigures the target to use a schema with FOR_FILTERING_AND_PARTITIONING index type.
	 */
	private void switchToFilteringAndPartitioning() {
		this.target.setEntitySchema(buildEntitySchema(true));
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
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				50, ENTITY_TYPE, groupTypeKey
			);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// no facets should have been added to the global index
			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		@Test
		@DisplayName("Should return early when trigger is null")
		void shouldReturnEarlyWhenTriggerIsNull() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new int[][]{{10}}
			);
			// clear the trigger so it returns null
			ReevaluateFacetExpressionExecutorTest.this.target.setTrigger(
				REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE, null
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		@Test
		@DisplayName("Should return early when dependency type is unsupported")
		void shouldReturnEarlyWhenDependencyTypeIsUnsupported() {
			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		/**
		 * Verifies that when `target.getIndexIfExists()` returns null for the
		 * REFERENCED_GROUP_ENTITY_TYPE key, the executor returns EMPTY resolution.
		 */
		@Test
		@DisplayName("Should return early when RTEI is null for GROUP_ENTITY_ATTRIBUTE")
		void shouldReturnEarlyWhenRteiIsNullForGroupEntityAttribute() {
			// do NOT register any RTEI — getIndexIfExists will return null

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		/**
		 * Verifies that when `target.getIndexIfExists()` returns null for the
		 * REFERENCED_GROUP_ENTITY_TYPE key (grouped reference), the executor returns EMPTY resolution.
		 */
		@Test
		@DisplayName("Should return early when RTEI is null for REFERENCED_ENTITY_ATTRIBUTE")
		void shouldReturnEarlyWhenRteiIsNullForReferencedEntityAttribute() {
			// do NOT register any RTEI — getIndexIfExists will return null

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
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
				new int[][]{{10, 20}}
			);
			// pre-populate global with facet entry for ownerPK=20 (it will be removed)
			prepopulateGlobalFacets(5, MUTATED_ENTITY_PK, 20);
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 20
			);
		}

		@Test
		@DisplayName("Should skip null reduced group index without error")
		void shouldSkipNullReducedGroupIndex() {
			// RTEI returns storagePKs but getIndexByPrimaryKeyIfExists returns null (no RGEI registered)
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				50, ENTITY_TYPE, groupTypeKey
			);
			rtei.insertPrimaryKeyIfMissing(100, MUTATED_ENTITY_PK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);
			// do NOT register any index for storagePK=100

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// no affected entities => no facet ops
			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		/**
		 * Verifies that when a single `ReducedGroupEntityIndex` contains multiple facet PKs (e.g., facetPK=5
		 * and facetPK=7), both produce `AffectedFacetGroup` entries and both receive add/remove operations.
		 */
		@Test
		@DisplayName("Should handle multiple facet PKs in a single ReducedGroupEntityIndex")
		void shouldHandleMultipleFacetPKsInSingleReducedGroupIndex() {
			// one RGEI (storagePK=100) with two facetPKs: 5 (owner=10) and 7 (owner=20)
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5, 7}},
				new int[][]{{10}, {20}}
			);
			// pre-populate global with facet entry for ownerPK=20 (facet 7, it will be removed)
			prepopulateGlobalFacets(7, MUTATED_ENTITY_PK, 20);
			// PK 10 matches (facet 5), PK 20 does not match (facet 7) => add 10, remove 20
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 7, MUTATED_ENTITY_PK, 20
			);
		}

		@Test
		@DisplayName("Should skip non-ReducedGroupEntityIndex without error")
		void shouldSkipNonGroupEntityIndex() {
			// storagePK maps to a regular ReducedEntityIndex (not ReducedGroupEntityIndex)
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				50, ENTITY_TYPE, groupTypeKey
			);
			rtei.insertPrimaryKeyIfMissing(100, MUTATED_ENTITY_PK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);

			// register a plain ReducedEntityIndex (not ReducedGroupEntityIndex)
			final ReducedEntityIndex plainIndex = new ReducedEntityIndex(
				100, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				)
			);
			plainIndex.insertPrimaryKeyIfMissing(10);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(100, plainIndex);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// non-RGEI is skipped => no affected entities => no facet ops
			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
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
		@DisplayName("Should resolve affected entities through referenced entity index with group PK")
		void shouldResolveAffectedEntitiesThroughReferencedEntityIndex() {
			final int groupPK = 99;
			setupReferencedEntityAttributeScenario(
				new int[]{200},
				new int[][]{{10, 20}},
				groupPK
			);
			// pre-populate global with facet entry for ownerPK=20 (it will be removed)
			prepopulateGlobalFacets(MUTATED_ENTITY_PK, groupPK, 20);
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, groupPK, 10
			);
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, groupPK, 20
			);
		}

		@Test
		@DisplayName("Should resolve null group PK for ungrouped facet")
		void shouldResolveNullGroupPKForUngroupedFacet() {
			// ungrouped reference schema => uses REFERENCED_ENTITY_TYPE path with groupPK = null
			setupReferencedEntityAttributeScenario(
				new int[]{200},
				new int[][]{{10}},
				null
			);
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// facet should exist with null group
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, null, 10
			);
		}

		/**
		 * Verifies that when `target.getIndexByPrimaryKeyIfExists()` returns null during
		 * REFERENCED_ENTITY_ATTRIBUTE grouped resolution, that storagePK is skipped without error.
		 */
		@Test
		@DisplayName("Should skip null reduced index in REFERENCED_ENTITY_ATTRIBUTE path")
		void shouldSkipNullReducedIndexInReferencedEntityPath() {
			final int groupPK = 99;
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				60, ENTITY_TYPE, groupTypeKey
			);
			// RTEI returns two storagePKs for group 99; only the second one has a real index
			rtei.insertPrimaryKeyIfMissing(200, groupPK);
			rtei.insertPrimaryKeyIfMissing(201, groupPK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);

			// do NOT register index for storagePK=200 (will return null)
			// register valid ReducedGroupEntityIndex for storagePK=201
			final ReducedGroupEntityIndex validReducedIndex = new ReducedGroupEntityIndex(
				201, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, groupPK))
				)
			);
			// facetPK = MUTATED_ENTITY_PK, ownerPK = 10
			validReducedIndex.insertPrimaryKeyIfMissing(10, MUTATED_ENTITY_PK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(201, validReducedIndex);

			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			// should not throw even though storagePK=200 maps to null
			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, groupPK, 10
			);
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
				new int[][]{{10}, {20}},
				groupPK
			);
			// both PKs match evaluation => both should be added
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10, 20)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, groupPK, 10
			);
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, MUTATED_ENTITY_PK, groupPK, 20
			);
			// exactly 2 adds from the two storagePKs
			assertEquals(2, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

		@Test
		@DisplayName("Should skip reduced index with empty owner PKs for facet")
		void shouldSkipReducedIndexWithEmptyPrimaryKeys() {
			// RTEI returns storagePK but the ReducedGroupEntityIndex has no owner PKs for the facetPK
			final int groupPK = 99;
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				60, ENTITY_TYPE, groupTypeKey
			);
			rtei.insertPrimaryKeyIfMissing(200, groupPK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);

			// register a ReducedGroupEntityIndex that has entries for a DIFFERENT facetPK (not MUTATED_ENTITY_PK)
			final ReducedGroupEntityIndex rgei = new ReducedGroupEntityIndex(
				200, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, groupPK))
				)
			);
			// populate with a different facet PK so getOwnerPKsForReferencedEntity(MUTATED_ENTITY_PK) returns null
			rgei.insertPrimaryKeyIfMissing(10, 999);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(200, rgei);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// no owner PKs for MUTATED_ENTITY_PK => no affected => no facet ops
			assertEquals(0, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
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
				new int[][]{{10, 20}}
			);
			// all affected PKs are in truePKs
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10, 20)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 20
			);
		}

		@Test
		@DisplayName("Should remove all affected when none match evaluation")
		void shouldRemoveAllAffectedWhenNoneMatchEvaluation() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new int[][]{{10, 20}}
			);
			// pre-populate global with entries to be removed
			prepopulateGlobalFacets(5, MUTATED_ENTITY_PK, 10, 20);
			// no affected PKs in truePKs
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap()
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 20
			);
		}

		@Test
		@DisplayName("Should split affected into adds and removes based on evaluation")
		void shouldSplitAffectedIntoAddsAndRemoves() {
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new int[][]{{10, 20, 30}}
			);
			// pre-populate global with entry for ownerPK=20 (it will be removed)
			prepopulateGlobalFacets(5, MUTATED_ENTITY_PK, 20);
			// only 10 and 30 match
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10, 30)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 20
			);
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 30
			);
		}
	}

	/**
	 * Tests verifying index routing: global-only vs. global + reduced entity index targeting based on
	 * the reference schema's {@link io.evitadb.api.requestResponse.schema.ReferenceIndexType}.
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
				new int[][]{{10}}
			);
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// global index should have the facet
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);

			// FOR_FILTERING mode should NOT look up REFERENCED_ENTITY_TYPE index at all
			// we can verify by checking that no reduced index was ever accessed
			assertNull(
				ReevaluateFacetExpressionExecutorTest.this.target.getRegisteredIndex(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME)
				),
				"No REFERENCED_ENTITY_TYPE index should be registered for FOR_FILTERING mode"
			);
		}

		/**
		 * Verifies that when `FOR_FILTERING_AND_PARTITIONING` is active and entities should be REMOVED,
		 * `removeFacet` is called on both the global AND reduced indexes.
		 */
		@Test
		@DisplayName("Should call removeFacet on both global and reduced index when REMOVE path is taken")
		void shouldRemoveFacetOnBothGlobalAndReducedIndex() {
			switchToFilteringAndPartitioning();
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new int[][]{{10}}
			);
			// pre-populate global with facet entry for ownerPK=10 (it will be removed)
			prepopulateGlobalFacets(5, MUTATED_ENTITY_PK, 10);

			// setup REFERENCED_ENTITY_TYPE index for reduced targeting
			final EntityIndexKey refEntityTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex refTypeRtei = new ReferencedTypeEntityIndex(
				70, ENTITY_TYPE, refEntityTypeKey
			);
			// reduced index for facetPK=5
			final ReducedEntityIndex reducedIndex = new ReducedEntityIndex(
				300, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				)
			);
			refTypeRtei.insertPrimaryKeyIfMissing(300, 5);
			// pre-populate reduced index with facet entry for ownerPK=10
			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, 5);
			final ReferenceSchemaContract refSchema = ReevaluateFacetExpressionExecutorTest.this.target
				.getEntitySchema().getReferenceOrThrowException(REFERENCE_NAME);
			reducedIndex.addFacet(refSchema, refKey, MUTATED_ENTITY_PK, 10);

			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(refEntityTypeKey, refTypeRtei);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(300, reducedIndex);

			// no PKs match evaluation => all should be removed
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap()
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// both global and reduced should have removed the facet
			assertFacetNotExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetNotExists(reducedIndex, REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10);
		}

		@Test
		@DisplayName("Should target both global and reduced index when FOR_FILTERING_AND_PARTITIONING")
		void shouldTargetGlobalAndReducedWhenFilteringAndPartitioning() {
			switchToFilteringAndPartitioning();
			setupGroupEntityAttributeScenario(
				new int[]{100},
				new int[][]{{5}},
				new int[][]{{10}}
			);

			// setup REFERENCED_ENTITY_TYPE index for reduced targeting
			final EntityIndexKey refEntityTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex refTypeRtei = new ReferencedTypeEntityIndex(
				70, ENTITY_TYPE, refEntityTypeKey
			);
			// reduced index for facetPK=5
			final ReducedEntityIndex reducedIndex = new ReducedEntityIndex(
				300, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				)
			);
			refTypeRtei.insertPrimaryKeyIfMissing(300, 5);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(refEntityTypeKey, refTypeRtei);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(300, reducedIndex);

			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			// both global and reduced should have the facet added
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetExists(reducedIndex, REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10);
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
				new int[][]{{10}}
			);
			// capture the FilterBy passed to evaluateFilter
			final FilterBy[] capturedFilter = new FilterBy[1];
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> {
					capturedFilter[0] = filter;
					return new BaseBitmap(10);
				}
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertNotNull(capturedFilter[0], "evaluateFilter should have been called");

			final FilterConstraint[] topChildren = capturedFilter[0].getChildren();
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
				new int[][]{{10}},
				99
			);
			// capture the FilterBy passed to evaluateFilter
			final FilterBy[] capturedFilter = new FilterBy[1];
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> {
					capturedFilter[0] = filter;
					return new BaseBitmap(10);
				}
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertNotNull(capturedFilter[0], "evaluateFilter should have been called");

			final FilterConstraint[] topChildren = capturedFilter[0].getChildren();
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
				new int[][]{{10}}
			);

			// override the trigger FilterBy to use two ReferenceHaving children
			final FilterBy triggerFilter = new FilterBy(
				new ReferenceHaving("otherRef", new EntityPrimaryKeyInSet(99)),
				new ReferenceHaving(REFERENCE_NAME, new EntityPrimaryKeyInSet(1))
			);
			ReevaluateFacetExpressionExecutorTest.this.target.setTrigger(
				REFERENCE_NAME, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE,
				new TestExpressionIndexTrigger(triggerFilter)
			);

			// capture the FilterBy passed to evaluateFilter
			final FilterBy[] capturedFilter = new FilterBy[1];
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> {
					capturedFilter[0] = filter;
					return new BaseBitmap(10);
				}
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertNotNull(capturedFilter[0], "evaluateFilter should have been called");

			final FilterConstraint[] topChildren = capturedFilter[0].getChildren();
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
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, TEST_SCOPE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex rtei = new ReferencedTypeEntityIndex(
				50, ENTITY_TYPE, groupTypeKey
			);
			rtei.insertPrimaryKeyIfMissing(100, MUTATED_ENTITY_PK);
			rtei.insertPrimaryKeyIfMissing(101, MUTATED_ENTITY_PK);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndex(groupTypeKey, rtei);

			final ReducedGroupEntityIndex rgei1 = new ReducedGroupEntityIndex(
				100, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				)
			);
			rgei1.insertPrimaryKeyIfMissing(10, 5);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(100, rgei1);

			final ReducedGroupEntityIndex rgei2 = new ReducedGroupEntityIndex(
				101, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, TEST_SCOPE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, MUTATED_ENTITY_PK))
				)
			);
			rgei2.insertPrimaryKeyIfMissing(20, 7);
			ReevaluateFacetExpressionExecutorTest.this.target.registerIndexByPK(101, rgei2);

			// all affected PKs match
			ReevaluateFacetExpressionExecutorTest.this.target.setEvaluateFilter(
				(filter, scope) -> new BaseBitmap(10, 20)
			);

			final ReevaluateFacetExpressionMutation mutation = new ReevaluateFacetExpressionMutation(
				REFERENCE_NAME, MUTATED_ENTITY_PK, DependencyType.GROUP_ENTITY_ATTRIBUTE, TEST_SCOPE
			);

			ReevaluateFacetExpressionExecutorTest.this.executor.execute(
				mutation, ReevaluateFacetExpressionExecutorTest.this.target
			);

			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 5, MUTATED_ENTITY_PK, 10
			);
			assertFacetExists(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex,
				REFERENCE_NAME, 7, MUTATED_ENTITY_PK, 20
			);
			assertEquals(2, countFacetEntries(
				ReevaluateFacetExpressionExecutorTest.this.globalIndex, REFERENCE_NAME
			));
		}

	}

	/**
	 * Test double for {@link IndexMutationTarget} backed by simple maps. Provides configurable
	 * {@link #evaluateFilter} behavior and explicit index registration via
	 * {@link #registerIndex(EntityIndexKey, EntityIndex)} and {@link #registerIndexByPK(int, EntityIndex)}.
	 */
	private static class TestIndexMutationTarget implements IndexMutationTarget {
		private final Map<EntityIndexKey, EntityIndex> indexesByKey = new HashMap<>();
		private final Map<Integer, EntityIndex> indexesByPK = new HashMap<>();
		private final Map<String, ExpressionIndexTrigger> triggers = new HashMap<>();
		private EntitySchema entitySchema;
		private BiFunction<FilterBy, Scope, Bitmap> evaluateFilterFn = (f, s) -> new BaseBitmap();

		/**
		 * Creates a new test target with the given entity schema.
		 *
		 * @param entitySchema the entity schema to use
		 */
		TestIndexMutationTarget(@Nonnull EntitySchema entitySchema) {
			this.entitySchema = entitySchema;
		}

		/**
		 * Registers an index by its key.
		 *
		 * @param key   the entity index key
		 * @param index the entity index
		 */
		void registerIndex(@Nonnull EntityIndexKey key, @Nonnull EntityIndex index) {
			this.indexesByKey.put(key, index);
		}

		/**
		 * Registers an index by its storage primary key.
		 *
		 * @param pk    the storage primary key
		 * @param index the entity index
		 */
		void registerIndexByPK(int pk, @Nonnull EntityIndex index) {
			this.indexesByPK.put(pk, index);
		}

		/**
		 * Returns the registered index for the given key, or null if not registered.
		 *
		 * @param key the entity index key
		 * @return the index, or null
		 */
		@Nullable
		EntityIndex getRegisteredIndex(@Nonnull EntityIndexKey key) {
			return this.indexesByKey.get(key);
		}

		/**
		 * Sets the trigger for the given reference name, dependency type, and scope.
		 *
		 * @param referenceName  the reference name
		 * @param dependencyType the dependency type
		 * @param scope          the scope
		 * @param trigger        the trigger, or null to clear
		 */
		void setTrigger(
			@Nonnull String referenceName,
			@Nonnull DependencyType dependencyType,
			@Nonnull Scope scope,
			@Nullable ExpressionIndexTrigger trigger
		) {
			final String key = referenceName + ":" + dependencyType + ":" + scope;
			if (trigger == null) {
				this.triggers.remove(key);
			} else {
				this.triggers.put(key, trigger);
			}
		}

		/**
		 * Sets the evaluate filter function.
		 *
		 * @param fn the function to use for filter evaluation
		 */
		void setEvaluateFilter(@Nonnull BiFunction<FilterBy, Scope, Bitmap> fn) {
			this.evaluateFilterFn = fn;
		}

		/**
		 * Updates the entity schema used by this target.
		 *
		 * @param schema the new entity schema
		 */
		void setEntitySchema(@Nonnull EntitySchema schema) {
			this.entitySchema = schema;
		}

		@Nonnull
		@Override
		public EntitySchema getEntitySchema() {
			return this.entitySchema;
		}

		@Nullable
		@Override
		public ExpressionIndexTrigger getTrigger(
			@Nonnull String referenceName,
			@Nonnull DependencyType dependencyType,
			@Nonnull Scope scope
		) {
			return this.triggers.get(referenceName + ":" + dependencyType + ":" + scope);
		}

		@Nonnull
		@Override
		public Bitmap evaluateFilter(@Nonnull FilterBy filterBy, @Nonnull Scope scope) {
			return this.evaluateFilterFn.apply(filterBy, scope);
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexesByKey.computeIfAbsent(entityIndexKey, k -> {
				throw new IllegalStateException("Index not pre-registered for key: " + k);
			});
		}

		@Nullable
		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexesByKey.get(entityIndexKey);
		}

		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			return this.indexesByPK.get(indexPrimaryKey);
		}
	}

	/**
	 * Minimal test double for {@link ExpressionIndexTrigger} that only implements
	 * {@link #getFilterByConstraint()} — the only method used by {@link ReevaluateFacetExpressionExecutor}.
	 * All other methods return default values.
	 */
	private static class TestExpressionIndexTrigger implements ExpressionIndexTrigger {
		private final FilterBy filterByConstraint;

		/**
		 * Creates a new test trigger with the given FilterBy constraint.
		 *
		 * @param filterByConstraint the pre-translated FilterBy to return
		 */
		TestExpressionIndexTrigger(@Nonnull FilterBy filterByConstraint) {
			this.filterByConstraint = filterByConstraint;
		}

		@Nonnull
		@Override
		public String getOwnerEntityType() {
			return ENTITY_TYPE;
		}

		@Nonnull
		@Override
		public String getReferenceName() {
			return REFERENCE_NAME;
		}

		@Nonnull
		@Override
		public Scope getScope() {
			return TEST_SCOPE;
		}

		@Nullable
		@Override
		public String getMutatedEntityType() {
			return null;
		}

		@Nullable
		@Override
		public DependencyType getDependencyType() {
			return null;
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

		@Nonnull
		@Override
		public FilterBy getFilterByConstraint() {
			return this.filterByConstraint;
		}

		@Override
		public boolean evaluate(
			int ownerEntityPK,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor storageAccessor,
			@Nonnull Function<String, EntitySchemaContract> schemaResolver
		) {
			throw new UnsupportedOperationException("Not used by ReevaluateFacetExpressionExecutor");
		}
	}
}
