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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WARM_UP counterpart of {@link EntityAtomicMutationRollbackFunctionalTest}: it drives the very same failing
 * entity through a catalog that is still being bulk loaded, where writes go IN PLACE to the index delegates instead of
 * to a transaction's diff layers.
 *
 * Its job is to pin down, as executable assertions, exactly how far the warm-up path is from the atomicity the
 * transactional path already provides — so that each phase of the port can be measured against it rather than argued
 * about. Both scenarios are a batch of three entities in which the middle one violates a unique constraint after its
 * index writes have already been applied, and they differ only in HOW FAR the mutation gets first:
 *
 * - **The early failure** aborts at the very first index the entity reaches, so the only state it leaves behind is the
 *   membership bitmap, the collection's storage diff layer and the indexes' dirty flags.
 * - **The late failure** submits an explicitly ORDERED upsert mutation whose duplicate code sits last, so the entity is
 *   already in the hierarchy, sort, filter, range and price indexes — five B+ tree-backed structures — before it fails.
 *
 * **The divergence with the switch off**, which the tests below still assert: the failed entity's primary key stays in
 * the collection's membership index (it is returned by a query) while its body storage part was never written
 * (fetching it yields nothing) — the same query therefore reports four products by reference and three by content. In
 * the late-failure shape it is additionally queryable through every index it reached on the way. Recovery is documented
 * as "compensate on the client or rebuild the catalog" in
 * `documentation/user/en/deep-dive/bulk-vs-incremental-indexing.md`.
 *
 * Both switch positions are exercised. With {@link WarmUpSavepoint} switched on the divergence is gone in both shapes:
 * every index agrees with the body fetch on the same three products, and the catalog keeps taking writes afterwards.
 * That flip is this line of work's acceptance criterion and this class is where it is recorded.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Per-entity atomic mutation rollback in warm-up mode")
