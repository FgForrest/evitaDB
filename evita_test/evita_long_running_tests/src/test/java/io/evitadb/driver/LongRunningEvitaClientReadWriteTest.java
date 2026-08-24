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

package io.evitadb.driver;

import com.github.javafaker.Faker;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.proxy.mock.CategoryInterface;
import io.evitadb.api.proxy.mock.MockCatalogChangeCaptureSubscriber;
import io.evitadb.api.proxy.mock.MockEngineChangeCaptureSubscriber;
import io.evitadb.api.proxy.mock.ProductInterface;
import io.evitadb.api.proxy.mock.TestEntity;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.cdc.CatalogChangeObserver;
import io.evitadb.dataType.ContainerType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.cdc.HeartBeatSensor;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.GrpcReservedKeywordsResponse;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogUnaryRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogUnaryRequest.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogUnaryResponse;
import io.evitadb.externalApi.grpc.generated.GrpcTaskStatus;
import io.evitadb.externalApi.grpc.generated.GrpcUuid;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.RequiresDefaultWarmUpWritePath;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
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
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;

/**
 * This test verifies behavior of {@link EvitaClient}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiresDefaultWarmUpWritePath
@SuppressWarnings("DataFlowIssue")
@Slf4j
@ExtendWith(EvitaParameterResolver.class)
@Tag(DRIVER)
@Tag(MANAGEMENT)
class LongRunningEvitaClientReadWriteTest implements TestConstants, EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	public static final String ATTRIBUTE_CATEGORY_ORDER = "orderInCategory";
	public static final String ATTRIBUTE_UUID = "uuid";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CODE_NAME = "codeName";
	private static final String ATTRIBUTE_CATEGORY_OPEN = "open";
	private static final String ATTRIBUTE_CATEGORY_MARKET_OPEN = "marketOpen";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");

	private final static int SEED = 42;
	private static final String EVITA_CLIENT_DATA_SET = "EvitaReadWriteClientDataSet";
	private static final String EVITA_CLIENT_EMPTY_DATA_SET = "EvitaReadWriteClientEmptyDataSet";
	private static final Map<Serializable, Integer> GENERATED_ENTITIES = new HashMap<>(20);
	private static final BiFunction<String, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, faker) -> {
		final int entityCount = GENERATED_ENTITIES.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	private static final int PRODUCT_COUNT = 10;
	private static DataGenerator DATA_GENERATOR;

	@DataSet(value = EVITA_CLIENT_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		DATA_GENERATOR = new DataGenerator.Builder()
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

		final String serverCertificates = evitaServer.getExternalApiServer()
		                                             .getApiOptions()
		                                             .certificate()
		                                             .getFolderPath()
		                                             .toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		final EvitaClientConfiguration evitaClientConfiguration = EvitaClientConfiguration
			.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			// disable the keep-alive ping in the test lane: a long inline call on a direct-executor
			// test server can stall the event loop past the ping budget and self-cancel the connection
			.pingIntervalMillis(0)
			.tls(
				ClientTlsOptions.builder()
					.mtlsEnabled(false)
					.certificateFolderPath(clientCertificates)
					.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
					.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
					.build()
			)
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.MINUTES)
					.build()
			)
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
								              builder.withReflectedReferenceToEntity(
									              "productsInCategory", Entities.PRODUCT, Entities.CATEGORY,
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

					DATA_GENERATOR.generateEntities(
						              DATA_GENERATOR.getSamplePriceListSchema(
							              session,
							              builder -> {
								              builder
									              .withAttribute(
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
										thatIs -> thatIs.faceted()
										                .withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
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
					DATA_GENERATOR.generateEntities(
						              productSchema.get(),
						              RANDOM_ENTITY_PICKER,
						              SEED
					              )
					              .limit(PRODUCT_COUNT)
					              .forEach(it -> {
						              final EntityReferenceContract upsertedProduct = session.upsertEntity(it);
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

					session.goLiveAndClose();
				}
			);
		}

		return new DataCarrier(
			"products", products.get(),
			"productSchema", productSchema.get()
		);
	}

	@DataSet(value = EVITA_CLIENT_EMPTY_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static EvitaClient initEmptyDataSet(EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer()
		                                         .getApiOptions();
		final HostDefinition grpcHost = apiOptions
			.getEndpointConfiguration(GrpcProvider.CODE)
			.getHost()[0];
		final HostDefinition systemHost = apiOptions
			.getEndpointConfiguration(SystemProvider.CODE)
			.getHost()[0];

		final String serverCertificates = evitaServer.getExternalApiServer()
		                                             .getApiOptions()
		                                             .certificate()
		                                             .getFolderPath()
		                                             .toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		final EvitaClientConfiguration evitaClientConfiguration = EvitaClientConfiguration
			.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			// disable the keep-alive ping in the test lane: a long inline call on a direct-executor
			// test server can stall the event loop past the ping budget and self-cancel the connection
			.pingIntervalMillis(0)
			.tls(
				ClientTlsOptions.builder()
					.mtlsEnabled(false)
					.certificateFolderPath(clientCertificates)
					.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
					.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
					.build()
			)
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.MINUTES)
					.build()
			)
			.build();

		return new EvitaClient(evitaClientConfiguration);
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

		final ReferencedFileSet expectedAssociatedData = originalProduct.getAssociatedData(
			DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class,
			ReflectionLookup.NO_CACHE_INSTANCE
		);
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
			final List<PriceContract> originalPricesForSale = originalProduct.getAllPricesForSale(
				currency, null, priceLists);
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
						.filter(it -> it.currency().equals(expectedPrice.currency()) && it.priceList()
						                                                                  .equals(
							                                                                  expectedPrice.priceList()))
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

		final Optional<PriceContract> first = Arrays.stream(expectedAllPrices).filter(
			it -> "basic".equals(it.priceList())).findFirst();
		if (first.isEmpty()) {
			assertNull(product.getBasicPrice());
		} else {
			assertEquals(
				first.get(),
				product.getBasicPrice()
			);
		}
	}

	public static void assertPrice(
		@Nonnull PricesContract updatedInstance,
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		final PriceContract price = updatedInstance.getPrice(priceId, priceList, currency).orElseGet(
			() -> fail("Price not found!"));
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.indexed());
	}

	private static void assertProductBasicData(
		@Nonnull SealedEntity originalProduct, @Nullable ProductInterface product) {
		assertNotNull(product);
		assertEquals(originalProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(originalProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
	}

	private static void assertProductAttributes(
		@Nonnull SealedEntity originalProduct, @Nonnull ProductInterface product, @Nullable Locale locale
	) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		if (locale != null) {
			assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), product.getName());
		}
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(
			originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
	}

	/**
	 * Creates new entity and inserts it into the index.
	 */
	private static void createEntity(
		@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities,
		@Nonnull EntityBuilder it
	) {
		final EntityReferenceContract insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

	@Nonnull
	private static EntityReferenceContract createSomeNewCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		final EntityBuilder builder = session
			.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(
				ATTRIBUTE_NAME, Locale.ENGLISH, "New category #" + primaryKey)
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
		return session
			.createNewEntity(Entities.PRODUCT)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "New product")
			.setAttribute(
				ATTRIBUTE_CODE, "product-" + (session.getEntityCollectionSize(Entities.PRODUCT) + 1))
			.setAttribute(ATTRIBUTE_PRIORITY, session.getEntityCollectionSize(Entities.PRODUCT) + 1L)
			.setReference(Entities.PARAMETER, 1)
			.setReference(Entities.PARAMETER, 2)
			.toMutation()
			.orElseThrow();
	}

	private static void assertSomeNewProductContent(@Nonnull SealedEntity loadedEntity) {
		assertNotNull(loadedEntity.getPrimaryKey());
		assertEquals("New product", loadedEntity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
	}

	/**
	 * Defines a reflected reference using EvitaClient in the specified catalog.
	 *
	 * @param evitaClient     the Evita client to use, must not be null
	 * @param someCatalogName the name of the catalog, must not be null
	 */
	private static void defineReflectedReference(
		@Nonnull EvitaClient evitaClient,
		@Nonnull String someCatalogName
	) {
		evitaClient.defineCatalog(someCatalogName)
			.withDescription("This is a tutorial catalog.")
			// define category schema
			.withEntitySchema(
				Entities.CATEGORY,
				whichIs -> whichIs.withDescription("A category of products.")
					.withReflectedReferenceToEntity(
						"productsInCategory", Entities.PRODUCT, "productCategory",
						thatIs -> thatIs.withAttributesInheritedExcept("note")
							.withCardinality(Cardinality.ZERO_OR_MORE)
							.withAttribute("customNote", String.class)
					)
					.withAttribute(
						"name", String.class,
						thatIs -> thatIs.localized().filterable().sortable()
					)
					.withHierarchy()
			)
			// define product schema
			.withEntitySchema(
				Entities.PRODUCT,
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
						"productCategory", Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						thatIs -> thatIs
							.withDescription("Assigned category.")
							.deprecated("Already deprecated.")
							.withAttribute("categoryPriority", Long.class, that -> that.sortable())
							.withAttribute("note", String.class)
							.indexedForFilteringAndPartitioning()
							.faceted()
					)
			)
			// and now push all the definitions (mutations) to the server
			.updateViaNewSession(evitaClient);
	}

	@Nonnull
	private static EvitaManagementServiceFutureStub getManagementStubInternal(
		@Nonnull EvitaClient evitaClient
	) {
		try {
			final EvitaManagementContract management = evitaClient.management();
			final Field evitaManagementServiceStub = management.getClass().getDeclaredField(
				"evitaManagementServiceFutureStub");
			evitaManagementServiceStub.setAccessible(true);
			return (EvitaManagementServiceFutureStub) evitaManagementServiceStub.get(management);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	@Test
	@Tag(SLOW)
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldReceiveHeartbeatAtRegularIntervals(EvitaClient evitaClient) throws InterruptedException {
		final String testCatalogName = "testCatalogForHeartbeat";
		try {
			// Create a test catalog
			evitaClient.defineCatalog(testCatalogName);
			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.goLiveAndClose();
					return null;
				}
			);

			// Create a custom subscriber that implements HeartBeatSensor to track heartbeats
			final MockCatalogChangeCaptureSubscriberWithHeartBeat catalogSubscriber =
				new MockCatalogChangeCaptureSubscriberWithHeartBeat(Integer.MAX_VALUE);

			// Create a client with low streaming timeout to trigger frequent heartbeats
			final EvitaClient clientWithLowTimeout = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host(evitaClient.getConfiguration().host())
					.port(evitaClient.getConfiguration().port())
					.systemApiPort(evitaClient.getConfiguration().systemApiPort())
					// disable the HTTP/2 keep-alive ping in the test lane so it does not confound the
					// app-level CDC heartbeat under test on this direct-executor server
					.pingIntervalMillis(0)
					.timeouts(
						ClientTimeoutOptions.builder()
							.streamingTimeout(6, TimeUnit.SECONDS)
							.build()
					)
					.build()
			);

			try {
				// Register catalog change capture with the heartbeat-aware subscriber
				clientWithLowTimeout.updateCatalog(
					testCatalogName,
					session -> {
						final ChangeCapturePublisher<ChangeCatalogCapture> publisher = session.registerChangeCatalogCapture(
							ChangeCatalogCaptureRequest
								.builder()
								.content(ChangeCaptureContent.BODY)
								.criteria(
									ChangeCatalogCaptureCriteria
										.builder()
										.schemaArea()
										.build()
								)
								.build()
						);
						publisher.subscribe(catalogSubscriber);
						return null;
					}
				);

				// Wait for at least 3 heartbeats (should take ~3 seconds with the calculated interval)
				// According to EvitaSessionService logic: heartBeatDelay = Math.min(Math.max(requestTimeout - 5000L, 1000L), 300000L)
				// With 6000ms timeout: heartBeatDelay = Math.min(Math.max(6000 - 5000, 1000), 300000) = 1000ms
				Thread.sleep(4000);

				// Verify we received multiple heartbeats
				final int receivedHeartbeats = catalogSubscriber.getHeartbeatCount();
				assertTrue(receivedHeartbeats >= 3,
					"Expected at least 3 heartbeats but got: " + receivedHeartbeats);

				// Verify heartbeats came at approximately 1 second intervals
				assertNotNull(catalogSubscriber.getFirstHeartbeatTime(), "Should have received first heartbeat");
				assertNotNull(catalogSubscriber.getLastHeartbeatTime(), "Should have received last heartbeat");
				assertNotNull(catalogSubscriber.getExpectedInterval(), "Should have expected interval from heartbeat");

				// Check that the expected interval is approximately 1000ms; tolerance is generous because the
				// server-reported interval picks up transport / scheduling slack under load and a few hundred
				// ms of drift on a 1s timer is not a misconfiguration we want this assertion to flag
				assertEquals(1000L, catalogSubscriber.getExpectedInterval(), 250L,
					"Expected interval should be approximately 1000ms based on timeout calculation");

				// Check that total time elapsed is reasonable for the number of heartbeats
				final long totalTime = catalogSubscriber.getLastHeartbeatTime() - catalogSubscriber.getFirstHeartbeatTime();
				assertTrue(
					totalTime > 0,
					"Total time between first and last heartbeat should be positive"
				);
				assertTrue(
					totalTime > catalogSubscriber.getExpectedInterval(),
					"Total time should be greater than one interval");
			} finally {
				clientWithLowTimeout.close();
			}

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}
	private static class MockCatalogChangeCaptureSubscriberWithHeartBeat extends MockCatalogChangeCaptureSubscriber implements HeartBeatSensor {
		private final AtomicInteger heartbeatCount = new AtomicInteger(0);
		private final AtomicReference<Long> firstHeartbeatTime = new AtomicReference<>();
		private final AtomicReference<Long> lastHeartbeatTime = new AtomicReference<>();
		private final AtomicReference<Long> expectedInterval = new AtomicReference<>();

		public MockCatalogChangeCaptureSubscriberWithHeartBeat(int initialRequestCount) {
			super(initialRequestCount);
		}

		@Override
		public void onHeartBeat(@Nonnull HeartBeat heartBeat) {
			final long currentTime = System.currentTimeMillis();
			this.heartbeatCount.incrementAndGet();

			if (this.firstHeartbeatTime.get() == null) {
				this.firstHeartbeatTime.set(currentTime);
				this.expectedInterval.set(heartBeat.millisToNextHeartbeat());
			}
			this.lastHeartbeatTime.set(currentTime);
		}

		public int getHeartbeatCount() {
			return this.heartbeatCount.get();
		}

		public Long getFirstHeartbeatTime() {
			return this.firstHeartbeatTime.get();
		}

		public Long getLastHeartbeatTime() {
			return this.lastHeartbeatTime.get();
		}

		public Long getExpectedInterval() {
			return this.expectedInterval.get();
		}


	}

}
