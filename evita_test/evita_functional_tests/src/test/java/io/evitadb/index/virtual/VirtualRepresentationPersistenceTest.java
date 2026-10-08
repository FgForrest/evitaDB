/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.virtual;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.category;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.collectionOf;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.describeRows;
import static io.evitadb.index.virtual.VirtualReferencePartitionTransitionTest.partitionOf;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the representation of the single-owner reference partitions of a catalog is the storage protocol of its
 * catalog header: a catalog created virtual is written under
 * {@link PersistenceService#VIRTUAL_REFERENCE_PARTITIONS_STORAGE_PROTOCOL_VERSION}, any other under
 * {@link PersistenceService#STORAGE_PROTOCOL_VERSION}, and every later header write - a commit, going live, a
 * collection or catalog rename, a catalog replacement, a duplicate, a backup and its restore - keeps the protocol the
 * catalog was created under. A catalog loaded from disk takes its representation from that header alone, before any
 * of its collections exists and before the write-ahead log is replayed onto it.
 *
 * Every catalog of this test is created in an explicitly requested representation (see
 * {@link Evita#createCatalogWithVirtualReferencePartitions(String, boolean)}), so the forced test mode changes nothing
 * here, and every engine that loads a catalog again is a fresh instance that was told nothing about it - the
 * representation it observes can only have come from the disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Persisted representation of single-owner reference partitions")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(REFERENCE)
class VirtualRepresentationPersistenceTest implements EvitaTestSupport {
	/**
	 * Long enough that no checkpoint falls due on its own during a test - every checkpoint is one the test provoked.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * Generous bound on a backup, a restore or a load of a catalog holding a handful of entities.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;
	/**
	 * The catalog the backups are restored under, or the catalog is duplicated or renamed to.
	 */
	private static final String OTHER_CATALOG = "otherCatalog";
	/**
	 * The collection created only after the last checkpoint.
	 */
	private static final String ENTITY_ACCESSORY = "accessory";
	/**
	 * The collection the product collection is renamed to.
	 */
	private static final String ENTITY_GOODS = "goods";
	/**
	 * The categories the accessories of the write-ahead log tail reference, each by one accessory alone.
	 */
	private static final int[] ACCESSORY_CATEGORIES = {7, 8};

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(VirtualRepresentationPersistenceTest.class.getSimpleName());
		this.evita = new Evita(configuration(this.paths));
	}

	@AfterEach
	void tearDown() {
		if (this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "created virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a catalog keeps the protocol it was created under through warm-up, going live, commits and reloads")
	void shouldKeepProtocolOfCreationThroughEveryHeaderWriteAndReload(boolean virtual) {
		final int expectedProtocol = protocolOf(virtual);
		createCatalog(CATALOG, virtual);
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after the creation");

		this.evita.updateCatalog(CATALOG, SingleOwnerPartitionFixture::setUp);
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after the warm-up");

		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after going live");

		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(SingleOwnerPartitionFixture.newProduct(session, 9));
			}
		);
		persistenceService(CATALOG).checkpoint();
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after a commit in the live catalog");
		final String rowsBeforeReload = describeRows(this.evita, ENTITY_PRODUCT, REFERENCE_CATEGORIES);

		restart();
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after the reload");
		assertEquals(
			rowsBeforeReload, describeRows(this.evita, ENTITY_PRODUCT, REFERENCE_CATEGORIES),
			"the registry must be read back as it was written"
		);
		if (virtual) {
			// the representation, read back from disk alone, maintains and reads every collection
			final Catalog catalog = catalogOf(this.evita, CATALOG);
			for (final String entityType : catalog.getEntityTypes()) {
				final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntityOrThrowException(
					entityType
				);
				assertTrue(collection.isVirtualReferencePartitionsMaintained(), entityType + " must be maintained");
			}
		}

		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(SingleOwnerPartitionFixture.newProduct(session, 10));
			}
		);
		persistenceService(CATALOG).checkpoint();
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after a commit in the reloaded catalog");
	}

	@ParameterizedTest(name = "created virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a renamed collection and a renamed catalog keep the protocol, also when loaded again")
	void shouldKeepProtocolWhenCollectionAndCatalogAreRenamed(boolean virtual) {
		final int expectedProtocol = protocolOf(virtual);
		createLiveCatalog(CATALOG, virtual);

		this.evita.updateCatalog(CATALOG, session -> {
			session.renameCollection(ENTITY_PRODUCT, ENTITY_GOODS);
		});
		persistenceService(CATALOG).checkpoint();
		assertRepresentation(CATALOG, virtual, expectedProtocol, "after the collection rename");

		this.evita.renameCatalog(CATALOG, OTHER_CATALOG);
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the catalog rename");

		restart();
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the reload of the renamed catalog");
	}

	@ParameterizedTest(name = "the replacing catalog virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a replaced catalog takes the protocol of the catalog replacing it, in both directions")
	void shouldTakeProtocolOfReplacingCatalogWhenCatalogIsReplaced(boolean replacingVirtual) {
		final int expectedProtocol = protocolOf(replacingVirtual);
		createLiveCatalog(OTHER_CATALOG, replacingVirtual);
		createLiveCatalog(CATALOG, !replacingVirtual);
		assertRepresentation(CATALOG, !replacingVirtual, protocolOf(!replacingVirtual), "before the replacement");

		this.evita.replaceCatalog(OTHER_CATALOG, CATALOG);
		assertRepresentation(CATALOG, replacingVirtual, expectedProtocol, "after the replacement");

		restart();
		assertRepresentation(CATALOG, replacingVirtual, expectedProtocol, "after the reload of the replaced catalog");
	}

	@ParameterizedTest(name = "created virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a duplicate under a new name and identifier keeps the protocol of its source")
	void shouldKeepProtocolWhenCatalogIsDuplicated(boolean virtual) {
		final int expectedProtocol = protocolOf(virtual);
		createLiveCatalog(CATALOG, virtual);

		this.evita.duplicateCatalog(CATALOG, OTHER_CATALOG);
		this.evita.activateCatalog(OTHER_CATALOG);
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the duplication");
		assertNotEquals(
			catalogOf(this.evita, CATALOG).getCatalogId(), catalogOf(this.evita, OTHER_CATALOG).getCatalogId(),
			"the duplicate must have been given an identifier of its own"
		);
	}

	@ParameterizedTest(name = "created virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a point-in-time backup restored under a new name keeps the protocol, in its bootstrap record too")
	void shouldKeepProtocolWhenPointInTimeBackupIsRestored(boolean virtual) throws Exception {
		final int expectedProtocol = protocolOf(virtual);
		createLiveCatalog(CATALOG, virtual);

		final Path archive = awaitArchive(
			this.evita.management().backupCatalog(CATALOG, OffsetDateTime.now(), null, false)
				.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		);
		restore(archive);
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the restore");
		final List<CatalogBootstrap> restoredRecords = bootstrapRecords(
			persistenceService(OTHER_CATALOG).getCatalogStoragePath()
		);
		assertEquals(
			expectedProtocol, restoredRecords.get(0).storageProtocolVersion(),
			"the archived bootstrap record carries the protocol of the backed-up catalog: " + restoredRecords
		);

		restart();
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the reload of the restored catalog");
	}

	@ParameterizedTest(name = "created virtual: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("a full backup restored under a new name keeps the protocol")
	void shouldKeepProtocolWhenFullBackupIsRestored(boolean virtual) throws Exception {
		final int expectedProtocol = protocolOf(virtual);
		createLiveCatalog(CATALOG, virtual);

		final Path archive = awaitArchive(
			this.evita.management().fullBackupCatalog(CATALOG).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		);
		restore(archive);
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the restore");

		restart();
		assertRepresentation(OTHER_CATALOG, virtual, expectedProtocol, "after the reload of the restored catalog");
	}

	@Test
	@DisplayName("a collection created after the last checkpoint is virtual while the write-ahead log replays it")
	@Tag(WAL)
	void shouldReplayCollectionCreatedAfterLastCheckpointVirtually() {
		// checkpoints of the live catalog are deferred by an interval no test outlives (with sync writes on, the
		// interval is ignored without them), so every commit after going live stays in the write-ahead log only
		final TestPaths deferringPaths = createTestPaths(
			VirtualRepresentationPersistenceTest.class.getSimpleName() + "_deferring"
		);
		final List<TestPaths> allocated = new ArrayList<>(2);
		allocated.add(deferringPaths);
		this.evita.close();
		try {
			this.evita = new Evita(deferringConfiguration(deferringPaths));
			createLiveCatalog(CATALOG, true);
			// the clean close publishes going live, which was deferred like any other live round
			this.evita.close();
			this.evita = new Evita(deferringConfiguration(deferringPaths));
			awaitCatalogLoaded(this.evita, CATALOG);
			final long checkpointedVersion = catalogOf(this.evita, CATALOG).getLastPersistedCatalogVersion();
			assertEquals(
				catalogOf(this.evita, CATALOG).getVersion(), checkpointedVersion,
				"the restarted engine must start with nothing left to checkpoint"
			);

			// the tail: a collection the last checkpoint knows nothing about, and one entity mutation per accessory
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.defineEntitySchema(ENTITY_ACCESSORY)
						.withReferenceToEntity(
							REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
						)
						.updateVia(session);
				}
			);
			for (int i = 0; i < ACCESSORY_CATEGORIES.length; i++) {
				final int accessory = i + 1;
				final int category = ACCESSORY_CATEGORIES[i];
				this.evita.updateCatalog(
					CATALOG,
					session -> {
						session.upsertEntity(
							session.createNewEntity(ENTITY_ACCESSORY, accessory)
								.setReference(REFERENCE_CATEGORIES, category)
						);
					}
				);
			}

			// the image a crash would leave: the storage copied while the engine is quiescent, before it is closed
			final Catalog running = catalogOf(this.evita, CATALOG);
			await()
				.atMost(30, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(
					() -> catalogOf(this.evita, CATALOG).getVersion() ==
						catalogOf(this.evita, CATALOG).getLastCatalogVersionInMutationStream()
				);
			final long appliedVersion = catalogOf(this.evita, CATALOG).getVersion();
			assertTrue(
				running.getLastPersistedCatalogVersion() < appliedVersion,
				"the tail must not have been checkpointed when the image is taken"
			);
			final TestPaths image = createTestPaths(
				VirtualRepresentationPersistenceTest.class.getSimpleName() + "_image"
			);
			allocated.add(image);
			copyRecursively(deferringPaths.storage(), image.storage());
			this.evita.close();

			// a fresh engine told nothing about the catalog boots from the image and replays the tail
			this.evita = new Evita(deferringConfiguration(image));
			awaitCatalogLoaded(this.evita, CATALOG);
			final Catalog recovered = catalogOf(this.evita, CATALOG);
			assertEquals(appliedVersion, recovered.getVersion(), "the replay must reach the version applied before");

			// every replayed entity mutation - the first one included - found the collection maintained, which the
			// collection created by the replay could only be when the representation was known before the replay
			assertEquals(
				ACCESSORY_CATEGORIES.length,
				recovered.getVirtualReferencePartitionMaintenance().getMaintainedMutationCount(),
				"every entity mutation the replay executed must have run with the virtual representation"
			);
			final ReferencedTypeEntityIndex typeIndex = assertInstanceOf(
				ReferencedTypeEntityIndex.class,
				collectionOf(this.evita, ENTITY_ACCESSORY).getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
				)
			);
			for (final int category : ACCESSORY_CATEGORIES) {
				// the replay wrote the single-owner partition virtually - the type index advertises its row only
				final int[] partitions = typeIndex.getAllReferenceIndexes(category);
				assertEquals(
					1, partitions.length, "the replay must have created the partition of category " + category
				);
				final OptionalLong row = typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(partitions[0]);
				assertTrue(
					row.isPresent(),
					"the replay must have registered the single-owner partition of category " + category
				);
				assertEquals(
					-1, partitionOf(this.evita, ENTITY_ACCESSORY, Scope.LIVE, category(category)),
					"the replayed single-owner partition of category " + category + " must have no index object"
				);
			}
			assertRepresentation(
				CATALOG, true, PersistenceService.VIRTUAL_REFERENCE_PARTITIONS_STORAGE_PROTOCOL_VERSION,
				"after the recovery"
			);
		} finally {
			if (this.evita.isActive()) {
				this.evita.close();
			}
			for (final TestPaths allocatedPaths : allocated) {
				cleanupTestPaths(allocatedPaths);
			}
		}
	}

	/**
	 * The switches of a virtual catalog are on for good, and the store refuses to change the protocol of a header.
	 */
	@Nested
	@DisplayName("Refusals")
	class Refusals {

		@Test
		@DisplayName("a virtual catalog maintains and reads every collection, those not existing yet included")
		void shouldMaintainAndReadVirtuallyEveryCollectionOfVirtualCatalog() {
			createCatalog(CATALOG, true);
			final VirtualReferencePartitionMaintenance maintenance = maintenanceOf(CATALOG);
			assertTrue(maintenance.isVirtual());
			assertTrue(maintenance.isMaintained("notCreatedYet"));
			assertTrue(maintenance.isReadVirtually("notCreatedYet"));
			// turning a switch on is accepted and changes nothing
			maintenance.setMaintained(ENTITY_PRODUCT, true);
			maintenance.setReadVirtually(ENTITY_PRODUCT, true);
			assertTrue(maintenance.isVirtual());
			assertTrue(maintenance.isMaintained("notCreatedYet"));
		}

		@Test
		@DisplayName("a virtual catalog refuses to stop maintaining or reading a collection virtually")
		void shouldRefuseSwitchingVirtualCatalogOff() {
			createCatalog(CATALOG, true);
			VirtualRepresentationPersistenceTest.this.evita.updateCatalog(
				CATALOG, SingleOwnerPartitionFixture::setUp
			);
			final VirtualReferencePartitionMaintenance maintenance = maintenanceOf(CATALOG);
			assertThrows(GenericEvitaInternalError.class, () -> maintenance.setReadVirtually(ENTITY_PRODUCT, false));
			assertThrows(GenericEvitaInternalError.class, () -> maintenance.setMaintained(ENTITY_PRODUCT, false));
			assertThrows(GenericEvitaInternalError.class, maintenance::leaveForcedDefault);
			final EntityCollection products = (EntityCollection) catalogOf(
				VirtualRepresentationPersistenceTest.this.evita, CATALOG
			).getCollectionForEntityOrThrowException(ENTITY_PRODUCT);
			assertThrows(
				GenericEvitaInternalError.class, () -> products.setVirtualReferencePartitionsReadVirtually(false)
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> products.setVirtualReferencePartitionsMaintained(false)
			);
			assertTrue(maintenance.isMaintained(ENTITY_PRODUCT));
			assertTrue(maintenance.isReadVirtually(ENTITY_PRODUCT));
		}

		@ParameterizedTest(name = "created virtual: {0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("the store refuses to rewrite a header under the protocol of the other representation")
		void shouldRefuseRewritingHeaderUnderProtocolOfOtherRepresentation(boolean virtual) {
			createLiveCatalog(CATALOG, virtual);
			final DefaultCatalogPersistenceService persistenceService = persistenceService(CATALOG);
			final long version = persistenceService.getLastCatalogVersion();
			final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService =
				persistenceService.getStoragePartPersistenceService(version);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
				storagePartPersistenceService.getCatalogHeader(version);

			final GenericEvitaInternalError refusal = assertThrows(
				GenericEvitaInternalError.class,
				() -> storagePartPersistenceService.writeCatalogHeader(
					protocolOf(!virtual),
					version,
					persistenceService.getCatalogStoragePath(),
					header.walFileReference(),
					header.collectionFileIndex(),
					header.catalogId(),
					header.catalogName(),
					header.catalogState(),
					header.lastEntityCollectionPrimaryKey()
				)
			);
			assertTrue(
				Objects.requireNonNull(refusal.getPrivateMessage()).contains("never changes"), refusal.getMessage()
			);
			assertEquals(
				protocolOf(virtual), storagePartPersistenceService.getCatalogHeader(version).storageProtocolVersion(),
				"the refused write must have left the header as it was"
			);
		}
	}

	/**
	 * Returns the storage protocol of the representation.
	 *
	 * @param virtual TRUE for the virtual representation
	 * @return the protocol
	 */
	private static int protocolOf(boolean virtual) {
		return virtual ?
			PersistenceService.VIRTUAL_REFERENCE_PARTITIONS_STORAGE_PROTOCOL_VERSION :
			PersistenceService.STORAGE_PROTOCOL_VERSION;
	}

	/**
	 * Creates a catalog in the given representation.
	 *
	 * @param catalogName the catalog
	 * @param virtual     TRUE to create it virtual
	 */
	private void createCatalog(@Nonnull String catalogName, boolean virtual) {
		this.evita.createCatalogWithVirtualReferencePartitions(catalogName, virtual);
		this.evita.defineCatalog(catalogName).updateViaNewSession(this.evita);
	}

	/**
	 * Creates a catalog in the given representation, fills it with the fixture of single-owner partitions in warm-up
	 * and takes it live.
	 *
	 * @param catalogName the catalog
	 * @param virtual     TRUE to create it virtual
	 */
	private void createLiveCatalog(@Nonnull String catalogName, boolean virtual) {
		createCatalog(catalogName, virtual);
		this.evita.updateCatalog(catalogName, SingleOwnerPartitionFixture::setUp);
		this.evita.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Asserts the representation of a loaded catalog: the protocol of its current catalog header and the holder of
	 * its switches.
	 *
	 * @param catalogName      the catalog
	 * @param virtual          TRUE when the catalog must be virtual
	 * @param expectedProtocol the protocol its header must carry
	 * @param when             names the moment in failure messages
	 */
	private void assertRepresentation(
		@Nonnull String catalogName,
		boolean virtual,
		int expectedProtocol,
		@Nonnull String when
	) {
		final DefaultCatalogPersistenceService persistenceService = persistenceService(catalogName);
		assertEquals(
			expectedProtocol,
			persistenceService.getCatalogHeader(persistenceService.getLastCatalogVersion()).storageProtocolVersion(),
			"the header of `" + catalogName + "` " + when
		);
		assertEquals(
			virtual, maintenanceOf(catalogName).isVirtual(), "the representation of `" + catalogName + "` " + when
		);
	}

	/**
	 * Closes the engine and starts a fresh one over the same directories, told nothing about any catalog, and waits
	 * until it has loaded every catalog.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(configuration(this.paths));
		for (final String catalogName : this.evita.getCatalogNames()) {
			awaitCatalogLoaded(this.evita, catalogName);
		}
	}

	/**
	 * Restores the backup archive under {@link #OTHER_CATALOG} and activates it.
	 *
	 * @param archive the backup archive
	 */
	private void restore(@Nonnull Path archive) throws Exception {
		try (InputStream inputStream = Files.newInputStream(archive)) {
			this.evita.management()
				.restoreCatalog(OTHER_CATALOG, Files.size(archive), inputStream)
				.getFutureResult()
				.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		this.evita.activateCatalog(OTHER_CATALOG);
	}

	/**
	 * Resolves the backup archive in the export directory.
	 *
	 * @param backup the finished backup
	 * @return the path of the archive
	 */
	@Nonnull
	private Path awaitArchive(@Nonnull FileForFetch backup) {
		final Path archive = backup.path(
			((FileSystemExportOptions) this.evita.getConfiguration().export()).getDirectory()
		);
		assertTrue(archive.toFile().isFile(), "the backup archive must have been written");
		return archive;
	}

	/**
	 * Returns the holder of the switches of a loaded catalog.
	 *
	 * @param catalogName the catalog
	 * @return the holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenanceOf(@Nonnull String catalogName) {
		return catalogOf(this.evita, catalogName).getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Returns the live persistence service of a loaded catalog.
	 *
	 * @param catalogName the catalog
	 * @return its persistence service
	 */
	@Nonnull
	private DefaultCatalogPersistenceService persistenceService(@Nonnull String catalogName) {
		// through the wildcard: `Catalog` holds the service under the interface's parameterisation while the
		// implementation declares concrete reference types, so the direct cast is rejected at compile time
		final CatalogPersistenceService<?, ?, ?> catalogPersistenceService =
			catalogOf(this.evita, catalogName).getPersistenceService();
		return (DefaultCatalogPersistenceService) catalogPersistenceService;
	}

	/**
	 * Reads every bootstrap record of the catalog stored in the given folder, oldest first.
	 *
	 * @param catalogStoragePath the catalog folder
	 * @return the bootstrap records
	 */
	@Nonnull
	private List<CatalogBootstrap> bootstrapRecords(@Nonnull Path catalogStoragePath) {
		final EvitaConfiguration configuration = this.evita.getConfiguration();
		final StorageSettings bootstrapSettings = new StorageSettings(
			configuration.storage(), configuration.transaction()
		).modifyForBootstrapFile();
		final String bootstrapFileName;
		try (Stream<Path> files = Files.list(catalogStoragePath)) {
			bootstrapFileName = files
				.map(it -> it.getFileName().toString())
				.filter(it -> it.endsWith(CatalogPersistenceService.BOOT_FILE_SUFFIX))
				.findFirst()
				.orElseThrow();
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list `" + catalogStoragePath + "`!", ex);
		}
		try (
			Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				bootstrapFileName.replace(CatalogPersistenceService.BOOT_FILE_SUFFIX, ""),
				catalogStoragePath,
				bootstrapSettings
			)
		) {
			return records.toList();
		}
	}

	/**
	 * Returns a loaded catalog of an engine.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the catalog
	 */
	@Nonnull
	private static Catalog catalogOf(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		return assertInstanceOf(Catalog.class, catalog, "catalog `" + catalogName + "` must be loaded");
	}

	/**
	 * Waits until the engine has loaded the catalog.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 */
	private static void awaitCatalogLoaded(@Nonnull Evita evita, @Nonnull String catalogName) {
		evita.waitUntilFullyInitialized();
		await()
			.atMost(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> evita.getCatalogInstance(catalogName).orElse(null) instanceof Catalog);
		assertNotNull(evita.getCatalogInstance(catalogName).orElse(null));
	}

	/**
	 * Copies a directory tree, creating the target if it does not exist.
	 *
	 * @param source tree to copy from
	 * @param target tree to copy into
	 */
	private static void copyRecursively(@Nonnull Path source, @Nonnull Path target) {
		try {
			final List<Path> entries;
			try (Stream<Path> tree = Files.walk(source)) {
				entries = tree.toList();
			}
			for (final Path entry : entries) {
				final Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(entry, destination);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy `" + source + "` to `" + target + "`!", ex);
		}
	}

	/**
	 * Builds the configuration of an engine over the given directories.
	 *
	 * @param paths the directories
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Builds the configuration of an engine over the given directories that defers every checkpoint of a live catalog
	 * for longer than any test runs.
	 *
	 * @param paths the directories
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration deferringConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.storage(
				StorageOptions.builder()
					.storageDirectory(paths.storage())
					.workDirectory(paths.work())
					.syncWrites(true)
					.build()
			)
			.transaction(TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build())
			.build();
	}
}
