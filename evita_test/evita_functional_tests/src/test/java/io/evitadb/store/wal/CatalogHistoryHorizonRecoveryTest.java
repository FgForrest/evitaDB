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

package io.evitadb.store.wal;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.core.Evita;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException.WalKind;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.store.wal.CatalogWriteAheadLog.getFirstAndLastVersionsFromWalFile;
import static io.evitadb.store.wal.CatalogWriteAheadLog.getIndexFromWalFileName;
import static io.evitadb.test.EvitaTestSupport.catalogDirectory;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the write-ahead log floor report being recovered when a catalog opens.
 *
 * Rotation announces the horizon its deletions imply exactly once and then forgets it - it deletes the files *before*
 * deriving the floor from them. A request the retention floor refuses is parked in memory, and a crash in between
 * drops it, so history no surviving log file can ever replay stays on disk for the life of the catalog. Opening the
 * catalog re-derives that floor from the files that are actually there, which is what this test exercises.
 *
 * Everything here runs against a real engine: real transactions, real rotation, real purge, real restart. Nothing is
 * mocked and no write-ahead log bytes are hand-shaped - a fixture assembled by hand can express states the commit
 * protocol cannot produce, and a test built on one proves nothing about production.
 *
 * The scenario is the one the recovery exists for:
 *
 * 1. a reader parked on the version the catalog went live at pins that version for as long as the session lives,
 * 2. transactions rotate the log until its oldest files are purged,
 * 3. the floor those deletions imply is refused by the pin and parked in memory,
 * 4. the process dies before anything releases the pin, which is what drops the parked request,
 * 5. the restart derives the same floor from the surviving files and gives the unreachable history up.
 *
 * Step 4 is a crash and not a shutdown, because an orderly shutdown cannot reach this state: `Evita#close` closes
 * every session before it terminates the catalogs, and the pin a closing session gives back drains the refused
 * request while the persistence service is still open. The crash is reproduced by copying the storage folder out
 * from under the live engine - the bytes a crash at that instant would have left behind - and booting the second
 * engine over the copy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Write-ahead log floor recovery when a catalog opens")
@Tag(STORAGE)
@Tag(WAL)
class CatalogHistoryHorizonRecoveryTest implements EvitaTestSupport {
	/**
	 * Attribute the transactions below write, sized so that a handful of them fill a whole log file.
	 */
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	/**
	 * Length of the attribute value written by each transaction - roughly a quarter of a log file, so that the log
	 * rotates every few transactions.
	 */
	private static final int PAYLOAD_LENGTH = 4_096;
	/**
	 * Number of transactions written after go-live. Enough to rotate the log several times over, so that more than one
	 * file is purged and the floor the purge implies sits well above the version the reader pins.
	 */
	private static final int TRANSACTION_COUNT = 30;
	/**
	 * Size a log file may reach before it is rotated away - the same narrow value the other rotation tests use.
	 */
	private static final long WAL_FILE_SIZE_BYTES = 16_384L;
	/**
	 * Number of log files kept behind the active one.
	 */
	private static final int WAL_FILE_COUNT_KEPT = 2;
	/**
	 * Upper bound for both waits below. Rotation, purge and the reconciliation are all driven by background tasks that
	 * expose no completion seam to latch onto - what they produce is a change on the file system - so the test polls
	 * for their outcome under a bound generous enough that a loaded machine cannot expire it.
	 */
	private static final long WAIT_TIMEOUT_MILLIS = 60_000L;
	/**
	 * Interval between two polls of the state the waits above are waiting for.
	 */
	private static final long POLL_INTERVAL_MILLIS = 250L;
	/**
	 * Page size used when the whole retained window is read at once - comfortably above the handful of records
	 * {@link #TRANSACTION_COUNT} transactions can produce.
	 */
	private static final int MAX_RETAINED_RECORDS_READ = 1_000;

	private TestPaths livePaths;
	private TestPaths recoveredPaths;

	/**
	 * Reads the number of records the catalog bootstrap file currently holds - the retained history window measured
	 * on disk, which is what a trim shortens.
	 *
	 * @param catalogDirectory the catalog folder to read the bootstrap file from
	 * @return the number of bootstrap records on disk
	 */
	private static int bootstrapRecordCount(@Nonnull Path catalogDirectory) {
		final File bootstrapFile = catalogDirectory
			.resolve(CatalogPersistenceService.getCatalogBootstrapFileName(TEST_CATALOG))
			.toFile();
		return CatalogBootstrap.getRecordCount(bootstrapFile.length());
	}

