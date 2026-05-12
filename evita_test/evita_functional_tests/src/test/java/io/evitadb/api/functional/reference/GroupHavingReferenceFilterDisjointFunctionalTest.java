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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the reference index-discovery bug where
 * {@link io.evitadb.api.query.filter.GroupHaving} evaluated inside
 * {@link io.evitadb.api.query.filter.ReferenceHaving} was looked up against the
 * REFERENCED_ENTITY_TYPE index keyed by referenced entity PKs, even though the inner
 * filter produced group entity PKs. The bug stayed dormant in datasets where the group
 * type and the referenced entity type share overlapping primary-key ranges (sequential
 * PKs in the standard test data generator). It surfaced on production-shaped data where
 * the two PK universes are disjoint - exactly the situation reproduced below by inserting
 * group entities into [10_001, 10_005] and referenced entities into [20_001, 20_010].
 *
 * Tests share a single read-only dataset built once via the
 * {@link io.evitadb.test.annotation.DataSet} fixture; no per-test catalog rebuild.
 *
 * Tests are organised in nested groups by query shape:
 * - {@link PureGroupHaving}: groupHaving as the sole reference-side filter
 * - {@link HierarchyAndGroupHaving}: hierarchyWithin + groupHaving, mirroring the original
 *   user-reported macbooks-with-ram-memory query
 * - {@link CombinedEntityAndGroupHaving}: groupHaving co-located with entityHaving in the
 *   same referenceHaving, exercising AND-composition of two reference-side filters
 * - {@link EmptyAndNegativeCases}: empty result paths the planner used to confuse with
 *   `silently wrong` results
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group having with disjoint group/entity PK universes")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(ENGINE)
@Tag(FILTER)
@Tag(REFERENCE)
public class GroupHavingReferenceFilterDisjointFunctionalTest {

	/**
	 * Shared dataset name. The catalog is initialised once per test class and torn down after
	 * the last test executes ({@link DataSet#destroyAfterClass() destroyAfterClass = true}).
	 */
	private static final String DISJOINT_PK_PARAMETERS = "DisjointPkParameters";

	/**
	 * Owner entity (one of the 30 products created in {@link #setUpDisjointPkParameters}).
	 */
	private static final String PRODUCT = "Product";

	/**
	 * Referenced entity type for the {@code parameterValues} reference. PKs live in [20_001, 20_010].
	 */
	private static final String PARAMETER_VALUE = "ParameterValue";

	/**
	 * Group entity type for the {@code parameterValues} reference. PKs live in [10_001, 10_005] -
	 * disjoint from {@link #PARAMETER_VALUE} PKs by construction. Five parameters carry distinct
	 * {@code code} attributes used to exercise both attribute-based and PK-based groupHaving filters.
	 */
	private static final String PARAMETER = "Parameter";

	/**
	 * Category entity type used to test hierarchyWithin + groupHaving combinations.
	 */
	private static final String CATEGORY = "Category";

	/**
	 * Reference name on Product. Indexed for both REFERENCED_ENTITY and REFERENCED_GROUP_ENTITY so
	 * the engine actually has a REFERENCED_GROUP_ENTITY_TYPE index to consult.
	 */
	private static final String PARAMETER_VALUES_REF = "parameterValues";

	/**
	 * Hierarchy reference name on Product.
	 */
	private static final String CATEGORIES_REF = "categories";

	/**
	 * Attribute name carrying the human-readable identifier used in filter conditions.
	 */
	private static final String ATTR_CODE = "code";

	/**
	 * PK offset for {@link #PARAMETER} entities - chosen well outside the referenced entity
	 * PK range so the discovery-phase mis-lookup the fix addresses surfaces as a missed match.
	 */
	private static final int PARAMETER_PK_BASE = 10_000;

	/**
	 * PK offset for {@link #PARAMETER_VALUE} entities.
	 */
	private static final int PARAMETER_VALUE_PK_BASE = 20_000;

	/**
	 * PK assigned to the category we filter products into via hierarchyWithin.
	 */
	private static final int LAPTOP_CATEGORY_PK = 1;

	/**
	 * Total number of Product entities created in the fixture.
	 */
	private static final int PRODUCT_COUNT = 30;

