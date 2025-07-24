/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.CatalogState;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.proxy.mock.CategoryInterface;
import io.evitadb.api.proxy.mock.ProductInterface;
import io.evitadb.api.proxy.mock.TestEntity;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.exception.EvitaInvalidUsageException;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.Assertions.assertDiffers;
import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_REFERENCE;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link EvitaClient}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@ExtendWith(EvitaParameterResolver.class)
class EvitaClientReadOnlyTest implements TestConstants, EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	public static final String ATTRIBUTE_CATEGORY_ORDER = "orderInCategory";
	public static final String ATTRIBUTE_UUID = "uuid";
	public static final String REFERENCE_PRODUCTS_IN_CATEGORY = "productsInCategory";
	private final static int SEED = 42;
	private static final String EVITA_CLIENT_DATA_SET = "EvitaReadOnlyClientDataSet";
	private static final Map<Serializable, Integer> GENERATED_ENTITIES = new HashMap<>(2000);
	private static final BiFunction<String, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, faker) -> {
		final int entityCount = GENERATED_ENTITIES.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	private static final int PRODUCT_COUNT = 10;

	@DataSet(value = EVITA_CLIENT_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, destroyAfterClass = true)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		final DataGenerator dataGenerator = new DataGenerator.Builder()
			.registerValueGenerator(
				Entities.PRICE_LIST, ATTRIBUTE_ORDER,
				faker -> Predecessor.HEAD
			).registerValueGenerator(
				Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
				faker -> Predecessor.HEAD
			).build();
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
			.timeout(5, TimeUnit.MINUTES)
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

					dataGenerator.generateEntities(
							dataGenerator.getSampleBrandSchema(
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

					dataGenerator.generateEntities(
							dataGenerator.getSampleCategorySchema(
								session,
								builder -> {
									builder.withReflectedReferenceToEntity(
										REFERENCE_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, Entities.CATEGORY,
										whichIs -> whichIs
											.withAttributesInherited()
											.withCardinality(Cardinality.ZERO_OR_MORE)
									);
									session.updateEntitySchema(builder);
									return builder.toInstance();
								}
							),
							RANDOM_ENTITY_PICKER,
							SEED
						)
						.limit(10)
						.forEach(it -> createEntity(session, GENERATED_ENTITIES, it));

					dataGenerator.generateEntities(
							dataGenerator.getSamplePriceListSchema(
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

					dataGenerator.generateEntities(
							dataGenerator.getSampleStoreSchema(
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

					dataGenerator.generateEntities(
							dataGenerator.getSampleParameterGroupSchema(
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

					dataGenerator.generateEntities(
							dataGenerator.getSampleParameterSchema(
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
						dataGenerator.getSampleProductSchema(
							session,
							builder -> {
								builder
									.withGlobalAttribute(ATTRIBUTE_CODE)
									.withReferenceToEntity(
										Entities.PARAMETER,
										Entities.PARAMETER,
										Cardinality.ZERO_OR_MORE,
										thatIs -> thatIs.faceted().withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
									)
									.withReferenceToEntity(
										Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
										whichIs -> whichIs.indexedForFilteringAndPartitioning()
											.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class)
									);
								session.updateEntitySchema(builder);
								return builder.toInstance();
							}
						)
					);

					final Map<Integer, SealedEntity> theProducts = CollectionUtils.createHashMap(10);
					dataGenerator.generateEntities(
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
			assertThrows(ContextMissingException.class, product::getReferencePrice);
		} else {
			assertEquals(originalProduct.getPrice(PRICE_LIST_REFERENCE, currency), product.getReferencePriceIfPresent());

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

		Predicate<PriceContract> predicate = null;
		if (currency != null) {
			predicate = it -> currency.equals(it.currency());
		}
		if (priceLists != null && priceLists.length > 0) {
			final String[] finalPriceLists = priceLists;
			predicate = predicate == null ?
				it -> Arrays.stream(finalPriceLists).anyMatch(priceList -> priceList.equals(it.priceList())) :
				predicate.and(it -> Arrays.stream(finalPriceLists).anyMatch(priceList -> priceList.equals(it.priceList())));
		}

		final PriceContract[] expectedAllPrices = predicate == null ?
			originalProduct.getPrices().toArray(PriceContract[]::new) :
			originalProduct.getPrices().stream().filter(predicate).toArray(PriceContract[]::new);
		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			.toArray(PriceContract[]::new);

		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);

		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		if (currency != null) {
			final Optional<PriceContract> first = Arrays.stream(expectedAllPrices)
				.filter(it -> "basic".equals(it.priceList()) && currency.equals(it.currency()))
				.findFirst();
			if (first.isEmpty()) {
				assertNull(product.getBasicPrice());
			} else {
				assertEquals(
					first.get(),
					product.getBasicPrice()
				);
			}
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
	 * This method is called only in set-up method.
	 */
	private static void createEntity(@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities, @Nonnull EntityBuilder it) {
		final EntityReferenceContract<?> insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
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
	void shouldGetCatalogState(EvitaClient evitaClient) {
		assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(TEST_CATALOG).orElseThrow());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryCatalog(EvitaClient evitaClient) {
		final CatalogSchemaContract catalogSchema = evitaClient.queryCatalog(
			TEST_CATALOG,
			EvitaSessionContract::getCatalogSchema
		);

		assertNotNull(catalogSchema);
		assertEquals(TEST_CATALOG, catalogSchema.getName());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryCatalogAsynchronously(EvitaClient evitaClient) throws ExecutionException, InterruptedException {
		final CompletableFuture<CatalogSchemaContract> catalogSchema = evitaClient.queryCatalogAsync(
			TEST_CATALOG,
			EvitaSessionContract::getCatalogSchema
		);

		assertNotNull(catalogSchema);
		assertEquals(TEST_CATALOG, catalogSchema.get().getName());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryOneEntityReference(EvitaClient evitaClient) {
		final EntityReference entityReference = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						)
					)
				).orElseThrow();
			}
		);

		assertNotNull(entityReference);
		assertEquals(Entities.PRODUCT, entityReference.getType());
		assertEquals(1, entityReference.getPrimaryKey());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotQueryOneMissingEntity(EvitaClient evitaClient) {
		final Optional<EntityReference> entityReference = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(-100)
						)
					)
				);
			}
		);

		assertTrue(entityReference.isEmpty());
	}

	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@ParameterizedTest()
	@MethodSource("returnRandomSeed")
	void shouldQueryOneSealedEntity(long seed, EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final Random rnd = new Random(seed);
		final int primaryKey = new ArrayList<>(products.keySet()).get(rnd.nextInt(products.size()));
		final boolean referencesOnly = rnd.nextBoolean();
		final SealedEntity sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(primaryKey)
						),
						require(
							referencesOnly ?
								entityFetch(
									hierarchyContent(),
									attributeContentAll(),
									associatedDataContentAll(),
									priceContentAll(),
									referenceContentAllWithAttributes(),
									dataInLocalesAll()
								) :
								entityFetch(
									hierarchyContent(entityFetchAll()),
									attributeContentAll(),
									associatedDataContentAll(),
									priceContentAll(),
									referenceContentAllWithAttributes(entityFetchAll()),
									dataInLocalesAll()
								)
						)
					)
				).orElseThrow();
			}
		);

		assertNotNull(sealedEntity);
		assertEquals(Entities.PRODUCT, sealedEntity.getType());
		assertExactlyEquals(products.get(primaryKey), sealedEntity);
	}

	@DisplayName("Should return custom entity model instance")
	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void queryOneCustomEntity(
		EvitaClient evitaClient,
		Map<Integer, SealedEntity> products,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetchAll())
		);

		final SealedEntity theProduct = products.get(1);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertProduct(
					theProduct,
					session.queryOne(query, ProductInterface.class).orElse(null),
					originalCategories
				);
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Disabled("Not working (yet)")
	void shouldQueryOneSealedBinaryEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						),
						require(
							entityFetchAll()
						)
					)
				);
			},
			SessionFlags.BINARY
		).orElseThrow();

		assertEquals(Entities.PRODUCT, sealedEntity.getType());
		assertExactlyEquals(products.get(1), sealedEntity);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryListOfEntityReferences(EvitaClient evitaClient) {
		final Integer[] requestedIds = {1, 2, 5};
		final List<EntityReference> entityReferences = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(requestedIds)
						)
					)
				);
			}
		);

		assertNotNull(entityReferences);
		assertEquals(3, entityReferences.size());

		for (int i = 0; i < entityReferences.size(); i++) {
			final EntityReference entityReference = entityReferences.get(i);
			assertEquals(Entities.PRODUCT, entityReference.getType());
			assertEquals(requestedIds[i], entityReference.getPrimaryKey());
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryListOfSealedEntities(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final Integer[] requestedIds = {1, 2, 5};
		final List<SealedEntity> sealedEntities = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(requestedIds)
						),
						require(
							entityFetchAll()
						)
					)
				);
			}
		);

		assertNotNull(sealedEntities);
		assertEquals(3, sealedEntities.size());

		for (int i = 0; i < sealedEntities.size(); i++) {
			final SealedEntity sealedEntity = sealedEntities.get(i);
			assertEquals(Entities.PRODUCT, sealedEntity.getType());
			assertEquals(requestedIds[i], sealedEntity.getPrimaryKey());
			assertExactlyEquals(products.get(requestedIds[i]), sealedEntity);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryListOfCustomEntities(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final Integer[] requestedIds = {1, 2, 5};
		final List<ProductInterface> fetchedProducts = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryList(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(requestedIds)
						),
						require(
							entityFetchAll()
						)
					),
					ProductInterface.class
				);
			}
		);

		assertNotNull(fetchedProducts);
		assertEquals(3, fetchedProducts.size());

		for (int i = 0; i < fetchedProducts.size(); i++) {
			final ProductInterface product = fetchedProducts.get(i);
			assertEquals(Entities.PRODUCT, product.getType());
			assertEquals(requestedIds[i], product.getPrimaryKey());
			assertProduct(products.get(requestedIds[i]), product, originalCategories);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldFetchReferencedEntityPredecessors(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] categoryPks = products.values()
					.stream()
					.flatMapToInt(
						it -> it.getReferences(Entities.CATEGORY)
							.stream()
							.filter(ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_ORDER) != null)
							.mapToInt(ReferenceContract::getReferencedPrimaryKey))
					.distinct()
					.limit(5)
					.toArray();

				final List<SealedEntity> categories = session.queryList(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(categoryPks)
						),
						require(
							entityFetchAll()
						)
					),
					SealedEntity.class
				);

				for (SealedEntity category : categories) {
					final Collection<ReferenceContract> references = category.getReferences(REFERENCE_PRODUCTS_IN_CATEGORY);
					assertNotNull(references);
					assertFalse(references.isEmpty());
					boolean found = false;
					for (ReferenceContract reference : references) {
						if (reference.getAttribute(ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class) != null) {
							found = true;
							break;
						}
					}
					assertTrue(found, "No reference with attribute " + ATTRIBUTE_CATEGORY_ORDER + " found!");
				}
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldGetListWithExtraResults(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity someProductWithCategory = products.values()
			.stream()
			.filter(it -> !it.getReferences(Entities.CATEGORY).isEmpty())
			.filter(it -> it.getAttributeValue(ATTRIBUTE_QUANTITY).isPresent())
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> result = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(someProductWithCategory.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::priceList).toArray(String[]::new)),
							priceInCurrency(someProductWithCategory.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::currency).findFirst().orElseThrow()),
							entityLocaleEquals(someProductWithCategory.getAllLocales().stream().findFirst().orElseThrow())
						),
						require(
							entityFetchAll(),
							queryTelemetry(),
							priceHistogram(20),
							attributeHistogram(20, ATTRIBUTE_QUANTITY),
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot("megaMenu", entityFetchAll())
							),
							facetSummary(FacetStatisticsDepth.IMPACT)
						)
					)
				);
			}
		);

		assertNotNull(result);
		assertTrue(result.getTotalRecordCount() > 0);

		assertNotNull(result.getExtraResult(QueryTelemetry.class));

		final PriceHistogram priceHistogram = result.getExtraResult(PriceHistogram.class);
		assertNotNull(priceHistogram);
		assertTrue(priceHistogram.getMax().compareTo(priceHistogram.getMin()) >= 0);
		assertTrue(priceHistogram.getMin().compareTo(BigDecimal.ZERO) > 0);
		assertTrue(priceHistogram.getBuckets().length > 0);

		final AttributeHistogram attributeHistogram = result.getExtraResult(AttributeHistogram.class);
		assertNotNull(attributeHistogram);
		final HistogramContract theHistogram = attributeHistogram.getHistogram(ATTRIBUTE_QUANTITY);
		assertNotNull(attributeHistogram);
		assertTrue(theHistogram.getMax().compareTo(theHistogram.getMin()) >= 0);
		assertTrue(theHistogram.getMin().compareTo(BigDecimal.ZERO) > 0);
		assertTrue(theHistogram.getBuckets().length > 0);

		final Hierarchy hierarchy = result.getExtraResult(Hierarchy.class);
		assertNotNull(hierarchy);
		final Map<String, List<LevelInfo>> categoryHierarchy = hierarchy.getReferenceHierarchy(Entities.CATEGORY);
		assertNotNull(categoryHierarchy);
		assertFalse(categoryHierarchy.get("megaMenu").isEmpty());

		final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
		assertNotNull(facetSummary);
		assertFalse(facetSummary.getReferenceStatistics().isEmpty());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldGetListOfCustomEntitiesWithExtraResults(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final SealedEntity someProductWithCategory = products.values()
			.stream()
			.filter(it -> it.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::currency).findFirst().isPresent())
			.filter(it -> it.getPrice(PRICE_LIST_REFERENCE, it.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::currency).findFirst().orElseThrow()).isPresent())
			.filter(it -> !it.getReferences(Entities.CATEGORY).isEmpty())
			.filter(it -> it.getAttributeValue(ATTRIBUTE_QUANTITY).isPresent())
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.findFirst()
			.orElseThrow();

		final String[] priceLists = someProductWithCategory.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::priceList).distinct().toArray(String[]::new);
		final Currency currency = someProductWithCategory.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::currency).findFirst().orElseThrow();
		final Locale locale = someProductWithCategory.getAllLocales().stream().findFirst().orElseThrow();

		final EvitaResponse<ProductInterface> result = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(priceLists),
							priceInCurrency(currency),
							entityLocaleEquals(locale)
						),
						require(
							defaultAccompanyingPriceLists(PRICE_LIST_REFERENCE),
							entityFetch(
								attributeContentAll(),
								hierarchyContent(),
								associatedDataContentAll(),
								priceContentRespectingFilter(),
								referenceContentAllWithAttributes(),
								accompanyingPriceContentDefault()
							),
							queryTelemetry(),
							priceHistogram(20),
							attributeHistogram(20, ATTRIBUTE_QUANTITY),
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot("megaMenu", entityFetchAll())
							),
							facetSummary(FacetStatisticsDepth.IMPACT)
						)
					),
					ProductInterface.class
				);
			}
		);

		final List<ProductInterface> resultData = result.getRecordData();
		for (final ProductInterface product : resultData) {
			assertEquals(Entities.PRODUCT, product.getType());
			assertProduct(
				products.get(product.getId()),
				product,
				originalCategories,
				currency,
				Stream.concat(Arrays.stream(priceLists), Stream.of(PRICE_LIST_REFERENCE)).toArray(String[]::new),
				locale
			);
		}

		assertNotNull(result);
		assertTrue(result.getTotalRecordCount() > 0);

		assertNotNull(result.getExtraResult(QueryTelemetry.class));

		final PriceHistogram priceHistogram = result.getExtraResult(PriceHistogram.class);
		assertNotNull(priceHistogram);
		assertTrue(priceHistogram.getMax().compareTo(priceHistogram.getMin()) >= 0);
		assertTrue(priceHistogram.getMin().compareTo(BigDecimal.ZERO) > 0);
		assertTrue(priceHistogram.getBuckets().length > 0);

		final AttributeHistogram attributeHistogram = result.getExtraResult(AttributeHistogram.class);
		assertNotNull(attributeHistogram);
		final HistogramContract theHistogram = attributeHistogram.getHistogram(ATTRIBUTE_QUANTITY);
		assertNotNull(attributeHistogram);
		assertTrue(theHistogram.getMax().compareTo(theHistogram.getMin()) >= 0);
		assertTrue(theHistogram.getMin().compareTo(BigDecimal.ZERO) > 0);
		assertTrue(theHistogram.getBuckets().length > 0);

		/* TOBEDONE #43 - provide access to entities of specific interface type */
		final Hierarchy hierarchy = result.getExtraResult(Hierarchy.class);
		assertNotNull(hierarchy);
		final Map<String, List<LevelInfo>> categoryHierarchy = hierarchy.getReferenceHierarchy(Entities.CATEGORY);
		assertNotNull(categoryHierarchy);
		assertFalse(categoryHierarchy.get("megaMenu").isEmpty());

		final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
		assertNotNull(facetSummary);
		assertFalse(facetSummary.getReferenceStatistics().isEmpty());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldGetSingleEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final Optional<SealedEntity> sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					Entities.PRODUCT,
					7,
					entityFetchAll().getRequirements()
				);
			}
		);

		assertTrue(sealedEntity.isPresent());

		sealedEntity.ifPresent(it -> {
			assertEquals(Entities.PRODUCT, it.getType());
			assertEquals(7, it.getPrimaryKey());
			assertEquals(products.get(7), it);
		});
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldGetSingleCustomEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final Optional<ProductInterface> product = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					ProductInterface.class,
					7,
					entityFetchAll().getRequirements()
				);
			}
		);

		assertTrue(product.isPresent());

		product.ifPresent(it -> {
			assertEquals(Entities.PRODUCT, it.getType());
			assertEquals(7, it.getPrimaryKey());
			assertProduct(products.get(7), it, originalCategories);
		});
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldEnrichSingleEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					Entities.PRODUCT,
					7,
					attributeContent()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, sealedEntity.getType());
		assertEquals(7, sealedEntity.getPrimaryKey());
		assertDiffers(products.get(7), sealedEntity);

		final SealedEntity enrichedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					Entities.PRODUCT,
					7,
					entityFetchAll().getRequirements()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, enrichedEntity.getType());
		assertEquals(7, enrichedEntity.getPrimaryKey());
		assertExactlyEquals(products.get(7), enrichedEntity);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldEnrichSingleCustomEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final ProductInterface product = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					ProductInterface.class,
					7,
					attributeContent()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(7, product.getPrimaryKey());
		assertProductBasicData(products.get(7), product);
		assertProductAttributes(products.get(7), product, null);
		assertThrows(ContextMissingException.class, product::getReferencedFileSet);
		assertThrows(ContextMissingException.class, product::getReferencedFileSetAsDifferentProperty);

		final ProductInterface enrichedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					ProductInterface.class,
					7,
					entityFetchAll().getRequirements()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, enrichedEntity.getType());
		assertEquals(7, enrichedEntity.getPrimaryKey());
		assertProduct(products.get(7), enrichedEntity, originalCategories);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldLimitSingleEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					Entities.PRODUCT,
					7,
					entityFetchAll().getRequirements()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, sealedEntity.getType());
		assertEquals(7, sealedEntity.getPrimaryKey());
		assertExactlyEquals(products.get(7), sealedEntity);

		final SealedEntity limitedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					Entities.PRODUCT,
					7,
					attributeContent()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, limitedEntity.getType());
		assertEquals(7, limitedEntity.getPrimaryKey());
		assertDiffers(products.get(7), limitedEntity);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldLimitSingleCustomEntity(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final ProductInterface sealedEntity = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					ProductInterface.class,
					7,
					entityFetchAll().getRequirements()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, sealedEntity.getType());
		assertEquals(7, sealedEntity.getPrimaryKey());
		assertProduct(products.get(7), sealedEntity, originalCategories);

		final ProductInterface limitedProduct = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(
					ProductInterface.class,
					7,
					attributeContent()
				);
			}
		).orElseThrow();

		assertEquals(Entities.PRODUCT, limitedProduct.getType());
		assertEquals(7, limitedProduct.getPrimaryKey());
		assertProductBasicData(products.get(7), limitedProduct);
		assertProductAttributes(products.get(7), limitedProduct, null);
		assertThrows(ContextMissingException.class, limitedProduct::getReferencedFileSet);
		assertThrows(ContextMissingException.class, limitedProduct::getReferencedFileSetAsDifferentProperty);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRetrieveSystemStatus(EvitaClient evitaClient) {
		final SystemStatus systemStatus = evitaClient.management().getSystemStatus();
		assertNotNull(systemStatus);
		assertEquals(1, systemStatus.catalogsOk());
		assertEquals(0, systemStatus.catalogsCorrupted());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRetrieveCatalogStatistics(EvitaClient evitaClient) {
		final CatalogStatistics[] catalogStatistics = evitaClient.management().getCatalogStatistics();

		assertEquals(1, catalogStatistics.length);
		final CatalogStatistics statistics = catalogStatistics[0];

		assertEquals(TEST_CATALOG, statistics.catalogName());
		assertFalse(statistics.corrupted());
		assertEquals(CatalogState.ALIVE, statistics.catalogState());
		assertEquals(1, statistics.catalogVersion());
		assertTrue(statistics.totalRecords() > 1);
		assertTrue(statistics.indexCount() > 1);
		assertTrue(statistics.sizeOnDiskInBytes() > 1);
		assertEquals(7, statistics.entityCollectionStatistics().length);

		for (EntityCollectionStatistics entityCollectionStatistics : statistics.entityCollectionStatistics()) {
			assertNotNull(entityCollectionStatistics.entityType());
			assertTrue(entityCollectionStatistics.totalRecords() > 0);
			assertTrue(entityCollectionStatistics.indexCount() > 0);
			assertTrue(entityCollectionStatistics.sizeOnDiskInBytes() > 0);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRetrieveCatalogVersionAtTheMoment(EvitaClient evitaClient) {
		final long lastCatalogVersion = Arrays.stream(evitaClient.management().getCatalogStatistics())
			.filter(it -> TEST_CATALOG.equals(it.catalogName()))
			.map(CatalogStatistics::catalogVersion)
			.findFirst()
			.orElseThrow();
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final CatalogVersion catalogVersionAt = session.getCatalogVersionAt(OffsetDateTime.now());
				assertEquals(lastCatalogVersion, catalogVersionAt.version());
				assertNotNull(catalogVersionAt.introducedAt());

				final CatalogVersion firstCatalogVersionAt = session.getCatalogVersionAt(null);
				assertEquals(0, firstCatalogVersionAt.version());
				assertNotNull(firstCatalogVersionAt.introducedAt());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRetrieveCollectionSize(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final Integer productCount = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntityCollectionSize(Entities.PRODUCT);
			}
		);

		assertEquals(products.size(), productCount);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldQueryListOfSealedEntitiesEvenWithoutProperRequirements(EvitaClient evitaClient) {
		final List<SealedEntity> sealedEntities = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1, 2, 5)
						)
					)
				);
			}
		);
		assertEquals(3, sealedEntities.size());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCallTerminationCallbackWhenClientClosesSession(EvitaClient evitaClient) {
		final AtomicReference<UUID> terminatedSessionId = new AtomicReference<>();
		final EvitaSessionContract theSession = evitaClient.createSession(
			new SessionTraits(
				TEST_CATALOG,
				session -> terminatedSessionId.set(session.getId())
			)
		);
		theSession.close();
		assertNotNull(terminatedSessionId.get());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCallTerminationCallbackWhenClientIsClosed(EvitaClient evitaClient) {
		final EvitaClient newEvitaClient = new EvitaClient(evitaClient.getConfiguration());
		final AtomicReference<UUID> terminatedSessionId = new AtomicReference<>();
		newEvitaClient.createSession(
			new SessionTraits(
				TEST_CATALOG,
				session -> terminatedSessionId.set(session.getId())
			)
		);
		newEvitaClient.close();
		assertNotNull(terminatedSessionId.get());
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCallTerminationCallbackWhenServerClosesTheSession(EvitaClient evitaClient, EvitaServer evitaServer) {
		final AtomicReference<UUID> terminatedSessionId = new AtomicReference<>();
		final EvitaSessionContract clientSession = evitaClient.createSession(
			new SessionTraits(
				TEST_CATALOG,
				session -> terminatedSessionId.set(session.getId())
			)
		);
		final EvitaSessionContract serverSession = evitaServer.getEvita()
			.getSessionById(clientSession.getId())
			.orElseThrow(() -> new IllegalStateException("Server doesn't know the session!"));

		serverSession.close();

		// we don't know that the session is dead, yet
		assertTrue(clientSession.isActive());
		assertNull(terminatedSessionId.get());

		try {
			clientSession.getEntityCollectionSize(Entities.PRODUCT);
		} catch (Exception ex) {
			// now we know it
			assertFalse(clientSession.isActive());
			assertNotNull(terminatedSessionId.get());
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldTranslateErrorCorrectlyAndLeaveSessionOpen(EvitaClient evitaClient) {
		final EvitaSessionContract clientSession = evitaClient.createReadOnlySession(TEST_CATALOG);
		try {
			clientSession.getEntity("nonExisting", 1, entityFetchAll().getRequirements());
		} catch (EvitaInvalidUsageException ex) {
			assertTrue(clientSession.isActive());
			assertTrue(ex.getPublicMessage().contains("No collection found for entity type `nonExisting`!"));
			assertEquals(ex.getPrivateMessage(), ex.getPublicMessage());
			assertNotNull(ex.getErrorCode());
		} finally {
			clientSession.close();
		}
	}

	@DisplayName("Should return entity schema directly or via model class")
	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldReturnEntitySchema(EvitaClient evitaClient) {
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertNotNull(session.getEntitySchema(Entities.PRODUCT).orElse(null));
				assertNotNull(session.getEntitySchema(ProductInterface.class).orElse(null));
				assertFalse(
					session.getEntitySchema(Entities.PRODUCT).orElseThrow()
						.differsFrom(session.getEntitySchema(ProductInterface.class).orElseThrow())
				);

			}
		);
	}

	@DisplayName("Should provide paginated access to references")
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Test
	void shouldReturnPaginatedReferences(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity productWithMaxReferences = products.values()
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evitaClient.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// provide all brands
										referenceContent(Entities.BRAND),
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											page(pageNumber, 5)
										)
									)
								)
							)
						).orElseThrow();

						assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 5);
						assertEquals(foundParameters.size(), productByPk.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						PaginatedList<ReferenceContract> parameters = new PaginatedList<>(pageNumber, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);

					}

					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide total count of references only")
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Test
	void shouldReturnTotalCountOfReferencesOnly(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity productWithMaxReferences = products.values()
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());

		assertEquals(
			Integer.valueOf(originParameters.size()),
			evitaClient.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity productByPk = session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
							),
							require(
								entityFetch(
									// but only first four parameters
									referenceContent(
										Entities.PARAMETER,
										entityFetchAll(),
										entityGroupFetchAll(),
										page(1, 0)
									)
								)
							)
						)
					).orElseThrow();

					return productByPk.getReferenceChunk(Entities.PARAMETER).getTotalRecordCount();
				}
			)
		);
	}

}
