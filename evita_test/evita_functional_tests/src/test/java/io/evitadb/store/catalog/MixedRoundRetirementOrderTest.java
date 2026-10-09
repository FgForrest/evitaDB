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
import java.util.Set;
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
 * Verifies that one `ALIVE` round may both compact a data file and delete, rename or replace an entity collection.
 *
 * A trunk round flushes before it merges. The flush compacts every data file that is due and retires the old file
 * at the round's own catalog version; the merge then deletes or relabels collections and retires their old files at
 * the version before it. Both retirements go to the same obsolete-file maintainer, so the round hands it a version
 * lower than the one it has just handed over. The round must still commit, the catalog must keep accepting
 * transactions, the old file of the dropped collection must eventually leave the disk, and a restart must load the
 * catalog with the content the round produced.
 *
 * The compacted file is either the collection `alpha` alone (its data file is the only one above the compaction size
 * threshold) or the catalog data file (every file is above it). Controls bound the claim: the compaction alone, the
 * deletion alone without any compaction, and the compaction and the deletion in two separate transactions. Every cell
 * runs with the default checkpoint interval and with a checkpoint published by every round; the main cells also with the
 * checkpoint deferred until the engine's close.
 *
 * Each cell walks the whole scenario and reports every deviation at once, so one red run shows where the failure
 * surfaces to the client, whether writes are refused afterwards, whether the old file stays, and whether a restart
 * recovers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Compaction and collection deletion or relabel in one ALIVE round")
