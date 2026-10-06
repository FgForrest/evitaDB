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
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a compaction inside a round whose checkpoint is deferred never deletes a file the still-published
 * bootstrap record names.
 *
 * With sync writes on and a checkpoint interval, an `ALIVE` round writes its data and builds its bootstrap record
 * but defers writing the record until the next checkpoint. Until then the bootstrap file still publishes an older
 * version, and a reload after a crash follows that older record. When such a round compacts a data file, the file it
 * replaces is exactly what the published record still names - so it must outlive every reader **and** the publication
 * of the record that supersedes it.
 *
 * The engine here compacts every data file on every flush (thresholds opened fully), never reaches a checkpoint on its
 * own (one-hour interval), and keeps no history (time travel off), so a retired file is deleted the moment the obsolete
 * file purge reaches it. A few transactions then run, and a read session closes on the newest version - which is what
 * lets the purge run. The crash is simulated by copying the storage directory of the running, quiescent engine: the
 * image a process crash at that moment would leave, with nothing the operating system already holds lost.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Compaction inside a round whose checkpoint is deferred")
@Tag(STORAGE)
@Tag(TRANSACTION)
class DeferredCheckpointCompactionCrashRecoveryTest implements EvitaTestSupport {
	private static final String CATALOG = "deferredCompaction";
	private static final String PRODUCT = "product";
	private static final String NAME = "name";
	private static final int PRODUCT_COUNT = 10;
	private static final int ALIVE_ROUNDS = 3;
	/**
	 * Long enough that no checkpoint fires on its own during the test - every round in `ALIVE` defers.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * How long the test gives the asynchronous obsolete-file purge to act before it inspects the disk. A correct
	 * engine deletes nothing in that window, so the whole wait is spent only when the test passes.
	 */
	private static final long PURGE_GRACE_MILLIS = 5_000L;

	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	@Nullable private Evita evita;
	@Nullable private Evita recovered;

	@AfterEach
	void tearDown() {
		if (this.recovered != null) {
			this.recovered.close();
		}
		if (this.evita != null) {
			this.evita.close();
		}
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	@Test
	@DisplayName("should keep the catalog data file the published bootstrap record names")
	void shouldKeepTheCatalogFileThePublishedRecordNames() {
		final CrashImage image = runCompactingRoundsAndTakeCrashImage();

		final CatalogBootstrap published = lastPublishedRecord(image.paths());
		final Path publishedCatalogFile = EvitaTestSupport.catalogDirectory(image.paths().storage(), CATALOG).resolve(
			CatalogPersistenceService.getCatalogDataStoreFileName(storagePrefix(image.paths()), published.catalogFileIndex())
		);
		assertTrue(
			Files.isRegularFile(publishedCatalogFile),
			"The published bootstrap record (version " + published.catalogVersion() + ", catalog file index " +
				published.catalogFileIndex() + ") names `" + publishedCatalogFile.getFileName() + "`, which is gone " +
				"from the crash image. Applied version " + image.appliedVersion() + ", persisted version " +
				image.persistedVersion() + ". Files in the image:\n" + image.listing().keySet().stream()
				.map(it -> "\t" + it).collect(Collectors.joining("\n"))
		);
	}

	@Test
	@DisplayName("should reopen from the published record after a crash and replay the deferred rounds")
	void shouldReopenAfterCrashAndReplayTheDeferredRounds() {
		final CrashImage image = runCompactingRoundsAndTakeCrashImage();
		final Map<Integer, String> expectedNames = image.names();
		this.evita.close();
		this.evita = null;

		final Evita theRecovered = assertDoesNotThrow(
			() -> new Evita(configurationOf(image.paths())),
			"The engine must start over the crash image, loading the catalog from the bootstrap record published " +
				"before the crash (version " + image.persistedVersion() + ")."
		);
		this.recovered = theRecovered;
		theRecovered.waitUntilFullyInitialized();
		final CatalogState state = awaitSettledState(theRecovered);
		assertEquals(
			CatalogState.ALIVE, state,
			"The catalog must load from the bootstrap record published before the crash (version " +
				image.persistedVersion() + ") and replay the write-ahead log up to version " + image.appliedVersion() +
				", but it ended up " + state + ". Files in the image:\n" + image.listing().keySet().stream()
				.map(it -> "\t" + it).collect(Collectors.joining("\n"))
		);
		assertEquals(
			expectedNames, readNames(theRecovered),
			"The recovered catalog must hold every round committed before the crash."
		);
	}

	/**
	 * Builds the scenario described on the class and copies the storage of the running engine aside.
	 *
	 * @return the crash image and the versions the engine had applied and published when it was copied
	 */
	@Nonnull
	private CrashImage runCompactingRoundsAndTakeCrashImage() {
		final TestPaths paths = allocatePaths("deferredCompaction");
		final Evita theEvita = new Evita(configurationOf(paths));
		this.evita = theEvita;

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

		for (int round = 1; round <= ALIVE_ROUNDS; round++) {
			final int theRound = round;
			theEvita.updateCatalog(
				CATALOG,
				session -> {
					for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
						session.getEntity(PRODUCT, pk, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(NAME, "product-" + pk + "-round-" + theRound)
							.upsertVia(session);
					}
				}
			);
		}
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> catalogOf(theEvita).getVersion() == catalogOf(theEvita).getLastCatalogVersionInMutationStream());
		// the last reader of the older versions leaves - this is what lets the obsolete file purge run
		final Map<Integer, String> names = readNames(theEvita);

		final Catalog catalog = catalogOf(theEvita);
		final long appliedVersion = catalog.getVersion();
		final long persistedVersion = catalog.getLastPersistedCatalogVersion();
		assertTrue(
			persistedVersion < appliedVersion,
			"Precondition: the rounds in ALIVE must have deferred their checkpoint (applied " + appliedVersion +
				", persisted " + persistedVersion + "), or this test proves nothing."
		);
		assertTrue(
			catalogDataFileIndexes(paths).stream().anyMatch(it -> it > 0),
			"Precondition: the catalog data file must have been compacted at least once, or this test proves nothing."
		);

		// give the asynchronous purge its chance to act on what the departure above released
		final Path publishedCatalogFile = EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG).resolve(
			CatalogPersistenceService.getCatalogDataStoreFileName(
				storagePrefix(paths), lastPublishedRecord(paths).catalogFileIndex()
			)
		);
		try {
			await()
				.atMost(PURGE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(() -> !Files.exists(publishedCatalogFile));
		} catch (ConditionTimeoutException ignored) {
			// the correct outcome - the file survived the whole window
		}

		final TestPaths image = allocatePaths("deferredCompactionImage");
		final SortedMap<String, String> beforeCopy = listFiles(paths.storage());
		copyRecursively(paths.storage(), image.storage());
		assertEquals(
			beforeCopy, listFiles(paths.storage()),
			"The storage of the engine changed while it was being copied - the image is not the state of one moment"
		);
		final SortedMap<String, String> imageListing = listFiles(image.storage());
		assertEquals(beforeCopy, imageListing, "The image must hold every file of the storage, whole");
		assertEquals(
			appliedVersion, catalogOf(theEvita).getVersion(),
			"The catalog published a new version while its storage was being copied"
		);
		return new CrashImage(image, appliedVersion, persistedVersion, names, imageListing);
	}

