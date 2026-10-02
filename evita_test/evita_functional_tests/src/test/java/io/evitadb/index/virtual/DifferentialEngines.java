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
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two embedded engines driven through one scripted mutation history in lock-step: the **reference engine** with the
 * maintenance of virtual reference partitions switched off for every collection, and the **maintaining engine** with
 * it switched on. Maintaining the partitions may write the registry of single-owner partitions and nothing else, so
 * whatever the two engines can be asked must come out the same - and this class asks them, at every point at which a
 * maintenance defect could hide.
 *
 * # Checkpoints
 *
 * - **Inside the still-open session**, after every step - in `ALIVE` that is inside the transaction, before the commit
 *   replays the transaction's mutations onto the last finalized catalog. A defect that only the transaction-local
 *   state shows would be erased by the replay, so the comparison after the commit alone cannot see it.
 * - **After a caught mutation failure**, still inside the session - the failed mutation must leave both engines
 *   exactly where they were, savepoint rollback included.
 * - **After the session closes** - a commit in `ALIVE`, a flush in `WARMING_UP`.
 * - **After a restart** of both engines, and **after a crash recovery** of both - see below.
 *
 * Every checkpoint also asserts that the switch is on for every collection of the maintaining engine and off for every
 * collection of the reference engine, so a switch lost on the way through a commit, going live or a rename fails the
 * checkpoint instead of silently turning the maintaining engine into a second reference engine.
 *
 * # What is compared
 *
 * Each checkpoint reduces each engine to a sorted map of `path -> value` lines, and the two maps must be equal:
 *
 * - **query level** - every entity body in both scopes, the facet summary, the attribute histograms of numeric
 *   filterable attributes, the reference histograms, and the entities `referenceHaving` matches per referenced
 *   entity;
 * - **index level** (the indexes maintaining the partitions must not notice the registry) - the global index's
 *   members and attribute indexes, every referenced-type index's advertised partitions per referenced entity, its
 *   filter values with their complete value-to-record mappings and its registration counts, every advertised
 *   partition's key, primary key, owners, languages and attribute indexes, the reduced-index membership's covered and
 *   residual partitions and owners, and the index population per type and scope;
 * - **enrollment** (inside the session only) - the indexes the open session has acquired for modification and not
 *   flushed yet, so a maintaining engine that touches an index the reference engine leaves alone is found even when
 *   it leaves the index's content unchanged;
 * - **storage level** - the record count and byte total per storage part type, and the collection header's last index
 *   primary key.
 *
 * Every attribute index is compared in full:
 *
 * - a filter index by its value-to-record mapping;
 * - a sort index position by position (see {@link SortIndexWalk}) - the records, the value of each and the blocks of
 *   records sharing a value, so two indexes ordering the same records over the same values but splitting them into
 *   blocks differently differ;
 * - a unique index by its records and value count, and a standalone one (see {@link OwnerUniqueIndex}) also value by
 *   value - a unique index folded onto a filter index is compared through that filter index's mapping;
 * - a chain index by its materialized order and by the predecessor every element declares, so a predecessor that
 *   changes without moving the element is found.
 *
 * Values are compared by their {@link CanonicalValue} encoding, never by their rendering, which is lossy for exactly
 * the values an index normalizes.
 *
 * # Accepted differences
 *
 * Maintaining the registry legitimately changes a few things, and they are listed here and nowhere else:
 *
 * - the storage part types of the registry itself - {@link #EXCLUDED_STORAGE_PART_TYPES};
 * - the heap size of the referenced-type entity index, which includes the registry - no heap size is ever sampled;
 * - how often a referenced-type entity index of referenced entities is enrolled for modification, dirty and flushed -
 *   its enrollment is not compared, and only the live record set is, never the data store's waste or file sizes.
 *
 * # Test-only reads
 *
 * Four structures have no public read and are compared through reads that exist for this harness alone: the
 * per-partition registration counts and the per-value counts of the filter attributes of a referenced-type entity
 * index, the value-to-record mapping of a standalone unique index, and the indexes a collection has enrolled for
 * modification.
 *
 * # Restart and crash recovery
 *
 * The switch is not persisted, so the maintaining engine is told which collections to maintain before it loads the
 * catalog again (see {@link Evita#maintainVirtualReferencePartitionsOnLoad(String, Set)}). The mutations the reopened
 * catalog replays from its write-ahead log at load are therefore maintained too.
 *
 * {@link #restart()} closes both engines cleanly, which publishes everything they applied, so the reopened catalogs
 * have nothing to replay. {@link #crashAndRecover()} copies the storage of both engines aside while they run - the
 * image a crash at that moment would leave - and boots both engines from the copies. Combined with a checkpoint
 * interval long enough to defer every checkpoint (see {@link #DifferentialEngines(EvitaTestSupport, String, Set,
 * TransactionOptions)}), the copies hold the mutations committed after the last checkpoint in the write-ahead log only,
 * and the boot replays them. Both report what the load found and did as a {@link Reload}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class DifferentialEngines implements AutoCloseable {
	/**
	 * Name of the catalog both engines host.
	 */
	static final String CATALOG = "differentialEngines";
	/**
	 * Storage part types only the maintaining engine may persist - the root and leaf pages of the registry of
	 * single-owner partitions. Excluded from the storage-level comparison.
	 */
	static final Set<String> EXCLUDED_STORAGE_PART_TYPES = Set.of(
		VirtualReferencePartitionIndexStoragePart.class.getSimpleName(),
		VirtualReferencePartitionIndexLeafPagePart.class.getSimpleName()
	);
	/**
	 * Upper bound of the entities one query fetches. The fixtures driving the engines are far smaller.
	 */
	private static final int PAGE_SIZE = 10_000;
	/**
	 * How many differences a failure message lists before it is cut short.
	 */
	private static final int REPORTED_DIFFERENCES = 40;

	/**
	 * The test owning the engines, used for their directories and configuration.
	 */
	private final EvitaTestSupport support;
	/**
	 * The entity types whose collections the maintaining engine maintains. Switched on by name, so a collection
	 * created later - in a transaction or by its replay - is maintained from the start.
	 */
	private final Set<String> entityTypes;
	/**
	 * The transaction settings of both engines; `null` for the defaults of the test configuration.
	 */
	@Nullable private final TransactionOptions transactionOptions;
	/**
	 * Every directory set any engine of this instance has used - the original ones and the crash images - so that
	 * {@link #close()} removes them all.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(4);
	/**
	 * Directories of the reference engine - replaced by its crash image once the engine recovers from one.
	 */
	private TestPaths referencePaths;
	/**
	 * Directories of the maintaining engine - replaced by its crash image once the engine recovers from one.
	 */
	private TestPaths maintainingPaths;
	/**
	 * Checks run on every collection of the maintaining engine at every checkpoint, with the checkpoint label.
	 */
	private final List<BiConsumer<String, EntityCollection>> maintainingEngineChecks = new ArrayList<>(2);
	/**
	 * The engine with the switch off.
	 */
	private Evita reference;
	/**
	 * The engine with the switch on.
	 */
	private Evita maintaining;

	/**
	 * Starts both engines, each in its own directories with an identical configuration.
	 *
	 * @param support     the test owning the engines
	 * @param label       a label naming the engines' directories
	 * @param entityTypes the entity types the maintaining engine maintains
	 */
	DifferentialEngines(
		@Nonnull EvitaTestSupport support,
		@Nonnull String label,
		@Nonnull Set<String> entityTypes
	) {
		this(support, label, entityTypes, null);
	}

	/**
	 * Starts both engines, each in its own directories with an identical configuration using the given transaction
	 * settings - typically a checkpoint interval that defers every checkpoint, so that {@link #crashAndRecover()} finds
	 * a write-ahead log tail to replay.
	 *
	 * @param support            the test owning the engines
	 * @param label              a label naming the engines' directories
	 * @param entityTypes        the entity types the maintaining engine maintains
	 * @param transactionOptions the transaction settings of both engines; `null` for the defaults of the test
	 *                           configuration
	 */
	DifferentialEngines(
		@Nonnull EvitaTestSupport support,
		@Nonnull String label,
		@Nonnull Set<String> entityTypes,
		@Nullable TransactionOptions transactionOptions
	) {
		this.support = support;
		this.entityTypes = Set.copyOf(entityTypes);
		this.transactionOptions = transactionOptions;
		this.referencePaths = allocatePaths(label + "_reference");
		this.maintainingPaths = allocatePaths(label + "_maintaining");
		this.reference = new Evita(configurationOf(this.referencePaths));
		this.maintaining = new Evita(configurationOf(this.maintainingPaths));
	}

	/**
	 * Defines the catalog in both engines, switches the maintaining engine on, runs the setup as one step and, for
	 * `ALIVE`, takes both catalogs live.
	 *
	 * @param state the catalog state the engines should end in
	 * @param setup the schema and the initial data
	 */
	void prepare(@Nonnull CatalogState state, @Nonnull Consumer<EvitaSessionContract> setup) {
		this.reference.defineCatalog(CATALOG).updateViaNewSession(this.reference);
		this.maintaining.defineCatalog(CATALOG).updateViaNewSession(this.maintaining);
		switchMaintainingEngineOn();
		step("setup", setup);
		if (state == CatalogState.ALIVE) {
			this.reference.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			this.maintaining.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			assertEqual("after going live");
		}
	}

	/**
	 * Registers a check run on every collection of the maintaining engine at every checkpoint - typically the parity
	 * oracle of the registry.
	 *
	 * @param check receives the checkpoint label and the collection
	 */
	void addMaintainingEngineCheck(@Nonnull BiConsumer<String, EntityCollection> check) {
		this.maintainingEngineChecks.add(check);
	}

	/**
	 * Applies one step to both engines, each in a session of its own, and compares them inside the session and after
	 * it closes.
	 *
	 * @param label names the step in failure messages
	 * @param step  the mutation to apply
	 */
	void step(@Nonnull String label, @Nonnull Consumer<EvitaSessionContract> step) {
		transaction(label, List.of(step));
	}

	/**
	 * Applies several steps to both engines in one session each - one transaction in `ALIVE` - and compares them after
	 * every step inside the session and once more after it closes.
	 *
	 * @param label names the transaction in failure messages
	 * @param steps the mutations to apply, in order
	 */
	void transaction(@Nonnull String label, @Nonnull List<Consumer<EvitaSessionContract>> steps) {
		final List<Snapshot> onReference = runSession(this.reference, steps, false);
		final List<Snapshot> onMaintaining = runSession(this.maintaining, steps, false);
		for (int i = 0; i < steps.size(); i++) {
			assertNoDifference(
				label + " / step " + (i + 1) + " inside the session", onReference.get(i), onMaintaining.get(i)
			);
		}
		assertEqual(label + " / after the session closed");
	}

	/**
	 * Applies a step expected to fail to both engines, each in a session of its own. The failure is caught inside the
	 * session, which then goes on and closes normally. The engines are compared right after the caught failure and
	 * after the session closes.
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
		final Consumer<EvitaSessionContract> caught = session -> {
			try {
				step.accept(session);
			} catch (RuntimeException ex) {
				if (ExceptionUtils.findInCauseChain(ex, expected) == null) {
					throw ex;
				}
				return;
			}
			fail(label + ": the step was expected to fail with " + expected.getSimpleName());
		};
		final Snapshot onReference = runSession(this.reference, List.of(caught), false).get(0);
		final Snapshot onMaintaining = runSession(this.maintaining, List.of(caught), false).get(0);
		assertNoDifference(label + " / after the caught failure inside the session", onReference, onMaintaining);
		assertEqual(label + " / after the session closed");
	}

	/**
	 * Applies a different step to each engine, each in a session of its own, and reports the differences inside the
	 * session and after it closes, without asserting anything. It exists to prove that the comparison can fail.
	 *
	 * @param onReference   the step applied to the reference engine
	 * @param onMaintaining the step applied to the maintaining engine
	 * @param rollback      TRUE to roll both sessions back instead of committing them
	 * @return the differences found at both checkpoints
	 */
	@Nonnull
	Divergence divergentSession(
		@Nonnull Consumer<EvitaSessionContract> onReference,
		@Nonnull Consumer<EvitaSessionContract> onMaintaining,
		boolean rollback
	) {
		final Snapshot inReference = runSession(this.reference, List.of(onReference), rollback).get(0);
		final Snapshot inMaintaining = runSession(this.maintaining, List.of(onMaintaining), rollback).get(0);
		return new Divergence(
			inReference.differencesFrom(inMaintaining),
			committedSnapshot(this.reference, false).differencesFrom(committedSnapshot(this.maintaining, true))
		);
	}

	/**
	 * Closes both engines cleanly, starts them again over the same directories - the maintaining one with the switch
	 * on from the moment it loads the catalog - and compares them.
	 *
	 * @return what each engine applied before the close and what its load found and did
	 */
	@Nonnull
	Reload restart() {
		final Catalog referenceCatalog = catalogOf(this.reference);
		final Catalog maintainingCatalog = catalogOf(this.maintaining);
		this.reference.close();
		this.maintaining.close();
		final Reload reload = new Reload(
			startReference(referenceCatalog.getVersion(), referenceCatalog.getLastPersistedCatalogVersion()),
			startMaintaining(maintainingCatalog.getVersion(), maintainingCatalog.getLastPersistedCatalogVersion())
		);
		assertEqual("after restart");
		return reload;
	}

	/**
	 * Takes the image a crash would leave of both running engines - a copy of each engine's own storage directory
	 * taken while the engine is quiescent and before it is closed - closes the engines, boots them from the copies -
	 * the maintaining one with the switch on from the moment it loads the catalog - and compares them.
	 *
	 * Closing an engine publishes everything it applied, so the copy must be taken before the close for it to keep the
	 * write-ahead log tail a crash would leave. The copy is taken only once the engine has incorporated everything its
	 * write-ahead log holds, and is accepted only when no file of the storage directory changed while it was being
	 * copied and the catalog published no new version meanwhile.
	 *
	 * @return what each engine had applied and checkpointed when it was copied and what its load from the copy found
	 * and did
	 */
	@Nonnull
	Reload crashAndRecover() {
		final CrashImage referenceImage = takeCrashImage(this.reference, this.referencePaths, "_referenceImage");
		final CrashImage maintainingImage = takeCrashImage(
			this.maintaining, this.maintainingPaths, "_maintainingImage"
		);
		this.reference.close();
		this.maintaining.close();
		this.referencePaths = referenceImage.paths();
		this.maintainingPaths = maintainingImage.paths();
		final Reload reload = new Reload(
			startReference(referenceImage.appliedVersion(), referenceImage.persistedVersion()),
			startMaintaining(maintainingImage.appliedVersion(), maintainingImage.persistedVersion())
		);
		assertEqual("after the crash recovery");
		return reload;
	}

	/**
	 * Compares the committed state of both engines and fails on any difference.
	 *
	 * @param label names the checkpoint in failure messages
	 */
	void assertEqual(@Nonnull String label) {
		assertNoDifference(label, committedSnapshot(this.reference, false), committedSnapshot(this.maintaining, true));
	}

	/**
	 * Returns the committed state of the reference engine reduced to the lines the comparison is made of.
	 *
	 * @return the snapshot lines
	 */
	@Nonnull
	SortedMap<String, String> referenceSnapshot() {
		return committedSnapshot(this.reference, false).lines();
	}

	/**
	 * Returns the engine with the switch off.
	 *
	 * @return the reference engine
	 */
	@Nonnull
	Evita getReference() {
		return this.reference;
	}

	/**
	 * Returns the engine with the switch on.
	 *
	 * @return the maintaining engine
	 */
	@Nonnull
	Evita getMaintaining() {
		return this.maintaining;
	}

	@Override
	public void close() {
		try {
			this.reference.close();
			this.maintaining.close();
		} finally {
			for (final TestPaths paths : this.allocatedPaths) {
				this.support.cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * Allocates a set of directories and remembers it for {@link #close()}.
	 *
	 * @param label a label naming the directories
	 * @return the directories, not created yet
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = this.support.createTestPaths(label);
		this.allocatedPaths.add(paths);
		return paths;
	}

	/**
	 * Builds the configuration both engines share, differing only in their directories.
	 *
	 * @param paths the directories of one engine
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths paths) {
		final EvitaConfiguration.Builder builder = this.support.newTestEvitaConfigurationBuilder(paths)
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
	 * Starts the reference engine over its current directories and waits until it has loaded the catalog.
	 *
	 * @param appliedVersion   the catalog version the previous engine had applied when it stopped
	 * @param persistedVersion the catalog version the previous engine had checkpointed when it stopped
	 * @return what the load found and did
	 */
	@Nonnull
	private EngineLoad startReference(long appliedVersion, long persistedVersion) {
		final long imageVersion = readImageVersion(this.referencePaths);
		this.reference = new Evita(configurationOf(this.referencePaths));
		awaitCatalogLoaded(this.reference);
		return describeLoad(this.reference, appliedVersion, persistedVersion, imageVersion);
	}

	/**
	 * Starts the maintaining engine over its current directories with the switch on from the moment it loads the
	 * catalog, and waits until it has loaded it.
	 *
	 * @param appliedVersion   the catalog version the previous engine had applied when it stopped
	 * @param persistedVersion the catalog version the previous engine had checkpointed when it stopped
	 * @return what the load found and did
	 */
	@Nonnull
	private EngineLoad startMaintaining(long appliedVersion, long persistedVersion) {
		final long imageVersion = readImageVersion(this.maintainingPaths);
		this.maintaining = new Evita(configurationOf(this.maintainingPaths), false);
		this.maintaining.maintainVirtualReferencePartitionsOnLoad(CATALOG, this.entityTypes);
		this.maintaining.scheduleInitialCatalogLoading();
		awaitCatalogLoaded(this.maintaining);
		return describeLoad(this.maintaining, appliedVersion, persistedVersion, imageVersion);
	}

	/**
	 * Reads the catalog version of the last bootstrap record in the storage of an engine that is not running - the
	 * version its next load starts from, before anything it replays advances it.
	 *
	 * @param paths the directories of the engine
	 * @return the catalog version the last bootstrap record publishes
	 */
	private long readImageVersion(@Nonnull TestPaths paths) {
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
			return records.reduce((previous, next) -> next).orElseThrow().catalogVersion();
		}
	}

	/**
	 * Describes the load an engine has just finished, before anything else runs in it.
	 *
	 * @param evita            the engine that has just loaded the catalog
	 * @param appliedVersion   the catalog version the previous engine had applied when it stopped
	 * @param persistedVersion the catalog version the previous engine had checkpointed when it stopped
	 * @param imageVersion     the catalog version of the last bootstrap record of the storage the engine loaded
	 * @return what the load found and did
	 */
	@Nonnull
	private static EngineLoad describeLoad(
		@Nonnull Evita evita,
		long appliedVersion,
		long persistedVersion,
		long imageVersion
	) {
		final Catalog catalog = catalogOf(evita);
		return new EngineLoad(
			appliedVersion,
			persistedVersion,
			imageVersion,
			catalog.getVersion(),
			catalog.getVirtualReferencePartitionMaintenance().getMaintainedMutationCount()
		);
	}

	/**
	 * Copies the storage directory of a running engine aside as the image a crash at this moment would leave.
	 *
	 * The engine must be quiescent while it is copied, and this method makes sure it is rather than assuming it:
	 *
	 * - it waits until the catalog has incorporated every transaction its write-ahead log holds, so no commit is still
	 *   in flight;
	 * - it lists every file of the storage directory with its length and modification time before and after the copy,
	 *   and requires both listings and the listing of the copy to be identical;
	 * - it requires the catalog instance published before the copy to be still the published one after it.
	 *
	 * @param evita  the running engine
	 * @param paths  the directories of the engine
	 * @param suffix a suffix naming the directories of the image
	 * @return the image and the versions the engine had applied and checkpointed when it was copied
	 */
	@Nonnull
	private CrashImage takeCrashImage(@Nonnull Evita evita, @Nonnull TestPaths paths, @Nonnull String suffix) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> catalogOf(evita).getVersion() == catalogOf(evita).getLastCatalogVersionInMutationStream());
		final Catalog catalog = catalogOf(evita);
		final long appliedVersion = catalog.getVersion();
		final long persistedVersion = catalog.getLastPersistedCatalogVersion();

		final TestPaths image = allocatePaths(paths.storage().getFileName() + suffix);
		final SortedMap<String, String> beforeCopy = listFiles(paths.storage());
		copyRecursively(paths.storage(), image.storage());
		final SortedMap<String, String> afterCopy = listFiles(paths.storage());

		assertEquals(
			beforeCopy, afterCopy,
			"The storage of the engine changed while it was being copied - the image is not the state of one moment"
		);
		assertEquals(
			beforeCopy, listFiles(image.storage()),
			"The image must hold every file of the storage, whole"
		);
		assertSame(
			catalog, catalogOf(evita),
			"The catalog published a new version while its storage was being copied"
		);
		return new CrashImage(image, appliedVersion, persistedVersion);
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
	 * Copies a directory tree, creating the target if it does not exist, and keeps the modification time of every
	 * file so the copy can be compared with its source.
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
	 * Switches the maintaining engine on for every entity type the history uses, whether its collection exists yet or
	 * not.
	 */
	private void switchMaintainingEngineOn() {
		final Catalog catalog = catalogOf(this.maintaining);
		for (final String entityType : this.entityTypes) {
			catalog.getVirtualReferencePartitionMaintenance().setMaintained(entityType, true);
		}
	}

	/**
	 * Runs the steps in one read-write session of the engine, taking a snapshot inside the session after each step,
	 * and closes the session - committing it, or rolling it back when asked to.
	 *
	 * @param evita       the engine
	 * @param steps       the mutations to apply
	 * @param rollback    TRUE to roll the session back
	 * @return one snapshot per step, taken inside the session
	 */
	@Nonnull
	private List<Snapshot> runSession(
		@Nonnull Evita evita,
		@Nonnull List<Consumer<EvitaSessionContract>> steps,
		boolean rollback
	) {
		final boolean maintained = evita == this.maintaining;
		final List<Snapshot> snapshots = new ArrayList<>(steps.size());
		try {
			evita.updateCatalog(
				CATALOG,
				session -> {
					for (final Consumer<EvitaSessionContract> step : steps) {
						step.accept(session);
						// `updateCatalog` runs this lambda with the session's transaction bound to the thread, so the
						// index walk below reads the transaction-local state the commit has not replayed yet
						snapshots.add(snapshot(evita, session, maintained, false, "inside the session"));
					}
					if (rollback) {
						session.setRollbackOnly();
					}
				}
			);
		} catch (RollbackException ex) {
			// the session reports the rollback it was asked for by throwing once it closes
			if (!rollback) {
				throw ex;
			}
		}
		return snapshots;
	}

	/**
	 * Takes the snapshot of an engine's committed state in a read-only session.
	 *
	 * @param evita      the engine
	 * @param maintained TRUE when the engine is the maintaining one
	 * @return the snapshot
	 */
	@Nonnull
	private Snapshot committedSnapshot(@Nonnull Evita evita, boolean maintained) {
		return evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, Snapshot>) session -> snapshot(evita, session, maintained, true, "committed")
		);
	}

	/**
	 * Reduces one engine to the lines the comparison is made of, and runs the switch assertion and the registered
	 * checks on the way.
	 *
	 * @param evita      the engine
	 * @param session    a session of the engine - for the in-session checkpoints the one the steps ran in
	 * @param maintained TRUE when the engine is the maintaining one
	 * @param committed  TRUE when the snapshot describes committed state, which is the only state the storage-level
	 *                   lines describe - the storage holds nothing of an open session, and a collection created in it
	 *                   is not even visible to the statistics yet
	 * @param label      names the checkpoint in failure messages of the checks
	 * @return the snapshot
	 */
	@Nonnull
	private Snapshot snapshot(
		@Nonnull Evita evita,
		@Nonnull EvitaSessionContract session,
		boolean maintained,
		boolean committed,
		@Nonnull String label
	) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
		final Snapshot snapshot = new Snapshot();
		for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
			final EntityCollection collection =
				(EntityCollection) catalog.getCollectionForEntity(entityType).orElseThrow();
			assertEquals(
				maintained, collection.isVirtualReferencePartitionsMaintained(),
				label + ": the switch of `" + entityType + "` must be " + (maintained ? "on" : "off") +
					" in the " + (maintained ? "maintaining" : "reference") + " engine"
			);
			if (maintained) {
				for (final BiConsumer<String, EntityCollection> check : this.maintainingEngineChecks) {
					check.accept(label, collection);
				}
			}
			final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
			for (final Scope scope : Scope.values()) {
				describeQueries(snapshot, session, schema, scope);
				describeIndexes(snapshot, collection, schema, scope);
			}
			if (committed) {
				describeStorage(snapshot, evita, entityType);
			} else {
				describeEnrollment(snapshot, collection);
			}
		}
		return snapshot;
	}

	/**
	 * Adds the query-level lines of one collection and scope.
	 *
	 * @param snapshot the snapshot to add to
	 * @param session  the session to query in
	 * @param schema   the schema of the collection
	 * @param scope    the scope to query
	 */
	private static void describeQueries(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope
	) {
		final String entityType = schema.getName();
		final String prefix = "query/" + entityType + "/" + scope + "/";
		final EvitaResponse<SealedEntity> bodies = session.query(
			query(collection(entityType), filterBy(scope(scope)), require(page(1, PAGE_SIZE), entityFetchAll())),
			SealedEntity.class
		);
		final Map<String, Set<Integer>> targetsByReference = new TreeMap<>();
		for (final SealedEntity entity : bodies.getRecordData()) {
			snapshot.put(prefix + "entity/" + entity.getPrimaryKeyOrThrowException(), entity.toString());
			for (final ReferenceContract reference : entity.getReferences()) {
				targetsByReference
					.computeIfAbsent(reference.getReferenceName(), __ -> new TreeSet<>())
					.add(reference.getReferencedPrimaryKey());
			}
		}

		boolean faceted = false;
		final List<String> histogramNames = new ArrayList<>(4);
		for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
			faceted |= referenceSchema.isFacetedInScope(scope);
			histogramNames.addAll(new TreeSet<>(referenceSchema.getHistogramIndexDefinitions(scope).keySet()));
			if (!referenceSchema.isIndexedInScope(scope)) {
				continue;
			}
			final String referenceName = referenceSchema.getName();
			for (final Integer target : targetsByReference.getOrDefault(referenceName, Set.of())) {
				final EvitaResponse<EntityReference> matching = session.query(
					query(
						collection(entityType),
						filterBy(scope(scope), referenceHaving(referenceName, entityPrimaryKeyInSet(target))),
						require(page(1, PAGE_SIZE))
					),
					EntityReference.class
				);
				snapshot.put(
					prefix + "referenceHaving/" + referenceName + "/" + target,
					String.valueOf(primaryKeysOf(matching.getRecordData()))
				);
			}
		}
		if (faceted) {
			describeExtraResults(
				snapshot, prefix + "facetSummary",
				session.query(
					query(collection(entityType), filterBy(scope(scope)), require(page(1, 1), facetSummary())),
					EntityReference.class
				)
			);
		}
		if (!histogramNames.isEmpty()) {
			describeExtraResults(
				snapshot, prefix + "referenceHistograms",
				session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(
							page(1, 1),
							referenceSummaryWithHistograms(
								null, null, null,
								histogramStatistics(10, histogramNames.toArray(String[]::new))
							)
						)
					),
					EntityReference.class
				)
			);
		}
		final String[] numericAttributes = schema.getAttributes().values().stream()
			.filter(it -> it.isFilterableInScope(scope) && !it.isLocalized())
			.filter(it -> Number.class.isAssignableFrom(it.getPlainType()))
			.map(AttributeSchemaContract::getName)
			.sorted()
			.toArray(String[]::new);
		if (numericAttributes.length > 0) {
			describeExtraResults(
				snapshot, prefix + "attributeHistograms",
				session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(page(1, 1), attributeHistogram(10, numericAttributes))
					),
					EntityReference.class
				)
			);
		}
	}

	/**
	 * Adds one line per extra result of a query response.
	 *
	 * @param snapshot the snapshot to add to
	 * @param path     the path of the lines
	 * @param response the response carrying the extra results
	 */
	private static void describeExtraResults(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull EvitaResponse<?> response
	) {
		for (final EvitaResponseExtraResult extraResult : response.getExtraResults().values()) {
			snapshot.put(path + "/" + extraResult.getClass().getSimpleName(), extraResult.toString());
		}
	}

	/**
	 * Adds the index-level lines of one collection and scope.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param collection the collection whose indexes are walked
	 * @param schema     the schema of the collection
	 * @param scope      the scope whose indexes are walked
	 */
	private static void describeIndexes(
		@Nonnull Snapshot snapshot,
		@Nonnull EntityCollection collection,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope
	) {
		final String prefix = "index/" + schema.getName() + "/" + scope + "/";
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex != null) {
			describeIndex(snapshot, prefix + "global", globalIndex);
		}
		for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
			final String referenceName = referenceSchema.getName();
			for (final EntityIndexType family : new EntityIndexType[]{
				EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
			}) {
				final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(family, scope, referenceName)
				);
				if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
					describeReferencedTypeIndex(
						snapshot, prefix + family + "/" + referenceName, collection, referencedTypeIndex
					);
				}
			}
			if (globalIndex instanceof final GlobalEntityIndex typedGlobalIndex) {
				final ReducedIndexMembership membership = typedGlobalIndex.getReducedIndexMembership(referenceName);
				if (membership != null) {
					describeMembership(snapshot, prefix + "membership/" + referenceName, membership);
				}
			}
		}
	}

	/**
	 * Adds the lines of one referenced-type entity index and of every partition it advertises.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param path       the path of the lines
	 * @param collection the collection the partitions are resolved in
	 * @param typeIndex  the referenced-type entity index
	 */
	private static void describeReferencedTypeIndex(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		snapshot.put(path + "/primaryKey", String.valueOf(typeIndex.getPrimaryKey()));
		snapshot.put(path + "/advertised", toString(typeIndex.getAllPrimaryKeys()));
		for (final Integer target : new TreeSet<>(typeIndex.getAllTrackedReferencedEntityPrimaryKeys())) {
			final int[] partitions = typeIndex.getAllReferenceIndexes(target).clone();
			Arrays.sort(partitions);
			snapshot.put(path + "/target/" + target, Arrays.toString(partitions));
		}
		describeAttributeIndexes(snapshot, path, typeIndex);
		describeCardinalities(snapshot, path, typeIndex);
		final int[] advertised = typeIndex.getAllPrimaryKeys().getArray();
		for (final int partitionPrimaryKey : advertised) {
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
			final String partitionPath = path + "/partition/" + partitionPrimaryKey;
			if (partition == null) {
				snapshot.put(partitionPath, "MISSING");
			} else {
				describeIndex(snapshot, partitionPath, partition);
			}
		}
	}

	/**
	 * Adds the registration counts of a referenced-type entity index: how many times each partition is registered, in
	 * total and per referenced entity, and how many times each partition carries each value of each filterable
	 * attribute.
	 *
	 * @param snapshot  the snapshot to add to
	 * @param path      the path of the lines
	 * @param typeIndex the referenced-type entity index
	 */
	private static void describeCardinalities(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		snapshot.put(
			path + "/cardinality/partitions", new TreeMap<>(typeIndex.getIndexPrimaryKeyCardinalities()).toString()
		);
		for (final Entry<AttributeIndexKey, Map<AttributeCardinalityKey, Integer>> attribute :
			new TreeMap<>(typeIndex.getAttributeCardinalities()).entrySet()) {
			final SortedMap<String, Integer> counts = new TreeMap<>();
			for (final Entry<AttributeCardinalityKey, Integer> count : attribute.getValue().entrySet()) {
				counts.put(
					count.getKey().recordId() + ":" + CanonicalValue.encode(count.getKey().value()), count.getValue()
				);
			}
			snapshot.put(path + "/cardinality/attribute/" + attribute.getKey(), counts.toString());
		}
	}

	/**
	 * Adds the line listing the indexes of one collection the open session has acquired for modification and not
	 * flushed yet, minus the referenced-type entity indexes of referenced entities - the ones carrying the registry,
	 * which the maintaining engine legitimately enrolls more often.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param collection the collection
	 */
	private static void describeEnrollment(@Nonnull Snapshot snapshot, @Nonnull EntityCollection collection) {
		final Set<String> enrolled = new TreeSet<>();
		for (final IndexKey indexKey : collection.getDirtyIndexKeys()) {
			if (!(indexKey instanceof final EntityIndexKey entityIndexKey) ||
				entityIndexKey.type() != EntityIndexType.REFERENCED_ENTITY_TYPE) {
				enrolled.add(indexKey.toString());
			}
		}
		snapshot.put("enrollment/" + collection.getEntityType(), enrolled.toString());
	}

	/**
	 * Adds the lines of one entity index: its key, members, languages and attribute indexes.
	 *
	 * @param snapshot the snapshot to add to
	 * @param path     the path of the lines
	 * @param index    the index
	 */
	private static void describeIndex(@Nonnull Snapshot snapshot, @Nonnull String path, @Nonnull EntityIndex index) {
		snapshot.put(path + "/key", index.getIndexKey().toString());
		snapshot.put(path + "/primaryKey", String.valueOf(index.getPrimaryKey()));
		snapshot.put(path + "/members", toString(index.getAllPrimaryKeys()));
		final List<Locale> locales = new ArrayList<>(index.getLanguages());
		locales.sort(Comparator.comparing(Locale::toLanguageTag));
		for (final Locale locale : locales) {
			snapshot.put(
				path + "/language/" + locale.toLanguageTag(),
				toString(index.getRecordsWithLanguageFormula(locale).compute())
			);
		}
		describeAttributeIndexes(snapshot, path, index);
	}

	/**
	 * Adds the lines of every attribute index an entity index holds - the complete value-to-record mapping of a filter
	 * index, the record and value order of a sort index, the records of a unique index and the order of a chain index.
	 *
	 * @param snapshot the snapshot to add to
	 * @param path     the path of the lines
	 * @param index    the entity index
	 */
	private static void describeAttributeIndexes(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull EntityIndex index
	) {
		index.forEachAttributeIndexKey(AttributeIndexType.FILTER, key -> {
			final FilterIndex filterIndex = index.getFilterIndex(key);
			if (filterIndex != null) {
				final StringBuilder buckets = new StringBuilder(64);
				for (final ValueToRecord bucket : filterIndex.getHistogramOfAllRecords().getBuckets()) {
					buckets.append(CanonicalValue.encode(bucket.getValue()))
						.append('=')
						.append(toString(bucket.getRecordIds()))
						.append("; ");
				}
				snapshot.put(path + "/filter/" + key, buckets.toString());
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.SORT, key -> {
			final SortIndex sortIndex = index.getSortIndex(key);
			if (sortIndex != null) {
				snapshot.put(path + "/sort/" + key, describeSortIndex(sortIndex));
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.UNIQUE, key -> {
			final UniqueIndex uniqueIndex = index.getUniqueIndex(key);
			if (uniqueIndex != null) {
				snapshot.put(
					path + "/unique/" + key,
					toString(uniqueIndex.getRecordIds()) + " of " + uniqueIndex.getDistinctValueCount() + " values"
				);
				if (uniqueIndex instanceof final OwnerUniqueIndex ownerUniqueIndex) {
					snapshot.put(path + "/unique/" + key + "/mapping", describeUniqueMapping(ownerUniqueIndex));
				}
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.CHAIN, key -> {
			final ChainIndex chainIndex = index.getChainIndex(key);
			if (chainIndex != null) {
				snapshot.put(path + "/chain/" + key, Arrays.toString(chainIndex.getUnorderedLookup().getArray()));
				snapshot.put(path + "/chain/" + key + "/predecessors", describeChainPredecessors(chainIndex));
			}
		});
	}

	/**
	 * Adds the lines of one reduced-index membership: the covered and residual partitions, the covered owners and the
	 * partitions each covered owner is a member of.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param path       the path of the lines
	 * @param membership the membership
	 */
	private static void describeMembership(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull ReducedIndexMembership membership
	) {
		snapshot.put(path + "/covered", toString(membership.getCoveredIndexPrimaryKeys()));
		snapshot.put(path + "/residual", toString(membership.getResidualIndexPrimaryKeys()));
		final Bitmap coveredOwners = membership.getCoveredOwners();
		snapshot.put(path + "/coveredOwners", toString(coveredOwners));
		for (final int owner : coveredOwners.getArray()) {
			snapshot.put(path + "/owner/" + owner, toString(membership.getIndexPrimaryKeys(owner)));
		}
	}

	/**
	 * Adds the storage-level lines of one collection: the record count and byte total per storage part type, minus
	 * the {@link #EXCLUDED_STORAGE_PART_TYPES}, the index population per type and scope, and the last index primary
	 * key of the collection header.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param evita      the engine
	 * @param entityType the entity type of the collection
	 */
	private static void describeStorage(@Nonnull Snapshot snapshot, @Nonnull Evita evita, @Nonnull String entityType) {
		final String prefix = "storage/" + entityType + "/";
		final EntityCollectionStatistics statistics = evita.management().getEntityCollectionStatistics(
			CATALOG, entityType,
			EnumSet.of(
				CatalogStatisticsComponent.STORAGE_COMPOSITION,
				CatalogStatisticsComponent.INDEX_SUMMARY,
				CatalogStatisticsComponent.COLLECTIONS
			)
		);
		final StoragePartUsage[] parts = statistics.storageCompositionIfPresent().orElseThrow().parts();
		for (final StoragePartUsage part : parts) {
			if (!EXCLUDED_STORAGE_PART_TYPES.contains(part.storagePartType())) {
				snapshot.put(
					prefix + "part/" + part.storagePartType(),
					part.count() + " records, " + part.totalBytes() + " bytes"
				);
			}
		}
		snapshot.put(prefix + "population", statistics.indexSummaryIfPresent().orElseThrow().toString());
		snapshot.put(
			prefix + "lastEntityIndexPrimaryKey",
			String.valueOf(statistics.headerIfPresent().orElseThrow().lastEntityIndexPrimaryKey())
		);
	}

	/**
	 * Renders a sort index position by position - see {@link SortIndexWalk#describe()}.
	 *
	 * @param sortIndex the sort index
	 * @return its rendering
	 */
	@Nonnull
	static String describeSortIndex(@Nonnull SortIndex sortIndex) {
		return SortIndexWalk.of(sortIndex).describe();
	}

	/**
	 * Renders the value-to-record mapping of a standalone unique index, in the value order of the index.
	 *
	 * @param uniqueIndex the unique index
	 * @return its rendering
	 */
	@Nonnull
	static String describeUniqueMapping(@Nonnull OwnerUniqueIndex uniqueIndex) {
		final StringBuilder mapping = new StringBuilder(64);
		uniqueIndex.forEachValueToRecord(
			(value, recordId) -> mapping.append(CanonicalValue.encode(value)).append('=').append(recordId).append("; ")
		);
		return mapping.toString();
	}

	/**
	 * Renders the predecessor every element of a chain index declares, by ascending element primary key.
	 *
	 * @param chainIndex the chain index
	 * @return its rendering
	 */
	@Nonnull
	static String describeChainPredecessors(@Nonnull ChainIndex chainIndex) {
		final int[] elements = chainIndex.getUnorderedLookup().getArray().clone();
		Arrays.sort(elements);
		final StringBuilder predecessors = new StringBuilder(16 * elements.length + 2);
		for (final int element : elements) {
			final OptionalInt predecessor = chainIndex.getPredecessorPrimaryKey(element);
			predecessors.append(element).append("<-")
				.append(predecessor.isPresent() ? String.valueOf(predecessor.getAsInt()) : "NONE")
				.append("; ");
		}
		return predecessors.toString();
	}

	/**
	 * Renders a bitmap by its elements.
	 *
	 * @param bitmap the bitmap
	 * @return its rendering
	 */
	@Nonnull
	private static String toString(@Nonnull Bitmap bitmap) {
		return Arrays.toString(bitmap.getArray());
	}

	/**
	 * Collects the primary keys of entity references in ascending order.
	 *
	 * @param references the entity references
	 * @return their primary keys
	 */
	@Nonnull
	private static List<Integer> primaryKeysOf(@Nonnull List<EntityReference> references) {
		final List<Integer> primaryKeys = new ArrayList<>(references.size());
		for (final EntityReference reference : references) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		Collections.sort(primaryKeys);
		return primaryKeys;
	}

	/**
	 * Fails when two snapshots differ, listing the differences.
	 *
	 * @param label         names the checkpoint
	 * @param onReference   the snapshot of the reference engine
	 * @param onMaintaining the snapshot of the maintaining engine
	 */
	private static void assertNoDifference(
		@Nonnull String label,
		@Nonnull Snapshot onReference,
		@Nonnull Snapshot onMaintaining
	) {
		final List<String> differences = onReference.differencesFrom(onMaintaining);
		if (!differences.isEmpty()) {
			fail(
				label + ": the engines differ in " + differences.size() + " line(s):\n" +
					String.join("\n", differences.subList(0, Math.min(differences.size(), REPORTED_DIFFERENCES)))
			);
		}
	}

	/**
	 * Blocks until a reopened engine has finished loading the catalog - the engine returns from its constructor while
	 * the catalog is still being activated on the service pool.
	 *
	 * @param evita the reopened engine
	 */
	private static void awaitCatalogLoaded(@Nonnull Evita evita) {
		evita.waitUntilFullyInitialized();
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
		assertNotNull(evita.getCatalogInstance(CATALOG).orElse(null), "The catalog must be loaded again");
	}

	/**
	 * What loading the catalog again found and did in both engines.
	 *
	 * @param reference   the load of the reference engine
	 * @param maintaining the load of the maintaining engine
	 */
	record Reload(@Nonnull EngineLoad reference, @Nonnull EngineLoad maintaining) {
	}

	/**
	 * What loading the catalog again found and did in one engine.
	 *
	 * @param appliedVersion          the catalog version the engine had applied when it was closed or copied
	 * @param persistedVersion        the catalog version the engine had checkpointed when it was closed or copied; a
	 *                                clean close publishes whatever lies between the two
	 * @param imageVersion            the catalog version of the last bootstrap record in the storage the engine loaded,
	 *                                read before the load - the version the load started from
	 * @param loadedVersion           the catalog version once the load finished, the write-ahead log replay included
	 * @param maintainedMutationCount the entity mutations the load executed with the switch on - every one of them a
	 *                                replayed one, since nothing else runs in the engine before it is described
	 */
	record EngineLoad(
		long appliedVersion,
		long persistedVersion,
		long imageVersion,
		long loadedVersion,
		long maintainedMutationCount
	) {
	}

	/**
	 * The copy of a running engine's storage directory and what the engine had done when it was taken.
	 *
	 * @param paths            the directories of the image, its storage holding the copy
	 * @param appliedVersion   the catalog version the engine had applied when it was copied
	 * @param persistedVersion the catalog version the engine had checkpointed when it was copied
	 */
	private record CrashImage(@Nonnull TestPaths paths, long appliedVersion, long persistedVersion) {
	}

	/**
	 * The differences between the two engines at the two checkpoints of one session.
	 *
	 * @param inSession   the differences found inside the session, after its step
	 * @param afterClose  the differences found after the session closed
	 */
	record Divergence(@Nonnull List<String> inSession, @Nonnull List<String> afterClose) {
	}

	/**
	 * One engine reduced to sorted `path -> value` lines.
	 */
	private static final class Snapshot {
		/**
		 * The lines, sorted by path.
		 */
		private final SortedMap<String, String> lines = new TreeMap<>();

		/**
		 * Adds a line, refusing a path that is already taken - two lines under one path would hide one of them.
		 *
		 * @param path  the path
		 * @param value the value
		 */
		void put(@Nonnull String path, @Nonnull String value) {
			final String previous = this.lines.put(path, value);
			if (previous != null) {
				fail("Snapshot path `" + path + "` was described twice");
			}
		}

		/**
		 * Returns the lines.
		 *
		 * @return the lines, sorted by path
		 */
		@Nonnull
		SortedMap<String, String> lines() {
			return Collections.unmodifiableSortedMap(this.lines);
		}

		/**
		 * Lists every path whose value differs from the other snapshot or that only one of them has.
		 *
		 * @param other the snapshot of the other engine
		 * @return the differences, one line each; empty when the snapshots are equal
		 */
		@Nonnull
		List<String> differencesFrom(@Nonnull Snapshot other) {
			final List<String> differences = new ArrayList<>(8);
			for (final Entry<String, String> line : this.lines.entrySet()) {
				final String otherValue = other.lines.get(line.getKey());
				if (otherValue == null) {
					differences.add("only in the reference engine: " + line.getKey() + " = " + line.getValue());
				} else if (!otherValue.equals(line.getValue())) {
					differences.add(
						"differs: " + line.getKey() + "\n   reference:   " + line.getValue() +
							"\n   maintaining: " + otherValue
					);
				}
			}
			for (final Entry<String, String> line : other.lines.entrySet()) {
				if (!this.lines.containsKey(line.getKey())) {
					differences.add("only in the maintaining engine: " + line.getKey() + " = " + line.getValue());
				}
			}
			return differences;
		}
	}
}
