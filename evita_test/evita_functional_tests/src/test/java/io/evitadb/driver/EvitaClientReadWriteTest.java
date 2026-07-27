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
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
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
import io.evitadb.api.requestResponse.schema.*;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * This test verifies behavior of {@link EvitaClient}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("DataFlowIssue")
@Slf4j
@ExtendWith(EvitaParameterResolver.class)
@Tag(DRIVER)
@Tag(MANAGEMENT)
class EvitaClientReadWriteTest implements TestConstants, EvitaTestSupport {
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
	private static final String REFERENCE_BRAND = "brand";
	private static final int BRAND_PRIMARY_KEY = 100;
	private static final String ATOMIC_ROLLBACK_CATALOG = "atomicRollbackClientCatalog";

	private final static int SEED = 42;
	private static final String EVITA_CLIENT_DATA_SET = "EvitaReadWriteClientDataSet";
	private static final String EVITA_CLIENT_EMPTY_DATA_SET = "EvitaReadWriteClientEmptyDataSet";
	/**
	 * Overall budget for the live-tail host-event retry loops in the HOST-area gRPC tests. Host
	 * events are dispatched only to subscribers already registered server-side and are NOT replayed,
	 * so a trigger fired before the async `registerSystemChangeCapture` subscription completes is
	 * dropped. The loop re-fires the trigger until the event round-trips or this budget elapses.
	 */
	private static final long HOST_EVENT_LIVENESS_BUDGET_MS = 20_000L;
	/**
	 * Per-attempt wait (seconds) for the host event to round-trip before the retry loop re-fires
	 * its trigger.
	 */
	private static final int HOST_EVENT_LIVENESS_POLL_SECONDS = 2;
	private static final Map<Serializable, Integer> GENERATED_ENTITIES = new HashMap<>(20);
	private static final BiFunction<String, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, faker) -> {
		final int entityCount = GENERATED_ENTITIES.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	private static final int PRODUCT_COUNT = 10;
	private static DataGenerator DATA_GENERATOR;

	@DataSet(value = EVITA_CLIENT_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true, useRealThreadPools = true)
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
					       .withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
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
							.withAttribute("categoryPriority", Long.class, AttributeSchemaEditor::sortable)
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
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRetrieveSystemConfiguration(EvitaClient evitaClient) {
		final String configuration = evitaClient.management().getConfiguration();
		assertNotNull(configuration);
		assertTrue(configuration.contains("name:"));
		assertTrue(configuration.contains("server:"));
		assertTrue(configuration.contains("api:"));
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotifyBasicSubscriber(EvitaClient evitaClient) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
		);

		// subscriber is registered and wants one event when it happens
		final MockEngineChangeCaptureSubscriber subscriber = new MockEngineChangeCaptureSubscriber(2);
		publisher.subscribe(subscriber);

		evitaClient.defineCatalog("newCatalog1");
		evitaClient.defineCatalog("newCatalog2");

		// subscriber wants more events now, should receive `newCatalog2` and future `newCatalog3`
		subscriber.request(4);

		evitaClient.defineCatalog("newCatalog3");

		// subscriber should receive 4 future events
		subscriber.request(4);

		evitaClient.defineCatalog("newCatalog4");
		evitaClient.defineCatalog("newCatalog5");

		// subscriber requested 2 events, this is third one, so it should be ignored
		evitaClient.defineCatalog("newCatalog6");

		// subscriber received one requested event
		assertEquals(1, subscriber.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog2"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog3"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog4"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog5"));
		// subscriber didn't ask for more events, so it didn't receive any new events
		assertEquals(0, subscriber.getCatalogCreated("newCatalog6"));

		evitaClient.deleteCatalogIfExists("newCatalog1");
		evitaClient.deleteCatalogIfExists("newCatalog2");
		evitaClient.deleteCatalogIfExists("newCatalog3");
		evitaClient.deleteCatalogIfExists("newCatalog4");
		evitaClient.deleteCatalogIfExists("newCatalog5");
		evitaClient.deleteCatalogIfExists("newCatalog6");
	}

	/**
	 * End-to-end gRPC round-trip test for {@link HostSystemEvent} delivery via
	 * {@link SystemCaptureArea#HOST}.
	 *
	 * Subscribes a client subscriber with host-area criteria and triggers a catalog
	 * create + activate sequence on the server. The host event must round-trip through the
	 * gRPC stream and be deserialized by the client driver into a
	 * {@link HostSystemEvent.CatalogInstalledIntoLiveView}.
	 */
	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotifyHostEventsOverGrpcWithHostCriteria(EvitaClient evitaClient) {
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(
				ChangeSystemCaptureRequest.builder()
					.content(ChangeCaptureContent.BODY)
					.criteria(
						new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
						new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
					)
					.build()
			)
		) {
			final MockEngineChangeCaptureSubscriber subscriber =
				new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
			publisher.subscribe(subscriber);

			// `publisher.subscribe(...)` only kicks off the async gRPC stream — it does NOT block
			// until the server-side subscription is registered (the server ACK lands on a different
			// thread). Host events fired before that registration completes are dropped, not
			// replayed. Retry the catalog activation with a DISTINCT catalog each attempt until the
			// install host event round-trips to the client subscriber, or the budget elapses. The
			// subscriber's per-catalog getter is event-specific, so checking the CURRENT attempt's
			// catalog only goes true once registration has completed.
			final String catalogFamily = "hostEventCatalogGrpc";
			final long deadlineNanos = System.nanoTime() + HOST_EVENT_LIVENESS_BUDGET_MS * 1_000_000L;
			int attempt = 0;
			boolean hostEventDelivered = false;
			try {
				while (!hostEventDelivered && System.nanoTime() < deadlineNanos) {
					final String catalog = catalogFamily + attempt++;
					evitaClient.defineCatalog(catalog);
					evitaClient.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
					hostEventDelivered = subscriber.getCatalogInstalled(
						catalog, HOST_EVENT_LIVENESS_POLL_SECONDS, TimeUnit.SECONDS, 1
					) >= 1;
				}
				assertTrue(
					hostEventDelivered,
					"Client should receive at least one CatalogInstalledIntoLiveView host event within the liveness budget"
				);
			} finally {
				subscriber.cancel();
				// clean up every catalog the retry loop created
				for (int i = 0; i < attempt; i++) {
					evitaClient.deleteCatalogIfExists(catalogFamily + i);
				}
			}
		}
	}

	/**
	 * End-to-end gRPC delivery test for {@link HostSystemEvent.CatalogSchemaUpdated}.
	 *
	 * Subscribes a client subscriber with HOST-area criteria, opens a WARMING_UP session
	 * that bumps the schema version (defines a collection + adds attributes), closes it,
	 * and asserts the coalesced schema-updated host event arrives exactly once over the
	 * gRPC stream — replacing the per-mutation refresh storm previously observed by
	 * GraphQL / REST managers.
	 */
	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@DisplayName("should deliver CatalogSchemaUpdated to HOST-area subscriber")
	void shouldDeliverCatalogSchemaUpdatedToHostSubscriber(EvitaClient evitaClient) {
		final String schemaUpdatedCatalog = "schemaUpdatedCatalogGrpc";
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(
				ChangeSystemCaptureRequest.builder()
					.content(ChangeCaptureContent.BODY)
					.criteria(new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST))
					.build()
			)
		) {
			final MockEngineChangeCaptureSubscriber subscriber =
				new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
			publisher.subscribe(subscriber);

			// create the WARMING_UP catalog once; each retry below applies schema mutations in a
			// single session, and the coalescing contract requires exactly one CatalogSchemaUpdated
			// host event per such session regardless of the mutation count
			evitaClient.defineCatalog(schemaUpdatedCatalog);

			// `publisher.subscribe(...)` only kicks off the async gRPC stream — it does NOT block
			// until the server-side subscription is registered. Host events are dispatched only to
			// already-registered subscribers and are NOT replayed, so a schema bump fired before
			// registration completes is dropped. Retry the bump — each attempt defines a DISTINCT
			// entity type, producing a fresh coalesced CatalogSchemaUpdated — until one round-trips
			// to the client subscriber, or the budget elapses. The per-catalog count is cumulative,
			// so it goes >= 1 as soon as any post-registration bump is delivered.
			final long deadlineNanos = System.nanoTime() + HOST_EVENT_LIVENESS_BUDGET_MS * 1_000_000L;
			int attempt = 0;
			boolean hostEventDelivered = false;
			while (!hostEventDelivered && System.nanoTime() < deadlineNanos) {
				final String probeEntityType = Entities.PRODUCT + attempt++;
				evitaClient.updateCatalog(
					schemaUpdatedCatalog,
					session -> {
						session.defineEntitySchema(probeEntityType)
							.withAttribute("attr1", String.class)
							.withAttribute("attr2", String.class)
							.withAttribute("attr3", String.class)
							.updateVia(session);
					}
				);
				hostEventDelivered = subscriber.getCatalogSchemaUpdated(
					schemaUpdatedCatalog, HOST_EVENT_LIVENESS_POLL_SECONDS, TimeUnit.SECONDS, 1
				) >= 1;
			}
			assertTrue(
				hostEventDelivered,
				"Client should receive at least one CatalogSchemaUpdated host event within the liveness budget"
			);

			subscriber.cancel();
		} finally {
			evitaClient.deleteCatalogIfExists(schemaUpdatedCatalog);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotifyLateSubscribers(EvitaClient evitaClient) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
		);

		// first subscriber is registered at the start, but it's not ready to receive events yet
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRequest = new MockEngineChangeCaptureSubscriber(0);
		publisher.subscribe(subscriberWithDelayedRequest);

		// should be ignored by both subscribers
		evitaClient.defineCatalog("newCatalog1");

		// second subscriber is registered later but ready to receive events
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRegistration = new MockEngineChangeCaptureSubscriber(
			Integer.MAX_VALUE);
		publisher.subscribe(subscriberWithDelayedRegistration);

		// first subscriber is ready to receive events now, should get one
		subscriberWithDelayedRequest.request(Integer.MAX_VALUE);

		evitaClient.defineCatalog("newCatalog2");

		// both should receive one late event
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog1", 10, TimeUnit.SECONDS, 1));
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog2", 10, TimeUnit.SECONDS, 1));
		assertEquals(0, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog2", 10, TimeUnit.SECONDS, 1));

		// cancel both subscribers
		subscriberWithDelayedRequest.cancel();
		subscriberWithDelayedRegistration.cancel();

		evitaClient.deleteCatalogIfExists("newCatalog1");
		evitaClient.deleteCatalogIfExists("newCatalog2");
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotifyLateSubscribersWithFixedInitialVersion(EvitaClient evitaClient) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(
				evitaClient.management().getSystemStatus().engineVersion(), null, null, ChangeCaptureContent.BODY)
		);

		// first subscriber is registered at the start, but it's not ready to receive events yet
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRequest = new MockEngineChangeCaptureSubscriber(0);
		publisher.subscribe(subscriberWithDelayedRequest);

		// should be ignored by both subscribers
		evitaClient.defineCatalog("newCatalog1");

		// second subscriber is registered later but ready to receive events
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRegistration = new MockEngineChangeCaptureSubscriber(
			Integer.MAX_VALUE);
		publisher.subscribe(subscriberWithDelayedRegistration);

		// first subscriber is ready to receive events now, should get one
		subscriberWithDelayedRequest.request(Integer.MAX_VALUE);

		evitaClient.defineCatalog("newCatalog2");

		// both should receive one late event
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog1", 10, TimeUnit.SECONDS, 1));
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog2", 10, TimeUnit.SECONDS, 1));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog1", 10, TimeUnit.SECONDS, 1));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog2", 10, TimeUnit.SECONDS, 1));

		evitaClient.deleteCatalogIfExists("newCatalog1");
		evitaClient.deleteCatalogIfExists("newCatalog2");

		// cancel both subscribers
		subscriberWithDelayedRequest.cancel();
		subscriberWithDelayedRegistration.cancel();
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotifyMultiplePublishers(EvitaClient evitaClient) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher1 = evitaClient.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.HEADER)
		);
		final MockEngineChangeCaptureSubscriber subscriber1 = new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
		publisher1.subscribe(subscriber1);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher2 = evitaClient.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
		);
		final MockEngineChangeCaptureSubscriber subscriber2 = new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
		publisher2.subscribe(subscriber2);

		evitaClient.defineCatalog("newCatalog1");

		assertEquals(
			0,
			subscriber1.getCatalogCreated("newCatalog1")
		); // subscriber1 is subscribed to HEADER content, so it cannot recognize catalog name
		assertEquals(
			2,
			subscriber1.getReceived()
		); // at least 2 events should be received (transaction and create catalog mutation)
		assertEquals(1, subscriber2.getCatalogCreated("newCatalog1", 10, TimeUnit.SECONDS, 1));

		evitaClient.deleteCatalogIfExists("newCatalog1");
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
	void shouldEvictLocalSessionsWhenCatalogIsDeletedViaSameClient(EvitaClient evitaClient) {
		final String someCatalogName = "evictOnDelete";
		try {
			evitaClient.defineCatalog(someCatalogName).updateViaNewSession(evitaClient);
			final EvitaSessionContract leakedSession = evitaClient.createReadWriteSession(someCatalogName);
			assertTrue(leakedSession.isActive());
			assertTrue(evitaClient.getSessionById(leakedSession.getId()).isPresent());

			evitaClient.deleteCatalogIfExists(someCatalogName);

			assertFalse(leakedSession.isActive());
			assertFalse(evitaClient.getSessionById(leakedSession.getId()).isPresent());
		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldEvictLocalSessionsWhenCatalogIsRenamedViaSameClient(EvitaClient evitaClient) {
		final String originalName = "evictOnRenameSource";
		final String renamedName = "evictOnRenameTarget";
		try {
			evitaClient.defineCatalog(originalName).updateViaNewSession(evitaClient);
			final EvitaSessionContract leakedSession = evitaClient.createReadWriteSession(originalName);
			assertTrue(leakedSession.isActive());

			evitaClient.renameCatalog(originalName, renamedName);

			assertFalse(leakedSession.isActive());
			assertFalse(evitaClient.getSessionById(leakedSession.getId()).isPresent());
		} finally {
			evitaClient.deleteCatalogIfExists(originalName);
			evitaClient.deleteCatalogIfExists(renamedName);
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

					final Optional<EntityAttributeSchemaContract> productNameAttribute = product.get().getAttribute(
						"name");
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
							ReferenceSchemaEditor::indexedForFilteringAndPartitioning
						)
						.withReferenceToEntity(
							"categories", "Category", Cardinality.ZERO_OR_MORE,
							ReferenceSchemaEditor::indexedForFilteringAndPartitioning
						)
				)
				// and now push all the definitions (mutations) to the server
				.updateViaNewSession(evitaClient);

			assertTrue(evitaClient.getCatalogNames().contains(someCatalogName));
			evitaClient.queryCatalog(
				someCatalogName, session -> {
					final Set<String> allEntityTypes = session.getAllEntityTypes();
					assertTrue(allEntityTypes.contains("Brand"));
					assertTrue(allEntityTypes.contains("Category"));
					assertTrue(allEntityTypes.contains("Product"));
				}
			);
		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldAllowCreatingReflectedReference(EvitaClient evitaClient) {
		final String someCatalogName = "differentCatalog";
		try {
			defineReflectedReference(evitaClient, someCatalogName);

			assertTrue(evitaClient.getCatalogNames().contains(someCatalogName));
			evitaClient.queryCatalog(
				someCatalogName, session -> {
					final Set<String> allEntityTypes = session.getAllEntityTypes();
					assertTrue(allEntityTypes.contains(Entities.CATEGORY));
					assertTrue(allEntityTypes.contains(Entities.PRODUCT));

					final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException(Entities.CATEGORY)
					                                                 .getReferenceOrThrowException(
						                                                 "productsInCategory");
					assertInstanceOf(ReflectedReferenceSchemaContract.class, reference);

					assertEquals(Entities.PRODUCT, reference.getReferencedEntityType());
					assertEquals("Assigned category.", reference.getDescription());
					assertEquals("Already deprecated.", reference.getDeprecationNotice());
					assertTrue(reference.isIndexed());
					assertTrue(reference.isFaceted());

					final Map<String, AttributeSchemaContract> attributes = reference.getAttributes();
					assertEquals(2, attributes.size());
					assertTrue(attributes.containsKey("customNote"));
					assertTrue(attributes.containsKey("categoryPriority"));
				}
			);
		} finally {
			evitaClient.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldModifyReflectedReferenceSchema(EvitaClient evitaClient) {
		defineReflectedReference(evitaClient, "differentCatalog");

		evitaClient.updateCatalog(
			"differentCatalog",
			session -> {
				session.getEntitySchemaOrThrowException(Entities.CATEGORY)
				       .openForWrite()
				       .withReflectedReferenceToEntity(
					       "productsInCategory", Entities.PRODUCT, "productCategory",
					       thatIs -> thatIs.withAttributesInheritedExcept("categoryPriority")
					                       .withCardinality(Cardinality.EXACTLY_ONE)
					                       .withoutAttribute("customNote")
					                       .withAttribute("newAttribute", String.class)
					                       .withDescription("My description.")
					                       .nonFaceted()
				       ).updateVia(session);
			}
		);

		evitaClient.queryCatalog(
			"differentCatalog",
			session -> {
				final SealedEntitySchema entitySchema = session.getEntitySchemaOrThrowException(Entities.CATEGORY);
				final ReferenceSchemaContract reference = entitySchema.getReferenceOrThrowException(
					"productsInCategory");
				assertEquals(Cardinality.EXACTLY_ONE, reference.getCardinality());
				assertEquals(Entities.PRODUCT, reference.getReferencedEntityType());
				assertEquals("My description.", reference.getDescription());
				assertTrue(reference.isIndexed());
				assertFalse(reference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("newAttribute"));
				assertTrue(attributes.containsKey("note"));
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCreateCatalog(EvitaClient evitaClient) {
		try (MockEngineChangeCaptureSubscriber engineSubscriber = new MockEngineChangeCaptureSubscriber(
			Integer.MAX_VALUE)) {
			evitaClient.registerSystemChangeCapture(
				new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
			).subscribe(engineSubscriber);

			final String newCatalogName = "newCatalog";
			try {
				evitaClient.defineCatalog(newCatalogName);
				final Set<String> catalogNames = evitaClient.getCatalogNames();

				assertEquals(2, catalogNames.size());
				assertTrue(catalogNames.contains(TEST_CATALOG));
				assertTrue(catalogNames.contains(newCatalogName));
				assertEquals(1, engineSubscriber.getCatalogCreated(newCatalogName, 10, TimeUnit.SECONDS, 1));

			} finally {
				evitaClient.deleteCatalogIfExists(newCatalogName);
				assertEquals(1, engineSubscriber.getCatalogDeleted(newCatalogName, 10, TimeUnit.SECONDS, 1));
			}
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRemoveCatalog(EvitaClient evitaClient) {
		final String newCatalogName = "newCatalog";
		evitaClient.defineCatalog(newCatalogName).updateViaNewSession(evitaClient);
		evitaClient.deleteCatalogIfExists(newCatalogName);

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
			evitaClient.queryCatalog(
				TEST_CATALOG, evitaSessionContract -> {
					return evitaSessionContract.getCatalogSchema().version();
				}
			)
		);

		evitaClient.replaceCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(4),
			evitaClient.queryCatalog(
				newCatalog, evitaSessionContract -> {
					return evitaSessionContract.getCatalogSchema().version();
				}
			)
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
			evitaClient.queryCatalog(
				TEST_CATALOG, evitaSessionContract -> {
					return evitaSessionContract.getCatalogSchema().version();
				}
			)
		);

		evitaClient.renameCatalog(TEST_CATALOG, newCatalog);

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(1, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(newCatalog));

		assertEquals(
			Integer.valueOf(4),
			evitaClient.queryCatalog(
				newCatalog, evitaSessionContract -> {
					return evitaSessionContract.getCatalogSchema().version();
				}
			)
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContents(
		EvitaClient evitaClient
	) throws ExecutionException, InterruptedException, TimeoutException {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));

		final EvitaManagementContract management = evitaClient.management();
		final CompletableFuture<FileForFetch> backupFileFuture = management.backupCatalog(
			TEST_CATALOG, null, null, true);
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

		// we need to activate the restored catalog first
		evitaClient.activateCatalog(restoredCatalogName);

		assertEquals(
			Integer.valueOf(PRODUCT_COUNT),
			evitaClient.queryCatalog(
				restoredCatalogName, session -> {
					return session.getEntityCollectionSize(Entities.PRODUCT);
				}
			)
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContentsUnary(
		EvitaClient evitaClient
	) throws ExecutionException, InterruptedException, TimeoutException {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));

		final EvitaManagementContract management = evitaClient.management();
		final CompletableFuture<FileForFetch> backupFileFuture = management.backupCatalog(
			TEST_CATALOG, null, null, true);
		final FileForFetch fileForFetch = backupFileFuture.get(3, TimeUnit.MINUTES);

		log.info("Catalog backed up to file: {}", fileForFetch.fileId());

		final String restoredCatalogName = TEST_CATALOG + "_restored";

		final EvitaManagementServiceFutureStub internalStub = getManagementStubInternal(evitaClient);

		GrpcUuid fileId = null;
		GrpcTaskStatus restoreTask = null;
		try (final InputStream inputStream = management.fetchFile(fileForFetch.fileId())) {
			// read input stream contents by 65k and upload it to the server
			final byte[] buffer = new byte[65 * 1024];
			do {
				final int read = inputStream.read(buffer);
				if (read == -1) {
					break;
				}

				// wait to restoration to be finished
				final Builder builder = GrpcRestoreCatalogUnaryRequest.newBuilder()
				                                                      .setCatalogName(restoredCatalogName)
				                                                      .setTotalSizeInBytes(
					                                                      fileForFetch.totalSizeInBytes())
				                                                      .setBackupFile(
					                                                      ByteString.copyFrom(buffer, 0, read)
				                                                      );
				if (fileId != null) {
					builder.setFileId(fileId);
				}
				final GrpcRestoreCatalogUnaryResponse response = internalStub.restoreCatalogUnary(
					builder.build()
				).get();

				restoreTask = response.getTask();
				fileId = fileId == null ? restoreTask.getFile().getFileId() : fileId;
			} while (true);

			Optional<TaskStatus<?, ?>> status = management.getTaskStatus(
				EvitaDataTypesConverter.toUuid(restoreTask.getTaskId()));
			while (status.map(it -> it.finished() == null).orElse(false)) {
				Thread.sleep(500);
				status = management.getTaskStatus(EvitaDataTypesConverter.toUuid(restoreTask.getTaskId()));
			}

		} catch (IOException e) {
			fail("Failed to restore catalog!", e);
		}

		log.info("Catalog restored from file: {}", fileForFetch.fileId());

		final Set<String> catalogNamesAgain = evitaClient.getCatalogNames();
		assertEquals(2, catalogNamesAgain.size());
		assertTrue(catalogNamesAgain.contains(TEST_CATALOG));
		assertTrue(catalogNamesAgain.contains(restoredCatalogName));

		// we need to activate the restored catalog first
		evitaClient.activateCatalog(restoredCatalogName);

		assertEquals(
			Integer.valueOf(PRODUCT_COUNT),
			evitaClient.queryCatalog(
				restoredCatalogName, session -> {
					return session.getEntityCollectionSize(Entities.PRODUCT);
				}
			)
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldBackupAndRestoreCatalogViaFileOnTheServerSide(
		EvitaClient evitaClient
	) throws ExecutionException, InterruptedException, TimeoutException {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertEquals(1, catalogNames.size());
		assertTrue(catalogNames.contains(TEST_CATALOG));

		final EvitaManagementContract management = evitaClient.management();
		final CompletableFuture<FileForFetch> backupFileFuture = management.backupCatalog(
			TEST_CATALOG, null, null, true
		);
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

		// we need to activate the restored catalog first
		evitaClient.activateCatalog(restoredCatalogName);

		assertEquals(
			Integer.valueOf(PRODUCT_COUNT),
			evitaClient.queryCatalog(
				restoredCatalogName, session -> {
					return session.getEntityCollectionSize(Entities.PRODUCT);
				}
			)
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
				productSchemaVersion.set(session.getEntitySchemaOrThrowException(Entities.PRODUCT).version());
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
				assertEquals(
					productSchemaVersion.get() + 1, session.getEntitySchemaOrThrowException(newCollection).version());
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
				productSchemaVersion.set(session.getEntitySchemaOrThrowException(Entities.PRODUCT).version());
				productCount.set(session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);

		final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(
			Integer.MAX_VALUE);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.registerChangeCatalogCapture(
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
				).subscribe(catalogSubscriber);

				return session.renameCollection(
					Entities.PRODUCT,
					newCollection
				);
			}
		);

		assertEquals(1, catalogSubscriber.getEntityCollectionCreated(newCollection, 10, TimeUnit.SECONDS, 1));
		assertEquals(1, catalogSubscriber.getEntityCollectionDeleted(Entities.PRODUCT, 10, TimeUnit.SECONDS, 1));

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertTrue(session.getAllEntityTypes().contains(newCollection));
				assertEquals(
					productSchemaVersion.get() + 1, session.getEntitySchemaOrThrowException(newCollection).version());
				assertEquals(productCount.get(), session.getEntityCollectionSize(newCollection));
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldRegisterChangeDataCaptureOutsideSession(EvitaClient evitaClient) {
		final String newCollection = "newCollection";
		final AtomicInteger productCount = new AtomicInteger();
		final AtomicInteger productSchemaVersion = new AtomicInteger();
		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertFalse(session.getAllEntityTypes().contains(newCollection));
				productSchemaVersion.set(session.getEntitySchemaOrThrowException(Entities.PRODUCT).version());
				productCount.set(session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);

		final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(
			Integer.MAX_VALUE);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				return session.registerChangeCatalogCapture(
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
			}
		).subscribe(catalogSubscriber);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				return session.renameCollection(
					Entities.PRODUCT,
					newCollection
				);
			}
		);

		assertEquals(1, catalogSubscriber.getEntityCollectionCreated(newCollection, 10, TimeUnit.SECONDS, 1));
		assertEquals(1, catalogSubscriber.getEntityCollectionDeleted(Entities.PRODUCT, 10, TimeUnit.SECONDS, 1));

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertFalse(session.getAllEntityTypes().contains(Entities.PRODUCT));
				assertTrue(session.getAllEntityTypes().contains(newCollection));
				assertEquals(
					productSchemaVersion.get() + 1, session.getEntitySchemaOrThrowException(newCollection).version());
				assertEquals(productCount.get(), session.getEntityCollectionSize(newCollection));
			}
		);
	}

	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldApplyDirectMutations(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.applyMutation(
					new EntityUpsertMutation(
						Entities.PRODUCT,
						null,
						EntityExistence.MUST_NOT_EXIST,
						List.of(
							new UpsertAttributeMutation("code", "siemens"),
							new UpsertAttributeMutation("name", Locale.ENGLISH, "Siemens"),
							new UpsertAttributeMutation("ean", "1234567890123"),
							new UpsertAttributeMutation("quantity", BigDecimal.ONE),
							new UpsertAttributeMutation("priority", 1L),
							new UpsertPriceMutation(
								new PriceKey(1, "basic", CURRENCY_CZK),
								BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
							)
						)
					)
				);
			}
		);
		final SealedEntity fetchedEntity = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					Query.query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals("code", "siemens")),
						require(entityFetchAll())
					)
				).orElseThrow();
			}
		);
		assertNotNull(fetchedEntity.getPrimaryKey());
		assertEquals("siemens", fetchedEntity.getAttribute("code"));
		assertEquals("Siemens", fetchedEntity.getAttribute("name", Locale.ENGLISH));
		assertEquals("1234567890123", fetchedEntity.getAttribute("ean"));
		assertEquals(BigDecimal.ONE, fetchedEntity.getAttribute("quantity"));
		assertEquals(Long.valueOf(1L), fetchedEntity.getAttribute("priority"));
		assertPrice(
			fetchedEntity, 1, "basic", Currency.getInstance("CZK"), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
			true
		);
	}

	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProduct(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> upsertedEntity = DATA_GENERATOR
					.generateEntities(
						productSchema, RANDOM_ENTITY_PICKER, SEED)
					.limit(1)
					.map(it -> session.upsertAndFetchEntity(
						it,
						entityFetchAllContent()
					))
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> fetchedEntity = session.getEntity(
					productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(addedEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - asynchronously.")
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronously(
		EvitaContract evita,
		SealedEntitySchema productSchema
	) throws ExecutionException, InterruptedException, TimeoutException {
		final AtomicInteger addedEntityPrimaryKey = new AtomicInteger();
		final CommitProgress commitProgress = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> upsertedEntity = DATA_GENERATOR.generateEntities(
					                                                            productSchema, RANDOM_ENTITY_PICKER, SEED)
				                                                            .limit(1)
				                                                            .map(it -> session.upsertAndFetchEntity(
					                                                            it,
					                                                            entityFetchAllContent()
				                                                            ))
				                                                            .findFirst();
				assertTrue(upsertedEntity.isPresent());
				addedEntityPrimaryKey.set(upsertedEntity.get().getPrimaryKey());
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		);

		List<String> worklog = new ArrayList<>();
		CompletableFuture.allOf(
			commitProgress.onConflictResolved()
			              .thenAccept(it -> worklog.add("Conflict resolved!"))
			              .toCompletableFuture(),
			commitProgress.onWalAppended().thenAccept(it -> worklog.add("WAL appended!")).toCompletableFuture(),
			commitProgress.onChangesVisible().thenAccept(it -> worklog.add("Changes visible!")).toCompletableFuture()
		).get(1, TimeUnit.MINUTES);

		commitProgress.onChangesVisible().toCompletableFuture().join();

		assertArrayEquals(
			new String[]{
				"Conflict resolved!",
				"WAL appended!",
				"Changes visible!"
			},
			worklog.toArray(new String[0])
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> entityFetchedAgain = session.getEntity(
					productSchema.getName(), addedEntityPrimaryKey.get(), entityFetchAllContent());
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

				final EntityReferenceContract newProduct = session.upsertEntity(entityMutation);
				newProductId.set(newProduct.getPrimaryKey());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity loadedEntity = session
					.getEntity(
						Entities.PRODUCT, newProductId.get(), entityFetchAllContent())
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
	void shouldArchiveExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());
				assertTrue(session.archiveEntity(Entities.PRODUCT, updatedEntity.getPrimaryKey()));
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());

				final Optional<SealedEntity> loadedArchivedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
				);
				assertTrue(loadedArchivedEntity.isPresent());
				assertEquals(Scope.ARCHIVED, loadedArchivedEntity.get().getScope());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldArchiveAndFetchExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final SealedEntity archivedEntity = session.archiveEntity(
					Entities.PRODUCT, updatedEntity.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertSomeNewProductContent(archivedEntity);
				assertEquals(Scope.ARCHIVED, archivedEntity.getScope());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());

				final Optional<SealedEntity> loadedArchivedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
				);
				assertTrue(loadedArchivedEntity.isPresent());
				assertEquals(Scope.ARCHIVED, loadedArchivedEntity.get().getScope());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldArchiveAndFetchExistingCustomEntity(
		EvitaClient evitaClient, Map<Integer, SealedEntity> originalCategories) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final ProductInterface archivedEntity = session.archiveEntity(
					ProductInterface.class, updatedEntity.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertProduct(updatedEntity, archivedEntity, originalCategories);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<ProductInterface> loadedEntity = session.getEntity(
					ProductInterface.class, newProductId.get(), entityFetchAllContent()
				);
				assertFalse(loadedEntity.isPresent());

				final Optional<SealedEntity> loadedArchivedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
				);
				assertTrue(loadedArchivedEntity.isPresent());
				assertEquals(Scope.ARCHIVED, loadedArchivedEntity.get().getScope());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldRestoreExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());
				session.archiveEntity(Entities.PRODUCT, updatedEntity.getPrimaryKey());
			}
		);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.PRODUCT, newProductId.get());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertTrue(loadedEntity.isPresent());
				assertEquals(Scope.LIVE, loadedEntity.get().getScope());
			}
		);
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldRestoreAndFetchExistingEntity(EvitaClient evitaClient) {
		final AtomicInteger newProductId = new AtomicInteger();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKey());

				final SealedEntity archivedEntity = session.archiveEntity(
					Entities.PRODUCT, updatedEntity.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertSomeNewProductContent(archivedEntity);
				assertEquals(Scope.ARCHIVED, archivedEntity.getScope());
			}
		);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity restoredEntity = session.restoreEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				).orElseThrow();

				assertSomeNewProductContent(restoredEntity);
				assertEquals(Scope.LIVE, restoredEntity.getScope());
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> loadedEntity = session.getEntity(
					Entities.PRODUCT, newProductId.get(), entityFetchAllContent()
				);
				assertTrue(loadedEntity.isPresent());
				assertEquals(Scope.LIVE, loadedEntity.get().getScope());
			}
		);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldRestoreAndFetchExistingCustomEntity(
		EvitaClient evitaClient, Map<Integer, SealedEntity> originalCategories
	) {
		final AtomicInteger newProductId = new AtomicInteger();
		final AtomicReference<SealedEntity> theEntity = new AtomicReference<>();
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityMutation entityMutation = createSomeNewProduct(session);

				final SealedEntity updatedEntity = session.upsertAndFetchEntity(
					entityMutation, entityFetchAll().getRequirements()
				);
				newProductId.set(updatedEntity.getPrimaryKeyOrThrowException());
				theEntity.set(updatedEntity);

				final ProductInterface archivedEntity = session.archiveEntity(
					ProductInterface.class, updatedEntity.getPrimaryKeyOrThrowException(), entityFetchAllContent()
				).orElseThrow();

				assertProduct(updatedEntity, archivedEntity, originalCategories);
			}
		);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				final ProductInterface restoredEntity = session.restoreEntity(
					ProductInterface.class, newProductId.get(), entityFetchAllContent()
				).orElseThrow();

				assertProduct(theEntity.get(), restoredEntity, originalCategories);
			}
		);

		evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<ProductInterface> loadedEntity = session.getEntity(
					ProductInterface.class, newProductId.get(), entityFetchAllContent()
				);
				assertTrue(loadedEntity.isPresent());
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
	void shouldDeleteAndFetchExistingCustomEntity(
		EvitaClient evitaClient, Map<Integer, SealedEntity> originalCategories) {
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
				assertEquals(
					"New category #50",
					deletedHierarchy.deletedRootEntity().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
				);
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
							                                                               .containerType(
								                                                               ContainerType.ENTITY)
							                                                               .build()
						                                                       )
						                                                       .build()
					                           )
					                           .content(ChangeCaptureContent.BODY)
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
		final List<CompletableFuture<CompletableFuture<FileForFetch>>> backupTasks = Stream
			.generate(
				() -> CompletableFuture.supplyAsync(
					() -> management.backupCatalog(TEST_CATALOG, null, null, true),
					executorService
				)
			)
			.limit(numberOfTasks)
			.toList();

		// Optional: Wait for all tasks to complete
		CompletableFuture.allOf(backupTasks.toArray(new CompletableFuture[0])).join();
		executorService.shutdown();

		management.listTaskStatuses(1, numberOfTasks, null);

		// cancel 7 of them immediately
		final List<Boolean> cancellationResult = Stream
			.concat(
				management.listTaskStatuses(1, 1, null)
				          .getData()
				          .stream()
				          .map(it -> management.cancelTask(it.taskId())),
				backupTasks.subList(3, numberOfTasks - 1)
				           .stream()
				           .map(task -> task.getNow(null).cancel(true))
			)
			.toList();

		// wait for all task to complete
		try {
			// wait for all task to complete
			CompletableFuture.allOf(
				backupTasks.stream().map(it -> it.getNow(null)).toArray(CompletableFuture[]::new)
			).get(3, TimeUnit.MINUTES);
		} catch (ExecutionException ignored) {
			// if tasks were cancelled, they will throw exception
		} catch (InterruptedException | TimeoutException e) {
			fail(e);
		}

		final PaginatedList<TaskStatus<?, ?>> taskStatuses = management.listTaskStatuses(1, numberOfTasks, null);
		assertEquals(numberOfTasks, taskStatuses.getTotalRecordCount());
		final int cancelled = cancellationResult.stream().mapToInt(b -> b ? 1 : 0).sum();
		assertEquals(
			backupTasks.size() - cancelled,
			taskStatuses.getData()
			            .stream()
			            .filter(
				            task -> task.simplifiedState() == TaskSimplifiedState.FINISHED)
			            .count()
		);
		assertEquals(
			cancelled,
			taskStatuses.getData()
			            .stream()
			            .filter(task -> task.simplifiedState() == TaskSimplifiedState.FAILED)
			            .count()
		);

		// fetch all tasks by their ids
		management.getTaskStatuses(
			taskStatuses.getData().stream().map(TaskStatus::taskId).toArray(UUID[]::new)
		).forEach(Assertions::assertNotNull);

		// fetch tasks individually
		taskStatuses.getData().forEach(task -> assertNotNull(management.getTaskStatus(task.taskId())));

		// list exported files
		final PaginatedList<FileForFetch> exportedFiles = management.listFilesToFetch(1, numberOfTasks, Set.of());
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
		final PaginatedList<FileForFetch> exportedFilesAfterDeletion = management.listFilesToFetch(1, numberOfTasks, Set.of());
		assertTrue(exportedFilesAfterDeletion.getData().stream().noneMatch(file -> deletedFiles.contains(file.fileId())));
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_DATA_SET, destroyAfterTest = true)
	void shouldListReservedKeywords(EvitaClient evitaClient) throws ExecutionException, InterruptedException {
		final EvitaManagementServiceFutureStub managementStub = getManagementStubInternal(evitaClient);
		final GrpcReservedKeywordsResponse keywords = managementStub.listReservedKeywords(Empty.newBuilder().build())
		                                                            .get();
		assertNotNull(keywords);
		assertTrue(keywords.getKeywordsCount() > 20);
	}

	@Test
	@UseDataSet(value = EVITA_CLIENT_EMPTY_DATA_SET, destroyAfterTest = true)
	void shouldVerifyDifferentScopeSettingsForSchemaAndLookups(EvitaClient evitaClient) {
		/* create schema for entity archival */
		final Scope[] scopes = new Scope[]{Scope.LIVE, Scope.ARCHIVED};
		evitaClient.defineCatalog(TEST_CATALOG)
		           .withAttribute(
			           ATTRIBUTE_CODE, String.class,
			           thatIs -> thatIs.uniqueGloballyInScope(scopes).sortableInScope(scopes)
		           )
		           .updateViaNewSession(evitaClient);

		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
				       .withoutGeneratedPrimaryKey()
				       .withGlobalAttribute(ATTRIBUTE_CODE)
				       .withReflectedReferenceToEntity(
					       "products",
					       Entities.PRODUCT,
					       Entities.CATEGORY,
					       whichIs -> whichIs.indexedInScope(scopes).withAttributesInherited()
				       )
				       .withHierarchy()
				       .updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
				       .withoutGeneratedPrimaryKey()
				       .withGlobalAttribute(ATTRIBUTE_CODE)
				       .withAttribute(
					       ATTRIBUTE_NAME, String.class,
					       thatIs -> thatIs.localized().filterableInScope(scopes).sortableInScope(scopes)
				       )
				       .withSortableAttributeCompound(
					       ATTRIBUTE_CODE_NAME,
					       new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					       new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				       )
				       .withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
				       .withReferenceToEntity(
					       Entities.CATEGORY,
					       Entities.CATEGORY,
					       Cardinality.ZERO_OR_MORE,
					       thatIs -> thatIs
						       .indexedInScope(scopes)
						       .withAttribute(
							       ATTRIBUTE_CATEGORY_MARKET, String.class,
							       whichIs -> whichIs.filterableInScope(scopes).sortableInScope(scopes)
						       )
						       .withAttribute(
							       ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(scopes))
						       .withSortableAttributeCompound(
							       ATTRIBUTE_CATEGORY_MARKET_OPEN,
							       new AttributeElement(
								       ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							       new AttributeElement(
								       ATTRIBUTE_CATEGORY_OPEN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						       )
				       )
				       .updateVia(session);
			}
		);

		// upsert entities product depends on
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, 1)
				       .setAttribute(ATTRIBUTE_CODE, "electronics")
				       .upsertVia(session);

				session.createNewEntity(Entities.CATEGORY, 2)
				       .setParent(1)
				       .setAttribute(ATTRIBUTE_CODE, "TV")
				       .upsertVia(session);
			}
		);

		// create product entity
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
				       .setAttribute(ATTRIBUTE_CODE, "TV-123")
				       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
				       .setReference(
					       Entities.CATEGORY, 2,
					       whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
					                                       .setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
				       )
				       .setPrice(
					       1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"),
					       new BigDecimal("121"), true
				       )
				       .setPrice(
					       1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"),
					       new BigDecimal("12.1"), true
				       )
				       .upsertVia(session);
			}
		);

		// check category has reflected reference to product
		final SealedEntity category = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				              .orElse(null);
			}
		);
		assertNotNull(category);
		final ReferenceContract products = category.getReference("products", 100).orElse(null);
		assertNotNull(products);
		assertEquals("EU", products.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

		// archive product entity
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check category has no reflected reference to product
		final SealedEntity categoryAfterArchiving = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				              .orElse(null);
			}
		);
		assertNotNull(categoryAfterArchiving);
		final ReferenceContract productsAfterArchiving = categoryAfterArchiving.getReference("products", 100).orElse(
			null);
		assertNotNull(productsAfterArchiving);

		// archive category entity
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.CATEGORY, 2);
			}
		);

		// check archived category has reflected reference to product
		final SealedEntity archivedCategory = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOne(
					Query.query(
						collection(Entities.CATEGORY),
						filterBy(
							scope(Scope.ARCHIVED),
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetchAll()
						)
					),
					SealedEntity.class
				).orElse(null);
			}
		);

		assertNotNull(archivedCategory);
		final ReferenceContract archivedProducts = archivedCategory.getReference("products", 100).orElse(null);
		assertNotNull(archivedProducts);
		assertEquals("EU", archivedProducts.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

		// restore both category and product entity
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.CATEGORY, 2);
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check restored category has reflected reference to product again
		final SealedEntity categoryAfterRestore = evitaClient.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				              .orElse(null);
			}
		);
		assertNotNull(categoryAfterRestore);
		final ReferenceContract productsAfterRestore = categoryAfterRestore.getReference("products", 100).orElse(null);
		assertNotNull(productsAfterRestore);
		assertEquals("EU", productsAfterRestore.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldCancelCatalogChangeSubscriberAndEvictPublisherOnServerSide(EvitaClient evitaClient, Evita evita) {
		final String testCatalogName = "testCatalogForCancellation";

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

			final CatalogChangeObserver changeObserver = (CatalogChangeObserver)
				evita.getCatalogInstance(testCatalogName)
				     .map(Catalog.class::cast)
				     .orElseThrow()
				     .getTransactionManager()
				     .getChangeObserver();

			final int initialUniquePublishersCount = changeObserver.getUniquePublishersCount();

			final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(Integer.MAX_VALUE);

			// Register catalog change capture and get the subscription
			evitaClient.updateCatalog(
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

					// Create an entity collection to trigger a change event
					session.defineEntitySchema(Entities.BRAND)
					       .updateVia(session);

					return null;
				}
			);

			assertEquals(initialUniquePublishersCount + 1, changeObserver.getUniquePublishersCount());

			// Verify that the subscriber received the event
			assertEquals(1, catalogSubscriber.getEntityCollectionCreated(Entities.BRAND, 10, TimeUnit.SECONDS, 1));

			// Cancel the subscription from client side
			catalogSubscriber.cancel();

			// Try to create another entity collection - this should not be received by the cancelled subscriber
			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema("AnotherEntity")
						.updateVia(session);
					return null;
				}
			);

			// Verify that the subscriber did not receive the second event (still only 1 event for BRAND)
			assertEquals(1, catalogSubscriber.getEntityCollectionCreated(Entities.BRAND));
			assertEquals(0, catalogSubscriber.getEntityCollectionCreated("AnotherEntity"));

			// check that the server discarded particular publisher
			assertEquals(initialUniquePublishersCount, changeObserver.getUniquePublishersCount());

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldTransitionCatalogFromWarmUpToLiveMode(EvitaClient evitaClient) {
		final String testCatalogName = "testGoLiveCatalog";
		try {
			// Create a catalog with some schema
			evitaClient.defineCatalog(testCatalogName)
			           .withDescription("Test catalog for go-live transition.")
			           // define a simple entity schema
			           .withEntitySchema(
				           "TestEntity",
				           whichIs -> whichIs.withDescription("A test entity for go-live transition.")
				                             .withAttribute(
					                             "name", String.class,
					                             thatIs -> thatIs.localized().filterable().sortable()
				                             )
				                             .withAttribute(
					                             "code", String.class,
					                             AttributeSchemaEditor::unique
				                             )
			           )
			           // push the definitions to the server
			           .updateViaNewSession(evitaClient);

			// Verify catalog was created
			assertTrue(evitaClient.getCatalogNames().contains(testCatalogName));

			// Open a session and transition catalog from warm-up to live mode
			final Progress<CommitVersions> goLiveProgress = evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					// Verify session is active before calling goLiveAndCloseWithProgress
					assertTrue(session.isActive());
					assertEquals(CatalogState.WARMING_UP, session.getCatalogState());

					// Call goLiveAndCloseWithProgress - this should close the session immediately
					// and return a progress object to monitor the transition
					return session.goLiveAndCloseWithProgress(
						progress -> {
							// Optional progress observer - log progress if needed
							System.out.println("Go-live progress: " + progress + "%");
						}
					);
				}
			);

			// Verify that the progress object is not null
			assertNotNull(goLiveProgress);

			// Wait for the go-live operation to complete
			goLiveProgress.onCompletion().toCompletableFuture().join();

			// Verify that the catalog is now in live mode by opening a new session
			evitaClient.queryCatalog(testCatalogName, session -> {
				// Verify we can still access the schema
				final Set<String> allEntityTypes = session.getAllEntityTypes();
				assertTrue(allEntityTypes.contains("TestEntity"));
				assertEquals(CatalogState.ALIVE, session.getCatalogState());

				// Verify the entity schema is accessible
				final SealedEntitySchema entitySchema = session.getEntitySchemaOrThrowException("TestEntity");
				assertEquals("A test entity for go-live transition.", entitySchema.getDescription());
				assertTrue(entitySchema.getAttribute("name").isPresent());
				assertTrue(entitySchema.getAttribute("code").isPresent());
			});

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldActivateCatalogFromInactiveState(EvitaClient evitaClient) {
		final String testCatalogName = "testActivationCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Verify catalog is alive
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Deactivate the catalog
			evitaClient.deactivateCatalog(testCatalogName);

			// Verify catalog is inactive
			assertEquals(CatalogState.INACTIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Activate the catalog again
			evitaClient.activateCatalog(testCatalogName);

			// Verify catalog is alive again
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Verify data is still accessible
			evitaClient.queryCatalog(
				testCatalogName,
				session -> {
					final Optional<SealedEntity> product = session.getEntity(
						Entities.PRODUCT, 1, entityFetchAllContent()
					);
					assertTrue(product.isPresent());
					assertEquals("Test Product", product.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					return null;
				}
			);

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldActivateCatalogWithProgress(EvitaClient evitaClient) {
		final String testCatalogName = "testActivationProgressCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Deactivate the catalog first
			evitaClient.deactivateCatalog(testCatalogName);
			assertEquals(CatalogState.INACTIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Activate the catalog with progress tracking
			final Progress<Void> activateProgress = evitaClient.activateCatalogWithProgress(testCatalogName);
			assertNotNull(activateProgress);

			// Wait for completion
			activateProgress.onCompletion().toCompletableFuture().join();

			// Verify catalog is alive again
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDeactivateCatalogWithProgress(EvitaClient evitaClient) {
		final String testCatalogName = "testDeactivationProgressCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Verify catalog is alive
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Deactivate the catalog with progress tracking
			final Progress<Void> deactivateProgress = evitaClient.deactivateCatalogWithProgress(testCatalogName);
			assertNotNull(deactivateProgress);

			// Wait for completion
			deactivateProgress.onCompletion().toCompletableFuture().join();

			// Verify catalog is inactive
			assertEquals(CatalogState.INACTIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldMakeCatalogMutableFromImmutableState(EvitaClient evitaClient) {
		final String testCatalogName = "testMutabilityCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Verify catalog is alive and mutable (write operations work)
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Make catalog immutable
			evitaClient.makeCatalogImmutable(testCatalogName);

			// Verify write operations throw ReadOnlyException when catalog is immutable
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evitaClient.updateCatalog(
					testCatalogName,
					session -> {
						session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 2)
								.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
						);
					}
				)
			);

			// Make catalog mutable again
			evitaClient.makeCatalogMutable(testCatalogName);

			// Verify write operations work when catalog is mutable
			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
					);
				}
			);

			// Verify both entities are accessible
			evitaClient.queryCatalog(
				testCatalogName,
				session -> {
					final Optional<SealedEntity> product1 = session.getEntity(
						Entities.PRODUCT, 1, entityFetchAllContent()
					);
					final Optional<SealedEntity> product2 = session.getEntity(
						Entities.PRODUCT, 2, entityFetchAllContent()
					);
					assertTrue(product1.isPresent());
					assertTrue(product2.isPresent());
					assertEquals("Test Product", product1.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("Another Product", product2.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					return null;
				}
			);

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldMakeCatalogMutableWithProgress(EvitaClient evitaClient) {
		final String testCatalogName = "testMutableProgressCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Make catalog immutable first
			evitaClient.makeCatalogImmutable(testCatalogName);

			// Make catalog mutable with progress tracking
			final Progress<Void> makeMutableProgress = evitaClient.makeCatalogMutableWithProgress(testCatalogName);
			assertNotNull(makeMutableProgress);

			// Wait for completion
			makeMutableProgress.onCompletion().toCompletableFuture().join();

			// Verify write operations work when catalog is mutable
			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
					);
				}
			);

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldMakeCatalogImmutableWithProgress(EvitaClient evitaClient) {
		final String testCatalogName = "testImmutableProgressCatalog";
		try {
			// Create and setup a catalog with some data
			evitaClient.defineCatalog(testCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				testCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
					);
					session.goLiveAndClose();
				}
			);

			// Verify catalog is alive and mutable
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(testCatalogName).orElseThrow());

			// Make catalog immutable with progress tracking
			final Progress<Void> makeImmutableProgress = evitaClient.makeCatalogImmutableWithProgress(testCatalogName);
			assertNotNull(makeImmutableProgress);

			// Wait for completion
			makeImmutableProgress.onCompletion().toCompletableFuture().join();

			// Verify write operations throw ReadOnlyException when catalog is immutable
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evitaClient.updateCatalog(
					testCatalogName,
					session -> {
						session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 2)
								.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
						);
					}
				)
			);

		} finally {
			// Clean up the test catalog
			evitaClient.deleteCatalogIfExists(testCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDuplicateCatalog(EvitaClient evitaClient) {
		final String sourceCatalogName = "sourceCatalogForDuplication";
		final String duplicatedCatalogName = "duplicatedCatalog";

		try {
			// Create and setup a source catalog with some data
			evitaClient.defineCatalog(sourceCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				sourceCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Category 1")
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Category 2")
					);
					session.goLiveAndClose();
				}
			);

			// Duplicate the catalog using the public API
			evitaClient.duplicateCatalog(sourceCatalogName, duplicatedCatalogName);

			// Verify that the duplicated catalog exists and is in INACTIVE state
			assertTrue(evitaClient.getCatalogNames().contains(duplicatedCatalogName));
			assertEquals(CatalogState.INACTIVE, evitaClient.getCatalogState(duplicatedCatalogName).orElse(null));

			// Activate the duplicated catalog
			evitaClient.activateCatalog(duplicatedCatalogName);

			// Verify that the duplicated catalog is now active
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(duplicatedCatalogName).orElse(null));

			// Query data in the duplicated catalog to verify it contains the same data as the original
			evitaClient.queryCatalog(
				duplicatedCatalogName,
				session -> {
					// Verify that the product entity exists with the expected data
					final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Product entity should exist in duplicated catalog"));

					assertEquals("The product", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));

					// Verify that category entities exist
					final SealedEntity category1 = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Category 1 should exist in duplicated catalog"));
					final SealedEntity category2 = session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Category 2 should exist in duplicated catalog"));

					assertNotNull(category1);
					assertNotNull(category2);

					// Verify that we can query entities
					final List<SealedEntity> products = session.queryList(
						query(
							collection(Entities.PRODUCT),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
					assertEquals(1, products.size());

					final List<SealedEntity> categories = session.queryList(
						query(
							collection(Entities.CATEGORY),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
					assertEquals(2, categories.size());
					return null;
				}
			);

		} finally {
			// Clean up the test catalogs
			evitaClient.deleteCatalogIfExists(sourceCatalogName);
			evitaClient.deleteCatalogIfExists(duplicatedCatalogName);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldDuplicateCatalogWithProgress(EvitaClient evitaClient) {
		final String sourceCatalogName = "sourceCatalogForProgressDuplication";
		final String duplicatedCatalogName = "duplicatedProgressCatalog";

		try {
			// Create and setup a source catalog with some data
			evitaClient.defineCatalog(sourceCatalogName)
				.updateViaNewSession(evitaClient);

			evitaClient.updateCatalog(
				sourceCatalogName,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Category 1")
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Category 2")
					);
					session.goLiveAndClose();
				}
			);

			// Duplicate the catalog with progress tracking
			final Progress<Void> duplicateProgress = evitaClient.duplicateCatalogWithProgress(sourceCatalogName, duplicatedCatalogName);
			assertNotNull(duplicateProgress);

			// Wait for completion
			duplicateProgress.onCompletion().toCompletableFuture().join();

			// Verify that the duplicated catalog exists and is in INACTIVE state
			assertTrue(evitaClient.getCatalogNames().contains(duplicatedCatalogName));
			assertEquals(CatalogState.INACTIVE, evitaClient.getCatalogState(duplicatedCatalogName).orElse(null));

			// Activate the duplicated catalog
			evitaClient.activateCatalog(duplicatedCatalogName);

			// Verify that the duplicated catalog is now active
			assertEquals(CatalogState.ALIVE, evitaClient.getCatalogState(duplicatedCatalogName).orElse(null));

			// Query data in the duplicated catalog to verify it contains the same data as the original
			evitaClient.queryCatalog(
				duplicatedCatalogName,
				session -> {
					// Verify that the product entity exists with the expected data
					final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Product entity should exist in duplicated catalog"));

					assertEquals("The product", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));

					// Verify that category entities exist
					final SealedEntity category1 = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Category 1 should exist in duplicated catalog"));
					final SealedEntity category2 = session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElseThrow(() -> new AssertionError("Category 2 should exist in duplicated catalog"));

					assertNotNull(category1);
					assertNotNull(category2);

					// Verify that we can query entities
					final List<SealedEntity> products = session.queryList(
						query(
							collection(Entities.PRODUCT),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
					assertEquals(1, products.size());

					final List<SealedEntity> categories = session.queryList(
						query(
							collection(Entities.CATEGORY),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
					assertEquals(2, categories.size());
					return null;
				}
			);

		} finally {
			// Clean up the test catalogs
			evitaClient.deleteCatalogIfExists(sourceCatalogName);
			evitaClient.deleteCatalogIfExists(duplicatedCatalogName);
		}
	}


	@Test
	@DisplayName("CDC publishers for different catalogs with identical request criteria should be independent")
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	void shouldNotLeakCdcEventsBetweenCatalogsWithIdenticalCaptureRequests(EvitaClient evitaClient) {
		final String catalogA = "testCatalogCdcA";
		final String catalogB = "testCatalogCdcB";

		try {
			// create two separate catalogs and make them go live
			evitaClient.defineCatalog(catalogA);
			evitaClient.updateCatalog(catalogA, session -> {
				session.goLiveAndClose();
				return null;
			});

			evitaClient.defineCatalog(catalogB);
			evitaClient.updateCatalog(catalogB, session -> {
				session.goLiveAndClose();
				return null;
			});

			// build identical CDC request criteria for both catalogs
			final ChangeCatalogCaptureRequest identicalRequest = ChangeCatalogCaptureRequest
				.builder()
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeCatalogCaptureCriteria
						.builder()
						.schemaArea()
						.build()
				)
				.build();

			final MockCatalogChangeCaptureSubscriber subscriberA =
				new MockCatalogChangeCaptureSubscriber(Integer.MAX_VALUE);
			final MockCatalogChangeCaptureSubscriber subscriberB =
				new MockCatalogChangeCaptureSubscriber(Integer.MAX_VALUE);

			// register CDC on catalog A
			evitaClient.updateCatalog(catalogA, session -> {
				session.registerChangeCatalogCapture(identicalRequest)
					.subscribe(subscriberA);
				return null;
			});

			// register CDC on catalog B with the same request
			evitaClient.updateCatalog(catalogB, session -> {
				session.registerChangeCatalogCapture(identicalRequest)
					.subscribe(subscriberB);
				return null;
			});

			// trigger schema change only in catalog A
			evitaClient.updateCatalog(catalogA, session -> {
				session.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				return null;
			});

			// subscriber A should receive the event
			assertEquals(
				1,
				subscriberA.getEntityCollectionCreated(Entities.BRAND, 10, TimeUnit.SECONDS, 1),
				"Subscriber on catalog A should receive schema change from catalog A"
			);

			// subscriber B must NOT receive the event from catalog A
			assertEquals(
				0,
				subscriberB.getEntityCollectionCreated(Entities.BRAND),
				"Subscriber on catalog B must not receive events from catalog A"
			);

			// trigger schema change only in catalog B
			evitaClient.updateCatalog(catalogB, session -> {
				session.defineEntitySchema(Entities.STORE)
					.updateVia(session);
				return null;
			});

			// subscriber B should receive its own event
			assertEquals(
				1,
				subscriberB.getEntityCollectionCreated(Entities.STORE, 10, TimeUnit.SECONDS, 1),
				"Subscriber on catalog B should receive schema change from catalog B"
			);

			// subscriber A must NOT receive the event from catalog B
			assertEquals(
				0,
				subscriberA.getEntityCollectionCreated(Entities.STORE),
				"Subscriber on catalog A must not receive events from catalog B"
			);
		} finally {
			evitaClient.deleteCatalogIfExists(catalogA);
			evitaClient.deleteCatalogIfExists(catalogB);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Tag(TRANSACTION)
	@Tag(ATTRIBUTE)
	@DisplayName("A single failed upsert is swallowed while surrounding upserts and removals still commit")
	void shouldCommitSurvivingMutationsWhenOneUpsertFailsMidTransaction(EvitaClient evitaClient) {
		try {
			// fresh ALIVE catalog carrying a unique `code`, seeded with product #1 (code "A") — see setup helper
			setupAtomicRollbackCatalog(evitaClient);

			// seed two more entities (#7, #8) that will be removed inside the batch transaction below
			evitaClient.updateCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 7).setAttribute(ATTRIBUTE_CODE, "G")
					);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 8).setAttribute(ATTRIBUTE_CODE, "H")
					);
				}
			);

			// one transaction mixing upserts and removals; the duplicate-code upsert (#3) throws mid-way over gRPC,
			// is caught by the client, and the server-side savepoint must revert only #3 so that every other mutation
			// still commits — proving a single exception doesn't corrupt the transaction from the client's viewpoint
			evitaClient.updateCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
					);

					assertTrue(
						session.deleteEntity(Entities.PRODUCT, 7),
						"Existing entity #7 should be removed inside the transaction!"
					);

					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.upsertEntity(
							// duplicate of #1's unique code → the server rejects it after partially touching the indexes
							session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
						),
						"The duplicate unique code must be rejected by the server!"
					);
					assertTrue(
						exception.getMessage().contains("Unique constraint violation"),
						"Unexpected exception message: " + exception.getMessage()
					);

					assertTrue(
						session.deleteEntity(Entities.PRODUCT, 8),
						"Existing entity #8 should be removed inside the transaction!"
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 4).setAttribute(ATTRIBUTE_CODE, "C")
					);
				}
			);

			// the transaction committed: every non-failing mutation is visible, #3 was fully reverted
			evitaClient.queryCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 1).isPresent(), "Pre-seeded #1 must survive!");
					assertTrue(session.getEntity(Entities.PRODUCT, 2).isPresent(), "Upserted #2 must be committed!");
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent(), "Reverted #3 must not exist!");
					assertTrue(session.getEntity(Entities.PRODUCT, 4).isPresent(), "Upserted #4 must be committed!");
					assertFalse(session.getEntity(Entities.PRODUCT, 7).isPresent(), "Removed #7 must be gone!");
					assertFalse(session.getEntity(Entities.PRODUCT, 8).isPresent(), "Removed #8 must be gone!");
				}
			);

			// the unique index stays consistent — surviving codes resolve to their owners, the reverted code "A" still
			// points only at #1, and the codes owned by the removed entities resolve to nothing
			assertCodeResolvesTo(evitaClient, "A", 1);
			assertCodeResolvesTo(evitaClient, "B", 2);
			assertCodeResolvesTo(evitaClient, "C", 4);
			assertCodeResolvesToNothing(evitaClient, "G");
			assertCodeResolvesToNothing(evitaClient, "H");
		} finally {
			evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Tag(TRANSACTION)
	@Tag(REFERENCE)
	@Tag(FACET)
	@Tag(PRICE)
	@DisplayName("A reverted entity leaves no orphan facet or phantom price entry across the gRPC boundary")
	void shouldLeaveNoIndexResidueWhenEntityFailsMidTransaction(EvitaClient evitaClient) {
		try {
			setupAtomicRollbackCatalog(evitaClient);

			// #2 and #4 legitimately reference brand #100 and publish a sellable basic/CZK price; #3 does the same but
			// carries the duplicate code "A", so its reference/facet and price index entries are written first, then the
			// unique check throws. The savepoint must scrub #3's tentative index entries before the transaction commits.
			evitaClient.updateCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_CODE, "B")
							.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
					);

					assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 3)
								.setAttribute(ATTRIBUTE_CODE, "A")
								.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
								.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
								.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
						),
						"The duplicate unique code must be rejected by the server!"
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 4)
							.setAttribute(ATTRIBUTE_CODE, "C")
							.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
					);
				}
			);

			evitaClient.queryCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 2).isPresent(), "Upserted #2 must be committed!");
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent(), "Reverted #3 must not exist!");
					assertTrue(session.getEntity(Entities.PRODUCT, 4).isPresent(), "Upserted #4 must be committed!");
				}
			);

			// the brand facet and the basic/CZK price index must resolve to exactly {#2, #4}; a leaked entry for the
			// reverted #3 would surface here as an extra primary key
			assertBrandFacetResolvesExactlyTo(evitaClient, 2, 4);
			assertPricedProductsAreExactly(evitaClient, 2, 4);
		} finally {
			evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Tag(TRANSACTION)
	@Tag(ATTRIBUTE)
	@DisplayName("An exception escaping the update block uncaught discards the whole transaction")
	void shouldRollBackWholeTransactionWhenUpdateCatalogBlockThrowsUncaught(EvitaClient evitaClient) {
		try {
			// fresh ALIVE catalog carrying a unique `code`, seeded with product #1 (code "A") — see setup helper
			setupAtomicRollbackCatalog(evitaClient);

			// a single transaction upserts #2 successfully and then throws an unrelated exception that nothing catches;
			// the escape flips the root frame to rollback-only, so even the already-sent #2 is discarded. This isolates
			// the client-driven full rollback: a non-mutation escape means the server savepoint plays no role, only the
			// client's root-frame rollback flag reaching the server discards the already-sent #2
			assertThrows(
				IllegalStateException.class,
				() -> evitaClient.updateCatalog(
					ATOMIC_ROLLBACK_CATALOG,
					// the always-throwing body is compatible with both updateCatalog overloads, so pin it to the
					// Consumer form to keep the block void
					(Consumer<EvitaSessionContract>) session -> {
						session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
						);
						throw new IllegalStateException("boom");
					}
				),
				"The exception escaping the update block must propagate to the caller!"
			);

			// the whole transaction was discarded: the successfully upserted #2 is gone, the pre-seeded #1 survives
			evitaClient.queryCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 1).isPresent(), "Pre-seeded #1 must survive!");
					assertFalse(session.getEntity(Entities.PRODUCT, 2).isPresent(), "Rolled-back #2 must not exist!");
				}
			);
			assertCodeResolvesTo(evitaClient, "A", 1);
			assertCodeResolvesToNothing(evitaClient, "B");
		} finally {
			evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Tag(TRANSACTION)
	@Tag(ATTRIBUTE)
	@DisplayName("A caught mutation failure at the root frame of a direct session poisons the whole transaction")
	void shouldPoisonWholeTransactionWhenMutationFailsInDirectReadWriteSession(EvitaClient evitaClient) {
		try {
			setupAtomicRollbackCatalog(evitaClient);

			// a direct read-write session has no surrounding update block, so every mutation runs at the root frame
			// (nesting level 1). The caught duplicate-code failure therefore poisons the whole transaction — 1:1 with
			// embedded direct-session usage, where catch-and-continue is a block-only capability — so both the mutation
			// before it (#2) and the one after it (#4) are discarded when the session closes
			try (final EvitaSessionContract session = evitaClient.createReadWriteSession(ATOMIC_ROLLBACK_CATALOG)) {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
				);

				assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.upsertEntity(
						// duplicate of #1's unique code — rejected by the server at the root frame, poisoning the transaction
						session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
					),
					"The duplicate unique code must be rejected by the server!"
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 4).setAttribute(ATTRIBUTE_CODE, "C")
				);
			} catch (RuntimeException discardedOnClose) {
				// closing a poisoned transaction discards every mutation and may surface the rollback as an exception
				// here — tolerated because the discard itself is the behaviour under test, verified by the query below
			}

			// the whole transaction was discarded: neither #2 nor #4 committed, while the pre-seeded #1 survives
			evitaClient.queryCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 1).isPresent(), "Pre-seeded #1 must survive!");
					assertFalse(session.getEntity(Entities.PRODUCT, 2).isPresent(), "Discarded #2 must not exist!");
					assertFalse(session.getEntity(Entities.PRODUCT, 4).isPresent(), "Discarded #4 must not exist!");
				}
			);
			assertCodeResolvesTo(evitaClient, "A", 1);
			assertCodeResolvesToNothing(evitaClient, "B");
			assertCodeResolvesToNothing(evitaClient, "C");
		} finally {
			evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		}
	}

	@Test
	@UseDataSet(EVITA_CLIENT_DATA_SET)
	@Tag(TRANSACTION)
	@Tag(ATTRIBUTE)
	@DisplayName("Requesting rollback before closing a direct session with progress discards every mutation")
	void shouldRollBackWholeTransactionViaCloseWithProgressWhenRollbackRequested(EvitaClient evitaClient) {
		try {
			setupAtomicRollbackCatalog(evitaClient);

			// open a direct session, upsert #2 successfully, then request an explicit rollback and close through the
			// progress-streaming path — this drives the closeNowWithProgress() rollback capture and the server-side
			// close-with-progress rollback handler, a path distinct from the plain close request
			final EvitaSessionContract session = evitaClient.createReadWriteSession(ATOMIC_ROLLBACK_CATALOG);
			session.upsertEntity(
				session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
			);
			session.setRollbackOnly();

			final CommitProgress progress = session.closeNowWithProgress();
			// await the terminal visibility stage so the requested rollback is fully processed server-side before the
			// verifying query; a user-requested rollback settles the stage exceptionally, which we deliberately absorb
			progress.onChangesVisible().toCompletableFuture().handle((versions, throwable) -> null).join();

			// the whole transaction was discarded: the upserted #2 is gone, the pre-seeded #1 survives
			evitaClient.queryCatalog(
				ATOMIC_ROLLBACK_CATALOG,
				readSession -> {
					assertTrue(readSession.getEntity(Entities.PRODUCT, 1).isPresent(), "Pre-seeded #1 must survive!");
					assertFalse(readSession.getEntity(Entities.PRODUCT, 2).isPresent(), "Rolled-back #2 must not exist!");
				}
			);
			assertCodeResolvesTo(evitaClient, "A", 1);
			assertCodeResolvesToNothing(evitaClient, "B");
		} finally {
			evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		}
	}

	/**
	 * Creates a fresh, ALIVE {@link #ATOMIC_ROLLBACK_CATALOG} carrying a product schema with a unique {@code code}
	 * attribute, an indexed and faceted {@code brand} reference and an indexed price. It seeds brand #100 and product
	 * #1 (unique code "A") in warm-up mode, then flips the catalog to ALIVE so subsequent live writes flow through the
	 * transactional diff-layer path where the per-entity savepoint operates and can collide on the unique code.
	 *
	 * @param evitaClient the gRPC client used to define and seed the catalog, must not be null
	 */
	private static void setupAtomicRollbackCatalog(@Nonnull EvitaClient evitaClient) {
		evitaClient.deleteCatalogIfExists(ATOMIC_ROLLBACK_CATALOG);
		evitaClient.defineCatalog(ATOMIC_ROLLBACK_CATALOG);
		evitaClient.updateCatalog(
			ATOMIC_ROLLBACK_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
					.withReferenceToEntity(
						REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexed().faceted()
					)
					.withPrice()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, BRAND_PRIMARY_KEY));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "A")
				);
			}
		);
		// flip the catalog to ALIVE so subsequent writes go through the transactional diff-layer path
		evitaClient.updateCatalog(ATOMIC_ROLLBACK_CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Asserts that querying the unique {@link #ATTRIBUTE_CODE} attribute in {@link #ATOMIC_ROLLBACK_CATALOG} for the
	 * given value resolves to exactly the expected product primary key.
	 *
	 * @param evitaClient        the gRPC client to query with, must not be null
	 * @param code               the unique attribute value to look up, must not be null
	 * @param expectedPrimaryKey the primary key the value must resolve to
	 */
	private static void assertCodeResolvesTo(@Nonnull EvitaClient evitaClient, @Nonnull String code, int expectedPrimaryKey) {
		evitaClient.queryCatalog(
			ATOMIC_ROLLBACK_CATALOG,
			session -> {
				final Optional<EntityReference> reference = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, code))
					),
					EntityReference.class
				);
				assertEquals(
					new EntityReference(Entities.PRODUCT, expectedPrimaryKey),
					reference.orElseThrow()
				);
			}
		);
	}

	/**
	 * Asserts that querying the unique {@link #ATTRIBUTE_CODE} attribute in {@link #ATOMIC_ROLLBACK_CATALOG} for the
	 * given value resolves to no entity at all (its former owner was removed or reverted).
	 *
	 * @param evitaClient the gRPC client to query with, must not be null
	 * @param code        the unique attribute value expected to be free, must not be null
	 */
	private static void assertCodeResolvesToNothing(@Nonnull EvitaClient evitaClient, @Nonnull String code) {
		evitaClient.queryCatalog(
			ATOMIC_ROLLBACK_CATALOG,
			session -> {
				final Optional<EntityReference> reference = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, code))
					),
					EntityReference.class
				);
				assertFalse(reference.isPresent(), "Code `" + code + "` must resolve to no entity!");
			}
		);
	}

	/**
	 * Asserts that filtering products by the brand facet {@link #BRAND_PRIMARY_KEY} in {@link #ATOMIC_ROLLBACK_CATALOG}
	 * returns exactly the supplied primary keys (and no others). A reverted entity whose facet entry leaked would
	 * appear here as an extra primary key.
	 *
	 * @param evitaClient         the gRPC client to query with, must not be null
	 * @param expectedPrimaryKeys the complete set of product primary keys expected to carry the brand facet
	 */
	private static void assertBrandFacetResolvesExactlyTo(@Nonnull EvitaClient evitaClient, @Nonnull int... expectedPrimaryKeys) {
		evitaClient.queryCatalog(
			ATOMIC_ROLLBACK_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(REFERENCE_BRAND, entityPrimaryKeyInSet(BRAND_PRIMARY_KEY))
							)
						)
					),
					EntityReference.class
				);
				assertExactPrimaryKeys(expectedPrimaryKeys, references, "brand facet");
			}
		);
	}

	/**
	 * Asserts that filtering products by the indexed {@link #PRICE_LIST_BASIC} price list in {@link #CURRENCY_CZK}
	 * within {@link #ATOMIC_ROLLBACK_CATALOG} returns exactly the supplied primary keys (and no others). A reverted
	 * entity whose price-index entry leaked would appear here as an extra primary key.
	 *
	 * @param evitaClient         the gRPC client to query with, must not be null
	 * @param expectedPrimaryKeys the complete set of product primary keys expected to be priced in basic/CZK
	 */
	private static void assertPricedProductsAreExactly(@Nonnull EvitaClient evitaClient, @Nonnull int... expectedPrimaryKeys) {
		evitaClient.queryCatalog(
			ATOMIC_ROLLBACK_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(PRICE_LIST_BASIC),
							priceInCurrency(CURRENCY_CZK)
						)
					),
					EntityReference.class
				);
				assertExactPrimaryKeys(expectedPrimaryKeys, references, "basic/CZK price index");
			}
		);
	}

	/**
	 * Shared helper asserting the primary keys carried by the supplied references are exactly the expected set
	 * (order-independent), producing a readable message naming the index under test on mismatch.
	 *
	 * @param expectedPrimaryKeys the complete expected set of primary keys
	 * @param references          the references returned by the query
	 * @param indexDescription    a human-readable name of the index being verified
	 */
	private static void assertExactPrimaryKeys(
		@Nonnull int[] expectedPrimaryKeys,
		@Nonnull List<EntityReference> references,
		@Nonnull String indexDescription
	) {
		final int[] actualPrimaryKeys = references.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.sorted()
			.toArray();
		final int[] sortedExpected = expectedPrimaryKeys.clone();
		Arrays.sort(sortedExpected);
		assertArrayEquals(
			sortedExpected,
			actualPrimaryKeys,
			"The " + indexDescription + " must resolve to exactly the surviving entities (no residue from the reverted entity)!"
		);
	}


}
