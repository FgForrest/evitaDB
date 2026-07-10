/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import io.evitadb.test.generator.DataGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_ALIAS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_VALIDITY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Focused regression test for the #760 price-strand data-loss bug.
 *
 * The bug: when a product's `basic` price gets a fresh internal id (price renumber, triggered by the
 * delete+restore of the entity inside a single transaction) **and** the product's `CATEGORY`
 * reference migrates between representative-keyed reduced indexes (because the `categoryGroup`
 * representative attribute value changes), the cleanup that empties the OLD reduced index removes the
 * entity's CURRENT (renumbered) internal price id instead of the OLD one the OLD index actually holds.
 * The OLD reduced index therefore ends up with `entityIds = []` but `indexedPriceIds = [staleId]`,
 * while the GLOBAL super price index — keyed only by entity — correctly drops the stale id.
 *
 * On the next `closeAndReopenEvita()` the reduced index rebuilds its price-record tree via
 * `superIndex.getPriceRecords(this.indexedPriceIds)`; the stale id is absent from the super index, so
 * catalog load throws `GenericEvitaInternalError: Price with id N was not found in the same index!`.
 * When the renumber also drops the LAST `basic`/`EUR` price from the super index for that price
 * list/currency, the same strand surfaces as the sibling symptom
 * `PriceListAndCurrencyPriceRefIndex can only be initialized with PriceListAndCurrencyPriceSuperIndex,
 * actual instance is NULL` — both are the identical #760 reduced-price-index reload failure and both
 * fail this test.
 *
 * ## How the strand is formed (and what is / isn't deterministic)
 *
 * The exact in-transaction interleaving that forms the strand (storage updated to the new
 * representative value before the price cascade runs, then the OLD-index migration cleanup running
 * against the already-renumbered price) is engine-internal and cannot be driven for a single product
 * through the public mutation API — verified by hand: a single product that renumbers its price and
 * migrates its representative key in one transaction reloads cleanly. The strand only forms under the
 * multi-product transactional walk of `SharedRgeiSoakTest`, so this test reuses that schema and walk
 * pinned to the historically-failing seed {@link #STRAND_SEED} (`1623796816`).
 *
 * The walk is byte-for-byte deterministic for a fixed seed, and the default commit behaviour
 * ({@link io.evitadb.api.TransactionContract.CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}) incorporates the
 * pre-reload transaction before the reload boundary. The test therefore **reproduces the strand on
 * every run** (it has failed 10/10 observed runs on this HEAD). What is NOT deterministic is the exact
 * stale price id and which of the two symptom messages above surfaces: the background trunk-incorporation
 * thread perturbs internal-price-id assignment and the order in which the many mutated products are
 * processed, so the strand lands on a different price/index from run to run (observed ids include 2, 4,
 * 20, and the NULL-super variant). This run-to-run perturbation could not be eliminated from the test
 * side (a settle before reload and explicit wait-for-changes-visible did not stabilise it). The test's
 * RED/GREEN verdict is nonetheless deterministic: always RED on this HEAD, and expected to be GREEN
 * once the in-memory cascade is fixed.
 *
 * The throw fires on the FIRST reload after the pre-reload mutation phase, so the post-reload phase of
 * the soak is intentionally omitted — the test is the minimal prefix of the soak flow that strands.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
@Tag(PRICE)
@DisplayName("Shared RGEI price strand — reduced price index must not retain a stale price id")
class SharedRgeiPriceStrandTest implements EvitaTestSupport {

	/**
	 * Local attribute names — kept in sync with `SharedRgeiSoakTest` /
	 * `LongRunningEvitaReferencesGenerationalTest`; drift here would silently decouple the reduced
	 * index shape from the one that exhibits the bug.
	 */
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_CATEGORY_GROUP = "categoryGroup";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_PRODUCTS = "products";

	/**
	 * Seed whose deterministic random walk reproduces the price strand on every run. Lifted from the
	 * `SharedRgeiSoakTest` seed list — the strand surfaces on the first reload after the pre-reload phase
	 * (the exact stale price id varies run-to-run; see the class JavaDoc).
	 */
	private static final long STRAND_SEED = 1623796816L;

	/**
	 * Number of products seeded before the mutation walk — identical to the soak so the random draws
	 * (and therefore the strand) match exactly.
	 */
	private static final int INITIAL_PRODUCT_COUNT = 10;

	/**
	 * Mutations applied in the single pre-reload transaction. Identical to the soak's per-phase budget
	 * so the deterministic random sequence that forms the strand is preserved verbatim.
	 */
	private static final int MUTATIONS_PER_PHASE = 60;

	/**
	 * Catalog name used for the test catalog — fixed so cleanup is deterministic.
	 */
	private static final String CATALOG_NAME = TEST_CATALOG;

	/**
	 * Soft cap on accumulated removed entities before the random walk prefers restore over removal.
	 * Mirrors the soak's value so the random walk decisions match.
	 */
	private static final int MAX_REMOVED_ENTITIES = 4;

	private TestPaths paths;
	private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
			this.evita = null;
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
			this.paths = null;
		}
	}

	/**
	 * Drives the deterministic pre-reload mutation phase for {@link #STRAND_SEED} and then crosses the
	 * commit+reload boundary. On the buggy HEAD the reload throws
	 * `Price with id N was not found in the same index!` (or the sibling NULL-super variant); after the
	 * fix the reload succeeds and the reopened catalog's prices remain queryable.
	 */
	@Test
	@DisplayName("Reload must not throw when a renumbered price's reduced index migrated representative keys")
	void shouldReloadWithoutStaleReducedPriceIndexWhenRepresentativeKeyMigrates() {
		this.paths = createTestPaths("SharedRgeiPriceStrandTest_" + STRAND_SEED);
		final DataGenerator dataGenerator = new DataGenerator();
		final Map<Serializable, Integer> generatedEntities = new HashMap<>();
		final BiFunction<String, Faker, Integer> randomEntityPicker =
			createRandomEntityPicker(generatedEntities);

		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(CATALOG_NAME);

		// phase 1: build the slim catalog and seed the initial product set
		setUpCatalog(dataGenerator, randomEntityPicker, generatedEntities);

		// phase 2: run the single deterministic pre-reload transaction (delete/restore/modify walk)
		final Map<Integer, SealedEntity> removedEntities = new HashMap<>(MAX_REMOVED_ENTITIES);
		final Random random = new Random(STRAND_SEED);
		final Function<SealedEntity, EntityBuilder> modificationFunction =
			dataGenerator.createModificationFunction(randomEntityPicker, random);

		runMutationPhase(modificationFunction, removedEntities, random);

		// phase 3: cross the commit+reload boundary — the strand surfaces here as a catalog load
		// failure on the buggy HEAD; after the fix the reopen completes cleanly
		closeAndReopenEvita();

		// phase 4: prove the rehydrated catalog is consistent — every product's prices are queryable
		// without the reduced/super price index divergence the bug introduces
		assertCatalogPricesAreQueryable();
	}

	/**
	 * Constructs the random-entity picker used by `DataGenerator` to wire references to existing brands
	 * and categories. Identical to the soak's picker so reference wiring matches.
	 *
	 * @param generatedEntities mutable map of entity-type -> highest generated primary key
	 * @return picker function consumed by `DataGenerator.generateEntities`
	 */
	@Nonnull
	private static BiFunction<String, Faker, Integer> createRandomEntityPicker(
		@Nonnull final Map<Serializable, Integer> generatedEntities
	) {
		return (entityType, faker) -> {
			final int entityCount = generatedEntities.computeIfAbsent(entityType, serializable -> 0);
			final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			return primaryKey == 0 ? null : primaryKey;
		};
	}

	/**
	 * Builds the brand/category/product schemas and seeds the catalog with the initial product set.
	 * Copied verbatim from `SharedRgeiSoakTest#setUpCatalog` so the `ReducedGroupEntityIndex` shape —
	 * the representative-keyed reduced index that strands — is exercised identically.
	 *
	 * @param dataGenerator      shared data generator instance for entity generation
	 * @param randomEntityPicker picker that wires references during product generation
	 * @param generatedEntities  map populated with the highest seen primary key per entity type
	 */
	private void setUpCatalog(
		@Nonnull final DataGenerator dataGenerator,
		@Nonnull final BiFunction<String, Faker, Integer> randomEntityPicker,
		@Nonnull final Map<Serializable, Integer> generatedEntities
	) {
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
					.updateVia(session);

				final SealedEntitySchema brandSchema = session.defineEntitySchema(Entities.BRAND)
					.verifySchemaStrictly()
					.withGeneratedPrimaryKey()
					.withoutHierarchy()
					.withoutPrice()
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.filterable().localized().sortable().nullable()
					)
					.updateAndFetchVia(session);

				dataGenerator.generateEntities(brandSchema, randomEntityPicker, STRAND_SEED)
					.limit(2)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> generatedEntities.put(
						Entities.BRAND, it.getPrimaryKeyOrThrowException()
					));

				final SealedEntitySchema categorySchema = session.defineEntitySchema(Entities.CATEGORY)
					.verifySchemaStrictly()
					.withGeneratedPrimaryKey()
					.withoutHierarchy()
					.withoutPrice()
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.filterable().localized().sortable().nullable()
					)
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
							.indexedForFiltering()
							.withAttributesInheritedExcept(ATTRIBUTE_CATEGORY_ORDER)
					)
					.updateAndFetchVia(session);

				dataGenerator.generateEntities(categorySchema, randomEntityPicker, STRAND_SEED)
					.limit(4)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> generatedEntities.put(
						Entities.CATEGORY, it.getPrimaryKeyOrThrowException()
					));

				final SealedEntitySchema productSchema = session.defineEntitySchema(Entities.PRODUCT)
					.verifySchemaStrictly()
					.withoutGeneratedPrimaryKey()
					.withoutHierarchy()
					.withPriceInCurrency(
						Currency.getInstance("CZK"),
						Currency.getInstance("EUR"),
						Currency.getInstance("USD"),
						Currency.getInstance("GBP")
					)
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.filterable().localized().sortable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_PRIORITY, Long.class, AttributeSchemaEditor::sortable
					)
					.withAttribute(
						ATTRIBUTE_VALIDITY, DateTimeRange.class,
						whichIs -> whichIs.filterable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_QUANTITY, BigDecimal.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
					)
					.withAttribute(
						ATTRIBUTE_ALIAS, Boolean.class,
						whichIs -> whichIs.filterable().withDefaultValue(false)
					)
					.withAssociatedData(
						ASSOCIATED_DATA_REFERENCED_FILES,
						DataGenerator.ReferencedFileSet.class,
						AssociatedDataSchemaEditor::nullable
					)
					.withAssociatedData(
						ASSOCIATED_DATA_LABELS, DataGenerator.Labels.class,
						whichIs -> whichIs.localized().nullable()
					)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(Entities.BRAND)
							.withAttribute(
								ATTRIBUTE_CATEGORY_GROUP, String.class,
								thatIs -> thatIs.filterable().representative()
							)
							.withAttribute(
								ATTRIBUTE_CATEGORY_PRIORITY, Long.class,
								thatIs -> thatIs.filterable().sortable()
							)
							.withAttribute(
								ATTRIBUTE_CATEGORY_ORDER, Long.class,
								AttributeSchemaEditor::sortable
							)
					)
					.updateAndFetchVia(session);

				dataGenerator.generateEntities(productSchema, randomEntityPicker, STRAND_SEED)
					.limit(INITIAL_PRODUCT_COUNT)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Applies `MUTATIONS_PER_PHASE` deterministic random mutations in a single transactional session.
	 * Each mutation is one of: remove an existing entity, restore a previously removed entity (which
	 * renumbers its prices), or modify an existing entity. Copied from `SharedRgeiSoakTest` so the
	 * random draw order — and therefore the strand-forming operation sequence — matches exactly.
	 *
	 * @param modificationFunction function that mutates an existing entity into a builder
	 * @param removedEntities      mutable map of removed entities
	 * @param random               deterministic random source seeded with {@link #STRAND_SEED}
	 */
	private void runMutationPhase(
		@Nonnull final Function<SealedEntity, EntityBuilder> modificationFunction,
		@Nonnull final Map<Integer, SealedEntity> removedEntities,
		@Nonnull final Random random
	) {
		String operation = null;
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(CATALOG_NAME)) {
			for (int i = 0; i < MUTATIONS_PER_PHASE; i++) {
				int primaryKey;
				// avoid hitting an already-removed entity; the small dataset keeps this loop cheap
				do {
					primaryKey = random.nextInt(INITIAL_PRODUCT_COUNT) + 1;
				} while (removedEntities.containsKey(primaryKey));

				if (random.nextInt(5) == 0 && removedEntities.size() < MAX_REMOVED_ENTITIES) {
					final int productId = primaryKey;
					operation = "removal of " + primaryKey;
					removedEntities.put(
						primaryKey,
						session.getEntity(
							Entities.PRODUCT, primaryKey, entityFetchAllContent()
						).orElseThrow(
							() -> new IllegalStateException(
								"Product with primary key " + productId + " was not found."
							)
						)
					);
					session.deleteEntity(Entities.PRODUCT, primaryKey);
				} else if (random.nextInt(5) == 0 && !removedEntities.isEmpty()) {
					final SealedEntity entityToRestore = pickRandom(random, removedEntities);
					removedEntities.remove(entityToRestore.getPrimaryKey());
					operation = "restore of " + entityToRestore.getPrimaryKey();
					session.upsertEntity(new CopyExistingEntityBuilder(entityToRestore));
				} else {
					operation = "modification of " + primaryKey;
					final SealedEntity existingEntity = session.getEntity(
						Entities.PRODUCT, primaryKey, entityFetchAllContent()
					).orElseThrow();
					session.upsertEntity(modificationFunction.apply(existingEntity));
				}
			}
		} catch (final Exception ex) {
			// any thrown exception during the deterministic walk is a regression signal; the operation
			// context narrows down which entity tripped
			fail("Failed during pre-reload " + operation + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * Closes the running `Evita` instance and reopens it against the same on-disk storage. On the
	 * buggy HEAD this throws `Price with id N was not found in the same index!` while loading the
	 * `PRODUCT` collection, because a reduced price index retained a stale internal price id absent
	 * from the super index.
	 */
	private void closeAndReopenEvita() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Verifies the rehydrated catalog is internally consistent by paging through every product and
	 * fetching its prices. A successful pass proves no reduced price index diverged from its super
	 * index across the reload boundary.
	 */
	private void assertCatalogPricesAreQueryable() {
		this.evita.queryCatalog(
			CATALOG_NAME,
			session -> {
				int pageNumber = 1;
				int seenProducts = 0;
				EvitaResponse<SealedEntity> products;
				do {
					products = session.querySealedEntity(
						Query.query(
							collection(Entities.PRODUCT),
							require(entityFetchAll(), page(pageNumber++, 100))
						)
					);
					for (final SealedEntity product : products.getRecordPage().getData()) {
						seenProducts++;
						// touch the prices so any lazily-evaluated divergence surfaces
						for (final PriceContract price : product.getPrices()) {
							assertFalse(
								price.priceId() < 0,
								"product " + product.getPrimaryKey()
									+ " carries a price with a negative external id"
							);
						}
					}
				} while (products.getRecordPage().hasNext());

				assertNotEquals(
					0, seenProducts,
					"no products survived the reload — seeding or reload regressed"
				);
				return null;
			}
		);
	}

	/**
	 * Builds the standard Evita configuration anchored at the per-test path triplet. Identical to the
	 * soak's configuration so the default commit behaviour (and thus the deterministic incorporation
	 * order) matches.
	 *
	 * @return the configuration used to construct the `Evita` instance
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Returns a deterministically selected entity from the supplied map. Caller must ensure the map is
	 * non-empty. Copied from the soak so the random consumption pattern is preserved.
	 *
	 * @param random random source seeded with {@link #STRAND_SEED}
	 * @param map    map of removed entities keyed by primary key
	 * @return one entry value from the map
	 */
	@Nonnull
	private static SealedEntity pickRandom(
		@Nonnull final Random random,
		@Nonnull final Map<Integer, SealedEntity> map
	) {
		// deterministic without re-allocation: walk the existing iterator
		final int index = map.size() == 1 ? 0 : random.nextInt(map.size());
		final Iterator<SealedEntity> it = map.values().iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}
}
