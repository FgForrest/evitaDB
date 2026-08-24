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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
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
import io.evitadb.test.annotation.RequiresDefaultWarmUpWritePath;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.generator.DataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Time-bounded, randomized soak harness for the shared `ReducedGroupEntityIndex` commit/reload
 * cliff edge. Where the fixed-seed sibling test enumerates a small, frozen seed list, this harness
 * keeps deriving fresh seeds and running the canonical pre-reload / reload / post-reload cycle until
 * a wall-clock budget is exhausted.
 *
 * It uses the standard generational-test plumbing — {@link TimeArgumentProvider} +
 * {@link TimeBoundedTestSupport#runFor} — exactly like
 * `LongRunningEvitaReferencesGenerationalTest#generationalTransactionalModificationProofTest`:
 *
 *   - Time-bounded: runs for the {@link TimeArgumentProvider} budget (default 1 minute; override
 *     with the JUnit `interval` configuration parameter) and stops as soon as the deadline passes.
 *   - Randomized but reproducible: every iteration's child seed is drawn from the single `runFor`
 *     random, itself seeded from {@link GenerationalTestInput#randomSeed()}. The whole run replays
 *     deterministically with `-Dtest.seed=<seed>`, and the failing child seed is surfaced in the
 *     assertion message so it can be replayed in isolation through the fixed-seed siblings
 *     `SharedRgeiSoakTest` / `SharedRgeiPriceStrandTest`.
 *   - Fail-fast: the first failing seed aborts the run (the standard behaviour); `runFor` enriches
 *     the thrown error with the reproduction seed.
 *   - Self-cleaning: each iteration owns a unique on-disk path and closes its `Evita` instance in a
 *     `finally` block, so neither file handles nor disk usage accumulate across iterations.
 *
 * The schema, mutation generator and reference-consistency assertions are duplicated verbatim from
 * the fixed-seed sibling so that this harness stays independent of it — the duplication is
 * intentional and lets the two tests evolve separately.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiresDefaultWarmUpWritePath
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
@Tag(SLOW)
@DisplayName("Shared ReducedGroupEntityIndex randomized soak — time-bounded reload cliff edge")
class SharedRgeiRandomizedSoakTest implements EvitaTestSupport, TimeBoundedTestSupport {

	/**
	 * Local attribute names — these are not declared in `DataGenerator` constants but live on the
	 * `categoryGroup`/`categoryOrder` slots used by the long-running test's schema. Keeping them in
	 * sync with `LongRunningEvitaReferencesGenerationalTest` is intentional: drift here would silently
	 * decouple the two tests.
	 */
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_CATEGORY_GROUP = "categoryGroup";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_PRODUCTS = "products";

	/**
	 * Number of products to seed before the mutation loop starts. Kept small — just enough to give
	 * the random mutator a meaningful working set without inflating reload time.
	 */
	private static final int INITIAL_PRODUCT_COUNT = 10;

	/**
	 * Total mutations applied per seed split across the two phases (pre-reload + post-reload). Each
	 * phase performs `MUTATIONS_PER_PHASE` operations so the reload boundary lands roughly halfway.
	 */
	private static final int MUTATIONS_PER_PHASE = 60;

	/**
	 * Catalog name used for the test catalog — fixed so seed-by-seed cleanup is deterministic.
	 */
	private static final String CATALOG_NAME = TEST_CATALOG;

	/**
	 * Number of removed entities allowed to accumulate before the random walk prefers restore over
	 * fresh removal. Mirrors the long-running test's soft cap but scaled to the small dataset.
	 */
	private static final int MAX_REMOVED_ENTITIES = 4;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Drives the canonical pre-reload / reload / post-reload cycle against a fresh child seed every
	 * iteration until the {@link TimeArgumentProvider} budget is exhausted, using the standard
	 * {@link TimeBoundedTestSupport#runFor} loop. Each iteration draws its child seed from the single
	 * `runFor` random (seeded from {@link GenerationalTestInput#randomSeed()}), so the whole run is
	 * reproducible with `-Dtest.seed=<seed>`. The first failing seed aborts the run (fail-fast) and the
	 * offending child seed is named in the assertion so it can be replayed in isolation through the
	 * fixed-seed siblings `SharedRgeiSoakTest` / `SharedRgeiPriceStrandTest`.
	 *
	 * @param input the generational test input supplied by {@link TimeArgumentProvider} (duration + seed)
	 */
	@ParameterizedTest(name = "Shared RGEI commit+reload survives randomized seeds within the time budget")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldSurviveRandomizedSeedsUnderTimeBudget(@Nonnull final GenerationalTestInput input) {
		runFor(
			input,
			0,
			(random, iteration) -> {
				final long childSeed = random.nextLong();
				this.paths = createTestPaths("SharedRgeiRandomizedSoak_" + childSeed);
				try {
					runFullCycle(childSeed);
				} catch (final Throwable t) {
					// surface the child seed so the failure can be replayed in isolation via the
					// fixed-seed siblings, then rethrow so runFor aborts (fail-fast)
					throw new AssertionError(
						"Randomized soak failed at child seed " + childSeed
							+ " (replay in isolation via SharedRgeiSoakTest / SharedRgeiPriceStrandTest "
							+ "with seed=" + childSeed + "): " + t.getMessage(),
						t
					);
				} finally {
					// always release per-iteration resources so handles + disk do not leak across the run
					cleanupIteration();
				}
				return iteration + 1;
			}
		);
	}

	/**
	 * Releases the per-iteration `Evita` instance and its on-disk paths so file handles and disk usage
	 * do not accumulate across randomized iterations. Tolerates a half-built instance: a close failure
	 * is logged and never masks the iteration outcome.
	 */
	private void cleanupIteration() {
		if (this.evita != null) {
			try {
				this.evita.close();
			} catch (final Throwable closeError) {
				// closing a half-broken instance must not mask the iteration outcome
				System.out.println("[SharedRgeiRandomizedSoak] close failed -> " + closeError);
			}
			this.evita = null;
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
			this.paths = null;
		}
	}

	/**
	 * Drives the canonical pre-reload / reload / post-reload cycle for the supplied seed against a
	 * freshly built `Evita` instance anchored at the current per-iteration path triplet.
	 *
	 * @param seed deterministic seed for the random walk and data generator
	 */
	private void runFullCycle(final long seed) {
		final DataGenerator dataGenerator = new DataGenerator();
		final Map<Serializable, Integer> generatedEntities = new HashMap<>();
		final BiFunction<String, Faker, Integer> randomEntityPicker =
			createRandomEntityPicker(generatedEntities);

		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(CATALOG_NAME);

		// phase 1: build a slim catalog and seed it with the initial product set
		setUpCatalog(dataGenerator, randomEntityPicker, generatedEntities, seed);

		// phase 2: run the first half of the mutation budget under transaction
		final Map<Integer, SealedEntity> removedEntities = new HashMap<>(MAX_REMOVED_ENTITIES);
		final Random random = new Random(seed);
		final Function<SealedEntity, EntityBuilder> modificationFunction =
			dataGenerator.createModificationFunction(randomEntityPicker, random);

		runMutationPhase("pre-reload", modificationFunction, removedEntities, random);

		// phase 3: force a commit+reload boundary — the cliff edge for manifest / contents divergence
		assertReferencesAreConsistent("pre-reload");
		closeAndReopenEvita();
		assertReferencesAreConsistent("post-reload");

		// phase 4: re-apply the rest of the mutation budget so any drift introduced by the reload
		// has a chance to fire on the rehydrated indexes
		runMutationPhase("post-reload", modificationFunction, removedEntities, random);

		assertReferencesAreConsistent("final");
	}

	/**
	 * Constructs the random-entity picker used by `DataGenerator` to wire references to existing
	 * brands and categories.
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
	 * Mirrors the schema definition from `LongRunningEvitaReferencesGenerationalTest` so the same
	 * `ReducedGroupEntityIndex` shape is exercised.
	 *
	 * @param dataGenerator       shared data generator instance for entity generation
	 * @param randomEntityPicker  picker that wires references during product generation
	 * @param generatedEntities   map populated with the highest seen primary key per entity type
	 * @param seed                deterministic seed for data generation
	 */
	private void setUpCatalog(
		@Nonnull final DataGenerator dataGenerator,
		@Nonnull final BiFunction<String, Faker, Integer> randomEntityPicker,
		@Nonnull final Map<Serializable, Integer> generatedEntities,
		final long seed
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

				dataGenerator.generateEntities(brandSchema, randomEntityPicker, seed)
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

				dataGenerator.generateEntities(categorySchema, randomEntityPicker, seed)
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

				dataGenerator.generateEntities(productSchema, randomEntityPicker, seed)
					.limit(INITIAL_PRODUCT_COUNT)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Applies `MUTATIONS_PER_PHASE` random mutations against the current `Evita` instance under a
	 * single transactional session. Each mutation is one of: remove an existing entity, restore a
	 * previously removed entity, or modify an existing entity.
	 *
	 * Any thrown exception is captured with the operation context so the assertion message points
	 * directly at the failing operation.
	 *
	 * @param label                phase label used in the failure message (pre/post-reload)
	 * @param modificationFunction function that mutates an existing entity into a builder
	 * @param removedEntities      shared map of removed entities, mutable across phases
	 * @param random               deterministic random source seeded once per test invocation
	 */
	private void runMutationPhase(
		@Nonnull final String label,
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
					operation = label + ":removal of " + primaryKey;
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
					operation = label + ":restore of " + entityToRestore.getPrimaryKey();
					session.upsertEntity(new CopyExistingEntityBuilder(entityToRestore));
					assertNotNull(
						session.getEntity(
							Entities.PRODUCT, entityToRestore.getPrimaryKey(), entityFetchAllContent()
						).orElse(null),
						"Restored entity " + entityToRestore.getPrimaryKey() + " missing post-upsert"
					);
				} else {
					operation = label + ":modification of " + primaryKey;
					final SealedEntity existingEntity = session.getEntity(
						Entities.PRODUCT, primaryKey, entityFetchAllContent()
					).orElseThrow();
					session.upsertEntity(modificationFunction.apply(existingEntity));
				}
			}
		} catch (final Exception ex) {
			// any thrown exception during the mutation loop is a regression signal; the operation
			// context narrows down which entity tripped
			fail(
				"Failed during " + operation + " (seed-driven random walk; phase=" + label + "): "
					+ ex.getMessage(),
				ex
			);
		}
	}

	/**
	 * Closes the running `Evita` instance and reopens it against the same on-disk storage. Any
	 * index whose persisted state diverges from its in-memory state surfaces as a runtime failure
	 * on the next mutation that touches it.
	 */
	private void closeAndReopenEvita() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Cross-validates product references against the reflected `products` references on category
	 * entities. Failures indicate either a missing reflection or an attribute-value drift between
	 * the owning and reflected side.
	 *
	 * Adapted from `LongRunningEvitaReferencesGenerationalTest#assertReferencesAreConsistent` but
	 * trimmed to the small dataset and parameterised with a phase label so failures are localised.
	 *
	 * @param phase human-readable phase label included in the failure message
	 */
	private void assertReferencesAreConsistent(@Nonnull final String phase) {
		this.evita.queryCatalog(
			CATALOG_NAME,
			session -> {
				final Map<Integer, ExpectedProducts> expectedProductsIndex = new HashMap<>(8);
				int pageNumber = 1;
				EvitaResponse<SealedEntity> products;
				do {
					products = session.querySealedEntity(
						Query.query(
							collection(Entities.PRODUCT),
							require(entityFetchAll(), page(pageNumber++, 100))
						)
					);
					for (final SealedEntity product : products.getRecordPage().getData()) {
						product.getReferences(Entities.CATEGORY).forEach(ref -> {
							final Integer categoryPrimaryKey = ref.getReferencedPrimaryKey();
							final Long categoryPriority = ref.getAttribute(
								ATTRIBUTE_CATEGORY_PRIORITY, Long.class
							);
							final String categoryGroup = ref.getAttribute(
								ATTRIBUTE_CATEGORY_GROUP, String.class
							);
							assertTrue(
								ref.getGroup().isPresent(),
								"[" + phase + "] product " + product.getPrimaryKey()
									+ " reference to category " + categoryPrimaryKey
									+ " is missing its group entity reference"
							);
							expectedProductsIndex.computeIfAbsent(
								categoryPrimaryKey, k -> new ExpectedProducts()
							).addProduct(
								product.getPrimaryKey(),
								ref.getReferenceKey(),
								Objects.requireNonNull(categoryPriority),
								Objects.requireNonNull(categoryGroup)
							);
						});
					}
				} while (products.getRecordPage().hasNext());

				assertFalse(
					expectedProductsIndex.isEmpty(),
					"[" + phase + "] no product carries a category reference — generator misconfigured?"
				);

				for (final Map.Entry<Integer, ExpectedProducts> entry : expectedProductsIndex.entrySet()) {
					final Integer categoryPrimaryKey = entry.getKey();
					final ExpectedProducts expectedProducts = entry.getValue();
					final SealedEntity category = session.getEntity(
						Entities.CATEGORY, categoryPrimaryKey, entityFetchAllContent()
					).orElseThrow();

					final List<Integer> actualProductIds = category.getReferences(REFERENCE_PRODUCTS)
						.stream()
						.peek(ref -> {
							final ReferenceKey referenceKey = ref.getReferenceKey();
							assertEquals(
								expectedProducts.getPriority(referenceKey),
								ref.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class),
								"[" + phase + "] category " + categoryPrimaryKey
									+ " reference to product " + referenceKey
									+ " has incorrect " + ATTRIBUTE_CATEGORY_PRIORITY
							);
							assertEquals(
								expectedProducts.getGroup(referenceKey),
								ref.getAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class),
								"[" + phase + "] category " + categoryPrimaryKey
									+ " reference to product " + referenceKey
									+ " has incorrect " + ATTRIBUTE_CATEGORY_GROUP
							);
						})
						.map(ReferenceContract::getReferencedPrimaryKey)
						.sorted()
						.toList();

					final List<Integer> expectedProductIds = expectedProducts.getProductIds();
					assertEquals(
						actualProductIds,
						expectedProductIds,
						"[" + phase + "] category " + categoryPrimaryKey
							+ " reflected products " + actualProductIds
							+ " diverge from expected " + expectedProductIds
					);
				}
				return null;
			}
		);
	}

	/**
	 * Builds the standard Evita configuration anchored at the per-test path triplet.
	 *
	 * @return the configuration used to construct the `Evita` instance
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Returns a deterministically selected entity from the supplied map. Caller must ensure the map
	 * is non-empty.
	 *
	 * @param random random source seeded once per test invocation
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

	/**
	 * Tracks expected `(productId -> priority, group)` tuples per category for the reflected-reference
	 * consistency check. Lifted from `LongRunningEvitaReferencesGenerationalTest`; reused verbatim to
	 * keep the assertion semantics identical between the slim and the long-running variant.
	 */
	private static final class ExpectedProducts {
		private final List<Integer> productIds = new ArrayList<>(16);
		private final Map<ComparableKey, Long> productPriorities = new HashMap<>(16);
		private final Map<ComparableKey, String> productGroups = new HashMap<>(16);

		/**
		 * Records one expected `(product, priority, group)` triple keyed by reference key.
		 *
		 * @param productId    product primary key
		 * @param referenceKey reference key carrying the internal PK
		 * @param priority     expected priority value on the reference
		 * @param group        expected categoryGroup value on the reference
		 */
		void addProduct(
			final int productId,
			@Nonnull final ReferenceKey referenceKey,
			@Nonnull final Long priority,
			@Nonnull final String group
		) {
			this.productIds.add(productId);
			final ComparableKey crk = new ComparableKey(productId, referenceKey.internalPrimaryKey());
			this.productPriorities.put(crk, priority);
			this.productGroups.put(crk, group);
		}

		/**
		 * Returns expected product ids sorted ascending — the canonical comparison shape against the
		 * reflected category references.
		 */
		@Nonnull
		List<Integer> getProductIds() {
			this.productIds.sort(Comparator.naturalOrder());
			return this.productIds;
		}

		/**
		 * Returns expected priority for the supplied reference key.
		 */
		@Nonnull
		Long getPriority(@Nonnull final ReferenceKey referenceKey) {
			return this.productPriorities.get(new ComparableKey(referenceKey));
		}

		/**
		 * Returns expected categoryGroup for the supplied reference key.
		 */
		@Nonnull
		String getGroup(@Nonnull final ReferenceKey referenceKey) {
			return this.productGroups.get(new ComparableKey(referenceKey));
		}

		/**
		 * Composite key pairing the product primary key with the reference's internal primary key.
		 * Required because the same product can carry multiple references to the same category, each
		 * with its own attribute values.
		 */
		private record ComparableKey(int referencedPrimaryKey, int internalPrimaryKey) {
			ComparableKey(@Nonnull final ReferenceKey referenceKey) {
				this(referenceKey.primaryKey(), referenceKey.internalPrimaryKey());
			}
		}
	}
}
