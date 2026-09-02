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

package io.evitadb.core.collection;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.CatalogUnpublishableException;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WARM_UP counterpart of {@link EntityAtomicMutationRollbackFunctionalTest}: it drives the very same failing
 * entity through a catalog that is still being bulk loaded, where writes go IN PLACE to the index delegates instead of
 * to a transaction's diff layers.
 *
 * Its job is to pin down, as executable assertions, that a failing entity leaves the warm-up path exactly as it found
 * it — the atomicity the transactional path already provides, reached here by {@link WarmUpSavepoint} rewinding the
 * in-place writes from the inverses the structures journal themselves. All three scenarios are a batch of three
 * entities in which the middle one violates a unique constraint after its index writes have already been applied, and
 * they differ only in WHICH index families the mutation reaches before it fails:
 *
 * - **The early failure** aborts at the very first index the entity reaches, so the only state to rewind is the
 *   membership bitmap, the collection's storage diff layer and the indexes' dirty flags.
 * - **The late failure** submits an explicitly ORDERED upsert mutation whose duplicate code sits last, so the entity is
 *   already in the hierarchy, sort, filter, range and price indexes — five B+ tree-backed structures — before it fails.
 * - **The facet failure** does the same for the reference side: two grouped, faceted references are written first, so
 *   the entity is already in the reference index, the reference-type cardinality index and the facet index family
 *   (facet index, per-reference index, per-facet-id bitmaps, group index) when the duplicate code aborts it. Were it
 *   left behind, its facet entry would be an ORPHAN — a facet counted forever against an entity that cannot be fetched.
 *
 * **What every scenario asserts is that the divergence is absent**: the failed entity's primary key is gone from the
 * collection's membership index, so a reference query and a body fetch agree on the same three products, no index it
 * reached on the way still answers for it, and the catalog keeps taking writes afterwards. The mechanism is
 * unconditional, so this is simply how a bulk load behaves — there is no configuration under which the partial state
 * survives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Per-entity atomic mutation rollback in warm-up mode")
