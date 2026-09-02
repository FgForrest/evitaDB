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

package io.evitadb.spike.trigram;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.FilterIndexView;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * **A3 of the P8 trigram-substring-index plan - the replication census.** Boots an embedded evitaDB against a catalog
 * that already exists on disk, walks every index of every entity collection, and reports how the resident index heap
 * is divided between the one {@link EntityIndexType#GLOBAL} index of a collection and the reduced indexes that
 * replicate its attribute values.
 *
 * # What question it answers
 *
 * The P8 brief's §34 observes that every reference marked `FOR_FILTERING_AND_PARTITIONING` copies **all** filterable
 * and sortable entity-level attribute values into every `ReducedEntityIndex` - one per distinct referenced entity -
 * and that each reduced index owns its own `AttributeIndex`, hence its own front-coded value trees. §34.3 proposes
 * hoisting the ordered dictionary into the global index and leaving only value ids behind. Whether that is worth
 * building depends on a number nobody had measured: **what share of the attribute-index heap actually sits in the
 * reduced indexes.** That share is this class's headline output, and it calibrates both the separate dedup line of
 * §34 and P1's own §4.8 estimates.
 *
 * The census also splits the attribute heap by sub-index family, because §34 names two distinct duplication layers
 * and they are paid in different families:
 *
 * - the **filter** family carries the per-reduced-index copy of the entity-level values, and its `value trees`
 *   column isolates the front-coded {@link io.evitadb.index.invertedIndex.InvertedIndex} beneath the views - the
 *   bytes §34.3 proposes to stop replicating;
 * - the **sort** family's `owner-mode` column is the second layer of §34.1: a sort-only attribute and every sortable
 *   attribute compound get an {@link OwnerSortIndex} holding a private value tree, storing the values a second time
 *   *inside the same entity index*.
 *
 * # Which surface the numbers come from, and why
 *
 * **Enumeration goes through the management surface, measurement does not.** Indexes are listed with
 * {@link EntityCollection#browseIndexes(IndexBrowseCriteria)} paging in {@link IndexBrowseOrdering#MAP_ORDER}, which
 * is the one ordering documented as the cheap exhaustive walk and the one exempted from the ranked-paging depth cap.
 * That matters for a census: index primary keys are sparse - a dropped index leaves a gap - so scanning primary keys
 * upwards until `getIndexCount()` of them have been found (what the older `IndexMemoryFootprintSpike` does) can
 * silently stop short, and a census that misses indexes answers the wrong question.
 *
 * The heap figures cannot come from the same place. {@link io.evitadb.api.statistics.BrowsedIndex} carries no memory
 * reading at all - deliberately, see {@link IndexBrowseOrdering#ENTITY_COUNT} - and
 * {@link io.evitadb.api.statistics.IndexDetail} carries only the index total, never the attribute-index share this
 * census is about. So each browsed row is resolved back to its live {@link EntityIndex} through
 * {@link EntityCollection#getIndexByPrimaryKeyIfExists(int)} and measured against the engine objects directly. This
 * is a spike run by hand against a named snapshot; nothing in CI depends on it.
 *
 * **One reflective read.** {@link AttributeIndex#getHeapSizeInBytes()} is the figure the plan names and the figure
 * {@link EntityIndex#getHeapSizeInBytes()} folds in, but the `attributeIndex` field is `protected` and no accessor
 * exposes it. It is read reflectively rather than re-derived from the per-family accessors, because re-deriving it
 * would silently omit whatever the family sum does not reach - the seven map spines, the key objects and the five
 * persisted leaf-page snapshots - and a census whose headline drifts from the engine's own accounting is worse than
 * no census. The per-family columns are reported *beside* that authoritative total, never in place of it.
 *
 * # Configuration
 *
 * The properties are the extractor's, so the same command line serves both:
 *
 * | Property | Meaning |
 * |---|---|
 * | `evita.trigram.catalogName` | **required** - the catalog to walk |
 * | `evita.trigram.dataDir` | **required** - storage directory containing a `<catalogName>/` subfolder |
 * | `evita.trigram.workDir` | working copy location; defaults to a fresh temp directory |
 * | `evita.trigram.copyData` | `false` opens `dataDir` in place instead of copying (default `true`) |
 * | `evita.trigram.compress` | storage compression of the snapshot (default `true`) |
 * | `evita.trigram.entityTypes` | comma-separated collection allow-list; default: every collection |
 *
 * # Running it
 *
 * ```shell
 * java -Xmx8g \
 *   --add-opens java.base/java.lang=ALL-UNNAMED \
 *   --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
 *   --add-opens java.base/java.math=ALL-UNNAMED \
 *   --add-opens java.base/java.util=ALL-UNNAMED \
 *   -Devita.trigram.catalogName=demo \
 *   -Devita.trigram.dataDir=/path/to/snapshot \
 *   -Devita.trigram.workDir=/path/to/work \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.TrigramReplicationCensus
 * ```
 *
 * The `--add-opens` flags are the ones {@link TrigramCorpusExtractor} documents: Byte Buddy generates classes
 * reflectively while an Evita instance boots and fails without them. Run with a heap large enough for the catalog -
 * the indexes are resident, only entity bodies stay on disk.
 *
 * @author Claude (P8 trigram-substring-index spike), FG Forrest a.s. (c) 2026
 */
public class TrigramReplicationCensus {

	/**
	 * System property naming the catalog to walk. Shared with {@link TrigramCorpusExtractor}.
	 */
	public static final String CATALOG_NAME_PROPERTY = "evita.trigram.catalogName";

	/**
	 * System property pointing at the storage directory that holds the `&lt;catalogName&gt;/` subfolder.
	 */
	public static final String DATA_DIR_PROPERTY = "evita.trigram.dataDir";

	/**
	 * System property overriding the working directory the catalog is copied into.
	 */
	public static final String WORK_DIR_PROPERTY = "evita.trigram.workDir";

	/**
	 * System property switching the pre-boot copy off, opening the data directory in place.
	 */
	public static final String COPY_DATA_PROPERTY = "evita.trigram.copyData";

	/**
	 * System property matching {@link StorageOptions#compress()} to the snapshot being read. Defaults to `true` for
	 * the reason {@link TrigramCorpusExtractor#COMPRESS_PROPERTY} documents: a reader with compression enabled opens
	 * both forms, one without it reports a compressed snapshot as a corrupted catalog.
	 */
	public static final String COMPRESS_PROPERTY = "evita.trigram.compress";

	/**
	 * System property restricting the census to a comma-separated list of entity types.
	 */
	public static final String ENTITY_TYPES_PROPERTY = "evita.trigram.entityTypes";

	/**
	 * How long the census waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * How often progress is reported while a collection's indexes are being measured.
	 */
	private static final int PROGRESS_LOG_INTERVAL = 25_000;

	/**
	 * The `attributeIndex` field of {@link EntityIndex}, opened once. See the class JavaDoc for why the census reads
	 * it reflectively instead of summing the publicly reachable sub-index families.
	 */
	private static final Field ATTRIBUTE_INDEX_FIELD = resolveAttributeIndexField();

	/**
	 * Entry point. Any failure exits the JVM explicitly: a partially constructed Evita instance has already
	 * started non-daemon threads, so a failed boot would otherwise leave a hanging JVM behind that still holds
	 * the storage-folder locks and blocks every subsequent run against the same directories.
	 *
	 * @param args unused; configuration is taken from system properties
	 */
	public static void main(@Nonnull String[] args) {
		try {
			run();
		} catch (final Throwable e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Performs the census as described in the class JavaDoc.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(DATA_DIR_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(System.getProperty(COPY_DATA_PROPERTY, "true"));
		final boolean compress = Boolean.parseBoolean(System.getProperty(COMPRESS_PROPERTY, "true"));
		final Set<String> entityTypeFilter = parseListProperty(ENTITY_TYPES_PROPERTY);

		final Path storageDir = copyData
			? copyCatalog(dataDir, catalogName)
			: dataDir;

		System.out.printf(
			"Trigram replication census - catalog `%s` from `%s`%n", catalogName, storageDir
		);

		final long bootStart = System.nanoTime();
		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(storageDir)
							.compress(compress)
							.build()
					)
					.server(
						ServerOptions.builder()
							.queryTimeoutInMilliseconds(600_000)
							.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			)
		) {
			final Catalog catalog = awaitLoaded(evita, catalogName);
			System.out.printf(
				"Catalog booted in %,d ms - version %d, state %s.%n%n",
				(System.nanoTime() - bootStart) / 1_000_000, catalog.getVersion(), catalog.getCatalogState()
			);
			censusCatalog(catalog, entityTypeFilter);
		}
	}

	/* ======================================== census ============================================= */

	/**
	 * Walks every selected collection of the catalog, prints the per-collection table as it goes, then the
	 * catalog-wide breakdowns and the headline replication share.
	 *
	 * @param catalog          the loaded catalog
	 * @param entityTypeFilter collections to visit; empty means every collection
	 */
	private static void censusCatalog(@Nonnull Catalog catalog, @Nonnull Set<String> entityTypeFilter) {
		final Map<EntityIndexType, Tally> catalogByType = new EnumMap<>(EntityIndexType.class);
		final Map<Scope, Tally> catalogByScope = new EnumMap<>(Scope.class);
		final List<CollectionReading> collectionReadings = new ArrayList<>(16);

		final Set<String> entityTypes = new TreeSet<>(catalog.getEntityTypes());
		for (final String entityType : entityTypes) {
			if (!entityTypeFilter.isEmpty() && !entityTypeFilter.contains(entityType)) {
				continue;
			}
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			System.out.printf("  %-28s measuring %,d indexes...%n", entityType, collection.getIndexCount());
			final long start = System.nanoTime();
			final Map<EntityIndexType, Tally> byType = censusCollection(collection, catalogByType, catalogByScope);
			System.out.printf(
				"  %-28s done in %,d ms.%n", entityType, (System.nanoTime() - start) / 1_000_000
			);
			collectionReadings.add(new CollectionReading(entityType, byType));
		}

		printCollectionTable(collectionReadings);
		printTypeHeapTable(catalogByType);
		printTypeFamilyTable(catalogByType);
		printScopeTable(catalogByScope);
		printHeadline(catalogByType);
	}

	/**
	 * Measures every index of one collection, folding each reading into the collection's own tallies and into the
	 * two catalog-wide ones at the same time.
	 *
	 * Indexes are enumerated through {@link EntityCollection#browseIndexes(IndexBrowseCriteria)} rather than by
	 * scanning primary keys, because primary keys are sparse and a scan that stops after `getIndexCount()` hits can
	 * end before the last index - see the class JavaDoc.
	 *
	 * @param collection     the collection to measure
	 * @param catalogByType  catalog-wide accumulator keyed by index kind
	 * @param catalogByScope catalog-wide accumulator keyed by scope
	 * @return this collection's tallies, keyed by index kind
	 */
	@Nonnull
	private static Map<EntityIndexType, Tally> censusCollection(
		@Nonnull EntityCollection collection,
		@Nonnull Map<EntityIndexType, Tally> catalogByType,
		@Nonnull Map<Scope, Tally> catalogByScope
	) {
		final Map<EntityIndexType, Tally> byType = new EnumMap<>(EntityIndexType.class);
		int pageNumber = 1;
		int measured = 0;
		int total = Integer.MAX_VALUE;
		while (measured < total) {
			final IndexBrowseResult page = collection.browseIndexes(
				new IndexBrowseCriteria(
					pageNumber, IndexBrowseCriteria.MAX_PAGE_SIZE,
					// the one ordering that materialises nothing outside the window and is therefore exempt from the
					// ranked-paging depth cap - the documented way to walk the whole set
					IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
					// three empty sets: every kind, every scope, every reference
					Set.of(), Set.of(), Set.of()
				)
			);
			total = page.totalRecordCount();
			final BrowsedIndex[] rows = page.indexes();
			if (rows.length == 0) {
				break;
			}
			for (int i = 0; i < rows.length; i++) {
				measureRow(collection, rows[i], byType, catalogByType, catalogByScope);
			}
			measured += rows.length;
			if (measured % PROGRESS_LOG_INTERVAL < rows.length && measured < total) {
				System.out.printf("      %,d / %,d indexes%n", measured, total);
			}
			pageNumber++;
		}
		return byType;
	}

	/**
	 * Resolves one browsed row back to its live index and folds its readings into all three accumulators.
	 *
	 * @param collection     the collection the row belongs to
	 * @param row            the browsed row naming the index
	 * @param byType         the collection's accumulator keyed by index kind
	 * @param catalogByType  catalog-wide accumulator keyed by index kind
	 * @param catalogByScope catalog-wide accumulator keyed by scope
	 */
	private static void measureRow(
		@Nonnull EntityCollection collection,
		@Nonnull BrowsedIndex row,
		@Nonnull Map<EntityIndexType, Tally> byType,
		@Nonnull Map<EntityIndexType, Tally> catalogByType,
		@Nonnull Map<Scope, Tally> catalogByScope
	) {
		final EntityIndexType indexType = row.indexType();
		if (indexType == null) {
			// only a catalog-level index reports no kind, and a collection browse cannot return one
			throw new GenericEvitaInternalError(
				"Collection `" + collection.getEntityType() + "` browsed index `" + row.indexPrimaryKey() +
					"` without an index type!",
				"Collection browse returned an index without a type!"
			);
		}
		final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(row.indexPrimaryKey());
		if (index == null) {
			// the browse read an immutable snapshot and this spike opens no writing session, so an index named by a
			// row must still be resolvable - a miss is a defect, not a race to tolerate
			throw new GenericEvitaInternalError(
				"Index `" + row.indexPrimaryKey() + "` of collection `" + collection.getEntityType() +
					"` was browsed but cannot be resolved!",
				"Browsed index cannot be resolved!"
			);
		}
		final Tally collectionTally = byType.computeIfAbsent(indexType, key -> new Tally());
		final Tally catalogTypeTally = catalogByType.computeIfAbsent(indexType, key -> new Tally());
		final Tally catalogScopeTally = catalogByScope.computeIfAbsent(row.scope(), key -> new Tally());
		measureIndex(index, collectionTally, catalogTypeTally, catalogScopeTally);
	}

	/**
	 * Measures one index and adds its readings to every accumulator passed in.
	 *
	 * The readings are taken once and folded into all three tallies, because every one of them is `O(index
	 * contents)`: {@link EntityIndex#getHeapSizeInBytes()} and {@link AttributeIndex#getHeapSizeInBytes()} each walk
	 * the whole index, and so does every sub-index reading below.
	 *
	 * @param index           the index to measure
	 * @param collectionTally the owning collection's accumulator for this index kind
	 * @param catalogTally    the catalog-wide accumulator for this index kind
	 * @param scopeTally      the catalog-wide accumulator for this index's scope
	 */
	private static void measureIndex(
		@Nonnull EntityIndex index,
		@Nonnull Tally collectionTally,
		@Nonnull Tally catalogTally,
		@Nonnull Tally scopeTally
	) {
		final AttributeIndex attributeIndex = attributeIndexOf(index);
		final Reading reading = new Reading();
		reading.indexCount = 1;
		reading.entityIndexBytes = index.getHeapSizeInBytes();
		reading.attributeIndexBytes = attributeIndex.getHeapSizeInBytes();
		reading.entityCount = index.getAllPrimaryKeys().size();

		measureFilterFamily(attributeIndex, reading);
		measureUniqueFamily(attributeIndex, reading);
		measureSortFamily(attributeIndex, reading);
		measureChainFamily(attributeIndex, reading);

		collectionTally.add(reading);
		catalogTally.add(reading);
		scopeTally.add(reading);
	}

	/**
	 * Prices the filter family of one attribute index.
	 *
	 * The keys come from the shared value index, so each names exactly one front-coded value tree. What the resolved
	 * {@link FilterIndex} charges depends on which variant it is, and the census must add back precisely what the
	 * variant leaves out or it would double-count the trees:
	 *
	 * - a {@link FilterIndexView} charges its own object and its query memos but neither the shared value tree nor
	 *   the shared range companion, both of which {@link AttributeIndex} charges once on their own maps;
	 * - an owner variant already charges both, so nothing is added.
	 *
	 * @param attributeIndex the attribute index to walk
	 * @param reading        the reading being assembled
	 */
	private static void measureFilterFamily(@Nonnull AttributeIndex attributeIndex, @Nonnull Reading reading) {
		for (final AttributeIndexKey key : attributeIndex.getFilterIndexes()) {
			final FilterIndex filterIndex = attributeIndex.getFilterIndex(key);
			if (filterIndex == null) {
				throw new GenericEvitaInternalError(
					"Filter index key `" + key + "` resolves to no filter index!",
					"Filter index key resolves to no filter index!"
				);
			}
			reading.filterKeyCount++;
			final long treeBytes = filterIndex.getInvertedIndex().getHeapSizeInBytes();
			reading.filterTreeBytes += treeBytes;
			long familyBytes = filterIndex.getHeapSizeInBytes();
			if (filterIndex instanceof FilterIndexView) {
				familyBytes += treeBytes;
				final RangeIndex rangeIndex = filterIndex.getRangeIndex();
				if (rangeIndex != null) {
					familyBytes += rangeIndex.getHeapSizeInBytes();
				}
			}
			reading.filterBytes += familyBytes;
		}
	}

	/**
	 * Prices the unique family of one attribute index.
	 *
	 * The key set is the union of the standalone (owner) and folded (view) unique keys, and each variant charges
	 * exactly what it owns - an owner its tree, a view only its own object - so summing the resolved indexes matches
	 * what {@link AttributeIndex#getHeapSizeInBytes()} charges on its two unique maps.
	 *
	 * @param attributeIndex the attribute index to walk
	 * @param reading        the reading being assembled
	 */
	private static void measureUniqueFamily(@Nonnull AttributeIndex attributeIndex, @Nonnull Reading reading) {
		for (final AttributeIndexKey key : attributeIndex.getUniqueIndexes()) {
			final UniqueIndex uniqueIndex = attributeIndex.getUniqueIndex(key);
			if (uniqueIndex == null) {
				throw new GenericEvitaInternalError(
					"Unique index key `" + key + "` resolves to no unique index!",
					"Unique index key resolves to no unique index!"
				);
			}
			reading.uniqueBytes += uniqueIndex.getHeapSizeInBytes();
		}
	}

	/**
	 * Prices the sort family of one attribute index, splitting out the owner-mode subtotal.
	 *
	 * The split is the second duplication layer of the brief's §34.1: a sort-only attribute and every sortable
	 * attribute compound get an {@link OwnerSortIndex} holding a private value tree, which stores the values a second
	 * time inside the same entity index. A view-mode sort index reads the shared tree the filter family already
	 * charges, so it contributes almost nothing here.
	 *
	 * @param attributeIndex the attribute index to walk
	 * @param reading        the reading being assembled
	 */
	private static void measureSortFamily(@Nonnull AttributeIndex attributeIndex, @Nonnull Reading reading) {
		for (final AttributeIndexKey key : attributeIndex.getSortIndexes()) {
			final SortIndex sortIndex = attributeIndex.getSortIndex(key);
			if (sortIndex == null) {
				throw new GenericEvitaInternalError(
					"Sort index key `" + key + "` resolves to no sort index!",
					"Sort index key resolves to no sort index!"
				);
			}
			final long sortBytes = sortIndex.getHeapSizeInBytes();
			reading.sortBytes += sortBytes;
			if (sortIndex instanceof OwnerSortIndex) {
				reading.sortOwnedBytes += sortBytes;
			}
		}
	}

	/**
	 * Prices the chain family of one attribute index.
	 *
	 * @param attributeIndex the attribute index to walk
	 * @param reading        the reading being assembled
	 */
	private static void measureChainFamily(@Nonnull AttributeIndex attributeIndex, @Nonnull Reading reading) {
		for (final AttributeIndexKey key : attributeIndex.getChainIndexes()) {
			final ChainIndex chainIndex = attributeIndex.getChainIndex(key);
			if (chainIndex == null) {
				throw new GenericEvitaInternalError(
					"Chain index key `" + key + "` resolves to no chain index!",
					"Chain index key resolves to no chain index!"
				);
			}
			reading.chainBytes += chainIndex.getHeapSizeInBytes();
		}
	}

	/* ======================================== reporting ========================================== */

	/**
	 * Prints one row per collection: how many indexes it holds, what they weigh, and how much of that weight is
	 * attribute data.
	 *
	 * @param readings the per-collection tallies, in walk order
	 */
	private static void printCollectionTable(@Nonnull List<CollectionReading> readings) {
		System.out.printf("%n=== PER COLLECTION ===%n");
		System.out.printf(
			"%-28s %9s %13s %13s %13s %8s%n",
			"entityType", "indexes", "entities", "index heap", "attr heap", "attr %"
		);
		for (int i = 0; i < readings.size(); i++) {
			final CollectionReading reading = readings.get(i);
			final Tally total = Tally.sum(reading.byType().values());
			System.out.printf(
				"%-28s %,9d %,13d %13s %13s %8s%n",
				reading.entityType(), total.indexCount, total.entityCount,
				bytes(total.entityIndexBytes), bytes(total.attributeIndexBytes),
				percent(total.attributeIndexBytes, total.entityIndexBytes)
			);
		}
	}

	/**
	 * Prints the catalog-wide heap breakdown by index kind - the table the census exists to produce.
	 *
	 * @param byType catalog-wide tallies keyed by index kind
	 */
	private static void printTypeHeapTable(@Nonnull Map<EntityIndexType, Tally> byType) {
		System.out.printf("%n=== PER INDEX TYPE - HEAP (whole catalog) ===%n");
		System.out.printf(
			"%-28s %9s %13s %13s %13s %8s %13s%n",
			"indexType", "indexes", "entities", "index heap", "attr heap", "attr %", "attr / index"
		);
		for (final EntityIndexType type : EntityIndexType.values()) {
			final Tally tally = byType.get(type);
			if (tally == null) {
				continue;
			}
			System.out.printf(
				"%-28s %,9d %,13d %13s %13s %8s %13s%n",
				type, tally.indexCount, tally.entityCount,
				bytes(tally.entityIndexBytes), bytes(tally.attributeIndexBytes),
				percent(tally.attributeIndexBytes, tally.entityIndexBytes),
				bytes(tally.indexCount == 0 ? 0L : tally.attributeIndexBytes / tally.indexCount)
			);
		}
		final Tally total = Tally.sum(byType.values());
		System.out.printf(
			"%-28s %,9d %,13d %13s %13s %8s %13s%n",
			"TOTAL", total.indexCount, total.entityCount,
			bytes(total.entityIndexBytes), bytes(total.attributeIndexBytes),
			percent(total.attributeIndexBytes, total.entityIndexBytes),
			bytes(total.indexCount == 0 ? 0L : total.attributeIndexBytes / total.indexCount)
		);
	}

	/**
	 * Prints where the attribute heap sits inside each index kind, family by family.
	 *
	 * The two isolating columns are the ones §34 needs: `value trees` is the front-coded payload the dedup line
	 * proposes to stop replicating, and `sort owned` is the private tree a sort-only attribute keeps beside it.
	 *
	 * @param byType catalog-wide tallies keyed by index kind
	 */
	private static void printTypeFamilyTable(@Nonnull Map<EntityIndexType, Tally> byType) {
		System.out.printf("%n=== PER INDEX TYPE - ATTRIBUTE FAMILIES (whole catalog) ===%n");
		System.out.printf(
			"%-28s %11s %13s %13s %13s %13s %13s%n",
			"indexType", "filter keys", "filter", "value trees", "sort", "sort owned", "chain"
		);
		for (final EntityIndexType type : EntityIndexType.values()) {
			final Tally tally = byType.get(type);
			if (tally == null) {
				continue;
			}
			printFamilyRow(type.name(), tally);
		}
		final Tally total = Tally.sum(byType.values());
		printFamilyRow("TOTAL", total);
		System.out.println(
			"  `filter` includes the shared value tree and range companion each view reads; `value trees` is the\n" +
				"  tree alone. `sort owned` is the part of `sort` held in owner mode, i.e. in a private value tree."
		);
		System.out.printf(
			"  unique family across the catalog: %s (kept out of the table - it is dwarfed by the rest)%n",
			bytes(total.uniqueBytes)
		);
		// the families never add up to the authoritative attribute total and are not meant to: the seven sub-index map
		// spines, the key objects and the five persisted leaf-page snapshots are charged by `AttributeIndex` and by no
		// family below it, which is exactly why the headline is read off the total rather than off this table
		System.out.printf(
			"  families above sum to %s of the %s attribute heap; the rest is map spines, keys and leaf-page " +
				"snapshots.%n",
			bytes(total.filterBytes + total.sortBytes + total.uniqueBytes + total.chainBytes),
			bytes(total.attributeIndexBytes)
		);
	}

	/**
	 * Prints one row of the attribute-family table.
	 *
	 * @param label the row label
	 * @param tally the tally to render
	 */
	private static void printFamilyRow(@Nonnull String label, @Nonnull Tally tally) {
		System.out.printf(
			"%-28s %,11d %13s %13s %13s %13s %13s%n",
			label, tally.filterKeyCount,
			bytes(tally.filterBytes), bytes(tally.filterTreeBytes),
			bytes(tally.sortBytes), bytes(tally.sortOwnedBytes), bytes(tally.chainBytes)
		);
	}

	/**
	 * Prints the scope split, so a catalog holding archived data cannot be read as if all of its indexes were live.
	 *
	 * @param byScope catalog-wide tallies keyed by scope
	 */
	private static void printScopeTable(@Nonnull Map<Scope, Tally> byScope) {
		System.out.printf("%n=== PER SCOPE (whole catalog) ===%n");
		System.out.printf("%-28s %9s %13s %13s%n", "scope", "indexes", "index heap", "attr heap");
		for (final Map.Entry<Scope, Tally> entry : byScope.entrySet()) {
			final Tally tally = entry.getValue();
			System.out.printf(
				"%-28s %,9d %13s %13s%n",
				entry.getKey(), tally.indexCount, bytes(tally.entityIndexBytes), bytes(tally.attributeIndexBytes)
			);
		}
	}

	/**
	 * Prints the calibration numbers the plan's gate G0 and the brief's §34 are waiting for: how much of the
	 * attribute-index heap is replicated rather than held once.
	 *
	 * Three lines rather than one, because §34.3 treats the kinds differently. The per-referenced-entity indexes are
	 * the fan-out the dedup line targets; the type-level indexes are proposed as the *host* of the hoisted dictionary
	 * for reference-level attributes, so their share is a cost that would remain.
	 *
	 * @param byType catalog-wide tallies keyed by index kind
	 */
	private static void printHeadline(@Nonnull Map<EntityIndexType, Tally> byType) {
		final Tally total = Tally.sum(byType.values());
		final Tally global = byType.getOrDefault(EntityIndexType.GLOBAL, new Tally());
		final long reducedBytes = total.attributeIndexBytes - global.attributeIndexBytes;
		final int reducedCount = total.indexCount - global.indexCount;
		final long fanOutBytes = attributeBytesOf(byType, EntityIndexType.REFERENCED_ENTITY)
			+ attributeBytesOf(byType, EntityIndexType.REFERENCED_GROUP_ENTITY);
		final int fanOutCount = indexCountOf(byType, EntityIndexType.REFERENCED_ENTITY)
			+ indexCountOf(byType, EntityIndexType.REFERENCED_GROUP_ENTITY);
		final long typeLevelBytes = attributeBytesOf(byType, EntityIndexType.REFERENCED_ENTITY_TYPE)
			+ attributeBytesOf(byType, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE);
		final long reducedTreeBytes = total.filterTreeBytes - global.filterTreeBytes;

		System.out.printf("%n=== HEADLINE - WHERE THE ATTRIBUTE HEAP SITS ===%n");
		System.out.printf(
			"attribute-index heap, whole catalog        : %13s across %,d indexes%n",
			bytes(total.attributeIndexBytes), total.indexCount
		);
		System.out.printf(
			"  held once, in GLOBAL indexes             : %13s across %,d indexes (%s)%n",
			bytes(global.attributeIndexBytes), global.indexCount,
			percent(global.attributeIndexBytes, total.attributeIndexBytes)
		);
		System.out.printf(
			"  replicated, in reduced indexes           : %13s across %,d indexes (%s)  <-- the calibration number%n",
			bytes(reducedBytes), reducedCount, percent(reducedBytes, total.attributeIndexBytes)
		);
		System.out.printf(
			"    of which per-referenced-entity fan-out : %13s across %,d indexes (%s)%n",
			bytes(fanOutBytes), fanOutCount, percent(fanOutBytes, total.attributeIndexBytes)
		);
		System.out.printf(
			"    of which reference-type level          : %13s (%s)%n",
			bytes(typeLevelBytes), percent(typeLevelBytes, total.attributeIndexBytes)
		);
		System.out.printf(
			"front-coded value trees in reduced indexes : %13s (%s of all value trees)%n",
			bytes(reducedTreeBytes), percent(reducedTreeBytes, total.filterTreeBytes)
		);
		// plain ASCII on purpose: this table gets pasted into a brief, and a console running a non-UTF-8 default
		// charset renders a section sign as a question mark
		System.out.println(
			"\nRead the reduced share as the ceiling the brief's 34.3 dictionary hoist could reclaim, not as the\n" +
				"saving: a reduced index would still hold a membership bitmap and its postings once the values\n" +
				"move out."
		);
	}

	/**
	 * Returns the attribute-index bytes tallied for one index kind, or zero when the catalog holds none of them.
	 *
	 * @param byType catalog-wide tallies keyed by index kind
	 * @param type   the kind to read
	 * @return the attribute-index bytes of that kind
	 */
	private static long attributeBytesOf(@Nonnull Map<EntityIndexType, Tally> byType, @Nonnull EntityIndexType type) {
		final Tally tally = byType.get(type);
		return tally == null ? 0L : tally.attributeIndexBytes;
	}

	/**
	 * Returns how many indexes of one kind were tallied, or zero when the catalog holds none of them.
	 *
	 * @param byType catalog-wide tallies keyed by index kind
	 * @param type   the kind to read
	 * @return the index count of that kind
	 */
	private static int indexCountOf(@Nonnull Map<EntityIndexType, Tally> byType, @Nonnull EntityIndexType type) {
		final Tally tally = byType.get(type);
		return tally == null ? 0 : tally.indexCount;
	}

	/* ========================================= support =========================================== */

	/**
	 * Opens the `attributeIndex` field of {@link EntityIndex} for reading.
	 *
	 * The spike runs from the shaded benchmark jar on the class path, so every evitaDB class sits in the unnamed
	 * module and the field opens without an `--add-opens` flag. A failure here means the field was renamed or the
	 * jar was put on the module path, and either way the census cannot produce its headline number - so it fails at
	 * class initialization rather than reporting a breakdown it silently could not compute.
	 *
	 * @return the opened field
	 */
	@Nonnull
	private static Field resolveAttributeIndexField() {
		try {
			final Field field = EntityIndex.class.getDeclaredField("attributeIndex");
			field.setAccessible(true);
			return field;
		} catch (final NoSuchFieldException | SecurityException e) {
			throw new GenericEvitaInternalError(
				"Cannot open `EntityIndex#attributeIndex` - the census reads it to reach " +
					"`AttributeIndex#getHeapSizeInBytes()`, which no accessor exposes.",
				"Cannot open the attribute index field of an entity index!",
				e
			);
		}
	}

	/**
	 * Reads the attribute sub-index of one entity index.
	 *
	 * @param index the index to read
	 * @return its attribute sub-index
	 */
	@Nonnull
	private static AttributeIndex attributeIndexOf(@Nonnull EntityIndex index) {
		try {
			return (AttributeIndex) ATTRIBUTE_INDEX_FIELD.get(index);
		} catch (final IllegalAccessException e) {
			throw new GenericEvitaInternalError(
				"Cannot read `EntityIndex#attributeIndex` of index `" + index.getIndexKey() + "`!",
				"Cannot read the attribute index field of an entity index!",
				e
			);
		}
	}

	/**
	 * Copies the named catalog out of the snapshot into a disposable working directory, so that boot-time WAL
	 * recovery and storage compaction cannot alter the snapshot every later run has to start from.
	 *
	 * Only the `&lt;catalogName&gt;/` subfolder of the working directory is deleted beforehand - never the working
	 * directory itself, which may be a location the caller keeps other things in.
	 *
	 * @param dataDir     snapshot directory holding the catalog
	 * @param catalogName catalog to copy
	 * @return the working directory to boot against
	 */
	@Nonnull
	private static Path copyCatalog(@Nonnull Path dataDir, @Nonnull String catalogName) throws IOException {
		final String workDirProperty = System.getProperty(WORK_DIR_PROPERTY);
		final Path workDir = workDirProperty == null || workDirProperty.isBlank()
			? Files.createTempDirectory("evita-trigram-census")
			: Path.of(workDirProperty);
		final Path source = dataDir.resolve(catalogName);
		if (!Files.isDirectory(source)) {
			throw new GenericEvitaInternalError(
				"Data directory `" + dataDir + "` holds no `" + catalogName + "/` subfolder!",
				"Data directory holds no subfolder for the requested catalog!"
			);
		}
		final Path target = workDir.resolve(catalogName);
		System.out.printf("Copying catalog `%s` to `%s`...%n", catalogName, target);
		final long copyStart = System.nanoTime();
		FileUtils.deleteDirectory(target.toFile());
		FileUtils.copyDirectory(source.toFile(), target.toFile());
		System.out.printf("Copy finished in %,d ms.%n", (System.nanoTime() - copyStart) / 1_000_000);
		return workDir;
	}

	/**
	 * Waits until the named catalog finishes loading. Catalogs load on background threads, so the first lookup after
	 * the constructor returns hands back an unusable placeholder rather than a loaded catalog.
	 *
	 * A catalog that failed to load is also an unusable placeholder, and waiting out the full timeout on one turns an
	 * instant, self-describing failure into a quarter-hour of silence - so the `CORRUPTED` state is checked for
	 * explicitly and reported with the exception that caused it.
	 *
	 * @param evita       the booted instance
	 * @param catalogName catalog to wait for
	 * @return the loaded catalog
	 */
	@Nonnull
	private static Catalog awaitLoaded(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
		while (System.nanoTime() < deadline) {
			final CatalogContract candidate = evita.getCatalogInstance(catalogName)
				.orElseThrow(() -> new IllegalArgumentException("Catalog `" + catalogName + "` not found!"));
			if (candidate instanceof final Catalog loaded) {
				return loaded;
			}
			if (candidate instanceof final UnusableCatalog unusable
				&& unusable.getCatalogState() == CatalogState.CORRUPTED) {
				throw new IllegalStateException(
					"Catalog `" + catalogName + "` failed to load and is CORRUPTED - if the cause mentions " +
						"compression, the snapshot was written with `" + COMPRESS_PROPERTY + "` enabled.",
					unusable.getRepresentativeException()
				);
			}
			try {
				Thread.sleep(500L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog `" + catalogName + "`!", e);
			}
		}
		throw new IllegalStateException(
			"Catalog `" + catalogName + "` did not become usable within the load timeout!"
		);
	}

	/**
	 * Reads a required system property.
	 *
	 * @param propertyName name of the property
	 * @return its value
	 */
	@Nonnull
	private static String requiredProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set - see " +
					TrigramReplicationCensus.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Parses a comma-separated allow-list property into a set, treating an unset property as "no restriction".
	 *
	 * @param propertyName name of the property
	 * @return the parsed entries, empty when the property is unset
	 */
	@Nonnull
	private static Set<String> parseListProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		final Set<String> entries = new TreeSet<>();
		final String[] parts = value.split(",");
		for (int i = 0; i < parts.length; i++) {
			final String trimmed = parts[i].trim();
			if (!trimmed.isEmpty()) {
				entries.add(trimmed);
			}
		}
		return entries;
	}

	/**
	 * Renders a byte count in the largest unit that keeps it above one.
	 *
	 * @param value the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String bytes(long value) {
		if (value < 1024L) {
			return value + " B";
		} else if (value < 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f KB", value / 1024.0);
		} else if (value < 1024L * 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0));
		} else {
			return String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0));
		}
	}

	/**
	 * Renders a share as a percentage, reporting an undefined share as `-` rather than as zero.
	 *
	 * @param part  the numerator
	 * @param whole the denominator
	 * @return the rendered share
	 */
	@Nonnull
	private static String percent(long part, long whole) {
		if (whole == 0L) {
			return "-";
		}
		return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
	}

	/* ========================================== state ============================================ */

	/**
	 * One index's readings, assembled once and then folded into several {@link Tally} instances.
	 *
	 * A mutable carrier rather than a record: the sub-index families are walked one after another and each adds to
	 * several of these fields, so an immutable value would mean threading a dozen locals through four methods.
	 */
	private static final class Reading {
		/** How many indexes this reading describes - always one, kept so {@link Tally#add} needs no special case. */
		private int indexCount;
		/** What {@link EntityIndex#getHeapSizeInBytes()} reported. */
		private long entityIndexBytes;
		/** What {@link AttributeIndex#getHeapSizeInBytes()} reported, a part of {@link #entityIndexBytes}. */
		private long attributeIndexBytes;
		/** How many entities the index covers. */
		private long entityCount;
		/** How many distinct attribute keys carry a filter index. */
		private int filterKeyCount;
		/** The filter family, including the shared value trees and range companions its views read. */
		private long filterBytes;
		/** The front-coded value trees alone - the payload §34.3 proposes to stop replicating. */
		private long filterTreeBytes;
		/** The unique family, owner and folded-view variants together. */
		private long uniqueBytes;
		/** The sort family. */
		private long sortBytes;
		/** The owner-mode part of {@link #sortBytes} - the private value trees of §34.1. */
		private long sortOwnedBytes;
		/** The chain family. */
		private long chainBytes;
	}

	/**
	 * A running total over many indexes, keyed in the census by index kind or by scope.
	 */
	private static final class Tally {
		/** How many indexes were folded in. */
		private int indexCount;
		/** Sum of {@link Reading#entityIndexBytes}. */
		private long entityIndexBytes;
		/** Sum of {@link Reading#attributeIndexBytes}. */
		private long attributeIndexBytes;
		/** Sum of {@link Reading#entityCount}. */
		private long entityCount;
		/** Sum of {@link Reading#filterKeyCount}. */
		private int filterKeyCount;
		/** Sum of {@link Reading#filterBytes}. */
		private long filterBytes;
		/** Sum of {@link Reading#filterTreeBytes}. */
		private long filterTreeBytes;
		/** Sum of {@link Reading#uniqueBytes}. */
		private long uniqueBytes;
		/** Sum of {@link Reading#sortBytes}. */
		private long sortBytes;
		/** Sum of {@link Reading#sortOwnedBytes}. */
		private long sortOwnedBytes;
		/** Sum of {@link Reading#chainBytes}. */
		private long chainBytes;

		/**
		 * Folds one index's readings in.
		 *
		 * @param reading the readings to add
		 */
		void add(@Nonnull Reading reading) {
			this.indexCount += reading.indexCount;
			this.entityIndexBytes += reading.entityIndexBytes;
			this.attributeIndexBytes += reading.attributeIndexBytes;
			this.entityCount += reading.entityCount;
			this.filterKeyCount += reading.filterKeyCount;
			this.filterBytes += reading.filterBytes;
			this.filterTreeBytes += reading.filterTreeBytes;
			this.uniqueBytes += reading.uniqueBytes;
			this.sortBytes += reading.sortBytes;
			this.sortOwnedBytes += reading.sortOwnedBytes;
			this.chainBytes += reading.chainBytes;
		}

		/**
		 * Folds another tally in, so a set of per-kind tallies can be totalled without re-walking any index.
		 *
		 * @param other the tally to add
		 */
		void addAll(@Nonnull Tally other) {
			this.indexCount += other.indexCount;
			this.entityIndexBytes += other.entityIndexBytes;
			this.attributeIndexBytes += other.attributeIndexBytes;
			this.entityCount += other.entityCount;
			this.filterKeyCount += other.filterKeyCount;
			this.filterBytes += other.filterBytes;
			this.filterTreeBytes += other.filterTreeBytes;
			this.uniqueBytes += other.uniqueBytes;
			this.sortBytes += other.sortBytes;
			this.sortOwnedBytes += other.sortOwnedBytes;
			this.chainBytes += other.chainBytes;
		}

		/**
		 * Totals a group of tallies into a fresh one.
		 *
		 * @param tallies the tallies to total
		 * @return their sum
		 */
		@Nonnull
		static Tally sum(@Nonnull Iterable<Tally> tallies) {
			final Tally total = new Tally();
			for (final Tally tally : tallies) {
				total.addAll(tally);
			}
			return total;
		}
	}

	/**
	 * One collection's tallies, held until the whole catalog has been walked so the per-collection table prints
	 * after the progress lines rather than between them.
	 *
	 * @param entityType the collection's entity type
	 * @param byType     its tallies, keyed by index kind
	 */
	private record CollectionReading(
		@Nonnull String entityType,
		@Nonnull Map<EntityIndexType, Tally> byType
	) {
	}

}
