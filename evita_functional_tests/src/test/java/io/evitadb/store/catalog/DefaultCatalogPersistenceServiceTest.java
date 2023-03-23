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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.memTable.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.Assertions.assertExactlyEquals;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test verifies contract of {@link CatalogPersistenceService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DefaultCatalogPersistenceServiceTest implements EvitaTestSupport {
	private static final String RENAMED_CATALOG = "somethingElse";
	public static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), entityType -> null);
	private static final SealedCatalogSchema SEALED_CATALOG_SCHEMA = new CatalogSchemaDecorator(CATALOG_SCHEMA);
	public static final String DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "defaultCatalogPersistenceServiceTest";

	private final DataGenerator dataGenerator = new DataGenerator();

	@Nonnull
	private StorageOptions getStorageOptions() {
		return new StorageOptions(
			getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST), 60, 60,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE, 1, true
		);
	}

	@BeforeEach
	public void setUp() throws IOException {
		final Path resolve = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
		resolve.toFile().mkdirs();
	}

	@AfterEach
	public void tearDown() throws IOException {
		cleanTestSubDirectory(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
	}

	@Test
	void shouldSerializeAndDeserializeCatalogHeader() {
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions()
		);
		ioService.prepare();

		ioService.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

		final EvitaSession mockSession = mock(EvitaSession.class);
		when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

		final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 1
		);
		final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 2
		);
		final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 3
		);

		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(3);
		entityHeaders.add(productCollection.flush());
		entityHeaders.add(brandCollection.flush());
		entityHeaders.add(storeCollection.flush());

		// try to serialize
		ioService.storeHeader(CatalogState.WARMING_UP, 0, 0, entityHeaders);

		// release buffers
		ioService.release();

		// try to deserialize again
		final CatalogBootstrap catalogHeader = ioService.getCatalogBootstrap();

		assertNotNull(catalogHeader);
		final Map<String, EntityCollectionHeader> entityTypesIndex = catalogHeader.getCollectionHeaders();
		assertEntityCollectionsHasIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, brandCollection, entityTypesIndex.get(Entities.BRAND));
		assertEntityCollectionsHasIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, storeCollection, entityTypesIndex.get(Entities.STORE));
		assertEntityCollectionsHasIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, productCollection, entityTypesIndex.get(Entities.PRODUCT));
	}

	@Test
	void shouldDetectInvalidCatalogContents() {
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions()
		);
		ioService.prepare();

		ioService.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

		final EvitaSession mockSession = mock(EvitaSession.class);
		when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

		final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 1
		);
		final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 2
		);
		final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 3
		);

		// try to serialize
		ioService.storeHeader(
			CatalogState.WARMING_UP,
			0, 0,
			Arrays.asList(
				productCollection.flush(),
				brandCollection.flush(),
				storeCollection.flush()
			)
		);

		// release buffers
		ioService.release();

		assertThrows(
			UnexpectedCatalogContentsException.class,
			() -> {
				final Path dataDirectory = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
				final Path catalogPath = dataDirectory.resolve(TEST_CATALOG);
				final Path renamedCatalogPath = dataDirectory.resolve(RENAMED_CATALOG);
				assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogHeaderFileName(TEST_CATALOG)).toFile()
					.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogHeaderFileName(RENAMED_CATALOG)).toFile()));
				assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(TEST_CATALOG)).toFile()
					.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(RENAMED_CATALOG)).toFile()));
				assertTrue(catalogPath.toFile().renameTo(renamedCatalogPath.toFile()));
				//noinspection EmptyTryBlock
				try (var ignored = new DefaultCatalogPersistenceService(
					RENAMED_CATALOG,
					renamedCatalogPath,
					getStorageOptions()
				)) {
					// do nothing
				}
			}
		);
	}

	@Test
	void shouldSignalizeInvalidEntityNames() {
		assertThrows(
			InvalidClassifierFormatException.class,
			() -> {
				try (var cps = new DefaultCatalogPersistenceService(
					SEALED_CATALOG_SCHEMA.getName(),
					getStorageOptions()
				)) {
					cps.verifyEntityType(
						Collections.emptyList(),
						"→"
					);
				}
			}
		);
	}

	@Test
	void shouldSignalizeConflictingEntityNames() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> {
				try (var cps = new DefaultCatalogPersistenceService(
					SEALED_CATALOG_SCHEMA.getName(),
					getStorageOptions()
				)) {
					final EntityCollection mockCollection = mock(EntityCollection.class);
					when(mockCollection.getEntityType()).thenReturn("a");
					when(mockCollection.getSchema()).thenReturn(new EntitySchemaDecorator(() -> SEALED_CATALOG_SCHEMA, EntitySchema._internalBuild("a")));
					cps.verifyEntityType(
						List.of(mockCollection),
						"A"
					);
				}
			}
		);
	}

	@Test
	void shouldRefuseDuplicateCatalogName() {
		//noinspection EmptyTryBlock
		try (var ignored1 = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions()
		)) {
		}

		assertThrows(
			DirectoryNotEmptyException.class,
			() -> {
				final CatalogSchema catalogSchemaAgain = CATALOG_SCHEMA;
				//noinspection EmptyTryBlock
				try (var ignored2 = new DefaultCatalogPersistenceService(
					catalogSchemaAgain.getName(),
					getStorageOptions()
				)) {
				}
			}
		);
	}

	@Test
	void shouldDeleteCatalog() throws IOException {
		shouldSerializeAndDeserializeCatalogHeader();

		final Path catalogDirectory = getStorageOptions().storageDirectoryOrDefault().resolve(TEST_CATALOG);
		try (var cps = new DefaultCatalogPersistenceService(
			TEST_CATALOG,
			catalogDirectory,
			getStorageOptions()
		)) {
			assertTrue(catalogDirectory.toFile().exists());
			assertEquals(5, countFiles(catalogDirectory));
			cps.delete();
			assertFalse(catalogDirectory.toFile().exists());
		}
	}

	@Test
	void shouldReturnDefaultHeaderOnEmptyDirectory() {
		try (var cps = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions()
		)) {
			final CatalogBootstrap header = cps.getCatalogBootstrap();
			assertNotNull(header);
			assertEquals(CatalogState.WARMING_UP, header.getCatalogState());
			assertEquals(TEST_CATALOG, header.getCatalogHeader().getCatalogName());
			assertEquals(1L, header.getCatalogHeader().getVersion());
		}
	}

	/*
		PRIVATE METHODS
	 */

	private int countFiles(@Nonnull Path catalogDirectory) throws IOException {
		try (var paths = Files.list(catalogDirectory)) {
			return (int) paths.count();
		}
	}

	@Nonnull
	private EntityCollection constructEntityCollectionWithSomeEntities(@Nonnull CatalogPersistenceService ioService, @Nonnull SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema entitySchema, int entityTypePrimaryKey) {
		final EntityCollection entityCollection = new EntityCollection(
			getMockCatalog(catalogSchema, entitySchema),
			entityTypePrimaryKey,
			entitySchema.getName(),
			ioService,
			NoCacheSupervisor.INSTANCE
		);

		dataGenerator.generateEntities(
				entitySchema,
				(serializable, faker) -> null,
				40
			)
			.limit(10)
			.forEach(it -> it.toMutation().ifPresent(entityCollection::upsertEntity));

		return entityCollection;
	}

	private void assertEntityCollectionsHasIdenticalContent(@Nonnull CatalogPersistenceService ioService, @Nonnull SealedCatalogSchema catalogSchema, @Nonnull EntityCollection entityCollection, @Nonnull EntityCollectionHeader collectionHeader) {
		assertEquals(entityCollection.size(), collectionHeader.getRecordCount());
		final ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(getStorageOptions());
		outputKeeper.prepare();

		final SealedEntitySchema schema = entityCollection.getSchema();
		final EntityCollection collection = new EntityCollection(
			getMockCatalog(catalogSchema, schema),
			entityCollection.getEntityTypePrimaryKey(),
			schema.getName(), ioService, NoCacheSupervisor.INSTANCE
		);

		final Iterator<Entity> it = entityCollection.entityIterator();
		while (it.hasNext()) {
			final Entity originEntity = it.next();
			final EvitaResponse<EntityClassifier> response = collection.getEntities(
				new EvitaRequest(
					query(
						collection(entityCollection.getSchema().getName()),
						filterBy(entityPrimaryKeyInSet(originEntity.getPrimaryKey())),
						require(entityFetchAll())
					),
					OffsetDateTime.now()
				),
				mock(EvitaSession.class)
			);
			assertEquals(1, response.getRecordData().size());
			final SealedEntity deserializedEntity = (SealedEntity) response.getRecordData().get(0);
			assertExactlyEquals(originEntity, deserializedEntity);
		}

		outputKeeper.free();
	}

	@Nonnull
	private Catalog getMockCatalog(SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema schema) {
		final Catalog mockCatalog = mock(Catalog.class);
		when(mockCatalog.getSchema()).thenReturn(catalogSchema);
		when(mockCatalog.getEntitySchema(schema.getName())).thenReturn(of(schema));
		when(mockCatalog.getEntityIndexIfExists(Mockito.eq(schema.getName()), any(EntityIndexKey.class))).thenReturn(null);
		return mockCatalog;
	}

}