	/**
	 * Waits until the catalog of a freshly started engine leaves the transitional states.
	 *
	 * @param evita the engine
	 * @return the state the catalog settled in
	 */
	@Nonnull
	private static CatalogState awaitSettledState(@Nonnull Evita evita) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> evita.getCatalogState(CATALOG).map(it -> !it.isTransitional()).orElse(false));
		return evita.getCatalogState(CATALOG).orElseThrow();
	}

	/**
	 * Reads the name of every product.
	 *
	 * @param evita the engine
	 * @return product primary key mapped to its name
	 */
	@Nonnull
	private static Map<Integer, String> readNames(@Nonnull Evita evita) {
		return evita.queryCatalog(
			CATALOG,
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
	 * Reads the last record of the bootstrap file - the record a reload follows.
	 *
	 * @param paths the directories of the engine
	 * @return the last published bootstrap record
	 */
	@Nonnull
	private CatalogBootstrap lastPublishedRecord(@Nonnull TestPaths paths) {
		final StorageOptions storageOptions = configurationOf(paths).storage();
		try (
			final Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				CATALOG,
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
	 * Builds the configuration described on the class for the given directories.
	 *
	 * @param paths the directories of the engine
	 * @return the configuration
	 */
	@Nonnull
	private static EvitaConfiguration configurationOf(@Nonnull TestPaths paths) {
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
					.timeTravelEnabled(false)
					// every flush compacts: `shouldCompact` holds on `activeRecordShare < maxWasteActiveShare` alone
					.minimalActiveRecordShare(1.0)
					.maxWasteActiveShare(1.1)
					.fileSizeCompactionThresholdBytes(1L)
					.minCompactionIntervalMilliseconds(0L)
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.transactionWorkDirectory(paths.work().resolve("tx"))
					.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
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
	 * Returns the currently published catalog of an engine.
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
					Files.size(file) + " bytes, modified at " + Files.getLastModifiedTime(file).toMillis()
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
	 * The storage of a running engine copied aside, with what the engine reported when it was copied.
	 *
	 * @param paths            the directories of the image
	 * @param appliedVersion   the newest version the engine had applied
	 * @param persistedVersion the version the last published bootstrap record names
	 * @param names            every product's name as the engine served it before the copy
	 * @param listing          every file of the image
	 */
	private record CrashImage(
		@Nonnull TestPaths paths,
		long appliedVersion,
		long persistedVersion,
		@Nonnull Map<Integer, String> names,
		@Nonnull SortedMap<String, String> listing
	) {
	}

}
