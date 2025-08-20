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

package io.evitadb.api;

import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.proxy.mock.MockCatalogStructuralChangeObserver;
import io.evitadb.api.proxy.mock.ProductInterface;
import io.evitadb.api.proxy.mock.ProductParameterInterface;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaManagement;
import io.evitadb.core.async.SessionKiller;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.spi.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class EvitaTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String DIR_EVITA_TEST = "evitaTest";
	public static final String DIR_EVITA_TEST_EXPORT = "evitaTest_export";
	public static final String REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY = "productsInCategory";
	public static final String REFERENCE_PRODUCT_CATEGORY = "productCategory";
	private static final Locale LOCALE_CZ = new Locale("cs", "CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private Evita evita;
	private String evitaInstanceId;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evitaInstanceId = this.evita.management().getSystemStatus().instanceId();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
	}

	@Test
	void shouldPreventOpeningParallelSessionsInWarmUpState() {
		assertThrows(
			ConcurrentInitializationException.class,
			() -> {
				try (final EvitaSessionContract theSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
					this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.defineEntitySchema(Entities.CATEGORY);
						}
					);
				}
			}
		);
	}

	@Test
	void shouldAutomaticallyCreateTransactionIfNoneExists() {
		try (final EvitaSessionContract writeSession = this.evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.goLiveAndClose();
		}

		try (final EvitaSessionContract writeSession = this.evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.defineEntitySchema(Entities.CATEGORY).updateVia(writeSession);
		}

		try (final EvitaSessionContract readSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
			assertNotNull(readSession.getEntitySchema(Entities.CATEGORY));
		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonIndexedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1)
				.upsertVia(session);

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1, 2))
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToSummarizeByNonFacetedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1)
				.upsertVia(session);

			assertThrows(
				ReferenceNotFacetedException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummaryOfReference(
									Entities.BRAND,
									FacetStatisticsDepth.COUNTS,
									entityFetch(entityFetchAllContent()))
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToAddIndexingRequiredReferenceAttributeOnNonIndexedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
						)
						.updateVia(session);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::unique)
						)
						.updateVia(session);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::sortable)
						)
						.updateVia(session);
				}
			);
		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_NAME, String.class)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setAttribute(ATTRIBUTE_NAME, "It's me")
				.upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_NAME, "ABC")
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableReferenceAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
				.upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.BRAND,
									attributeEquals(ATTRIBUTE_NAME, "ABC")
								)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToOrderByNonSortableAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_NAME, String.class)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setAttribute(ATTRIBUTE_NAME, "It's me")
				.upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToOrderByNonSortableReferenceAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
				.upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.BRAND,
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
								)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldHandleQueryingEmptyCollection() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final EvitaResponse<SealedEntity> entities = session.query(
					query(
						collection(Entities.BRAND)
					),
					SealedEntity.class
				);

				// result is expected to be empty
				assertEquals(0, entities.getTotalRecordCount());
				assertTrue(entities.getRecordData().isEmpty());
			}
		);
	}

	@Test
	void shouldReturnZeroOrExactlyOne() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));

				assertNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(10)))
					).orElse(null)
				);

				assertNotNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1)))
					)
				);

				assertThrows(
					UnexpectedResultCountException.class,
					() -> session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2)))
					)
				);
			}
		);
	}

	@Test
	void shouldReturnMultipleResults() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final Integer[] pks = new Integer[50];
				for (int i = 0; i < 50; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i + 1));
					pks[i] = i + 1;
				}

				final EvitaResponse<EntityReference> emptyResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(100))),
					EntityReference.class
				);

				assertEquals(0, emptyResult.getTotalRecordCount());
				assertTrue(emptyResult.getRecordData().isEmpty());

				final EvitaResponse<EntityReference> firstPageResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)), require(page(1, 5))),
					EntityReference.class
				);

				assertEquals(50, firstPageResult.getTotalRecordCount());
				assertArrayEquals(new int[]{1, 2, 3, 4, 5}, firstPageResult.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray());

				final EvitaResponse<SealedEntity> thirdPageResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)), require(page(3, 5), entityFetch())),
					SealedEntity.class
				);

				assertEquals(50, thirdPageResult.getTotalRecordCount());
				assertArrayEquals(new int[]{11, 12, 13, 14, 15}, thirdPageResult.getRecordData().stream().mapToInt(SealedEntity::getPrimaryKey).toArray());

				assertThrows(
					UnexpectedResultException.class,
					() -> session.query(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2))),
						SealedEntity.class
					)
				);
			}
		);
	}

	@Test
	void shouldCreateAndLoadCatalog() {
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldKillInactiveSessionsAutomatically() throws NoSuchFieldException, IllegalAccessException {
		this.evita.updateCatalog(
			TEST_CATALOG,
			it -> {
				it.goLiveAndClose();
			}
		);
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration(1)
		);

		final EvitaSessionContract sessionInactive = this.evita.createReadOnlySession(TEST_CATALOG);
		final EvitaSessionContract sessionActive = this.evita.createReadOnlySession(TEST_CATALOG);

		assertEquals(2L, this.evita.getActiveSessions().count());

		final long start = System.currentTimeMillis();
		do {
			assertNotNull(sessionActive.getCatalogSchema());
		} while (!(System.currentTimeMillis() - start > 2000));

		final Field sessionKillerField = Evita.class.getDeclaredField("sessionKiller");
		sessionKillerField.setAccessible(true);
		final SessionKiller sessionKiller = (SessionKiller) sessionKillerField.get(this.evita);
		sessionKiller.run();

		assertFalse(sessionInactive.isActive());
		assertTrue(sessionActive.isActive());
		assertEquals(1L, this.evita.getActiveSessions().count());
	}

	@Test
	void shouldCreateAndDropCatalog() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		this.evita.deleteCatalogIfExists(TEST_CATALOG);

		assertFalse(this.evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldFailToCreateCatalogWithDuplicateNameInOneOfNamingConventions() {
		try {
			this.evita.defineCatalog("test-catalog");
			fail("Duplicated catalog name should be refused!");
		} catch (CatalogAlreadyPresentException ex) {
			assertEquals(
				"Catalog `test-catalog` and existing catalog `testCatalog` produce the same name `testCatalog` " +
					"in `CAMEL_CASE` convention! Please choose different catalog name.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldCreateAndDeleteEntityCollectionFromWithinTheSession() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));

				session.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 10));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 11));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));

				session.deleteCollection(Entities.BRAND);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				return null;
			}
		);
	}

	@Test
	void shouldRenameExistingCatalogInWarmUpMode() {
		doRenameCatalog(CatalogState.WARMING_UP);
	}

	@Test
	void shouldRenameExistingCatalogInTransactionalMode() {
		doRenameCatalog(CatalogState.ALIVE);
	}

	@Test
	void shouldReplaceExistingCatalogInWarmUpMode() {
		doReplaceCatalog(CatalogState.WARMING_UP);
	}

	@Test
	void shouldReplaceExistingCatalogInTransactionalMode() {
		doReplaceCatalog(CatalogState.ALIVE);
	}

	@Test
	void shouldCreateAndDropCollection() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndRenameCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset(this.evitaInstanceId);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.renameCollection(Entities.PRODUCT, Entities.STORE);
			assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(this.evitaInstanceId, TEST_CATALOG, Entities.STORE));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(this.evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});

		// the original file was immediately removed from the file system (we're in warm-up mode)
		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldRenameEntityCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.renameCollection(Entities.PRODUCT, Entities.BRAND);
		});

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			assertEquals(1, session.getEntityCollectionSize(Entities.BRAND));
			final Optional<SealedEntity> brand = session.getEntity(Entities.BRAND, 1, entityFetchAllContent());
			assertTrue(brand.isPresent());
			assertEquals("The product", brand.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
			return null;
		});
	}

	@Test
	void shouldFailToRenameCollectionToExistingCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			assertThrows(
				EntityTypeAlreadyPresentInCatalogSchemaException.class,
				() -> session.renameCollection(Entities.PRODUCT, Entities.CATEGORY)
			);
		});
	}

	@Test
	void shouldCreateAndReplaceCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset(this.evitaInstanceId);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(this.evitaInstanceId, TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(this.evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});

		// the original file was immediately removed from the file system (we're in warm-up mode)
		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldCreateAndRenameCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset(this.evitaInstanceId);

		try (final EvitaSessionContract oldSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
			log.info("Old session catalog version: " + oldSession.getCatalogVersion());

			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.renameCollection(Entities.PRODUCT, Entities.STORE);
				assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
			});

			assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(this.evitaInstanceId, TEST_CATALOG, Entities.STORE));
			assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(this.evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

			this.evita.queryCatalog(TEST_CATALOG, session -> {
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
				assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
				log.info("New session catalog version: " + session.getCatalogVersion());
				return null;
			});

			// the file needs to remain on disk for the old session to be able to read it
			assertTrue(theCollectionFile.exists());

			// we can still read data from old session (and old entity collection)
			assertThrows(CollectionNotFoundException.class, () -> oldSession.getEntityCollectionSize(Entities.STORE));
			assertEquals(1, oldSession.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, oldSession.getEntityCollectionSize(Entities.CATEGORY));
		}

		// give async process some time to finish
		final long start = System.currentTimeMillis();
		do {
			Thread.onSpinWait();
		} while (theCollectionFile.exists() && System.currentTimeMillis() - start < 2000);

		// the original file is removed when old session is terminated - there is no other reader
		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldCreateAndReplaceCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		MockCatalogStructuralChangeObserver.reset(this.evitaInstanceId);

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(this.evitaInstanceId, TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(this.evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndDropCollectionsInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldUpdateEntitySchemaAttributeDefinitionsReferringToGlobalOnes() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				final EntityAttributeSchemaContract firstAttribute = session.getEntitySchemaOrThrow(Entities.PRODUCT).getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertInstanceOf(GlobalAttributeSchemaContract.class, firstAttribute);
				assertTrue(firstAttribute.isLocalized());
				assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, ((GlobalAttributeSchemaContract) firstAttribute).getGlobalUniquenessType());

				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGloballyWithinLocale)
					.updateVia(session);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				assertTrue(catalogSchema.getAttribute(ATTRIBUTE_URL).isPresent());
				assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE, catalogSchema.getAttribute(ATTRIBUTE_URL).orElseThrow().getGlobalUniquenessType());

				final EntityAttributeSchemaContract secondAttribute = session.getEntitySchemaOrThrow(Entities.PRODUCT).getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertInstanceOf(GlobalAttributeSchemaContract.class, secondAttribute);
				assertTrue(secondAttribute.isLocalized());
				assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE, ((GlobalAttributeSchemaContract) secondAttribute).getGlobalUniquenessType());
			}
		);
	}

	@Test
	void shouldUpdateEntityAttributesReferringToGlobalAttributeThatIsChanged() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityAttributeSchemaContract productUrl = session.getEntitySchema(Entities.PRODUCT).orElseThrow()
					.getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertNull(productUrl.getDescription());
				assertTrue(productUrl.isLocalized());
				assertTrue(productUrl.isUnique());
				assertTrue(productUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGlobally());

				final EntityAttributeSchemaContract categoryUrl = session.getEntitySchema(Entities.CATEGORY).orElseThrow()
					.getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertNull(categoryUrl.getDescription());
				assertTrue(categoryUrl.isLocalized());
				assertTrue(categoryUrl.isUnique());
				assertTrue(categoryUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGlobally());
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_URL, String.class,
						whichIs -> whichIs
							.withDescription("URL of the entity")
							.localized(() -> false)
							.uniqueGloballyWithinLocale()
					)
					.updateVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityAttributeSchemaContract productUrl = session.getEntitySchema(Entities.PRODUCT).orElseThrow()
					.getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertEquals("URL of the entity", productUrl.getDescription());
				assertFalse(productUrl.isLocalized());
				assertTrue(productUrl.isUnique());
				assertTrue(productUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGloballyWithinLocale());

				final EntityAttributeSchemaContract categoryUrl = session.getEntitySchema(Entities.CATEGORY).orElseThrow()
					.getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertEquals("URL of the entity", categoryUrl.getDescription());
				assertFalse(categoryUrl.isLocalized());
				assertTrue(categoryUrl.isUnique());
				assertTrue(categoryUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGloballyWithinLocale());
			}
		);
	}

	@Test
	void shouldCreateReflectedReference() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						whichIs -> whichIs.withAttributesInheritedExcept("note")
							.withFacetedInherited()
							.withAttribute("customNote", String.class)
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withDescription("Assigned category.")
							.deprecated("Already deprecated.")
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
							.withAttribute("note", String.class)
							.faceted()
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
					.orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	@Test
	void shouldCreateReflectedReferenceAndRetainInheritanceDuringEngineRestart() {
		shouldCreateReflectedReference();

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
					.orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	@Test
	void shouldUpdateInheritedPropertiesInReflectedReference() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.EXACTLY_ONE,
						whichIs -> whichIs
							.withDescription("Assigned category (updated).")
							.notDeprecatedAnymore()
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable().filterable())
							.withAttribute("note", String.class)
							.withAttribute("additionalAttribute", String.class)
							.indexedForFilteringAndPartitioning()
							.nonFaceted()
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
					.orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category (updated).", reflectedReference.getDescription());
				assertNull(reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.EXACTLY_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertFalse(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(3, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertTrue(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
				assertTrue(attributes.containsKey("additionalAttribute"));
			}
		);
	}

	@Test
	void shouldDropReflectedReferenceAndCreateRegularOneOfTheSameName() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withoutReferenceTo(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY)
					.withReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, Cardinality.ZERO_OR_ONE
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
					.orElseThrow();

				final ReferenceSchemaContract newReference = categorySchema.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertFalse(newReference instanceof ReflectedReferenceSchemaContract);

				assertNull(newReference.getDescription());
				assertNull(newReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, newReference.getCardinality());
				assertFalse(newReference.isIndexed());
				assertFalse(newReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = newReference.getAttributes();
				assertEquals(0, attributes.size());
			}
		);
	}

	@Test
	void shouldDropRegularReferenceAndCreateReflectedOneOfTheSameName() {
		shouldDropReflectedReferenceAndCreateRegularOneOfTheSameName();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutReferenceTo(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						whichIs -> whichIs.withAttributesInheritedExcept("note")
							.withAttribute("customNote", String.class)
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
					.orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	@Test
	void shouldFailToCreateNonIndexedReferenceWhenReflectedReferenceExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() ->
				this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.CATEGORY)
							.withReflectedReferenceToEntity(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY)
							.updateVia(session);

						session.defineEntitySchema(Entities.PRODUCT)
							.withReferenceToEntity(
								REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
								ReferenceSchemaEditor::nonIndexed
							)
							.updateVia(session);
					}
				)
		);
	}

	@Test
	void shouldFailToCreateReflectedReferenceToNonIndexedReference() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							ReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToCreateNonManagedReferenceWhenEntityOfSuchNameExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToCreateManagedReferenceWhenEntityOfSuchNameDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToCreateNonManagedGroupReferenceWhenEntityOfSuchNameExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PARAMETER_GROUP)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withGroupType(Entities.PARAMETER_GROUP)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToCreateManagedGroupReferenceWhenEntityOfSuchNameDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsFilterableAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.filterable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsUniqueAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.unique())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsSortableAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceEntityTypeWhenThereIsReflectedReference() {
		// first create intertwined references
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);
			}
		);

		// now try to change the target entity
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(REFERENCE_PRODUCT_CATEGORY, Entities.PARAMETER, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceEntityTypeToNonManagedWhenThereIsReflectedReference() {
		// first create intertwined references
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);
			}
		);

		// now try to change the target entity
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexed() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class)
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference, and it should be ok
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedFilterableAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						ReflectedReferenceSchemaEditor::withAttributesInherited
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.filterable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedUniqueAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						ReflectedReferenceSchemaEditor::withAttributesInherited
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.unique())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedSortableAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						ReflectedReferenceSchemaEditor::withAttributesInherited
					)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocale() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				final SealedEntity result = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_URL, "/tenProdukt")),
						require(entityFetch(attributeContent()))
					)
				).orElseThrow();

				assertEquals("Hle, produkt", result.getAttribute(ATTRIBUTE_NAME));

				final Set<Locale> locales = result.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) result).getImplicitLocale());
			}
		);
	}

	@Test
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocalePerEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, slightly different product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, trochu jiný produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theOtherProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenJinýProdukt")
				);

				final List<SealedEntity> result = session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInSet(ATTRIBUTE_URL, "/tenProdukt", "/theOtherProduct")),
						require(entityFetch(attributeContent()))
					)
				);

				assertNotNull(result);

				final SealedEntity firstProduct = result.stream().filter(it -> Objects.equals(it.getPrimaryKey(), 1)).findFirst().orElse(null);
				final SealedEntity secondProduct = result.stream().filter(it -> Objects.equals(it.getPrimaryKey(), 2)).findFirst().orElse(null);
				assertNotNull(firstProduct);
				assertNotNull(secondProduct);

				assertEquals("Hle, produkt", firstProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> firstProductLocales = firstProduct.getLocales();
				assertEquals(1, firstProductLocales.size());
				assertTrue(firstProductLocales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) firstProduct).getImplicitLocale());

				assertEquals("Hence, slightly different product", secondProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> secondProductLocales = secondProduct.getLocales();
				assertEquals(1, secondProductLocales.size());
				assertTrue(secondProductLocales.contains(Locale.ENGLISH));
				assertEquals(Locale.ENGLISH, ((EntityDecorator) secondProduct).getImplicitLocale());
			}
		);
	}

	@Test
	void shouldUpdateExistingPriceInReducedPriceIndexes() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withPrice(2)
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE, ReferenceSchemaEditor::indexedForFilteringAndPartitioning)
					.updateVia(session);

				session.createNewEntity(Entities.CATEGORY, 1).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(Entities.CATEGORY, 1)
					.setPrice(1, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 2)
					.setReference(Entities.CATEGORY, 1)
					.setPrice(2, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.upsertVia(session);

				session.goLiveAndClose();
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite()
					.setPrice(1, "basic", CURRENCY_CZK, null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, false)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity sealedEntity = session.getEntity(Entities.PRODUCT, 1, priceContentAll())
					.orElseThrow();

				assertEquals(BigDecimal.TEN, sealedEntity.getPrice(1, "basic", CURRENCY_CZK).orElseThrow().priceWithTax());
			}
		);
	}

	@Test
	void shouldFailToDefineTwoEntitiesSharingNameInSpecificNamingConvention() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema("abc");
					session.defineEntitySchema("ABc");
				}
			)
		);
	}

	@Test
	void shouldFailToDefineReferencesToManagedEntitiesThatDontExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema("someEntity")
						.withReferenceToEntity(
							"someReference",
							"nonExistingEntity",
							Cardinality.ONE_OR_MORE
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToDefineReferencesToManagedEntityGroupThatDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(
							"someEntity"
						)
						.withReferenceTo(
							"someReference",
							"nonExistingEntityNonManagedEntity",
							Cardinality.ONE_OR_MORE,
							whichIs -> whichIs.withGroupTypeRelatedToEntity("nonExistingGroup")
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldCreateReferencesToNonManagedEntityAndGroup() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(
						"someEntity"
					)
					.withReferenceTo(
						"someReference",
						"nonExistingEntityNonManagedEntity",
						Cardinality.ONE_OR_MORE,
						whichIs -> whichIs.withGroupType("nonExistingNonManagedGroup")
					).updateVia(session);

				assertNotNull(session.getEntitySchema("someEntity"));
			}
		);
	}

	@Test
	void shouldCreateCircularReferencesToManagedEntitiesAndGroups() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final ModifyEntitySchemaMutation categoryMutation = session.defineEntitySchema(
						Entities.CATEGORY
					)
					.withReferenceToEntity(Entities.PRODUCT, Entities.PRODUCT, Cardinality.ONE_OR_MORE)
					.toMutation()
					.orElseThrow();

				final ModifyEntitySchemaMutation productMutation = session.defineEntitySchema(
						Entities.PRODUCT
					)
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE)
					.toMutation()
					.orElseThrow();

				session.updateCatalogSchema(
					categoryMutation, productMutation
				);

				assertNotNull(session.getEntitySchema(Entities.CATEGORY));
				assertNotNull(session.getEntitySchema(Entities.PRODUCT));
			}
		);
	}

	@Test
	void shouldFailGracefullyWhenRequestingHierarchyOnNonHierarchyEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);

				assertThrows(
					EntityIsNotHierarchicalException.class,
					() -> session.queryListOfSealedEntities(
						query(
							collection(Entities.PRODUCT),
							filterBy(hierarchyWithinRootSelf()),
							require(entityFetch())
						)
					)
				);
			}
		);
	}

	@Test
	void shouldCorrectlyWorkWithOverlappingRangesWhenUpdated() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("range", IntegerNumberRange[].class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(
						"range",
						new IntegerNumberRange[]{
							IntegerNumberRange.between(1, 5),
							IntegerNumberRange.between(5, 10),
						}
					).upsertVia(session);

				IntFunction<EntityReference> getInRange = (threshold) -> session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInRange("range", threshold))
					)
				).orElse(null);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(7)
				);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute("range", new IntegerNumberRange[]{IntegerNumberRange.between(1, 5)})
					.upsertVia(session);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertNull(
					getInRange.apply(7)
				);
			}
		);
	}

	@Test
	void shouldIsolateChangesInSchemaWithinTransactions() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("someAttribute", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);

		// now open a new session and modify something
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.isTransactionOpen());
				session.getCatalogSchema()
					.openForWrite()
					.withDescription("This is my beautiful catalog.")
					.updateVia(session);

				session.getEntitySchema(Entities.PRODUCT)
					.orElseThrow()
					.openForWrite()
					.withDescription("This is my beautiful product collection.")
					.withAttribute("someAttribute", String.class, thatIs -> thatIs.localized().filterable())
					.withAttribute("differentAttribute", Integer.class)
					.updateVia(session);

				// create different session in parallel (original session is not yet committed)
				final CountDownLatch latch = new CountDownLatch(1);
				final Thread testThread = new Thread(() -> {
					try {
						this.evita.queryCatalog(
							TEST_CATALOG,
							parallelSession -> {
								assertNull(parallelSession.getCatalogSchema().getDescription());

								final SealedEntitySchema productSchema = parallelSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
								assertNull(productSchema.getDescription());

								final AttributeSchemaContract someAttributeSchema = productSchema
									.getAttribute("someAttribute")
									.orElseThrow();

								assertFalse(someAttributeSchema.isLocalized());
								assertFalse(someAttributeSchema.isFilterable());

								final AttributeSchemaContract differentAttributeSchema = productSchema
									.getAttribute("differentAttribute")
									.orElse(null);

								assertNull(differentAttributeSchema);

							}
						);
					} finally {
						latch.countDown();
					}
				});
				testThread.start();
				try {
					latch.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					fail(e.getMessage());
				}

				log.info("Committing changes.");
			}
		);

		// verify the changes were propagated at last
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals("This is my beautiful catalog.", session.getCatalogSchema().getDescription());

				final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();
				assertEquals("This is my beautiful product collection.", productSchema.getDescription());

				final AttributeSchemaContract someAttributeSchema = productSchema
					.getAttribute("someAttribute")
					.orElseThrow();

				assertTrue(someAttributeSchema.isLocalized());
				assertTrue(someAttributeSchema.isFilterable());

				final AttributeSchemaContract differentAttributeSchema = productSchema
					.getAttribute("differentAttribute")
					.orElseThrow();
				assertNotNull(differentAttributeSchema);
			}
		);
	}

	@Test
	void shouldReturnCorrectCatalogAndSchemaVersions() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("someAttribute", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);

		final int initialCatalogSchemaVersion = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema().version();
			}
		);

		// create two parallel sessions
		final EvitaSessionContract session1 = this.evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));
		final EvitaSessionContract session2 = this.evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));

		// in both modify entity schema
		session1.getEntitySchema(Entities.PRODUCT)
			.orElseThrow()
			.openForWrite()
			.withDescription("This is my beautiful product collection.")
			.updateVia(session1);

		session2.getEntitySchema(Entities.PRODUCT)
			.orElseThrow()
			.openForWrite()
			.withAttribute("someAttribute", String.class, thatIs -> thatIs.localized().filterable())
			.updateVia(session2);

		final List<String> worklog = new CopyOnWriteArrayList<>();

		// commit second first
		final CommitProgress session2CommitProgress = session2.closeNowWithProgress();
		session2CommitProgress.onConflictResolved().thenAccept(commitVersions -> worklog.add("Session 2 conflict resolved: " + commitVersions));
		session2CommitProgress.onWalAppended().thenAccept(commitVersions -> worklog.add("Session 2 WAL appended: " + commitVersions));

		// commit first
		final CommitProgress session1CommitProgress = session1.closeNowWithProgress();
		session1CommitProgress.onConflictResolved().thenAccept(commitVersions -> worklog.add("Session 1 conflict resolved: " + commitVersions));
		session1CommitProgress.onWalAppended().thenAccept(commitVersions -> worklog.add("Session 1 WAL appended: " + commitVersions));

		final CompletableFuture<CommitVersions> session1Future = session1CommitProgress.onChangesVisible()
			.thenApply(commitVersions -> {
				worklog.add("Session 1 changes visible: " + commitVersions);
				return commitVersions;
			})
			.toCompletableFuture();
		final CompletableFuture<CommitVersions> session2Future = session2CommitProgress.onChangesVisible()
			.thenApply(commitVersions -> {
				worklog.add("Session 2 changes visible: " + commitVersions);
				return commitVersions;
			})
			.toCompletableFuture();

		CompletableFuture.allOf(
			session1Future,
			session2Future
		).join();

		final CommitVersions versionsAssignedAfter = session1Future.getNow(null);
		final CommitVersions versionsAssigned = session2Future.getNow(null);

		// check work log
		assertEquals(
			6,
			worklog.size(),
			"Expected 6 log entries, but got: " + worklog.size() + ". Log: " + String.join(", ", worklog)
		);

		// versions in second session, committed first will be lesser
		assertTrue(versionsAssigned.catalogVersion() < versionsAssignedAfter.catalogVersion());
		assertTrue(versionsAssigned.catalogSchemaVersion() < versionsAssignedAfter.catalogSchemaVersion());

		// verify the changes were propagated at last
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(initialCatalogSchemaVersion + 2, session.getCatalogSchema().version());

				final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();
				assertEquals("This is my beautiful product collection.", productSchema.getDescription());

				final AttributeSchemaContract someAttributeSchema = productSchema
					.getAttribute("someAttribute")
					.orElseThrow();

				assertTrue(someAttributeSchema.isLocalized());
				assertTrue(someAttributeSchema.isFilterable());
			}
		);
	}

	@Test
	void shouldThrowExceptionInOnWalAppendedAndVerifyChangesAreStillVisible() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("testAttribute", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);

		// create a session and make a change
		final EvitaSessionContract session = this.evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));

		// create an entity
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute("testAttribute", "Test value")
		);

		// throw exception in onWalAppended completion stage
		final CommitProgress commitProgress = session.closeNowWithProgress();
		final Function<CommitVersions, Object> exceptionThrower = commitVersions -> {
			throw new RuntimeException("Test exception in onWalAppended");
		};
		commitProgress.onConflictResolved().thenApply(exceptionThrower);
		commitProgress.onWalAppended().thenApply(exceptionThrower);
		commitProgress.onChangesVisible().thenApply(exceptionThrower);

		// wait for the changes to be visible
		// this should not throw an exception because the exception in onWalAppended should not stop transaction processing
		final CommitVersions commitVersions = commitProgress.onChangesVisible().toCompletableFuture().join();
		assertNotNull(commitVersions);

		// verify that changes are visible in a new session
		this.evita.queryCatalog(
			TEST_CATALOG,
			session2 -> {
				// verify entity was created
				final EvitaResponse<EntityReference> response = session2.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						)
					),
					EntityReference.class
				);
				assertEquals(1, response.getTotalRecordCount());
			}
		);

		// restart evita engine
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		// verify that changes are still visible after restart
		this.evita.queryCatalog(
			TEST_CATALOG,
			session3 -> {
				// verify entity was created
				final EvitaResponse<EntityReference> response = session3.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						)
					),
					EntityReference.class
				);
				assertEquals(1, response.getTotalRecordCount());
			}
		);
	}

	@Test
	void shouldStartEvenIfOneCatalogIsCorrupted() {
		assertTrue(this.evita.getCatalogState(TEST_CATALOG + "_1").isEmpty());

		this.evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		assertEquals(CatalogState.WARMING_UP, this.evita.getCatalogState(TEST_CATALOG + "_1").orElseThrow());

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		assertEquals(CatalogState.CORRUPTED, this.evita.getCatalogState(TEST_CATALOG + "_1").orElseThrow());

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
					.certificate(
						CertificateOptions.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
					.enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					this.evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);

			final CatalogStatistics[] catalogStatistics = this.evita.management().getCatalogStatistics();
			assertNotNull(catalogStatistics);
			assertEquals(3, catalogStatistics.length);

			final CatalogStatistics statistics = Arrays.stream(catalogStatistics).filter(it -> TEST_CATALOG.equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics.sizeOnDiskInBytes() > 400L && statistics.sizeOnDiskInBytes() < 600L,
				"Expected size on disk to be between 400 and 600 bytes, but was " + statistics.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG, false, CatalogState.WARMING_UP, 0L, 0, 1, statistics.sizeOnDiskInBytes(), new EntityCollectionStatistics[0]
				),
				statistics
			);

			final CatalogStatistics statistics1 = Arrays.stream(catalogStatistics).filter(it -> (TEST_CATALOG + "_1").equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics1.sizeOnDiskInBytes() > 900L && statistics1.sizeOnDiskInBytes() < 1200L,
				"Expected size on disk to be between 900 and 1200 bytes, but was " + statistics1.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG + "_1", true, null, -1L, -1, -1, statistics1.sizeOnDiskInBytes(), new EntityCollectionStatistics[0]
				),
				statistics1
			);

			final CatalogStatistics statistics2 = Arrays.stream(catalogStatistics).filter(it -> (TEST_CATALOG + "_2").equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics2.sizeOnDiskInBytes() > 1000L && statistics2.sizeOnDiskInBytes() < 1700L,
				"Expected size on disk to be between 1000 and 1700 bytes, but was " + statistics2.sizeOnDiskInBytes()
			);
			final EntityCollectionStatistics productStatistics = statistics2.entityCollectionStatistics()[0];
			assertTrue(
				productStatistics.sizeOnDiskInBytes() > 300L && productStatistics.sizeOnDiskInBytes() < 600L,
				"Expected size on disk to be between 300 and 600 bytes, but was " + productStatistics.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG + "_2", false, CatalogState.WARMING_UP, 0, 1, 2, statistics2.sizeOnDiskInBytes(),
					new EntityCollectionStatistics[]{
						new EntityCollectionStatistics(Entities.PRODUCT, 1, 1, productStatistics.sizeOnDiskInBytes())
					}
				),
				statistics2
			);

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	@Test
	void shouldCreateCatalogEvenIfOneCatalogIsCorrupted() {
		this.evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
					.certificate(
						CertificateOptions.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
					.enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					this.evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);

			// but allow creating new catalog
			this.evita.defineCatalog(TEST_CATALOG + "_3")
				.updateViaNewSession(this.evita);

			assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG + "_3"));

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	@Test
	void shouldReplaceCorruptedCatalogWithCorrectOne() {
		this.evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
					.certificate(
						CertificateOptions.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
					.enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					this.evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);

			// but allow creating new catalog
			this.evita.defineCatalog(TEST_CATALOG + "_3")
				.updateViaNewSession(this.evita);

			assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG + "_3"));
			this.evita.replaceCatalog(TEST_CATALOG + "_3", TEST_CATALOG + "_1");

			final Set<String> catalogNamesAgain = this.evita.getCatalogNames();
			assertEquals(3, catalogNamesAgain.size());

			// exception should not be thrown again
			this.evita.updateCatalog(
				TEST_CATALOG + "_1",
				session -> {
					session.getAllEntityTypes();
				}
			);

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	@Test
	void shouldProperlyHandleFetchingOfNotYetKnownEntities() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable())
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable())
					.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ONE_OR_MORE)
					.withReferenceTo(Entities.PARAMETER, Entities.PARAMETER, Cardinality.ONE_OR_MORE)
					.updateVia(session);

				session.createNewEntity(Entities.BRAND, 1)
					.setAttribute(ATTRIBUTE_NAME, "Siemens")
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, "Mixer")
					.setReference(Entities.BRAND, 1)
					.setReference(Entities.BRAND, 2)
					.setReference(Entities.PARAMETER, 3)
					.upsertVia(session);

				final SealedEntity fullEntity = session.getEntity(
						Entities.PRODUCT, 1,
						entityFetchAllContentAnd(
							referenceContent(Entities.BRAND, entityFetchAll()),
							referenceContent(Entities.PARAMETER, entityFetchAll())
						)
					)
					.orElseThrow();

				// we get only single brand because when brand with PK=2 was fetched it was not found, yet it should
				// be present since entity maps to evita managed entity
				assertEquals(2, fullEntity.getReferences(Entities.BRAND).size());
				assertEquals(1, fullEntity.getReferences(Entities.PARAMETER).size());

				assertTrue(fullEntity.getReference(Entities.BRAND, 1).orElseThrow().getReferencedEntity().isPresent());
				assertFalse(fullEntity.getReference(Entities.BRAND, 2).orElseThrow().getReferencedEntity().isPresent());

				final SealedEntity shortEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow();

				// we get both brands because their bodies were not fetched and we had no chance to find out that
				// the brand with PK=2 is not (yet) present in the evita storage
				assertEquals(2, shortEntity.getReferences(Entities.BRAND).size());
				assertEquals(1, shortEntity.getReferences(Entities.PARAMETER).size());
			}
		);
	}

	@Test
	void shouldNotReturnGroupOfNonMatchingLocale() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PARAMETER)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.withReferenceToEntity(Entities.PARAMETER_GROUP, Entities.PARAMETER_GROUP, Cardinality.ZERO_OR_ONE)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.withReferenceToEntity(Entities.PARAMETER, Entities.PARAMETER, Cardinality.ZERO_OR_MORE, whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP))
					.updateVia(session);

				session.createNewEntity(Entities.PARAMETER_GROUP, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Group")
					.upsertVia(session);

				session.createNewEntity(Entities.PARAMETER, 1)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Parametr")
					.setReference(Entities.PARAMETER_GROUP, 1)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
					.setReference(Entities.PARAMETER, 1, whichIs -> whichIs.setGroup(1))
					.upsertVia(session);

				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1),
							entityLocaleEquals(LOCALE_CZ)
						),
						require(
							entityFetch(
								entityFetchAllContentAnd(
									referenceContent(Entities.PARAMETER, entityFetchAll(), entityGroupFetchAll())
								)
							)
						)
					)
				).orElseThrow();

				assertNotNull(product.getReference(Entities.PARAMETER, 1).orElse(null));
				assertNotNull(product.getReference(Entities.PARAMETER, 1).orElseThrow().getGroup().orElse(null));
				assertNull(product.getReference(Entities.PARAMETER, 1).orElseThrow().getGroupEntity().orElse(null));

				final ProductInterface productAsCustomClass = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1),
							entityLocaleEquals(LOCALE_CZ)
						),
						require(
							entityFetch(
								entityFetchAllContentAnd(
									referenceContent(Entities.PARAMETER, entityFetchAll(), entityGroupFetchAll())
								)
							)
						)
					),
					ProductInterface.class
				).orElseThrow();

				final ProductParameterInterface parameter = productAsCustomClass.getParameterById(1);
				assertNotNull(parameter);
				assertNotNull(parameter.getParameterGroup());
				assertNull(parameter.getParameterGroupEntity());
			}
		);
	}

	@Test
	void shouldCorrectlyLocalizeGloballyUniqueAttribute() {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
				);
			}
		);

		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
					);
				}
			)
		);

		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_URL, "/theProduct"))
						),
						EntityReference.class
					).orElseThrow();
				}
			)
		);

		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_URL, "/theProduct"),
								entityLocaleEquals(Locale.ENGLISH)
							)
						),
						EntityReference.class
					).orElseThrow();
				}
			)
		);

		assertNull(
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_URL, "/theProduct"),
								entityLocaleEquals(Locale.FRENCH)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	@Test
	void shouldCreateBackupAndRestoreCatalog() throws IOException, ExecutionException, InterruptedException {
		setupCatalogWithProductAndCategory();

		final CompletableFuture<FileForFetch> backupPathFuture = this.evita.management().backupCatalog(TEST_CATALOG, null, null, true);
		final Path backupPath = backupPathFuture.join().path(this.evita.getConfiguration().storage().exportDirectory());

		assertTrue(backupPath.toFile().exists());

		try (final BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(backupPath.toFile()))) {
			final CompletableFuture<Void> future = this.evita.management().restoreCatalog(
				TEST_CATALOG + "_restored",
				Files.size(backupPath),
				inputStream
			).getFutureResult();

			// wait for the restore to finish
			future.get();
		}

		this.evita.queryCatalog(TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				this.evita.queryCatalog(TEST_CATALOG + "_restored",
					session2 -> {
						final Set<String> allEntityTypes1 = session.getAllEntityTypes();
						final Set<String> allEntityTypes2 = session2.getAllEntityTypes();

						assertEquals(allEntityTypes1, allEntityTypes2);

						for (String entityType : allEntityTypes1) {
							session.queryList(
								query(
									collection(entityType),
									require(entityFetchAll(), page(1, 100))
								),
								SealedEntity.class
							).forEach(entity -> {
								final SealedEntity entity2 = session2.getEntity(
									entityType, entity.getPrimaryKey(), entityFetchAllContent()
								).orElseThrow();
								assertEquals(entity, entity2);
							});
						}
					});
			});
	}

	@Test
	void shouldCreateBackupAndRestoreTransactionalCatalog() throws IOException, ExecutionException, InterruptedException {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name")
					.upsertVia(session);
			}
		);

		final EvitaManagement management = this.evita.management();
		final CompletableFuture<FileForFetch> backupPathFuture = management.backupCatalog(TEST_CATALOG, null, null, true);
		final Path backupPath = backupPathFuture.join().path(this.evita.getConfiguration().storage().exportDirectory());

		assertTrue(backupPath.toFile().exists());

		try (final BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(backupPath.toFile()))) {
			final CompletableFuture<Void> future = management.restoreCatalog(
				TEST_CATALOG + "_restored",
				Files.size(backupPath),
				inputStream
			).getFutureResult();

			// wait for the restore to finish
			future.get();
		}

		this.evita.queryCatalog(TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				this.evita.queryCatalog(TEST_CATALOG + "_restored",
					session2 -> {
						final Set<String> allEntityTypes1 = session.getAllEntityTypes();
						final Set<String> allEntityTypes2 = session2.getAllEntityTypes();

						assertEquals(allEntityTypes1, allEntityTypes2);

						for (String entityType : allEntityTypes1) {
							session.queryList(
								query(
									collection(entityType),
									require(entityFetchAll(), page(1, 100))
								),
								SealedEntity.class
							).forEach(entity -> {
								final SealedEntity entity2 = session2.getEntity(
									entityType, entity.getPrimaryKey(), entityFetchAllContent()
								).orElseThrow();
								assertEquals(entity, entity2);
							});
						}
					});
			});

		// verify we can write to the original catalog again
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name again")
					.upsertVia(session);
			}
		);

		// and to the restored one as well
		this.evita.updateCatalog(
			TEST_CATALOG + "_restored",
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name again")
					.upsertVia(session);
			}
		);
	}

	@Test
	void shouldListAndCancelTasks() {
		final int numberOfTasks = 20;

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.goLiveAndClose();
			}
		);

		final EvitaManagement management = this.evita.management();
		ExecutorService executorService = Executors.newFixedThreadPool(numberOfTasks);

		// Step 2: Generate backup tasks using the custom executor
		final List<CompletableFuture<CompletableFuture<FileForFetch>>> backupTasks = Stream.generate(
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
		final List<Boolean> cancellationResult = Stream.concat(
				management.listTaskStatuses(1, 1, null)
					.getData()
					.stream()
					.map(it -> management.cancelTask(it.taskId())),
				backupTasks.subList(3, numberOfTasks - 1)
					.stream()
					.map(task -> task.getNow(null).cancel(true))
			)
			.toList();

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
		// there is small chance, that cancelled task will finish after all (if it was cancelled in terminal stage)
		final long finishedTasks = taskStatuses.getData().stream().filter(task -> task.simplifiedState() == TaskSimplifiedState.FINISHED).count();
		assertTrue(Math.abs((backupTasks.size() - cancelled) - finishedTasks) < numberOfTasks * 0.1);
		assertEquals(numberOfTasks - finishedTasks, taskStatuses.getData().stream().filter(task -> task.simplifiedState() == TaskSimplifiedState.FAILED).count());

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

	private void doRenameCatalog(@Nonnull CatalogState catalogState) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String renamedCatalogName = TEST_CATALOG + "_renamed";
		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				versionBeforeRename.set(session.getCatalogSchema().version());
				session.goLiveAndClose();
			});
		} else {
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		this.evita.renameCatalog(TEST_CATALOG, renamedCatalogName);

		assertFalse(this.evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(this.evita.getCatalogNames().contains(renamedCatalogName));

		this.evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());
				return null;
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		this.evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				return null;
			}
		);
	}

	private void doReplaceCatalog(@Nonnull CatalogState catalogState) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String temporaryCatalogName = TEST_CATALOG + "_tmp";
		this.evita.defineCatalog(temporaryCatalogName);
		this.evita.updateCatalog(
			temporaryCatalogName,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);

		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			this.evita.updateCatalog(temporaryCatalogName, session -> {
				versionBeforeRename.set(session.getCatalogSchema().version());
				session.goLiveAndClose();
			});
		} else {
			this.evita.queryCatalog(
				temporaryCatalogName,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		this.evita.replaceCatalog(temporaryCatalogName, TEST_CATALOG);

		assertFalse(this.evita.getCatalogNames().contains(temporaryCatalogName));
		assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG));

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 3));
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(3, session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());

				for (int i = 1; i <= 3; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 4));
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(4, session.getEntityCollectionSize(Entities.PRODUCT));

				for (int i = 1; i <= 4; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);
	}

	private void setupCatalogWithProductAndCategory() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);

			session
				.defineEntitySchema(Entities.PRODUCT)
				.verifySchemaButCreateOnTheFly()
				.updateVia(session);

			final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
				.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(3, PRICE_LIST_VIP, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(4, PRICE_LIST_VIP, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product");

			session.upsertEntity(product);

			session.defineEntitySchema(Entities.CATEGORY);

			session
				.defineEntitySchema(Entities.CATEGORY)
				.withHierarchy()
				.updateVia(session);

			session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
			session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));
		});
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.serviceThreadPool(
						ThreadPoolOptions.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getEvitaTestDirectory())
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_TEST_EXPORT))
					.timeTravelEnabled(false)
					.build()
			)
			.build();
	}

	@Nonnull
	private Path getEvitaTestDirectory() {
		return getTestDirectory().resolve(DIR_EVITA_TEST);
	}

}
