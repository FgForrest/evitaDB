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

package io.evitadb.api;

import com.github.javafaker.Faker;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.apachecommons.CommonsLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@CommonsLog
class EvitaReferencesGenerationalTest implements EvitaTestSupport, TimeBoundedTestSupport {
	/**
	 * Seed for data generation.
	 */
	private static final long SEED = 10;
	/**
	 * Count of the product that will exist in the database BEFORE the test starts.
	 */
	private static final int INITIAL_COUNT_OF_PRODUCTS = 1000;
	private static final String DIRECTORY_EVITA_GENERATIONAL_TEST = "evitaReferenceGenerationalTest";
	private static final String DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT = "evitaReferenceGenerationalTest_export";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_CATEGORY_GROUP = "categoryGroup";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_PRODUCTS = "products";
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator();
	/**
	 * Index of created entities that allows to retrieve referenced entities when creating product.
	 */
	protected final Map<Serializable, Integer> generatedEntities = new HashMap<>();
	/**
	 * Function allowing to pseudo randomly pick referenced entity for the product.
	 */
	protected final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
		final Integer entityCount = this.generatedEntities.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	/**
	 * Created randomized category schema.
	 */
	protected SealedEntitySchema categorySchema;
	/**
	 * Created randomized product schema.
	 */
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that infinitely produces new artificial products.
	 */
	protected Iterator<EntityBuilder> productIterator;
	/**
	 * Evita instance.
	 */
	private Evita evita;
	/**
	 * Functions allows to pseudo randomly modify existing product contents.
	 */
	private Function<SealedEntity, EntityBuilder> modificationFunction;

	/**
	 * Creates new product stream for the iteration.
	 */
	protected Stream<EntityBuilder> getProductStream() {
		return this.dataGenerator.generateEntities(
			this.productSchema,
			this.randomEntityPicker,
			SEED
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT);
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		final String catalogName = "testCatalog";
		// prepare database
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			catalogName,
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
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						brandSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> this.generatedEntities.put(Entities.BRAND, it.getPrimaryKeyOrThrowException()));