	/**
	 * Lists the write-ahead log files present in the catalog folder, oldest index first.
	 *
	 * @param catalogDirectory the catalog folder to list
	 * @return the log files, sorted by their index
	 */
	@Nonnull
	private static File[] listWalFiles(@Nonnull Path catalogDirectory) {
		final File[] walFiles = catalogDirectory.toFile().listFiles(
			(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
		);
		final File[] result = walFiles == null ? new File[0] : walFiles;
		Arrays.sort(result, Comparator.comparingInt(file -> getIndexFromWalFileName(file.getName())));
		return result;
	}

	/**
	 * Returns the catalog version of the oldest bootstrap record still retained, read through the same public listing
	 * a client would use to discover the window time travel can reach.
	 *
	 * @param evita the running engine to ask
	 * @return the oldest retained catalog version
	 */
	private static long oldestRetainedVersion(@Nonnull Evita evita) {
		return oldestRetainedBlocks(evita, 1).get(0).endVersion();
	}

	/**
	 * Returns the oldest retained version blocks, oldest first.
	 *
	 * @param evita    the running engine to ask
	 * @param pageSize how many blocks to ask for
	 * @return the retained blocks, oldest first
	 */
	@Nonnull
	private static List<MaterializedVersionBlock> oldestRetainedBlocks(@Nonnull Evita evita, int pageSize) {
		final PaginatedList<MaterializedVersionBlock> versions = evita
			.getCatalogInstanceOrThrowException(TEST_CATALOG)
			.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, pageSize);
		assertTrue(!versions.getData().isEmpty(), "The catalog must retain at least one bootstrap record!");
		return versions.getData();
	}

	/**
	 * Returns the catalog version of every bootstrap record still retained, oldest first - one per block, since each
	 * block ends at the version of the record that introduced it.
	 *
	 * @param evita the running engine to ask
	 * @return the retained record versions, ascending
	 */
	@Nonnull
	private static List<Long> retainedRecordVersions(@Nonnull Evita evita) {
		return oldestRetainedBlocks(evita, MAX_RETAINED_RECORDS_READ)
			.stream()
			.map(MaterializedVersionBlock::endVersion)
			.toList();
	}

