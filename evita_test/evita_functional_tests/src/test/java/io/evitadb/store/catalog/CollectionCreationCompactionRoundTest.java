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
import java.util.TreeSet;
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
 * Verifies that one `ALIVE` round may both compact a collection's data file and create a new collection.
 *
 * A trunk round flushes before it merges. The flush compacts `alpha`'s data file and records the header of the new
 * file in the catalog header; a round that changed the collection map then refreshes the catalog header from the
 * collections of the merge. The live collection must still address the file its compaction produced, the next
 * compaction of `alpha` must write a new file rather than into the live one, and a restart must load the catalog with
 * the new collection and the content every round produced.
 *
 * Unlike a deletion, rename or replace, the creation retires no data file, so the round never hands the obsolete-file
 * maintainer a lower version than the flush did.
 *
 * Controls bound the claim: the compaction alone, the creation alone, and the compaction and the creation in two
 * separate transactions. Every cell runs with the default checkpoint interval, with a checkpoint published by every
 * round and with the checkpoint deferred until the engine's close.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Compaction and collection creation in one ALIVE round")
@Tag(STORAGE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
class CollectionCreationCompactionRoundTest implements EvitaTestSupport {
	private static final String CATALOG = "creationCompactionRound";
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final String DELTA = "delta";
	private static final String NAME = "name";
	private static final String PAYLOAD = "payload";
	private static final int ALPHA_COUNT = 400;
	private static final int SMALL_COUNT = 5;
	private static final int PAYLOAD_LENGTH = 2_000;
	/**
	 * Below the size of `alpha`'s data file (≈ 800 kB and growing) and above every other data file of the catalog.
	 */
	private static final long COLLECTION_ONLY_THRESHOLD_BYTES = 262_144L;
	/**
	 * Generous bound for the trunk and for every client call - a suspended pipeline must surface as a failure,
	 * never as a hang.
	 */
	private static final long TIMEOUT_SECONDS = 60L;
	/**
	 * Long enough that no checkpoint fires on its own during the test - every round in `ALIVE` defers.
	 */
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

	/**
	 * Rounds defer their checkpoint as the default configuration does.
	 */
	@Nested
	@DisplayName("With the default checkpoint interval")
	class WithDefaultCheckpoint {

		@Test
		@DisplayName("should commit a collection compaction together with a collection creation")
		void shouldCommitCompactionWithCreation() {
			assertRounds(Checkpoint.DEFAULT, Scenario.UPDATE_ALPHA_CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction alone")
		void shouldCommitCompactionAlone() {
			assertRounds(Checkpoint.DEFAULT, Scenario.UPDATE_ALPHA);
		}

		@Test
		@DisplayName("control: should commit a collection creation alone")
		void shouldCommitCreationAlone() {
			assertRounds(Checkpoint.DEFAULT, Scenario.CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a creation in separate transactions")
		void shouldCommitCompactionAndCreationSeparately() {
			assertRounds(Checkpoint.DEFAULT, Scenario.UPDATE_ALPHA_THEN_CREATE_DELTA);
		}
	}

	/**
	 * Every round publishes its bootstrap record at once.
	 */
	@Nested
	@DisplayName("With a checkpoint published by every round")
	class WithCheckpointEveryRound {

		@Test
		@DisplayName("should commit a collection compaction together with a collection creation")
		void shouldCommitCompactionWithCreation() {
			assertRounds(Checkpoint.EVERY_ROUND, Scenario.UPDATE_ALPHA_CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction alone")
		void shouldCommitCompactionAlone() {
			assertRounds(Checkpoint.EVERY_ROUND, Scenario.UPDATE_ALPHA);
		}

		@Test
		@DisplayName("control: should commit a collection creation alone")
		void shouldCommitCreationAlone() {
			assertRounds(Checkpoint.EVERY_ROUND, Scenario.CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a creation in separate transactions")
		void shouldCommitCompactionAndCreationSeparately() {
			assertRounds(Checkpoint.EVERY_ROUND, Scenario.UPDATE_ALPHA_THEN_CREATE_DELTA);
		}
	}

	/**
	 * No round publishes its bootstrap record on its own; the engine's close publishes the last one.
	 */
	@Nested
	@DisplayName("With the checkpoint deferred until the close")
	class WithCheckpointDeferred {

		@Test
		@DisplayName("should commit a collection compaction together with a collection creation")
		void shouldCommitCompactionWithCreation() {
			assertRounds(Checkpoint.DEFERRED, Scenario.UPDATE_ALPHA_CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction alone")
		void shouldCommitCompactionAlone() {
			assertRounds(Checkpoint.DEFERRED, Scenario.UPDATE_ALPHA);
		}

		@Test
		@DisplayName("control: should commit a collection creation alone")
		void shouldCommitCreationAlone() {
			assertRounds(Checkpoint.DEFERRED, Scenario.CREATE_DELTA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a creation in separate transactions")
		void shouldCommitCompactionAndCreationSeparately() {
			assertRounds(Checkpoint.DEFERRED, Scenario.UPDATE_ALPHA_THEN_CREATE_DELTA);
		}
	}

	/**
	 * How often an `ALIVE` round publishes its bootstrap record.
	 */
	private enum Checkpoint {
		/**
		 * The engine's default checkpoint interval - rounds in quick succession defer their publication.
		 */
		DEFAULT,
		/**
		 * Every round publishes.
		 */
		EVERY_ROUND,
		/**
		 * No round publishes on its own - the engine's close publishes the last deferred record.
		 */
		DEFERRED
	}

	/**
	 * The transactions of the first stage and what they are expected to leave behind.
	 */
	private enum Scenario {
		/**
		 * `alpha` rewritten in full - the compaction alone.
		 */
		UPDATE_ALPHA(true, false),
		/**
		 * `alpha` rewritten in full and `delta` created in one transaction.
		 */
		UPDATE_ALPHA_CREATE_DELTA(true, true),
		/**
		 * `delta` created, nothing else changed - no compaction.
		 */
		CREATE_DELTA(false, true),
		/**
		 * `alpha` rewritten in full in one transaction, `delta` created in the next one.
		 */
		UPDATE_ALPHA_THEN_CREATE_DELTA(true, true);

		/**
		 * Whether `alpha` is rewritten, which is what makes its data file due for compaction.
		 */
		private final boolean rewritesAlpha;
		/**
		 * Whether the collection `delta` is created.
		 */
		private final boolean createsDelta;

		Scenario(boolean rewritesAlpha, boolean createsDelta) {
			this.rewritesAlpha = rewritesAlpha;
			this.createsDelta = createsDelta;
		}

		/**
		 * Returns the transactions of the scenario, in commit order.
		 */
		@Nonnull
		List<Consumer<EvitaSessionContract>> transactions() {
			return switch (this) {
				case UPDATE_ALPHA -> List.of(session -> rewriteAlpha(session, 'b', "rewritten-"));
				case UPDATE_ALPHA_CREATE_DELTA -> List.of(session -> {
					rewriteAlpha(session, 'b', "rewritten-");
					createDelta(session);
				});
				case CREATE_DELTA -> List.of(CollectionCreationCompactionRoundTest::createDelta);
				case UPDATE_ALPHA_THEN_CREATE_DELTA -> List.of(
					session -> rewriteAlpha(session, 'b', "rewritten-"),
					CollectionCreationCompactionRoundTest::createDelta
				);
			};
		}

		/**
		 * Computes the expected content of every collection from the content before the scenario.
		 */
		@Nonnull
		SortedMap<String, Map<Integer, String>> expected(@Nonnull SortedMap<String, Map<Integer, String>> before) {
			final SortedMap<String, Map<Integer, String>> expected = new TreeMap<>(before);
			if (this.rewritesAlpha) {
				expected.put(ALPHA, names(ALPHA, ALPHA_COUNT, "rewritten-"));
			}
			if (this.createsDelta) {
				expected.put(DELTA, Map.of(1, DELTA + "-1"));
			}
			return expected;
		}
	}

	/**
	 * Runs one cell: creates and fills the catalog, takes it live and then
	 *
	 * 1. runs the scenario's transactions and checks the compaction premise, the visible content and the file the
	 *    live `alpha` addresses,
	 * 2. rewrites `alpha` again, which must compact it into a new file again,
	 * 3. commits one more small write,
	 * 4. closes and reopens the engine and checks the content.
	 *
	 * Every deviation is collected and reported together.
	 */
	private void assertRounds(@Nonnull Checkpoint checkpoint, @Nonnull Scenario scenario) {
		final TestPaths paths = allocatePaths("creationCompactionRound");
		final List<String> problems = new ArrayList<>(8);
		final Evita theEvita = new Evita(configurationOf(paths, checkpoint));
		this.evita = theEvita;
		createCatalog(theEvita);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final SortedMap<String, Map<Integer, String>> before = readCollections(theEvita);
		final int alphaIndexBefore = maxFileIndex(paths, ALPHA + "-", ".collection");
		final long versionBefore = catalogOf(theEvita).getVersion();
		final String folderBefore = describeFolder(paths);

		// stage 1: the scenario's transactions, each awaited so that each is a round of its own
		boolean scenarioCommitted = true;
		for (Consumer<EvitaSessionContract> transaction : scenario.transactions()) {
			final Throwable failure = runWithTimeout(() -> theEvita.updateCatalog(CATALOG, transaction));
			if (failure != null) {
				scenarioCommitted = false;
				problems.add("The scenario's transaction must commit, but the client got: " + stackTraceOf(failure));
			}
			awaitTrunkIncorporated(theEvita, problems, "scenario transaction");
		}

		// premise: alpha compacted exactly when the scenario rewrote it, and every transaction was a round of its own
		final int alphaIndexAfter = maxFileIndex(paths, ALPHA + "-", ".collection");
		final long versionAfter = catalogOf(theEvita).getVersion();
		final String folderAfter = describeFolder(paths);
		final String premise = "Premise (" + scenario + "): alpha file index " + alphaIndexBefore + " -> " +
			alphaIndexAfter + ", catalog version " + versionBefore + " -> " + versionAfter + " for " +
			scenario.transactions().size() + " transaction(s). Folder before: " + folderBefore + ". Folder after: " +
			folderAfter + ".";
		assertTrue((alphaIndexAfter > alphaIndexBefore) == scenario.rewritesAlpha, premise);
		assertTrue(versionAfter - versionBefore == scenario.transactions().size(), premise);

		final SortedMap<String, Map<Integer, String>> expected = scenario.expected(before);
		final SortedMap<String, Map<Integer, String>> visible = readCollections(theEvita);
		if (!expected.equals(visible)) {
			problems.add(
				"After the scenario the catalog must serve " + expected.keySet() + " with the new content, but serves " +
					visible.keySet() + (expected.keySet().equals(visible.keySet()) ? " (content differs)" : "") + "."
			);
		}
		if (scenarioCommitted) {
			checkLiveAlphaIndex(theEvita, paths, problems, "the scenario");
		}

		// stage 2: alpha rewritten again - its compaction derives the target file from the live header
		final Throwable secondFailure = runWithTimeout(
			() -> theEvita.updateCatalog(
				CATALOG,
				session -> {
					rewriteAlpha(session, 'c', "again-");
				}
			)
		);
		if (secondFailure != null) {
			problems.add("The second rewrite of `alpha` must commit, but the client got: " + stackTraceOf(secondFailure));
		} else {
			awaitTrunkIncorporated(theEvita, problems, "second rewrite of `alpha`");
			expected.put(ALPHA, names(ALPHA, ALPHA_COUNT, "again-"));
			final int alphaIndexSecond = maxFileIndex(paths, ALPHA + "-", ".collection");
			assertTrue(
				alphaIndexSecond > alphaIndexAfter,
				"Premise: the second rewrite compacts `alpha` again, but its file index stays " + alphaIndexSecond +
					" (was " + alphaIndexAfter + "). Folder: " + describeFolder(paths) + ". Problems so far: " + problems
			);
			checkLiveAlphaIndex(theEvita, paths, problems, "the second rewrite");
		}

		// stage 3: a further small write must still be processed
		final Throwable followUpFailure = runWithTimeout(
			() -> theEvita.updateCatalog(
				CATALOG,
				session -> {
					session.createNewEntity(ALPHA, ALPHA_COUNT + 1)
						.setAttribute(NAME, "follow-up")
						.setAttribute(PAYLOAD, "x")
						.upsertVia(session);
				}
			)
		);
		if (followUpFailure != null) {
			problems.add("A transaction after the rounds must commit, but the client got: " + stackTraceOf(followUpFailure));
		} else {
			awaitTrunkIncorporated(theEvita, problems, "follow-up transaction");
			final Map<Integer, String> alpha = new TreeMap<>(expected.get(ALPHA));
			alpha.put(ALPHA_COUNT + 1, "follow-up");
			expected.put(ALPHA, alpha);
		}
		final SortedMap<String, Map<Integer, String>> visibleAtEnd = readCollections(theEvita);
		if (!expected.equals(visibleAtEnd)) {
			problems.add(
				"Before the restart the catalog must serve " + expected.keySet() + " with the committed content, but " +
					"serves " + visibleAtEnd.keySet() +
					(expected.keySet().equals(visibleAtEnd.keySet()) ? " (content differs)" : "") + "."
			);
		}

		// stage 4: a restart must load the content every round produced
		final Catalog catalog = catalogOf(theEvita);
		final String beforeClose = "Applied version " + catalog.getVersion() + ", persisted version " +
			catalog.getLastPersistedCatalogVersion() + ", last WAL version " +
			catalog.getLastCatalogVersionInMutationStream() + ". Folder: " + describeFolder(paths) + ".";
		final Throwable closeFailure = runWithTimeout(theEvita::close);
		this.evita = null;
		if (closeFailure != null) {
			problems.add("The engine must close cleanly, but: " + stackTraceOf(closeFailure));
		}
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
				problems.add("The catalog must load ALIVE after the restart, but is " + state + ". " + beforeClose);
			} else {
				final SortedMap<String, Map<Integer, String>> reloaded = readCollections(reopened);
				if (!expected.equals(reloaded)) {
					problems.add(
						"The reloaded catalog must hold " + expected.keySet() + " with the committed content, but holds " +
							reloaded.keySet() + (expected.keySet().equals(reloaded.keySet()) ? " (content differs)" : "") +
							". " + beforeClose
					);
				}
				checkLiveAlphaIndex(reopened, paths, problems, "the restart");
			}
		} catch (RuntimeException ex) {
			problems.add("The engine must restart over the catalog, but: " + stackTraceOf(ex) + " " + beforeClose);
		}

		assertTrue(
			problems.isEmpty(),
			"Cell " + checkpoint + " / " + scenario + " - " + premise + "\n- " + String.join("\n- ", problems)
		);
	}

	/**
	 * Records a problem when the live collection `alpha` does not address its newest data file - the next compaction
	 * derives its target from the live header, and a stale index makes it write into the live file.
	 */
	private static void checkLiveAlphaIndex(
		@Nonnull Evita evita,
		@Nonnull TestPaths paths,
		@Nonnull List<String> problems,
		@Nonnull String after
	) {
		final int newestIndex = maxFileIndex(paths, ALPHA + "-", ".collection");
		final int liveIndex = ((EntityCollectionFileHeader) catalogOf(evita)
			.getCollectionForEntityOrThrowException(ALPHA)
			.getEntityCollectionHeader()).entityTypeFileIndex();
		if (liveIndex != newestIndex) {
			problems.add(
				"After " + after + " the live collection `alpha` must address its newest data file (index " +
					newestIndex + "), but addresses index " + liveIndex + ". Folder: " + describeFolder(paths) + "."
			);
		}
	}

	/**
	 * Returns the live catalog instance.
	 */
	@Nonnull
	private static Catalog catalogOf(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/**
	 * Defines `alpha` and `beta` in a new catalog and fills them, still warming up. `alpha` carries a large payload on
	 * every entity so that its data file alone is above {@link #COLLECTION_ONLY_THRESHOLD_BYTES}.
	 */
	private static void createCatalog(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG).updateViaNewSession(evita);
		evita.updateCatalog(
			CATALOG,
			session -> {
				for (String entityType : List.of(ALPHA, BETA)) {
					defineSchema(session, entityType);
				}
				for (int pk = 1; pk <= ALPHA_COUNT; pk++) {
					session.createNewEntity(ALPHA, pk)
						.setAttribute(NAME, ALPHA + "-" + pk)
						.setAttribute(PAYLOAD, payload('a'))
						.upsertVia(session);
				}
				for (int pk = 1; pk <= SMALL_COUNT; pk++) {
					session.createNewEntity(BETA, pk)
						.setAttribute(NAME, BETA + "-" + pk)
						.setAttribute(PAYLOAD, "small")
						.upsertVia(session);
				}
			}
		);
	}

	/**
	 * Defines the entity type with a filterable name and a payload.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		session.defineEntitySchema(entityType)
			.withoutGeneratedPrimaryKey()
			.withAttribute(NAME, String.class, thatIs -> thatIs.filterable())
			.withAttribute(PAYLOAD, String.class)
			.updateVia(session);
	}

	/**
	 * Defines the collection `delta` and stores one entity in it.
	 */
	private static void createDelta(@Nonnull EvitaSessionContract session) {
		defineSchema(session, DELTA);
		session.createNewEntity(DELTA, 1)
			.setAttribute(NAME, DELTA + "-1")
			.setAttribute(PAYLOAD, "small")
			.upsertVia(session);
	}

	/**
	 * Rewrites the name and the payload of every `alpha` entity, which turns about half of its data file into waste.
	 */
	private static void rewriteAlpha(@Nonnull EvitaSessionContract session, char payloadCharacter, @Nonnull String prefix) {
		for (int pk = 1; pk <= ALPHA_COUNT; pk++) {
			session.getEntity(ALPHA, pk, attributeContentAll())
				.orElseThrow()
				.openForWrite()
				.setAttribute(NAME, prefix + ALPHA + "-" + pk)
				.setAttribute(PAYLOAD, payload(payloadCharacter))
				.upsertVia(session);
		}
	}

	/**
	 * Returns a payload of {@link #PAYLOAD_LENGTH} copies of the character.
	 */
	@Nonnull
	private static String payload(char character) {
		return String.valueOf(character).repeat(PAYLOAD_LENGTH);
	}

	/**
	 * Returns the names `prefix + entityType + "-" + pk` for primary keys 1 to `count`.
	 */
	@Nonnull
	private static Map<Integer, String> names(@Nonnull String entityType, int count, @Nonnull String prefix) {
		final Map<Integer, String> names = new TreeMap<>();
		for (int pk = 1; pk <= count; pk++) {
			names.put(pk, prefix + entityType + "-" + pk);
		}
		return names;
	}

	/**
	 * Runs the action on another thread and returns its failure, or `null` when it completed; a hang counts as a
	 * failure after {@link #TIMEOUT_SECONDS}.
	 */
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

	/**
	 * Waits until the engine incorporated every transaction it accepted into its catalog; records a problem when it
	 * does not.
	 */
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
							require(entityFetch(attributeContent(NAME)), page(1, ALPHA_COUNT * 2))
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
	 * Returns the highest file index among the files whose name starts with the prefix and ends with the suffix, or
	 * `-1` without one. The index is the number between the last `_` and the suffix.
	 */
	private static int maxFileIndex(@Nonnull TestPaths paths, @Nonnull String prefix, @Nonnull String suffix) {
		return listFolder(paths)
			.stream()
			.filter(name -> name.startsWith(prefix) && name.endsWith(suffix))
			.mapToInt(name -> Integer.parseInt(name.substring(name.lastIndexOf('_') + 1, name.lastIndexOf('.'))))
			.max()
			.orElse(-1);
	}

	/**
	 * Lists the file names in the catalog folder.
	 */
	@Nonnull
	private static TreeSet<String> listFolder(@Nonnull TestPaths paths) {
		try (final Stream<Path> files = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), CATALOG))) {
			return files.map(it -> it.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
		} catch (IOException ex) {
			return new TreeSet<>();
		}
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
	 * Renders the throwable with its stack trace, for assertion messages.
	 */
	@Nonnull
	private static String stackTraceOf(@Nonnull Throwable throwable) {
		final StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	/**
	 * Builds the configuration: own directories, sync writes, time travel off, compaction thresholds that only
	 * `alpha`'s data file exceeds, and the cell's checkpoint interval.
	 */
	@Nonnull
	private static EvitaConfiguration configurationOf(@Nonnull TestPaths paths, @Nonnull Checkpoint checkpoint) {
		final StorageOptions storage = StorageOptions.builder()
			.storageDirectory(paths.storage())
			.workDirectory(paths.work())
			.syncWrites(true)
			.timeTravelEnabled(false)
			.fileSizeCompactionThresholdBytes(COLLECTION_ONLY_THRESHOLD_BYTES)
			.minimalActiveRecordShare(0.99)
			.minCompactionIntervalMilliseconds(0L)
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