				this.categorySchema = session.defineEntitySchema(Entities.CATEGORY)
					.verifySchemaStrictly()
					.withGeneratedPrimaryKey()
					.withoutHierarchy()
					.withoutPrice()
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					/* here we define list of attributes with indexes for search / sort */
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
							.indexedForFiltering()
							.withAttributesInheritedExcept(ATTRIBUTE_CATEGORY_ORDER)
					)
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						this.categorySchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> this.generatedEntities.put(Entities.CATEGORY, it.getPrimaryKeyOrThrowException()));

				this.productSchema = session.defineEntitySchema(Entities.PRODUCT)
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
					/* here we define list of attributes with indexes for search / sort */
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable())
					.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable())
					.withAttribute(ATTRIBUTE_ALIAS, Boolean.class, whichIs -> whichIs.filterable().withDefaultValue(false))
					/* here we define set of associated data, that can be stored along with entity */
					.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, whichIs -> whichIs.nullable())
					.withAssociatedData(ASSOCIATED_DATA_LABELS, Labels.class, whichIs -> whichIs.localized().nullable())
					/* here we define facets that relate to another entities stored in Evita */
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs ->
							/* we can specify special attributes on relation */
							whichIs.indexedForFilteringAndPartitioning()
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
								.withAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class, thatIs -> thatIs.filterable().representative())
								.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Long.class, thatIs -> thatIs.sortable())
					)
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT);
	}

	@Test
	void loadTest() {
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		assertNotNull(this.evita);
	}

	@ParameterizedTest(name = "Evita should survive generational randomized test upserting the values in transaction (focused on reference handling)")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalTransactionalModificationProofTest(GenerationalTestInput input) {
		final int maximumAmountOfRemovedEntities = 50;
		final Map<Integer, SealedEntity> removedEntities = CollectionUtils.createHashMap(maximumAmountOfRemovedEntities);

		final TestState finalState = runFor(
			input,
			100,
			new TestState(0, 0),
			(random, testState) -> {
				int generation = testState.generation();
				int updateCounter = testState.updateCounter();
				// create product modificator
				if (this.modificationFunction == null) {
					this.modificationFunction = this.dataGenerator.createModificationFunction(this.randomEntityPicker, random);
				}

				String operation = null;
				try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
					final int iterations = random.nextInt(500);
					for (int i = 0; i < iterations; i++) {
						int primaryKey;
						do {
							primaryKey = random.nextInt(INITIAL_COUNT_OF_PRODUCTS) + 1;
						} while (removedEntities.containsKey(primaryKey));

						if (random.nextInt(10) == 0 && removedEntities.size() < maximumAmountOfRemovedEntities) {
							int productId = primaryKey;
							removedEntities.put(
								primaryKey,
								session.getEntity(
									Entities.PRODUCT,
									primaryKey,
									entityFetchAllContent()
								)
									.orElseThrow(
										() -> new IllegalStateException("Product with primary key " + productId + " was not found.")
									)
							);
							operation = "removal of " + primaryKey;
							session.deleteEntity(Entities.PRODUCT, primaryKey);
						} else if (random.nextInt(10) == 0 && removedEntities.size() > 10) {
							final SealedEntity entityToRestore = pickRandom(random, removedEntities.values());
							removedEntities.remove(entityToRestore.getPrimaryKey());
							operation = "restoring of " + entityToRestore.getPrimaryKey();
							session.upsertEntity(
								new CopyExistingEntityBuilder(entityToRestore)
							);
							assertNotNull(
								session.getEntity(
									Entities.PRODUCT,
									entityToRestore.getPrimaryKey(),
									entityFetchAllContent()
								)
							);
						} else {
							operation = "modification of " + primaryKey;
							final SealedEntity existingEntity = session.getEntity(
								Entities.PRODUCT,
								primaryKey,
								entityFetchAllContent()
							).orElseThrow();
							session.upsertEntity(
								this.modificationFunction.apply(existingEntity)
							);
						}
						updateCounter++;
					}
				} catch (Exception ex) {
					fail("Failed to execute " + operation + ": " + ex.getMessage(), ex);
				}

				generation++;

				assertReferencesAreConsistent();
				if (generation % 3 == 0) {
					// reload EVITA entirely
					this.evita.close();
					System.out.println("Survived " + generation + " generations, size on disk is " + byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile())));
					this.evita = new Evita(
						getEvitaConfiguration()
					);
					this.evita.waitUntilFullyInitialized();
					// check all references are correctly interlined
					assertReferencesAreConsistent();
				}

				return new TestState(
					generation, updateCounter
				);
			}
		);
		System.out.println(
			"Finished " + finalState.generation() + " generations (" + finalState.updateCounter() + " updates), size on disk is " +
				byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile()))
		);
	}

	/**
	 * Validates consistency between product references in the catalog and their corresponding category references.
	 *
	 * This method queries all products from the specified catalog and builds an index of expected product references
	 * for each category. It then iterates through each category, comparing its actual references to the expected
	 * references. For each reference, it validates that:
	 * 1. All actual product references exist in the expected references.
	 * 2. The `ATTRIBUTE_CATEGORY_ORDER` and `ATTRIBUTE_CATEGORY_GROUP` attributes match the expected values.
	 *
	 * If any inconsistency is found in the references, the method will throw an assertion error to indicate the
	 * validation failure.
	 *
	 * The validation ensures the following:
	 * - Cross-reference integrity between products and categories.
	 * - Correct attribute values for each reference.
	 *
	 * This method characteristically operates in a transactional manner to query and validate data while adhering to
	 * immutability principles guaranteed by the read-only nature of `SealedEntity`.
	 *
	 * Preconditions:
	 * - The catalog name `TEST_CATALOG` must exist.
	 * - Product and category entities must adhere to the schema definitions.
	 *
	 * Implementation details:
	 * - Products are queried in paginated batches to handle large datasets.
	 * - The method utilizes `HashMap` and `List` for tracking and validating references.
	 * - Each reference is validated with attributes being explicitly required and non-null.
	 * - The consistency check relies on the correctness of entity attributes and reference structures.
	 *
	 * Exceptions:
	 * Throws an assertion error if:
	 * - A product reference in a category does not match the expected product index.
	 * - The attributes of a product reference are inconsistent with the expected values.
	 */
	private void assertReferencesAreConsistent() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<Integer, ExpectedProducts> expectedProductsIndex = new HashMap<>(32);
				int pageNumber = 1;
				EvitaResponse<SealedEntity> products;
				do {
					products = session.querySealedEntity(
						Query.query(
							collection(Entities.PRODUCT),
							require(
								entityFetchAll(),
								page(pageNumber++, 100)
							)
						)
					);
					for (SealedEntity product : products.getRecordPage().getData()) {
						product.getReferences(Entities.CATEGORY).forEach(ref -> {
							final Integer categoryPrimaryKey = ref.getReferencedPrimaryKey();
							final Long categoryPriority = ref.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class);
							final String categoryGroup = ref.getAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class);
							assertTrue(
								ref.getGroup().isPresent(),
								"Product " + product.getPrimaryKey() + " reference to category " +
									categoryPrimaryKey + " should have a group entity reference"
							);
							expectedProductsIndex.computeIfAbsent(
								categoryPrimaryKey,
								k -> new ExpectedProducts()
							).addProduct(
								product.getPrimaryKey(),
								ref.getReferenceKey(),
								Objects.requireNonNull(categoryPriority),
								Objects.requireNonNull(categoryGroup)
							);
						});
					}
				} while (products.getRecordPage().hasNext());

				assertFalse(expectedProductsIndex.isEmpty());

				// now verify that all references are correct
				for (Map.Entry<Integer, ExpectedProducts> expectedProductsEntry : expectedProductsIndex.entrySet()) {
					final Integer categoryPrimaryKey = expectedProductsEntry.getKey();
					final ExpectedProducts expectedProducts = expectedProductsEntry.getValue();
					final SealedEntity category = session.getEntity(
						Entities.CATEGORY,
						categoryPrimaryKey,
						entityFetchAllContent()
					).orElseThrow();

					final List<Integer> actualProductIds = category.getReferences(REFERENCE_PRODUCTS)
						.stream()
						.peek(ref -> {
							final ReferenceKey referenceKey = ref.getReferenceKey();
							assertEquals(
								expectedProducts.getPriority(referenceKey), ref.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class),
								"Category " + categoryPrimaryKey + " reference to product " + referenceKey +
									" has incorrect attribute " + ATTRIBUTE_CATEGORY_PRIORITY
							);
							assertEquals(
								expectedProducts.getGroup(referenceKey), ref.getAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class),
								"Category " + categoryPrimaryKey + " reference to product " + referenceKey +
									" has incorrect attribute " + ATTRIBUTE_CATEGORY_GROUP
							);
							// Note: reflected references do not carry group entity references
							// (groups are only stored on the original/owning side of the reference),
							// so we do not validate groups here on the category (reflected) side.
						})
						.map(ReferenceContract::getReferencedPrimaryKey)
						.sorted()
						.toList();

					final List<Integer> expectedProductIds = expectedProducts.getProductIds();
					assertEquals(
						actualProductIds,
						expectedProductIds,
						"Category " + categoryPrimaryKey + " references products " + actualProductIds +
							" but expected products were " + expectedProductIds
					);
				}

				return null;
			}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIRECTORY_EVITA_GENERATIONAL_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Returns random element from the set.
	 */
	@Nonnull
	private static <T> T pickRandom(@Nonnull Random random, @Nonnull Collection<T> theSet) {
		Assert.isTrue(!theSet.isEmpty(), "There are no values to choose from!");
		final int index = theSet.size() == 1 ? 0 : random.nextInt(theSet.size() - 1) + 1;
		final Iterator<T> it = theSet.iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}

	/**
	 * Generational test state.
	 *
	 * @param generation    total count of generations that were correctly created
	 * @param updateCounter simple counter for measuring total product count updated in the database.
	 */
	private record TestState(
		int generation,
		int updateCounter
	) {
	}

	/**
	 * The ExpectedProducts class is a utility class used to maintain and manage
	 * information about a collection of products. It tracks product IDs, their
	 * associated priorities, and group identifiers.
	 *
	 * Product information is stored in three collections:
	 * - {@code productIds} keeps track of the product IDs.
	 * - {@code productPriorities} maps each product ID to a priority value.
	 * - {@code productGroups} maps each product ID to a group identifier.
	 *
	 * This class provides methods to add product information and retrieve details
	 * such as product IDs, priorities, and group identifiers for a product.
	 *
	 * Thread Safety: This class is not thread-safe as it does not use
	 * synchronization mechanisms. Concurrent modifications to the collections
	 * may lead to inconsistent results.
	 */
	private static class ExpectedProducts {
		private final List<Integer> productIds = new ArrayList<>(128);
		private final Map<ComparableKey, Long> productPriorities = new HashMap<>(128);
		private final Map<ComparableKey, String> productGroups = new HashMap<>(128);

		public void addProduct(
			int productId,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull Long priority,
			@Nonnull String group
		) {
			this.productIds.add(productId);
			final ComparableKey crk = new ComparableKey(productId, referenceKey.internalPrimaryKey());
			this.productPriorities.put(crk, priority);
			this.productGroups.put(crk, group);
		}

		@Nonnull
		public List<Integer> getProductIds() {
			this.productIds.sort(Comparator.naturalOrder());
			return this.productIds;
		}

		@Nonnull
		public Long getPriority(@Nonnull ReferenceKey referenceKey) {
			return this.productPriorities.get(new ComparableKey(referenceKey));
		}

		@Nonnull
		public String getGroup(@Nonnull ReferenceKey referenceKey) {
			return this.productGroups.get(new ComparableKey(referenceKey));
		}

		private record ComparableKey(
			int referencedPrimaryKey,
			int internalPrimaryKey
		) {

			public ComparableKey(@Nonnull ReferenceKey referenceKey) {
				this(referenceKey.primaryKey(), referenceKey.internalPrimaryKey());
			}
		}

	}

}
