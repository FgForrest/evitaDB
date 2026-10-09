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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.index.virtual.ReadRouteActivation.LineConsumption;
import io.evitadb.index.virtual.ReadRouteActivation.ReadRoute;
import io.evitadb.index.virtual.TransitionHistory.InjectedTransitionFailure;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.randomWithSeed;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
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
 *   filterable attributes, the reference histograms, and per reference a line of every query route of the virtual
 *   reading its schema admits (see {@link #describeQueries}): `referenceHaving` of targets, negations, groups,
 *   locales and attribute bounds, the bidirectional rewrite, `referenceContent` filtered and chained, the hierarchy
 *   filters and statistics, the `pickFirst` orderings through both routes and the block-by-block orderings. The
 *   maintaining engine runs every line with its collections read virtually and counts the virtual rows each line
 *   reads (see {@link #getVirtualRowsReadPerLine()});
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
 * # The registry itself
 *
 * At every checkpoint the registry of single-owner partitions of every collection of the maintaining engine is checked
 * against its partitions by {@link VirtualReferencePartitionOracle}, exactly: every single-owner partition must have
 * its row, inside the open session too, because a partition left with one owner of two is demoted by the very mutation
 * that left it so. It is checked against the entity bodies by {@link VirtualReferencePartitionRawRowOracle} too, so a
 * defect of the indexing path both engines and the mirror share - which leaves the partitions, the registry and the
 * other engine in agreement - is found as well. Both engines report every transition of their partitions and the
 * outcome of every root mutation to a history of their own (see {@link TransitionHistory}), installed before the
 * catalog is defined and before every load, which tests read to prove what a history exercised. Any further check can
 * be added by {@link #addMaintainingEngineCheck(MaintainingEngineCheck)}.
 *
 * Failures can be injected right after a transition, in both engines at once (see
 * {@link #failingStepAfter(String, TransitionStage, String, int, Consumer)}): the engine that maintains nothing passes
 * the very same transitions, so both fail at the same point of the same mutation and must end in the same state.
 *
 * # Shapes left out
 *
 * A few query shapes fail with the switch off as well, so a line built on them would compare two failures. They are
 * left out, and nothing virtual is lost by it - each is a pre-existing defect of the object reading:
 *
 * - the block-by-block ordering by the referenced primary key over a reference allowing duplicates (a stale read of a
 *   scratch buffer in the merge walk of `SortedRecordsSupplierFactory`);
 * - `attributeSetExact` in a block-by-block ordering over several partitions, or over a partition holding no value of
 *   the attribute - the line narrows the owners to those of one target whose row carries a value.
 *
 * Archiving the second owner of a shared partition carrying a `ReferencedEntityPredecessor` fails with the switch off
 * as well - every owner of the partition writes the same chain element, and the first removal drops it - so a history
 * must not do it.
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
public final class DifferentialEngines implements AutoCloseable {
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
	// TODO JNO - remove at the end of #1615
	/**
	 * Storage part types of the entities themselves - the only ones compared at the storage level when the maintaining
	 * engine writes its single-owner partitions virtually, because the twin persists an index object for each of them.
	 */
	static final Set<String> ENTITY_STORAGE_PART_TYPES = Set.of(
		EntityBodyStoragePart.class.getSimpleName(),
		AttributesStoragePart.class.getSimpleName(),
		AssociatedDataStoragePart.class.getSimpleName(),
		PricesStoragePart.class.getSimpleName(),
		ReferencesStoragePart.class.getSimpleName()
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
	 * The seed of the random target order of the `pickFirst` lines of the prefetch route.
	 */
	private static final long SEED = 42L;
	/**
	 * The line fetching every entity body of a scope.
	 */
	static final String LINE_ENTITY_BODIES = "entityBodies";
	/**
	 * The lines of `referenceHaving` asking for one target.
	 */
	static final String LINE_REFERENCE_HAVING_TARGET = "referenceHaving/entityPrimaryKeyInSet";
	/**
	 * The line of `referenceHaving` negating every other target.
	 */
	static final String LINE_REFERENCE_HAVING_NOT = "referenceHaving/not";
	/**
	 * The line of `referenceHaving` asking for every other group.
	 */
	static final String LINE_REFERENCE_HAVING_GROUP = "referenceHaving/groupHaving";
	/**
	 * The lines of `referenceHaving` asking for one owner locale.
	 */
	static final String LINE_REFERENCE_HAVING_LOCALE = "referenceHaving/entityLocaleEquals";
	/**
	 * The lines of `referenceHaving` bounding one attribute, every collection read virtually.
	 */
	static final String LINE_REFERENCE_HAVING_ATTRIBUTE = "referenceHaving/attribute";
	/**
	 * The lines of `referenceHaving` bounding one attribute, only the counterpart's collection read virtually.
	 */
	static final String LINE_BIDIRECTIONAL = "bidirectional";
	/**
	 * The line of `referenceContent(filterBy)` asking for every other target.
	 */
	static final String LINE_REFERENCE_CONTENT_FILTER = "referenceContent/filterBy";
	/**
	 * The lines of `referenceContent(orderBy)` by one chained attribute.
	 */
	static final String LINE_REFERENCE_CONTENT_CHAIN = "referenceContent/orderByChain";
	/**
	 * The lines of `hierarchyWithin` of one target.
	 */
	static final String LINE_HIERARCHY_WITHIN = "hierarchyWithin";
	/**
	 * The line of `hierarchyWithinRoot`.
	 */
	static final String LINE_HIERARCHY_WITHIN_ROOT = "hierarchyWithinRoot";
	/**
	 * The line of the `hierarchyOfReference` statistics.
	 */
	static final String LINE_HIERARCHY_STATISTICS = "hierarchyStatistics";
	/**
	 * The lines of the `pickFirst` ordering by one attribute through the index route.
	 */
	static final String LINE_PICK_FIRST_INDEX = "pickFirst/attribute/index";
	/**
	 * The lines of the `pickFirst` orderings through the prefetch route.
	 */
	static final String LINE_PICK_FIRST_PREFETCH = "pickFirst/prefetch";
	/**
	 * The line of the `pickFirst` ordering by the referenced primary key through the index route.
	 */
	static final String LINE_PICK_FIRST_REFERENCED_KEY = "pickFirst/referencedPrimaryKey/index";
	/**
	 * The lines of the block-by-block ordering by one sortable attribute.
	 */
	static final String LINE_TRAVERSE_ATTRIBUTE = "traverse/attribute";
	/**
	 * The lines of the block-by-block ordering by one chained attribute.
	 */
	static final String LINE_TRAVERSE_CHAIN = "traverse/chain";
	/**
	 * The line of the block-by-block ordering by the referenced primary key.
	 */
	static final String LINE_TRAVERSE_REFERENCED_KEY = "traverse/referencedPrimaryKey";
	/**
	 * The lines of the block-by-block ordering by `attributeSetExact` of one attribute.
	 */
	static final String LINE_TRAVERSE_ATTRIBUTE_SET_EXACT = "traverse/attributeSetExact";
	/**
	 * The lines of the block-by-block ordering by one sortable attribute narrowed by `hierarchyWithinRoot`, whose
	 * planning set comes from the hierarchy option of index selection.
	 */
	static final String LINE_HIERARCHY_TRAVERSE = "hierarchyWithinRoot/traverse/attribute";
	/**
	 * The line of the facet summary.
	 */
	static final String LINE_FACET_SUMMARY = "facetSummary";
	/**
	 * The line of the reference histograms.
	 */
	static final String LINE_REFERENCE_HISTOGRAMS = "referenceHistograms";
	/**
	 * The line of the attribute histograms.
	 */
	static final String LINE_ATTRIBUTE_HISTOGRAMS = "attributeHistograms";

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
	// TODO JNO - remove at the end of #1615
	/**
	 * TRUE when the maintaining engine represents its single-owner partitions virtually and writes them virtually -
	 * as registry rows without index objects - while the reference engine is their twin: index objects and a registry
	 * maintained next to them. See {@link #writtenVirtually(EvitaTestSupport, String, Set, TransactionOptions)}.
	 */
	private final boolean writtenVirtually;
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
	private final List<MaintainingEngineCheck> maintainingEngineChecks = new ArrayList<>(2);
	/**
	 * The transitions the reference engine passed. It maintains nothing, but it passes the same transitions, and a
	 * failure injected after one of them must fail it at the same point as the maintaining engine.
	 */
	private final TransitionHistory referenceHistory = new TransitionHistory();
	/**
	 * The transitions the maintaining engine passed.
	 */
	private final TransitionHistory maintainingHistory = new TransitionHistory();
	/**
	 * The virtual rows the query lines of the maintaining engine read, summed per line name over every checkpoint -
	 * see {@link #getVirtualRowsReadPerLine()}.
	 */
	private final SortedMap<String, Long> virtualRowsPerLine = new TreeMap<>();
	/**
	 * What the consuming stages of the query lines of the maintaining engine answered virtually and the routes the lines
	 * declared, per line name over every checkpoint - see {@link #getConsumptionPerLine()}.
	 */
	private final SortedMap<String, LineConsumption> consumptionPerLine = new TreeMap<>();
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
		this(support, label, entityTypes, transactionOptions, false);
	}

	/**
	 * Starts both engines, each in its own directories with an identical configuration using the given transaction
	 * settings, in the mode the flag selects.
	 *
	 * @param support            the test owning the engines
	 * @param label              a label naming the engines' directories
	 * @param entityTypes        the entity types the maintaining engine maintains
	 * @param transactionOptions the transaction settings of both engines; `null` for the defaults of the test
	 *                           configuration
	 * @param writtenVirtually   TRUE for the mode of {@link #writtenVirtually}
	 */
	private DifferentialEngines(
		@Nonnull EvitaTestSupport support,
		@Nonnull String label,
		@Nonnull Set<String> entityTypes,
		@Nullable TransactionOptions transactionOptions,
		boolean writtenVirtually
	) {
		this.support = support;
		this.entityTypes = Set.copyOf(entityTypes);
		this.transactionOptions = transactionOptions;
		this.writtenVirtually = writtenVirtually;
		this.referencePaths = allocatePaths(label + "_reference");
		this.maintainingPaths = allocatePaths(label + "_maintaining");
		this.reference = new Evita(configurationOf(this.referencePaths));
		this.maintaining = new Evita(configurationOf(this.maintainingPaths));
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Starts both engines for the comparison of a catalog writing its single-owner partitions virtually with its twin.
	 * The maintaining engine hosts a catalog representing its single-owner partitions virtually with the virtual
	 * writing on - its single-owner partitions are registry rows without index objects. The reference engine hosts a
	 * catalog with index objects that maintains its registry next to them - the twin a catalog writing its partitions
	 * as objects is. Everything the engines can be asked must still come out the same, with these differences in what
	 * is compared:
	 *
	 * - a partition with a registry row is described by the row and the registry is described in full in both engines,
	 *   so the registry of the maintaining engine is compared with the one the twin keeps next to its real partitions,
	 *   which {@link VirtualReferencePartitionOracle} checks against them; an index object of a partition with a row
	 *   is not described;
	 * - the reference engine is checked by {@link VirtualReferencePartitionOracle} and the maintaining engine, whose
	 *   partitions are not objects, is not; both are checked by {@link VirtualReferencePartitionRawRowOracle};
	 * - the maintaining engine reads every query line virtually, the reference engine none;
	 * - the storage level compares the storage parts of the entities and the last index primary key only, and the
	 *   enrollment leaves out the partitions of the references whose partitions may be virtual.
	 *
	 * @param support            the test owning the engines
	 * @param label              a label naming the engines' directories
	 * @param entityTypes        the entity types of the history - maintained by both engines
	 * @param transactionOptions the transaction settings of both engines; `null` for the defaults of the test
	 *                           configuration
	 * @return the engines
	 */
	@Nonnull
	static DifferentialEngines writtenVirtually(
		@Nonnull EvitaTestSupport support,
		@Nonnull String label,
		@Nonnull Set<String> entityTypes,
		@Nullable TransactionOptions transactionOptions
	) {
		return new DifferentialEngines(support, label, entityTypes, transactionOptions, true);
	}

	/**
	 * Defines the catalog in both engines, switches the maintaining engine on, runs the setup as one step and, for
	 * `ALIVE`, takes both catalogs live.
	 *
	 * @param state the catalog state the engines should end in
	 * @param setup the schema and the initial data
	 */
	void prepare(@Nonnull CatalogState state, @Nonnull Consumer<EvitaSessionContract> setup) {
		// TODO JNO - remove at the end of #1615
		this.reference.createCatalogWithVirtualReferencePartitions(CATALOG, false);
		this.reference.defineCatalog(CATALOG).updateViaNewSession(this.reference);
		// TODO JNO - remove at the end of #1615
		this.maintaining.createCatalogWithVirtualReferencePartitions(CATALOG, this.writtenVirtually);
		this.maintaining.defineCatalog(CATALOG).updateViaNewSession(this.maintaining);
		catalogOf(this.reference).getVirtualReferencePartitionMaintenance().setProbe(this.referenceHistory);
		catalogOf(this.maintaining).getVirtualReferencePartitionMaintenance().setProbe(this.maintainingHistory);
		switchReferenceEngineOff();
		switchMaintainingEngineOn();
		step("setup", setup);
		if (state == CatalogState.ALIVE) {
			this.reference.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			this.maintaining.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			assertEqual("after going live");
		}
	}

	/**
	 * Registers a check run on every collection of the maintaining engine at every checkpoint, next to the parity
	 * oracle of the registry, which runs there always.
	 *
	 * @param check receives the checkpoint label and the collection
	 */
	void addMaintainingEngineCheck(@Nonnull MaintainingEngineCheck check) {
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
	 * Applies a step to both engines, each in a session of its own, failing it in both right after the first
	 * transition of the given kind of the given entity type it passes - see {@link TransitionHistory}. The failure is
	 * caught inside the session, which then goes on and closes normally. The engines are compared right after the
	 * caught failure and after the session closes, and both must have failed at the transition.
	 *
	 * @param label      names the step in failure messages
	 * @param stage      the transition to fail after
	 * @param entityType the entity type owning the reference whose partition passes the transition
	 * @param skip       how many matching transitions to let pass before failing after the next one
	 * @param step       the mutation to fail
	 */
	void failingStepAfter(
		@Nonnull String label,
		@Nonnull TransitionStage stage,
		@Nonnull String entityType,
		int skip,
		@Nonnull Consumer<EvitaSessionContract> step
	) {
		final int referenceInjected = this.referenceHistory.getInjectedFailures();
		final int maintainingInjected = this.maintainingHistory.getInjectedFailures();
		this.referenceHistory.failAfter(stage, entityType, skip);
		this.maintainingHistory.failAfter(stage, entityType, skip);
		try {
			failingStep(label, step, InjectedTransitionFailure.class);
		} finally {
			this.referenceHistory.disarm();
			this.maintainingHistory.disarm();
		}
		assertEquals(
			referenceInjected + 1, this.referenceHistory.getInjectedFailures(),
			label + ": the reference engine must have failed after " + stage + " of `" + entityType + "`"
		);
		assertEquals(
			maintainingInjected + 1, this.maintainingHistory.getInjectedFailures(),
			label + ": the maintaining engine must have failed after " + stage + " of `" + entityType + "`"
		);
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

	// TODO JNO - remove at the end of #1615
	/**
	 * Reduces the indexes of one collection of a catalog writing its single-owner partitions virtually to the
	 * index-level lines of the comparison - in every scope, registry included (see {@link #describeIndexes}) - without
	 * running a query. Read inside a session, the lines describe the state of the session's transaction.
	 *
	 * @param collection the collection
	 * @return the lines, sorted by path
	 */
	@Nonnull
	static SortedMap<String, String> describeIndexesOf(@Nonnull EntityCollection collection) {
		final Snapshot snapshot = new Snapshot();
		for (final Scope scope : Scope.values()) {
			describeIndexes(snapshot, collection, collection.getSchema(), scope, true);
		}
		return snapshot.lines();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Reduces the committed state of any catalog of an engine - not only the one the engines host - to the query-level
	 * lines of the comparison (see {@link #describeQueries}), every collection in every scope. Each line runs with the
	 * read switch of the catalog itself: a catalog representing its single-owner partitions virtually reads them
	 * virtually, any other one reads its index objects. Two catalogs holding the same data must have equal lines,
	 * whatever their representations.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the lines, sorted by path
	 */
	@Nonnull
	public static SortedMap<String, String> describeQueriesOf(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			(Function<EvitaSessionContract, SortedMap<String, String>>) session -> {
				final Snapshot snapshot = new Snapshot();
				for (final String entityType : new TreeSet<>(session.getAllEntityTypes())) {
					final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
					for (final Scope scope : Scope.values()) {
						describeQueries(snapshot, session, schema, scope, ReadRouteActivation.OFF);
					}
				}
				return snapshot.lines();
			}
		);
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

	/**
	 * Returns how many virtual rows the query lines of the maintaining engine read, summed per line name (the `LINE_*`
	 * constants) over every checkpoint so far. A line that never ran is absent; a line that ran without reading a
	 * virtual row maps to zero.
	 *
	 * @return the virtual rows read per line name
	 */
	@Nonnull
	SortedMap<String, Long> getVirtualRowsReadPerLine() {
		return Collections.unmodifiableSortedMap(this.virtualRowsPerLine);
	}

	/**
	 * Returns what the consuming stages of the query lines of the maintaining engine answered virtually and which routes
	 * the lines declared, per line name (the `LINE_*` constants) over every checkpoint so far - see
	 * {@link LineConsumption}. A line that never ran is absent.
	 *
	 * @return the consumption per line name
	 */
	@Nonnull
	SortedMap<String, LineConsumption> getConsumptionPerLine() {
		return Collections.unmodifiableSortedMap(this.consumptionPerLine);
	}

	/**
	 * Returns the transitions the reference engine passed.
	 *
	 * @return the history of the reference engine
	 */
	@Nonnull
	TransitionHistory getReferenceHistory() {
		return this.referenceHistory;
	}

	/**
	 * Returns the transitions the maintaining engine passed.
	 *
	 * @return the history of the maintaining engine
	 */
	@Nonnull
	TransitionHistory getMaintainingHistory() {
		return this.maintainingHistory;
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
		this.reference = new Evita(configurationOf(this.referencePaths), false);
		// maintaining nothing - or, as the twin of a catalog writing virtually, its registry next to its objects - and
		// observed from the load on like the maintaining engine
		this.reference.maintainVirtualReferencePartitionsOnLoad(
			CATALOG, this.writtenVirtually ? this.entityTypes : Set.of(), this.referenceHistory
		);
		this.reference.scheduleInitialCatalogLoading();
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
		this.maintaining.maintainVirtualReferencePartitionsOnLoad(CATALOG, this.entityTypes, this.maintainingHistory);
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
	 * Switches the reference engine off for every entity type the history uses. A no-op unless the JVM runs in the
	 * forced mode of the virtual reference partitions, where a brand-new catalog is maintained and read virtually by
	 * default (see {@link io.evitadb.core.catalog.VirtualReferencePartitionMaintenance}) and the reference engine would
	 * otherwise become a second maintaining engine.
	 */
	private void switchReferenceEngineOff() {
		final Catalog catalog = catalogOf(this.reference);
		for (final String entityType : this.entityTypes) {
			catalog.getVirtualReferencePartitionMaintenance().setReadVirtually(entityType, false);
			// the twin of a catalog writing virtually maintains its registry next to its objects
			catalog.getVirtualReferencePartitionMaintenance().setMaintained(entityType, this.writtenVirtually);
		}
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
		final TransitionHistory history = maintained ? this.maintainingHistory : this.referenceHistory;
		final boolean wroteInPlace = catalogOf(evita).getCatalogState() == CatalogState.WARMING_UP;
		final List<Snapshot> snapshots = new ArrayList<>(steps.size());
		boolean rolledBack = rollback;
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
		} catch (RuntimeException ex) {
			// a session that fails to close is rolled back, whatever it wrote
			rolledBack = true;
			throw ex;
		} finally {
			history.sessionClosed(wroteInPlace, rolledBack);
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
			final boolean expectedMaintained = maintained || this.writtenVirtually;
			assertEquals(
				expectedMaintained, collection.isVirtualReferencePartitionsMaintained(),
				label + ": the switch of `" + entityType + "` must be " + (expectedMaintained ? "on" : "off") +
					" in the " + (maintained ? "maintaining" : "reference") + " engine"
			);
			// TODO JNO - remove at the end of #1615
			assertEquals(
				maintained && this.writtenVirtually,
				catalog.getVirtualReferencePartitionMaintenance().isWrittenVirtually(),
				label + ": the virtual writing must be " + (maintained && this.writtenVirtually ? "on" : "off") +
					" in the " + (maintained ? "maintaining" : "reference") + " engine"
			);
			if (maintained) {
				// the registered checks first - they name the defect they look for, the oracles only its consequence
				for (final MaintainingEngineCheck check : this.maintainingEngineChecks) {
					check.check(label, collection);
				}
				// the partitions of a catalog writing virtually are no index objects to check the registry against
				if (!this.writtenVirtually) {
					VirtualReferencePartitionOracle.assertConsistent(label, collection);
				}
				VirtualReferencePartitionRawRowOracle.assertConsistent(label, session, collection);
			} else if (this.writtenVirtually) {
				// the twin's registry is checked against its real partitions, and the snapshot then compares it with
				// the registry of the catalog writing virtually
				VirtualReferencePartitionOracle.assertConsistent(label, collection);
				VirtualReferencePartitionRawRowOracle.assertConsistent(label, session, collection);
			}
			final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
			// the maintaining engine reads virtually on the query lines whose routes have landed, and only there - a
			// catalog writing virtually reads every line virtually, its switch cannot be turned off
			final ReadRouteActivation activation;
			if (!maintained) {
				activation = ReadRouteActivation.OFF;
			} else {
				activation = new ReadRouteActivation(
					catalog.getVirtualReferencePartitionMaintenance(),
					this.writtenVirtually ? Set.of() : this.entityTypes,
					ReadRouteActivation.LANDED_ROUTES, this.virtualRowsPerLine, this.consumptionPerLine
				);
			}
			for (final Scope scope : Scope.values()) {
				describeQueries(snapshot, session, schema, scope, activation);
				describeIndexes(snapshot, collection, schema, scope, this.writtenVirtually);
			}
			if (committed) {
				describeStorage(snapshot, evita, entityType, this.writtenVirtually);
			} else {
				describeEnrollment(snapshot, collection, this.writtenVirtually);
			}
		}
		return snapshot;
	}

	/**
	 * Adds the query-level lines of one collection and scope.
	 *
	 * Each query line runs with the read switch the activation gives its routes - see {@link ReadRouteActivation} - and
	 * counts the virtual rows it reads under its line name (the `LINE_*` constants). Besides the entity bodies, the
	 * facet summary and the histograms, every reference indexed in the scope gets the lines of every query route of the
	 * virtual reading its schema admits:
	 *
	 * - `referenceHaving` of every target, of the negation of every other target, of every other group
	 *   (`groupHaving`), of every owner locale (`entityLocaleEquals`) and of an upper bound of every comparable
	 *   filterable attribute - the last also answered by the bidirectional rewrite when the reference has a
	 *   counterpart, on a line that reads only the counterpart's collection virtually;
	 * - `referenceContent(filterBy)` of every other target and `referenceContent(orderBy)` of every chained attribute;
	 * - `hierarchyWithinRoot`, `hierarchyWithin` of every target and the `hierarchyOfReference` statistics of a
	 *   reference to a hierarchical entity;
	 * - the `pickFirst` orderings of a reference to a non-hierarchical entity - by every sortable attribute and by the
	 *   referenced primary key, through the index route and through the prefetch route;
	 * - the block-by-block orderings - `traverseByEntityProperty` by every sortable and every chained attribute, by
	 *   `attributeSetExact` of a filterable attribute and by the referenced primary key.
	 *
	 * Two block-by-block shapes are left out for a reference allowing duplicates, because pre-existing defects make
	 * them fail with the switch off as well (see the class documentation): the referenced primary key and
	 * `attributeSetExact`.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param session    the session to query in
	 * @param schema     the schema of the collection
	 * @param scope      the scope to query
	 * @param activation decides the read switch of each query line
	 */
	private static void describeQueries(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope,
		@Nonnull ReadRouteActivation activation
	) {
		final String entityType = schema.getName();
		final String prefix = "query/" + entityType + "/" + scope + "/";
		final EvitaResponse<SealedEntity> bodies = activation.run(
			LINE_ENTITY_BODIES,
			EnumSet.of(ReadRoute.ENTITY_BODY),
			() -> session.query(
				query(collection(entityType), filterBy(scope(scope)), require(page(1, PAGE_SIZE), entityFetchAll())),
				SealedEntity.class
			)
		);
		final ReferenceSample sample = new ReferenceSample();
		for (final SealedEntity entity : bodies.getRecordData()) {
			snapshot.put(prefix + "entity/" + entity.getPrimaryKeyOrThrowException(), entity.toString());
			sample.addOwner(entity);
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
			final Set<Integer> targets = sample.getTargets(referenceName);
			for (final Integer target : targets) {
				final EvitaResponse<EntityReference> matching = activation.run(
					LINE_REFERENCE_HAVING_TARGET,
					EnumSet.of(
						ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES,
						ReadRoute.REFERENCED_ENTITY_IN_BODY, ReadRoute.PARTITION_TO_TARGET
					),
					() -> queryOwners(
						session, entityType, scope, referenceHaving(referenceName, entityPrimaryKeyInSet(target))
					)
				);
				snapshot.put(
					prefix + "referenceHaving/" + referenceName + "/" + target,
					String.valueOf(primaryKeysOf(matching.getRecordData()))
				);
			}
			describeNegatedReferenceHaving(
				snapshot, session, entityType, scope, referenceName, targets, activation, prefix
			);
			describeFilteredReferenceContent(
				snapshot, session, entityType, scope, referenceName, targets, activation, prefix
			);
			describeGroupHaving(snapshot, session, entityType, scope, referenceSchema, sample, activation, prefix);
			describeLocaleHaving(snapshot, session, entityType, scope, referenceName, sample, activation, prefix);
			describeAttributeHaving(
				snapshot, session, schema, scope, referenceSchema, sample, activation, prefix
			);
			final boolean hierarchical = isReferenceToHierarchy(session, referenceSchema, null);
			final boolean hierarchyIndexed = isReferenceToHierarchy(session, referenceSchema, scope);
			if (hierarchyIndexed) {
				describeHierarchy(snapshot, session, entityType, scope, referenceName, targets, activation, prefix);
			}
			describeOrderings(
				snapshot, session, entityType, scope, referenceSchema, hierarchical, hierarchyIndexed, sample,
				activation, prefix
			);
		}
		if (faceted) {
			describeExtraResults(
				snapshot, prefix + "facetSummary",
				activation.run(
					LINE_FACET_SUMMARY,
					EnumSet.of(ReadRoute.FACETS_AND_HISTOGRAMS),
					() -> session.query(
						query(collection(entityType), filterBy(scope(scope)), require(page(1, 1), facetSummary())),
						EntityReference.class
					)
				)
			);
		}
		if (!histogramNames.isEmpty()) {
			describeExtraResults(
				snapshot, prefix + "referenceHistograms",
				activation.run(
					LINE_REFERENCE_HISTOGRAMS,
					EnumSet.of(ReadRoute.FACETS_AND_HISTOGRAMS),
					() -> session.query(
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
				activation.run(
					LINE_ATTRIBUTE_HISTOGRAMS,
					EnumSet.of(ReadRoute.FACETS_AND_HISTOGRAMS),
					() -> session.query(
						query(
							collection(entityType),
							filterBy(scope(scope)),
							require(page(1, 1), attributeHistogram(10, numericAttributes))
						),
						EntityReference.class
					)
				)
			);
		}
	}

	/**
	 * Adds one line with the owners `referenceHaving` matches when its body negates every other target of the
	 * reference - a negation settled per row, which a row read virtually has to answer exactly: an owner holding a
	 * negated row and another one matches, an owner holding negated rows only does not.
	 *
	 * @param snapshot      the snapshot to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param activation    decides the read switch of the line
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeNegatedReferenceHaving(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		if (targets.isEmpty()) {
			return;
		}
		final Integer[] everyOther = everyOther(targets);
		final EvitaResponse<EntityReference> matching = activation.run(
			LINE_REFERENCE_HAVING_NOT,
			EnumSet.of(
				ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES,
				ReadRoute.REFERENCED_ENTITY_IN_BODY, ReadRoute.NEGATION_IN_BODY
			),
			() -> queryOwners(
				session, entityType, scope, referenceHaving(referenceName, not(entityPrimaryKeyInSet(everyOther)))
			)
		);
		snapshot.put(
			prefix + "referenceHaving/" + referenceName + "/not",
			String.valueOf(primaryKeysOf(matching.getRecordData()))
		);
	}

	/**
	 * Adds one line per entity of the collection in the scope with the references `referenceContent(filterBy)` keeps
	 * when it asks for every other target of the reference - so the answer holds kept and dropped rows of the same
	 * owners, and a row read virtually has to be united with the other rows of its key exactly as an index object's
	 * owners are.
	 *
	 * @param snapshot      the snapshot to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param activation    decides the read switch of the line
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeFilteredReferenceContent(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		if (targets.isEmpty()) {
			return;
		}
		final Integer[] everyOther = everyOther(targets);
		final EvitaResponse<SealedEntity> filtered = activation.run(
			LINE_REFERENCE_CONTENT_FILTER,
			EnumSet.of(ReadRoute.REFERENCE_CONTENT_FILTER, ReadRoute.REFERENCED_ENTITY_IN_BODY),
			() -> session.query(
				query(
					collection(entityType),
					filterBy(scope(scope)),
					require(
						page(1, PAGE_SIZE),
						entityFetch(
							referenceContentWithAttributes(referenceName, filterBy(entityPrimaryKeyInSet(everyOther)))
						)
					)
				),
				SealedEntity.class
			)
		);
		putReferences(snapshot, prefix + "referenceContent/" + referenceName + "/", referenceName, filtered, false);
	}

	/**
	 * Adds one line with the owners `referenceHaving` matches when its body asks for every other group the rows of
	 * the reference belong to - a group leaf, which a row read virtually answers from the group of its own row only.
	 * Only a group entity managed by evitaDB can be asked for.
	 *
	 * @param snapshot        the snapshot to add to
	 * @param session         the session to query in
	 * @param entityType      the collection
	 * @param scope           the scope to query
	 * @param referenceSchema the reference
	 * @param sample          what the entity bodies of the collection hold
	 * @param activation      decides the read switch of the line
	 * @param prefix          the path prefix of the lines
	 */
	private static void describeGroupHaving(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSample sample,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		final String referenceName = referenceSchema.getName();
		final Set<Integer> groups = sample.getGroups(referenceName);
		if (
			referenceSchema.getReferencedGroupType() == null || !referenceSchema.isReferencedGroupTypeManaged() ||
				groups.isEmpty()
		) {
			return;
		}
		final Integer[] everyOther = everyOther(groups);
		final EvitaResponse<EntityReference> matching = activation.run(
			LINE_REFERENCE_HAVING_GROUP,
			EnumSet.of(
				ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES, ReadRoute.GROUP_IN_BODY
			),
			() -> queryOwners(
				session, entityType, scope,
				referenceHaving(referenceName, groupHaving(entityPrimaryKeyInSet(everyOther)))
			)
		);
		snapshot.put(
			prefix + "referenceHaving/" + referenceName + "/groupHaving",
			String.valueOf(primaryKeysOf(matching.getRecordData()))
		);
	}

	/**
	 * Adds one line per locale the owners of the collection hold with the owners `referenceHaving` matches when its
	 * body asks for that locale - a locale leaf, which a row read virtually answers from the locales of its owner.
	 *
	 * @param snapshot      the snapshot to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param sample        what the entity bodies of the collection hold
	 * @param activation    decides the read switch of the line
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeLocaleHaving(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull ReferenceSample sample,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		if (sample.getTargets(referenceName).isEmpty()) {
			return;
		}
		for (final Locale locale : sample.getLocales()) {
			final EvitaResponse<EntityReference> matching = activation.run(
				LINE_REFERENCE_HAVING_LOCALE,
				EnumSet.of(
					ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES, ReadRoute.LOCALE_IN_BODY
				),
				() -> queryOwners(
					session, entityType, scope, referenceHaving(referenceName, entityLocaleEquals(locale))
				)
			);
			snapshot.put(
				prefix + "referenceHaving/" + referenceName + "/locale/" + locale.toLanguageTag(),
				String.valueOf(primaryKeysOf(matching.getRecordData()))
			);
		}
	}

	/**
	 * Adds, for every filterable non-localized numeric or string attribute of the reference the rows of the
	 * collection carry, one line with the owners `referenceHaving` matches when its body bounds the attribute by the
	 * median of its values - a row-exact body - and, when the reference has a counterpart, one more line answering the
	 * very same query with only the counterpart's collection read virtually, so whatever it reads virtually is read by
	 * the bidirectional rewrite.
	 *
	 * @param snapshot        the snapshot to add to
	 * @param session         the session to query in
	 * @param schema          the schema of the collection
	 * @param scope           the scope to query
	 * @param referenceSchema the reference
	 * @param sample          what the entity bodies of the collection hold
	 * @param activation      decides the read switch of the lines
	 * @param prefix          the path prefix of the lines
	 */
	private static void describeAttributeHaving(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSample sample,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		final String entityType = schema.getName();
		final String referenceName = referenceSchema.getName();
		final boolean counterpart = hasCounterpart(session, schema, referenceSchema);
		for (final AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
			final String attributeName = attributeSchema.getName();
			final List<Serializable> values = sample.getAttributeValues(referenceName, attributeName);
			if (
				!attributeSchema.isFilterableInScope(scope) || attributeSchema.isLocalized() ||
					!isComparablePlainType(attributeSchema.getPlainType()) || values.isEmpty()
			) {
				continue;
			}
			final Serializable median = values.get(values.size() / 2);
			final FilterConstraint body = referenceHaving(
				referenceName, attributeLessThanEquals(attributeName, median)
			);
			final EvitaResponse<EntityReference> matching = activation.run(
				LINE_REFERENCE_HAVING_ATTRIBUTE,
				EnumSet.of(ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES),
				() -> queryOwners(session, entityType, scope, body)
			);
			snapshot.put(
				prefix + "referenceHaving/" + referenceName + "/attribute/" + attributeName,
				String.valueOf(primaryKeysOf(matching.getRecordData()))
			);
			if (counterpart) {
				final EvitaResponse<EntityReference> rewritten = activation.run(
					LINE_BIDIRECTIONAL,
					EnumSet.of(ReadRoute.BIDIRECTIONAL_REWRITE),
					Set.of(referenceSchema.getReferencedEntityType()),
					() -> queryOwners(session, entityType, scope, body)
				);
				snapshot.put(
					prefix + "bidirectional/" + referenceName + "/" + attributeName,
					String.valueOf(primaryKeysOf(rewritten.getRecordData()))
				);
			}
		}
	}

	/**
	 * Adds the hierarchy lines of a reference to a hierarchical entity: the owners `hierarchyWithinRoot` matches, the
	 * owners `hierarchyWithin` matches for every target, and the `hierarchyOfReference` statistics from the root.
	 *
	 * @param snapshot      the snapshot to add to
	 * @param session       the session to query in
	 * @param entityType    the collection
	 * @param scope         the scope to query
	 * @param referenceName the reference
	 * @param targets       the targets the entities of the collection reference, ascending
	 * @param activation    decides the read switch of the lines
	 * @param prefix        the path prefix of the lines
	 */
	private static void describeHierarchy(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Set<Integer> targets,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		final EvitaResponse<EntityReference> withinRoot = activation.run(
			LINE_HIERARCHY_WITHIN_ROOT,
			EnumSet.of(ReadRoute.HIERARCHY_WITHIN),
			() -> queryOwners(session, entityType, scope, hierarchyWithinRoot(referenceName))
		);
		snapshot.put(
			prefix + "hierarchyWithinRoot/" + referenceName, String.valueOf(primaryKeysOf(withinRoot.getRecordData()))
		);
		for (final Integer target : targets) {
			final EvitaResponse<EntityReference> within = activation.run(
				LINE_HIERARCHY_WITHIN,
				EnumSet.of(ReadRoute.HIERARCHY_WITHIN),
				() -> queryOwners(
					session, entityType, scope, hierarchyWithin(referenceName, entityPrimaryKeyInSet(target))
				)
			);
			snapshot.put(
				prefix + "hierarchyWithin/" + referenceName + "/" + target,
				String.valueOf(primaryKeysOf(within.getRecordData()))
			);
		}
		describeExtraResults(
			snapshot, prefix + "hierarchyStatistics/" + referenceName,
			activation.run(
				LINE_HIERARCHY_STATISTICS,
				EnumSet.of(ReadRoute.HIERARCHY_STATISTICS),
				() -> session.query(
					query(
						collection(entityType),
						filterBy(scope(scope)),
						require(
							page(1, 1),
							hierarchyOfReference(
								referenceName, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
								fromRoot(
									"tree",
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				)
			)
		);
	}

	/**
	 * Adds the ordering lines of a reference - each the owners of the collection in the order the ordering sorts them:
	 *
	 * - for a reference to a non-hierarchical entity, the `pickFirst` orderings by every sortable non-chained attribute
	 *   and by the referenced primary key, each through the index route over every owner with the targets in
	 *   descending primary key order, and through the prefetch route over every other owner with the targets in a
	 *   seeded random order - the prefetch route answers the plain primary key order without any partition, and it
	 *   runs for a `Comparable` attribute type only, because it fails for the others with the switch off as well;
	 * - the block-by-block orderings by every sortable attribute, chained ones included, and - for a reference to an
	 *   entity whose hierarchy is indexed in the scope - by every sortable non-chained attribute narrowed by
	 *   `hierarchyWithinRoot`, whose planning set the hierarchy option of index selection supplies, and - for a
	 *   reference not allowing duplicates - by the referenced primary key and by `attributeSetExact` of every sortable
	 *   numeric or string attribute, the latter over the owners of one target whose row carries a value, referenced
	 *   by a single owner where there is one: it sorts by the sort index of the only partition the ordering plans over
	 *   and fails over several partitions and over a partition holding no value, with the switch off as well;
	 * - `referenceContent(orderBy)` of every sortable chained attribute, one line per owner.
	 *
	 * @param snapshot         the snapshot to add to
	 * @param session          the session to query in
	 * @param entityType       the collection
	 * @param scope            the scope to query
	 * @param referenceSchema  the reference
	 * @param hierarchical     TRUE when the reference targets a hierarchical entity, which admits no `pickFirst`
	 * @param hierarchyIndexed TRUE when the hierarchy of the referenced entity is indexed in the scope, which admits
	 *                         `hierarchyWithinRoot`
	 * @param sample           what the entity bodies of the collection hold
	 * @param activation       decides the read switch of the lines
	 * @param prefix           the path prefix of the lines
	 */
	private static void describeOrderings(
		@Nonnull Snapshot snapshot,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean hierarchical,
		boolean hierarchyIndexed,
		@Nonnull ReferenceSample sample,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String prefix
	) {
		final String referenceName = referenceSchema.getName();
		final List<Integer> owners = sample.getOwners();
		if (sample.getTargets(referenceName).isEmpty()) {
			return;
		}
		final FilterConstraint everyOtherOwner = entityPrimaryKeyInSet(everyOther(owners));
		final boolean duplicates = referenceSchema.getCardinality().allowsDuplicates();
		final String orderPrefix = prefix + "order/" + referenceName + "/";
		for (final AttributeSchemaContract attributeSchema : new TreeMap<>(referenceSchema.getAttributes()).values()) {
			if (!attributeSchema.isSortableInScope(scope) || attributeSchema.isLocalized()) {
				continue;
			}
			final String attributeName = attributeSchema.getName();
			final boolean chained = Predecessor.class.equals(attributeSchema.getPlainType()) ||
				ReferencedEntityPredecessor.class.equals(attributeSchema.getPlainType());
			// the prefetch route compares the values of a type that is not `Comparable` with the comparator of the
			// entities and fails with the switch off as well, so it runs for comparable types only
			final boolean prefetchable = Comparable.class.isAssignableFrom(attributeSchema.getPlainType());
			if (!chained && !hierarchical) {
				final OrderConstraint pickFirst = referenceProperty(
					referenceName,
					pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)),
					attributeNatural(attributeName, OrderDirection.ASC)
				);
				putOrder(
					snapshot, orderPrefix + "pickFirst/" + attributeName + "/index", activation, LINE_PICK_FIRST_INDEX,
					EnumSet.of(ReadRoute.PICK_FIRST_GATHER, ReadRoute.PICK_FIRST_CLAIM, ReadRoute.PICK_FIRST_VALUE),
					() -> queryOrder(session, entityType, scope, null, pickFirst, DebugMode.PREFER_INDEX_SCAN)
				);
			}
			if (!chained && !hierarchical && prefetchable) {
				// the prefetch route ranks the targets through the partitions only for an order other than the plain
				// primary key one, which it answers without touching any partition
				putOrder(
					snapshot, orderPrefix + "pickFirst/" + attributeName + "/prefetch", activation,
					LINE_PICK_FIRST_PREFETCH,
					EnumSet.of(ReadRoute.PICK_FIRST_GATHER, ReadRoute.PICK_FIRST_TARGET_RANK),
					() -> queryOrder(
						session, entityType, scope, everyOtherOwner,
						referenceProperty(
							referenceName,
							pickFirstByEntityProperty(randomWithSeed(SEED)),
							attributeNatural(attributeName, OrderDirection.ASC)
						),
						DebugMode.PREFER_PREFETCHING
					)
				);
			}
			putOrder(
				snapshot, orderPrefix + "traverse/" + attributeName, activation,
				chained ? LINE_TRAVERSE_CHAIN : LINE_TRAVERSE_ATTRIBUTE,
				EnumSet.of(ReadRoute.TRAVERSE_PLANNING_SET, ReadRoute.SEQUENTIAL_BLOCKS, ReadRoute.BLOCK_PROVIDERS),
				() -> queryOrder(
					session, entityType, scope, null,
					referenceProperty(
						referenceName,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(attributeName, OrderDirection.ASC)
					),
					DebugMode.PREFER_INDEX_SCAN
				)
			);
			if (!chained && hierarchyIndexed) {
				// the hierarchy option of index selection supplies the planning set of the ordering - the partitions
				// of the nodes, read virtually as handles
				putOrder(
					snapshot, orderPrefix + "hierarchyWithinRoot/traverse/" + attributeName, activation,
					LINE_HIERARCHY_TRAVERSE,
					EnumSet.of(
						ReadRoute.HIERARCHY_WITHIN, ReadRoute.HIERARCHY_TRAVERSE_PLANNING_SET,
						ReadRoute.SEQUENTIAL_BLOCKS, ReadRoute.BLOCK_PROVIDERS
					),
					() -> queryOrder(
						session, entityType, scope, hierarchyWithinRoot(referenceName),
						referenceProperty(
							referenceName,
							traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
							attributeNatural(attributeName, OrderDirection.ASC)
						),
						DebugMode.PREFER_INDEX_SCAN
					)
				);
			}
			if (chained) {
				final EvitaResponse<SealedEntity> chainedContent = activation.run(
					LINE_REFERENCE_CONTENT_CHAIN,
					EnumSet.of(ReadRoute.REFERENCE_CONTENT_CHAIN),
					() -> session.query(
						query(
							collection(entityType),
							filterBy(scope(scope)),
							require(
								page(1, PAGE_SIZE),
								entityFetch(
									referenceContentWithAttributes(
										referenceName, orderBy(attributeNatural(attributeName, OrderDirection.ASC))
									)
								)
							)
						),
						SealedEntity.class
					)
				);
				putReferences(
					snapshot, prefix + "referenceContent/" + referenceName + "/chain/" + attributeName + "/",
					referenceName, chainedContent, true
				);
			}
		}
		if (!hierarchical) {
			final OrderConstraint byReferencedKey = referenceProperty(
				referenceName, entityPrimaryKeyNatural(OrderDirection.ASC)
			);
			putOrder(
				snapshot, orderPrefix + "pickFirst/referencedPrimaryKey/index", activation,
				LINE_PICK_FIRST_REFERENCED_KEY,
				EnumSet.of(ReadRoute.PICK_FIRST_GATHER, ReadRoute.PICK_FIRST_REFERENCED_PRIMARY_KEY),
				() -> queryOrder(session, entityType, scope, null, byReferencedKey, DebugMode.PREFER_INDEX_SCAN)
			);
			putOrder(
				snapshot, orderPrefix + "pickFirst/referencedPrimaryKey/prefetch", activation,
				LINE_PICK_FIRST_PREFETCH,
				EnumSet.of(ReadRoute.PICK_FIRST_GATHER, ReadRoute.PICK_FIRST_TARGET_RANK),
				() -> queryOrder(
					session, entityType, scope, everyOtherOwner,
					referenceProperty(
						referenceName,
						pickFirstByEntityProperty(randomWithSeed(SEED)),
						entityPrimaryKeyNatural(OrderDirection.ASC)
					),
					DebugMode.PREFER_PREFETCHING
				)
			);
		}
		if (duplicates) {
			return;
		}
		putOrder(
			snapshot, orderPrefix + "traverse/referencedPrimaryKey", activation, LINE_TRAVERSE_REFERENCED_KEY,
			EnumSet.of(
				ReadRoute.TRAVERSE_PLANNING_SET, ReadRoute.SEQUENTIAL_BLOCKS, ReadRoute.BLOCK_REFERENCED_PRIMARY_KEY
			),
			() -> queryOrder(
				session, entityType, scope, null,
				referenceProperty(
					referenceName,
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					entityPrimaryKeyNatural(OrderDirection.DESC)
				),
				DebugMode.PREFER_INDEX_SCAN
			)
		);
		for (final AttributeSchemaContract attributeSchema : new TreeMap<>(referenceSchema.getAttributes()).values()) {
			final String attributeName = attributeSchema.getName();
			final List<Serializable> values = sample.getAttributeValues(referenceName, attributeName);
			// `attributeSetExact` sorts by the sort index of the only partition the ordering plans over, and fails with
			// the switch off as well over several partitions and over a partition holding no value of the attribute -
			// so the line narrows the owners to those of one target whose row carries a value
			final Integer exactTarget = sample.getTargetWithValue(referenceName, attributeName);
			if (
				!attributeSchema.isSortableInScope(scope) || attributeSchema.isLocalized() ||
					!isComparablePlainType(attributeSchema.getPlainType()) || values.isEmpty() || exactTarget == null
			) {
				continue;
			}
			final FilterConstraint ownersOfExactTarget = referenceHaving(
				referenceName, entityPrimaryKeyInSet(exactTarget)
			);
			// the last value first, so the exact order differs from the natural one
			final Serializable[] exactOrder = values.size() == 1 ?
				new Serializable[]{values.get(0)} :
				new Serializable[]{values.get(values.size() - 1), values.get(0)};
			putOrder(
				snapshot, orderPrefix + "traverse/attributeSetExact/" + attributeName, activation,
				LINE_TRAVERSE_ATTRIBUTE_SET_EXACT,
				EnumSet.of(
					ReadRoute.TRAVERSE_PLANNING_SET, ReadRoute.SEQUENTIAL_BLOCKS, ReadRoute.BLOCK_EXACT_ATTRIBUTE_SET
				),
				() -> queryOrder(
					session, entityType, scope, ownersOfExactTarget,
					referenceProperty(
						referenceName,
						traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeSetExact(attributeName, exactOrder)
					),
					DebugMode.PREFER_INDEX_SCAN
				)
			);
		}
	}

	/**
	 * Runs one ordering line and adds the owners it returned, in its order.
	 *
	 * @param snapshot   the snapshot to add to
	 * @param path       the path of the line
	 * @param activation decides the read switch of the line
	 * @param line       the name the virtual rows of the line are counted under
	 * @param routes     the routes the line exercises
	 * @param query      the ordering query
	 */
	private static void putOrder(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull ReadRouteActivation activation,
		@Nonnull String line,
		@Nonnull Set<ReadRoute> routes,
		@Nonnull Supplier<EvitaResponse<EntityReference>> query
	) {
		final EvitaResponse<EntityReference> ordered = activation.run(line, routes, query);
		final List<Integer> primaryKeys = new ArrayList<>(ordered.getRecordData().size());
		for (final EntityReference reference : ordered.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		snapshot.put(path, primaryKeys.toString());
	}

	/**
	 * Adds one line per fetched entity with its references of the reference - sorted, or in the order the response
	 * holds them when that order is what the line compares.
	 *
	 * @param snapshot      the snapshot to add to
	 * @param pathPrefix    the path prefix of the lines, completed by the entity primary key
	 * @param referenceName the reference
	 * @param response      the response holding the entities
	 * @param keepOrder     TRUE to keep the order of the response, FALSE to sort the references
	 */
	private static void putReferences(
		@Nonnull Snapshot snapshot,
		@Nonnull String pathPrefix,
		@Nonnull String referenceName,
		@Nonnull EvitaResponse<SealedEntity> response,
		boolean keepOrder
	) {
		for (final SealedEntity entity : response.getRecordData()) {
			final List<String> references = new ArrayList<>(entity.getReferences(referenceName).size());
			for (final ReferenceContract reference : entity.getReferences(referenceName)) {
				references.add(reference.getReferenceKey() + " " + reference.getAttributeValues());
			}
			if (!keepOrder) {
				Collections.sort(references);
			}
			snapshot.put(pathPrefix + entity.getPrimaryKeyOrThrowException(), references.toString());
		}
	}

	/**
	 * Queries the owners of the collection in the scope matching the filter.
	 *
	 * @param session    the session to query in
	 * @param entityType the collection
	 * @param scope      the scope to query
	 * @param filter     the filter beside the scope
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull FilterConstraint filter
	) {
		return session.query(
			query(collection(entityType), filterBy(scope(scope), filter), require(page(1, PAGE_SIZE))),
			EntityReference.class
		);
	}

	/**
	 * Queries the owners of the collection in the scope in the order of the ordering.
	 *
	 * @param session    the session to query in
	 * @param entityType the collection
	 * @param scope      the scope to query
	 * @param selection  the filter beside the scope, `null` for every owner
	 * @param ordering   the ordering
	 * @param debugMode  the debug mode forcing the route of the ordering
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nullable FilterConstraint selection,
		@Nonnull OrderConstraint ordering,
		@Nonnull DebugMode debugMode
	) {
		return session.query(
			query(
				collection(entityType),
				filterBy(scope(scope), selection),
				orderBy(ordering),
				require(page(1, PAGE_SIZE), debug(debugMode))
			),
			EntityReference.class
		);
	}

	/**
	 * Returns every other element of an ascending collection, starting with the first.
	 *
	 * @param elements the elements, ascending
	 * @return every other element
	 */
	@Nonnull
	private static Integer[] everyOther(@Nonnull Collection<Integer> elements) {
		final List<Integer> everyOther = new ArrayList<>((elements.size() + 1) / 2);
		int index = 0;
		for (final Integer element : elements) {
			if (index++ % 2 == 0) {
				everyOther.add(element);
			}
		}
		return everyOther.toArray(Integer[]::new);
	}

	/**
	 * Returns TRUE for the attribute types whose values the bounded and exact lines can name - numbers and strings.
	 *
	 * @param plainType the plain type of the attribute
	 * @return TRUE when the type is comparable by value
	 */
	private static boolean isComparablePlainType(@Nonnull Class<?> plainType) {
		return String.class.equals(plainType) || Number.class.isAssignableFrom(plainType);
	}

	/**
	 * Returns TRUE when the reference targets a managed hierarchical entity - whose hierarchy is indexed in the scope,
	 * when one is given.
	 *
	 * @param session         the session reading the schemas
	 * @param referenceSchema the reference
	 * @param scope           the scope the hierarchy must be indexed in, `null` for any
	 * @return TRUE when the reference targets a hierarchy
	 */
	private static boolean isReferenceToHierarchy(
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Scope scope
	) {
		return referenceSchema.isReferencedEntityTypeManaged() &&
			session.getEntitySchema(referenceSchema.getReferencedEntityType())
				.map(it -> it.isWithHierarchy() && (scope == null || it.isHierarchyIndexedInScope(scope)))
				.orElse(false);
	}

	/**
	 * Returns TRUE when the reference has a counterpart the bidirectional rewrite could answer it from - it is a
	 * reflected reference, or the referenced entity reflects it.
	 *
	 * @param session         the session reading the schemas
	 * @param schema          the schema of the collection owning the reference
	 * @param referenceSchema the reference
	 * @return TRUE when the reference has a counterpart
	 */
	private static boolean hasCounterpart(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (referenceSchema instanceof ReflectedReferenceSchemaContract) {
			return true;
		}
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return false;
		}
		return session.getEntitySchema(referenceSchema.getReferencedEntityType())
			.map(
				target -> target.getReferences().values().stream().anyMatch(
					it -> it instanceof final ReflectedReferenceSchemaContract reflected &&
						schema.getName().equals(reflected.getReferencedEntityType()) &&
						referenceSchema.getName().equals(reflected.getReflectedReferenceName())
				)
			)
			.orElse(false);
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
		@Nonnull Scope scope,
		boolean byRegistry
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
						snapshot, prefix + family + "/" + referenceName, collection, referencedTypeIndex,
						byRegistry && family == EntityIndexType.REFERENCED_ENTITY_TYPE
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
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		boolean byRegistry
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
		// only the type index of referenced entities carries a registry
		final VirtualReferencePartitionIndex registry =
			byRegistry ? typeIndex.getVirtualReferencePartitionIndex() : null;
		for (final int partitionPrimaryKey : advertised) {
			final String partitionPath = path + "/partition/" + partitionPrimaryKey;
			// TODO JNO - remove at the end of #1615
			if (registry != null && registry.getPartitionOwner(partitionPrimaryKey).isPresent()) {
				// a registered partition is its row in a catalog writing virtually - the registry lines describe it
				snapshot.put(partitionPath, "REGISTERED");
				continue;
			}
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
			if (partition == null) {
				snapshot.put(partitionPath, "MISSING");
			} else {
				describeIndex(snapshot, partitionPath, partition);
			}
		}
		// TODO JNO - remove at the end of #1615
		if (registry != null) {
			describeRegistry(snapshot, path + "/registry", typeIndex.getIndexKey().scope(), registry);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Adds the lines of a registry of single-owner partitions, independent of how its entries are laid out in pages:
	 * every owner row with its referenced entity and representative attribute values, the declared sort and chain
	 * keys, every sort value and chain predecessor of a registered partition and the entry count.
	 *
	 * @param snapshot the snapshot to add to
	 * @param path     the path of the lines
	 * @param scope    the scope of the registry
	 * @param registry the registry
	 */
	private static void describeRegistry(
		@Nonnull Snapshot snapshot,
		@Nonnull String path,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry
	) {
		final SortedMap<Integer, Long> rows = new TreeMap<>();
		registry.forEachEntry(VirtualReferencePartitionSlot.PARTITION_OWNER, rows::put);
		final SortedSet<String> sortKeys = new TreeSet<>();
		for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
			sortKeys.add(key.toString());
		}
		final SortedSet<String> chainKeys = new TreeSet<>();
		for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
			chainKeys.add(key.toString());
		}
		snapshot.put(path + "/sortKeys", sortKeys.toString());
		snapshot.put(path + "/chainKeys", chainKeys.toString());
		snapshot.put(path + "/entries", String.valueOf(registry.getEntryCount()));
		for (final Entry<Integer, Long> row : rows.entrySet()) {
			final int partitionPrimaryKey = row.getKey();
			final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
			final Serializable[] tuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
				new Serializable[0] : registry.getTuple(tupleId);
			final String rowPath = path + "/row/" + partitionPrimaryKey;
			snapshot.put(
				rowPath,
				"owner " + NumberUtils.unpackHigh(row.getValue()) + ", target " +
					NumberUtils.unpackLow(row.getValue()) + ", tuple " + CanonicalValue.encode(tuple) +
					", scope " + scope
			);
			for (final AttributeIndexKey key : registry.getDeclaredSortKeys()) {
				final int valueId = registry.getSortValueId(key, partitionPrimaryKey);
				if (valueId != VirtualReferencePartitionIndex.NO_SORT_VALUE_ID) {
					snapshot.put(
						rowPath + "/sort/" + key,
						CanonicalValue.encodeIndexed(registry.getNormalizedSortValue(key, valueId))
					);
				}
			}
			for (final AttributeIndexKey key : registry.getDeclaredChainKeys()) {
				final OptionalInt predecessor = registry.getChainPredecessor(key, partitionPrimaryKey);
				if (predecessor.isPresent()) {
					snapshot.put(rowPath + "/chain/" + key, String.valueOf(predecessor.getAsInt()));
				}
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
	private static void describeEnrollment(
		@Nonnull Snapshot snapshot,
		@Nonnull EntityCollection collection,
		boolean withoutVirtualPartitions
	) {
		final Set<String> enrolled = new TreeSet<>();
		for (final IndexKey indexKey : collection.getDirtyIndexKeys()) {
			if (indexKey instanceof final EntityIndexKey entityIndexKey) {
				if (entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
					continue;
				}
				// TODO JNO - remove at the end of #1615
				if (
					withoutVirtualPartitions && entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY &&
						entityIndexKey.discriminator() instanceof final RepresentativeReferenceKey key &&
						collection.getSchema().getReference(key.referenceName())
							.map(it -> VirtualReferencePartitionOracle.isShadowed(it, entityIndexKey.scope()))
							.orElse(false)
				) {
					continue;
				}
			}
			enrolled.add(indexKey.toString());
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
					uniqueIndex.size() + " records of " + uniqueIndex.getDistinctValueCount() + " values"
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
	private static void describeStorage(
		@Nonnull Snapshot snapshot,
		@Nonnull Evita evita,
		@Nonnull String entityType,
		boolean entityPartsOnly
	) {
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
			if (
				!EXCLUDED_STORAGE_PART_TYPES.contains(part.storagePartType()) &&
					(!entityPartsOnly || ENTITY_STORAGE_PART_TYPES.contains(part.storagePartType()))
			) {
				snapshot.put(
					prefix + "part/" + part.storagePartType(),
					part.count() + " records, " + part.totalBytes() + " bytes"
				);
			}
		}
		// TODO JNO - remove at the end of #1615 (the condition, its body stays)
		if (!entityPartsOnly) {
			snapshot.put(prefix + "population", statistics.indexSummaryIfPresent().orElseThrow().toString());
		}
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
	static void awaitCatalogLoaded(@Nonnull Evita evita) {
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
	 * A check run on every collection of the maintaining engine at every checkpoint.
	 */
	@FunctionalInterface
	interface MaintainingEngineCheck {

		/**
		 * Checks one collection.
		 *
		 * @param label      names the checkpoint in failure messages
		 * @param collection the collection
		 */
		void check(@Nonnull String label, @Nonnull EntityCollection collection);
	}

	/**
	 * What the entity bodies of one collection in one scope hold that the query lines are built from: the owners, their
	 * locales, and per reference the targets, the groups and the values of every non-localized numeric or string
	 * attribute of its rows.
	 */
	private static final class ReferenceSample {
		/**
		 * The owner primary keys, ascending.
		 */
		private final SortedSet<Integer> owners = new TreeSet<>();
		/**
		 * The locales of the owners, by language tag.
		 */
		private final SortedMap<String, Locale> locales = new TreeMap<>();
		/**
		 * The referenced primary keys per reference, ascending.
		 */
		private final Map<String, SortedSet<Integer>> targets = new TreeMap<>();
		/**
		 * How many owners reference each target, per reference.
		 */
		private final Map<String, Map<Integer, Integer>> ownersPerTarget = new TreeMap<>();
		/**
		 * The group primary keys per reference, ascending.
		 */
		private final Map<String, SortedSet<Integer>> groups = new TreeMap<>();
		/**
		 * The targets whose row carries a value of the attribute, per reference and attribute name.
		 */
		private final Map<String, SortedSet<Integer>> targetsWithValue = new TreeMap<>();
		/**
		 * The distinct values per reference and attribute name, ascending.
		 */
		private final Map<String, SortedSet<Comparable<Object>>> attributeValues = new TreeMap<>();

		/**
		 * Adds what one owner's body holds.
		 *
		 * @param entity the owner
		 */
		@SuppressWarnings("unchecked")
		void addOwner(@Nonnull SealedEntity entity) {
			this.owners.add(entity.getPrimaryKeyOrThrowException());
			for (final Locale locale : entity.getAllLocales()) {
				this.locales.put(locale.toLanguageTag(), locale);
			}
			final Set<String> ownTargets = CollectionUtils.createHashSet(entity.getReferences().size());
			for (final ReferenceContract reference : entity.getReferences()) {
				final String referenceName = reference.getReferenceName();
				this.targets.computeIfAbsent(referenceName, __ -> new TreeSet<>())
					.add(reference.getReferencedPrimaryKey());
				// an owner of a reference allowing duplicates is counted once per target
				if (ownTargets.add(referenceName + "/" + reference.getReferencedPrimaryKey())) {
					this.ownersPerTarget.computeIfAbsent(referenceName, __ -> new TreeMap<>())
						.merge(reference.getReferencedPrimaryKey(), 1, Integer::sum);
				}
				reference.getGroup().ifPresent(
					group -> this.groups.computeIfAbsent(referenceName, __ -> new TreeSet<>())
						.add(group.getPrimaryKey())
				);
				for (final AttributeValue attributeValue : reference.getAttributeValues()) {
					final Serializable value = attributeValue.value();
					if (
						attributeValue.key().locale() == null &&
							(value instanceof String || value instanceof Number) && value instanceof Comparable<?>
					) {
						final String key = referenceName + "/" + attributeValue.key().attributeName();
						this.attributeValues.computeIfAbsent(key, __ -> new TreeSet<>()).add((Comparable<Object>) value);
						this.targetsWithValue.computeIfAbsent(key, __ -> new TreeSet<>())
							.add(reference.getReferencedPrimaryKey());
					}
				}
			}
		}

		/**
		 * Returns a target of the reference whose row carries a value of the attribute - one exactly one owner
		 * references when there is such a target.
		 *
		 * @param referenceName the reference
		 * @param attributeName the attribute
		 * @return the target, `null` when no row of the reference carries a value of the attribute
		 */
		@Nullable
		Integer getTargetWithValue(@Nonnull String referenceName, @Nonnull String attributeName) {
			final SortedSet<Integer> withValue = this.targetsWithValue.get(referenceName + "/" + attributeName);
			if (withValue == null || withValue.isEmpty()) {
				return null;
			}
			final Map<Integer, Integer> counts = this.ownersPerTarget.getOrDefault(referenceName, Map.of());
			for (final Integer target : withValue) {
				if (counts.getOrDefault(target, 0) == 1) {
					return target;
				}
			}
			return withValue.first();
		}

		/**
		 * Returns the owner primary keys.
		 *
		 * @return the owners, ascending
		 */
		@Nonnull
		List<Integer> getOwners() {
			return new ArrayList<>(this.owners);
		}

		/**
		 * Returns the locales of the owners.
		 *
		 * @return the locales, by language tag
		 */
		@Nonnull
		Collection<Locale> getLocales() {
			return this.locales.values();
		}

		/**
		 * Returns the targets of a reference.
		 *
		 * @param referenceName the reference
		 * @return the referenced primary keys, ascending
		 */
		@Nonnull
		Set<Integer> getTargets(@Nonnull String referenceName) {
			return this.targets.getOrDefault(referenceName, Collections.emptySortedSet());
		}

		/**
		 * Returns the groups of a reference.
		 *
		 * @param referenceName the reference
		 * @return the group primary keys, ascending
		 */
		@Nonnull
		Set<Integer> getGroups(@Nonnull String referenceName) {
			return this.groups.getOrDefault(referenceName, Collections.emptySortedSet());
		}

		/**
		 * Returns the distinct values of an attribute of a reference.
		 *
		 * @param referenceName the reference
		 * @param attributeName the attribute
		 * @return the values, ascending
		 */
		@Nonnull
		List<Serializable> getAttributeValues(@Nonnull String referenceName, @Nonnull String attributeName) {
			final SortedSet<Comparable<Object>> values = this.attributeValues.get(referenceName + "/" + attributeName);
			final List<Serializable> result = new ArrayList<>(values == null ? 0 : values.size());
			if (values != null) {
				for (final Comparable<Object> value : values) {
					result.add((Serializable) value);
				}
			}
			return result;
		}
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