@Tag(TestTags.ENGINE)
@Tag(TestTags.INDEXING)
class EntityAtomicMutationRollbackWarmUpFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_SORTABLE_CODE = "sortableCode";
	private static final String ATTRIBUTE_VALIDITY = "validity";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String REFERENCE_PARAMETER = "parameter";
	private static final int PARAMETER_GROUP_PRIMARY_KEY = 900;
	private static final int PARAMETER_ONE = 901;
	private static final int PARAMETER_TWO = 902;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final OffsetDateTime VALIDITY_START =
		OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime WITHIN_VALIDITY =
		OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final DateTimeRange VALIDITY =
		DateTimeRange.between(VALIDITY_START, VALIDITY_START.plusYears(1));
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EntityAtomicMutationRollbackWarmUpFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);

		// define a product schema carrying a unique code and seed product #1 (code "A"). The catalog deliberately stays
		// in WARM_UP - it is never taken live - so every write below goes in place to the index delegates.
		// The hierarchy, the sortable/filterable code, the validity range and the price exist for the LATE-failure
		// scenario: they are what puts a sort index, a filter index, a range index, a hierarchy index and a price index
		// between the start of an entity mutation and the unique-code check that aborts it
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PARAMETER)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
					.withAttribute(
						ATTRIBUTE_SORTABLE_CODE, String.class, whichIs -> whichIs.filterable().sortable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable()
					)
					// the grouped, faceted, indexed reference of the FACET scenario: it is what puts a reference index,
					// a reference-type cardinality index and the whole facet index family (reference -> facet id ->
					// group) between the start of an entity mutation and the unique-code check that aborts it
					.withReferenceToEntity(
						REFERENCE_PARAMETER, Entities.PARAMETER, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexed().faceted()
							.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
					)
					.withPrice()
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PARAMETER_GROUP, PARAMETER_GROUP_PRIMARY_KEY)
				);
				session.upsertEntity(session.createNewEntity(Entities.PARAMETER, PARAMETER_ONE));
				session.upsertEntity(session.createNewEntity(Entities.PARAMETER, PARAMETER_TWO));

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "A")
				);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Failure at the first index the entity reaches")
	class EarlyFailure {

		/**
		 * Pins the reach of the mechanism for the narrowest failure shape there is.
		 *
		 * What the scenario actually exercises, and why that is the whole of it: the failing entity reaches the unique
		 * index only after its primary key has been written to the collection's membership bitmap, and
		 * `EntityIndex#upsertAttribute` runs the unique insert BEFORE the filter one, while the unique insert itself
		 * checks the value is free before writing its own tree. So the duplicate code aborts the mutation with exactly
		 * three kinds of state touched — the membership bitmap, the collection's storage diff layer, and the indexes'
		 * dirty flags — and all three journal their warm-up writes.
		 *
		 * That also says what this test does NOT prove: it never reaches a B+ tree. {@link LateFailure} is the one
		 * that does.
		 */
		@Test
		@DisplayName("The failed entity leaves no orphan primary key behind")
		@Tag(TestTags.ATTRIBUTE)
		void shouldLeaveNoOrphanPrimaryKeyBehind() {
			runFailingBatch();

			assertProductReferencesAre(1, 2, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);
		}

		@Test
		@DisplayName("The surrounding batch is unaffected and the catalog keeps accepting writes")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepWritingAfterTheFailedEntity() {
			runFailingBatch();

			// everything the batch legitimately wrote survived the bracketed failure, and the unique index is intact
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);

			// a later session still flushes: the rollback completed, so nothing raised the catalog's barrier
			EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 5).setAttribute(ATTRIBUTE_CODE, "D")
					);
				}
			);
			assertCodeResolvesTo("D", 5);
		}
	}

	@Nested
	@DisplayName("Failure after the filter, sort, range, hierarchy and price writes")
	class LateFailure {

		/**
		 * The scenario {@link EarlyFailure} explicitly could not reach: the duplicate code is written LAST, so the
		 * mutation aborts only once the entity has already been inserted into the hierarchy index, the sort index (an
		 * unordered lookup tree plus its order-key B+ tree), the filter index (a bucket B+ tree), the range index (a
		 * long-keyed B+ tree) and the price index (an element-keyed B+ tree). Every one of those is a structure the
		 * early scenario never touched.
		 *
		 * Each assertion below is answered by a different index, so a family left un-journaled shows up as its own
		 * failure rather than as one blanket one.
		 */
		@Test
		@DisplayName("An entity failing after those writes recovers fully")
		@Tag(TestTags.ATTRIBUTE)
		@Tag(TestTags.PRICE)
		@Tag(TestTags.HIERARCHY)
		void shouldRecoverFromLateFailure() {
			runLateFailingBatch();

			// membership and bodies agree - no orphan primary key
			assertProductReferencesAre(1, 2, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);

			// the unique index took no entry for the failed code, and the successful ones are intact
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);

			// filter + sort index (bucket tree, unordered lookup tree, order-key tree): the failed entity's sortable
			// code is gone, and the ordered result of the surviving ones is complete and correctly ordered
			assertQueryReturns(
				"the failed entity must leave no entry in the filter index",
				filterBy(attributeEquals(ATTRIBUTE_SORTABLE_CODE, "S3"))
			);
			assertOrderedQueryReturns(
				"the sort index must rank exactly the surviving entities",
				filterBy(attributeIs(ATTRIBUTE_SORTABLE_CODE, AttributeSpecialValue.NOT_NULL)),
				orderBy(attributeNatural(ATTRIBUTE_SORTABLE_CODE, OrderDirection.ASC)),
				2, 4
			);

			// range index (long-keyed tree): only the surviving entities are valid at the probed moment
			assertQueryReturns(
				"the range index must cover exactly the surviving entities",
				filterBy(attributeInRange(ATTRIBUTE_VALIDITY, WITHIN_VALIDITY)),
				2, 4
			);

			// price index (element-keyed tree): the failed entity contributes no price
			assertQueryReturns(
				"the price index must cover exactly the surviving entities",
				filterBy(priceInPriceLists(PRICE_LIST_BASIC), priceInCurrency(CURRENCY_CZK)),
				2, 4
			);

			// hierarchy index: the failed entity is no longer a child of #1
			assertQueryReturns(
				"the hierarchy index must hold exactly the surviving entities",
				filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1))),
				1, 2, 4
			);
		}

		@Test
		@DisplayName("The catalog keeps accepting writes after the failure was rolled back")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepWritingAfterALateFailure() {
			runLateFailingBatch();

			// the code the failed entity tried to claim is free again for a fresh entity, and every index it touched
			// accepts the new record on top of the restored state
			EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(lateProduct(5, "D", "S3", 50));
				}
			);

			assertCodeResolvesTo("D", 5);
			assertQueryReturns(
				"the sortable code freed by the rollback must be claimable again",
				filterBy(attributeEquals(ATTRIBUTE_SORTABLE_CODE, "S3")),
				5
			);
			assertQueryReturns(
				"the price index must accept the record written after the rollback",
				filterBy(priceInPriceLists(PRICE_LIST_BASIC), priceInCurrency(CURRENCY_CZK)),
				2, 4, 5
			);
			assertQueryReturns(
				"the hierarchy index must accept the child added after the rollback",
				filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1))),
				1, 2, 4, 5
			);
		}
	}

	@Nested
	@DisplayName("Failure after the reference and facet writes")
	class ReferenceAndFacetFailure {

		/**
		 * The reference and facet families, which neither failure shape above reaches: the failing entity is inserted
		 * into the reference index, the reference-type cardinality index and the whole facet index family (the facet
		 * index, its per-reference index, its per-facet-id bitmaps and its group index) BEFORE the duplicate code
		 * aborts it.
		 *
		 * The failure this pins down is the orphan facet — a facet entry pointing at an entity that does not exist —
		 * which is worse than a missing one, because facet computation keeps counting it forever. Each assertion is
		 * answered by a different structure: the reference index by `referenceHaving`, the facet index and its id
		 * bitmaps by `facetHaving`, and the group index by the facet summary's per-facet counts.
		 */
		@Test
		@DisplayName("An entity failing after those writes leaves no orphan facet behind")
		@Tag(TestTags.FACET)
		@Tag(TestTags.REFERENCE)
		void shouldRecoverFromReferenceAndFacetFailure() {
			runFacetFailingBatch();

			// membership and bodies agree - no orphan primary key
			assertProductReferencesAre(1, 2, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);

			// reference index and reference-type cardinality index: both parameters resolve to the survivors only
			assertQueryReturns(
				"the reference index must hold exactly the surviving entities",
				filterBy(referenceHaving(REFERENCE_PARAMETER, entityPrimaryKeyInSet(PARAMETER_ONE))),
				2, 4
			);
			assertQueryReturns(
				"the second reference must hold exactly the surviving entities too",
				filterBy(referenceHaving(REFERENCE_PARAMETER, entityPrimaryKeyInSet(PARAMETER_TWO))),
				2, 4
			);

			// facet index + per-facet-id bitmaps: filtering by either facet returns the survivors, never the reverted
			// entity whose body was never stored
			assertFacetResolvesExactlyTo(PARAMETER_ONE, 2, 4);
			assertFacetResolvesExactlyTo(PARAMETER_TWO, 2, 4);

			// facet group index: the summary counts both facets of the group at exactly the surviving entities, so a
			// leaked entry would show up as an inflated count even where the filter happened to hide it
			assertFacetCountsAre(2, 2);
		}

		@Test
		@DisplayName("The catalog keeps accepting faceted writes after the failure was rolled back")
		@Tag(TestTags.FACET)
		@Tag(TestTags.REFERENCE)
		void shouldKeepWritingFacetedEntitiesAfterAFailure() {
			runFacetFailingBatch();

			// the reverted entity's facet slots are free again, and the restored indexes take a new record on top
			EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(facetedProduct(5, "D"));
				}
			);

			assertCodeResolvesTo("D", 5);
			assertFacetResolvesExactlyTo(PARAMETER_ONE, 2, 4, 5);
			assertFacetResolvesExactlyTo(PARAMETER_TWO, 2, 4, 5);
			assertFacetCountsAre(3, 3);
		}
	}

	/**
	 * Runs the LATE-failure scenario in a single warm-up session. It is the same three-entity shape as
	 * {@link #runFailingBatch()}, except each entity is submitted as an explicitly ordered
	 * {@link EntityUpsertMutation} whose duplicate unique code sits LAST — so the failure strikes only after the
	 * hierarchy, sort, filter, range and price indexes have all been written.
	 *
	 * The ordering has to be explicit: an entity built through the fluent builder emits its attribute mutations in
	 * {@code HashMap} iteration order, which would decide by hash whether the failing code is written before or after
	 * the attributes whose indexes this scenario exists to exercise.
	 */
	private void runLateFailingBatch() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(lateProduct(2, "B", "S2", 20));

				assertThrows(
					UniqueValueViolationException.class,
					// duplicate of entity #1's code → fails once every other index of this entity is already written
					() -> session.upsertEntity(lateProduct(3, "A", "S3", 30))
				);

				session.upsertEntity(lateProduct(4, "C", "S4", 40));
			}
		);
	}

	/**
	 * Builds one product of the late-failure scenario as an explicitly ordered upsert mutation: parent, sortable code,
	 * validity range, price, and only then the unique code that may abort the whole thing.
	 *
	 * @param primaryKey   the product's primary key
	 * @param code         the unique code — "A" makes the mutation fail on entity #1's reservation
	 * @param sortableCode the filterable + sortable code, written into the sort and filter indexes
	 * @param priceId      the price id, unique per product so the price index gets a distinct record
	 * @return the ordered upsert mutation
	 */
	@Nonnull
	private EntityMutation lateProduct(
		int primaryKey, @Nonnull String code, @Nonnull String sortableCode, int priceId
	) {
		return new EntityUpsertMutation(
			Entities.PRODUCT,
			primaryKey,
			EntityExistence.MUST_NOT_EXIST,
			new SetParentMutation(1),
			new UpsertAttributeMutation(ATTRIBUTE_SORTABLE_CODE, sortableCode),
			new UpsertAttributeMutation(ATTRIBUTE_VALIDITY, VALIDITY),
			new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE),
			new UpsertPriceMutation(
				priceId, PRICE_LIST_BASIC, CURRENCY_CZK, null,
				BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
			),
			new UpsertAttributeMutation(ATTRIBUTE_CODE, code)
		);
	}

	/**
	 * Runs the FACET-failure scenario in a single warm-up session. Same three-entity shape as
	 * {@link #runFailingBatch()}, except every product carries two grouped, faceted references written BEFORE its
	 * unique code — so the failure strikes once the reference index, the reference-type cardinality index and the facet
	 * index family have all taken the failing entity.
	 */
	private void runFacetFailingBatch() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(facetedProduct(2, "B"));

				assertThrows(
					UniqueValueViolationException.class,
					// duplicate of entity #1's code -> fails once both references and their facets are already written
					() -> session.upsertEntity(facetedProduct(3, "A"))
				);

				session.upsertEntity(facetedProduct(4, "C"));
			}
		);
	}

	/**
	 * Builds one product of the facet-failure scenario as an explicitly ordered upsert mutation: two references, each
	 * assigned to the shared parameter group, and only then the unique code that may abort the whole thing.
	 *
	 * The ordering has to be explicit for the same reason {@link #lateProduct} spells its mutations out: an entity
	 * built through the fluent builder emits its mutations in hash order, which would decide by hash whether the facet
	 * writes this scenario exists to exercise happen before or after the failing code.
	 *
	 * @param primaryKey the product's primary key
	 * @param code       the unique code — "A" makes the mutation fail on entity #1's reservation
	 * @return the ordered upsert mutation
	 */
	@Nonnull
	private EntityMutation facetedProduct(int primaryKey, @Nonnull String code) {
		final ReferenceKey firstParameter = new ReferenceKey(REFERENCE_PARAMETER, PARAMETER_ONE);
		final ReferenceKey secondParameter = new ReferenceKey(REFERENCE_PARAMETER, PARAMETER_TWO);
		return new EntityUpsertMutation(
			Entities.PRODUCT,
			primaryKey,
			EntityExistence.MUST_NOT_EXIST,
			new InsertReferenceMutation(firstParameter),
			new SetReferenceGroupMutation(
				firstParameter, Entities.PARAMETER_GROUP, PARAMETER_GROUP_PRIMARY_KEY
			),
			new InsertReferenceMutation(secondParameter),
			new SetReferenceGroupMutation(
				secondParameter, Entities.PARAMETER_GROUP, PARAMETER_GROUP_PRIMARY_KEY
			),
			new UpsertAttributeMutation(ATTRIBUTE_CODE, code)
		);
	}

	/**
	 * Asserts that filtering products by one parameter facet returns exactly the supplied primary keys. A reverted
	 * entity whose facet entry leaked shows up here as an extra primary key that cannot be fetched — the orphan facet.
	 *
	 * @param facetPrimaryKey     the parameter whose facet is being filtered on
	 * @param expectedPrimaryKeys the complete set of products expected to carry that facet
	 */
	private void assertFacetResolvesExactlyTo(int facetPrimaryKey, int... expectedPrimaryKeys) {
		assertQueryReturns(
			"the facet of parameter #" + facetPrimaryKey + " must resolve to exactly the surviving entities",
			filterBy(userFilter(facetHaving(REFERENCE_PARAMETER, entityPrimaryKeyInSet(facetPrimaryKey)))),
			expectedPrimaryKeys
		);
	}

	/**
	 * Asserts the facet summary counts both parameter facets of the shared group at exactly the supplied numbers. This
	 * is the assertion the facet GROUP index answers: the counts are accumulated per group, so an entry left behind by
	 * a reverted entity inflates them even in cases where a filter would not reveal it.
	 *
	 * @param expectedFirstCount  how many products the first parameter's facet must count
	 * @param expectedSecondCount how many products the second parameter's facet must count
	 */
	private void assertFacetCountsAre(int expectedFirstCount, int expectedSecondCount) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(collection(Entities.PRODUCT), require(facetSummary())),
					EntityReference.class
				);
				final FacetSummary summary = response.getExtraResult(FacetSummary.class);
				assertNotNull(summary, "The facet summary must be computed!");

				final ReferenceGroupStatistics groupStatistics =
					summary.getFacetGroupStatistics(REFERENCE_PARAMETER, PARAMETER_GROUP_PRIMARY_KEY);
				assertNotNull(
					groupStatistics,
					"The parameter group must still be present in the facet summary!"
				);
				assertFacetCountIs(groupStatistics, PARAMETER_ONE, expectedFirstCount);
				assertFacetCountIs(groupStatistics, PARAMETER_TWO, expectedSecondCount);
				return null;
			}
		);
	}

	/**
	 * Asserts one facet of a group is counted at the expected number of products.
	 *
	 * @param groupStatistics the group the facet belongs to
	 * @param facetPrimaryKey the facet being counted
	 * @param expectedCount   the number of products the facet must be counted at
	 */
	private static void assertFacetCountIs(
		@Nonnull ReferenceGroupStatistics groupStatistics, int facetPrimaryKey, int expectedCount
	) {
		final FacetStatistics facetStatistics = groupStatistics.getFacetStatistics(facetPrimaryKey);
		assertNotNull(
			facetStatistics,
			"Parameter #" + facetPrimaryKey + " must still be counted in the facet summary!"
		);
		assertEquals(
			expectedCount, facetStatistics.getCount(),
			"The facet of parameter #" + facetPrimaryKey + " must be counted at exactly the surviving entities!"
		);
	}

	/**
	 * Runs the scenario in a single warm-up session: product #2 succeeds, #3 fails on the duplicate code "A" after its
	 * index writes were applied, #4 succeeds. The failure is swallowed the way a bulk loader would swallow it, so the
	 * session goes on to close and flush.
	 */
	private void runFailingBatch() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
				);

				assertThrows(
					UniqueValueViolationException.class,
					() -> session.upsertEntity(
						// duplicate of entity #1's code → fails after partially touching the indexes
						session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
					)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 4).setAttribute(ATTRIBUTE_CODE, "C")
				);
			}
		);
	}

	/**
	 * Asserts that a plain product query — which is answered from the collection's membership index alone, without
	 * reading a single storage part — returns exactly the supplied primary keys.
	 *
	 * @param expectedPrimaryKeys the complete set of primary keys the index is expected to hold
	 */
	private void assertProductReferencesAre(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(collection(Entities.PRODUCT)),
					EntityReference.class
				);
				final int[] actualPrimaryKeys = references.stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.sorted()
					.toArray();
				assertEquals(
					Arrays.toString(sorted(expectedPrimaryKeys)),
					Arrays.toString(actualPrimaryKeys),
					"The membership index must hold exactly the expected primary keys!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that the same product query, this time asked to materialize the entity bodies, yields exactly the
	 * supplied primary keys. A primary key present in the index but missing a body storage part silently drops out
	 * here — which is what makes agreement between this and {@link #assertProductReferencesAre(int...)} the sharp
	 * observation that the rollback left nothing behind.
	 *
	 * @param expectedPrimaryKeys the complete set of primary keys expected to have a stored body
	 */
	private void assertFetchedProductsAre(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> entities = session.queryList(
					query(collection(Entities.PRODUCT), require(entityFetchAll())),
					SealedEntity.class
				);
				final int[] actualPrimaryKeys = entities.stream()
					.mapToInt(it -> it.getPrimaryKeyOrThrowException())
					.sorted()
					.toArray();
				assertEquals(
					Arrays.toString(sorted(expectedPrimaryKeys)),
					Arrays.toString(actualPrimaryKeys),
					"Only the entities whose body was actually stored may be materialized!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that no entity body can be read for the given primary key.
	 *
	 * @param primaryKey the primary key that must have no stored body
	 */
	private void assertProductAbsent(int primaryKey) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(
					session.getEntity(Entities.PRODUCT, primaryKey).isPresent(),
					"Entity #" + primaryKey + " must not be readable - its body was never stored!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that querying the unique {@link #ATTRIBUTE_CODE} attribute for the given value resolves to exactly the
	 * expected primary key.
	 *
	 * @param code               the unique attribute value to look up
	 * @param expectedPrimaryKey the primary key the value must resolve to
	 */
	private void assertCodeResolvesTo(@Nonnull String code, int expectedPrimaryKey) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<EntityReference> reference = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, code))
					),
					EntityReference.class
				);
				assertTrue(reference.isPresent(), "Code `" + code + "` must resolve to an entity!");
				assertEquals(
					new EntityReference(Entities.PRODUCT, expectedPrimaryKey),
					reference.get()
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that a product query narrowed by the supplied filter returns exactly the supplied primary keys, in any
	 * order. Each call is answered by one specific index, so the `what` description says which one is being pinned.
	 *
	 * @param what                what the index under test is expected to hold, for the failure message
	 * @param filter              the filter narrowing the query
	 * @param expectedPrimaryKeys the complete set of primary keys the query must return
	 */
	private void assertQueryReturns(
		@Nonnull String what, @Nonnull FilterBy filter, int... expectedPrimaryKeys
	) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] actualPrimaryKeys = session.queryList(
						query(collection(Entities.PRODUCT), filter), EntityReference.class
					).stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.sorted()
					.toArray();
				assertEquals(
					Arrays.toString(sorted(expectedPrimaryKeys)),
					Arrays.toString(actualPrimaryKeys),
					"Expected that " + what + "!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that a product query narrowed and ordered by the supplied constraints returns exactly the supplied
	 * primary keys IN THAT ORDER — the assertion the sort index answers, which an unordered comparison would not make.
	 *
	 * @param what                what the sort index is expected to rank, for the failure message
	 * @param filter              the filter narrowing the query
	 * @param order               the ordering the sort index must supply
	 * @param expectedPrimaryKeys the primary keys in the exact order the query must return them
	 */
	private void assertOrderedQueryReturns(
		@Nonnull String what, @Nonnull FilterBy filter, @Nonnull OrderBy order, int... expectedPrimaryKeys
	) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] actualPrimaryKeys = session.queryList(
						query(collection(Entities.PRODUCT), filter, order), EntityReference.class
					).stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.toArray();
				assertEquals(
					Arrays.toString(expectedPrimaryKeys),
					Arrays.toString(actualPrimaryKeys),
					"Expected that " + what + "!"
				);
				return null;
			}
		);
	}

	@Nested
	@DisplayName("The barrier for a warm-up failure that left the catalog unpublishable")
	class UnpublishableBarrier {
		private static final String SIMULATED_FAILURE = "simulated warm-up rollback failure";

		/**
		 * Pins the fail-fast that stops a doomed bulk load at the first entity after the failure.
		 *
		 * Without it a loader keeps writing into a catalog that can never save any of it, and only finds out at the
		 * next session close - which during a long import can be hours of work later.
		 *
		 * The session is opened BEFORE the barrier goes up, because that is the sequence a real loader is in: it is
		 * already inside a session when its entity fails, and it holds its `Catalog` by final reference. Opening a
		 * session afterwards is refused a step earlier, by the deactivation this barrier schedules.
		 */
		@Test
		@DisplayName("The next root mutation on an already open session is refused")
		void shouldRefuseTheNextRootMutation() {
			final EvitaSessionContract session = EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita
				.createReadWriteSession(TEST_CATALOG);
			try {
				unpublishableCatalog();
				final CatalogUnpublishableException refusal = assertThrows(
					CatalogUnpublishableException.class,
					() -> session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 999).setAttribute(ATTRIBUTE_CODE, "Z")
					),
					"A catalog that can no longer publish must refuse the next root mutation."
				);
				assertEquals(
					SIMULATED_FAILURE, refusal.getCause().getMessage(),
					"The refusal must carry the failure that caused it, so the log names the real problem."
				);
			} finally {
				// closing surfaces the refusal too - the close-time flush is one of the guarded publication routes -
				// so this both cleans the session up and pins that the failure is never swallowed
				assertThrows(
					RuntimeException.class, session::close,
					"Closing a session on a catalog that can no longer publish must surface the failure."
				);
			}
		}

		/**
		 * Flush is the ordinary publication route, and the refusal has to land at its ENTRY rather than at the header
		 * write: collecting the trapped changes is destructive, so a flush that is going to be refused anyway must not
		 * consume state on its way to being refused.
		 */
		@Test
		@DisplayName("Flushing is refused before anything is collected")
		void shouldRefuseToFlush() {
			final Catalog catalog = unpublishableCatalog();
			assertThrows(
				CatalogUnpublishableException.class,
				catalog::flush,
				"A catalog that can no longer publish must refuse to flush."
			);
		}

		/**
		 * Go-live publishes a bootstrap record of its own AND mints an ALIVE catalog that shares these indexes, so
		 * letting it through would carry untrustworthy state past every warm-up guard there is. The flush the go-live
		 * operator runs first is a no-op when nothing changed, so it cannot be relied on to refuse in its place.
		 */
		@Test
		@DisplayName("Going live is refused")
		void shouldRefuseToGoLive() {
			final Catalog catalog = unpublishableCatalog();
			assertThrows(
				CatalogUnpublishableException.class,
				catalog::goLive,
				"A catalog that can no longer publish must refuse to go live."
			);
		}

		/**
		 * The barrier must never cost a resource. Termination skips the flush loop and the header write - both are
		 * futile once nothing may be published - and still terminates every collection it holds.
		 *
		 * This is the failure the buffer-level predecessor of this barrier actually caused: it threw from inside the
		 * collect, which aborted the terminate loop at its first collection and left the rest un-terminated.
		 *
		 * The `terminate()` below is NOT the only terminator in play: raising the barrier schedules an asynchronous
		 * deactivation which terminates the very same instance. That is exactly why `Catalog#terminate` has to honour
		 * the idempotence {@link io.evitadb.api.CatalogContract#terminate()} promises - whichever call arrives second
		 * returns once the first has finished, so this assertion observes a COMPLETED termination either way. Before
		 * that was true this test failed intermittently with `Catalog is already terminated!`, on scheduler latency
		 * alone.
		 */
		@Test
		@DisplayName("Termination still releases every collection")
		void shouldStillTerminateEveryCollection() {
			final Catalog catalog = unpublishableCatalog();
			final List<EntityCollection> collections = List.of(
				catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT),
				catalog.getCollectionForEntityOrThrowException(Entities.PARAMETER),
				catalog.getCollectionForEntityOrThrowException(Entities.PARAMETER_GROUP)
			);

			assertDoesNotThrow(
				catalog::terminate,
				"Terminating a catalog that can no longer publish must not throw - shutdown has to finish."
			);

			for (final EntityCollection collection : collections) {
				assertTrue(
					collection.isTerminated(),
					"Collection `" + collection.getEntityType() + "` must be terminated even though the catalog " +
						"could not publish its state."
				);
			}
		}

		@Test
		@DisplayName("Terminating twice is a no-op, not a failure")
		void shouldTolerateASecondTermination() {
			// two legitimate owners terminate a catalog and they are not ordered: the deactivation the barrier
			// schedules, and engine shutdown. `Evita#closeCatalogs` calls terminate() unguarded, so a premise failure
			// here would have propagated out of shutdown rather than being the programming error it claimed to catch
			final Catalog catalog = unpublishableCatalog();
			catalog.terminate();
			assertTrue(catalog.isTerminated(), "self-check: the first call must actually terminate the catalog");

			assertDoesNotThrow(
				catalog::terminate,
				"CatalogContract#terminate is documented idempotent - a repeat call is ignored, never refused."
			);
		}

		/**
		 * Returns the test catalog with the barrier already raised by a simulated warm-up failure.
		 *
		 * @return the catalog under test, marked unpublishable
		 */
		@Nonnull
		private Catalog unpublishableCatalog() {
			final Catalog catalog = (Catalog) EntityAtomicMutationRollbackWarmUpFunctionalTest.this.evita
				.getCatalogInstanceOrThrowException(TEST_CATALOG);
			assertTrue(catalog.isPublishable(), "The catalog must start out publishable.");
			catalog.markUnpublishable(new RuntimeException(SIMULATED_FAILURE));
			assertFalse(catalog.isPublishable(), "Marking the catalog must raise the barrier.");
			return catalog;
		}
	}

	/**
	 * Returns a sorted copy of the supplied primary keys, so the assertions do not depend on the order they were
	 * written in.
	 *
	 * @param primaryKeys the primary keys to sort
	 * @return a sorted copy
	 */
	@Nonnull
	private static int[] sorted(int... primaryKeys) {
		final int[] copy = primaryKeys.clone();
		Arrays.sort(copy);
		return copy;
	}

}