	/**
	 * Writes {@link #TRANSACTION_COUNT} transactions, each carrying a payload large enough to fill a quarter of a log
	 * file. Every commit waits until its changes are visible, so the versions the rotation queues for removal are
	 * actually processed - a queued removal is only carried out once the version it belongs to has been published.
	 *
	 * @param evita the running engine to write into
	 */
	private static void writeTransactions(@Nonnull Evita evita) {
		final String payload = "x".repeat(PAYLOAD_LENGTH);
		for (int i = 0; i < TRANSACTION_COUNT; i++) {
			final int primaryKey = i + 1;
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, primaryKey)
							.setAttribute(ATTRIBUTE_PAYLOAD, payload + primaryKey)
					);
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);
		}
	}

	/**
	 * Waits until the log has actually purged its oldest files - the state the whole test depends on, and one that
	 * rotation only reaches once the versions inside those files have been processed.
	 *
	 * @param catalogDirectory the catalog folder to watch
	 */
	private static void awaitWalPurge(@Nonnull Path catalogDirectory) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
		while (System.currentTimeMillis() < deadline) {
			final File[] walFiles = listWalFiles(catalogDirectory);
			if (walFiles.length > 1 && getIndexFromWalFileName(walFiles[0].getName()) > 0) {
				return;
			}
			//noinspection BusyWait
			Thread.sleep(POLL_INTERVAL_MILLIS);
		}
	}

	/**
	 * Waits until the reconciliation submitted when the catalog opened has given the unreachable history up.
	 *
	 * @param evita                the freshly opened engine
	 * @param oldestRetainedBefore the oldest retained version the restart inherited
	 * @return the oldest retained version once it moved, or the unchanged one when the wait timed out
	 */
	private static long awaitHistoryGivenUp(
		@Nonnull Evita evita,
		long oldestRetainedBefore
	) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
		long oldestRetained = oldestRetainedVersion(evita);
		while (oldestRetained <= oldestRetainedBefore && System.currentTimeMillis() < deadline) {
			//noinspection BusyWait
			Thread.sleep(POLL_INTERVAL_MILLIS);
			oldestRetained = oldestRetainedVersion(evita);
		}
		return oldestRetained;
	}

	/**
	 * Copies the whole storage folder out from under the running engine - the bytes a crash at this instant would
	 * have left behind. A file that disappears between the listing and the copy is one the engine has just reclaimed
	 * and a crash would not have preserved either, so it is skipped rather than failing the snapshot.
	 *
	 * @param source the folder to snapshot
	 * @param target the folder to snapshot it into
	 */
	private static void snapshotStorageFolder(@Nonnull Path source, @Nonnull Path target) throws IOException {
		try (final Stream<Path> paths = Files.walk(source)) {
			for (final Path path : paths.toList()) {
				final Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else {
					try {
						Files.createDirectories(destination.getParent());
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					} catch (NoSuchFileException ignored) {
						log.debug("File `{}` vanished while the storage folder was snapshotted.", path);
					}
				}
			}
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		this.livePaths = createTestPaths(CatalogHistoryHorizonRecoveryTest.class.getSimpleName());
		this.recoveredPaths = createTestPaths(CatalogHistoryHorizonRecoveryTest.class.getSimpleName() + "Recovered");
		Files.createDirectories(this.livePaths.storage());
		Files.createDirectories(this.recoveredPaths.storage());
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.livePaths);
		cleanupTestPaths(this.recoveredPaths);
	}

	@Test
	@DisplayName("should give up on open the history no surviving log file can replay")
	void shouldRecoverTheFloorReportRotationLostWhenTheCatalogOpens() throws Exception {
		final long oldestRetainedBeforeRestart;
		final List<Long> retainedRecordsBeforeRestart;

		try (final Evita evita = bootEvita(this.livePaths)) {
			evita.waitUntilFullyInitialized();
			defineCatalogAndGoLive(evita);

			// resolved only once the catalog exists, and by looking rather than by deriving - see `catalogDirectory`
			final Path liveCatalogDirectory = catalogDirectory(this.livePaths.storage(), TEST_CATALOG);

			// deliberately never closed: a session pins the catalog version it reads and gives the pin back when it
			// closes, and that release drains whatever the pin refused. The pin has to still be held when the crash
			// below is taken, or the history this test needs left behind is trimmed before the snapshot is
			final EvitaSessionContract pinningReader = evita.createReadOnlySession(TEST_CATALOG);
			final long pinnedVersion = pinningReader.getCatalogVersion();
			assertTrue(pinnedVersion > 0L, "The catalog must be alive before the reader pins a version!");

			writeTransactions(evita);
			awaitWalPurge(liveCatalogDirectory);

			retainedRecordsBeforeRestart = retainedRecordVersions(evita);
			oldestRetainedBeforeRestart = oldestRetainedVersion(evita);
			assertTrue(
				oldestRetainedBeforeRestart <= pinnedVersion,
				"The pin must have held the retained history at or below the version it pinned (observed `"
					+ oldestRetainedBeforeRestart + "`, pinned `" + pinnedVersion + "`)!"
			);

			snapshotStorageFolder(this.livePaths.storage(), this.recoveredPaths.storage());
		}

		// resolved from the snapshot rather than alongside the live one: the copy only exists now, and it carries
		// whatever folder name the live catalog had
		final Path recoveredCatalogDirectory = catalogDirectory(this.recoveredPaths.storage(), TEST_CATALOG);

		// the snapshot has to hold the state the recovery is about - purged log, untrimmed history - and saying so
		// out loud is the difference between a test that guards the behaviour and one that is trivially true
		final File[] survivingWalFiles = listWalFiles(recoveredCatalogDirectory);
		assertTrue(
			survivingWalFiles.length > 1,
			"Rotation must have left the file it appends to plus at least one it can replay from!"
		);
		final int oldestWalFileIndex = getIndexFromWalFileName(survivingWalFiles[0].getName());
		assertTrue(
			oldestWalFileIndex > 0,
			"The fixture must have purged at least one log file - a log that still holds file `0` implies no floor!"
		);
		// read from the oldest file's tail rather than its head: the tail is written when a file is rotated away, so
		// it is an independent record of the version production derives by reading that same file's head
		final long firstReplayableVersion = getFirstAndLastVersionsFromWalFile(
			survivingWalFiles[0], WalKind.CATALOG
		).firstVersion();
		final int bootstrapRecordsBeforeRestart = bootstrapRecordCount(recoveredCatalogDirectory);
		assertTrue(
			oldestRetainedBeforeRestart < firstReplayableVersion,
			"The fixture never reached the state under test: history below the first replayable version `"
				+ firstReplayableVersion + "` must still be retained, but the oldest retained version is `"
				+ oldestRetainedBeforeRestart + "`!"
		);

		// what the recovery is expected to leave behind: the trim keeps the records at or above the floor and drops
		// every record below it (see `copyAllNecessaryBootstrapRecords`), so this states both halves at once - nothing
		// the surviving log files can still replay was given up, and nothing they cannot was kept
		final List<Long> recordsExpectedToSurvive = retainedRecordsBeforeRestart.stream()
			.filter(recordVersion -> recordVersion >= firstReplayableVersion)
			.toList();
		final long newestRecordBeforeRestart = retainedRecordsBeforeRestart
			.get(retainedRecordsBeforeRestart.size() - 1);
		assertTrue(
			!recordsExpectedToSurvive.isEmpty(),
			"The fixture must leave at least one record above the floor, or the catalog would lose its whole history!"
		);

		try (final Evita recovered = bootEvita(this.recoveredPaths)) {
			recovered.waitUntilFullyInitialized();

			final long oldestRetainedAfterRestart = awaitHistoryGivenUp(recovered, oldestRetainedBeforeRestart);
			assertTrue(
				oldestRetainedAfterRestart > oldestRetainedBeforeRestart,
				"Opening the catalog must have given up the history no surviving log file can replay (still at `"
					+ oldestRetainedAfterRestart + "`, expected above `" + oldestRetainedBeforeRestart + "`)!"
			);
			assertTrue(
				bootstrapRecordCount(recoveredCatalogDirectory) < bootstrapRecordsBeforeRestart,
				"The bootstrap file must have been trimmed by the recovery!"
			);

			// records the restart appended itself are not part of the question - it is the inherited ones that the
			// recovery either kept or gave up, so the comparison is made over those alone
			final List<Long> inheritedRecordsAfterRestart = retainedRecordVersions(recovered).stream()
				.filter(recordVersion -> recordVersion <= newestRecordBeforeRestart)
				.toList();
			assertEquals(
				recordsExpectedToSurvive, inheritedRecordsAfterRestart,
				"The recovery must give up exactly the records below the first replayable version `"
					+ firstReplayableVersion + "` - no more, and no fewer!"
			);

			// and the catalog is still a working catalog afterwards, not merely a shorter one
			try (final EvitaSessionContract session = recovered.createReadOnlySession(TEST_CATALOG)) {
				assertTrue(
					session.getEntity(Entities.PRODUCT, TRANSACTION_COUNT).isPresent(),
					"The data written before the crash must still be readable!"
				);
			}
		}
	}

	/**
	 * Boots an engine over the given test directories with a log narrow enough to rotate every few transactions and an
	 * unlimited time travel budget - the size guard is the other driver of the horizon, and leaving it out of the way
	 * is what makes a horizon that moved attributable to the recovery under test.
	 *
	 * @param paths the directory triplet to boot over
	 * @return the booted engine
	 */
	@Nonnull
	private Evita bootEvita(@Nonnull TestPaths paths) {
		return new Evita(
			newTestEvitaConfigurationBuilder(paths)
				.storage(
					StorageOptions.builder()
						.storageDirectory(paths.storage())
						.workDirectory(paths.work())
						.timeTravelEnabled(true)
						.timeTravelSizeLimitBytes(-1L)
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.walFileSizeBytes(WAL_FILE_SIZE_BYTES)
						.walFileCountKept(WAL_FILE_COUNT_KEPT)
						.build()
				)
				.build()
		);
	}

	/**
	 * Creates the catalog, declares the single entity type the transactions below write, and turns the catalog
	 * transactional.
	 *
	 * @param evita the running engine to set up
	 */
	private void defineCatalogAndGoLive(@Nonnull Evita evita) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_PAYLOAD, String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

}
