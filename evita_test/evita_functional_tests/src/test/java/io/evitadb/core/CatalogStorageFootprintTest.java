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

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the {@link CatalogStatisticsComponent#STORAGE_SIZE} decomposition: that every byte in a catalog directory
 * is attributed to exactly one storage class, that the classes reconcile with the measured total by construction, and
 * that the flat directory listing the decomposition is built from agrees with the recursive walk the shipped
 * `CatalogStatisticsEvent` metric still uses.
 *
 * That last equality is the one that licenses the change: the metric feeds JFR and from there live Prometheus
 * dashboards, so the two computations agreeing on a real catalog is what makes replacing one with the other safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Catalog storage footprint decomposition")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogStorageFootprintTest implements EvitaTestSupport {
	private static final String CATALOG = "storageFootprintTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogStorageFootprintTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT);
				session.defineEntitySchema(ENTITY_CATEGORY);
				for (int i = 1; i <= 50; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, i));
				}
				for (int i = 1; i <= 10; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("The flat listing the decomposition uses agrees with the recursive walk the metric uses")
	void shouldMeasureTheSameTotalAsTheRecursiveDirectoryWalk() {
		final StorageSizeStatistics storageSize = fetchCatalogStorageSize();

		// `CatalogStatisticsEvent` still measures with `FileUtils.getDirectorySize`, which walks recursively; the
		// decomposition sums a single flat listing. They can only agree while the catalog directory stays flat, and
		// that flatness is an implicit assumption of every file-name-based attribution below - so it is pinned here
		// rather than left to be discovered by a Prometheus dashboard reading a number that quietly changed.
		assertEquals(
			FileUtils.getDirectorySize(catalogDirectory()),
			storageSize.sizeOnDiskInBytes(),
			"The flat listing and the recursive walk disagree - the catalog directory is no longer flat"
		);
	}

	@Test
	@DisplayName("Every catalog byte lands in exactly one storage class")
	void shouldAccountForEveryCatalogByte() {
		final StorageSizeStatistics storageSize = fetchCatalogStorageSize();

		assertEquals(
			storageSize.sizeOnDiskInBytes(),
			storageSize.liveBytes() + storageSize.wasteBytes() + storageSize.walBytes() +
				storageSize.awaitingDeletionBytes() + storageSize.bootstrapBytes() + storageSize.unaccountedBytes(),
			"The storage classes do not reconcile with the measured total: " + storageSize
		);
		// the blocked/purgeable pair partitions `awaitingDeletionBytes` - it must not be added to the total again
		assertEquals(
			storageSize.awaitingDeletionBytes(),
			storageSize.blockedByActiveReaderBytes() + storageSize.purgeableBytes(),
			"The blocked/purgeable split does not partition the files awaiting deletion: " + storageSize
		);
		assertAllNonNegative(storageSize);

		// the attribution has to actually happen - all of these were `unaccountedBytes` before the decomposition
		// landed, and an implementation that silently regressed to that would still satisfy every sum above
		assertTrue(storageSize.bootstrapBytes() > 0, "The bootstrap file was not attributed: " + storageSize);
		assertTrue(storageSize.liveBytes() > 0, "No live bytes were attributed: " + storageSize);
		assertTrue(
			storageSize.unaccountedBytes() < storageSize.sizeOnDiskInBytes(),
			"Everything is still unaccounted for: " + storageSize
		);
	}

	@Test
	@DisplayName("Every collection byte lands in exactly one storage class")
	void shouldAccountForEveryCollectionByte() {
		long summedCollectionLiveBytes = 0L;
		for (final String entityType : new String[]{ENTITY_PRODUCT, ENTITY_CATEGORY}) {
			final EntityCollectionStatistics statistics = this.evita.management().getEntityCollectionStatistics(
				CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.STORAGE_SIZE)
			);
			final CollectionStorageSize storageSize = statistics.storageSizeIfPresent().orElseThrow();

			assertEquals(
				storageSize.sizeOnDiskInBytes(),
				storageSize.liveBytes() + storageSize.wasteBytes() + storageSize.awaitingDeletionBytes() +
					storageSize.unaccountedBytes(),
				"The storage classes of `" + entityType + "` do not reconcile with its measured total: " + storageSize
			);
			assertTrue(storageSize.sizeOnDiskInBytes() >= 0, entityType + ": " + storageSize);
			assertTrue(storageSize.liveBytes() >= 0, entityType + ": " + storageSize);
			assertTrue(storageSize.wasteBytes() >= 0, entityType + ": " + storageSize);
			assertTrue(storageSize.awaitingDeletionBytes() >= 0, entityType + ": " + storageSize);
			assertTrue(storageSize.unaccountedBytes() >= 0, entityType + ": " + storageSize);
			assertTrue(storageSize.liveBytes() > 0, "No live bytes attributed to `" + entityType + "`: " + storageSize);
			summedCollectionLiveBytes += storageSize.liveBytes();
		}

		// the two levels are computed by independent code paths - the catalog aggregates from one listing of the whole
		// directory, each collection from its own filtered listing - and this is what ties them together. It is also
		// the only assertion here that would notice the catalog-level lookup of a collection's open persistence
		// service silently missing: the collection's bytes would drop out of the catalog's `liveBytes` while every
		// per-collection sum above still balanced. Strict, because the catalog data store contributes its own bytes
		// on top of every collection's
		final StorageSizeStatistics catalogStorageSize = fetchCatalogStorageSize();
		assertTrue(
			summedCollectionLiveBytes < catalogStorageSize.liveBytes(),
			"The collections' live bytes (" + summedCollectionLiveBytes + ") must be a proper subset of the " +
				"catalog's (" + catalogStorageSize.liveBytes() + "), which also holds the catalog data store"
		);
	}

	@Test
	@DisplayName("The write-ahead log is attributed even with time travel disabled")
	void shouldAttributeTheWriteAheadLogWithoutTimeTravel() {
		// the record's javadoc used to claim `walBytes` reads `0` without time travel. It does not: the log is
		// trimmed to a kept-file count in both modes, and time travel widens the retained window rather than being
		// what creates one. A transactional catalog on this test's time-travel-disabled configuration is what proves
		// it - and it is also the only test here in which the WAL bucket is non-empty at all
		this.evita.updateCatalog(
			CATALOG,
			EvitaSessionContract::goLiveAndClose
		);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 1_000));
			}
		);

		final StorageSizeStatistics storageSize = fetchCatalogStorageSize();
		assertTrue(storageSize.walBytes() > 0, "The write-ahead log was not attributed: " + storageSize);
		assertEquals(
			storageSize.sizeOnDiskInBytes(),
			storageSize.liveBytes() + storageSize.wasteBytes() + storageSize.walBytes() +
				storageSize.awaitingDeletionBytes() + storageSize.bootstrapBytes() + storageSize.unaccountedBytes(),
			"The storage classes do not reconcile once a write-ahead log exists: " + storageSize
		);
	}

	@Test
	@DisplayName("A stray file stays visible in the unaccounted remainder")
	void shouldSurfaceAnUnknownFileAsUnaccounted() {
		final StorageSizeStatistics before = fetchCatalogStorageSize();
		final byte[] content = "a temporary file left behind by something the engine does not track"
			.getBytes(StandardCharsets.UTF_8);
		writeIntoCatalogDirectory("interrupted-compaction.tmp", content);

		final StorageSizeStatistics after = fetchCatalogStorageSize();
		assertEquals(before.sizeOnDiskInBytes() + content.length, after.sizeOnDiskInBytes());
		assertEquals(
			before.unaccountedBytes() + content.length,
			after.unaccountedBytes(),
			"A file the engine does not track must stay visible in the remainder, not vanish from the report"
		);
	}

	@Test
	@DisplayName("A data file newer than the header is not reported as garbage")
	void shouldNotReportCompactionOutputAsAwaitingDeletion() {
		final StorageSizeStatistics before = fetchCatalogStorageSize();
		// compaction writes the next generation *before* the header flips to it, so a snapshot taken mid-compaction
		// sees a data file the header does not name. Reading it as a superseded generation would be the exact
		// inverse of the truth - it is about to become the current one
		final int nextCatalogFileIndex = currentCatalogFileIndex() + 1;
		final byte[] content = new byte[4096];
		writeIntoCatalogDirectory(
			CatalogPersistenceService.getCatalogDataStoreFileName(CATALOG, nextCatalogFileIndex), content
		);

		final StorageSizeStatistics after = fetchCatalogStorageSize();
		assertEquals(before.sizeOnDiskInBytes() + content.length, after.sizeOnDiskInBytes());
		assertEquals(
			before.awaitingDeletionBytes(),
			after.awaitingDeletionBytes(),
			"A generation newer than the header is compaction output, not a file awaiting deletion"
		);
		assertEquals(before.unaccountedBytes() + content.length, after.unaccountedBytes());
	}

	@Test
	@DisplayName("A data file of a collection the header no longer knows is awaiting deletion")
	void shouldReportOrphanedCollectionFileAsAwaitingDeletion() {
		final StorageSizeStatistics before = fetchCatalogStorageSize();
		assertEquals(0L, before.awaitingDeletionBytes(), "A freshly built catalog has nothing awaiting deletion");

		// what a dropped collection leaves behind until the purge catches up: a data file whose entity type primary
		// key appears in no reference of the catalog header, so no generation of it is current
		final byte[] content = new byte[2048];
		writeIntoCatalogDirectory(
			CatalogPersistenceService.getEntityCollectionDataStoreFileName("droppedThing", 99, 0), content
		);

		final StorageSizeStatistics after = fetchCatalogStorageSize();
		assertEquals(before.sizeOnDiskInBytes() + content.length, after.sizeOnDiskInBytes());
		assertEquals(content.length, after.awaitingDeletionBytes());
		assertEquals(before.unaccountedBytes(), after.unaccountedBytes());
		// nothing in this process is holding the file, so it reads as purgeable rather than reader-blocked - the
		// distinction an operator uses to tell "the purge is behind" from "a long-running session is pinning disk"
		assertEquals(0L, after.blockedByActiveReaderBytes());
		assertEquals(content.length, after.purgeableBytes());
	}

	/**
	 * Reads the storage size component of the test catalog.
	 *
	 * @return the delivered {@link StorageSizeStatistics} of the test catalog
	 */
	@Nonnull
	private StorageSizeStatistics fetchCatalogStorageSize() {
		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.STORAGE_SIZE)
		);
		return statistics.storageSizeIfPresent().orElseThrow();
	}

	/**
	 * Asserts that no storage class of the catalog-level decomposition came out negative - which is what a missing
	 * clamp on the offset index's estimated active size, or a class counted twice, would produce.
	 *
	 * @param storageSize the decomposition to check
	 */
	private static void assertAllNonNegative(@Nonnull StorageSizeStatistics storageSize) {
		assertTrue(storageSize.sizeOnDiskInBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.liveBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.wasteBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.walBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.awaitingDeletionBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.blockedByActiveReaderBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.purgeableBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.bootstrapBytes() >= 0, storageSize.toString());
		assertTrue(storageSize.unaccountedBytes() >= 0, storageSize.toString());
	}

	/**
	 * Returns the file system directory the test catalog is stored in.
	 *
	 * @return path of the test catalog directory
	 */
	@Nonnull
	private Path catalogDirectory() {
		return this.paths.storage().resolve(CATALOG);
	}

	/**
	 * Returns the generation index of the catalog data store file the catalog currently uses. A freshly created
	 * catalog has exactly one, so the highest index present is the current one.
	 *
	 * @return the current catalog data store file generation index
	 */
	private int currentCatalogFileIndex() {
		try (Stream<Path> files = Files.list(catalogDirectory())) {
			return files
				.map(it -> it.getFileName().toString())
				.filter(it -> it.endsWith(CatalogPersistenceService.CATALOG_FILE_SUFFIX))
				.mapToInt(CatalogPersistenceService::getIndexFromCatalogFileName)
				.max()
				.orElseThrow(() -> new IllegalStateException("No catalog data store file found!"));
		} catch (IOException e) {
			fail(e);
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Writes a file directly into the catalog directory, bypassing the engine - which is the point: these tests are
	 * about what the decomposition does with content it did not put there itself.
	 *
	 * @param fileName name of the file to create
	 * @param content  bytes to write into it
	 */
	private void writeIntoCatalogDirectory(@Nonnull String fileName, @Nonnull byte[] content) {
		try {
			Files.write(catalogDirectory().resolve(fileName), content);
		} catch (IOException e) {
			fail(e);
		}
	}

	/**
	 * Builds the configuration of the embedded instance used by this test.
	 *
	 * @return configuration pointing at this test's isolated directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}
}
