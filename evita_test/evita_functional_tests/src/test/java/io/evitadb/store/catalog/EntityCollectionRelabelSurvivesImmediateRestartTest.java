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
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that an entity collection renamed, or put in place of another one, survives an engine restart that
 * follows the relabel immediately - with no further transaction in between.
 *
 * In `ALIVE` the relabel is a transaction. It reaches the disk in the trunk round that incorporates it, which writes
 * the catalog header, publishes a bootstrap record and lets the write-ahead log treat the version as processed. A
 * restart afterwards replays nothing of that version, so whatever the published record reaches is the whole story:
 * the relabelled collection must load under its new name, with its content, and the old name must be gone.
 *
 * Two controls bound the claim: the same relabel followed by one more unrelated transaction, whose round publishes
 * again, and a relabel performed in `WARMING_UP` before going live, which never passes through a trunk round at all.
 * The cells run with a checkpoint published by every round - once with replaced data files removed as soon as no
 * reader needs them, once with them kept (time travel) - and with a checkpoint deferred past the relabel, which the
 * engine's close then publishes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity collection rename and replace followed by an immediate restart")
@Tag(STORAGE)
@Tag(SCHEMA)
class EntityCollectionRelabelSurvivesImmediateRestartTest implements EvitaTestSupport {
	private static final String CATALOG = "collectionRelabel";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final String BRAND = "brand";
	private static final String NAME = "name";
	private static final int PRODUCT_COUNT = 10;
	private static final int CATEGORY_COUNT = 3;
	/**
	 * Long enough that no checkpoint fires on its own during the test - every round in `ALIVE` defers.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;

	private final List<TestPaths> allocatedPaths = new ArrayList<>(1);
	@Nullable private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * Every `ALIVE` round publishes its bootstrap record at once.
	 */
	@Nested
	@DisplayName("When every round publishes its checkpoint")
	class WhenEveryRoundPublishes {

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across an immediate restart")
		void shouldKeepRenameAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.RENAME, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection replaced in ALIVE across an immediate restart")
		void shouldKeepReplaceAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.REPLACE, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across a restart after another transaction")
		void shouldKeepRenameAcrossRestartAfterAnotherTransaction() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.RENAME, Follow.ANOTHER_TRANSACTION);
		}

		@Test
		@DisplayName("should keep a collection replaced in ALIVE across a restart after another transaction")
		void shouldKeepReplaceAcrossRestartAfterAnotherTransaction() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.REPLACE, Follow.ANOTHER_TRANSACTION);
		}

		@Test
		@DisplayName("should keep a collection renamed in WARMING_UP across a restart after going live")
		void shouldKeepWarmUpRenameAcrossRestart() {
			assertWarmUpRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.RENAME);
		}

		@Test
		@DisplayName("should keep a collection replaced in WARMING_UP across a restart after going live")
		void shouldKeepWarmUpReplaceAcrossRestart() {
			assertWarmUpRelabelSurvivesRestart(Setup.PUBLISHED, Relabel.REPLACE);
		}
	}

	/**
	 * Every `ALIVE` round publishes its bootstrap record at once, and replaced data files stay on disk (time travel),
	 * so a stale published record still finds the file of the collection under its old name.
	 */
	@Nested
	@DisplayName("When every round publishes its checkpoint and the history is kept")
	class WhenEveryRoundPublishesAndHistoryIsKept {

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across an immediate restart")
		void shouldKeepRenameAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED_KEEPING_HISTORY, Relabel.RENAME, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection replaced in ALIVE across an immediate restart")
		void shouldKeepReplaceAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED_KEEPING_HISTORY, Relabel.REPLACE, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across a restart after another transaction")
		void shouldKeepRenameAcrossRestartAfterAnotherTransaction() {
			assertRelabelSurvivesRestart(Setup.PUBLISHED_KEEPING_HISTORY, Relabel.RENAME, Follow.ANOTHER_TRANSACTION);
		}
	}

	/**
	 * `ALIVE` rounds defer their checkpoint; the engine's close publishes the last one.
	 */
	@Nested
	@DisplayName("When the checkpoint is deferred until the close")
	class WhenTheCheckpointIsDeferred {

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across an immediate restart")
		void shouldKeepRenameAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.DEFERRED, Relabel.RENAME, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection replaced in ALIVE across an immediate restart")
		void shouldKeepReplaceAcrossImmediateRestart() {
			assertRelabelSurvivesRestart(Setup.DEFERRED, Relabel.REPLACE, Follow.NOTHING);
		}

		@Test
		@DisplayName("should keep a collection renamed in ALIVE across a restart after another transaction")
		void shouldKeepRenameAcrossRestartAfterAnotherTransaction() {
			assertRelabelSurvivesRestart(Setup.DEFERRED, Relabel.RENAME, Follow.ANOTHER_TRANSACTION);
		}
	}

	/**
	 * Selects how the engine checkpoints and whether it keeps replaced data files.
	 *
	 * @param deferred          whether `ALIVE` rounds defer their checkpoint
	 * @param timeTravelEnabled whether replaced data files stay on disk rather than being removed once unused
	 */
	private record Setup(boolean deferred, boolean timeTravelEnabled) {
		static final Setup PUBLISHED = new Setup(false, false);
		static final Setup PUBLISHED_KEEPING_HISTORY = new Setup(false, true);
		static final Setup DEFERRED = new Setup(true, false);
	}

	/**
	 * The relabel under test and the collections it is expected to leave behind.
	 */
	private enum Relabel {
		/**
		 * `product` becomes `brand`; `category` is untouched.
		 */
		RENAME,
		/**
		 * `product` takes the place of `category`; the content of `category` is dropped.
		 */
		REPLACE;

		/**
		 * Performs the relabel in the session.
		 */
		void perform(@Nonnull EvitaSessionContract session) {
			if (this == RENAME) {
				session.renameCollection(PRODUCT, BRAND);
			} else {
				session.replaceCollection(CATEGORY, PRODUCT);
			}
		}

		/**
		 * Computes the expected content of every collection from the content before the relabel.
		 */
		@Nonnull
		SortedMap<String, Map<Integer, String>> expected(@Nonnull SortedMap<String, Map<Integer, String>> before) {
			final SortedMap<String, Map<Integer, String>> expected = new TreeMap<>(before);
			final Map<Integer, String> products = expected.remove(PRODUCT);
			expected.put(this == RENAME ? BRAND : CATEGORY, products);
			return expected;
		}
	}

	/**
	 * What happens between the relabel and the restart.
	 */
	private enum Follow {
		/**
		 * The engine closes right after the relabel became visible.
		 */
		NOTHING,
		/**
		 * One more transaction, touching only the collection the relabel left alone or the relabelled one, commits first.
		 */
		ANOTHER_TRANSACTION
	}

	/**
	 * Creates the catalog, takes it live, relabels a collection in a transaction, optionally runs one more
	 * transaction, restarts and asserts the collections loaded from disk.
	 */
	private void assertRelabelSurvivesRestart(@Nonnull Setup setup, @Nonnull Relabel relabel, @Nonnull Follow follow) {
		final TestPaths paths = allocatePaths("collectionRelabel");
		final Evita theEvita = new Evita(configurationOf(paths, setup));
		this.evita = theEvita;
		createCatalog(theEvita);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final SortedMap<String, Map<Integer, String>> before = readCollections(theEvita);

		theEvita.updateCatalog(CATALOG, relabel::perform);
		awaitTrunkIncorporated(theEvita);
		SortedMap<String, Map<Integer, String>> expected = relabel.expected(before);
		assertEquals(expected, readCollections(theEvita), "Precondition: the relabel must be visible before the restart.");

		if (follow == Follow.ANOTHER_TRANSACTION) {
			final String relabelled = relabel == Relabel.RENAME ? BRAND : CATEGORY;
			theEvita.updateCatalog(
				CATALOG,
				session -> {
					session.getEntity(relabelled, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(NAME, "changed-after-relabel")
						.upsertVia(session);
				}
			);
			awaitTrunkIncorporated(theEvita);
			expected = new TreeMap<>(expected);
			final Map<Integer, String> changed = new TreeMap<>(expected.get(relabelled));
			changed.put(1, "changed-after-relabel");
			expected.put(relabelled, changed);
			assertEquals(expected, readCollections(theEvita), "Precondition: the follow-up must be visible.");
		}

		final Catalog catalog = (Catalog) theEvita.getCatalogInstance(CATALOG).orElseThrow();
		final String beforeClose = "Applied version " + catalog.getVersion() + ", persisted version " +
			catalog.getLastPersistedCatalogVersion() + ". Catalog folder: " + describeFolder(paths) + ".";
		theEvita.close();
		this.evita = null;

		assertReopensWith(paths, setup, expected, beforeClose);
	}

	/**
	 * Creates the catalog, relabels a collection while still warming up, takes it live, restarts and asserts the
	 * collections loaded from disk.
	 */
	private void assertWarmUpRelabelSurvivesRestart(@Nonnull Setup setup, @Nonnull Relabel relabel) {
		final TestPaths paths = allocatePaths("collectionRelabelWarmUp");
		final Evita theEvita = new Evita(configurationOf(paths, setup));
		this.evita = theEvita;
		createCatalog(theEvita);
		final SortedMap<String, Map<Integer, String>> before = readCollections(theEvita);

		theEvita.updateCatalog(CATALOG, relabel::perform);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final SortedMap<String, Map<Integer, String>> expected = relabel.expected(before);
		assertEquals(expected, readCollections(theEvita), "Precondition: the relabel must be visible before the restart.");

		final String beforeClose = "Catalog folder: " + describeFolder(paths) + ".";
		theEvita.close();
		this.evita = null;

		assertReopensWith(paths, setup, expected, beforeClose);
	}

	/**
	 * Starts an engine over `paths` and asserts the catalog loads with exactly the expected collections.
	 */
	private void assertReopensWith(
		@Nonnull TestPaths paths,
		@Nonnull Setup setup,
		@Nonnull SortedMap<String, Map<Integer, String>> expected,
		@Nonnull String beforeClose
	) {
		final Evita reopened = assertDoesNotThrow(
			() -> new Evita(configurationOf(paths, setup)),
			"The engine must start over the relabelled catalog. " + beforeClose
		);
		this.evita = reopened;
		reopened.waitUntilFullyInitialized();
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> reopened.getCatalogState(CATALOG).map(it -> !it.isTransitional()).orElse(true));
		assertEquals(
			CatalogState.ALIVE, reopened.getCatalogState(CATALOG).orElse(null),
			"The catalog must load after the restart. " + beforeClose
		);
		assertEquals(
			expected, readCollections(reopened),
			"The reloaded catalog must hold the relabelled collections, and the old name must be gone. " + beforeClose
		);
	}

	/**
	 * Defines `product` and `category` in a new catalog and fills them, still warming up.
	 */
	private static void createCatalog(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG).updateViaNewSession(evita);
		evita.updateCatalog(
			CATALOG,
			session -> {
				for (String entityType : List.of(PRODUCT, CATEGORY)) {
					session.defineEntitySchema(entityType)
						.withoutGeneratedPrimaryKey()
						.withAttribute(NAME, String.class, thatIs -> thatIs.filterable())
						.updateVia(session);
				}
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.createNewEntity(PRODUCT, pk).setAttribute(NAME, "product-" + pk).upsertVia(session);
				}
				for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
					session.createNewEntity(CATEGORY, pk).setAttribute(NAME, "category-" + pk).upsertVia(session);
				}
			}
		);
	}

	/**
	 * Waits until the engine incorporated every transaction it accepted into its catalog.
	 */
	private static void awaitTrunkIncorporated(@Nonnull Evita evita) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> {
				final Catalog catalog = (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
				return catalog.getVersion() == catalog.getLastCatalogVersionInMutationStream();
			});
	}

	/**
	 * Reads every collection of the catalog: entity type mapped to the name of each of its entities.
	 */
	@Nonnull
	private static SortedMap<String, Map<Integer, String>> readCollections(@Nonnull Evita evita) {
		return evita.queryCatalog(
			CATALOG,
			session -> {
				final SortedMap<String, Map<Integer, String>> collections = new TreeMap<>();
				for (String entityType : session.getAllEntityTypes()) {
					final Map<Integer, String> names = new TreeMap<>();
					for (SealedEntity entity : session.queryListOfSealedEntities(
						query(
							collection(entityType),
							require(entityFetch(attributeContentAll()), page(1, PRODUCT_COUNT * 2))
						)
					)) {
						names.put(entity.getPrimaryKeyOrThrowException(), entity.getAttribute(NAME));
					}
					collections.put(entityType, names);
				}
				return collections;
			}
		);
	}

	/**
	 * Lists the catalog folder with file sizes, for assertion messages.
	 */
	@Nonnull
	private static String describeFolder(@Nonnull TestPaths paths) {
		try (final Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			return files
				.sorted()
				.map(it -> {
					try {
						return it.getFileName() + " (" + Files.size(it) + " B)";
					} catch (IOException ex) {
						return it.getFileName() + " (?)";
					}
				})
				.collect(Collectors.joining(", "));
		} catch (IOException ex) {
			return "unreadable: " + ex.getMessage();
		}
	}

	/**
	 * Builds the configuration: own directories, sync writes, a checkpoint either per round or deferred, and time
	 * travel on or off.
	 */
	@Nonnull
	private static EvitaConfiguration configurationOf(@Nonnull TestPaths paths, @Nonnull Setup setup) {
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
					.timeTravelEnabled(setup.timeTravelEnabled())
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
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths(label);
		this.allocatedPaths.add(paths);
		return paths;
	}

}
