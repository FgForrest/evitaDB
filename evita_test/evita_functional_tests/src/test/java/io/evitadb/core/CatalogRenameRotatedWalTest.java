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
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers renaming a live catalog whose write-ahead log has already **rotated and been purged**, which is the one
 * rename shape no other test reaches.
 *
 * A rename moves no file: the folder keeps the prefix its files were created under, and the new catalog name is
 * written into the header only. Every log lookup therefore has to go through the prefix discovered from the
 * folder, never through the catalog name the header now carries. Derived from the name instead, the lookup
 * addresses a file that does not exist, reports the log empty and creates a fresh one beside the files it failed
 * to find — and the next boot then sees indexes that are not consecutive and refuses to open the catalog at all
 * (`AbstractMutationLog#getFirstAndLastWalFileIndex` asserts the run is unbroken).
 *
 * The sibling case in `EvitaTest#shouldKeepWriteAheadLogAddressableAfterRename` commits a single transaction, so
 * its log never leaves index 0 — with one file, a name-derived lookup and a prefix-derived one differ only in the
 * file they create, and both leave a consecutive run behind. Rotation is what makes the two observably diverge,
 * and it is why this fixture writes enough transactions to rotate the log **and** waits for the retention to
 * purge the oldest files, so the surviving run starts above zero.
 *
 * **Calibration** — reverting `DefaultCatalogPersistenceService` (the rename constructor, `walFileNameProvider`)
 * to build the provider from `catalogName` rather than from the inherited storage prefix fails this test on the
 * file-name assertion, with an 8-byte stub written under the new catalog name beside the two files it failed to
 * find: `[renamedCatalog_9.wal, testCatalog_8.wal, testCatalog_9.wal]`.
 *
 * Be precise about what that calibrates. It reconstructs the naming-provider defect, which was introduced *and*
 * fixed inside the folder-decoupling work itself — not the rename loop of issue #1414, which renamed the
 * bootstrap and the current data file and moved the directory. That code no longer exists to be reverted, so no
 * one-line edit can reconstruct it; what the two have in common is the on-disk result, an orphaned log under the
 * former name beside a stub under the new one, which is what this test detects. The argument that the original
 * mechanism is gone is architectural and belongs in the ADR, not in a calibration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Rename of a catalog whose write-ahead log has rotated")
