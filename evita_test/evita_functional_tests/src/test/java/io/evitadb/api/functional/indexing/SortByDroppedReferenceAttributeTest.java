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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyExact;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.strip;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for sorting on a reference attribute whose sortable value has been soft-deleted on at least
 * one referenced target. Sorting by a `referenceProperty` in that state used to duplicate the affected entity
 * in the result page (and crash on `entityFetch(referenceContent(...))` via the `Collectors.toMap`
 * duplicate-key in `EntityIndexSupplier`).
 *
 * Root cause: the lazy-fill paths in `EntityDecorator` (`getFilteredReferencesByName` and the sibling
 * `getFilteredReferences`) exposed raw `Reference` objects when references were resolved lazily for the sort
 * path, bypassing the attribute predicate (and therefore the `dropped()` filter); the comparator then read
 * the historical attribute value and treated the entity as sortable while the underlying `SortIndex` correctly
 * excluded it.
 *
 * The high-fidelity reproducer lives in `MilagroCzReproductionTest` (production snapshot, gated by
 * `-Dmilagro.repro=true`). The cases below run on a minimal in-memory catalog and lock the public contract
 * — sorting on a reference attribute that is soft-deleted on at least one referenced target must never
 * duplicate primary keys in the result page nor crash on reference fetch — across the three query shapes
 * (simple referenceProperty + referenceContent, attribute-only fetch, traverseBy variant). They do not in
 * isolation reproduce the production duplication on this small fixture (the planner takes a different sort
 * path at this scale); they guard the post-fix invariant under CI where the snapshot is unavailable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sort by reference attribute that is soft-deleted on one reference must not duplicate result PKs")
