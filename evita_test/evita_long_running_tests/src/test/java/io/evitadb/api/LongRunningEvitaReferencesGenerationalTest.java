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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Long-running generational test for {@link Evita} focused on reference handling. Continuously
 * upserts, deletes and restores products with references and verifies that the reflected
 * references on the related entities remain consistent across catalog reloads.
 *
 * The random mutations move the partitions of the reflected `products` reference of the categories - indexed for
 * filtering only - between no owner, a single owner and several owners. Every generation therefore also filters the
 * categories by `referenceHaving` on that reference and compares the answer with the categories the test's own model
 * of the written data expects (see {@link #assertReferenceHavingMatchesModel(int)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@CommonsLog
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class LongRunningEvitaReferencesGenerationalTest implements EvitaTestSupport, TimeBoundedTestSupport {
	/**
	 * Seed for data generation.
	 */
	private static final long SEED = 10;
	/**
	 * Count of the product that will exist in the database BEFORE the test starts.
	 */
	private static final int INITIAL_COUNT_OF_PRODUCTS = 1000;
	/**
	 * Count of the categories the products reference - they are never removed.
	 */
	private static final int CATEGORY_COUNT = 10;
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
	 * The test's own model of the written data: the rows of the `CATEGORY` reference of every existing product, as the
	 * test wrote them, by product primary key - a removed product has no entry. Each row is reflected on its category as
	 * a row of {@link #REFERENCE_PRODUCTS} carrying the same inherited attributes, so the model tells which categories a
	 * `referenceHaving` on that reference must find without asking evitaDB. The rows are kept in their natural order,
	 * so two lists of the same rows are equal.
	 */
	private final Map<Integer, List<CategoryRow>> categoryRowsByProduct =
		CollectionUtils.createHashMap(INITIAL_COUNT_OF_PRODUCTS);
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
	private TestPaths paths;
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
		this.paths = createTestPaths("EvitaReferencesGenerationalTest");
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		this.categoryRowsByProduct.clear();
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
					.limit(CATEGORY_COUNT)
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
					.withAttribute(ATTRIBUTE_PRIORITY, Long.class, AttributeSchemaEditor::sortable)
					.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable())
					.withAttribute(ATTRIBUTE_ALIAS, Boolean.class, whichIs -> whichIs.filterable().withDefaultValue(false))
					/* here we define set of associated data, that can be stored along with entity */
					.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, AssociatedDataSchemaEditor::nullable)
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
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Long.class, AttributeSchemaEditor::sortable)
					)
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(product -> {
						session.upsertEntity(product);
						rememberCategoryRows(product.toInstance());
					});

				session.goLiveAndClose();
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "Evita should survive generational randomized test upserting the values in transaction (focused on reference handling)")
	@Tag(SLOW)
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
							this.categoryRowsByProduct.remove(primaryKey);
						} else if (random.nextInt(10) == 0 && removedEntities.size() > 10) {
							final SealedEntity entityToRestore = pickRandom(random, removedEntities.values());
							removedEntities.remove(entityToRestore.getPrimaryKey());
							operation = "restoring of " + entityToRestore.getPrimaryKey();
							session.upsertEntity(
								new CopyExistingEntityBuilder(entityToRestore)
							);
							rememberCategoryRows(entityToRestore);
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
							final EntityBuilder modifiedEntity = this.modificationFunction.apply(existingEntity);
							session.upsertEntity(modifiedEntity);
							rememberCategoryRows(modifiedEntity.toInstance());
						}
						updateCounter++;
					}
				} catch (Exception ex) {
					fail("Failed to execute " + operation + ": " + ex.getMessage(), ex);
				}

				generation++;

				assertReferencesAreConsistent();
				assertReferenceHavingMatchesModel(generation);
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
					assertReferenceHavingMatchesModel(generation);
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
	 * - The test's own model of the product references ({@link #categoryRowsByProduct}) equals the stored products, so
	 *   the `referenceHaving` check compares the index with what was really written.
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
				final Map<Integer, List<CategoryRow>> storedCategoryRows =
					CollectionUtils.createHashMap(INITIAL_COUNT_OF_PRODUCTS);
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
						storedCategoryRows.put(product.getPrimaryKeyOrThrowException(), toCategoryRows(product));
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
				// the model is the oracle of the `referenceHaving` check - a divergence here is a fault of the model
				// (or of the write path), never of the index the check reads
				assertEquals(
					this.categoryRowsByProduct.keySet(), storedCategoryRows.keySet(),
					"The test's model holds other products than the catalog."
				);
				for (Map.Entry<Integer, List<CategoryRow>> stored : storedCategoryRows.entrySet()) {
					assertEquals(
						this.categoryRowsByProduct.get(stored.getKey()), stored.getValue(),
						"The test's model of the category references of product " + stored.getKey() +
							" differs from the stored product."
					);
				}

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

	/**
	 * Filters the categories by `referenceHaving` on {@link #REFERENCE_PRODUCTS} - the reflected reference indexed for
	 * filtering only, whose partitions the random mutations move between no owner, a single owner and several owners -
	 * and compares every answer with the categories the test's own model ({@link #categoryRowsByProduct}) expects.
	 * Every product primary key the test uses is asked about, removed products included, in three shapes:
	 *
	 * - `entityPrimaryKeyInSet(p)`: the categories with a row referencing the product `p`, none for a removed product;
	 * - `entityPrimaryKeyInSet(p), attributeEquals(categoryGroup, g)`: those with a row referencing `p` in the group
	 *   `g` of a picked row of `p`;
	 * - `entityPrimaryKeyInSet(p), not(attributeGreaterThanEquals(categoryPriority, x))`: those with a row referencing
	 *   `p` whose priority is below the priority `x` of the same picked row - the negation holds per row, so a
	 *   category with another row of a higher priority is found as well.
	 *
	 * Each query asks about a single product so that its answer depends on the partitions of that product alone - over
	 * many products the ten categories would all be found even when one owner went missing. `entityPrimaryKeyInSet`
	 * also keeps every shape on this side of the reference pair: the rewrite to the products' own reference takes over
	 * only a body of reference attributes and `entityHaving`.
	 *
	 * @param generation the number of the generation, which picks the row of each product the attribute shapes compare
	 *                   with - the random of the generation is left alone, so a seed replays the same mutations
	 */
	private void assertReferenceHavingMatchesModel(int generation) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int productId = 1; productId <= INITIAL_COUNT_OF_PRODUCTS; productId++) {
					final List<CategoryRow> rows = this.categoryRowsByProduct.getOrDefault(productId, List.of());
					assertCategoriesFound(
						session, rows, referenceHaving(REFERENCE_PRODUCTS, entityPrimaryKeyInSet(productId)), row -> true
					);
					if (!rows.isEmpty()) {
						final CategoryRow pickedRow = rows.get((productId + generation) % rows.size());
						assertCategoriesFound(
							session, rows,
							referenceHaving(
								REFERENCE_PRODUCTS,
								entityPrimaryKeyInSet(productId),
								attributeEquals(ATTRIBUTE_CATEGORY_GROUP, pickedRow.group())
							),
							row -> row.group().equals(pickedRow.group())
						);
						assertCategoriesFound(
							session, rows,
							referenceHaving(
								REFERENCE_PRODUCTS,
								entityPrimaryKeyInSet(productId),
								not(attributeGreaterThanEquals(ATTRIBUTE_CATEGORY_PRIORITY, pickedRow.priority()))
							),
							row -> row.priority() < pickedRow.priority()
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Queries the categories satisfying a `referenceHaving` and asserts they are exactly the categories of the product
	 * rows the constraint matches.
	 *
	 * @param session         the session to query in
	 * @param rows            the rows of the `CATEGORY` reference of the product the constraint asks about, empty for a
	 *                        removed product
	 * @param referenceHaving the constraint
	 * @param matchingRow     tells which of the rows satisfy the body of the constraint
	 */
	private static void assertCategoriesFound(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<CategoryRow> rows,
		@Nonnull FilterConstraint referenceHaving,
		@Nonnull Predicate<CategoryRow> matchingRow
	) {
		final List<Integer> expectedCategories = rows.stream()
			.filter(matchingRow)
			.map(CategoryRow::categoryPrimaryKey)
			.distinct()
			.sorted()
			.toList();
		final List<Integer> foundCategories = session.queryListOfEntityReferences(
				Query.query(
					collection(Entities.CATEGORY),
					filterBy(referenceHaving),
					require(page(1, CATEGORY_COUNT))
				)
			)
			.stream()
			.map(EntityReferenceContract::getPrimaryKeyOrThrowException)
			.sorted()
			.toList();
		assertEquals(
			expectedCategories, foundCategories,
			() -> "`" + referenceHaving + "` found the categories " + foundCategories + ", but the product rows the " +
				"test wrote expect " + expectedCategories + " - rows: " + rows
		);
	}

	/**
	 * Records the rows of the `CATEGORY` reference of a product the test has just written in
	 * {@link #categoryRowsByProduct}.
	 *
	 * @param product the product as written
	 */
	private void rememberCategoryRows(@Nonnull SealedEntity product) {
		this.categoryRowsByProduct.put(product.getPrimaryKeyOrThrowException(), toCategoryRows(product));
	}

	/**
	 * Returns the rows of the `CATEGORY` reference of a product in their natural order.
	 *
	 * @param product the product
	 * @return its rows, possibly empty
	 */
	@Nonnull
	private static List<CategoryRow> toCategoryRows(@Nonnull SealedEntity product) {
		return product.getReferences(Entities.CATEGORY)
			.stream()
			.map(
				ref -> new CategoryRow(
					ref.getReferencedPrimaryKey(),
					Objects.requireNonNull(ref.getAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class)),
					Objects.requireNonNull(ref.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class))
				)
			)
			.sorted()
			.toList();
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.build();
	}

	/**
	 * Returns random element from the set.
	 */
	@Nonnull
	private static <T> T pickRandom(@Nonnull Random random, @Nonnull Collection<T> theSet) {
		Assert.isTrue(!theSet.isEmpty(), "There are no values to choose from!");
		// the index must span [0, size) - drawing from [1, size) walked past the first candidate, so whichever
		// value the collection happens to iterate first could never be selected
		final int index = random.nextInt(theSet.size());
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
	 * One row of the `CATEGORY` reference of a product in the test's model - and, reflected, one row of the
	 * {@link #REFERENCE_PRODUCTS} reference of the category carrying the same inherited attributes.
	 *
	 * @param categoryPrimaryKey the referenced category
	 * @param group              the value of {@link #ATTRIBUTE_CATEGORY_GROUP}
	 * @param priority           the value of {@link DataGenerator#ATTRIBUTE_CATEGORY_PRIORITY}
	 */
	private record CategoryRow(
		int categoryPrimaryKey,
		@Nonnull String group,
		long priority
	) implements Comparable<CategoryRow> {
		/**
		 * The natural order - by category, group and priority.
		 */
		private static final Comparator<CategoryRow> NATURAL_ORDER = Comparator
			.comparingInt(CategoryRow::categoryPrimaryKey)
			.thenComparing(CategoryRow::group)
			.thenComparingLong(CategoryRow::priority);

		@Override
		public int compareTo(@Nonnull CategoryRow other) {
			return NATURAL_ORDER.compare(this, other);
		}
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
