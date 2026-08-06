/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.ObsoleteFileMaintainer.DataFilesBulkInfo;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getEntityCollectionDataStoreFileName;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the active-reader floor tracking exposed by {@link ObsoleteFileMaintainer} through its public API.
 *
 * The active-reader floor is the minimal catalog version that is still referenced by an active reader (open
 * session) or writer. It is recorded monotonically by {@link ObsoleteFileMaintainer#catalogConsumersLeft(long, long)}
 * and read back via {@link ObsoleteFileMaintainer#getActiveReaderFloor()}. The floor is tracked regardless of
 * whether time travel is enabled, because the WAL-rotation purge is clamped by it so that a catalog data file
 * still needed by an active reader is never deleted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Obsolete file maintainer active-reader floor tracking")
@Tag(STORAGE)
@Tag(WAL)
class ObsoleteFileMaintainerTest {

	/**
	 * Catalog name used for the maintainer under test; the value is irrelevant to floor tracking.
	 */
	private static final String CATALOG_NAME = "testCatalog";

	/**
	 * No-op supplier of oldest data file info; the floor logic never consults it.
	 */
	private static final Supplier<DataFilesBulkInfo> NO_OP_SUPPLIER = () -> null;

	/**
	 * Executor backing the scheduler; shut down after each test.
	 */
	private ScheduledThreadPoolExecutor executor;

	/**
	 * Scheduler handed to the maintainer; the floor logic never schedules work in these tests.
	 */
	private Scheduler scheduler;

	@TempDir
	private Path catalogStoragePath;

	@BeforeEach
	void setUp() {
		this.executor = new ScheduledThreadPoolExecutor(1);
		this.scheduler = new Scheduler(this.executor);
	}

	@AfterEach
	void tearDown() {
		this.executor.shutdownNow();
	}

	@Test
	@DisplayName("Floor is zero before any consumers-left notification")
	void shouldReportZeroFloorBeforeAnyConsumersLeftCall() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			assertEquals(0L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Single notification records the minimum of read and written versions")
	void shouldRecordMinimumOfReadAndWrittenVersions() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(5L, 3L);

			assertEquals(3L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Floor accumulates monotonically and never decreases")
	void shouldAccumulateFloorMonotonically() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(5L, 5L);
			assertEquals(5L, maintainer.getActiveReaderFloor());

			// a lower minimum must not lower the floor
			maintainer.catalogConsumersLeft(2L, 2L);
			assertEquals(5L, maintainer.getActiveReaderFloor());

			// a higher minimum raises the floor
			maintainer.catalogConsumersLeft(9L, 7L);
			assertEquals(7L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Notification whose minimum is zero or negative leaves the floor unchanged")
	void shouldIgnoreNonPositiveMinimum() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(4L, 4L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// minimum is zero -> guarded out
			maintainer.catalogConsumersLeft(8L, 0L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// minimum is negative -> guarded out
			maintainer.catalogConsumersLeft(-1L, 8L);
			assertEquals(4L, maintainer.getActiveReaderFloor());
		}
	}

	@ParameterizedTest(name = "timeTravelEnabled={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("Floor accumulation is identical regardless of time-travel mode")
	void shouldAccumulateFloorIdenticallyInBothTimeTravelModes(boolean timeTravelEnabled) {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(timeTravelEnabled)) {
			assertEquals(0L, maintainer.getActiveReaderFloor());

			maintainer.catalogConsumersLeft(6L, 4L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// lower minimum does not lower the floor in either mode
			maintainer.catalogConsumersLeft(3L, 3L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// higher minimum raises the floor in either mode
			maintainer.catalogConsumersLeft(10L, 8L);
			assertEquals(8L, maintainer.getActiveReaderFloor());
		}
	}

	/**
	 * Builds an {@link ObsoleteFileMaintainer} bound to the per-test scheduler and temporary storage path.
	 *
	 * @param timeTravelEnabled whether the maintainer should operate in time-travel mode
	 * @return a freshly constructed maintainer that the caller is responsible for closing
	 */
	@Nonnull
	private ObsoleteFileMaintainer newMaintainer(boolean timeTravelEnabled) {
		return newMaintainer(timeTravelEnabled, NO_OP_SUPPLIER);
	}

	/**
	 * Builds an {@link ObsoleteFileMaintainer} bound to the per-test scheduler and temporary storage path, using
	 * the supplied source of the oldest retained data file info.
	 *
	 * @param timeTravelEnabled           whether the maintainer should operate in time-travel mode
	 * @param oldestDataFilesInfoSupplier source of the oldest retained bootstrap record and its catalog header
	 * @return a freshly constructed maintainer that the caller is responsible for closing
	 */
	@Nonnull
	private ObsoleteFileMaintainer newMaintainer(
		boolean timeTravelEnabled,
		@Nonnull Supplier<DataFilesBulkInfo> oldestDataFilesInfoSupplier
	) {
		return new ObsoleteFileMaintainer(
			CATALOG_NAME,
			this.scheduler,
			this.catalogStoragePath,
			timeTravelEnabled,
			oldestDataFilesInfoSupplier
		);
	}

	/**
	 * Builds an {@link ObsoleteFileMaintainer} whose scheduler never actually runs anything, so the only deletion
	 * pass is the one the test drives. Use it wherever the assertion is about *which* pass does the work - with a
	 * live scheduler the maintainer's own rescheduling competes for the folder and either side can win.
	 *
	 * @param timeTravelEnabled whether the maintainer should operate in time-travel mode
	 * @return a freshly constructed maintainer that the caller is responsible for closing
	 */
	@Nonnull
	private ObsoleteFileMaintainer newMaintainerWithInertScheduler(boolean timeTravelEnabled) {
		return new ObsoleteFileMaintainer(
			CATALOG_NAME,
			Mockito.mock(Scheduler.class),
			this.catalogStoragePath,
			timeTravelEnabled,
			NO_OP_SUPPLIER
		);
	}

	/**
	 * Verifies that a catalog version explicitly pinned by a live consumer is never purged.
	 *
	 * The active-reader floor alone cannot express this. It is fed by `catalogConsumersLeft`, which fires only when
	 * the *last* reader of a version leaves, and it only ever rises - so a consumer that starts on a version in the
	 * **past** is invisible to it. A point-in-time backup is exactly that consumer: `BackupTask` pins the catalog
	 * version of the bootstrap record it is about to copy. Without the pin the purge sees a floor above that version,
	 * concludes nothing is in use down there, and deletes the files the backup is still reading.
	 */
	@Nested
	@DisplayName("Pinned catalog versions")
	class PinnedCatalogVersions {

		@Test
		@DisplayName("A version pinned below an already advanced reader floor still blocks the purge")
		void shouldNotPurgeBelowAVersionPinnedInThePast() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				// readers have moved on and the floor has risen accordingly
				maintainer.catalogConsumersLeft(100L, 100L);
				assertEquals(100L, maintainer.getRetentionFloor());

				// a point-in-time backup now starts reading a version far in the past
				maintainer.catalogVersionPinned(20L);

				assertEquals(20L, maintainer.getRetentionFloor());
			}
		}

		@Test
		@DisplayName("Releasing the pin lets the purge proceed again")
		void shouldReleaseThePinOnceTheConsumerIsDone() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				maintainer.catalogConsumersLeft(100L, 100L);
				maintainer.catalogVersionPinned(20L);
				assertEquals(20L, maintainer.getRetentionFloor());

				maintainer.catalogVersionReleased(20L);

				assertEquals(100L, maintainer.getRetentionFloor());
			}
		}

		@Test
		@DisplayName("Nothing held at all is reported as absent, never as a pin at version zero")
		void shouldReportAbsentFloorAsNegative() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				// `0` is a pinnable version - it is what a catalog goes live with, and what a full backup holds before
				// any history has been given up. Reporting "nothing is held" as `0` makes such a pin a silent no-op,
				// so the absent case has to be a value no consumer can ever hold
				assertEquals(-1L, maintainer.getRetentionFloor(), "an unheld catalog must report no floor at all");

				maintainer.catalogVersionPinned(0L);
				assertEquals(0L, maintainer.getRetentionFloor(), "version zero is a floor like any other");

				maintainer.catalogVersionReleased(0L);
				assertEquals(-1L, maintainer.getRetentionFloor());
			}
		}

		@Test
		@DisplayName("A version stays pinned until the last of several consumers releases it")
		void shouldHoldTheVersionUntilTheLastConsumerReleasesIt() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				maintainer.catalogConsumersLeft(100L, 100L);
				maintainer.catalogVersionPinned(20L);
				maintainer.catalogVersionPinned(20L);

				maintainer.catalogVersionReleased(20L);
				assertEquals(20L, maintainer.getRetentionFloor(), "one consumer is still reading version 20");

				maintainer.catalogVersionReleased(20L);
				assertEquals(100L, maintainer.getRetentionFloor());
			}
		}

		@Test
		@DisplayName("The retention floor is the lowest of the reader floor and every pin")
		void shouldReportTheLowestOfReaderFloorAndPins() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				maintainer.catalogVersionPinned(70L);
				// nothing else is known yet, so the only pin decides
				assertEquals(70L, maintainer.getRetentionFloor());

				maintainer.catalogConsumersLeft(40L, 40L);
				assertEquals(40L, maintainer.getRetentionFloor(), "the reader floor is now lower than the pin");

				maintainer.catalogVersionPinned(15L);
				assertEquals(15L, maintainer.getRetentionFloor());

				// releasing the lowest pin falls back to the next lowest constraint
				maintainer.catalogVersionReleased(15L);
				assertEquals(40L, maintainer.getRetentionFloor());
			}
		}

		@Test
		@DisplayName("Releasing a version that was never pinned is harmless")
		void shouldIgnoreReleaseOfUnpinnedVersion() {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(true)) {
				maintainer.catalogConsumersLeft(100L, 100L);

				maintainer.catalogVersionReleased(20L);

				assertEquals(100L, maintainer.getRetentionFloor());
			}
		}
	}

	/**
	 * Verifies which data files the time-travel WAL-rotation purge deletes and which it must leave alone.
	 *
	 * The purge keeps everything the **oldest retained** bootstrap record still pins and drops everything below it.
	 * The interesting cases are the entity collections whose primary key is absent from that record's catalog
	 * header, because absence has two opposite causes that must not be treated alike.
	 */
	/**
	 * Verifies that the eager warm-up deletion path answers to the directory read hold like every other deleter.
	 *
	 * `removeFileWhenNotUsed` is the second door into `purgeFile`, and it does not go through the scheduled purge at
	 * all - at catalog version `0` it unlinks inline, on the commit thread. Warm-up is also when a backup is most
	 * exposed, because it holds the folder precisely for the generations that repeated compaction strands. With time
	 * travel on the unlink is skipped anyway; with it off - the default configuration - this is a live delete under
	 * a running backup.
	 */
	@Nested
	@DisplayName("Warm-up eager purge under a directory read hold")
	class WarmUpEagerPurge {

		@Test
		@DisplayName("A file retired at version zero is not unlinked while the folder is held")
		void shouldNotUnlinkTheWarmUpFileWhileTheFolderIsHeld() throws IOException {
			try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
				final Path retiredFile = createCatalogFile(0);
				maintainer.acquireDirectoryReadHold();

				maintainer.removeFileWhenNotUsed(0L, retiredFile, () -> {});

				assertTrue(
					retiredFile.toFile().exists(),
					"a file retired during warm-up must survive while a consumer is reading the folder"
				);
			}
		}

		@Test
		@DisplayName("The deferred file is taken by the next deletion pass once the folder is free")
		void shouldPurgeTheDeferredFileOnceTheFolderIsFree() throws IOException {
			// the scheduler is inert here on purpose. Releasing the last hold schedules a purge of its own, and this
			// test asserts what the *next pass* does - with a live scheduler the two race for the folder and either
			// one can legitimately win, which would make the assertions below describe a coin toss rather than the
			// mechanism. Driving the pass from the test thread is the same choice the size-guard tests make
			try (ObsoleteFileMaintainer maintainer = newMaintainerWithInertScheduler(false)) {
				final Path deferredFile = createCatalogFile(0);
				maintainer.acquireDirectoryReadHold();
				maintainer.removeFileWhenNotUsed(0L, deferredFile, () -> {});
				maintainer.releaseDirectoryReadHold();

				// observed through a second retirement rather than through the task the release schedules, so that
				// the assertion is about the drain itself and not about winning a race with the scheduler
				final Path laterFile = createCatalogFile(1);
				maintainer.removeFileWhenNotUsed(0L, laterFile, () -> {});

				assertFalse(
					deferredFile.toFile().exists(),
					"the file parked while the folder was held must be taken by the next pass"
				);
				assertFalse(
					laterFile.toFile().exists(),
					"and the pass that took it must still do its own work"
				);
			}
		}

		@Test
		@DisplayName("A parked file does not outlive the maintainer that parked it")
		void shouldPurgeDeferredFilesWhenTheMaintainerCloses() throws IOException {
			final Path deferredFile;
			try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
				deferredFile = createCatalogFile(0);
				maintainer.acquireDirectoryReadHold();
				maintainer.removeFileWhenNotUsed(0L, deferredFile, () -> {});
			}

			// the hold is deliberately still open here - a catalog that is closing empties its folder regardless,
			// because a consumer reading it has nothing left to read. What must not happen is the file being
			// forgotten: nothing would ever collect it again
			assertFalse(
				deferredFile.toFile().exists(),
				"a parked file must not be left on disk by the maintainer that parked it"
			);
		}

		/**
		 * Creates an empty catalog data file with the given index inside the temporary catalog storage path.
		 *
		 * @param fileIndex index of the catalog data file
		 * @return path of the created file
		 */
		@Nonnull
		private Path createCatalogFile(int fileIndex) throws IOException {
			return Files.createFile(
				ObsoleteFileMaintainerTest.this.catalogStoragePath.resolve(
					getCatalogDataStoreFileName(CATALOG_NAME, fileIndex)
				)
			);
		}

	}

	@Nested
	@DisplayName("Time-travel purge of obsolete data files")
	class TimeTravelPurge {

		/**
		 * Entity type of the collection that already existed at the oldest retained version.
		 */
		private static final String RETAINED_ENTITY_TYPE = "product";

		/**
		 * Primary key of {@link #RETAINED_ENTITY_TYPE}; also the watermark stored in the oldest retained header.
		 */
		private static final int RETAINED_ENTITY_PK = 1;

		@Test
		@DisplayName("Files below the oldest retained generation are deleted, the pinned ones survive")
		void shouldDeleteOnlyFilesBelowOldestRetainedGeneration() throws IOException {
			final Path obsoleteCatalogFile = createFile(getCatalogDataStoreFileName(CATALOG_NAME, 0));
			final Path retainedCatalogFile = createFile(getCatalogDataStoreFileName(CATALOG_NAME, 1));
			final Path obsoleteCollectionFile = createFile(
				getEntityCollectionDataStoreFileName(RETAINED_ENTITY_TYPE, RETAINED_ENTITY_PK, 0));
			final Path retainedCollectionFile = createFile(
				getEntityCollectionDataStoreFileName(RETAINED_ENTITY_TYPE, RETAINED_ENTITY_PK, 1));

			purgeWithOldestRetainedGeneration(RETAINED_ENTITY_PK);

			assertFalse(obsoleteCatalogFile.toFile().exists(), "catalog file below the retained index must be gone");
			assertTrue(retainedCatalogFile.toFile().exists(), "pinned catalog file must survive");
			assertFalse(
				obsoleteCollectionFile.toFile().exists(), "collection file below the retained index must be gone");
			assertTrue(retainedCollectionFile.toFile().exists(), "pinned collection file must survive");
		}

		@Test
		@DisplayName("Files of a collection created after the retention floor survive")
		void shouldKeepFilesOfCollectionCreatedAfterRetentionFloor() throws IOException {
			// a collection created after the oldest retained version is absent from that version's catalog header,
			// but its primary key is above the watermark that header recorded - every file it has was written
			// later and is still pinned by a retained bootstrap record, including the live one
			final int laterEntityPk = RETAINED_ENTITY_PK + 1;
			final Path liveFileOfNewCollection = createFile(
				getEntityCollectionDataStoreFileName("brand", laterEntityPk, 0));
			createFile(getCatalogDataStoreFileName(CATALOG_NAME, 1));

			purgeWithOldestRetainedGeneration(RETAINED_ENTITY_PK);

			assertTrue(
				liveFileOfNewCollection.toFile().exists(),
				"the only data file of a collection created after the retention floor must not be deleted"
			);
		}

		@Test
		@DisplayName("Files of a collection dropped before the retention floor are reclaimed")
		void shouldDeleteFilesOfCollectionDroppedBeforeRetentionFloor() throws IOException {
			// a collection whose primary key is at or below the watermark, yet absent from the header, existed once
			// and was dropped - no retained record can reach its files any more
			final Path fileOfDroppedCollection = createFile(
				getEntityCollectionDataStoreFileName("store", RETAINED_ENTITY_PK, 3));
			createFile(getCatalogDataStoreFileName(CATALOG_NAME, 1));

			// watermark above the dropped collection's key, and the header lists no collection at all
			purgeWithOldestRetainedGeneration(RETAINED_ENTITY_PK + 5, Map.of());

			assertFalse(
				fileOfDroppedCollection.toFile().exists(),
				"files of a collection dropped before the retention floor must be reclaimed"
			);
		}

		/**
		 * Runs the time-travel purge against an oldest-retained generation that pins catalog file index `1` and
		 * the single {@link #RETAINED_ENTITY_TYPE} collection at file index `1`.
		 *
		 * @param lastEntityCollectionPrimaryKey watermark recorded in the oldest retained catalog header
		 */
		private void purgeWithOldestRetainedGeneration(int lastEntityCollectionPrimaryKey) {
			purgeWithOldestRetainedGeneration(
				lastEntityCollectionPrimaryKey,
				Map.of(
					RETAINED_ENTITY_TYPE,
					new CollectionFileReference(RETAINED_ENTITY_TYPE, RETAINED_ENTITY_PK, 1, null)
				)
			);
		}

		/**
		 * Runs the time-travel purge against an oldest-retained generation that pins catalog file index `1` and
		 * the supplied collection references.
		 *
		 * @param lastEntityCollectionPrimaryKey watermark recorded in the oldest retained catalog header
		 * @param collectionFileIndex            collections alive at the oldest retained version
		 */
		private void purgeWithOldestRetainedGeneration(
			int lastEntityCollectionPrimaryKey,
			@Nonnull Map<String, CollectionFileReference> collectionFileIndex
		) {
			final DataFilesBulkInfo oldestGeneration = new DataFilesBulkInfo(
				new CatalogBootstrap(1L, 1, OffsetDateTime.now(), new FileLocation(0L, 1)),
				new CatalogHeader<>(
					PersistenceService.STORAGE_PROTOCOL_VERSION,
					1L,
					null,
					collectionFileIndex,
					Map.of(),
					UUID.randomUUID(),
					CATALOG_NAME,
					CatalogState.ALIVE,
					lastEntityCollectionPrimaryKey,
					1.0
				)
			);
			try (
				ObsoleteFileMaintainer maintainer = newMaintainer(true, () -> oldestGeneration)
			) {
				maintainer.createWalPurgeCallback().purgeFilesUpTo(1L);
			}
		}

		/**
		 * Creates an empty file with the given name inside the temporary catalog storage path.
		 *
		 * @param fileName name of the file to create
		 * @return path of the created file
		 */
		@Nonnull
		private Path createFile(@Nonnull String fileName) throws IOException {
			return Files.createFile(ObsoleteFileMaintainerTest.this.catalogStoragePath.resolve(fileName));
		}

	}

}
