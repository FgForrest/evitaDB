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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.mock.MockCatalogStructuralChangeObserver;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

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
		evita = new Evita(
			getEvitaConfiguration()
		);
		evita.defineCatalog(TEST_CATALOG);
		evitaInstanceId = evita.getSystemStatus().instanceId();
	}

	@AfterEach
	void tearDown() {
		evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
	}

	@Test
	void shouldPreventOpeningParallelSessionsInWarmUpState() {
		assertThrows(
			ConcurrentInitializationException.class,
			() -> {
				try (final EvitaSessionContract theSession = evita.createReadOnlySession(TEST_CATALOG)) {
					evita.updateCatalog(
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
		try (final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.goLiveAndClose();
		}

		try (final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.defineEntitySchema(Entities.CATEGORY).updateVia(writeSession);
		}

		try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
			assertNotNull(readSession.getEntitySchema(Entities.CATEGORY));
		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonIndexedReference() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexed().withAttribute(ATTRIBUTE_NAME, String.class)
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
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
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexed().withAttribute(ATTRIBUTE_NAME, String.class)
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.close();
		evita = new Evita(
			getEvitaConfiguration()
		);

		assertTrue(evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldKillInactiveSessionsAutomatically() {
		evita.updateCatalog(
			TEST_CATALOG,
			it -> {
				it.goLiveAndClose();
			}
		);
		evita.close();

		evita = new Evita(
			getEvitaConfiguration(1)
		);

		final EvitaSessionContract sessionInactive = evita.createReadOnlySession(TEST_CATALOG);
		final EvitaSessionContract sessionActive = evita.createReadOnlySession(TEST_CATALOG);

		assertEquals(2L, evita.getActiveSessions().count());

		final long start = System.currentTimeMillis();
		do {
			assertNotNull(sessionActive.getCatalogSchema());
		} while (!(System.currentTimeMillis() - start > 5000 || !sessionInactive.isActive()));

		assertFalse(sessionInactive.isActive());
		assertTrue(sessionActive.isActive());
		assertEquals(1L, evita.getActiveSessions().count());
	}

	@Test
	void shouldCreateAndDropCatalog() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		evita.deleteCatalogIfExists(TEST_CATALOG);

		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldFailToCreateCatalogWithDuplicateNameInOneOfNamingConventions() {
		try {
			evita.defineCatalog("test-catalog");
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
		evita.updateCatalog(
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

		evita.queryCatalog(
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

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		evita.queryCatalog(TEST_CATALOG, session -> {
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

		MockCatalogStructuralChangeObserver.reset(evitaInstanceId);

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.renameCollection(Entities.PRODUCT, Entities.STORE);
			assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(evitaInstanceId, TEST_CATALOG, Entities.STORE));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});

		// the original file was immediately removed from the file system (we're in warm-up mode)
		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldFailToRenameCollectionToExistingCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		evita.updateCatalog(TEST_CATALOG, session -> {
			assertThrows(
				EvitaInvalidUsageException.class,
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

		MockCatalogStructuralChangeObserver.reset(evitaInstanceId);

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(evitaInstanceId, TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
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

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset(evitaInstanceId);

		try (final EvitaSessionContract oldSession = evita.createReadOnlySession(TEST_CATALOG)) {
			log.info("Old session catalog version: " + oldSession.getCatalogVersion());

			evita.updateCatalog(TEST_CATALOG, session -> {
				session.renameCollection(Entities.PRODUCT, Entities.STORE);
				assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
			});

			assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(evitaInstanceId, TEST_CATALOG, Entities.STORE));
			assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

			evita.queryCatalog(TEST_CATALOG, session -> {
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

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		MockCatalogStructuralChangeObserver.reset(evitaInstanceId);

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(evitaInstanceId, TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(evitaInstanceId, TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndDropCollectionsInTransaction() {
		setupCatalogWithProductAndCategory();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldUpdateEntitySchemaAttributeDefinitionsReferringToGlobalOnes() {
		evita.updateCatalog(
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
				assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, ((GlobalAttributeSchemaContract)firstAttribute).getGlobalUniquenessType());

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
				assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE, ((GlobalAttributeSchemaContract)secondAttribute).getGlobalUniquenessType());
			}
		);
	}

	@Test
	void shouldUpdateEntityAttributesReferringToGlobalAttributeThatIsChanged() {
		evita.updateCatalog(
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

		evita.queryCatalog(
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

		evita.updateCatalog(
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

		evita.queryCatalog(
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
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocale() {
		evita.updateCatalog(
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
		evita.updateCatalog(
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
	void shouldFailToDefineTwoEntitiesSharingNameInSpecificNamingConvention() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> evita.updateCatalog(
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
			() -> evita.updateCatalog(
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
			() -> evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("someAttribute", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);

		// now open a new session and modify something
		evita.updateCatalog(
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
						evita.queryCatalog(
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
		evita.queryCatalog(
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
	void shouldStartEvenIfOneCatalogIsCorrupted() {
		evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				evita,
				ApiOptions.builder()
					.certificate(
						CertificateSettings.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[1]))
					.enable(RestProvider.CODE, new RestConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);
		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	@Test
	void shouldCreateCatalogEvenIfOneCatalogIsCorrupted() {
		evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				evita,
				ApiOptions.builder()
					.certificate(
						CertificateSettings.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[1]))
					.enable(RestProvider.CODE, new RestConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);

			// but allow creating new catalog
			evita.defineCatalog(TEST_CATALOG + "_3")
				.updateViaNewSession(evita);

			assertTrue(evita.getCatalogNames().contains(TEST_CATALOG + "_3"));

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	@Test
	void shouldReplaceCorruptedCatalogWithCorrectOne() {
		evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				evita,
				ApiOptions.builder()
					.certificate(
						CertificateSettings.builder()
							.folderPath(getEvitaTestDirectory() + "-certificates")
							.build()
					)
					.enable(GraphQLProvider.CODE, new GraphQLConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[0]))
					.enable(GrpcProvider.CODE, new GrpcConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[1]))
					.enable(RestProvider.CODE, new RestConfig(AbstractApiConfiguration.LOCALHOST + ":" + ports[2]))
					.build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> {
					evita.updateCatalog(
						TEST_CATALOG + "_1",
						session -> {
							session.getAllEntityTypes();
						}
					);
				}
			);

			// but allow creating new catalog
			evita.defineCatalog(TEST_CATALOG + "_3")
				.updateViaNewSession(evita);

			assertTrue(evita.getCatalogNames().contains(TEST_CATALOG + "_3"));
			evita.replaceCatalog(TEST_CATALOG + "_3", TEST_CATALOG + "_1");

			final Set<String> catalogNamesAgain = evita.getCatalogNames();
			assertEquals(3, catalogNamesAgain.size());

			// exception should not be thrown again
			evita.updateCatalog(
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
		evita.updateCatalog(
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
	void shouldCreateBackupAndRestoreCatalog() throws IOException, ExecutionException, InterruptedException {
		setupCatalogWithProductAndCategory();

		final CompletableFuture<FileForFetch> backupPathFuture = evita.backupCatalog(TEST_CATALOG, null, true);
		final Path backupPath = backupPathFuture.join().path();

		assertTrue(backupPath.toFile().exists());

		final CompletableFuture<Void> future = evita.restoreCatalog(
			TEST_CATALOG + "_restored",
			Files.size(backupPath),
			new BufferedInputStream(new FileInputStream(backupPath.toFile()))
		);

		// wait for the restore to finish
		future.get();

		evita.queryCatalog(TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				evita.queryCatalog(TEST_CATALOG + "_restored",
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

		evita.queryCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		final CompletableFuture<FileForFetch> backupPathFuture = evita.backupCatalog(TEST_CATALOG, null, true);
		final Path backupPath = backupPathFuture.join().path();

		assertTrue(backupPath.toFile().exists());

		final CompletableFuture<Void> future = evita.restoreCatalog(
			TEST_CATALOG + "_restored",
			Files.size(backupPath),
			new BufferedInputStream(new FileInputStream(backupPath.toFile()))
		);

		// wait for the restore to finish
		future.get();

		evita.queryCatalog(TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				evita.queryCatalog(TEST_CATALOG + "_restored",
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

	private void doRenameCatalog(@Nonnull CatalogState catalogState) {
		evita.updateCatalog(
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
			evita.updateCatalog(TEST_CATALOG, session -> {
				versionBeforeRename.set(session.getCatalogSchema().version());
				session.goLiveAndClose();
			});
		} else {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		evita.renameCatalog(TEST_CATALOG, renamedCatalogName);

		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(evita.getCatalogNames().contains(renamedCatalogName));

		evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());
				return null;
			}
		);

		evita.close();

		evita = new Evita(
			getEvitaConfiguration()
		);

		evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				return null;
			}
		);
	}

	private void doReplaceCatalog(@Nonnull CatalogState catalogState) {
		evita.updateCatalog(
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
		evita.defineCatalog(temporaryCatalogName);
		evita.updateCatalog(
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
			evita.updateCatalog(temporaryCatalogName, session -> {
				versionBeforeRename.set(session.getCatalogSchema().version());
				session.goLiveAndClose();
			});
		} else {
			evita.queryCatalog(
				temporaryCatalogName,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		evita.replaceCatalog(temporaryCatalogName, TEST_CATALOG);

		assertFalse(evita.getCatalogNames().contains(temporaryCatalogName));
		assertTrue(evita.getCatalogNames().contains(TEST_CATALOG));

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 3));
			}
		);

		evita.queryCatalog(
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

		evita.close();

		evita = new Evita(
			getEvitaConfiguration()
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 4));
			}
		);

		evita.queryCatalog(
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
		evita.updateCatalog(TEST_CATALOG, session -> {
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
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getEvitaTestDirectory())
					.exportDirectory(getEvitaTestDirectory())
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
