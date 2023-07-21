/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionContract.DeletedHierarchy;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.TestEntity;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.Assertions.assertDiffers;
import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link EvitaClient}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ExtendWith(EvitaParameterResolver.class)
class EvitaClientTest implements TestConstants, EvitaTestSupport {
	private final static int SEED = 42;
	private static final String EVITA_CLIENT_DATA_SET = "EvitaClientDataSet";

	@DataSet(value = EVITA_CLIENT_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		final Map<Serializable, Integer> generatedEntities = new HashMap<>(2000);
		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
			final int entityCount = generatedEntities.computeIfAbsent(entityType, serializable -> 0);
			final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			return primaryKey == 0 ? null : primaryKey;
		};

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
			.host(grpcHost.hostName())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			.mtlsEnabled(false)
			.certificateFolderPath(clientCertificates)
			.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
			.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
			.build();

		AtomicReference<Map<Integer, SealedEntity>> products = new AtomicReference<>();
		try (final EvitaClient setupClient = new EvitaClient(evitaClientConfiguration)) {
			final DataGenerator dataGenerator = new DataGenerator();
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
							dataGenerator.getSampleBrandSchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(5)
						.forEach(it -> createEntity(session, generatedEntities, it));

					dataGenerator.generateEntities(
							dataGenerator.getSampleCategorySchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(10)
						.forEach(it -> createEntity(session, generatedEntities, it));

					dataGenerator.generateEntities(
							dataGenerator.getSamplePriceListSchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(4)
						.forEach(it -> createEntity(session, generatedEntities, it));

					dataGenerator.generateEntities(
							dataGenerator.getSampleStoreSchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(12)
						.forEach(it -> createEntity(session, generatedEntities, it));

					dataGenerator.generateEntities(
							dataGenerator.getSampleParameterGroupSchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(20)
						.forEach(it -> createEntity(session, generatedEntities, it));

					dataGenerator.generateEntities(
							dataGenerator.getSampleParameterSchema(session),
							randomEntityPicker,
							SEED
						)
						.limit(20)
						.forEach(it -> createEntity(session, generatedEntities, it));

					final EntitySchemaContract productSchema = dataGenerator.getSampleProductSchema(
						session,
						entitySchemaBuilder -> {
							entitySchemaBuilder
								.withGlobalAttribute(ATTRIBUTE_CODE)
								.withReferenceToEntity(
									Entities.PARAMETER,
									Entities.PARAMETER,
									Cardinality.ZERO_OR_MORE,
									thatIs -> thatIs.faceted().withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
								);
						}
					);

					final Map<Integer, SealedEntity> theProducts = CollectionUtils.createHashMap(10);
					dataGenerator.generateEntities(
							productSchema,
							randomEntityPicker,
							SEED
						)
						.limit(10)
						.forEach(it -> {
							final EntityReference upsertedProduct = session.upsertEntity(it);
							theProducts.put(
								upsertedProduct.getPrimaryKey(),
								session.getEntity(
									productSchema.getName(),
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
			"products", products.get()
		);
	}

	private static void assertCategoryParent(
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull CategoryInterface category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.getId());
		if (originalCategory.getParent().isEmpty()) {
			assertNull(category.getParentId());
			assertNull(category.getParentEntityReference());
			assertNull(category.getParentEntity());
		} else {
			final int expectedParentId = originalCategory.getParent().getAsInt();
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

		final PriceContract[] allPricesForSale = product.getAllPricesForSale();
		final PriceContract[] expectedAllPricesForSale = originalProduct.getAllPricesForSale().toArray(PriceContract[]::new);

		if (currency == null && priceLists == null) {
			assertThrows(ContextMissingException.class, product::getPriceForSale);
		} else {
			assertEquals(
				Arrays.stream(expectedAllPricesForSale)
					.filter(it -> Objects.equals(currency, it.currency()))
					.filter(it -> Arrays.stream(priceLists).anyMatch(priceList -> Objects.equals(priceList, it.priceList())))
					.sorted((o1, o2) -> {
						final int ix1 = ArrayUtils.indexOf(o1.priceList(), priceLists);
						final int ix2 = ArrayUtils.indexOf(o2.priceList(), priceLists);
						return Integer.compare(ix1, ix2);
					})
					.findFirst()
					.orElse(null),
				product.getPriceForSale()
			);
		}

		assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
		assertArrayEquals(expectedAllPricesForSale, allPricesForSale);

		if (expectedAllPricesForSale.length > 0) {
			final PriceContract expectedPrice = expectedAllPricesForSale[0];
			assertEquals(
				expectedPrice,
				product.getPriceForSale(expectedPrice.priceList(), expectedPrice.currency())
			);

			if ((currency == null || priceLists == null) && expectedPrice.validity() != null) {
				assertEquals(
					expectedPrice,
					product.getPriceForSale(expectedPrice.priceList(), expectedPrice.currency(), expectedPrice.validity().getPreciseFrom())
				);
			}

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.priceList().equals(expectedPrice.priceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.priceList())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.currency().equals(expectedPrice.currency()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.currency())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.currency().equals(expectedPrice.currency()) && it.priceList().equals(expectedPrice.priceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.priceList(), expectedPrice.currency())
			);
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
			Integer.valueOf(10),
			evitaClient.queryCatalog(TEST_CATALOG, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().getVersion();
			})
		);

		evitaClient.replaceCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(11),
			evitaClient.queryCatalog(newCatalog, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().getVersion();
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
			Integer.valueOf(10),
			evitaClient.queryCatalog(TEST_CATALOG, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().getVersion();
			})
		);

		evitaClient.renameCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(11),
			evitaClient.queryCatalog(newCatalog, evitaSessionContract -> {
				return evitaSessionContract.getCatalogSchema().getVersion();
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
									referenceContentAll(),
									dataInLocales()
								) :
								entityFetch(
									hierarchyContent(entityFetchAll()),
									attributeContentAll(),
									associatedDataContentAll(),
									priceContentAll(),
									referenceContentAll(entityFetchAll()),
									dataInLocales()
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
	void shouldGetListWithExtraResults(EvitaClient evitaClient, Map<Integer, SealedEntity> products) {
		final SealedEntity someProductWithCategory = products.values()
			.stream()
			.filter(it -> !it.getReferences(Entities.CATEGORY).isEmpty())
			.filter(it -> !it.getAllPricesForSale().isEmpty())
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> result = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(someProductWithCategory.getAllPricesForSale().stream().map(PriceContract::priceList).toArray(String[]::new)),
							priceInCurrency(someProductWithCategory.getAllPricesForSale().stream().map(PriceContract::currency).findFirst().orElseThrow()),
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
		assertTrue(categoryHierarchy.get("megaMenu").size() > 0);

		final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
		assertNotNull(facetSummary);
		assertTrue(facetSummary.getReferenceStatistics().size() > 0);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldGetListOfCustomEntitiesWithExtraResults(EvitaClient evitaClient, Map<Integer, SealedEntity> products, Map<Integer, SealedEntity> originalCategories) {
		final SealedEntity someProductWithCategory = products.values()
			.stream()
			.filter(it -> !it.getReferences(Entities.CATEGORY).isEmpty())
			.filter(it -> !it.getAllPricesForSale().isEmpty())
			.findFirst()
			.orElseThrow();

		final String[] priceLists = someProductWithCategory.getAllPricesForSale().stream().map(PriceContract::priceList).toArray(String[]::new);
		final Currency currency = someProductWithCategory.getAllPricesForSale().stream().map(PriceContract::currency).findFirst().orElseThrow();
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
				priceLists,
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
		assertTrue(categoryHierarchy.get("megaMenu").size() > 0);

		final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
		assertNotNull(facetSummary);
		assertTrue(facetSummary.getReferenceStatistics().size() > 0);
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
			.getSessionById(TEST_CATALOG, clientSession.getId())
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
			assertEquals("No collection found for entity type `nonExisting`!", ex.getPublicMessage());
			assertEquals(ex.getPrivateMessage(), ex.getPublicMessage());
			assertNotNull(ex.getErrorCode());
		} finally {
			clientSession.close();
		}
	}

	@DisplayName("Should return entity schema directly or via model class")
	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldReturnEntitySchema(EvitaSessionContract evitaSession) {
		assertNotNull(evitaSession.getEntitySchema(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchema(ProductInterface.class));
		assertEquals(
			evitaSession.getEntitySchema(Entities.PRODUCT),
			evitaSession.getEntitySchema(ProductInterface.class)
		);

		assertNotNull(evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchemaOrThrow(ProductInterface.class));
		assertEquals(
			evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT),
			evitaSession.getEntitySchemaOrThrow(ProductInterface.class)
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
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

}