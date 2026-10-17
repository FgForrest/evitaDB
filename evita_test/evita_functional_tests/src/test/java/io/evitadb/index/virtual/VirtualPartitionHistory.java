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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.utils.ExceptionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.CATALOG;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One embedded engine driven through a scripted mutation history of the catalog
 * {@link SingleOwnerPartitionFixture#CATALOG} - born, as every new catalog is, with its single-owner reference
 * partitions written as registry rows - and checked at every moment of the history a defect of the virtual write path
 * could hide in. Nothing is compared with a second engine: what the index objects of a twin used to prove, the entity
 * bodies, the invariants of the representation, the identity of the partitions across the history and the stored parts
 * prove here.
 *
 * # Moments
 *
 * - **Inside the still-open session**, after every step - in `ALIVE` that is inside the transaction, before the commit
 *   executes the transaction's mutations once more on the trunk, so a defect only the transaction-local state shows is
 *   not erased by that execution;
 * - **after a caught failure**, still inside the session (see
 *   {@link #failingStep(String, Consumer, Class)});
 * - **after the session closes** - a commit in `ALIVE`, a flush in `WARMING_UP` - and after going live;
 * - **after a restart** and **after a crash recovery**.
 *
 * # What is checked
 *
 * At every moment, every collection is held to:
 *
 * - the entity bodies (see {@link VirtualReferencePartitionRawRowOracle}): the closed world of the partitions the
 *   bodies produce - a registry row naming the owner for every partition with one owner, an index object holding
 *   exactly the owners for every other one, and nothing else - with the sort values and chain predecessors the bodies
 *   declare;
 * - the identity of the partitions (see {@link PartitionIdentityLedger}): a partition keeps the primary key it was
 *   observed under before, unless the history declares its key emptied and entered again, or replayed by a crash
 *   recovery (see {@link #getLedger()}). An open transaction of a live catalog shows provisional primary keys - its
 *   commit executes it once more on the trunk, which mints the primary keys of the partitions it creates anew - so a
 *   live catalog is held to the ledger once committed only;
 * - no index object of a partition with fewer than two owners, and no partition the reduced-index membership knows that
 *   is neither a row nor an index object.
 *
 * The committed state is held besides to every invariant of
 * {@link VirtualPartitionInvariants#assertInvariants(String, Evita, String)}, which reads the storage too, and to what
 * the catalog stores (see {@link VirtualPartitionInvariants#assertStoredParts(String, Evita, String)}): as many entity
 * index parts as indexes, and as many entity parts as a history-free rebuild of the bodies stores.
 *
 * A restart must leave the index-level and the query-level description of the catalog (see {@link CatalogDescriber}) as
 * it was before the close. A crash recovery must leave the query-level description as it was; its index level differs
 * legitimately in the primary keys of the partitions the recovery replays from the write-ahead log, which the ledger
 * holds instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualPartitionHistory implements AutoCloseable {
	/**
	 * The test owning the engine, used for its directories and configuration.
	 */
	private final EvitaTestSupport support;
	/**
	 * The transaction settings of the engine; `null` for the defaults of the test configuration.
	 */
	@Nullable private final TransactionOptions transactionOptions;
	/**
	 * Every directory set the engine has used - the original one and the crash images - so that {@link #close()}
	 * removes them all.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	/**
	 * The primary key of every partition observed at the moments of the history so far.
	 */
	private final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
	/**
	 * The directories of the engine - replaced by the crash image once the engine recovers from one.
	 */
	private TestPaths paths;
	/**
	 * The running engine.
	 */
	private Evita evita;

	/**
	 * Starts the engine in directories of its own.
	 *
	 * @param support            the test owning the engine
	 * @param label              a label naming the engine's directories
	 * @param transactionOptions the transaction settings of the engine - typically a checkpoint interval that defers
	 *                           every checkpoint, so that {@link #crashAndRecover()} finds a write-ahead log tail to
	 *                           replay; `null` for the defaults of the test configuration
	 */
	VirtualPartitionHistory(
		@Nonnull EvitaTestSupport support,
		@Nonnull String label,
		@Nullable TransactionOptions transactionOptions
	) {
		this.support = support;
		this.transactionOptions = transactionOptions;
		this.paths = allocatePaths(label);
		this.evita = new Evita(configurationOf(this.paths));
	}

	/**
	 * Defines the catalog, runs the setup as one step and, for `ALIVE`, takes the catalog live.
	 *
	 * @param state the catalog state the history should continue in
	 * @param setup the schema and the initial data
	 */
	void prepare(@Nonnull CatalogState state, @Nonnull Consumer<EvitaSessionContract> setup) {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		step("setup", setup);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			checkCommitted("after going live");
		}
	}

	/**
	 * Applies one step in a session of its own and checks the catalog inside the session and after it closes.
	 *
	 * @param label names the step in failure messages
	 * @param step  the mutation to apply
	 */
	void step(@Nonnull String label, @Nonnull Consumer<EvitaSessionContract> step) {
		transaction(label, List.of(step));
	}

	/**
	 * Applies several steps in one session - one transaction in `ALIVE` - and checks the catalog after every step
	 * inside the session and once more after it closes.
	 *
	 * @param label names the session in failure messages
	 * @param steps the mutations to apply, in order
	 */
	void transaction(@Nonnull String label, @Nonnull List<Consumer<EvitaSessionContract>> steps) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				for (int i = 0; i < steps.size(); i++) {
					steps.get(i).accept(session);
					// `updateCatalog` runs this lambda with the session's transaction bound to the thread, so the
					// checks read the transaction-local state the commit has not executed on the trunk yet
					checkInSession(label + " / step " + (i + 1) + " inside the session", session);
				}
			}
		);
		checkCommitted(label + " / after the session closed");
	}

	/**
	 * Applies a step expected to fail - the failure caught inside the session, which then goes on and closes normally
	 * - and asserts that the failed root mutation left the indexes exactly as they were: the index-level description of
	 * the catalog (see {@link VirtualPartitionInvariants#describeIndexes(Evita, String)}) - every registry row with its
	 * primary key, owner, sort values and chain predecessors, every index object and the reduced-index membership - is
	 * compared with the one before the step right after the caught failure inside the session, and the committed one
	 * once the session closed. The catalog is checked at both moments too.
	 *
	 * @param label    names the step in failure messages
	 * @param step     the mutation expected to fail
	 * @param expected the exception the failure must carry in its cause chain
	 */
	void failingStep(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> step,
		@Nonnull Class<? extends Throwable> expected
	) {
		final SortedMap<String, String> committedBefore =
			VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				final SortedMap<String, String> before =
					VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG);
				assertTrue(
					fails(step, session, expected),
					label + ": the step was expected to fail with " + expected.getSimpleName()
				);
				final String inSession = label + " / after the caught failure inside the session";
				VirtualPartitionInvariants.assertUnchanged(
					inSession, before, VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG)
				);
				checkInSession(inSession, session);
			}
		);
		final String afterClose = label + " / after the session closed";
		VirtualPartitionInvariants.assertUnchanged(
			afterClose, committedBefore, VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG)
		);
		checkCommitted(afterClose);
	}

	/**
	 * Closes the engine cleanly - which publishes everything it applied - starts it again over the same directories
	 * and asserts that the catalog is described exactly as it was before the close, at the index and the query level.
	 */
	void restart() {
		final SortedMap<String, String> indexes = VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG);
		final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(this.evita, CATALOG);
		this.evita.close();
		this.evita = startEngine();
		VirtualPartitionInvariants.assertUnchanged(
			"after the restart", indexes, VirtualPartitionInvariants.describeIndexes(this.evita, CATALOG)
		);
		VirtualPartitionInvariants.assertUnchanged(
			"after the restart, the query lines", queries, CatalogDescriber.describeQueriesOf(this.evita, CATALOG)
		);
		checkCommitted("after the restart");
	}

	/**
	 * Takes the image a crash would leave of the running engine - a copy of its storage directory taken while the
	 * engine is quiescent and before it is closed, because a clean close publishes everything it applied - closes the
	 * engine, boots it from the copy and asserts that the catalog answers every query line as it did before. The
	 * primary keys of the partitions the recovery replays from the write-ahead log may change; declare them in the
	 * ledger before (see {@link PartitionIdentityLedger#declareReplayed}).
	 *
	 * The copy is taken only once the catalog has incorporated everything its write-ahead log holds, and is accepted
	 * only when no file of the storage directory changed while it was being copied and the catalog published no new
	 * version meanwhile.
	 *
	 * @return the catalog version the image published and the one its load ended at
	 */
	@Nonnull
	Recovery crashAndRecover() {
		final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(this.evita, CATALOG);
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> catalog().getVersion() == catalog().getLastCatalogVersionInMutationStream());
		final Catalog catalog = catalog();
		final TestPaths image = allocatePaths(this.paths.storage().getFileName() + "_image");
		final SortedMap<String, String> beforeCopy = listFiles(this.paths.storage());
		CatalogReloadTestSupport.copyRecursively(this.paths.storage(), image.storage());
		assertEquals(
			beforeCopy, listFiles(this.paths.storage()),
			"The storage of the engine changed while it was being copied - the image is not the state of one moment"
		);
		assertEquals(beforeCopy, listFiles(image.storage()), "The image must hold every file of the storage, whole");
		assertSame(catalog, catalog(), "The catalog published a new version while its storage was being copied");

		this.evita.close();
		this.paths = image;
		final long imageVersion = readImageVersion(image);
		this.evita = startEngine();
		final Recovery recovery = new Recovery(imageVersion, catalog().getVersion());
		VirtualPartitionInvariants.assertUnchanged(
			"after the crash recovery, the query lines", queries,
			CatalogDescriber.describeQueriesOf(this.evita, CATALOG)
		);
		checkCommitted("after the crash recovery");
		return recovery;
	}

	/**
	 * Asserts that the committed catalog represents the partition of the key by a registry row naming the owner, and
	 * by no index object.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owner      the expected owner
	 * @return the primary key of the partition
	 */
	int assertRow(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int owner
	) {
		final VirtualReferencePartition row =
			SingleOwnerPartitionFixture.findRegistered(this.evita, entityType, scope, key);
		assertNotNull(row, "the catalog must hold the row of " + key + " in " + scope);
		assertEquals(owner, row.ownerPrimaryKey(), "the owner of the row of " + key + " in " + scope);
		final EntityCollection collection = SingleOwnerPartitionFixture.collectionOf(this.evita, entityType);
		assertNull(
			collection.getIndexByPrimaryKeyIfExists(row.getPrimaryKey()),
			"the partition of " + key + " in " + scope + " must have no index object under the primary key of its row"
		);
		assertNull(
			collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key)),
			"the partition of " + key + " in " + scope + " must have no index object"
		);
		return row.getPrimaryKey();
	}

	/**
	 * Asserts that the committed catalog represents the partition of the key by an index object holding exactly the
	 * owners, and by no registry row.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owners     the expected owners, in ascending order
	 * @return the primary key of the partition
	 */
	int assertIndexObject(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int... owners
	) {
		final EntityIndex partition = SingleOwnerPartitionFixture.collectionOf(this.evita, entityType)
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key));
		assertNotNull(partition, "the partition of " + key + " in " + scope + " must be an index object");
		assertArrayEquals(
			owners, partition.getAllPrimaryKeys().getArray(),
			"the owners of the partition of " + key + " in " + scope
		);
		assertNoRow(entityType, scope, key);
		return partition.getPrimaryKey();
	}

	/**
	 * Asserts that the committed catalog holds no registry row of the key.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	void assertNoRow(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		assertNull(
			SingleOwnerPartitionFixture.findRegistered(this.evita, entityType, scope, key),
			"the catalog must hold no row of " + key + " in " + scope
		);
	}

	/**
	 * Returns the running engine.
	 *
	 * @return the engine
	 */
	@Nonnull
	Evita getEvita() {
		return this.evita;
	}

	/**
	 * Returns the identity of the partitions observed so far, for the history to declare the keys it empties and
	 * enters again, or has replayed by a crash recovery, before the next moment of checking.
	 *
	 * @return the ledger
	 */
	@Nonnull
	PartitionIdentityLedger getLedger() {
		return this.ledger;
	}

	@Override
	public void close() {
		try {
			this.evita.close();
		} finally {
			for (final TestPaths allocated : this.allocatedPaths) {
				this.support.cleanupTestPaths(allocated);
			}
		}
	}

	/**
	 * Checks every collection inside an open session - see the class documentation.
	 *
	 * @param label   names the moment in failure messages
	 * @param session the session the steps ran in
	 */
	private void checkInSession(@Nonnull String label, @Nonnull EvitaSessionContract session) {
		final Catalog catalog = catalog();
		// the primary keys an open transaction of a live catalog shows are provisional
		final PartitionIdentityLedger theLedger =
			catalog.getCatalogState() == CatalogState.WARMING_UP ? this.ledger : null;
		for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
			final EntityCollection collection =
				(EntityCollection) catalog.getCollectionForEntityOrThrowException(entityType);
			VirtualPartitionInvariants.assertNoPartitionObjectBelowTwoOwners(label, collection);
			VirtualPartitionInvariants.assertMembershipKnowsOnlyExistingPartitions(label, collection);
			VirtualReferencePartitionRawRowOracle.assertConsistent(label, session, collection, theLedger);
		}
	}

	/**
	 * Checks the committed catalog - see the class documentation.
	 *
	 * @param label names the moment in failure messages
	 */
	private void checkCommitted(@Nonnull String label) {
		VirtualPartitionInvariants.assertInvariants(label, this.evita, CATALOG);
		this.evita.queryCatalog(
			CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
					VirtualReferencePartitionRawRowOracle.assertConsistent(
						label, session, VirtualPartitionInvariants.collectionOf(this.evita, CATALOG, entityType),
						this.ledger
					);
				}
			}
		);
		VirtualPartitionInvariants.assertStoredParts(label, this.evita, CATALOG);
	}

	/**
	 * Returns the currently published catalog of the engine.
	 *
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return VirtualPartitionInvariants.catalogOf(this.evita, CATALOG);
	}

	/**
	 * Applies a step in the session and tells whether it failed with the expected exception; any other failure is
	 * rethrown.
	 *
	 * @param step     the step
	 * @param session  the session
	 * @param expected the exception the failure must carry in its cause chain
	 * @return TRUE when the step failed with the expected exception
	 */
	private static boolean fails(
		@Nonnull Consumer<EvitaSessionContract> step,
		@Nonnull EvitaSessionContract session,
		@Nonnull Class<? extends Throwable> expected
	) {
		try {
			step.accept(session);
			return false;
		} catch (RuntimeException ex) {
			if (!ExceptionUtils.causeChainContains(ex, expected)) {
				throw ex;
			}
			return true;
		}
	}

	/**
	 * Starts the engine over its current directories and waits until it has loaded the catalog.
	 *
	 * @return the engine
	 */
	@Nonnull
	private Evita startEngine() {
		final Evita started = new Evita(configurationOf(this.paths));
		CatalogReloadTestSupport.awaitCatalogLoaded(started, CATALOG);
		return started;
	}

	/**
	 * Allocates a set of directories and remembers it for {@link #close()}.
	 *
	 * @param label a label naming the directories
	 * @return the directories, not created yet
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths allocated = this.support.createTestPaths(label);
		this.allocatedPaths.add(allocated);
		return allocated;
	}

	/**
	 * Builds the configuration of the engine over the given directories.
	 *
	 * @param enginePaths the directories of the engine
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths enginePaths) {
		final EvitaConfiguration.Builder builder = this.support.newTestEvitaConfigurationBuilder(enginePaths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			);
		if (this.transactionOptions != null) {
			builder.transaction(this.transactionOptions);
		}
		return builder.build();
	}

	/**
	 * Reads the catalog version of the last bootstrap record in the storage of an engine that is not running - the
	 * version its next load starts from, before anything it replays advances it.
	 *
	 * @param enginePaths the directories of the engine
	 * @return the catalog version the last bootstrap record publishes
	 */
	private long readImageVersion(@Nonnull TestPaths enginePaths) {
		final StorageOptions storageOptions = configurationOf(enginePaths).storage();
		try (
			final Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				CATALOG,
				EvitaTestSupport.catalogDirectory(enginePaths.storage(), CATALOG),
				// bootstrap records are never compressed
				new StorageSettings(
					StorageOptions.builder(storageOptions).compress(false).build(),
					TransactionOptions.builder().build()
				)
			)
		) {
			return records.reduce((previous, next) -> next).orElseThrow().catalogVersion();
		}
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
					// milliseconds, because the copy keeps the modification time only as precisely as the file system
					// it copies to stores it
					Files.size(file) + " bytes, modified at " + Files.getLastModifiedTime(file).toMillis()
				);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot list `" + root + "`!", ex);
		}
		return listing;
	}

	/**
	 * What a crash recovery loaded.
	 *
	 * @param imageVersion  the catalog version of the last bootstrap record of the crash image - the version the load
	 *                      started from
	 * @param loadedVersion the catalog version once the load finished, the write-ahead log replay included
	 */
	record Recovery(long imageVersion, long loadedVersion) {
	}

}
