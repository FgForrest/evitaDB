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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a catalog renamed, or put in place of another one, right after an `ALIVE` round that compacted the
 * catalog data file and deferred its checkpoint loads under its new name with all of its data.
 *
 * With sync writes on and a checkpoint interval, an `ALIVE` round writes its data and builds its bootstrap record but
 * defers writing the record until the next checkpoint. When that round compacts the catalog data file, the record it
 * built names the new file while the published one still names the file it replaced. A rename or replace arriving
 * before the checkpoint writes the catalog header through the round's storage - into the new file - and publishes a
 * bootstrap record of its own, which must therefore name the new file too.
 *
 * The engine here never reaches a checkpoint on its own (one-hour interval) and keeps its history (time travel on), so
 * the replaced catalog data file stays on disk and a load failure cannot be blamed on its removal. Two compaction
 * gates are exercised:
 *
 * - **the round compacts, the relabel does not** - the minimal compaction interval lets exactly one compaction through:
 *   the one inside the round, issued after the interval elapsed; the relabel follows within the same interval
 * - **both compact** - the interval is zero, so the relabel compacts the catalog data file once more
 *
 * A control runs the second gate without a checkpoint interval: the round publishes its record at once, so whatever
 * fails there does not depend on the deferral.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog rename and replace right after a compaction whose checkpoint is deferred")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class CatalogRelabelAfterDeferredCompactionTest implements EvitaTestSupport {
	private static final String CATALOG = "relabelSource";
	private static final String RENAMED = "relabelTarget";
	private static final String REPLACED = "relabelReplaced";
	private static final String PRODUCT = "product";
	private static final String NAME = "name";
	private static final int PRODUCT_COUNT = 10;
	/**
	 * Long enough that no checkpoint fires on its own during the test - every round in `ALIVE` defers.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * Minimal interval between two compactions of the catalog data file when the relabel must not compact. Long enough
	 * for the relabel to follow the compacting round inside it, short enough to wait out once before the round.
	 */
	private static final long ONE_COMPACTION_INTERVAL_MILLIS = 3_000L;

	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	@Nullable private Evita evita;
	@Nullable private Evita reopened;

	@AfterEach
	void tearDown() {
		if (this.reopened != null) {
			this.reopened.close();
		}
		if (this.evita != null) {
			this.evita.close();
		}
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * The relabel follows the compacting round inside the minimal compaction interval, so only the round compacts.
	 */
	@Nested
	@DisplayName("When only the round compacts")
	class WhenOnlyTheRoundCompacts {

		@Test
		@DisplayName("should rename the catalog and load it under the new name after a restart")
		void shouldRenameAndReloadUnderTheNewName() {
			assertRenamedCatalogSurvivesRestart(Setup.DEFERRED_ROUND_COMPACTS);
		}

		@Test
		@DisplayName("should rename the catalog and load it under the new name after a crash")
		void shouldRenameAndRecoverUnderTheNewNameAfterCrash() {
			assertRenamedCatalogSurvivesCrash(Setup.DEFERRED_ROUND_COMPACTS);
		}

		@Test
		@DisplayName("should replace another catalog and load in its place after a restart")
		void shouldReplaceAndReloadUnderTheReplacedName() {
			assertReplacingCatalogSurvivesRestart(Setup.DEFERRED_ROUND_COMPACTS);
		}
	}

	/**
	 * Every flush compacts, the relabel's included.
	 */
	@Nested
	@DisplayName("When the relabel compacts too")
	class WhenTheRelabelCompactsToo {

		@Test
		@DisplayName("should rename the catalog and load it under the new name after a restart")
		void shouldRenameAndReloadUnderTheNewName() {
			assertRenamedCatalogSurvivesRestart(Setup.DEFERRED_BOTH_COMPACT);
		}

		@Test
		@DisplayName("should replace another catalog and load in its place after a restart")
		void shouldReplaceAndReloadUnderTheReplacedName() {
			assertReplacingCatalogSurvivesRestart(Setup.DEFERRED_BOTH_COMPACT);
		}
	}

	/**
	 * Control: the round publishes its record at once (no checkpoint interval), so nothing is deferred - but both the
	 * round, or the load, and the relabel compact, all at one catalog version.
	 */
	@Nested
	@DisplayName("When the round publishes at once and both compact")
	class WhenTheRoundPublishesAtOnce {

		@Test
		@DisplayName("should rename the catalog and load it under the new name after a restart")
		void shouldRenameAndReloadUnderTheNewName() {
			assertRenamedCatalogSurvivesRestart(Setup.PUBLISHED_BOTH_COMPACT);
		}

		@Test
		@DisplayName("should replace another catalog and load in its place after a restart")
		void shouldReplaceAndReloadUnderTheReplacedName() {
			assertReplacingCatalogSurvivesRestart(Setup.PUBLISHED_BOTH_COMPACT);
		}

		@Test
		@DisplayName("should rename a catalog loaded from disk at the version of its last round")
		void shouldRenameCatalogRightAfterItWasLoaded() {
			final TestPaths paths = allocatePaths("relabelRenameAfterLoad");
			final Scenario scenario = runCompactingRound(paths, Setup.PUBLISHED_BOTH_COMPACT);
			CatalogRelabelAfterDeferredCompactionTest.this.evita.close();
			final Evita theEvita = new Evita(configurationOf(paths, Setup.PUBLISHED_BOTH_COMPACT));
			CatalogRelabelAfterDeferredCompactionTest.this.evita = theEvita;
			theEvita.waitUntilFullyInitialized();

			assertDoesNotThrow(
				() -> theEvita.renameCatalog(CATALOG, RENAMED),
				"The rename must succeed on a freshly loaded catalog. " + scenario.describe()
			);
			assertEquals(
				scenario.names(), readNames(theEvita, RENAMED), "The renamed catalog must serve the newest data."
			);
		}
	}

	/**
	 * Selects how the engine checkpoints and compacts.
	 *
	 * @param compactionIntervalMillis the minimal interval between two compactions of the catalog data file; when
	 *                                 zero, every flush with any waste compacts
	 * @param deferred                 whether `ALIVE` rounds defer their checkpoint
	 */
	private record Setup(long compactionIntervalMillis, boolean deferred) {
		static final Setup DEFERRED_ROUND_COMPACTS = new Setup(ONE_COMPACTION_INTERVAL_MILLIS, true);
		static final Setup DEFERRED_BOTH_COMPACT = new Setup(0L, true);
		static final Setup PUBLISHED_BOTH_COMPACT = new Setup(0L, false);
	}

	/**
	 * Renames the catalog right after a deferred compacting round, then restarts the engine cleanly.
	 *
	 * @param setup how the engine checkpoints and compacts
	 */
	private void assertRenamedCatalogSurvivesRestart(@Nonnull Setup setup) {
		final TestPaths paths = allocatePaths("relabelRename");
		final Scenario scenario = runCompactingRound(paths, setup);
		final Evita theEvita = this.evita;

		assertDoesNotThrow(
			() -> theEvita.renameCatalog(CATALOG, RENAMED),
			"The rename must succeed right after a deferred compacting round. " + scenario.describe()
		);
		assertEquals(scenario.names(), readNames(theEvita, RENAMED), "The renamed catalog must serve the newest data.");
		final String afterRelabel = describeDisk(paths);

		theEvita.close();
		this.evita = null;
		assertReopensWithNames(paths, RENAMED, scenario, afterRelabel);
	}

	/**
	 * Renames the catalog right after a deferred compacting round and recovers a copy of the storage of the running
	 * engine - the image a crash at that moment would leave.
	 *
	 * @param setup how the engine checkpoints and compacts
	 */
	private void assertRenamedCatalogSurvivesCrash(@Nonnull Setup setup) {
		final TestPaths paths = allocatePaths("relabelRenameCrash");
		final Scenario scenario = runCompactingRound(paths, setup);
		final Evita theEvita = this.evita;

		assertDoesNotThrow(
			() -> theEvita.renameCatalog(CATALOG, RENAMED),
			"The rename must succeed right after a deferred compacting round. " + scenario.describe()
		);
		assertEquals(scenario.names(), readNames(theEvita, RENAMED), "The renamed catalog must serve the newest data.");

		final TestPaths image = allocatePaths("relabelRenameCrashImage");
		final SortedMap<String, String> beforeCopy = listFiles(paths.storage());
		copyRecursively(paths.storage(), image.storage());
		assertEquals(
			beforeCopy, listFiles(paths.storage()),
			"The storage of the engine changed while it was being copied - the image is not the state of one moment"
		);
		final String afterRelabel = describeDisk(image);
		theEvita.close();
		this.evita = null;
		assertReopensWithNames(image, RENAMED, scenario, afterRelabel);
	}

	/**
	 * Puts the catalog in place of another one right after a deferred compacting round, then restarts the engine
	 * cleanly.
	 *
	 * @param setup how the engine checkpoints and compacts
	 */
	private void assertReplacingCatalogSurvivesRestart(@Nonnull Setup setup) {
		final TestPaths paths = allocatePaths("relabelReplace");
		final Scenario scenario = runCompactingRound(paths, setup);
		final Evita theEvita = this.evita;

		assertDoesNotThrow(
			() -> theEvita.replaceCatalog(CATALOG, REPLACED),
			"The replace must succeed right after a deferred compacting round. " + scenario.describe()
		);
		assertEquals(
			scenario.names(), readNames(theEvita, REPLACED), "The replacing catalog must serve the newest data."
		);
		final String afterRelabel = describeDisk(paths);

		theEvita.close();
		this.evita = null;
		assertReopensWithNames(paths, REPLACED, scenario, afterRelabel);
	}

	/**
	 * Starts an engine over `paths` and asserts the catalog loads under `catalogName` with the newest data.
	 */
	private void assertReopensWithNames(
		@Nonnull TestPaths paths,
		@Nonnull String catalogName,
		@Nonnull Scenario scenario,
		@Nonnull String afterRelabel
	) {
		final Evita theReopened = assertDoesNotThrow(
			() -> new Evita(configurationOf(paths, scenario.setup())),
			"The engine must start over the relabelled catalog. " + scenario.describe() + " " + afterRelabel
		);
		this.reopened = theReopened;
		theReopened.waitUntilFullyInitialized();
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> theReopened.getCatalogState(catalogName).map(it -> !it.isTransitional()).orElse(true));
		assertEquals(
			CatalogState.ALIVE, theReopened.getCatalogState(catalogName).orElse(null),
			"The catalog must load under `" + catalogName + "`. Known catalogs: " + theReopened.getCatalogNames() +
				". " + scenario.describe() + " " + afterRelabel
		);
		assertEquals(
			scenario.names(), readNames(theReopened, catalogName),
			"The reloaded catalog must serve the newest data."
		);
	}

	/**
	 * Creates the catalog (and the one to be replaced), takes it live and runs one `ALIVE` round that compacts the
	 * catalog data file and defers its checkpoint.
	 *
	 * @param paths the directories of the engine
	 * @param setup how the engine checkpoints and compacts
	 * @return what the round left behind
	 */
	@Nonnull
	private Scenario runCompactingRound(@Nonnull TestPaths paths, @Nonnull Setup setup) {
		final long compactionIntervalMillis = setup.compactionIntervalMillis();
		final Evita theEvita = new Evita(configurationOf(paths, setup));
		this.evita = theEvita;

		theEvita.defineCatalog(REPLACED).updateViaNewSession(theEvita);
		theEvita.updateCatalog(REPLACED, EvitaSessionContract::goLiveAndClose);

		theEvita.defineCatalog(CATALOG).updateViaNewSession(theEvita);
		theEvita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(NAME, String.class, thatIs -> thatIs.filterable())
					.updateVia(session);
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.createNewEntity(PRODUCT, pk).setAttribute(NAME, "product-" + pk).upsertVia(session);
				}
			}
		);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final int publishedFileIndex = lastPublishedRecord(paths).catalogFileIndex();

		if (compactionIntervalMillis > 0L) {
			// whatever compacted last did so no later than here - after this wait the round may compact once
			await()
				.pollDelay(compactionIntervalMillis + 250L, TimeUnit.MILLISECONDS)
				.atMost(compactionIntervalMillis + 1_000L, TimeUnit.MILLISECONDS)
				.until(() -> true);
		}

		theEvita.updateCatalog(
			CATALOG,
			session -> {
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.getEntity(PRODUCT, pk, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(NAME, "product-" + pk + "-round")
						.upsertVia(session);
				}
			}
		);
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> catalogOf(theEvita).getVersion() == catalogOf(theEvita).getLastCatalogVersionInMutationStream());
		final Map<Integer, String> names = readNames(theEvita, CATALOG);

		final Catalog catalog = catalogOf(theEvita);
		final Scenario scenario = new Scenario(
			setup, catalog.getVersion(), catalog.getLastPersistedCatalogVersion(),
			publishedFileIndex, names, describeDisk(paths)
		);
		if (setup.deferred()) {
			assertTrue(
				scenario.persistedVersion() < scenario.appliedVersion(),
				"Precondition: the round in ALIVE must have deferred its checkpoint, or this test proves nothing. " +
					scenario.describe()
			);
			assertEquals(
				publishedFileIndex, lastPublishedRecord(paths).catalogFileIndex(),
				"Precondition: the published record must still name the catalog data file from before the round. " +
					scenario.describe()
			);
		} else {
			assertEquals(
				scenario.appliedVersion(), scenario.persistedVersion(),
				"Precondition: the round must have published its record at once. " + scenario.describe()
			);
		}
		assertTrue(
			catalogDataFileIndexes(paths).stream().anyMatch(it -> it > publishedFileIndex),
			"Precondition: the round must have compacted the catalog data file, or this test proves nothing. " +
				scenario.describe()
		);
		return scenario;
	}

	/**
	 * Reads the name of every product.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog to read
	 * @return product primary key mapped to its name
	 */
	@Nonnull
	private static Map<Integer, String> readNames(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			session -> {
				final Map<Integer, String> names = new TreeMap<>();
				for (SealedEntity product : session.queryListOfSealedEntities(
					query(
						collection(PRODUCT),
						require(entityFetch(attributeContentAll()), page(1, PRODUCT_COUNT * 2))
					)
				)) {
					names.put(product.getPrimaryKeyOrThrowException(), product.getAttribute(NAME));
				}
				return names;
			}
		);
	}

	/**
	 * Describes the catalog folder: the last bootstrap record and the files present.
	 *
	 * @param paths the directories of the engine
	 * @return a one-line description for assertion messages
	 */
	@Nonnull
	private static String describeDisk(@Nonnull TestPaths paths) {
		final CatalogBootstrap last = lastPublishedRecord(paths);
		return "Last bootstrap record: version " + last.catalogVersion() + ", catalog file index " +
			last.catalogFileIndex() + ", offset index at " + last.fileLocation() + ". Catalog folder: " +
			listFiles(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG)).entrySet().stream()
				.map(it -> it.getKey() + " (" + it.getValue() + ")")
				.collect(Collectors.joining(", ")) + ".";
	}

	/**
	 * Reads the last record of the bootstrap file of the catalog folder - the record a reload follows.
	 *
	 * @param paths the directories of the engine
	 * @return the last bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap lastPublishedRecord(@Nonnull TestPaths paths) {
		final StorageOptions storageOptions = configurationOf(paths, Setup.DEFERRED_BOTH_COMPACT).storage();
		try (
			final Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				storagePrefix(paths),
				EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG),
				// bootstrap records are never compressed
				new StorageSettings(
					StorageOptions.builder(storageOptions).compress(false).build(),
					TransactionOptions.builder().build()
				)
			)
		) {
			return records.reduce((previous, next) -> next).orElseThrow();
		}
	}

	/**
	 * Returns the prefix the catalog's files are named with - the name of its bootstrap file without the suffix.
	 *
	 * @param paths the directories of the engine
	 * @return the storage prefix
	 */
	@Nonnull
	private static String storagePrefix(@Nonnull TestPaths paths) {
		try (final Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			final String bootstrapFileName = files
				.map(it -> it.getFileName().toString())
				.filter(it -> it.endsWith(CatalogPersistenceService.BOOT_FILE_SUFFIX))
				.findFirst()
				.orElseThrow();
			return bootstrapFileName.substring(
				0, bootstrapFileName.length() - CatalogPersistenceService.BOOT_FILE_SUFFIX.length()
			);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list the catalog directory!", ex);
		}
	}

	/**
	 * Lists the indexes of the catalog data files present on disk.
	 *
	 * @param paths the directories of the engine
	 * @return the file indexes
	 */
	@Nonnull
	private static List<Integer> catalogDataFileIndexes(@Nonnull TestPaths paths) {
		final String prefix = storagePrefix(paths) + '_';
		try (final Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			return files
				.map(it -> it.getFileName().toString())
				.filter(it -> it.startsWith(prefix) && it.endsWith(CatalogPersistenceService.CATALOG_FILE_SUFFIX))
				.map(it -> Integer.parseInt(
					it.substring(prefix.length(), it.length() - CatalogPersistenceService.CATALOG_FILE_SUFFIX.length())
				))
				.toList();
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list the catalog directory!", ex);
		}
	}

	/**
	 * Builds the configuration described on the class.
	 *
	 * @param paths the directories of the engine
	 * @param setup how the engine checkpoints and compacts
	 * @return the configuration
	 */
	@Nonnull
	private static EvitaConfiguration configurationOf(@Nonnull TestPaths paths, @Nonnull Setup setup) {
		final long compactionIntervalMillis = setup.compactionIntervalMillis();
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(paths.storage())
					.workDirectory(paths.work())
					.syncWrites(true)
					// the replaced data file stays on disk, so a load failure cannot be blamed on its removal
					.timeTravelEnabled(true)
					// any waste at all makes a flush compact - immediately when no interval is set, otherwise once the
					// interval elapsed (the forced override is switched off, or it would be clamped up and never wait)
					.minimalActiveRecordShare(1.0)
					.maxWasteActiveShare(compactionIntervalMillis > 0L ? 0.0 : 1.1)
					.fileSizeCompactionThresholdBytes(1L)
					.minCompactionIntervalMilliseconds(compactionIntervalMillis)
					.build()
			)
			// each engine its own export folder - the default one is shared and locked by whichever engine runs first
			.export(
				FileSystemExportOptions.builder()
					.directory(paths.export())
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.transactionWorkDirectory(paths.work().resolve("tx"))
					.checkpointIntervalInMillis(setup.deferred() ? NEVER_ELAPSES_MILLIS : 0L)
					.build()
			)
			.build();
	}

	/**
	 * Allocates test directories and remembers them for the clean-up.
	 *
	 * @param label a label naming the directories
	 * @return the directories
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths(label);
		this.allocatedPaths.add(paths);
		return paths;
	}

	/**
	 * Returns the currently published source catalog of an engine.
	 *
	 * @param evita the engine
	 * @return the catalog
	 */
	@Nonnull
	private static Catalog catalogOf(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/**
	 * Lists every file under a directory with its length and last modification time in milliseconds.
	 *
	 * @param root the directory
	 * @return the relative path of every file mapped to its length and modification time
	 */
	@Nonnull
	private static SortedMap<String, String> listFiles(@Nonnull Path root) {
		final SortedMap<String, String> listing = new TreeMap<>();
		try (final Stream<Path> tree = Files.walk(root)) {
			for (final Path file : tree.filter(Files::isRegularFile).toList()) {
				listing.put(
					root.relativize(file).toString(),
					Files.size(file) + " B, mtime " + Files.getLastModifiedTime(file).toMillis()
				);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list `" + root + "`!", ex);
		}
		return listing;
	}

	/**
	 * Copies a directory tree, keeping the modification time of every file so the copy can be compared with its
	 * source.
	 *
	 * @param source tree to copy from
	 * @param target tree to copy into
	 */
	private static void copyRecursively(@Nonnull Path source, @Nonnull Path target) {
		try {
			final List<Path> entries;
			try (final Stream<Path> tree = Files.walk(source)) {
				entries = tree.toList();
			}
			for (final Path entry : entries) {
				final Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(
						entry, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
					);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy `" + source + "` to `" + target + "`!", ex);
		}
	}

	/**
	 * What the deferred compacting round left behind.
	 *
	 * @param setup                    how the engine checkpoints and compacts
	 * @param appliedVersion           the newest version the engine had applied
	 * @param persistedVersion         the version the last published bootstrap record names
	 * @param publishedFileIndex       the catalog data file index the published record names
	 * @param names                    every product's name as the engine served it after the round
	 * @param disk                     the catalog folder after the round
	 */
	private record Scenario(
		@Nonnull Setup setup,
		long appliedVersion,
		long persistedVersion,
		int publishedFileIndex,
		@Nonnull Map<Integer, String> names,
		@Nonnull String disk
	) {

		/**
		 * Renders the scenario for assertion messages.
		 */
		@Nonnull
		String describe() {
			return "After the round: applied version " + this.appliedVersion + ", persisted version " +
				this.persistedVersion + ", published catalog file index " + this.publishedFileIndex + ". " + this.disk;
		}
	}

}
