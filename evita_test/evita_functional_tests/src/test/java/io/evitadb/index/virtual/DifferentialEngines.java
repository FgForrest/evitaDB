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
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.utils.ExceptionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * - **After a restart** of both engines.
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
 *   members and attribute indexes, every referenced-type index's advertised partitions per referenced entity and its
 *   filter values with their complete value-to-record mappings, every advertised partition's key, primary key, owners,
 *   languages and attribute indexes, the reduced-index membership's covered and residual partitions and owners, and
 *   the index population per type and scope;
 * - **storage level** - the record count and byte total per storage part type, and the collection header's last index
 *   primary key.
 *
 * # Accepted differences
 *
 * Maintaining the registry legitimately changes a few things, and they are listed here and nowhere else:
 *
 * - the storage part types of the registry itself - {@link #EXCLUDED_STORAGE_PART_TYPES};
 * - the heap size of the referenced-type entity index, which includes the registry - no heap size is ever sampled;
 * - how often a referenced-type entity index is dirty and flushed - only the live record set is compared, never the
 *   data store's waste or file sizes.
 *
 * # What cannot be compared without reflection
 *
 * The per-partition registration cardinality of a referenced-type entity index and the per-value multiplicity of its
 * filter attributes are kept in private structures without a public read. They are compared indirectly - through the
 * advertised partitions and their owners, and through the values and records the filter indexes expose - and directly
 * only as far as the byte totals of their persisted storage parts reach.
 *
 * # Restart
 *
 * The switch is not persisted, so the maintaining engine is switched on again right after a restart. Mutations the
 * reopened catalog replays from its write-ahead log at load are applied with the switch still off.
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
	 * Directories of the reference engine.
	 */
	private final TestPaths referencePaths;
	/**
	 * Directories of the maintaining engine.
	 */
	private final TestPaths maintainingPaths;
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
		this.support = support;
		this.entityTypes = Set.copyOf(entityTypes);
		this.referencePaths = support.createTestPaths(label + "_reference");
		this.maintainingPaths = support.createTestPaths(label + "_maintaining");
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
	 * Closes both engines, starts them again over the same directories, switches the maintaining engine on again and
	 * compares them.
	 */
	void restart() {
		this.reference.close();
		this.maintaining.close();
		this.reference = new Evita(configurationOf(this.referencePaths));
		this.maintaining = new Evita(configurationOf(this.maintainingPaths));
		awaitCatalogLoaded(this.reference);
		awaitCatalogLoaded(this.maintaining);
		switchMaintainingEngineOn();
		assertEqual("after restart");
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
			this.support.cleanupTestPaths(this.referencePaths);
			this.support.cleanupTestPaths(this.maintainingPaths);
		}
	}

	/**
	 * Builds the configuration both engines share, differing only in their directories.
	 *
	 * @param paths the directories of one engine
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths paths) {
		return this.support.newTestEvitaConfigurationBuilder(paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * Switches the maintaining engine on for every entity type the history uses, whether its collection exists yet or
	 * not.
	 */
	private void switchMaintainingEngineOn() {
		final Catalog catalog = (Catalog) this.maintaining.getCatalogInstance(CATALOG).orElseThrow();
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
					buckets.append(describeValue(bucket.getValue()))
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
				snapshot.put(
					path + "/sort/" + key,
					Arrays.toString(sortIndex.getSortedRecords()) + " by " +
						Arrays.stream(sortIndex.getSortedRecordValues())
							.map(DifferentialEngines::describeValue)
							.toList()
				);
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.UNIQUE, key -> {
			final UniqueIndex uniqueIndex = index.getUniqueIndex(key);
			if (uniqueIndex != null) {
				snapshot.put(
					path + "/unique/" + key,
					toString(uniqueIndex.getRecordIds()) + " of " + uniqueIndex.getDistinctValueCount() + " values"
				);
			}
		});
		index.forEachAttributeIndexKey(AttributeIndexType.CHAIN, key -> {
			final ChainIndex chainIndex = index.getChainIndex(key);
			if (chainIndex != null) {
				snapshot.put(path + "/chain/" + key, Arrays.toString(chainIndex.getUnorderedLookup().getArray()));
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
	 * Renders a value of an attribute index so that two equal values render the same in both engines - arrays by
	 * their elements rather than by their identity.
	 *
	 * @param value the value
	 * @return its rendering
	 */
	@Nonnull
	private static String describeValue(@Nullable Serializable value) {
		if (value instanceof final Object[] array) {
			return Arrays.deepToString(array);
		}
		return String.valueOf(value);
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