@Tag(FUNCTIONAL_TEST)
public class SortByDroppedReferenceAttributeTest implements EvitaTestSupport {
	private static final String DIR = "sortByDroppedReferenceAttributeTest";
	private static final String DIR_EXPORT = "sortByDroppedReferenceAttributeTest_export";
	private static final String GROUPS = "groups";
	private static final String ATTRIBUTE_ORDER_IN_GROUP = "orderInGroup";
	private static final String ATTRIBUTE_ASSIGNMENT_PRIORITY = "assignmentPriority";
	private static final int GROUP_1 = 1;
	private static final int GROUP_2 = 2;
	private static final int PRODUCT_A = 100;
	private static final int PRODUCT_B = 101;
	private static final int PRODUCT_C = 102;

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		defineSchema();
		seedDataAndGoLive();
		dropOrderInGroupOnOneReference();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
	}

	@DisplayName("Compound referenceProperty sort with entityFetch(referenceContent) must not throw for a soft-deleted sortable reference attribute")
	@Test
	void shouldNotCrashOnDroppedReferenceAttributeWithReferenceContent() {
		assertDoesNotThrow(
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						Query.query(
							collection(Entities.PRODUCT),
							filterBy(referenceHaving(GROUPS, entityPrimaryKeyInSet(GROUP_1))),
							orderBy(
								referenceProperty(
									GROUPS,
									attributeNatural(ATTRIBUTE_ORDER_IN_GROUP, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_ASSIGNMENT_PRIORITY, OrderDirection.ASC)
								)
							),
							require(strip(0, 20), entityFetch(referenceContent(GROUPS)))
						)
					);
					assertResultPageContainsAllProductsInExpectedOrder(response.getRecordPage().getData());
					return null;
				}
			),
			"querying with compound reference-attribute sort + entityFetch(referenceContent) must not throw Duplicate key"
		);
	}

	@DisplayName("Result page must contain unique PKs when entityFetch omits referenceContent (compound sort, no traverse)")
	@Test
	void shouldNotDuplicateRecordsWhenReferenceContentIsNotFetched() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> response = session.querySealedEntity(
					Query.query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(GROUPS, entityPrimaryKeyInSet(GROUP_1))),
						orderBy(
							referenceProperty(
								GROUPS,
								attributeNatural(ATTRIBUTE_ORDER_IN_GROUP, OrderDirection.ASC),
								attributeNatural(ATTRIBUTE_ASSIGNMENT_PRIORITY, OrderDirection.ASC)
							)
						),
						require(strip(0, 20), entityFetch(attributeContent()))
					)
				);
				assertUniquePrimaryKeys(response.getRecordPage().getData());
				return null;
			}
		);
	}

	@DisplayName("Compound referenceProperty sort with traverseBy must not duplicate result PKs")
	@Test
	void shouldNotDuplicateRecordsWithTraverseBy() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> response = session.querySealedEntity(
					Query.query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(GROUPS, entityPrimaryKeyInSet(GROUP_1))),
						orderBy(
							referenceProperty(
								GROUPS,
								attributeNatural(ATTRIBUTE_ORDER_IN_GROUP, OrderDirection.ASC),
								attributeNatural(ATTRIBUTE_ASSIGNMENT_PRIORITY, OrderDirection.ASC),
								traverseByEntityProperty(entityPrimaryKeyExact(GROUP_1))
							)
						),
						require(strip(0, 20), entityFetch(attributeContent()))
					)
				);
				assertUniquePrimaryKeys(response.getRecordPage().getData());
				return null;
			}
		);
	}

	/**
	 * Defines the catalog schema used across all test cases: a `PARAMETER_GROUP` entity and a `PRODUCT` entity
	 * with a `ZERO_OR_MORE` reference to `PARAMETER_GROUP`, carrying two nullable sortable attributes
	 * (`orderInGroup` and `assignmentPriority`) that form the compound sort key under test.
	 */
	private void defineSchema() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						GROUPS,
						Entities.PARAMETER_GROUP,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexed()
							.withAttribute(
								ATTRIBUTE_ORDER_IN_GROUP, Integer.class,
								// nullable so that the soft-deleted state can legally exist on the reference;
								// the absence of the value is the regression scenario
								thatIs -> thatIs.sortable().nullable()
							)
							.withAttribute(
								ATTRIBUTE_ASSIGNMENT_PRIORITY, Long.class,
								// second sort key exercises the compound comparator path which is where
								// the duplication used to surface in production under the compound comparator
								thatIs -> thatIs.sortable().nullable()
							)
					)
					.updateVia(session);

				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Creates two groups and three products in the warm-up catalog, assigns each product to group 1
	 * (and product A also to group 2) with initial sort attribute values, then transitions the catalog to live mode.
	 */
	private void seedDataAndGoLive() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PARAMETER_GROUP, GROUP_1).upsertVia(session);
				session.createNewEntity(Entities.PARAMETER_GROUP, GROUP_2).upsertVia(session);

				// product A belongs to both groups; its orderInGroup on group 1 will be soft-deleted in tx2
				session.createNewEntity(Entities.PRODUCT, PRODUCT_A)
					.setReference(
						GROUPS, GROUP_1,
						thatIs -> thatIs
							.setAttribute(ATTRIBUTE_ORDER_IN_GROUP, 5)
							.setAttribute(ATTRIBUTE_ASSIGNMENT_PRIORITY, 100L)
					)
					.setReference(
						GROUPS, GROUP_2,
						thatIs -> thatIs
							.setAttribute(ATTRIBUTE_ORDER_IN_GROUP, 50)
							.setAttribute(ATTRIBUTE_ASSIGNMENT_PRIORITY, 500L)
					)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, PRODUCT_B)
					.setReference(
						GROUPS, GROUP_1,
						thatIs -> thatIs
							.setAttribute(ATTRIBUTE_ORDER_IN_GROUP, 1)
							.setAttribute(ATTRIBUTE_ASSIGNMENT_PRIORITY, 200L)
					)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, PRODUCT_C)
					.setReference(
						GROUPS, GROUP_1,
						thatIs -> thatIs
							.setAttribute(ATTRIBUTE_ORDER_IN_GROUP, 3)
							.setAttribute(ATTRIBUTE_ASSIGNMENT_PRIORITY, 300L)
					)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Removes the `orderInGroup` attribute from product A's reference to group 1 in a separate transaction so the
	 * resulting {@code AttributeValue} carries {@code dropped() == true} instead of being absent. The dropped tombstone
	 * is exactly the production state that triggers the duplication bug.
	 */
	private void dropOrderInGroupOnOneReference() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productA = session.getEntity(
					Entities.PRODUCT, PRODUCT_A, referenceContent(GROUPS)
				).orElseThrow();

				productA.openForWrite()
					.setReference(GROUPS, GROUP_1, thatIs -> thatIs.removeAttribute(ATTRIBUTE_ORDER_IN_GROUP))
					.upsertVia(session);
			}
		);
		// fixture-integrity assertion skipped on purpose: the public read-back path filters dropped attribute
		// values via the decorator's attribute predicate, so the tombstone shape is not observable through
		// SealedEntity; the milagro reproducer harness covers the storage-layer tombstone separately
	}

	/**
	 * Asserts that no primary key appears more than once in the given entity list, failing with a descriptive
	 * message that names the duplicate key when the sorter contract is broken.
	 */
	private static void assertUniquePrimaryKeys(@Nonnull List<SealedEntity> entities) {
		final Set<Integer> seen = new HashSet<>(entities.size());
		for (final SealedEntity entity : entities) {
			final int pk = entity.getPrimaryKeyOrThrowException();
			assertTrue(
				seen.add(pk),
				"Duplicate primary key " + pk + " in result page — sorter contract broken"
			);
		}
	}

	/**
	 * Locks the post-fix observable contract for the result page: it must contain exactly the three
	 * seeded products (`PRODUCT_A`, `PRODUCT_B`, `PRODUCT_C`), have no duplicate primary keys, and
	 * `PRODUCT_B` (orderInGroup = 1) must precede `PRODUCT_C` (orderInGroup = 3) in the ASC sort.
	 * `PRODUCT_A`'s position is intentionally not asserted because its `orderInGroup` on `GROUP_1`
	 * is the soft-deleted tombstone — its placement is the implementation choice for null sort-keys
	 * and is therefore not part of the public contract under test.
	 */
	private static void assertResultPageContainsAllProductsInExpectedOrder(@Nonnull List<SealedEntity> entities) {
		assertUniquePrimaryKeys(entities);
		final Set<Integer> pks = new HashSet<>(entities.size());
		int positionB = -1;
		int positionC = -1;
		for (int i = 0; i < entities.size(); i++) {
			final int pk = entities.get(i).getPrimaryKeyOrThrowException();
			pks.add(pk);
			if (pk == PRODUCT_B) {
				positionB = i;
			} else if (pk == PRODUCT_C) {
				positionC = i;
			}
		}
		assertEquals(
			Set.of(PRODUCT_A, PRODUCT_B, PRODUCT_C), pks,
			"Result page must contain exactly PRODUCT_A, PRODUCT_B and PRODUCT_C"
		);
		assertTrue(
			positionB >= 0 && positionC >= 0 && positionB < positionC,
			"PRODUCT_B (orderInGroup=1) must precede PRODUCT_C (orderInGroup=3) in ASC sort"
		);
	}

	/**
	 * Builds the minimal {@link EvitaConfiguration} for the embedded test instance: infinite session timeout,
	 * test-scoped storage directory, and a dedicated export directory.
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.storage(StorageOptions.builder().storageDirectory(getTestDirectory().resolve(DIR)).build())
			.export(FileSystemExportOptions.builder().directory(getTestDirectory().resolve(DIR_EXPORT)).build())
			.build();
	}
}
