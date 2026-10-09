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
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.test.EvitaTestSupport;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that one `ALIVE` transaction may relabel several collections in a chain - a swap of two names through a
 * temporary one, a rename into a name another rename just vacated - or rename a collection and create a new one under
 * the vacated name.
 *
 * Every cell commits its scenario, checks the content served under every name, commits one more write into every
 * collection (which also publishes past the relabel), then restarts the engine and checks the content once more. A
 * restart that fails is retried once to tell a transient failure from a permanent one.
 *
 * Controls bound the claim: every rename alone, and the steps of every chain committed as separate transactions.
 * Every cell runs with the default checkpoint interval, with a checkpoint published by every round and with the
 * checkpoint deferred until the engine's close.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Collection relabel chains in one ALIVE round")
@Tag(STORAGE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
class CollectionRelabelChainRoundTest implements EvitaTestSupport {
	private static final String CATALOG = "relabelChainRound";
	private static final String BRAVO = "bravo";
	private static final String CHARLIE = "charlie";
	private static final String DELTA = "delta";
	private static final String ECHO = "echo";
	private static final String TANGO = "tango";
	private static final String NAME = "name";
	private static final int BRAVO_COUNT = 3;
	private static final int DELTA_COUNT = 2;
	private static final int FOLLOW_UP_PK = 100;
	private static final long TIMEOUT_SECONDS = 60L;
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;

	private final List<TestPaths> allocatedPaths = new ArrayList<>(1);
	@Nullable private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			try {
				this.evita.close();
			} catch (RuntimeException ignored) {
				// the cell already reported the state the engine was left in
			}
		}
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	@Nested
	@DisplayName("With the default checkpoint interval")
	class WithDefaultCheckpoint {
		@Test void swapInOneTransaction() { assertScenario(Checkpoint.DEFAULT, Scenario.SWAP_IN_ONE); }
		@Test void swapStepsSeparately() { assertScenario(Checkpoint.DEFAULT, Scenario.SWAP_SEPARATELY); }
		@Test void chainInOneTransaction() { assertScenario(Checkpoint.DEFAULT, Scenario.CHAIN_IN_ONE); }
		@Test void chainStepsSeparately() { assertScenario(Checkpoint.DEFAULT, Scenario.CHAIN_SEPARATELY); }
		@Test void renameBravoAlone() { assertScenario(Checkpoint.DEFAULT, Scenario.RENAME_BRAVO_ALONE); }
		@Test void renameDeltaAlone() { assertScenario(Checkpoint.DEFAULT, Scenario.RENAME_DELTA_ALONE); }
		@Test void renameAndCreateInOneTransaction() { assertScenario(Checkpoint.DEFAULT, Scenario.RENAME_AND_CREATE_IN_ONE); }
		@Test void renameAndCreateSeparately() { assertScenario(Checkpoint.DEFAULT, Scenario.RENAME_AND_CREATE_SEPARATELY); }
		@Test void createAlone() { assertScenario(Checkpoint.DEFAULT, Scenario.CREATE_ALONE); }
	}

	@Nested
	@DisplayName("With a checkpoint published by every round")
	class WithCheckpointEveryRound {
		@Test void swapInOneTransaction() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.SWAP_IN_ONE); }
		@Test void swapStepsSeparately() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.SWAP_SEPARATELY); }
		@Test void chainInOneTransaction() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.CHAIN_IN_ONE); }
		@Test void chainStepsSeparately() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.CHAIN_SEPARATELY); }
		@Test void renameBravoAlone() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.RENAME_BRAVO_ALONE); }
		@Test void renameDeltaAlone() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.RENAME_DELTA_ALONE); }
		@Test void renameAndCreateInOneTransaction() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.RENAME_AND_CREATE_IN_ONE); }
		@Test void renameAndCreateSeparately() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.RENAME_AND_CREATE_SEPARATELY); }
		@Test void createAlone() { assertScenario(Checkpoint.EVERY_ROUND, Scenario.CREATE_ALONE); }
	}

	@Nested
	@DisplayName("With the checkpoint deferred until the close")
	class WithCheckpointDeferred {
		@Test void swapInOneTransaction() { assertScenario(Checkpoint.DEFERRED, Scenario.SWAP_IN_ONE); }
		@Test void swapStepsSeparately() { assertScenario(Checkpoint.DEFERRED, Scenario.SWAP_SEPARATELY); }
		@Test void chainInOneTransaction() { assertScenario(Checkpoint.DEFERRED, Scenario.CHAIN_IN_ONE); }
		@Test void chainStepsSeparately() { assertScenario(Checkpoint.DEFERRED, Scenario.CHAIN_SEPARATELY); }
		@Test void renameBravoAlone() { assertScenario(Checkpoint.DEFERRED, Scenario.RENAME_BRAVO_ALONE); }
		@Test void renameDeltaAlone() { assertScenario(Checkpoint.DEFERRED, Scenario.RENAME_DELTA_ALONE); }
		@Test void renameAndCreateInOneTransaction() { assertScenario(Checkpoint.DEFERRED, Scenario.RENAME_AND_CREATE_IN_ONE); }
		@Test void renameAndCreateSeparately() { assertScenario(Checkpoint.DEFERRED, Scenario.RENAME_AND_CREATE_SEPARATELY); }
		@Test void createAlone() { assertScenario(Checkpoint.DEFERRED, Scenario.CREATE_ALONE); }
	}

	/**
	 * How often an `ALIVE` round publishes its bootstrap record.
	 */
	private enum Checkpoint {
		DEFAULT, EVERY_ROUND, DEFERRED
	}

	/**
	 * The transactions of a scenario and the content they are expected to leave behind.
	 */
	private enum Scenario {
		/**
		 * `bravo` and `delta` swap names through `tango`, in one transaction.
		 */
		SWAP_IN_ONE,
		/**
		 * The same three renames, each its own transaction.
		 */
		SWAP_SEPARATELY,
		/**
		 * `bravo` becomes `charlie` and `delta` becomes `bravo`, in one transaction.
		 */
		CHAIN_IN_ONE,
		/**
		 * The same two renames, each its own transaction.
		 */
		CHAIN_SEPARATELY,
		/**
		 * `bravo` becomes `charlie`.
		 */
		RENAME_BRAVO_ALONE,
		/**
		 * `delta` becomes `echo`.
		 */
		RENAME_DELTA_ALONE,
		/**
		 * `bravo` becomes `charlie` and a new `bravo` is created, in one transaction.
		 */
		RENAME_AND_CREATE_IN_ONE,
		/**
		 * The same rename and creation, each its own transaction.
		 */
		RENAME_AND_CREATE_SEPARATELY,
		/**
		 * A new `echo` is created, nothing else changes.
		 */
		CREATE_ALONE;

		@Nonnull
		List<Consumer<EvitaSessionContract>> transactions() {
			final Consumer<EvitaSessionContract> bravoToTango = s -> s.renameCollection(BRAVO, TANGO);
			final Consumer<EvitaSessionContract> deltaToBravo = s -> s.renameCollection(DELTA, BRAVO);
			final Consumer<EvitaSessionContract> tangoToDelta = s -> s.renameCollection(TANGO, DELTA);
			final Consumer<EvitaSessionContract> bravoToCharlie = s -> s.renameCollection(BRAVO, CHARLIE);
			final Consumer<EvitaSessionContract> deltaToEcho = s -> s.renameCollection(DELTA, ECHO);
			final Consumer<EvitaSessionContract> createBravo = s -> createCollection(s, BRAVO);
			final Consumer<EvitaSessionContract> createEcho = s -> createCollection(s, ECHO);
			return switch (this) {
				case SWAP_IN_ONE -> List.of(bravoToTango.andThen(deltaToBravo).andThen(tangoToDelta));
				case SWAP_SEPARATELY -> List.of(bravoToTango, deltaToBravo, tangoToDelta);
				case CHAIN_IN_ONE -> List.of(bravoToCharlie.andThen(deltaToBravo));
				case CHAIN_SEPARATELY -> List.of(bravoToCharlie, deltaToBravo);
				case RENAME_BRAVO_ALONE -> List.of(bravoToCharlie);
				case RENAME_DELTA_ALONE -> List.of(deltaToEcho);
				case RENAME_AND_CREATE_IN_ONE -> List.of(bravoToCharlie.andThen(createBravo));
				case RENAME_AND_CREATE_SEPARATELY -> List.of(bravoToCharlie, createBravo);
				case CREATE_ALONE -> List.of(createEcho);
			};
		}

		@Nonnull
		SortedMap<String, Map<Integer, String>> expected(@Nonnull SortedMap<String, Map<Integer, String>> before) {
			final Map<Integer, String> bravo = before.get(BRAVO);
			final Map<Integer, String> delta = before.get(DELTA);
			final SortedMap<String, Map<Integer, String>> expected = new TreeMap<>();
			switch (this) {
				case SWAP_IN_ONE, SWAP_SEPARATELY -> {
					expected.put(BRAVO, delta);
					expected.put(DELTA, bravo);
				}
				case CHAIN_IN_ONE, CHAIN_SEPARATELY -> {
					expected.put(CHARLIE, bravo);
					expected.put(BRAVO, delta);
				}
				case RENAME_BRAVO_ALONE -> {
					expected.put(CHARLIE, bravo);
					expected.put(DELTA, delta);
				}
				case RENAME_DELTA_ALONE -> {
					expected.put(BRAVO, bravo);
					expected.put(ECHO, delta);
				}
				case RENAME_AND_CREATE_IN_ONE, RENAME_AND_CREATE_SEPARATELY -> {
					expected.put(CHARLIE, bravo);
					expected.put(BRAVO, Map.of(1, "new-" + BRAVO + "-1"));
					expected.put(DELTA, delta);
				}
				case CREATE_ALONE -> {
					expected.putAll(before);
					expected.put(ECHO, Map.of(1, "new-" + ECHO + "-1"));
				}
			}
			return expected;
		}
	}

	/**
	 * Runs one cell; every deviation is collected and reported together.
	 */
	private void assertScenario(@Nonnull Checkpoint checkpoint, @Nonnull Scenario scenario) {
		final TestPaths paths = allocatePaths("relabelChainRound");
		final List<String> problems = new ArrayList<>(8);
		final List<String> trace = new ArrayList<>(8);
		final Evita theEvita = new Evita(configurationOf(paths, checkpoint));
		this.evita = theEvita;
		createCatalog(theEvita);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final SortedMap<String, Map<Integer, String>> before = readCollections(theEvita);
		final long versionBefore = catalogOf(theEvita).getVersion();
		trace.add("before: " + before + " v" + versionBefore + " headers " + describeHeaders(theEvita) +
			" folder " + describeFolder(paths));

		// stage 1: the scenario
		boolean scenarioCommitted = true;
		for (Consumer<EvitaSessionContract> transaction : scenario.transactions()) {
			final Throwable failure = runWithTimeout(() -> theEvita.updateCatalog(CATALOG, transaction));
			if (failure != null) {
				scenarioCommitted = false;
				problems.add("The scenario's transaction must commit, but the client got: " + stackTraceOf(failure));
			}
			awaitTrunkIncorporated(theEvita, problems, "scenario transaction");
		}
		final long versionAfter = catalogOf(theEvita).getVersion();
		final String premise = "Premise (" + scenario + "): catalog version " + versionBefore + " -> " + versionAfter +
			" for " + scenario.transactions().size() + " transaction(s)";
		trace.add(premise + "; state " + theEvita.getCatalogState(CATALOG).orElse(null));
		if (scenarioCommitted) {
			assertTrue(versionAfter - versionBefore == scenario.transactions().size(), premise);
		}
		final SortedMap<String, Map<Integer, String>> expected = scenario.expected(before);
		final SortedMap<String, Map<Integer, String>> visible = safeRead(theEvita, problems, "after the scenario");
		trace.add("after scenario: visible " + visible + " headers " + describeHeaders(theEvita) + " folder " +
			describeFolder(paths));
		if (visible != null && !expected.equals(visible)) {
			problems.add("After the scenario the catalog must serve " + expected + ", but serves " + visible + ".");
		}

		// stage 2: one write into every collection that should exist
		final Throwable followUpFailure = runWithTimeout(
			() -> theEvita.updateCatalog(
				CATALOG,
				session -> {
					for (String entityType : expected.keySet()) {
						session.createNewEntity(entityType, FOLLOW_UP_PK)
							.setAttribute(NAME, "follow-up-" + entityType)
							.upsertVia(session);
					}
				}
			)
		);
		if (followUpFailure != null) {
			problems.add("A follow-up write must commit, but the client got: " + stackTraceOf(followUpFailure));
		} else {
			awaitTrunkIncorporated(theEvita, problems, "follow-up transaction");
			for (String entityType : expected.keySet()) {
				final Map<Integer, String> content = new TreeMap<>(expected.get(entityType));
				content.put(FOLLOW_UP_PK, "follow-up-" + entityType);
				expected.put(entityType, content);
			}
		}
		final SortedMap<String, Map<Integer, String>> visibleAtEnd = safeRead(theEvita, problems, "before the restart");
		if (visibleAtEnd != null && !expected.equals(visibleAtEnd)) {
			problems.add("Before the restart the catalog must serve " + expected + ", but serves " + visibleAtEnd + ".");
		}

		// stage 3: restart, and once more when the first one fails
		final Catalog catalog = catalogOf(theEvita);
		final String beforeClose = "Applied version " + catalog.getVersion() + ", persisted version " +
			catalog.getLastPersistedCatalogVersion() + ", last WAL version " +
			catalog.getLastCatalogVersionInMutationStream() + ", state " + theEvita.getCatalogState(CATALOG).orElse(null) +
			", headers " + describeHeaders(theEvita) + ". Folder: " + describeFolder(paths) + ".";
		trace.add("before close: " + beforeClose);
		final Throwable closeFailure = runWithTimeout(theEvita::close);
		this.evita = null;
		if (closeFailure != null) {
			problems.add("The engine must close cleanly, but: " + stackTraceOf(closeFailure));
		}
		final String firstRestart = restartAndCheck(paths, checkpoint, expected, trace, "first restart");
		if (firstRestart != null) {
			problems.add(firstRestart);
			final String secondRestart = restartAndCheck(paths, checkpoint, expected, trace, "second restart");
			problems.add(secondRestart == null ? "The second restart succeeded with the expected content." : secondRestart);
		}

		System.out.println("CELL " + checkpoint + " / " + scenario + " problems=" + problems.size() + "\n  " +
			String.join("\n  ", trace));
		assertTrue(
			problems.isEmpty(),
			"Cell " + checkpoint + " / " + scenario + " - " + premise + "\nTrace:\n  " + String.join("\n  ", trace) +
				"\nProblems:\n- " + String.join("\n- ", problems)
		);
	}

	/**
	 * Starts an engine over the folder, checks it loads ALIVE with the expected content and closes it again.
	 *
	 * @return null when the catalog loaded with the expected content, the problem otherwise
	 */
	@Nullable
	private String restartAndCheck(
		@Nonnull TestPaths paths,
		@Nonnull Checkpoint checkpoint,
		@Nonnull SortedMap<String, Map<Integer, String>> expected,
		@Nonnull List<String> trace,
		@Nonnull String label
	) {
		String problem = null;
		try {
			final Evita reopened = new Evita(configurationOf(paths, checkpoint));
			this.evita = reopened;
			reopened.waitUntilFullyInitialized();
			await()
				.atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(() -> reopened.getCatalogState(CATALOG).map(it -> !it.isTransitional()).orElse(true));
			final CatalogState state = reopened.getCatalogState(CATALOG).orElse(null);
			if (state != CatalogState.ALIVE) {
				problem = "The catalog must load ALIVE after the " + label + ", but is " + state + ".";
			} else {
				final SortedMap<String, Map<Integer, String>> reloaded = readCollections(reopened);
				trace.add(label + ": reloaded " + reloaded + " headers " + describeHeaders(reopened));
				if (!expected.equals(reloaded)) {
					problem = "After the " + label + " the catalog must hold " + expected + ", but holds " + reloaded + ".";
				}
			}
		} catch (RuntimeException ex) {
			problem = "The engine must start over the catalog at the " + label + ", but: " + stackTraceOf(ex);
		}
		trace.add(label + ": " + (problem == null ? "OK" : "FAILED") + " folder " + describeFolder(paths));
		if (this.evita != null) {
			final Throwable closeFailure = runWithTimeout(this.evita::close);
			this.evita = null;
			if (closeFailure != null) {
				trace.add(label + ": close failed: " + closeFailure);
			}
		}
		return problem;
	}

	@Nonnull
	private static Catalog catalogOf(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/**
	 * Describes the header every live collection addresses: its physical entity type, primary key and file index.
	 */
	@Nonnull
	private static String describeHeaders(@Nonnull Evita evita) {
		try {
			final Catalog catalog = catalogOf(evita);
			final SortedMap<String, String> headers = new TreeMap<>();
			for (String entityType : catalog.getEntityTypes()) {
				final EntityCollectionFileHeader header = (EntityCollectionFileHeader) catalog
					.getCollectionForEntityOrThrowException(entityType)
					.getEntityCollectionHeader();
				headers.put(
					entityType,
					header.entityType() + "/" + header.entityTypePrimaryKey() + "_" + header.entityTypeFileIndex()
				);
			}
			return headers.toString();
		} catch (RuntimeException ex) {
			return "unreadable: " + ex;
		}
	}

	private static void createCatalog(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG).updateViaNewSession(evita);
		evita.updateCatalog(
			CATALOG,
			session -> {
				defineSchema(session, BRAVO);
				defineSchema(session, DELTA);
				for (int pk = 1; pk <= BRAVO_COUNT; pk++) {
					session.createNewEntity(BRAVO, pk).setAttribute(NAME, BRAVO + "-" + pk).upsertVia(session);
				}
				for (int pk = 1; pk <= DELTA_COUNT; pk++) {
					session.createNewEntity(DELTA, pk).setAttribute(NAME, DELTA + "-" + pk).upsertVia(session);
				}
			}
		);
	}

	private static void defineSchema(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		session.defineEntitySchema(entityType)
			.withoutGeneratedPrimaryKey()
			.withAttribute(NAME, String.class, thatIs -> thatIs.filterable())
			.updateVia(session);
	}

	private static void createCollection(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		defineSchema(session, entityType);
		session.createNewEntity(entityType, 1).setAttribute(NAME, "new-" + entityType + "-1").upsertVia(session);
	}

	@Nullable
	private static Throwable runWithTimeout(@Nonnull Runnable action) {
		try {
			CompletableFuture.runAsync(action).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return null;
		} catch (java.util.concurrent.ExecutionException ex) {
			return ex.getCause();
		} catch (Exception ex) {
			return ex;
		}
	}

	private static void awaitTrunkIncorporated(@Nonnull Evita evita, @Nonnull List<String> problems, @Nonnull String after) {
		try {
			await()
				.atMost(10, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(() -> {
					final Catalog catalog = catalogOf(evita);
					return catalog.getVersion() == catalog.getLastCatalogVersionInMutationStream();
				});
		} catch (ConditionTimeoutException ex) {
			final Catalog catalog = catalogOf(evita);
			problems.add(
				"The trunk must incorporate the " + after + ", but the catalog stays at version " + catalog.getVersion() +
					" while the WAL reaches " + catalog.getLastCatalogVersionInMutationStream() + "."
			);
		}
	}

	@Nullable
	private static SortedMap<String, Map<Integer, String>> safeRead(
		@Nonnull Evita evita, @Nonnull List<String> problems, @Nonnull String when
	) {
		try {
			return readCollections(evita);
		} catch (RuntimeException ex) {
			problems.add("Reading the catalog " + when + " must succeed, but: " + stackTraceOf(ex));
			return null;
		}
	}

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
							require(entityFetch(attributeContent(NAME)), page(1, 1000))
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

	@Nonnull
	private static String describeFolder(@Nonnull TestPaths paths) {
		try (final Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			return files
				.sorted()
				.filter(it -> it.getFileName().toString().endsWith(".collection"))
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

	@Nonnull
	private static String stackTraceOf(@Nonnull Throwable throwable) {
		final StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		final String full = writer.toString();
		// the first lines carry the message and the throw site, the cause chain carries the rest
		return full.lines()
			.filter(line -> !line.startsWith("\tat ") || line.contains("io.evitadb"))
			.limit(40)
			.collect(Collectors.joining("\n"));
	}

	@Nonnull
	private static EvitaConfiguration configurationOf(@Nonnull TestPaths paths, @Nonnull Checkpoint checkpoint) {
		final StorageOptions storage = StorageOptions.builder()
			.storageDirectory(paths.storage())
			.workDirectory(paths.work())
			.syncWrites(true)
			.timeTravelEnabled(false)
			.build();
		final TransactionOptions.Builder transaction = TransactionOptions.builder()
			.transactionWorkDirectory(paths.work().resolve("tx"));
		if (checkpoint == Checkpoint.EVERY_ROUND) {
			transaction.checkpointIntervalInMillis(0L);
		} else if (checkpoint == Checkpoint.DEFERRED) {
			transaction.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS);
		}
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(storage)
			// each engine its own export folder - the default one is shared and locked by whichever engine runs first
			.export(
				FileSystemExportOptions.builder()
					.directory(paths.export())
					.build()
			)
			.transaction(transaction.build())
			.build();
	}

	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths(label);
		this.allocatedPaths.add(paths);
		return paths;
	}

}