@Tag(TestTags.ENGINE)
@Tag(TestTags.INDEXING)
// the warm-up atomicity switch is a process-wide static and test classes in this module run concurrently in one JVM;
// @Isolated keeps a flipped switch from reaching an unrelated class
@Isolated
class EntityAtomicMutationRollbackWarmUpFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_SORTABLE_CODE = "sortableCode";
	private static final String ATTRIBUTE_VALIDITY = "validity";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final OffsetDateTime VALIDITY_START =
		OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime WITHIN_VALIDITY =
		OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final DateTimeRange VALIDITY =
		DateTimeRange.between(VALIDITY_START, VALIDITY_START.plusYears(1));
	private TestPaths paths;
	private Evita evita;
	private boolean originalAtomicity;

	@BeforeEach
	void setUp() {
		this.originalAtomicity = WarmUpSavepoint.isEnabled();
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
					.withPrice()
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "A")
				);
			}
		);
	}

	@AfterEach
	void tearDown() {
		WarmUpSavepoint.setEnabled(this.originalAtomicity);
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Warm-up atomicity switched off")
	class WithoutWarmUpAtomicity {

		@Test
		@DisplayName("The failed entity leaves an orphan primary key with no body behind")
		@Tag(TestTags.ATTRIBUTE)
		void shouldLeaveOrphanPrimaryKeyBehindWhenAtomicityIsOff() {
			WarmUpSavepoint.setEnabled(false);
			runFailingBatch();

			// #3 was registered in the collection's membership index before its unique-code check threw, and nothing
			// reverted it; its body storage part is only written on the success path, so it was never stored
			assertProductReferencesAre(1, 2, 3, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);
		}

		@Test
		@DisplayName("The unique attribute index is nonetheless left consistent")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepUniqueIndexConsistentWhenAtomicityIsOff() {
			WarmUpSavepoint.setEnabled(false);
			runFailingBatch();

			// the failure struck as the unique index rejected the duplicate, so that index never took #3's entry - the
			// damage is confined to the structures written before the check, which is what makes it easy to miss
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);
		}

		@Test
		@DisplayName("A late failure additionally strands the entity in the filter, range, price and hierarchy indexes")
		@Tag(TestTags.ATTRIBUTE)
		@Tag(TestTags.PRICE)
		@Tag(TestTags.HIERARCHY)
		void shouldStrandLateWritesWhenAtomicityIsOff() {
			WarmUpSavepoint.setEnabled(false);
			runLateFailingBatch();

			// the mutation aborted only at its last local mutation, so everything written before it stayed - the
			// bodiless entity #3 is queryable through every index it reached on the way. This is the divergence the
			// switched-on counterpart of this test closes
			assertProductReferencesAre(1, 2, 3, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertQueryReturns(
				"the filter index kept the failed entity's sortable code",
				filterBy(attributeEquals(ATTRIBUTE_SORTABLE_CODE, "S3")),
				3
			);
			assertQueryReturns(
				"the range index kept the failed entity's validity",
				filterBy(attributeInRange(ATTRIBUTE_VALIDITY, WITHIN_VALIDITY)),
				2, 3, 4
			);
			assertQueryReturns(
				"the price index kept the failed entity's price",
				filterBy(priceInPriceLists(PRICE_LIST_BASIC), priceInCurrency(CURRENCY_CZK)),
				2, 3, 4
			);
			assertQueryReturns(
				"the hierarchy index kept the failed entity as a child of #1",
				filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1))),
				1, 2, 3, 4
			);
		}
	}

	@Nested
	@DisplayName("Warm-up atomicity switched on")
	class WithWarmUpAtomicity {

		/**
		 * Pins the reach of the mechanism as it stands, which for this scenario is complete: the reference query and
		 * the body fetch agree, so no orphan primary key is left behind.
		 *
		 * What the scenario actually exercises, and why that is the whole of it: the failing entity reaches the unique
		 * index only after its primary key has been written to the collection's membership bitmap, and
		 * `EntityIndex#upsertAttribute` runs the unique insert BEFORE the filter one, while the unique insert itself
		 * checks the value is free before writing its own tree. So the duplicate code aborts the mutation with exactly
		 * three kinds of state touched — the membership bitmap, the collection's storage diff layer, and the indexes'
		 * dirty flags — and all three journal their warm-up writes.
		 *
		 * That also says what this test does NOT prove: it never reaches a B+ tree. The late-failure test below is the
		 * one that does.
		 */
		@Test
		@DisplayName("The failed entity leaves no orphan primary key behind")
		@Tag(TestTags.ATTRIBUTE)
		void shouldLeaveNoOrphanPrimaryKeyWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
			runFailingBatch();

			assertProductReferencesAre(1, 2, 4);
			assertFetchedProductsAre(1, 2, 4);
			assertProductAbsent(3);
		}

		@Test
		@DisplayName("The surrounding batch is unaffected and the catalog keeps accepting writes")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepWritingAfterTheFailedEntityWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
			runFailingBatch();

			// everything the batch legitimately wrote survived the bracketed failure, and the unique index is intact
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);

			// a later session still flushes: the rollback completed, so nothing poisoned the warm-up buffers
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

		/**
		 * The scenario the early-failure test above explicitly could not reach: the duplicate code is written LAST, so
		 * the mutation aborts only once the entity has already been inserted into the hierarchy index, the sort index
		 * (an unordered lookup tree plus its order-key B+ tree), the filter index (a bucket B+ tree), the range index
		 * (a long-keyed B+ tree) and the price index (an element-keyed B+ tree). Every one of those is a structure the
		 * early scenario never touched.
		 *
		 * Each assertion below is answered by a different index, so a family left un-journaled shows up as its own
		 * failure rather than as one blanket one.
		 */
		@Test
		@DisplayName("An entity failing after the filter, sort, range, hierarchy and price writes recovers fully")
		@Tag(TestTags.ATTRIBUTE)
		@Tag(TestTags.PRICE)
		@Tag(TestTags.HIERARCHY)
		void shouldRecoverFromLateFailureWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
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
		@DisplayName("The catalog keeps accepting writes after a late failure was rolled back")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepWritingAfterALateFailureWhenAtomicityIsOn() {
			WarmUpSavepoint.setEnabled(true);
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
	 * here — which is what makes the gap between this and {@link #assertProductReferencesAre(int...)} the sharp
	 * observation of the divergence.
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