@Tag(STORAGE)
@Tag(WAL)
@Tag(TRANSACTION)
class CatalogRenameRotatedWalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	private static final String RENAMED_CATALOG = "renamedCatalog";
	/**
	 * Long enough that a handful of transactions overflow a log file, so the fixture rotates within a count that
	 * still runs in the fast loop.
	 */
	private static final int PAYLOAD_LENGTH = 4_096;
	private static final int TRANSACTION_COUNT = 30;
	private static final long WAL_FILE_SIZE_BYTES = 16_384L;
	private static final int WAL_FILE_COUNT_KEPT = 2;
	private static final long WAIT_TIMEOUT_MILLIS = 60_000L;
	private static final long POLL_INTERVAL_MILLIS = 100L;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Lists the write-ahead log files present in the passed folder.
	 *
	 * @param catalogDirectory folder to list
	 * @return the log files found, never null
	 */
	@Nonnull
	private static File[] listWalFiles(@Nonnull Path catalogDirectory) {
		final File[] walFiles = catalogDirectory.toFile().listFiles(
			(dir, name) -> name.endsWith(PersistenceService.WAL_FILE_SUFFIX)
		);
		return walFiles == null ? new File[0] : walFiles;
	}

	/**
	 * Returns the names of the write-ahead log files present in the passed folder, in ascending index order.
	 *
	 * @param catalogDirectory folder to inspect
	 * @return the file names, never null
	 */
	@Nonnull
	private static Set<String> walFileNames(@Nonnull Path catalogDirectory) {
		return Arrays.stream(listWalFiles(catalogDirectory))
			.map(File::getName)
			.sorted()
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * Parses the index out of a write-ahead log file name locally, so the assertions depend on the file naming
	 * convention itself rather than on the storage module's own parser.
	 *
	 * @param walFileName the file name to parse
	 * @return the index the file carries
	 */
	private static int walIndex(@Nonnull String walFileName) {
		final String withoutSuffix = walFileName.substring(
			0, walFileName.length() - PersistenceService.WAL_FILE_SUFFIX.length()
		);
		return Integer.parseInt(withoutSuffix.substring(withoutSuffix.lastIndexOf('_') + 1));
	}

	/**
	 * Returns the sorted write-ahead log indexes present in the passed folder.
	 *
	 * @param catalogDirectory folder to inspect
	 * @return sorted indexes, never null
	 */
	@Nonnull
	private static int[] walIndexes(@Nonnull Path catalogDirectory) {
		return Arrays.stream(listWalFiles(catalogDirectory))
			.mapToInt(file -> walIndex(file.getName()))
			.sorted()
			.toArray();
	}

	/**
	 * Asserts the passed indexes form an unbroken ascending run - the premise the log asserts on when it opens,
	 * and the one the orphaned files of issue #1414 broke.
	 *
	 * @param indexes sorted indexes to check
	 * @param message what the run is being asserted about
	 */
	private static void assertContiguous(@Nonnull int[] indexes, @Nonnull String message) {
		assertTrue(indexes.length > 0, message + " - at least one write-ahead log file must exist!");
		for (int i = 1; i < indexes.length; i++) {
			final int position = i;
			assertEquals(
				indexes[i - 1] + 1, indexes[i],
				() -> message + " - the indexes must be consecutive, but " + Arrays.toString(indexes) +
					" jumps at position " + position
			);
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogRenameRotatedWalTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Keeps the rotated log addressable, and opens again after a restart")
	void shouldOpenRenamedCatalogWhoseWriteAheadLogHasRotated() throws Exception {
		defineCatalogAndGoLive();

		final String payload = "x".repeat(PAYLOAD_LENGTH);
		for (int i = 1; i <= TRANSACTION_COUNT; i++) {
			commitProduct(TEST_CATALOG, i, payload + i);
		}

		// the fixture is only meaningful once the retention has purged the oldest files - that is what moves the
		// surviving run above zero and makes a name-derived lookup observably differ from a prefix-derived one
		final Path folder = catalogFolder(TEST_CATALOG);
		awaitWalRotationAndPurge(folder);

		final Set<String> walFilesBeforeRename = walFileNames(folder);
		final int[] indexesBeforeRename = walIndexes(folder);
		assertTrue(
			indexesBeforeRename.length > 1 && indexesBeforeRename[0] > 0,
			() -> "The fixture must rotate AND purge the log before the rename - it is what this test is for - " +
				"but the surviving indexes were " + Arrays.toString(indexesBeforeRename) +
				". Raise TRANSACTION_COUNT or lower WAL_FILE_SIZE_BYTES if the write path got cheaper."
		);

		this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);

		// a rename relabels the catalog and moves nothing, so the folder must hold exactly the files it held
		// before - neither renamed onto the new name, nor joined by a stub created under it
		final Path folderAfterRename = catalogFolder(RENAMED_CATALOG);
		assertEquals(
			folder, folderAfterRename,
			"A rename must repoint the name, never move the data!"
		);
		assertEquals(
			walFilesBeforeRename, walFileNames(folderAfterRename),
			"The rename must leave every write-ahead log file exactly as it found it!"
		);
		assertContiguous(walIndexes(folderAfterRename), "After the rename");

		// the write path has to stay anchored to the same prefix too: a transaction committed after the rename
		// must extend the run the folder already carries rather than start a second one under the new name
		commitProduct(RENAMED_CATALOG, TRANSACTION_COUNT + 1, "committed after the rename");
		assertContiguous(walIndexes(folderAfterRename), "After a commit that follows the rename");

		// the last transaction stops at WAL persistence rather than at visibility, so the log may still hold an
		// unincorporated record when the engine goes down - which is what puts the restart on the replay path
		// instead of merely on the addressing one. It is deliberately not asserted that replay *did* run: trunk
		// incorporation is free to have caught up first, and a test that demanded it lose that race would be
		// flaky. Either way the entity has to be there afterwards, and only one of the two paths can deliver it.
		commitProductAwaitingWalOnly(RENAMED_CATALOG, TRANSACTION_COUNT + 2, "committed into the log only");

		// the reported failure was a boot that threw out of the log's constructor, so the restart is the assertion
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();

		assertTrue(
			this.evita.getCatalogNames().contains(RENAMED_CATALOG),
			"The renamed catalog must come back from the restart!"
		);
		assertEquals(
			TRANSACTION_COUNT + 2, productCount(RENAMED_CATALOG),
			"Every transaction committed around the rename must survive the restart!"
		);
		assertContiguous(walIndexes(catalogFolder(RENAMED_CATALOG)), "After the restart");
	}

	/**
	 * Waits until the log has rotated far enough for the retention to have purged the oldest files, which is what
	 * moves the first surviving index off zero.
	 *
	 * @param catalogDirectory folder to watch
	 */
	private static void awaitWalRotationAndPurge(@Nonnull Path catalogDirectory) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
		while (System.currentTimeMillis() < deadline) {
			final int[] indexes = walIndexes(catalogDirectory);
			if (indexes.length > 1 && indexes[0] > 0) {
				return;
			}
			//noinspection BusyWait
			Thread.sleep(POLL_INTERVAL_MILLIS);
		}
		// deliberately silent - the caller asserts the precondition, so a timeout surfaces as the assertion
		// naming what was actually on disk rather than as a bare timeout
	}

	/**
	 * Resolves the folder the passed catalog is bound to - an opaque token, not the catalog's name.
	 *
	 * @param catalogName the catalog whose folder to resolve
	 * @return the folder path, never null
	 */
	@Nonnull
	private Path catalogFolder(@Nonnull String catalogName) {
		return this.paths.storage()
			.resolve(this.evita.getCatalogFolderContext().folderIdFor(catalogName).id());
	}

	/**
	 * Commits a single product in its own transaction and waits until the change is visible.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void commitProduct(@Nonnull String catalogName, int primaryKey, @Nonnull String attributeValue) {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE));
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
			       .setAttribute(ATTRIBUTE_PAYLOAD, attributeValue)
		);
		assertNotNull(session.closeNowWithProgress().onChangesVisible().toCompletableFuture().join());
	}

	/**
	 * Commits a single product and waits only until its record is persisted in the log, leaving the trunk
	 * incorporation to run - or not run - before the engine goes down.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void commitProductAwaitingWalOnly(
		@Nonnull String catalogName,
		int primaryKey,
		@Nonnull String attributeValue
	) {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE));
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
			       .setAttribute(ATTRIBUTE_PAYLOAD, attributeValue)
		);
		assertNotNull(session.closeNowWithProgress().onWalAppended().toCompletableFuture().join());
	}

	/**
	 * Counts the products stored in the passed catalog.
	 *
	 * @param catalogName catalog to read
	 * @return number of products found
	 */
	private int productCount(@Nonnull String catalogName) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return session.getEntityCollectionSize(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Defines the catalog with a single product schema and takes it live.
	 */
	private void defineCatalogAndGoLive() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
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

	/**
	 * Configuration with a narrow log and a shallow retention - which is what makes the log rotate and purge
	 * within a transaction count that still belongs in the fast loop.
	 *
	 * @return the configuration, never null
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.walFileSizeBytes(WAL_FILE_SIZE_BYTES)
					.walFileCountKept(WAL_FILE_COUNT_KEPT)
					.build()
			)
			.build();
	}

}
