/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.driver;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.TestEntity;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.State;
import io.evitadb.dataType.ContainerType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Predecessor;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link EvitaClient}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@ExtendWith(EvitaParameterResolver.class)
class EvitaClientReadWriteTest implements TestConstants, EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	public static final String ATTRIBUTE_UUID = "uuid";
	private final static int SEED = 42;
	private static final String EVITA_CLIENT_DATA_SET = "EvitaReadWriteClientDataSet";
	private static final Map<Serializable, Integer> GENERATED_ENTITIES = new HashMap<>(2000);
	private static final BiFunction<String, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, faker) -> {
		final int entityCount = GENERATED_ENTITIES.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	private static final int PRODUCT_COUNT = 10;
	private static DataGenerator DATA_GENERATOR;

	@DataSet(value = EVITA_CLIENT_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		DATA_GENERATOR = new DataGenerator();
		GENERATED_ENTITIES.clear();

		final ApiOptions apiOptions = evitaServer.getExternalApiServer()
			.getApiOptions();
		final HostDefinition grpcHost = apiOptions
			.getEndpointConfiguration(GrpcProvider.CODE)
			.getHost()[0];
		final HostDefinition systemHost = apiOptions
			.getEndpointConfiguration(SystemProvider.CODE)
			.getHost()[0];

		final String serverCertificates = evitaServer.getExternalApiServer().getApiOptions().certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		final EvitaClientConfiguration evitaClientConfiguration = EvitaClientConfiguration.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			.mtlsEnabled(false)
			.certificateFolderPath(clientCertificates)
			.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
			.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
			.build();

		final AtomicReference<EntitySchemaContract> productSchema = new AtomicReference<>();
		AtomicReference<Map<Integer, SealedEntity>> products = new AtomicReference<>();
		try (final EvitaClient setupClient = new EvitaClient(evitaClientConfiguration)) {
			setupClient.defineCatalog(TEST_CATALOG);
			// create bunch or entities for referencing in products
			setupClient.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally())
						.updateVia(session);

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSampleBrandSchema(
								session,
								builder -> {
									builder.withAttribute(ATTRIBUTE_UUID, UUID.class);
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(5)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSampleCategorySchema(
								session,
								builder -> {
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(10)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					DATA_GENERATOR.registerValueGenerator(
						Entities.PRICE_LIST, ATTRIBUTE_ORDER,
						faker -> Predecessor.HEAD
					);

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSamplePriceListSchema(
								session,
								builder -> {
									builder.withAttribute(
										ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable
									);
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(4)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSampleStoreSchema(
								session,
								builder -> {
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(12)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSampleParameterGroupSchema(
								session,
								builder -> {
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(20)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					DATA_GENERATOR.generateEntities(
							DATA_GENERATOR.getSampleParameterSchema(
								session,
								builder -> {
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(20)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					productSchema.set(
						DATA_GENERATOR.getSampleProductSchema(
							session,
							builder -> {
								builder
									.withGlobalAttribute(ATTRIBUTE_CODE)
									.withReferenceToEntity(
										Entities.PARAMETER,
										Entities.PARAMETER,
										Cardinality.ZERO_OR_MORE,
										thatIs -> thatIs.faceted().withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
									);
								session.updateEntitySchema(builder);
								return builder.toInstance();
							}
						)
					);

					final Map<Integer, SealedEntity> theProducts = CollectionUtils.createHashMap(10);
					DATA_GENERATOR.generateEntities(
							productSchema.get(),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(PRODUCT_COUNT)
						.forEach(it -> {
							final EntityReference upsertedProduct = session.upsertEntity(it);
							theProducts.put(
								upsertedProduct.getPrimaryKey(),
								session.getEntity(
									productSchema.get().getName(),
									upsertedProduct.getPrimaryKey(),
									entityFetchAllContent()
								).orElseThrow()
							);
						});
					products.set(theProducts);
				}
			);
		}

		return new DataCarrier(
			"evitaClient", new EvitaClient(evitaClientConfiguration),
			"products", products.get(),
			"productSchema", productSchema.get()
		);
	}

	private static void assertCategoryParent(
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull CategoryInterface category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.getId());
		if (originalCategory.getParentEntity().isEmpty()) {
			assertNull(category.getParentId());
			assertNull(category.getParentEntityReference());
			assertNull(category.getParentEntity());
		} else {
			final int expectedParentId = originalCategory.getParentEntity().get().getPrimaryKey();
			assertEquals(
				expectedParentId,
				category.getParentId()
			);
			assertEquals(
				new EntityReference(Entities.CATEGORY, expectedParentId),
				category.getParentEntityReference()
			);
			assertCategory(category.getParentEntity(), originalCategories.get(expectedParentId), locale);
			assertCategoryParent(originalCategories, category.getParentEntity(), locale);
		}
	}

	private static void assertCategoryParents(
		@Nonnull Collection<CategoryInterface> categories,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		for (CategoryInterface category : categories) {
			assertCategoryParent(originalCategories, category, locale);
		}
	}

	private static void assertCategory(
		@Nonnull CategoryInterface category,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale
	) {
		assertEquals(TestEntity.CATEGORY, category.getEntityType());
		assertEquals(sealedEntity.getPrimaryKey(), category.getId());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_CODE), category.getCode());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_PRIORITY), category.getPriority());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_VALIDITY), category.getValidity());
		if (locale == null) {
			for (AttributeValue attributeValue : sealedEntity.getAttributeValues(DataGenerator.ATTRIBUTE_NAME)) {
				assertEquals(attributeValue.value(), category.getName(attributeValue.key().locale()));
			}
		} else {
			assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.getName());
			assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.getName(locale));
		}
	}

	private static void assertCategoryIds(
		@Nonnull Stream<Integer> categoryIds,
		@Nonnull int[] expectedCategoryIds
	) {
		assertNotNull(categoryIds);
		final Integer[] references = categoryIds
			.sorted()
			.toArray(Integer[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		assertArrayEquals(
			Arrays.stream(expectedCategoryIds)
				.boxed()
				.toArray(Integer[]::new),
			references
		);
	}

	private static void assertProduct(
		@Nonnull SealedEntity originalProduct,
		@Nullable ProductInterface product,
		@Nonnull Map<Integer, SealedEntity> originalCategories

	) {
		assertProduct(
			originalProduct, product, originalCategories, null, null, null
		);
	}

	private static void assertProduct(
		@Nonnull SealedEntity originalProduct,
		@Nullable ProductInterface product,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Currency currency,
		@Nullable String[] priceLists,
		@Nullable Locale locale
	) {
		assertProductBasicData(originalProduct, product);
		assertProductAttributes(originalProduct, product, locale);

		final ReferencedFileSet expectedAssociatedData = originalProduct.getAssociatedData(DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, ReflectionLookup.NO_CACHE_INSTANCE);
		if (expectedAssociatedData == null) {
			assertNull(product.getReferencedFileSet());
			assertNull(product.getReferencedFileSetAsDifferentProperty());
		} else {
			assertEquals(expectedAssociatedData, product.getReferencedFileSet());
			assertEquals(expectedAssociatedData, product.getReferencedFileSetAsDifferentProperty());
		}

		assertCategoryParents(product.getCategories(), originalCategories, locale);

		final int[] expectedCategoryIds = originalProduct.getReferences(Entities.CATEGORY)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.toArray();

		assertCategoryIds(product.getCategoryIds().stream(), expectedCategoryIds);
		assertCategoryIds(product.getCategoryIdsAsList().stream(), expectedCategoryIds);
		assertCategoryIds(product.getCategoryIdsAsSet().stream(), expectedCategoryIds);
		assertCategoryIds(Arrays.stream(product.getCategoryIdsAsArray()).boxed(), expectedCategoryIds);

		if (currency == null && priceLists == null) {
			assertThrows(ContextMissingException.class, product::getPriceForSale);
			assertThrows(ContextMissingException.class, product::getAllPricesForSale);
		} else {
			final PriceContract[] allPricesForSale = product.getAllPricesForSale();
			final List<PriceContract> originalPricesForSale = originalProduct.getAllPricesForSale(currency, null, priceLists);
			final PriceContract[] expectedAllPricesForSale = originalPricesForSale.toArray(PriceContract[]::new);
			assertEquals(
				Arrays.stream(expectedAllPricesForSale)
					.filter(it -> Objects.equals(currency, it.currency()))
					.filter(it -> Arrays.stream(priceLists)
						.anyMatch(priceList -> Objects.equals(priceList, it.priceList())))
					.min((o1, o2) -> {
						final int ix1 = ArrayUtils.indexOf(o1.priceList(), priceLists);
						final int ix2 = ArrayUtils.indexOf(o2.priceList(), priceLists);
						return Integer.compare(ix1, ix2);
					})
					.orElse(null),
				product.getPriceForSale()
			);

			assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
			assertArrayEquals(expectedAllPricesForSale, allPricesForSale);

			if (expectedAllPricesForSale.length > 0) {
				final PriceContract expectedPrice = expectedAllPricesForSale[0];
				assertEquals(
					expectedPrice,
					product.getPriceForSale(expectedPrice.priceList(), expectedPrice.currency())
				);

				assertArrayEquals(
					originalPricesForSale
						.stream()
						.filter(it -> it.priceList().equals(expectedPrice.priceList()))
						.toArray(PriceContract[]::new),
					product.getAllPricesForSale(expectedPrice.priceList())
				);

				assertArrayEquals(
					originalPricesForSale
						.stream()
						.filter(it -> it.currency().equals(expectedPrice.currency()))
						.toArray(PriceContract[]::new),
					product.getAllPricesForSale(expectedPrice.currency())
				);

				assertArrayEquals(
					originalPricesForSale
						.stream()
						.filter(it -> it.currency().equals(expectedPrice.currency()) && it.priceList().equals(expectedPrice.priceList()))
						.toArray(PriceContract[]::new),
					product.getAllPricesForSale(expectedPrice.priceList(), expectedPrice.currency())
				);
			}
		}

		final PriceContract[] expectedAllPrices = originalProduct.getPrices().toArray(PriceContract[]::new);
		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			.toArray(PriceContract[]::new);

		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);

		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		final Optional<PriceContract> first = Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.priceList())).findFirst();
		if (first.isEmpty()) {
			assertNull(product.getBasicPrice());
		} else {
			assertEquals(
				first.get(),
				product.getBasicPrice()
			);
		}
	}

	private static void assertProductBasicData(@Nonnull SealedEntity originalProduct, @Nullable ProductInterface product) {
		assertNotNull(product);
		assertEquals(originalProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(originalProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
	}

	private static void assertProductAttributes(@Nonnull SealedEntity originalProduct, @Nonnull ProductInterface product, @Nullable Locale locale) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), product.getName());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
	}

	/**
	 * Creates new entity and inserts it into the index.
	 */
	private static void createEntity(@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities, @Nonnull EntityBuilder it) {
		final EntityReferenceContract<?> insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

	@Nonnull
	private static EntityReference createSomeNewCategory(@Nonnull EvitaSessionContract session,
	                                                     int primaryKey,
	                                                     @Nullable Integer parentPrimaryKey) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "New category #" + primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "category-" + primaryKey)
			.setAttribute(ATTRIBUTE_PRIORITY, (long) primaryKey);

		if (parentPrimaryKey == null) {
			builder.removeParent();
		} else {
			builder.setParent(parentPrimaryKey);
		}

		return builder.upsertVia(session);
	}

	@Nonnull
	private static EntityMutation createSomeNewProduct(@Nonnull EvitaSessionContract session) {
		return session.createNewEntity(Entities.PRODUCT)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "New product")
			.setAttribute(ATTRIBUTE_CODE, "product-" + (session.getEntityCollectionSize(Entities.PRODUCT) + 1))
			.setAttribute(ATTRIBUTE_PRIORITY, session.getEntityCollectionSize(Entities.PRODUCT) + 1L)
			.toMutation()
			.orElseThrow();
	}

	private static void assertSomeNewProductContent(@Nonnull SealedEntity loadedEntity) {
		assertNotNull(loadedEntity.getPrimaryKey());
		assertEquals("New product", loadedEntity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldAllowCreatingCatalogAlongWithTheSchema(EvitaClient evitaClient) {
		final String someCatalogName = "differentCatalog";
		try {
			evitaClient.defineCatalog(someCatalogName)
				.withDescription("Some description.")
				.updateVia(evitaClient.createReadWriteSession(someCatalogName));
			assertTrue(evitaClient.getCatalogNames().contains(someCatalogName));
		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldAllowCreatingCatalogAndEntityCollectionsInPrototypingMode(EvitaClient evitaClient) {
		final String someCatalogName = "differentCatalog";
		try {
			evitaClient.defineCatalog(someCatalogName)
				.withDescription("This is a tutorial catalog.")
				.updateViaNewSession(evitaClient);

			assertTrue(evitaClient.getCatalogNames().contains(someCatalogName));
			evitaClient.updateCatalog(
				someCatalogName,
				session -> {
					session.createNewEntity("Brand", 1)
						.setAttribute("name", Locale.ENGLISH, "Lenovo")
						.upsertVia(session);

					final Optional<SealedEntitySchema> brand = session.getEntitySchema("Brand");
					assertTrue(brand.isPresent());

					final Optional<EntityAttributeSchemaContract> nameAttribute = brand.get().getAttribute("name");
					assertTrue(nameAttribute.isPresent());
					assertTrue(nameAttribute.get().isLocalized());

					// now create an example category tree
					session.createNewEntity("Category", 10)
						.setAttribute("name", Locale.ENGLISH, "Electronics")
						.upsertVia(session);

					session.createNewEntity("Category", 11)
						.setAttribute("name", Locale.ENGLISH, "Laptops")
						// laptops will be a child category of electronics
						.setParent(10)
						.upsertVia(session);

					// finally, create a product
					session.createNewEntity("Product")
						// with a few attributes
						.setAttribute("name", Locale.ENGLISH, "ThinkPad P15 Gen 1")
						.setAttribute("cores", 8)
						.setAttribute("graphics", "NVIDIA Quadro RTX 4000 with Max-Q Design")
						// and price for sale
						.setPrice(
							1, "basic",
							Currency.getInstance("USD"),
							new BigDecimal("1420"), new BigDecimal("20"), new BigDecimal("1704"),
							true
						)
						// link it to the manufacturer
						.setReference(
							"brand", "Brand",
							Cardinality.EXACTLY_ONE,
							1
						)
						// and to the laptop category
						.setReference(
							"categories", "Category",
							Cardinality.ZERO_OR_MORE,
							11
						)
						.upsertVia(session);

					final Optional<SealedEntitySchema> product = session.getEntitySchema("Product");
					assertTrue(product.isPresent());

					final Optional<EntityAttributeSchemaContract> productNameAttribute = product.get().getAttribute("name");
					assertTrue(productNameAttribute.isPresent());
					assertTrue(productNameAttribute.get().isLocalized());
				}
			);

		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldAllowCreatingCatalogAndEntityCollectionsSchemas(EvitaClient evitaClient) {
		final String someCatalogName = "differentCatalog";
		try {
			evitaClient.defineCatalog(someCatalogName)
				.withDescription("This is a tutorial catalog.")
				// define brand schema
				.withEntitySchema(
					"Brand",
					whichIs -> whichIs.withDescription("A manufacturer of products.")
						.withAttribute(
							"name", String.class,
							thatIs -> thatIs.localized().filterable().sortable()
						)
				)
				// define category schema
				.withEntitySchema(
					"Category",
					whichIs -> whichIs.withDescription("A category of products.")
						.withAttribute(
							"name", String.class,
							thatIs -> thatIs.localized().filterable().sortable()
						)
						.withHierarchy()
				)
				// define product schema
				.withEntitySchema(
					"Product",
					whichIs -> whichIs.withDescription("A product in inventory.")
						.withAttribute(
							"name", String.class,
							thatIs -> thatIs.localized().filterable().sortable()
						)
						.withAttribute(
							"cores", Integer.class,
							thatIs -> thatIs.withDescription("Number of CPU cores.")
								.filterable()
						)
						.withAttribute(
							"graphics", String.class,
							thatIs -> thatIs.withDescription("Graphics card.")
								.filterable()
						)
						.withPrice()
						.withReferenceToEntity(
							"brand", "Brand", Cardinality.EXACTLY_ONE,
							thatIs -> thatIs.indexed()
						)
						.withReferenceToEntity(
							"categories", "Category", Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.indexed()
						)
				)
				// and now push all the definitions (mutations) to the server
				.updateViaNewSession(evitaClient);

			assertTrue(evitaClient.getCatalogNames().contains(someCatalogName));
			evitaClient.queryCatalog(someCatalogName, session -> {
				final Set<String> allEntityTypes = session.getAllEntityTypes();
				assertTrue(allEntityTypes.contains("Brand"));
				assertTrue(allEntityTypes.contains("Category"));
				assertTrue(allEntityTypes.contains("Product"));
			});
		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldBeAbleToRunParallelClients(EvitaClient evitaClient) {
		final EvitaClient anotherParallelClient = new EvitaClient(evitaClient.getConfiguration());
		shouldListCatalogNames(anotherParallelClient);
		shouldListCatalogNames(evitaClient);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldAbleToFetchNonCachedEntitySchemaFromCatalogSchema(EvitaClient evitaClient) {
		final EvitaClient clientWithEmptyCache = new EvitaClient(evitaClient.getConfiguration());
		clientWithEmptyCache.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<EntitySchemaContract> productSchema = session.getCatalogSchema().getEntitySchema(Entities.PRODUCT);
				assertNotNull(productSchema);
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldListCatalogNames(EvitaClient evitaClient) {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCreateCatalog(EvitaClient evitaClient) {
		final String newCatalogName = "newCatalog";
		try {
			final CatalogSchemaContract newCatalog = evitaClient.defineCatalog(newCatalogName);
			final Set<String> catalogNames = evitaClient.getCatalogNames();

			assertEquals(2, catalogNames.size());
			assertTrue(catalogNames.contains(TEST_CATALOG));
			assertTrue(catalogNames.contains(newCatalogName));
		} finally {
			evitaClient.deleteCatalogIfExists(newCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRemoveCatalog(EvitaClient evitaClient) {
		final String newCatalogName = "newCatalog";
		evitaClient.defineCatalog(newCatalogName).updateViaNewSession(evitaClient);
		final boolean removed = evitaClient.deleteCatalogIfExists(newCatalogName);
		assertTrue(removed);

		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldReplaceCatalog(EvitaClient evitaClient) {
		final String newCatalog = "newCatalog";
		evitaClient.defineCatalog(newCatalog);

		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(2, catalogNames.size());
		assertTrue(catalogNames.contains(newCatalog));
		assertTrue(catalogNames.contains(TEST_CATALOG));
		assertEquals(
			Integer.valueOf(3),
			evitaClient.queryCatalog(TEST_CATALOG, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().version();
			})
		);

		evitaClient.replaceCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(4),
			evitaClient.queryCatalog(newCatalog, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().version();
			})
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldRenameCatalog(EvitaClient evitaClient) {
		final String newCatalog = "newCatalog";

		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));
		assertEquals(
			Integer.valueOf(3),
			evitaClient.queryCatalog(TEST_CATALOG, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().version();
			})
		);

		evitaClient.renameCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(4),
			evitaClient.queryCatalog(newCatalog, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().version();
			})
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContents(EvitaClient evitaClient) throws ExecutionException, InterruptedException, TimeoutException {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));

		final EvitaManagementContract management = evitaClient.management();
		final CompletableFuture<FileForFetch> backupFileFuture = management.backupCatalog(TEST_CATALOG, null, true);
		final FileForFetch fileForFetch = backupFileFuture.get(3, TimeUnit.MINUTES);

		log.info("Catalog backed up to file: {}", fileForFetch.fileId());

		final String restoredCatalogName = TEST_CATALOG + "_restored";
		try (final InputStream inputStream = management.fetchFile(fileForFetch.fileId())) {
			// wait to restoration to be finished
			management.restoreCatalog(restoredCatalogName, fileForFetch.totalSizeInBytes(), inputStream)
				.getFutureResult()
				.get(3, TimeUnit.MINUTES);

		} catch (IOException e) {
			fail("Failed to restore catalog!", e);
		}

		log.info("Catalog restored from file: {}", fileForFetch.fileId());

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(2, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(TEST_CATALOG));
		assertTrue(catalogNamesAgain.contains(restoredCatalogName));

		assertEquals(
			Integer.valueOf(PRODUCT_COUNT),
			evitaClient.queryCatalog(restoredCatalogName, session -> {
				return session.getEntityCollectionSize(Entities.PRODUCT);
			})
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldBackupAndRestoreCatalogViaFileOnTheServerSide(EvitaClient evitaClient) throws ExecutionException, InterruptedException, TimeoutException {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));

		final EvitaManagementContract management = evitaClient.management();
		final CompletableFuture<FileForFetch> backupFileFuture = management.backupCatalog(TEST_CATALOG, null, true);
		final FileForFetch fileForFetch = backupFileFuture.get(3, TimeUnit.MINUTES);

		log.info("Catalog backed up to file: {}", fileForFetch.fileId());

		final String restoredCatalogName = TEST_CATALOG + "_restored";
		final Task<?, Void> restoreTask = management.restoreCatalog(restoredCatalogName, fileForFetch.fileId());

		// wait for the restore to finish
		restoreTask.getFutureResult().get(3, TimeUnit.MINUTES);

		log.info("Catalog restored from file: {}", fileForFetch.fileId());

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(2, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(TEST_CATALOG));
		assertTrue(catalogNamesAgain.contains(restoredCatalogName));

		assertEquals(
			Integer.valueOf(PRODUCT_COUNT),
			evitaClient.queryCatalog(restoredCatalogName, session -> {
				return session.getEntityCollectionSize(Entities.PRODUCT);
			})
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldReplaceCollection(EvitaClient evitaClient) {
		final String newCollection = "newCollection";
		final AtomicInteger productCount = new AtomicInteger();
		final AtomicInteger productSchemaVersion = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertFalse(session.getAllEntityTypes().contains(newCollection));
				session.defineEntitySchema(newCollection)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.updateVia(session);
				assertTrue(session.getAllEntityTypes().contains(newCollection));
				productSchemaVersion.set(session.getEntitySchemaOrThrow(Entities.PRODUCT).version());
				productCount.set(session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				return session.replaceCollection(
					newCollection,
					Entities.PRODUCT
				);
			}
		);
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertTrue(session.getAllEntityTypes().contains(newCollection));
				assertEquals(productSchemaVersion.get() + 1, session.getEntitySchemaOrThrow(newCollection).version());
				assertEquals(productCount.get(), session.getEntityCollectionSize(newCollection));
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldRenameCollection(EvitaClient evitaClient) {
		final String newCollection = "newCollection";
		final AtomicInteger productCount = new AtomicInteger();
		final AtomicInteger productSchemaVersion = new AtomicInteger();
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertFalse(session.getAllEntityTypes().contains(newCollection));
				productSchemaVersion.set(session.getEntitySchemaOrThrow(Entities.PRODUCT).version());
				productCount.set(session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				return session.renameCollection(
					Entities.PRODUCT,
					newCollection
				);
			}
		);
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertTrue(session.getAllEntityTypes().contains(newCollection));
				assertEquals(productSchemaVersion.get() + 1, session.getEntitySchemaOrThrow(newCollection).version());
				assertEquals(productCount.get(), session.getEntityCollectionSize(newCollection));
			}
		);
	}

	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProduct(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> upsertedEntity = DATA_GENERATOR.generateEntities(productSchema, RANDOM_ENTITY_PICKER, SEED)
					.limit(1)
					.map(it -> session.upsertAndFetchEntity(it, entityFetchAllContent()))
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(addedEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - asynchronously.")
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronously(EvitaContract evita, SealedEntitySchema productSchema) {
		final AtomicInteger addedEntityPrimaryKey = new AtomicInteger();
		final CompletableFuture<SealedEntity> addedEntity = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> upsertedEntity = DATA_GENERATOR.generateEntities(productSchema, RANDOM_ENTITY_PICKER, SEED)
					.limit(1)
					.map(it -> session.upsertAndFetchEntity(it, entityFetchAllContent()))
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				addedEntityPrimaryKey.set(upsertedEntity.get().getPrimaryKey());
				return upsertedEntity.get();
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		);

		final int expectedEntityPrimaryKey = addedEntityPrimaryKey.get();
		while (!addedEntity.isDone()) {
			Thread.onSpinWait();
		}

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> entityFetchedAgain = session.getEntity(productSchema.getName(), expectedEntityPrimaryKey, entityFetchAllContent());
				assertTrue(entityFetchedAgain.isPresent(), "Entity not found in catalog!");
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldCreateAndDropEntityCollection(EvitaClient evitaClient) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String newEntityType = "newEntityType";
				session.defineEntitySchema(newEntityType)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterable())
					.updateVia(session);

				assertTrue(session.getAllEntityTypes().contains(newEntityType));
				session.deleteCollection(newEntityType);
				assertFalse(session.getAllEntityTypes().contains(newEntityType));
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldUpsertNewEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);
				assertNotNull(entityMutation);

				final EntityReference newProduct = session.upsertEntity(entityMutation);
				newProductId.set(newProduct.getPrimaryKey());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity loadedEntity = session.getEntity(Entities.PRODUCT, newProductId.get(), entityFetchAllContent())
					.orElseThrow();

				assertSomeNewProductContent(loadedEntity);
			}
		);

		// reset data
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.deleteEntity(Entities.PRODUCT, newProductId.get());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldUpsertAndFetchNewEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				assertSomeNewProductContent(updatedEntity);
			}
		);


		// reset data
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.deleteEntity(Entities.PRODUCT, newProductId.get());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldDeleteExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());
				session.deleteEntity(Entities.PRODUCT, updatedEntity.getPrimaryKey());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDeleteAndFetchExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final SealedEntity removedEntity = session.deleteEntity(
					Entities.PRODUCT, updatedEntity.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertSomeNewProductContent(removedEntity);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldDeleteAndFetchExistingCustomEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> originalCategories) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final ProductInterface removedEntity = session.deleteEntity(
					ProductInterface.class, updatedEntity.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertProduct(updatedEntity, removedEntity, originalCategories);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<ProductInterface> loadedEntity = session.getEntity(
					ProductInterface.class, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDeleteEntityByQuery(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final int deletedEntities = session.deleteEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(newProductId.get()))
					)
				);

				assertEquals(1, deletedEntities);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDeleteEntitiesAndFetchByQuery(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final SealedEntity[] deletedEntities = session.deleteSealedEntitiesAndReturnBodies(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(newProductId.get())),
						require(entityFetchAll())
					)
				);

				assertEquals(1, deletedEntities.length);
				assertSomeNewProductContent(deletedEntities[0]);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldDeleteHierarchy(EvitaClient evitaClient) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				createSomeNewCategory(session, 50, null);
				createSomeNewCategory(session, 51, 50);
				createSomeNewCategory(session, 52, 51);
				createSomeNewCategory(session, 53, 50);

				final int deletedEntities = session.deleteEntityAndItsHierarchy(
					Entities.CATEGORY, 50
				);

				assertEquals(4, deletedEntities);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getEntity(Entities.CATEGORY, 50, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 51, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 52, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 53, entityFetchAllContent()).isPresent());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldDeleteHierarchyAndFetchRoot(EvitaClient evitaClient) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				createSomeNewCategory(session, 50, null);
				createSomeNewCategory(session, 51, 50);
				createSomeNewCategory(session, 52, 51);
				createSomeNewCategory(session, 53, 50);

				final DeletedHierarchy<SealedEntity> deletedHierarchy = session.deleteEntityAndItsHierarchy(
					Entities.CATEGORY, 50, entityFetchAllContent()
				);

				assertEquals(4, deletedHierarchy.deletedEntities());
				assertNotNull(deletedHierarchy.deletedRootEntity());
				assertEquals(50, deletedHierarchy.deletedRootEntity().getPrimaryKey());
				assertEquals("New category #50", deletedHierarchy.deletedRootEntity().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getEntity(Entities.CATEGORY, 50, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 51, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 52, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 53, entityFetchAllContent()).isPresent());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldDeleteHierarchyAndFetchCustomRoot(EvitaClient evitaClient) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				createSomeNewCategory(session, 50, null);
				createSomeNewCategory(session, 51, 50);
				createSomeNewCategory(session, 52, 51);
				createSomeNewCategory(session, 53, 50);

				final DeletedHierarchy<CategoryInterface> deletedHierarchy = session.deleteEntityAndItsHierarchy(
					CategoryInterface.class, 50, entityFetchAllContent()
				);

				assertEquals(4, deletedHierarchy.deletedEntities());
				assertNotNull(deletedHierarchy.deletedRootEntity());
				assertEquals(50, deletedHierarchy.deletedRootEntity().getPrimaryKey());
				assertEquals("New category #50", deletedHierarchy.deletedRootEntity().getName(Locale.ENGLISH));
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getEntity(Entities.CATEGORY, 50, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 51, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 52, entityFetchAllContent()).isPresent());
				assertFalse(session.getEntity(Entities.CATEGORY, 53, entityFetchAllContent()).isPresent());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldGetMutationsHistory(EvitaClient evitaClient) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				createSomeNewCategory(session, 50, null);
				createSomeNewCategory(session, 51, 50);
				createSomeNewCategory(session, 52, 51);
				createSomeNewCategory(session, 53, 50);

				final DeletedHierarchy<CategoryInterface> deletedHierarchy = session.deleteEntityAndItsHierarchy(
					CategoryInterface.class, 50, entityFetchAllContent()
				);
				assertNotNull(deletedHierarchy);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Stream<ChangeCatalogCapture> mutationsHistory = session.getMutationsHistory(
					ChangeCatalogCaptureRequest.builder()
						.criteria(
							ChangeCatalogCaptureCriteria.builder()
								.area(CaptureArea.DATA)
								.site(
									DataSite.builder()
										.containerType(ContainerType.ENTITY)
										.build()
								)
								.build()
						)
						.content(CaptureContent.BODY)
						.build()
				);

				final List<ChangeCatalogCapture> mutations = mutationsHistory.toList();
				assertTrue(mutations.size() > 10);
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldListAndCancelTasks(EvitaClient evitaClient) {
		final int numberOfTasks = 20;
		final ExecutorService executorService = Executors.newFixedThreadPool(numberOfTasks);
		final EvitaManagementContract management = evitaClient.management();

		// Step 2: Generate backup tasks using the custom executor
		final List<CompletableFuture<CompletableFuture<FileForFetch>>> backupTasks = Stream.generate(
				() -> CompletableFuture.supplyAsync(
					() -> management.backupCatalog(TEST_CATALOG, null, true),
					executorService
				)
			)
			.limit(numberOfTasks)
			.toList();

		// Optional: Wait for all tasks to complete
		CompletableFuture.allOf(backupTasks.toArray(new CompletableFuture[0])).join();
		executorService.shutdown();

		management.listTaskStatuses(1, numberOfTasks);

		// cancel 7 of them immediately
		final List<Boolean> cancellationResult = Stream.concat(
				management.listTaskStatuses(1, 1)
					.getData()
					.stream()
					.map(it -> management.cancelTask(it.taskId())),
				backupTasks.subList(3, numberOfTasks - 1)
					.stream()
					.map(task -> task.getNow(null).cancel(true))
			)
			.toList();

		// wait for all task to complete
		assertThrows(
			ExecutionException.class,
			() -> CompletableFuture.allOf(
				backupTasks.stream().map(it -> it.getNow(null)).toArray(CompletableFuture[]::new)
			).get(3, TimeUnit.MINUTES)
		);

		final PaginatedList<TaskStatus<?, ?>> taskStatuses = management.listTaskStatuses(1, numberOfTasks);
		assertEquals(numberOfTasks, taskStatuses.getTotalRecordCount());
		final int cancelled = cancellationResult.stream().mapToInt(b -> b ? 1 : 0).sum();
		assertEquals(backupTasks.size() - cancelled, taskStatuses.getData().stream().filter(task -> task.state() == State.FINISHED).count());
		assertEquals(cancelled, taskStatuses.getData().stream().filter(task -> task.state() == State.FAILED).count());

		// fetch all tasks by their ids
		management.getTaskStatuses(
			taskStatuses.getData().stream().map(TaskStatus::taskId).toArray(UUID[]::new)
		).forEach(Assertions::assertNotNull);

		// fetch tasks individually
		taskStatuses.getData().forEach(task -> assertNotNull(management.getTaskStatus(task.taskId())));

		// list exported files
		final PaginatedList<FileForFetch> exportedFiles = management.listFilesToFetch(1, numberOfTasks, null);
		// some task might have finished even if cancelled (if they were cancelled in terminal phase)
		assertTrue(exportedFiles.getTotalRecordCount() >= backupTasks.size() - cancelled);
		exportedFiles.getData().forEach(file -> assertTrue(file.totalSizeInBytes() > 0));

		// get all files by their ids
		exportedFiles.getData().forEach(file -> assertNotNull(management.getFileToFetch(file.fileId())));

		// fetch all of them
		exportedFiles.getData().forEach(
			file -> {
				try (final InputStream inputStream = management.fetchFile(file.fileId())) {
					final Path tempFile = Files.createTempFile(String.valueOf(file.fileId()), ".zip");
					Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
					assertTrue(tempFile.toFile().exists());
					assertEquals(file.totalSizeInBytes(), Files.size(tempFile));
					Files.delete(tempFile);
				} catch (IOException e) {
					fail(e);
				}
			});

		// delete them
		final Set<UUID> deletedFiles = CollectionUtils.createHashSet(exportedFiles.getData().size());
		exportedFiles.getData()
			.forEach(file -> {
				management.deleteFile(file.fileId());
				deletedFiles.add(file.fileId());
			});

		// list them again and there should be none of them
		final PaginatedList<FileForFetch> exportedFilesAfterDeletion = management.listFilesToFetch(1, numberOfTasks, null);
		assertTrue(exportedFilesAfterDeletion.getData().stream().noneMatch(file -> deletedFiles.contains(file.fileId())));
	}

}