@Tag(STORAGE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
class MixedRoundRetirementOrderTest implements EvitaTestSupport {
	private static final String CATALOG = "mixedRoundRetirement";
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final String GAMMA = "gamma";
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
		@DisplayName("should commit a collection compaction together with a collection deletion")
		void shouldCommitCompactionWithDeletion() {
			assertRound(Checkpoint.DEFAULT, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_DELETE_BETA);
		}

		@Test
		@DisplayName("should commit a collection compaction together with a collection rename")
		void shouldCommitCompactionWithRename() {
			assertRound(Checkpoint.DEFAULT, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_RENAME_GAMMA);
		}

		@Test
		@DisplayName("should commit a collection compaction together with a collection replace")
		void shouldCommitCompactionWithReplace() {
			assertRound(Checkpoint.DEFAULT, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA);
		}

		@Test
		@DisplayName("should commit a catalog compaction together with a collection deletion")
		void shouldCommitCatalogCompactionWithDeletion() {
			assertRound(Checkpoint.DEFAULT, Compaction.EVERY_FILE, Scenario.DELETE_BETA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction alone")
		void shouldCommitCompactionAlone() {
			assertRound(Checkpoint.DEFAULT, Compaction.COLLECTION, Scenario.UPDATE_ALPHA);
		}

		@Test
		@DisplayName("control: should commit a collection deletion without any compaction")
		void shouldCommitDeletionWithoutCompaction() {
			assertRound(Checkpoint.DEFAULT, Compaction.NONE, Scenario.UPDATE_ALPHA_DELETE_BETA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a deletion in separate transactions")
		void shouldCommitCompactionAndDeletionSeparately() {
			assertRound(Checkpoint.DEFAULT, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_THEN_DELETE_BETA);
		}
	}

	/**
	 * Every round publishes its bootstrap record at once.
	 */
	@Nested
	@DisplayName("With a checkpoint published by every round")
	class WithCheckpointEveryRound {

		@Test
		@DisplayName("should commit a collection compaction together with a collection deletion")
		void shouldCommitCompactionWithDeletion() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_DELETE_BETA);
		}

		@Test
		@DisplayName("should commit a collection compaction together with a collection rename")
		void shouldCommitCompactionWithRename() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_RENAME_GAMMA);
		}

		@Test
		@DisplayName("should commit a collection compaction together with a collection replace")
		void shouldCommitCompactionWithReplace() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA);
		}

		@Test
		@DisplayName("should commit a catalog compaction together with a collection deletion")
		void shouldCommitCatalogCompactionWithDeletion() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.EVERY_FILE, Scenario.DELETE_BETA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction alone")
		void shouldCommitCompactionAlone() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.COLLECTION, Scenario.UPDATE_ALPHA);
		}

		@Test
		@DisplayName("control: should commit a collection deletion without any compaction")
		void shouldCommitDeletionWithoutCompaction() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.NONE, Scenario.UPDATE_ALPHA_DELETE_BETA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a deletion in separate transactions")
		void shouldCommitCompactionAndDeletionSeparately() {
			assertRound(Checkpoint.EVERY_ROUND, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_THEN_DELETE_BETA);
		}
	}

	/**
	 * No round publishes its bootstrap record on its own; the engine's close publishes the last one.
	 */
	@Nested
	@DisplayName("With the checkpoint deferred until the close")
	class WithCheckpointDeferred {

		@Test
		@DisplayName("should commit a collection compaction together with a collection deletion")
		void shouldCommitCompactionWithDeletion() {
			assertRound(Checkpoint.DEFERRED, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_DELETE_BETA);
		}

		@Test
		@DisplayName("should commit a collection compaction together with a collection rename")
		void shouldCommitCompactionWithRename() {
			assertRound(Checkpoint.DEFERRED, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_RENAME_GAMMA);
		}

		@Test
		@DisplayName("control: should commit a collection compaction and a deletion in separate transactions")
		void shouldCommitCompactionAndDeletionSeparately() {
			assertRound(Checkpoint.DEFERRED, Compaction.COLLECTION, Scenario.UPDATE_ALPHA_THEN_DELETE_BETA);
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
	 * Which data files the compaction thresholds make due in every round that changes them.
	 */
	private enum Compaction {
		/**
		 * The engine's default thresholds - nothing in this catalog is big enough to compact.
		 */
		NONE,
		/**
		 * Only `alpha`'s data file is above the size threshold.
		 */
		COLLECTION,
		/**
		 * Every data file, the catalog's included, is above the size threshold.
		 */
		EVERY_FILE
	}

	/**
	 * The transactions run against the live catalog and the collections they are expected to leave behind.
	 */
	private enum Scenario {
		/**
		 * `alpha` rewritten in full - the compaction alone.
		 */
		UPDATE_ALPHA(true, false),
		/**
		 * `alpha` rewritten in full and `beta` deleted in one transaction.
		 */
		UPDATE_ALPHA_DELETE_BETA(true, true),
		/**
		 * `alpha` rewritten in full and `gamma` renamed to `delta` in one transaction.
		 */
		UPDATE_ALPHA_RENAME_GAMMA(true, true),
		/**
		 * `alpha` rewritten in full and `gamma` put in place of `beta` in one transaction.
		 */
		UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA(true, true),
		/**
		 * `beta` deleted, nothing else changed - the catalog data file supplies the compaction.
		 */
		DELETE_BETA(false, true),
		/**
		 * `alpha` rewritten in full in one transaction, `beta` deleted in the next one.
		 */
		UPDATE_ALPHA_THEN_DELETE_BETA(true, true);

		/**
		 * Whether `alpha` is rewritten, which is what makes its data file due for compaction.
		 */
		private final boolean rewritesAlpha;
		/**
		 * Whether a collection's data file is dropped by a deletion or relabel.
		 */
		private final boolean dropsCollectionFile;

		Scenario(boolean rewritesAlpha, boolean dropsCollectionFile) {
			this.rewritesAlpha = rewritesAlpha;
			this.dropsCollectionFile = dropsCollectionFile;
		}

		/**
		 * Returns the transactions of the scenario, in commit order.
		 */
		@Nonnull
		List<Consumer<EvitaSessionContract>> transactions() {
			return switch (this) {
				case UPDATE_ALPHA -> List.of(MixedRoundRetirementOrderTest::rewriteAlpha);
				case UPDATE_ALPHA_DELETE_BETA -> List.of(session -> {
					rewriteAlpha(session);
					session.deleteCollection(BETA);
				});
				case UPDATE_ALPHA_RENAME_GAMMA -> List.of(session -> {
					rewriteAlpha(session);
					session.renameCollection(GAMMA, DELTA);
				});
				case UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA -> List.of(session -> {
					rewriteAlpha(session);
					session.replaceCollection(BETA, GAMMA);
				});
				case DELETE_BETA -> List.of(session -> session.deleteCollection(BETA));
				case UPDATE_ALPHA_THEN_DELETE_BETA -> List.of(
					MixedRoundRetirementOrderTest::rewriteAlpha,
					session -> session.deleteCollection(BETA)
				);
			};
		}

		/**
		 * Returns the entity types whose data files the scenario drops.
		 */
		@Nonnull
		Set<String> droppedTypes() {
			return switch (this) {
				case UPDATE_ALPHA -> Set.of();
				case UPDATE_ALPHA_DELETE_BETA, DELETE_BETA, UPDATE_ALPHA_THEN_DELETE_BETA -> Set.of(BETA);
				case UPDATE_ALPHA_RENAME_GAMMA -> Set.of(GAMMA);
				case UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA -> Set.of(BETA, GAMMA);
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
			switch (this) {
				case UPDATE_ALPHA_DELETE_BETA, DELETE_BETA, UPDATE_ALPHA_THEN_DELETE_BETA -> expected.remove(BETA);
				case UPDATE_ALPHA_RENAME_GAMMA -> expected.put(DELTA, expected.remove(GAMMA));
				case UPDATE_ALPHA_REPLACE_BETA_WITH_GAMMA -> expected.put(BETA, expected.remove(GAMMA));
				default -> {
					// nothing dropped
				}
			}
			return expected;
		}
	}

	/**
	 * Runs one cell: creates and fills the catalog, takes it live, runs the scenario's transactions and checks that
	 * each committed, that the catalog accepts a further transaction, that the dropped data files leave the disk
	 * and that a restart loads the expected content. Every deviation is collected and reported together.
	 */
	private void assertRound(@Nonnull Checkpoint checkpoint, @Nonnull Compaction compaction, @Nonnull Scenario scenario) {
		final TestPaths paths = allocatePaths("mixedRoundRetirement");
		final List<String> problems = new ArrayList<>(8);
		final Evita theEvita = new Evita(configurationOf(paths, checkpoint, compaction));
		this.evita = theEvita;
		createCatalog(theEvita);
		theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final SortedMap<String, Map<Integer, String>> before = readCollections(theEvita);
		final Set<String> droppedFiles = collectionFiles(paths, scenario.droppedTypes());
		final int alphaIndexBefore = maxFileIndex(paths, ALPHA + "-", ".collection");
		final int catalogIndexBefore = maxFileIndex(paths, "", ".catalog");
		final String folderBefore = describeFolder(paths);

		// the scenario's transactions
		boolean scenarioCommitted = true;
		for (Consumer<EvitaSessionContract> transaction : scenario.transactions()) {
			final Throwable failure = runWithTimeout(() -> theEvita.updateCatalog(CATALOG, transaction));
			if (failure != null) {
				scenarioCommitted = false;
				problems.add("The scenario's transaction must commit, but the client got: " + stackTraceOf(failure));
			}
			awaitTrunkIncorporated(theEvita, problems, "scenario transaction");
		}

		// premise: the compaction the cell is about happened (or, in the control without one, did not)
		final int alphaIndexAfter = maxFileIndex(paths, ALPHA + "-", ".collection");
		final int catalogIndexAfter = maxFileIndex(paths, "", ".catalog");
		final String folderAfter = describeFolder(paths);
		final String compactionPremise = "Premise (" + compaction + "): alpha file index " + alphaIndexBefore + " -> " +
			alphaIndexAfter + ", catalog file index " + catalogIndexBefore + " -> " + catalogIndexAfter +
			". Folder before: " + folderBefore + ". Folder after: " + folderAfter + ".";
		final boolean alphaCompacted = alphaIndexAfter > alphaIndexBefore;
		final boolean catalogCompacted = catalogIndexAfter > catalogIndexBefore;
		final boolean premiseHolds = switch (compaction) {
			case NONE -> !alphaCompacted && !catalogCompacted;
			case COLLECTION -> alphaCompacted && !catalogCompacted;
			case EVERY_FILE -> catalogCompacted;
		};
		assertTrue(premiseHolds, compactionPremise);

		// the visible state after the scenario
		final SortedMap<String, Map<Integer, String>> expected = scenario.expected(before);
		final SortedMap<String, Map<Integer, String>> visible = readCollections(theEvita);
		if (!expected.keySet().equals(visible.keySet()) || !expected.equals(visible)) {
			problems.add(
				"After the scenario the catalog must serve " + expected.keySet() + " with the new content, but serves " +
					visible.keySet() + (expected.equals(visible) ? "" : " (content differs)") + "."
			);
		}

		// the live collection must address the file its compaction produced - the next compaction derives its target
		// from it, and a stale index would make it overwrite the live file
		final Catalog liveCatalog = (Catalog) theEvita.getCatalogInstance(CATALOG).orElseThrow();
		final int liveAlphaIndex = ((EntityCollectionFileHeader) liveCatalog
			.getCollectionForEntityOrThrowException(ALPHA)
			.getEntityCollectionHeader()).entityTypeFileIndex();
		if (scenarioCommitted && liveAlphaIndex != alphaIndexAfter) {
			problems.add(
				"The live collection `alpha` must address its newest data file (index " + alphaIndexAfter +
					"), but addresses index " + liveAlphaIndex + "."
			);
		}

		// a further transaction must still be processed
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
			problems.add("A transaction after the scenario must commit, but the client got: " + stackTraceOf(followUpFailure));
		} else {
			awaitTrunkIncorporated(theEvita, problems, "follow-up transaction");
			final String followUpName = readCollections(theEvita).getOrDefault(ALPHA, Map.of()).get(ALPHA_COUNT + 1);
			if (!"follow-up".equals(followUpName)) {
				problems.add("The follow-up entity must be readable after its commit, but reads `" + followUpName + "`.");
			}
			final Map<Integer, String> alpha = new TreeMap<>(expected.get(ALPHA));
			alpha.put(ALPHA_COUNT + 1, "follow-up");
			expected.put(ALPHA, alpha);
		}

		// the data files of the dropped collections must eventually leave the disk
		if (scenario.dropsCollectionFile) {
			assertTrue(!droppedFiles.isEmpty(), "Premise: the dropped collections own data files. " + folderBefore);
			final Set<String> remaining = awaitFilesGone(theEvita, paths, droppedFiles);
			if (!remaining.isEmpty()) {
				problems.add("The dropped data files must leave the disk, but these stayed: " + remaining + ".");
			}
		}

		// a restart must load the content the scenario produced
		final Catalog catalog = (Catalog) theEvita.getCatalogInstance(CATALOG).orElseThrow();
		final String beforeClose = "Applied version " + catalog.getVersion() + ", persisted version " +
			catalog.getLastPersistedCatalogVersion() + ", last WAL version " +
			catalog.getLastCatalogVersionInMutationStream() + ". Folder: " + describeFolder(paths) + ".";
		final Throwable closeFailure = runWithTimeout(theEvita::close);
		this.evita = null;
		if (closeFailure != null) {
			problems.add("The engine must close cleanly, but: " + stackTraceOf(closeFailure));
		}
		try {
			final Evita reopened = new Evita(configurationOf(paths, checkpoint, compaction));
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
				final Set<String> remaining = awaitFilesGone(reopened, paths, droppedFiles);
				if (scenario.dropsCollectionFile && !remaining.isEmpty()) {
					problems.add("The dropped data files must be gone after the restart, but these stayed: " + remaining + ".");
				}
			}
		} catch (RuntimeException ex) {
			problems.add("The engine must restart over the catalog, but: " + stackTraceOf(ex) + " " + beforeClose);
		}

		assertTrue(
			problems.isEmpty(),
			"Cell " + checkpoint + " / " + compaction + " / " + scenario + " - " + compactionPremise + "\n- " +
				String.join("\n- ", problems)
		);
	}

	/**
	 * Defines `alpha`, `beta` and `gamma` in a new catalog and fills them, still warming up. `alpha` carries a large
	 * payload on every entity so that its data file alone is above {@link #COLLECTION_ONLY_THRESHOLD_BYTES}.
	 */
	private static void createCatalog(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG).updateViaNewSession(evita);
		evita.updateCatalog(
			CATALOG,
			session -> {
				for (String entityType : List.of(ALPHA, BETA, GAMMA)) {
					session.defineEntitySchema(entityType)
						.withoutGeneratedPrimaryKey()
						.withAttribute(NAME, String.class, thatIs -> thatIs.filterable())
						.withAttribute(PAYLOAD, String.class)
						.updateVia(session);
				}
				for (int pk = 1; pk <= ALPHA_COUNT; pk++) {
					session.createNewEntity(ALPHA, pk)
						.setAttribute(NAME, ALPHA + "-" + pk)
						.setAttribute(PAYLOAD, payload('a'))
						.upsertVia(session);
				}
				for (String entityType : List.of(BETA, GAMMA)) {
					for (int pk = 1; pk <= SMALL_COUNT; pk++) {
						session.createNewEntity(entityType, pk)
							.setAttribute(NAME, entityType + "-" + pk)
							.setAttribute(PAYLOAD, "small")
							.upsertVia(session);
					}
				}
			}
		);
	}

	/**
	 * Rewrites the name and the payload of every `alpha` entity, which turns about half of its data file into waste.
	 */
	private static void rewriteAlpha(@Nonnull EvitaSessionContract session) {
		for (int pk = 1; pk <= ALPHA_COUNT; pk++) {
			session.getEntity(ALPHA, pk, attributeContentAll())
				.orElseThrow()
				.openForWrite()
				.setAttribute(NAME, "rewritten-" + ALPHA + "-" + pk)
				.setAttribute(PAYLOAD, payload('b'))
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
	 * Waits until none of the files is in the catalog folder and returns those still there when the wait ran out.
	 * Every read session that ends lets the obsolete-file maintainer purge what no reader needs any more.
	 */
	@Nonnull
	private static Set<String> awaitFilesGone(@Nonnull Evita evita, @Nonnull TestPaths paths, @Nonnull Set<String> files) {
		try {
			await()
				.atMost(10, TimeUnit.SECONDS)
				.pollInterval(100, TimeUnit.MILLISECONDS)
				.until(() -> {
					readCollections(evita);
					final Set<String> remaining = listFolder(paths);
					remaining.retainAll(files);
					return remaining.isEmpty();
				});
		} catch (ConditionTimeoutException ignored) {
			// reported by the caller
		}
		final Set<String> remaining = listFolder(paths);
		remaining.retainAll(files);
		return remaining;
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
					final Catalog catalog = (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
					return catalog.getVersion() == catalog.getLastCatalogVersionInMutationStream();
				});
		} catch (ConditionTimeoutException ex) {
			final Catalog catalog = (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
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
	 * Lists the names of the data files of the given entity types.
	 */
	@Nonnull
	private static Set<String> collectionFiles(@Nonnull TestPaths paths, @Nonnull Set<String> entityTypes) {
		return listFolder(paths)
			.stream()
			.filter(name -> name.endsWith(".collection"))
			.filter(name -> entityTypes.stream().anyMatch(type -> name.startsWith(type + "-")))
			.collect(Collectors.toCollection(TreeSet::new));
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
	private static Set<String> listFolder(@Nonnull TestPaths paths) {
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
	 * Builds the configuration: own directories, sync writes, time travel off, the cell's compaction thresholds and
	 * its checkpoint interval.
	 */
	@Nonnull
	private static EvitaConfiguration configurationOf(
		@Nonnull TestPaths paths,
		@Nonnull Checkpoint checkpoint,
		@Nonnull Compaction compaction
	) {
		final StorageOptions.Builder storage = StorageOptions.builder()
			.storageDirectory(paths.storage())
			.workDirectory(paths.work())
			.syncWrites(true)
			.timeTravelEnabled(false);
		if (compaction != Compaction.NONE) {
			storage
				.fileSizeCompactionThresholdBytes(
					compaction == Compaction.COLLECTION ? COLLECTION_ONLY_THRESHOLD_BYTES : 1L
				)
				.minimalActiveRecordShare(0.99)
				.minCompactionIntervalMilliseconds(0L);
		}
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
			.storage(storage.build())
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