	/**
	 * Five known group {@link #PARAMETER} PKs created in the fixture.
	 */
	private static final int RAM_PARAMETER_PK = PARAMETER_PK_BASE + 1;
	private static final int CPU_PARAMETER_PK = PARAMETER_PK_BASE + 2;
	private static final int SCREEN_PARAMETER_PK = PARAMETER_PK_BASE + 3;
	private static final int STORAGE_PARAMETER_PK = PARAMETER_PK_BASE + 4;
	private static final int WEIGHT_PARAMETER_PK = PARAMETER_PK_BASE + 5;

	/**
	 * Builds the shared read-only catalog used by every test in this class.
	 *
	 * Layout:
	 * - 5 Parameter (group) entities at PKs [10_001, 10_005]
	 * - 10 ParameterValue (referenced entity) entities at PKs [20_001, 20_010]
	 * - 1 Category root (laptops) at PK 1
	 * - 30 Product entities with the following reference distribution:
	 *   - even-indexed products carry one RAM parameterValue (group = RAM)
	 *   - odd-indexed products carry one CPU value (group = CPU) and one SCREEN value (group = SCREEN)
	 *   - every product additionally carries one STORAGE value (group = STORAGE)
	 *   - no product carries WEIGHT (used for the empty-result test)
	 *
	 * Returns a {@link DataCarrier} pre-populating the two index sets every assertion needs, so
	 * test methods do not recompute them.
	 */
	@Nonnull
	@DataSet(value = DISJOINT_PK_PARAMETERS, destroyAfterClass = true)
	DataCarrier setUpDisjointPkParameters(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PARAMETER)
					.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().sortable())
					.updateVia(session);
				session.defineEntitySchema(PARAMETER_VALUE)
					.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().sortable())
					.updateVia(session);
				session.defineEntitySchema(CATEGORY)
					.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().sortable())
					.withHierarchy()
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().sortable())
					.withReferenceToEntity(
						CATEGORIES_REF, CATEGORY, Cardinality.ZERO_OR_MORE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.withReferenceToEntity(
						PARAMETER_VALUES_REF, PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(PARAMETER)
					)
					.updateVia(session);

				// hierarchy root: one "laptops" category that all products live under
				session.upsertEntity(
					session.createNewEntity(CATEGORY, LAPTOP_CATEGORY_PK)
						.setAttribute(ATTR_CODE, "laptops")
				);

				// five parameter group entities at well-known disjoint PKs
				upsertParameter(session, RAM_PARAMETER_PK, "ram-memory");
				upsertParameter(session, CPU_PARAMETER_PK, "cpu");
				upsertParameter(session, SCREEN_PARAMETER_PK, "screen");
				upsertParameter(session, STORAGE_PARAMETER_PK, "storage");
				upsertParameter(session, WEIGHT_PARAMETER_PK, "weight");

				// ten parameter-value entities, each assigned to one of the five parameter groups
				// (two values per parameter). PKs live in [20_001, 20_010]
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 1, "ram-8gb");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 2, "ram-16gb");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 3, "cpu-m1");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 4, "cpu-m2");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 5, "screen-13");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 6, "screen-15");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 7, "storage-256");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 8, "storage-512");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 9, "weight-1kg");
				upsertParameterValue(session, PARAMETER_VALUE_PK_BASE + 10, "weight-2kg");

				// 30 products. Even-indexed products (PK 2, 4, ...) carry one RAM value (alternating
				// 8gb/16gb by slot index). Odd-indexed products carry CPU + SCREEN values - no RAM.
				// All products also carry a STORAGE value so AND-with-entityHaving stays non-trivial.
				for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
					final boolean evenSlot = productPk % 2 == 0;
					final int ramPick = (productPk / 2) % 2 == 0
						? PARAMETER_VALUE_PK_BASE + 1
						: PARAMETER_VALUE_PK_BASE + 2;
					final int cpuPick = productPk % 4 < 2
						? PARAMETER_VALUE_PK_BASE + 3
						: PARAMETER_VALUE_PK_BASE + 4;
					final int screenPick = productPk % 4 < 2
						? PARAMETER_VALUE_PK_BASE + 5
						: PARAMETER_VALUE_PK_BASE + 6;
					final int storagePick = productPk <= 15
						? PARAMETER_VALUE_PK_BASE + 7
						: PARAMETER_VALUE_PK_BASE + 8;

					var productBuilder = session.createNewEntity(PRODUCT, productPk)
						.setAttribute(ATTR_CODE, "product-" + productPk)
						.setReference(CATEGORIES_REF, LAPTOP_CATEGORY_PK);

					if (evenSlot) {
						productBuilder = productBuilder.setReference(
							PARAMETER_VALUES_REF, ramPick,
							ref -> ref.setGroup(PARAMETER, RAM_PARAMETER_PK)
						);
					} else {
						productBuilder = productBuilder
							.setReference(
								PARAMETER_VALUES_REF, cpuPick,
								ref -> ref.setGroup(PARAMETER, CPU_PARAMETER_PK)
							)
							.setReference(
								PARAMETER_VALUES_REF, screenPick,
								ref -> ref.setGroup(PARAMETER, SCREEN_PARAMETER_PK)
							);
					}
					session.upsertEntity(
						productBuilder.setReference(
							PARAMETER_VALUES_REF, storagePick,
							ref -> ref.setGroup(PARAMETER, STORAGE_PARAMETER_PK)
						)
					);
				}
			}
		);

		final Set<Integer> evenProductPks = IntStream.rangeClosed(1, PRODUCT_COUNT)
			.filter(pk -> pk % 2 == 0)
			.boxed()
			.collect(Collectors.toUnmodifiableSet());
		// even products whose RAM slot rotation picks ram-16gb (the second of the two ram values)
		final Set<Integer> evenProductPksWithRam16Gb = IntStream.rangeClosed(1, PRODUCT_COUNT)
			.filter(pk -> pk % 2 == 0)
			.filter(pk -> (pk / 2) % 2 != 0)
			.boxed()
			.collect(Collectors.toUnmodifiableSet());

		return new DataCarrier(
			"evenProductPks", evenProductPks,
			"evenProductPksWithRam16Gb", evenProductPksWithRam16Gb
		);
	}

	@Nested
	@DisplayName("groupHaving alone (reference-side only)")
	class PureGroupHaving {

		@DisplayName("Should return all even-indexed products via groupHaving(attributeEquals ram-memory)")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldFilterByGroupAttributeEquals(
			@Nonnull Evita evita, @Nonnull Set<Integer> evenProductPks
		) {
			// The pre-fix bug returns zero results here because the inner formula yields Parameter PK
			// 10_001, which the parameterValues REFERENCED_ENTITY_TYPE index (keyed by PV PKs in
			// [20_001, 20_010]) cannot resolve to any reduced-index PK.
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(attributeEquals(ATTR_CODE, "ram-memory"))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(evenProductPks.size(), actual.size(), "Should match all even-indexed products");
			assertEquals(evenProductPks, Set.copyOf(actual));
		}

		@DisplayName("Should return all even-indexed products via groupHaving(entityPrimaryKeyInSet RAM)")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldFilterByGroupEntityPrimaryKeyInSet(
			@Nonnull Evita evita, @Nonnull Set<Integer> evenProductPks
		) {
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(entityPrimaryKeyInSet(RAM_PARAMETER_PK))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(evenProductPks, Set.copyOf(actual));
		}

		@DisplayName("Should union products across multiple group PKs")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldFilterByMultipleGroupPks(@Nonnull Evita evita) {
			// CPU is on odd-indexed products. RAM is on even-indexed products. Together: all 30.
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(entityPrimaryKeyInSet(RAM_PARAMETER_PK, CPU_PARAMETER_PK))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(PRODUCT_COUNT, actual.size(),
				"Both RAM and CPU groups together cover all 30 products");
		}

		@DisplayName("Should return every product when filtering by STORAGE group (carried by all)")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldFilterByGroupCarriedByEveryProduct(@Nonnull Evita evita) {
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(entityPrimaryKeyInSet(STORAGE_PARAMETER_PK))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(PRODUCT_COUNT, actual.size(), "Every product carries the STORAGE group");
		}
	}

	@Nested
	@DisplayName("hierarchyWithin + groupHaving (the original failing pattern)")
	@Tag(HIERARCHY)
	class HierarchyAndGroupHaving {

		@DisplayName("Should return even-indexed laptops under hierarchy when filtered by ram-memory group")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldFilterByHierarchyAndGroup(
			@Nonnull Evita evita, @Nonnull Set<Integer> evenProductPks
		) {
			// Mirrors the user-reported failing demo query: hierarchyWithin(categories) + groupHaving.
			// All 30 products live under laptops; only 15 carry RAM (even-indexed).
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						hierarchyWithin(CATEGORIES_REF, attributeEquals(ATTR_CODE, "laptops")),
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(attributeEquals(ATTR_CODE, "ram-memory"))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(evenProductPks, Set.copyOf(actual));
		}
	}

	@Nested
	@DisplayName("entityHaving combined with groupHaving on same reference")
	class CombinedEntityAndGroupHaving {

		@DisplayName("Should AND entityHaving and groupHaving for the same reference")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldAndEntityAndGroupHaving(
			@Nonnull Evita evita, @Nonnull Set<Integer> evenProductPksWithRam16Gb
		) {
			// Even products carry ram-8gb (PV) OR ram-16gb depending on slot. Filter to those
			// having ram-memory group AND ram-16gb value - i.e. those where the ram slot rotation
			// picked 16gb.
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							entityHaving(attributeEquals(ATTR_CODE, "ram-16gb")),
							groupHaving(attributeEquals(ATTR_CODE, "ram-memory"))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(evenProductPksWithRam16Gb, Set.copyOf(actual),
				"AND of entityHaving + groupHaving must narrow correctly");
		}

		@DisplayName("Should still narrow by groupHaving when entityHaving is empty filter")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldNarrowByGroupHavingAloneEvenWhenEntityHavingMatchesAll(
			@Nonnull Evita evita, @Nonnull Set<Integer> evenProductPks
		) {
			// entityHaving(attributeStartsWith("ram-")) matches both ram-8gb and ram-16gb.
			// Combined with groupHaving(ram-memory) this should still be the RAM-carrying products.
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							entityHaving(attributeStartsWith(ATTR_CODE, "ram-")),
							groupHaving(attributeEquals(ATTR_CODE, "ram-memory"))
						)
					),
					require(page(1, 50))
				)
			);
			assertEquals(evenProductPks, Set.copyOf(actual));
		}
	}

	@Nested
	@DisplayName("Empty and negative cases")
	@Tag(FACET)
	class EmptyAndNegativeCases {

		@DisplayName("Should return empty result when no product carries the group")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldReturnEmptyWhenNoProductHasGroup(@Nonnull Evita evita) {
			// WEIGHT group exists but no product references it.
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(entityPrimaryKeyInSet(WEIGHT_PARAMETER_PK))
						)
					),
					require(page(1, 50))
				)
			);
			assertTrue(actual.isEmpty(), "WEIGHT is unused on every product");
		}

		@DisplayName("Should return empty result for unknown group PK")
		@UseDataSet(DISJOINT_PK_PARAMETERS)
		@Test
		void shouldReturnEmptyForUnknownGroupPk(@Nonnull Evita evita) {
			final List<Integer> actual = queryProductPks(
				evita,
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							PARAMETER_VALUES_REF,
							groupHaving(entityPrimaryKeyInSet(Integer.MAX_VALUE))
						)
					),
					require(page(1, 50))
				)
			);
			assertTrue(actual.isEmpty(), "Unknown group PK must not coincidentally match anything");
		}
	}

	// --- helpers ----------------------------------------------------------------

	/**
	 * Inserts a {@link #PARAMETER} entity at the given PK with the given {@code code} attribute.
	 */
	private static void upsertParameter(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull String code
	) {
		session.upsertEntity(
			session.createNewEntity(PARAMETER, pk).setAttribute(ATTR_CODE, code)
		);
	}

	/**
	 * Inserts a {@link #PARAMETER_VALUE} entity at the given PK with the given {@code code} attribute.
	 */
	private static void upsertParameterValue(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull String code
	) {
		session.upsertEntity(
			session.createNewEntity(PARAMETER_VALUE, pk).setAttribute(ATTR_CODE, code)
		);
	}

	/**
	 * Executes the given query as a reference-PK lookup against the {@link #PRODUCT} collection
	 * and returns the resulting primary keys as a {@link List}.
	 */
	@Nonnull
	private static List<Integer> queryProductPks(
		@Nonnull Evita evita, @Nonnull io.evitadb.api.query.Query queryToRun
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> response =
					session.queryEntityReference(queryToRun);
				return response.getRecordData()
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.toList();
			}
		);
	}

}
