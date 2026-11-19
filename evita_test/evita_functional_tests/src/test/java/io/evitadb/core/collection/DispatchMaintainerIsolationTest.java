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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GroupHistogramEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.BiConsumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the maintainers of two dispatches into one collection keep their records of the histogram stores they
 * wrote to apart: each drops the stores it emptied and nothing of the other's, whatever order the two dispatches
 * interleave in. The collection is shared by every session writing into it, so a record kept on the collection, or on
 * its shared maintainer, would let one dispatch drop - or forget - the stores of another.
 *
 * The catalog stays in warm-up. A product references the brand 10 in its group, which is the group 1 for the product
 * 1 and the group 2 for the product 2; the brand weighs 5 and an unconditional histogram takes the weight, so each
 * group has a store holding one value. The two maintainers write into the stores directly, as a dispatch does, which
 * makes the interleaving deterministic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Maintainers of concurrent dispatches into one collection")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class DispatchMaintainerIsolationTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	private static final int BRAND_PK = 10;
	private static final int BRAND_WEIGHT = 5;
	private static final int FIRST_GROUP_PK = 1;
	private static final int SECOND_GROUP_PK = 2;
	private static final int FIRST_PRODUCT_PK = 1;
	private static final int SECOND_PRODUCT_PK = 2;
	private static final EntityIndexKey FIRST_STORE_KEY =
		GroupHistogramEntityIndex.keyOf(REFERENCE_BRAND, FIRST_GROUP_PK, Scope.LIVE);
	private static final EntityIndexKey SECOND_STORE_KEY =
		GroupHistogramEntityIndex.keyOf(REFERENCE_BRAND, SECOND_GROUP_PK, Scope.LIVE);

	/**
	 * Maintainer A writes into the store of the group 1, maintainer B into the store of the group 2. B empties its
	 * store and drops what it emptied while A is still going: the store of the group 2 is gone, the store of the group
	 * 1 stays. A then empties its store and drops what it emptied: the store of the group 1 is gone too. A restart
	 * finds neither.
	 */
	@Test
	@DisplayName("each maintainer drops the stores it emptied, whichever dispatch ends first")
	void shouldDropOnlyItsOwnEmptiedStoresWhenDispatchesInterleave() {
		final TestPaths paths = createTestPaths("DispatchMaintainerIsolation");
		Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, DispatchMaintainerIsolationTest::setUpCatalog);

			final Evita running = evita;
			evita.updateCatalog(TEST_CATALOG, session -> {
				final EntityCollection collection = productCollection(running);
				final EntityCollection.DispatchMaintainer first = collection.createDispatchMaintainer(session);
				final EntityCollection.DispatchMaintainer second = collection.createDispatchMaintainer(session);

				final GroupHistogramEntityIndex firstStore = assertStoreHoldsValue(collection, FIRST_STORE_KEY);
				final GroupHistogramEntityIndex secondStore = assertStoreHoldsValue(collection, SECOND_STORE_KEY);
				assertSame(firstStore, first.getOrCreateIndex(FIRST_STORE_KEY), "A registers the stored store");
				assertSame(secondStore, second.getOrCreateIndex(SECOND_STORE_KEY), "B registers the stored store");

				secondStore.removeHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, SECOND_PRODUCT_PK, 0);
				second.dropEmptiedGroupHistogramStores();
				assertNull(collection.getIndexByKeyIfExists(SECOND_STORE_KEY), "B drops the store it emptied");
				assertSame(
					firstStore, collection.getIndexByKeyIfExists(FIRST_STORE_KEY),
					"B leaves the store A writes into"
				);

				firstStore.removeHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, FIRST_PRODUCT_PK, 0);
				first.dropEmptiedGroupHistogramStores();
				assertNull(collection.getIndexByKeyIfExists(FIRST_STORE_KEY), "A drops the store it emptied");
			});

			evita.close();
			evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
			evita.waitUntilFullyInitialized();
			final EntityCollection collection = productCollection(evita);
			assertNull(collection.getIndexByKeyIfExists(FIRST_STORE_KEY), "no store of the group 1 after a restart");
			assertNull(collection.getIndexByKeyIfExists(SECOND_STORE_KEY), "no store of the group 2 after a restart");
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Maintainer A empties the store of the group 1 and is still going when maintainer B empties the store of the
	 * group 2 and drops what it emptied: B drops its own store only, so the empty store of the group 1 stays. A then
	 * fills its store again and drops what it emptied: the store holds a value, so A keeps it.
	 */
	@Test
	@DisplayName("a store another dispatch emptied is kept while that one runs, and once it is filled again")
	void shouldKeepStoreAnotherDispatchEmptiedWhileItStillRuns() {
		runInWarmUpCatalog("DispatchMaintainerIsolation_stillRunning", (session, collection) -> {
			final EntityCollection.DispatchMaintainer first = collection.createDispatchMaintainer(session);
			final EntityCollection.DispatchMaintainer second = collection.createDispatchMaintainer(session);
			final GroupHistogramEntityIndex firstStore = assertStoreHoldsValue(collection, FIRST_STORE_KEY);
			final GroupHistogramEntityIndex secondStore = assertStoreHoldsValue(collection, SECOND_STORE_KEY);
			assertSame(firstStore, first.getOrCreateIndex(FIRST_STORE_KEY), "A registers the stored store");
			assertSame(secondStore, second.getOrCreateIndex(SECOND_STORE_KEY), "B registers the stored store");

			firstStore.removeHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, FIRST_PRODUCT_PK, 0);
			assertTrue(firstStore.isEmpty(), "A emptied its store");
			secondStore.removeHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, SECOND_PRODUCT_PK, 0);
			second.dropEmptiedGroupHistogramStores();
			assertNull(collection.getIndexByKeyIfExists(SECOND_STORE_KEY), "B drops the store it emptied");
			assertSame(
				firstStore, collection.getIndexByKeyIfExists(FIRST_STORE_KEY),
				"B leaves the empty store A still writes into"
			);

			assertSame(firstStore, first.getOrCreateIndex(FIRST_STORE_KEY), "A registers the store again to fill it");
			firstStore.insertHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, FIRST_PRODUCT_PK, Integer.class, 0);
			first.dropEmptiedGroupHistogramStores();
			assertSame(
				firstStore, collection.getIndexByKeyIfExists(FIRST_STORE_KEY),
				"A keeps the store it filled again"
			);
			assertFalse(firstStore.isEmpty(), "the store kept by A holds its value");
		});
	}

	/**
	 * Maintainers A and B both register the store of the group 1, whose value is then withdrawn. B drops the empty
	 * store first; A finds the store it recorded gone and drops nothing, without failing. The store of the group 2,
	 * which neither registered, stays.
	 */
	@Test
	@DisplayName("a recorded store another dispatch already dropped is skipped")
	void shouldSweepWithoutFailureWhenRecordedStoreWasAlreadyDropped() {
		runInWarmUpCatalog("DispatchMaintainerIsolation_alreadyDropped", (session, collection) -> {
			final EntityCollection.DispatchMaintainer first = collection.createDispatchMaintainer(session);
			final EntityCollection.DispatchMaintainer second = collection.createDispatchMaintainer(session);
			final GroupHistogramEntityIndex firstStore = assertStoreHoldsValue(collection, FIRST_STORE_KEY);
			final GroupHistogramEntityIndex secondStore = assertStoreHoldsValue(collection, SECOND_STORE_KEY);
			assertSame(firstStore, first.getOrCreateIndex(FIRST_STORE_KEY), "A registers the stored store");
			assertSame(firstStore, second.getOrCreateIndex(FIRST_STORE_KEY), "B registers the same store");

			firstStore.removeHistogramValue(HISTOGRAM_WEIGHT, null, BRAND_WEIGHT, FIRST_PRODUCT_PK, 0);
			second.dropEmptiedGroupHistogramStores();
			assertNull(collection.getIndexByKeyIfExists(FIRST_STORE_KEY), "B drops the emptied store");

			assertDoesNotThrow(first::dropEmptiedGroupHistogramStores, "A skips the store B dropped");
			assertNull(collection.getIndexByKeyIfExists(FIRST_STORE_KEY), "the store stays dropped");
			assertSame(
				secondStore, collection.getIndexByKeyIfExists(SECOND_STORE_KEY),
				"the store neither registered stays"
			);
		});
	}

	/**
	 * Sets the catalog up in warm-up and runs `body` in one more session of it, on the product collection, then closes
	 * the engine.
	 *
	 * @param label names the directory of the case
	 * @param body  the case, given the session and the product collection
	 */
	private void runInWarmUpCatalog(
		@Nonnull String label,
		@Nonnull BiConsumer<EvitaSessionContract, EntityCollection> body
	) {
		final TestPaths paths = createTestPaths(label);
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, DispatchMaintainerIsolationTest::setUpCatalog);
			evita.updateCatalog(TEST_CATALOG, session -> {
				body.accept(session, productCollection(evita));
			});
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Returns the store the collection holds under `storeKey`, asserting that there is one and that it holds a value -
	 * so that registering it cannot create a missing store and turn the withdrawal into a no-op.
	 *
	 * @param collection the product collection
	 * @param storeKey   the key of the store
	 * @return the store
	 */
	@Nonnull
	private static GroupHistogramEntityIndex assertStoreHoldsValue(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndexKey storeKey
	) {
		final GroupHistogramEntityIndex store = assertInstanceOf(
			GroupHistogramEntityIndex.class, collection.getIndexByKeyIfExists(storeKey), "the store of " + storeKey
		);
		assertFalse(store.isEmpty(), "the store of " + storeKey + " holds a value");
		return store;
	}

	/**
	 * Returns the product collection of the running engine.
	 *
	 * @param evita the engine
	 * @return the collection
	 */
	@Nonnull
	private static EntityCollection productCollection(@Nonnull Evita evita) {
		return (EntityCollection) evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}

	/**
	 * Defines the schemas and stores the group, the brand and the two products, each in a group of its own.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
					.indexedForFilteringInScope(Scope.LIVE)
					.indexedWithComponentsInScope(
						Scope.LIVE,
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_WEIGHT,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['" + ATTRIBUTE_WEIGHT + "']"),
						null
					)
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, FIRST_GROUP_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, SECOND_GROUP_PK));
		session.upsertEntity(
			session.createNewEntity(ENTITY_BRAND, BRAND_PK).setAttribute(ATTRIBUTE_WEIGHT, BRAND_WEIGHT)
		);
		storeProduct(session, FIRST_PRODUCT_PK, FIRST_GROUP_PK);
		storeProduct(session, SECOND_PRODUCT_PK, SECOND_GROUP_PK);
	}

	/**
	 * Stores the product `productPk` with one reference to the brand 10 in the group `groupPk`.
	 *
	 * @param session   the session of the catalog in warm-up
	 * @param productPk the primary key of the product
	 * @param groupPk   the primary key of the group of the reference
	 */
	private static void storeProduct(@Nonnull EvitaSessionContract session, int productPk, int groupPk) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, groupPk))
		);
	}

}
