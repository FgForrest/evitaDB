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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spike.trigram.ValueDedupCensus.DomainKind;
import io.evitadb.spike.trigram.ValueDedupCensus.Lever;
import io.evitadb.utils.VMLayout;
import org.apache.commons.io.FileUtils;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * **P3 of the catalog-wide value-ids line - the representation spike.** {@link ValueDedupCensus} produced a ledger
 * whose *measured* half (what a reduced value tree costs today) was cross-checked twice, and whose *projected* half
 * (what the candidate representation would cost instead) had never met a real object graph. This spike builds the
 * candidate representations out of the contents of real reduced value trees and weighs each one with JOL, so the
 * projection can be confirmed, corrected, or thrown away.
 *
 * It answers two questions the census left open:
 *
 * 1. **Is `ValueDedupCensus#candidateSpineOf` trustworthy?** The same method is called here as the model under test -
 *    not a re-implementation - and its output is compared against the deep-retained size of a structure really
 *    allocated from a real tree's buckets. The error is reported per stratum and per lever, with its sign, because a
 *    projection that is 5% optimistic on the `K = 1` stratum moves 63% of the dictionary prize.
 * 2. **What is the dictionary lever worth *over* a plain container that keeps the strings where they are?** The census
 *    could not isolate this and said so. Here the same trees are built both ways - `V1` with 4-byte value ids and `V3`
 *    with one exactly-allocated front-coded string column - and the difference is the entire justification for the
 *    canonical-owner apparatus.
 *
 * # The five variants
 *
 * Every variant is built from the **same** live tree's buckets, in tree order, and every one of them references the
 * **same live `TransactionalBitmap` instances** for multi-record buckets.
 *
 * | variant | what it is | applies to |
 * |---|---|---|
 * | `V0` | the live {@link InvertedIndex} exactly as the catalog holds it | every sampled tree |
 * | `V1` | dictionary container: `int[K]` ids + `int[K]` postings (+ overflow, + sort slots) | every sampled tree |
 * | `V2` | container-primitive: the primitive key column in place of the ids | primitive-keyed domains |
 * | `V3` | container-strings: one front-coded string column in place of the ids | `String`-keyed domains |
 * | `V4` | {@link TransactionalLongBPlusTree} keyed by value id | every sampled tree |
 *
 * `V1` and `V2` are bare arrays inside one small container object, which is literally the shape
 * `ValueDedupCensus#candidateSpineOf` prices. `V3` has no bare-array form - the front-coded column *is* the
 * representation - so it additionally pays that column's own object header, and the tables say so.
 *
 * A temporal domain decomposes into a `(long[], int[])` parallel pair and therefore needs a container with **five**
 * reference fields where every other variant needs four. The census model charges one array header and one reference
 * slot fewer for that case; the container-lever error table is split by key-width family so the discrepancy is visible
 * rather than averaged away.
 *
 * # How the bytes are counted, and where the walk stops
 *
 * Sizes are deep-retained, walked by this class rather than by `GraphLayout`, so the stop set is explicit and provably
 * identical on every side of every comparison. Each reached object is charged
 * {@link VirtualMachine#sizeOf(Object)} once (identity-deduplicated) and its reference fields are pushed.
 *
 * **The walk stops at {@link Bitmap}.** A record bitmap of a multi-record bucket survives every candidate
 * representation untouched, is shared by reference between all five variants, and is already priced by the census as
 * `bitmapBytes` on both sides of its ledger. Charging it here would add the same constant to every column and shrink
 * every ratio toward one. So no variant includes bitmap bytes, and the `V0` column is therefore comparable to the
 * census's `removable = treeBytes - bitmapBytes`, not to `treeBytes`.
 *
 * The walk also stops at objects that are shared catalog-wide rather than owned by one tree - {@link Class},
 * {@link ClassLoader}, {@link Comparator}, {@link Function}, {@link Locale}, {@link Collator}, {@link Charset} - and
 * skips the handful of fields `InvertedIndex#getHeapSizeInBytes` deliberately charges as a slot only
 * ({@link #SLOT_ONLY_FIELDS}). That makes `V0` comparable to the engine's own accounting, which is the point of
 * measuring it: both figures are printed side by side, and their disagreement is a finding in itself.
 *
 * # Sampling
 *
 * The catalog walk is the census's, index for index. Every reduced value tree it measures is offered to a reservoir
 * keyed by `(domain, K-stratum)`, so the sample spans every stratum of every domain instead of being dominated by the
 * one domain that happens to hold the most trees. Reservoir sampling is uniform within a cell and seeded from a
 * constant, so two runs over the same snapshot pick the same trees. When the cells together hold more than
 * `evita.trigram.sampleLimit` trees, each cell is cut proportionally to a prefix of its (already uniform) reservoir.
 *
 * # Configuration
 *
 * {@link ValueDedupCensus}'s properties, so one command line serves both, plus two of its own:
 *
 * | Property | Meaning |
 * |---|---|
 * | `evita.trigram.catalogName` | **required** - the catalog to walk |
 * | `evita.trigram.dataDir` | **required** - storage directory containing a `<catalogName>/` subfolder |
 * | `evita.trigram.workDir` / `evita.trigram.reportDir` | working copy / TSV location |
 * | `evita.trigram.copyData` / `evita.trigram.compress` / `evita.trigram.entityTypes` | as in the census |
 * | `evita.trigram.sampleLimit` | how many trees to weigh in total (default {@link #DEFAULT_SAMPLE_LIMIT}) |
 * | `evita.trigram.perCellLimit` | reservoir depth per cell (default {@link #DEFAULT_PER_CELL_LIMIT}) |
 *
 * # Running it
 *
 * ```shell
 * java -Xmx16g -Djol.magicFieldOffset=true \
 *   --add-opens java.base/java.lang=ALL-UNNAMED \
 *   --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
 *   --add-opens java.base/java.math=ALL-UNNAMED \
 *   --add-opens java.base/java.util=ALL-UNNAMED \
 *   -Devita.trigram.catalogName=evita \
 *   -Devita.trigram.dataDir=/path/to/snapshot \
 *   -Devita.trigram.copyData=false \
 *   -Devita.trigram.workDir=/path/to/work -Devita.trigram.reportDir=/path/to/work \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.ValueDedupRepresentationSpike
 * ```
 *
 * Keep `-Xmx` **below 32 GB**, exactly as the census does: above it the VM drops compressed oops, references widen to
 * eight bytes and neither the projections nor the measurements are comparable to the census runs. The banner prints
 * both {@link VMLayout} and JOL's own view of the layout so that assumption is checked rather than trusted.
 *
 * @author Claude (catalog-wide value ids spike), FG Forrest a.s. (c) 2026
 */
public class ValueDedupRepresentationSpike {

	/**
	 * System property capping how many trees are weighed in total.
	 */
	public static final String SAMPLE_LIMIT_PROPERTY = "evita.trigram.sampleLimit";

	/**
	 * System property setting the reservoir depth of one `(domain, stratum)` cell.
	 */
	public static final String PER_CELL_LIMIT_PROPERTY = "evita.trigram.perCellLimit";

	/**
	 * Default total sample size.
	 */
	private static final int DEFAULT_SAMPLE_LIMIT = 2000;

	/**
	 * Default reservoir depth per `(domain, stratum)` cell.
	 */
	private static final int DEFAULT_PER_CELL_LIMIT = 16;

	/**
	 * Seed of the reservoir sampler. Constant so two runs over one snapshot weigh the same trees.
	 */
	private static final long SAMPLE_SEED = 0x5EED_1454L;

	/**
	 * Leaf block size of the `V4` B+ tree. Deliberately the {@link TransactionalLongBPlusTree} default (64) rather
	 * than the inverted index's own 256: the smaller leaf is the *favourable* setting for the rejected alternative,
	 * because a tiny tree wastes a quarter as much allocated-but-unused leaf capacity. If the alternative loses at 64
	 * it loses at 256 by a wider margin, so the steelman is the honest configuration to report.
	 */
	private static final int V4_BLOCK_SIZE = 64;

	/**
	 * Minimum leaf occupancy of the `V4` tree, and the internal-node block sizes that go with it. Spelled out rather
	 * than taken from the `(blockSize, valueType)` convenience constructor, which derives `blockSize / 2` and trips
	 * the tree's own "minimum block size must be less than half the block size" premise.
	 */
	private static final int V4_MIN_BLOCK_SIZE = V4_BLOCK_SIZE / 2 - 1;

	/**
	 * Minimum internal-node occupancy of the `V4` tree, mirroring the tree's own default derivation.
	 */
	private static final int V4_MIN_INTERNAL_BLOCK_SIZE = (int) (Math.ceil((float) V4_MIN_BLOCK_SIZE / 2.0) - 1);

	/**
	 * How long the spike waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * How often the index walk reports progress.
	 */
	private static final int PROGRESS_LOG_INTERVAL = 25_000;

	/**
	 * Sentinel for a variant that does not apply to a domain - a primitive column on a string domain, a front-coded
	 * column on an `Integer` domain. Never zero: a zero would average into the tables as a free representation.
	 */
	private static final long NOT_APPLICABLE = -1L;

	/**
	 * Fields whose target {@link InvertedIndex#getHeapSizeInBytes()} charges as a reference slot only, because the
	 * object behind them is shared with the owning index family or is derived bookkeeping rebuilt on load. The walk
	 * skips them for the same reason, so `V0` stays comparable to the engine's own figure.
	 */
	private static final Set<String> SLOT_ONLY_FIELDS = Set.of(
		"normalizer", "comparator", "plainType", "keyType", "pageStreamRegistry", "valueIdConsumers",
		"valueIdDirectory", "valueColumnFactory", "recordColumnFactory", "valueIdMinter"
	);

	/**
	 * Upper bucket-count bound of each stratum band; mirrors {@link ValueDedupCensus#STRATA_UPPER_BOUNDS} by reference
	 * rather than by copy, so the two tools can never drift into incomparable bands.
	 */
	private static final int[] STRATA_UPPER_BOUNDS = ValueDedupCensus.STRATA_UPPER_BOUNDS;

	/**
	 * Labels of the strata bands, shared with the census for the same reason as the bounds.
	 */
	private static final String[] STRATA_LABELS = ValueDedupCensus.STRATA_LABELS;

	/**
	 * The `attributeIndex` field of {@link EntityIndex}, opened once - the same read the census makes, for the same
	 * reason: no accessor exposes the sub-index family and the spike may not edit the engine.
	 */
	private static final Field ATTRIBUTE_INDEX_FIELD = openField(EntityIndex.class, "attributeIndex");

	/**
	 * The `ownedTree` field of {@link OwnerSortIndex} - the private value tree of a sort-only attribute or compound.
	 */
	private static final Field OWNED_TREE_FIELD = openField(OwnerSortIndex.class, "ownedTree");

	/**
	 * `io.evitadb.index.bPlusTree.ValueColumnFactory`, reached by name because its `create` method returns the
	 * package-private `ValueColumn` and therefore cannot be named from this package at all.
	 */
	private static final Class<?> VALUE_COLUMN_FACTORY_CLASS =
		openClass("io.evitadb.index.bPlusTree.ValueColumnFactory");

	/**
	 * The factory the engine itself selects for a `String` key - a front-coded column, whichever comparator the tree
	 * carries. Obtained through `ValueColumnFactory#forKey` rather than by constructing the column directly, so `V3`
	 * measures the representation the engine would really pick.
	 */
	private static final Object STRING_COLUMN_FACTORY = stringColumnFactory();

	/**
	 * `ValueColumnFactory#create(int)`, opened once.
	 */
	private static final Method COLUMN_CREATE_METHOD = openMethod(VALUE_COLUMN_FACTORY_CLASS, "create", int.class);

	/**
	 * JOL's view of the running VM, used for the shallow size of every walked object.
	 */
	private static final VirtualMachine JOL_VM = VM.current();

	/**
	 * Per-class cache of the reference fields the walk descends through.
	 */
	private static final Map<Class<?>, WalkField[]> REFERENCE_FIELDS = new HashMap<>(512);

	/**
	 * `bulkLoad` / `getHeapSizeInBytes` of the front-coded column, resolved from the first instance the factory hands
	 * back and cached; the declaring class is package-private, so the methods cannot be named at compile time.
	 */
	private static Method columnBulkLoadMethod;

	/**
	 * See {@link #columnBulkLoadMethod}.
	 */
	private static Method columnHeapSizeMethod;

	/**
	 * How many fields neither reflection nor JOL's Unsafe offset could read, across the whole run. Each one is warned
	 * about at the moment it is discovered and counted here as well: an unreadable field truncates the walk, and a
	 * silent truncation looks exactly like a representation win.
	 */
	private static long inaccessibleFieldCount;

	/**
	 * Entry point. Any failure exits the JVM explicitly, because a partially constructed Evita instance has already
	 * started non-daemon threads and would otherwise hold the storage folder locks for ever.
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
	 * Boots against the snapshot, samples the reduced value trees, builds and weighs every variant, then prints the
	 * tables and writes the TSV.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(ValueDedupCensus.CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(ValueDedupCensus.DATA_DIR_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(
			System.getProperty(ValueDedupCensus.COPY_DATA_PROPERTY, "true")
		);
		final boolean compress = Boolean.parseBoolean(
			System.getProperty(ValueDedupCensus.COMPRESS_PROPERTY, "true")
		);
		final Set<String> entityTypeFilter = parseListProperty(ValueDedupCensus.ENTITY_TYPES_PROPERTY);
		final int sampleLimit = intProperty(SAMPLE_LIMIT_PROPERTY, DEFAULT_SAMPLE_LIMIT);
		final int perCellLimit = intProperty(PER_CELL_LIMIT_PROPERTY, DEFAULT_PER_CELL_LIMIT);

		final Path workDir = resolveWorkDir();
		final Path storageDir = copyData ? copyCatalog(dataDir, workDir, catalogName) : dataDir;
		final Path reportDir = resolveReportDir(workDir);

		System.out.printf("Value dedup REPRESENTATION spike - catalog `%s` from `%s`%n", catalogName, storageDir);
		printLayoutBanner(sampleLimit, perCellLimit);

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

			final List<SampledTree> samples = collectSamples(catalog, entityTypeFilter, sampleLimit, perCellLimit);
			final List<Reading> readings = measureAll(samples);

			printSampleTable(readings);
			printBaselineTable(readings);
			printProjectionErrorTable(readings, Lever.DICTIONARY);
			printProjectionErrorTable(readings, Lever.CONTAINER_ONLY);
			printContainerFamilyTable(readings);
			printDictionaryMarginalTable(readings);
			printBPlusTreePenaltyTable(readings);
			printFooter();
			writeTsv(readings, catalogName, reportDir);
		}
	}

	/* ========================================== sampling ========================================= */

	/**
	 * Walks every selected collection exactly as {@link ValueDedupCensus} does and offers each reduced value tree to
	 * the reservoir of its `(domain, stratum)` cell.
	 *
	 * The census's owner-resolution pass is deliberately **not** repeated: this spike prices representations, and the
	 * canonical owner contributes only the per-domain host increment, which the census already measured on every tree
	 * of every domain rather than on a sample.
	 *
	 * @param catalog          the loaded catalog
	 * @param entityTypeFilter collections to visit; empty means every collection
	 * @param sampleLimit      total number of trees to keep
	 * @param perCellLimit     reservoir depth of one cell
	 * @return the sampled trees, in walk order
	 */
	@Nonnull
	private static List<SampledTree> collectSamples(
		@Nonnull Catalog catalog,
		@Nonnull Set<String> entityTypeFilter,
		int sampleLimit,
		int perCellLimit
	) {
		final Map<String, SampleCell> cells = new LinkedHashMap<>(512);
		final Random random = new Random(SAMPLE_SEED);
		long treesSeen = 0L;

		final Set<String> entityTypes = new TreeSet<>(catalog.getEntityTypes());
		for (final String entityType : entityTypes) {
			if (!entityTypeFilter.isEmpty() && !entityTypeFilter.contains(entityType)) {
				continue;
			}
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			System.out.printf("  %-28s walking %,d indexes...%n", entityType, collection.getIndexCount());
			final long start = System.nanoTime();
			final long before = treesSeen;
			treesSeen += walkCollection(collection, cells, random, perCellLimit);
			System.out.printf(
				"  %-28s done in %,d ms - %,d trees offered.%n",
				entityType, (System.nanoTime() - start) / 1_000_000, treesSeen - before
			);
		}

		final List<SampledTree> samples = downsample(cells, sampleLimit);
		System.out.printf(
			"%nSampled %,d trees out of %,d offered, across %,d (domain, stratum) cells.%n",
			samples.size(), treesSeen, cells.size()
		);
		return samples;
	}

	/**
	 * Walks one collection's reduced indexes and offers every value tree they hold.
	 *
	 * @param collection   the collection to walk
	 * @param cells        the reservoirs being filled
	 * @param random       the shared, seeded sampler
	 * @param perCellLimit reservoir depth of one cell
	 * @return how many trees were offered
	 */
	private static long walkCollection(
		@Nonnull EntityCollection collection,
		@Nonnull Map<String, SampleCell> cells,
		@Nonnull Random random,
		int perCellLimit
	) {
		final long[] offered = new long[1];
		browse(
			collection,
			(index, row) -> {
				final EntityIndexType indexType = indexTypeOf(collection, row);
				switch (indexType) {
					case REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY ->
						offered[0] += offerReducedIndex(collection, index, cells, random, perCellLimit);
					// the owner candidates hold the values once and are not what the fan-out lever reduces
					case GLOBAL, REFERENCED_ENTITY_TYPE, REFERENCED_GROUP_ENTITY_TYPE -> {
					}
				}
			}
		);
		return offered[0];
	}

	/**
	 * Offers every value tree of one reduced index: its filter trees, and the owned tree of every
	 * {@link OwnerSortIndex}. A {@link SortIndexView} is skipped for the census's reason - the tree beneath it is the
	 * filter domain's and would be weighed twice.
	 *
	 * @param collection   the owning collection
	 * @param index        the live reduced index
	 * @param cells        the reservoirs being filled
	 * @param random       the shared, seeded sampler
	 * @param perCellLimit reservoir depth of one cell
	 * @return how many trees were offered
	 */
	private static long offerReducedIndex(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndex index,
		@Nonnull Map<String, SampleCell> cells,
		@Nonnull Random random,
		int perCellLimit
	) {
		final AttributeIndex attributeIndex = attributeIndexOf(index);
		final Scope scope = index.getIndexKey().scope();
		final String entityType = collection.getEntityType();
		long offered = 0L;

		for (final AttributeIndexKey key : attributeIndex.getFilterIndexes()) {
			final FilterIndex filterIndex = attributeIndex.getFilterIndex(key);
			if (filterIndex == null) {
				throw new GenericEvitaInternalError(
					"Filter index key `" + key + "` resolves to no filter index!",
					"Filter index key resolves to no filter index!"
				);
			}
			final boolean sortable = attributeIndex.getSortIndex(key) instanceof SortIndexView;
			offered += offerTree(
				entityType, scope, key, DomainKind.FILTER, filterIndex.getInvertedIndex(), sortable,
				cells, random, perCellLimit
			);
		}

		for (final AttributeIndexKey key : attributeIndex.getSortIndexes()) {
			final SortIndex sortIndex = attributeIndex.getSortIndex(key);
			if (sortIndex == null) {
				throw new GenericEvitaInternalError(
					"Sort index key `" + key + "` resolves to no sort index!",
					"Sort index key resolves to no sort index!"
				);
			}
			if (!(sortIndex instanceof final OwnerSortIndex ownerSortIndex)) {
				continue;
			}
			final InvertedIndex tree = ownedTreeOf(ownerSortIndex);
			final Serializable sample = firstValueOf(tree);
			final DomainKind kind = sample instanceof ComparableArray ? DomainKind.COMPOUND : DomainKind.OWNER_SORT;
			offered += offerTree(entityType, scope, key, kind, tree, true, cells, random, perCellLimit);
		}
		return offered;
	}

	/**
	 * Measures the census's per-tree figures for one value tree and offers it to its cell's reservoir.
	 *
	 * The bucket array is materialized once and walked once, which is also where the record bitmaps of multi-record
	 * buckets are priced - the quantity the JOL walk deliberately excludes from every variant.
	 *
	 * @param entityType   the owning collection
	 * @param scope        the owning index's scope
	 * @param key          the attribute key
	 * @param kind         which structure the tree comes from
	 * @param tree         the tree to offer
	 * @param sortable     whether the tree also serves ordering
	 * @param cells        the reservoirs being filled
	 * @param random       the shared, seeded sampler
	 * @param perCellLimit reservoir depth of one cell
	 * @return `1` when the tree was offered, `0` when it held no buckets
	 */
	private static long offerTree(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull AttributeIndexKey key,
		@Nonnull DomainKind kind,
		@Nonnull InvertedIndex tree,
		boolean sortable,
		@Nonnull Map<String, SampleCell> cells,
		@Nonnull Random random,
		int perCellLimit
	) {
		final ValueToRecordBitmap[] buckets = tree.getValueToRecordBitmap();
		final int bucketCount = buckets.length;
		if (bucketCount == 0) {
			return 0L;
		}
		final Serializable sample = buckets[0].getValue();
		long records = 0L;
		long bitmapBytes = 0L;
		int multiCount = 0;
		for (int i = 0; i < bucketCount; i++) {
			final int cardinality = buckets[i].size();
			records += cardinality;
			if (cardinality > 1) {
				multiCount++;
				bitmapBytes += buckets[i].getRecordIds().getHeapSizeInBytes();
			}
		}

		final int containerKeyBytes = ValueDedupCensus.containerKeyBytesOf(sample);
		final boolean dictionaryEligible = sample instanceof String || sample instanceof ComparableArray;
		final Lever lever = dictionaryEligible
			? Lever.DICTIONARY
			: (containerKeyBytes > 0 ? Lever.CONTAINER_ONLY : Lever.NONE);
		final int stratum = stratumOf(bucketCount);
		final SampledTree candidate = new SampledTree(
			tree, domainLabelOf(entityType, key, scope, kind), entityType, key, scope, kind, lever,
			sample.getClass().getSimpleName(), containerKeyBytes, sample instanceof String,
			bucketCount, records, multiCount, sortable, stratum, tree.getHeapSizeInBytes(), bitmapBytes
		);
		cells
			.computeIfAbsent(candidate.domainLabel() + '#' + stratum, cellKey -> new SampleCell(perCellLimit))
			.offer(candidate, random);
		return 1L;
	}

	/**
	 * Cuts the filled reservoirs down to the total sample budget, proportionally per cell and never below one tree
	 * per cell, so a stratum that exists at all is still represented.
	 *
	 * Taking a prefix of a reservoir is legitimate here: reservoir sampling leaves the retained set a uniform sample
	 * of its cell, and the retained set carries no order that correlates with tree size.
	 *
	 * @param cells       the filled reservoirs
	 * @param sampleLimit total sample budget
	 * @return the trees to weigh
	 */
	@Nonnull
	private static List<SampledTree> downsample(@Nonnull Map<String, SampleCell> cells, int sampleLimit) {
		int retained = 0;
		for (final SampleCell cell : cells.values()) {
			retained += cell.retained();
		}
		final List<SampledTree> samples = new ArrayList<>(Math.min(retained, sampleLimit) + cells.size());
		if (retained <= sampleLimit) {
			for (final SampleCell cell : cells.values()) {
				cell.drainInto(samples, cell.retained());
			}
			return samples;
		}
		for (final SampleCell cell : cells.values()) {
			final int keep = Math.max(1, (int) Math.floor((double) sampleLimit * cell.retained() / retained));
			cell.drainInto(samples, Math.min(keep, cell.retained()));
		}
		return samples;
	}

	/**
	 * Maps a bucket count onto its stratum band.
	 *
	 * @param buckets the tree's bucket count
	 * @return the band index
	 */
	private static int stratumOf(int buckets) {
		for (int band = 0; band < STRATA_UPPER_BOUNDS.length; band++) {
			if (buckets <= STRATA_UPPER_BOUNDS[band]) {
				return band;
			}
		}
		throw new GenericEvitaInternalError(
			"Bucket count " + buckets + " falls in no stratum - the last band is open-ended and cannot be missed!",
			"Bucket count falls in no stratum!"
		);
	}

	/* ========================================= measuring ========================================= */

	/**
	 * Builds and weighs every applicable variant of every sampled tree.
	 *
	 * @param samples the sampled trees
	 * @return one reading per sampled tree
	 */
	@Nonnull
	private static List<Reading> measureAll(@Nonnull List<SampledTree> samples) {
		final List<Reading> readings = new ArrayList<>(samples.size());
		final long start = System.nanoTime();
		for (int i = 0; i < samples.size(); i++) {
			readings.add(measure(samples.get(i), i == 0));
			if ((i + 1) % 250 == 0) {
				System.out.printf("      weighed %,d / %,d trees%n", i + 1, samples.size());
			}
		}
		System.out.printf(
			"Weighed %,d trees in %,d ms; %,d fields were unreadable during the walk.%n%n",
			readings.size(), (System.nanoTime() - start) / 1_000_000, inaccessibleFieldCount
		);
		return readings;
	}

	/**
	 * Builds the variants of one sampled tree and weighs each of them.
	 *
	 * @param sample       the tree to weigh
	 * @param printHistogram whether to print the per-class breakdown of the `V0` walk, done once per run as evidence
	 *                       that the stop set really bounds the walk
	 * @return the reading
	 */
	@Nonnull
	private static Reading measure(@Nonnull SampledTree sample, boolean printHistogram) {
		final InvertedIndex tree = sample.tree();
		final ValueToRecordBitmap[] buckets = tree.getValueToRecordBitmap();
		final int bucketCount = buckets.length;
		final int[] postings = new int[bucketCount];
		final TransactionalBitmap[] overflow = sample.multiCount() > 0 ? new TransactionalBitmap[bucketCount] : null;
		for (int i = 0; i < bucketCount; i++) {
			final ValueToRecordBitmap bucket = buckets[i];
			if (bucket.size() > 1) {
				// the SAME live instance every variant shares; the walk stops here, so it is charged by none of them
				overflow[i] = bucket.getRecordIds();
			} else {
				postings[i] = bucket.getRecordIds().getFirst();
			}
		}
		final int[] sortSlots = sample.sortable() ? new int[bucketCount] : null;
		final int[] valueIds = new int[bucketCount];
		for (int i = 0; i < bucketCount; i++) {
			valueIds[i] = i;
		}

		final long jolV0 = printHistogram ? deepRetainedWithHistogram(tree) : deepRetained(tree);
		final long jolV1 = deepRetained(new ExactContainer(valueIds, postings, overflow, sortSlots));

		long jolV2 = NOT_APPLICABLE;
		if (sample.containerKeyBytes() > 0) {
			jolV2 = deepRetained(primitiveContainerOf(buckets, sample, postings, overflow, sortSlots));
		}

		long jolV3 = NOT_APPLICABLE;
		long frontCodedOwnBytes = NOT_APPLICABLE;
		if (sample.stringKeyed()) {
			final Object column = frontCodedColumnOf(buckets);
			frontCodedOwnBytes = columnHeapSize(column);
			jolV3 = deepRetained(new ExactContainer(column, postings, overflow, sortSlots));
		}

		final long jolV4 = deepRetained(bPlusTreeOf(buckets, postings, overflow));

		return new Reading(
			sample, jolV0, jolV1, jolV2, jolV3, jolV4, frontCodedOwnBytes,
			ValueDedupCensus.candidateSpineOf(bucketCount, sample.multiCount(), sample.sortable(), 0),
			sample.containerKeyBytes() > 0
				? ValueDedupCensus.candidateSpineOf(
					bucketCount, sample.multiCount(), sample.sortable(), sample.containerKeyBytes()
				)
				: NOT_APPLICABLE
		);
	}

	/**
	 * Builds the container-primitive variant: the same postings, overflow and sort slots, with the primitive key
	 * column the census's `containerKeyBytesOf` names in place of the value ids.
	 *
	 * @param buckets   the tree's buckets, in tree order
	 * @param sample    the sampled tree's identity
	 * @param postings  the shared postings column
	 * @param overflow  the shared overflow column, `null` when the tree has no multi-record bucket
	 * @param sortSlots the shared sort-slot column, `null` when the domain does not serve ordering
	 * @return the container object
	 */
	@Nonnull
	private static Object primitiveContainerOf(
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull SampledTree sample,
		@Nonnull int[] postings,
		@Nullable TransactionalBitmap[] overflow,
		@Nullable int[] sortSlots
	) {
		final int bucketCount = buckets.length;
		final int keyBytes = sample.containerKeyBytes();
		if (keyBytes == Long.BYTES) {
			final long[] keys = new long[bucketCount];
			for (int i = 0; i < bucketCount; i++) {
				keys[i] = longKeyOf(buckets[i].getValue());
			}
			return new ExactContainer(keys, postings, overflow, sortSlots);
		}
		if (keyBytes == Integer.BYTES) {
			final int[] keys = new int[bucketCount];
			for (int i = 0; i < bucketCount; i++) {
				keys[i] = intKeyOf(buckets[i].getValue());
			}
			return new ExactContainer(keys, postings, overflow, sortSlots);
		}
		throw new GenericEvitaInternalError(
			"Container key width " + keyBytes + " B has no column shape - `containerKeyBytesOf` returned a width " +
				"this spike cannot build!",
			"Container key width has no column shape!"
		);
	}

	/**
	 * Builds the container-strings variant's key column: one exactly-allocated front-coded column holding every
	 * bucket value of the tree, bulk-loaded in tree order so prefix sharing is exactly what a sorted run gives.
	 *
	 * @param buckets the tree's buckets, in tree order
	 * @return the front-coded column, as an opaque object (its type is package-private to the engine)
	 */
	@Nonnull
	private static Object frontCodedColumnOf(@Nonnull ValueToRecordBitmap[] buckets) {
		final int bucketCount = buckets.length;
		final Object[] keys = new Object[bucketCount];
		for (int i = 0; i < bucketCount; i++) {
			keys[i] = buckets[i].getValue();
		}
		try {
			final Object column = COLUMN_CREATE_METHOD.invoke(STRING_COLUMN_FACTORY, bucketCount);
			if (columnBulkLoadMethod == null) {
				columnBulkLoadMethod = openMethod(column.getClass(), "bulkLoad", Object[].class, int.class);
				columnHeapSizeMethod = openMethod(column.getClass(), "getHeapSizeInBytes");
			}
			columnBulkLoadMethod.invoke(column, keys, bucketCount);
			return column;
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot bulk-load a front-coded column of " + bucketCount + " keys!",
				"Cannot bulk-load a front-coded column!",
				e
			);
		}
	}

	/**
	 * Reads a front-coded column's own accounting, so the engine's figure and the JOL walk can be compared on a
	 * structure this spike allocated itself.
	 *
	 * @param column the column to read
	 * @return its self-reported heap footprint
	 */
	private static long columnHeapSize(@Nonnull Object column) {
		try {
			return (Long) columnHeapSizeMethod.invoke(column);
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot read a front-coded column's own heap size!",
				"Cannot read a front-coded column's own heap size!",
				e
			);
		}
	}

	/**
	 * Builds the `V4` variant: a {@link TransactionalLongBPlusTree} mapping a dense value id to its posting, which is
	 * the per-tiny-tree B+ alternative the design rejected in favour of an exact-sized array container.
	 *
	 * The value type is `Object`, holding a boxed `Integer` for a single-record bucket and the live
	 * {@link TransactionalBitmap} for a multi-record one - the same instances every other variant references. The
	 * boxes are a real cost of this alternative and are charged; a record id inside the JDK's `Integer` cache is
	 * charged once rather than once per bucket, which understates `V4` slightly on a catalog of tiny primary keys.
	 *
	 * @param buckets  the tree's buckets, in tree order
	 * @param postings the single-record postings already extracted
	 * @param overflow the multi-record bitmaps already extracted, `null` when the tree has none
	 * @return the populated tree
	 */
	@Nonnull
	private static Object bPlusTreeOf(
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull int[] postings,
		@Nullable TransactionalBitmap[] overflow
	) {
		final TransactionalLongBPlusTree<Object> tree = new TransactionalLongBPlusTree<>(
			V4_BLOCK_SIZE, V4_MIN_BLOCK_SIZE, V4_MIN_BLOCK_SIZE, V4_MIN_INTERNAL_BLOCK_SIZE, Object.class
		);
		for (int i = 0; i < buckets.length; i++) {
			final Object payload = overflow != null && overflow[i] != null
				? overflow[i]
				: Integer.valueOf(postings[i]);
			tree.insert(i, payload);
		}
		return tree;
	}

	/**
	 * Encodes a bucket value into the single `long` slot the engine's long key codec would give it.
	 *
	 * The encoding does not have to be the codec's own - a `long[]` costs the same whatever it holds - but a real
	 * encoding keeps the structure inspectable and makes an unexpected key type fail loudly instead of silently
	 * measuring zeroes.
	 *
	 * @param value the bucket value
	 * @return its long form
	 */
	private static long longKeyOf(@Nonnull Serializable value) {
		if (value instanceof final Boolean flag) {
			return flag ? 1L : 0L;
		}
		if (value instanceof final Character character) {
			return character;
		}
		if (value instanceof final Number number) {
			return number.longValue();
		}
		if (value instanceof final LocalDate date) {
			return date.toEpochDay();
		}
		if (value instanceof final LocalTime time) {
			return time.toNanoOfDay();
		}
		// the temporal types share the same single `long` slot: normalized to a millisecond-exact `Instant` and
		// stored as its epoch-milli
		if (value instanceof final Instant instant) {
			return instant.toEpochMilli();
		}
		if (value instanceof final OffsetDateTime offsetDateTime) {
			return offsetDateTime.toInstant().toEpochMilli();
		}
		if (value instanceof final LocalDateTime localDateTime) {
			return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
		}
		throw new GenericEvitaInternalError(
			"Value of type `" + value.getClass().getName() + "` was priced at 8 B by `containerKeyBytesOf` but this " +
				"spike has no long encoding for it!",
			"A long-column key type has no encoding in the representation spike!"
		);
	}

	/**
	 * Encodes a bucket value into the 4-byte slot the engine's `int` column would give it.
	 *
	 * @param value the bucket value
	 * @return its int form
	 */
	private static int intKeyOf(@Nonnull Serializable value) {
		if (value instanceof final BigDecimal decimal) {
			// the engine normalizes a BigDecimal to a scaled int long before the column sees it; the truncation here
			// is irrelevant to a footprint measurement and keeps the array inspectable
			return decimal.unscaledValue().intValue();
		}
		if (value instanceof final Number number) {
			return number.intValue();
		}
		throw new GenericEvitaInternalError(
			"Value of type `" + value.getClass().getName() + "` was priced at 4 B by `containerKeyBytesOf` but this " +
				"spike has no int encoding for it!",
			"An int-column key type has no encoding in the representation spike!"
		);
	}

	/* ========================================== the walk ========================================= */

	/**
	 * Deep-retained size of an object graph, charging every reached object once and stopping at the boundaries the
	 * class JavaDoc names.
	 *
	 * @param root the graph root
	 * @return the retained bytes
	 */
	private static long deepRetained(@Nullable Object root) {
		return walk(root, null);
	}

	/**
	 * Same as {@link #deepRetained}, additionally printing the per-class breakdown of the walk. Called once per run,
	 * on the first sampled tree, so the stop set can be audited from the log instead of taken on trust.
	 *
	 * @param root the graph root
	 * @return the retained bytes
	 */
	private static long deepRetainedWithHistogram(@Nullable Object root) {
		final Map<Class<?>, long[]> histogram = new HashMap<>(128);
		final long total = walk(root, histogram);
		final List<Map.Entry<Class<?>, long[]>> rows = new ArrayList<>(histogram.entrySet());
		rows.sort((left, right) -> Long.compare(right.getValue()[1], left.getValue()[1]));
		System.out.printf("%n=== WALK AUDIT (first sampled tree, V0) - total %s ===%n", bytes(total));
		System.out.printf("  %-58s %10s %14s%n", "class", "objects", "bytes");
		for (int i = 0; i < Math.min(14, rows.size()); i++) {
			final Map.Entry<Class<?>, long[]> row = rows.get(i);
			System.out.printf(
				"  %-58s %,10d %14s%n",
				trim(row.getKey().getName(), 58), row.getValue()[0], bytes(row.getValue()[1])
			);
		}
		System.out.println();
		return total;
	}

	/**
	 * The walk itself - an explicit stack rather than recursion, because a dense tree is thousands of nodes deep in
	 * reference terms and a recursive walk would overflow on the largest sampled trees.
	 *
	 * @param root      the graph root
	 * @param histogram optional per-class accumulator of `{objects, bytes}`
	 * @return the retained bytes
	 */
	private static long walk(@Nullable Object root, @Nullable Map<Class<?>, long[]> histogram) {
		if (root == null) {
			return 0L;
		}
		final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>(1024);
		final ArrayDeque<Object> pending = new ArrayDeque<>(1024);
		long total = 0L;
		push(pending, seen, root);
		while (!pending.isEmpty()) {
			final Object current = pending.pop();
			final long shallow = JOL_VM.sizeOf(current);
			total += shallow;
			final Class<?> type = current.getClass();
			if (histogram != null) {
				final long[] row = histogram.computeIfAbsent(type, ignored -> new long[2]);
				row[0]++;
				row[1] += shallow;
			}
			if (type.isArray()) {
				if (!type.getComponentType().isPrimitive()) {
					final Object[] elements = (Object[]) current;
					for (int i = 0; i < elements.length; i++) {
						push(pending, seen, elements[i]);
					}
				}
				continue;
			}
			final WalkField[] fields = referenceFieldsOf(type);
			for (int i = 0; i < fields.length; i++) {
				push(pending, seen, fields[i].read(current));
			}
		}
		return total;
	}

	/**
	 * Enqueues one reference unless it is null, already seen, or outside the walk's boundary.
	 *
	 * @param pending the walk stack
	 * @param seen    identity set of already-charged objects
	 * @param value   the reference to enqueue
	 */
	private static void push(
		@Nonnull ArrayDeque<Object> pending,
		@Nonnull IdentityHashMap<Object, Boolean> seen,
		@Nullable Object value
	) {
		if (value == null || isOpaque(value)) {
			return;
		}
		if (seen.put(value, Boolean.TRUE) == null) {
			pending.push(value);
		}
	}

	/**
	 * Whether an object is outside the walk's boundary - a record bitmap (shared by reference between every variant
	 * and priced separately by the census), or something the whole catalog shares rather than one tree owning it.
	 *
	 * @param value the object to classify
	 * @return `true` when the walk must neither charge nor descend into it
	 */
	private static boolean isOpaque(@Nonnull Object value) {
		return value instanceof Bitmap
			|| value instanceof Class<?>
			|| value instanceof ClassLoader
			|| value instanceof Comparator<?>
			|| value instanceof Function<?, ?>
			|| value instanceof Locale
			|| value instanceof Collator
			|| value instanceof Charset;
	}

	/**
	 * Returns the reference fields of a class and its supertypes, opened and cached. Fields naming a shared or
	 * derived structure ({@link #SLOT_ONLY_FIELDS}) are left out, matching what the engine's own heap accounting
	 * charges as a slot.
	 *
	 * A field that `setAccessible` refuses - anything in a `java.base` package this run did not `--add-opens` - is
	 * **not** dropped. Dropping one truncates the walk silently and reports a fraction of the real graph as if it
	 * were the whole of it, which is exactly how the first run of this spike measured a 248-byte `InvertedIndex`.
	 * Such a field is read through JOL's Unsafe field offset instead, and only a field neither route can read is
	 * counted as lost.
	 *
	 * @param type the class to reflect over
	 * @return its walkable reference fields
	 */
	@Nonnull
	private static WalkField[] referenceFieldsOf(@Nonnull Class<?> type) {
		final WalkField[] cached = REFERENCE_FIELDS.get(type);
		if (cached != null) {
			return cached;
		}
		final List<WalkField> collected = new ArrayList<>(16);
		Class<?> current = type;
		while (current != null && current != Object.class) {
			final Field[] declared = current.getDeclaredFields();
			for (int i = 0; i < declared.length; i++) {
				final Field field = declared[i];
				if (Modifier.isStatic(field.getModifiers())
					|| field.getType().isPrimitive()
					|| SLOT_ONLY_FIELDS.contains(field.getName())) {
					continue;
				}
				collected.add(walkFieldOf(field));
			}
			current = current.getSuperclass();
		}
		final WalkField[] fields = collected.toArray(new WalkField[0]);
		REFERENCE_FIELDS.put(type, fields);
		return fields;
	}

	/**
	 * Chooses how one field will be read: plain reflection where the package is open, JOL's Unsafe offset otherwise.
	 *
	 * @param field the field to prepare
	 * @return the prepared field
	 */
	@Nonnull
	private static WalkField walkFieldOf(@Nonnull Field field) {
		try {
			field.setAccessible(true);
			return new WalkField(field, true, 0L);
		} catch (final RuntimeException reflectionRefused) {
			try {
				return new WalkField(field, false, JOL_VM.fieldOffset(field));
			} catch (final RuntimeException unsafeRefused) {
				// neither route works - the field's target is lost from the walk, and the run says so out loud
				inaccessibleFieldCount++;
				System.out.printf(
					"  WARNING: `%s#%s` can be read by neither reflection nor JOL - its target is NOT counted.%n",
					field.getDeclaringClass().getName(), field.getName()
				);
				return new WalkField(field, false, WalkField.UNREADABLE);
			}
		}
	}

	/* ========================================= reporting ========================================= */

	/**
	 * Prints the layout banner - both evitaDB's own {@link VMLayout} and JOL's independent view of the same VM. The
	 * census's projections are only comparable to these measurements when both agree on a 4-byte reference.
	 *
	 * @param sampleLimit  the configured total sample budget
	 * @param perCellLimit the configured reservoir depth
	 */
	private static void printLayoutBanner(int sampleLimit, int perCellLimit) {
		System.out.printf("VM layout (evitaDB): %s%n", VMLayout.current());
		// `addressSize()` is the NATIVE pointer width and is 8 on every 64-bit VM whether or not compressed oops are
		// on, so it cannot answer the question the census's projections depend on. The width is measured instead, by
		// weighing two reference arrays that differ by exactly one slot.
		// the two probes are 16 slots apart, not one: at 8-byte alignment a single extra 4-byte slot disappears into
		// the padding of the shorter array and the difference reads as zero on a VM that really has 4-byte references
		final long emptyArray = JOL_VM.sizeOf(new Object[0]);
		final long referenceWidth = (JOL_VM.sizeOf(new Object[17]) - JOL_VM.sizeOf(new Object[1])) / 16L;
		System.out.printf(
			"VM layout (JOL):     objectHeader=%dB arrayHeader=%dB alignment=%dB; MEASURED reference=%dB, " +
				"empty Object[]=%dB, bare Object=%dB%n",
			JOL_VM.objectHeaderSize(), JOL_VM.arrayHeaderSize(), JOL_VM.objectAlignment(),
			referenceWidth, emptyArray, JOL_VM.sizeOf(new Object())
		);
		if (referenceWidth != VMLayout.current().referenceSize()) {
			throw new GenericEvitaInternalError(
				"JOL measures a " + referenceWidth + " B reference but `VMLayout` assumes " +
					VMLayout.current().referenceSize() + " B - the projections and the measurements would be priced " +
					"on two different VMs. Keep `-Xmx` below 32 GB so compressed oops stay on.",
				"JOL and VMLayout disagree on the reference width!"
			);
		}
		System.out.printf(
			"Sampling: total budget %,d trees, reservoir depth %,d per (domain, stratum) cell, seed 0x%X.%n",
			sampleLimit, perCellLimit, SAMPLE_SEED
		);
		System.out.println(
			"Walk: deep-retained, identity-deduplicated, STOPPING AT Bitmap - so no variant includes record-bitmap"
		);
		System.out.println(
			"      bytes and V0 is comparable to the census's `removable = treeBytes - bitmapBytes`, not to treeBytes."
		);
		System.out.println();
	}

	/**
	 * Prints how the sample is spread over levers and strata, so a thin cell is visible before its numbers are read.
	 *
	 * @param readings every reading of the run
	 */
	private static void printSampleTable(@Nonnull List<Reading> readings) {
		System.out.printf("%n=== SAMPLE SPREAD (trees weighed) ===%n");
		System.out.printf("  %-16s", "lever");
		for (int i = 0; i < STRATA_LABELS.length; i++) {
			System.out.printf(" %10s", "K " + STRATA_LABELS[i]);
		}
		System.out.printf(" %10s%n", "total");
		for (final Lever lever : Lever.values()) {
			final long[] counts = new long[STRATA_LABELS.length];
			long total = 0L;
			for (int i = 0; i < readings.size(); i++) {
				final SampledTree sample = readings.get(i).sample();
				if (sample.lever() == lever) {
					counts[sample.stratum()]++;
					total++;
				}
			}
			if (total == 0L) {
				continue;
			}
			System.out.printf("  %-16s", lever);
			for (int i = 0; i < counts.length; i++) {
				System.out.printf(" %,10d", counts[i]);
			}
			System.out.printf(" %,10d%n", total);
		}
	}

	/**
	 * Prints the `V0` alignment table: the engine's own figure for a live tree, the census's removable derivation of
	 * it, and the JOL walk of the same object graph.
	 *
	 * This is the cross-check the census could not make. Its whole ledger's *measured* side is
	 * `InvertedIndex#getHeapSizeInBytes`, and nothing until now has weighed the tree independently.
	 *
	 * @param readings every reading of the run
	 */
	private static void printBaselineTable(@Nonnull List<Reading> readings) {
		System.out.printf("%n=== V0 BASELINE - engine accounting vs JOL walk (bitmaps excluded on both sides) ===%n");
		System.out.printf(
			"  %-16s %-8s %8s %14s %14s %14s %14s %8s %8s %8s%n",
			"lever", "K", "trees", "engine tree", "bitmaps", "removable", "JOL V0", "p50 err", "p95 err", "max err"
		);
		for (final Lever lever : Lever.values()) {
			for (int stratum = 0; stratum < STRATA_LABELS.length; stratum++) {
				final ErrorSeries series = new ErrorSeries();
				long trees = 0L;
				long engineBytes = 0L;
				long bitmapBytes = 0L;
				long removable = 0L;
				long measured = 0L;
				for (int i = 0; i < readings.size(); i++) {
					final Reading reading = readings.get(i);
					if (reading.sample().lever() != lever || reading.sample().stratum() != stratum) {
						continue;
					}
					trees++;
					engineBytes += reading.sample().treeBytes();
					bitmapBytes += reading.sample().bitmapBytes();
					removable += reading.sample().removableBytes();
					measured += reading.jolV0();
					series.add(reading.sample().removableBytes(), reading.jolV0());
				}
				if (trees == 0L) {
					continue;
				}
				System.out.printf(
					"  %-16s %-8s %,8d %14s %14s %14s %14s %8s %8s %8s%n",
					lever, STRATA_LABELS[stratum], trees, bytes(engineBytes), bytes(bitmapBytes), bytes(removable),
					bytes(measured), series.percentile(0.50d), series.percentile(0.95d), series.max()
				);
			}
		}
		System.out.println(
			"  err = (census removable - JOL V0) / JOL V0; positive means the engine's accounting is the larger figure."
		);
	}

	/**
	 * Prints the projection-error table of one lever: the census's spine model against the deep-retained size of the
	 * structure that model describes, built out of a real tree.
	 *
	 * @param readings every reading of the run
	 * @param lever    the lever whose spine model is under test
	 */
	private static void printProjectionErrorTable(@Nonnull List<Reading> readings, @Nonnull Lever lever) {
		final String title = switch (lever) {
			case DICTIONARY -> "V1 - dictionary container (int[] ids + int[] postings)";
			case CONTAINER_ONLY -> "V2 - container-primitive (primitive key column in place)";
			case NONE -> throw new GenericEvitaInternalError(
				"Lever `NONE` prices no representation and cannot have a projection-error table!",
				"Lever NONE has no projection-error table!"
			);
		};
		System.out.printf("%n=== PROJECTION ERROR - %s ===%n", title);
		System.out.printf(
			"  %-8s %8s %14s %14s %14s %8s %8s %8s %8s%n",
			"K", "trees", "JOL V0", "projected", "measured", "p50 err", "p95 err", "min err", "max err"
		);
		final ErrorSeries all = new ErrorSeries();
		long allProjected = 0L;
		long allMeasured = 0L;
		for (int stratum = 0; stratum < STRATA_LABELS.length; stratum++) {
			final ErrorSeries series = new ErrorSeries();
			long trees = 0L;
			long baseline = 0L;
			long projected = 0L;
			long measured = 0L;
			for (int i = 0; i < readings.size(); i++) {
				final Reading reading = readings.get(i);
				if (reading.sample().stratum() != stratum) {
					continue;
				}
				final long readingProjected;
				final long readingMeasured;
				if (lever == Lever.DICTIONARY) {
					readingProjected = reading.projectedDictionarySpine();
					readingMeasured = reading.jolV1();
				} else {
					readingProjected = reading.projectedContainerSpine();
					readingMeasured = reading.jolV2();
				}
				if (readingProjected == NOT_APPLICABLE || readingMeasured == NOT_APPLICABLE) {
					continue;
				}
				trees++;
				baseline += reading.jolV0();
				projected += readingProjected;
				measured += readingMeasured;
				series.add(readingProjected, readingMeasured);
				all.add(readingProjected, readingMeasured);
			}
			if (trees == 0L) {
				continue;
			}
			allProjected += projected;
			allMeasured += measured;
			System.out.printf(
				"  %-8s %,8d %14s %14s %14s %8s %8s %8s %8s%n",
				STRATA_LABELS[stratum], trees, bytes(baseline), bytes(projected), bytes(measured),
				series.percentile(0.50d), series.percentile(0.95d), series.min(), series.max()
			);
		}
		System.out.printf(
			"  %-8s %8s %14s %14s %14s %8s%n",
			"TOTAL", "", "", bytes(allProjected), bytes(allMeasured),
			signedPercent(allProjected - allMeasured, allMeasured)
		);
		System.out.println(
			"  err = (projected - measured) / measured; positive means the census UNDER-states the saving."
		);
	}

	/**
	 * Prints the container-lever error split by key-width family. Two families remain — the scaled-`int` column and
	 * the single-`long` one, the latter now carrying the temporal keys too — and they are still reported separately
	 * because a 4-byte slot and an 8-byte slot amortize the container's fixed fields differently.
	 *
	 * @param readings every reading of the run
	 */
	private static void printContainerFamilyTable(@Nonnull List<Reading> readings) {
		System.out.printf("%n=== PROJECTION ERROR - V2 by key-width family ===%n");
		System.out.printf(
			"  %-26s %8s %14s %14s %8s %8s %8s%n",
			"family", "trees", "projected", "measured", "p50 err", "min err", "max err"
		);
		final int[] families = {Integer.BYTES, Long.BYTES};
		final String[] labels = {"int[] (4 B)", "long[] (8 B)"};
		for (int f = 0; f < families.length; f++) {
			final ErrorSeries series = new ErrorSeries();
			long trees = 0L;
			long projected = 0L;
			long measured = 0L;
			for (int i = 0; i < readings.size(); i++) {
				final Reading reading = readings.get(i);
				if (reading.sample().containerKeyBytes() != families[f] || reading.jolV2() == NOT_APPLICABLE) {
					continue;
				}
				trees++;
				projected += reading.projectedContainerSpine();
				measured += reading.jolV2();
				series.add(reading.projectedContainerSpine(), reading.jolV2());
			}
			if (trees == 0L) {
				continue;
			}
			System.out.printf(
				"  %-26s %,8d %14s %14s %8s %8s %8s%n",
				labels[f], trees, bytes(projected), bytes(measured),
				series.percentile(0.50d), series.min(), series.max()
			);
		}
	}

	/**
	 * Prints the dictionary-marginal table - the measurement the census named as the one that should decide whether
	 * the canonical-owner apparatus is worth building.
	 *
	 * `V1` moves the strings out to a canonical owner and leaves a 4-byte id behind; `V3` keeps them in place in one
	 * front-coded column and needs no owner, no ids, no allocator and no host increment. The delta is the *entire*
	 * gross return of the dictionary lever over the container lever on a string domain - before the host increment,
	 * which only `V1` pays and which the census already priced per domain.
	 *
	 * @param readings every reading of the run
	 */
	private static void printDictionaryMarginalTable(@Nonnull List<Reading> readings) {
		System.out.printf("%n=== DICTIONARY MARGINAL - V1 (ids) vs V3 (strings in place), string domains only ===%n");
		System.out.printf(
			"  %-46s %7s %12s %12s %12s %12s %9s %9s%n",
			"domain", "trees", "sum K", "JOL V0", "V1 ids", "V3 strings", "V3-V1", "of V0"
		);
		final Map<String, long[]> perDomain = new LinkedHashMap<>(64);
		for (int i = 0; i < readings.size(); i++) {
			final Reading reading = readings.get(i);
			if (!reading.sample().stringKeyed() || reading.jolV3() == NOT_APPLICABLE) {
				continue;
			}
			final long[] row = perDomain.computeIfAbsent(reading.sample().domainLabel(), ignored -> new long[6]);
			row[0]++;
			row[1] += reading.sample().bucketCount();
			row[2] += reading.jolV0();
			row[3] += reading.jolV1();
			row[4] += reading.jolV3();
			row[5] += reading.frontCodedColumnBytes();
		}
		final List<Map.Entry<String, long[]>> rows = new ArrayList<>(perDomain.entrySet());
		rows.sort((left, right) -> Long.compare(right.getValue()[2], left.getValue()[2]));
		long totalTrees = 0L;
		long totalBaseline = 0L;
		long totalIds = 0L;
		long totalStrings = 0L;
		for (int i = 0; i < rows.size(); i++) {
			final Map.Entry<String, long[]> row = rows.get(i);
			final long[] values = row.getValue();
			totalTrees += values[0];
			totalBaseline += values[2];
			totalIds += values[3];
			totalStrings += values[4];
			System.out.printf(
				"  %-46s %,7d %,12d %12s %12s %12s %9s %9s%n",
				trim(row.getKey(), 46), values[0], values[1], bytes(values[2]), bytes(values[3]), bytes(values[4]),
				signedBytes(values[4] - values[3]), signedPercent(values[4] - values[3], values[2])
			);
		}
		System.out.printf(
			"  %-46s %,7d %12s %12s %12s %12s %9s %9s%n",
			"TOTAL", totalTrees, "", bytes(totalBaseline), bytes(totalIds), bytes(totalStrings),
			signedBytes(totalStrings - totalIds), signedPercent(totalStrings - totalIds, totalBaseline)
		);
		System.out.println(
			"  `V3-V1` is the dictionary lever's MARGINAL gross return over a container that keeps the strings in"
		);
		System.out.println(
			"  place - what the front-coded column costs above the 4-byte ids that would replace it. It is gross: only"
		);
		System.out.println(
			"  V1 additionally pays the canonical owner's host id column, which the census priced per domain."
		);
		System.out.println(
			"  A NEGATIVE figure would mean front-coding is cheaper than the ids and the dictionary is worthless here."
		);
	}

	/**
	 * Prints the `V4` penalty table - what a per-tiny-tree B+ tree costs against the exact-array container that
	 * replaced it in the design.
	 *
	 * @param readings every reading of the run
	 */
	private static void printBPlusTreePenaltyTable(@Nonnull List<Reading> readings) {
		System.out.printf("%n=== V4 PENALTY - TransactionalLongBPlusTree(valueId -> posting), block size %d ===%n",
			V4_BLOCK_SIZE);
		System.out.printf(
			"  %-8s %8s %14s %14s %14s %9s %9s%n",
			"K", "trees", "JOL V0", "V1 container", "V4 B+ tree", "V4/V1", "V4/V0"
		);
		long allBaseline = 0L;
		long allContainer = 0L;
		long allTree = 0L;
		for (int stratum = 0; stratum < STRATA_LABELS.length; stratum++) {
			long trees = 0L;
			long baseline = 0L;
			long container = 0L;
			long bPlusTree = 0L;
			for (int i = 0; i < readings.size(); i++) {
				final Reading reading = readings.get(i);
				if (reading.sample().stratum() != stratum) {
					continue;
				}
				trees++;
				baseline += reading.jolV0();
				container += reading.jolV1();
				bPlusTree += reading.jolV4();
			}
			if (trees == 0L) {
				continue;
			}
			allBaseline += baseline;
			allContainer += container;
			allTree += bPlusTree;
			System.out.printf(
				"  %-8s %,8d %14s %14s %14s %9s %9s%n",
				STRATA_LABELS[stratum], trees, bytes(baseline), bytes(container), bytes(bPlusTree),
				ratio(bPlusTree, container), ratio(bPlusTree, baseline)
			);
		}
		System.out.printf(
			"  %-8s %8s %14s %14s %14s %9s %9s%n",
			"TOTAL", "", bytes(allBaseline), bytes(allContainer), bytes(allTree),
			ratio(allTree, allContainer), ratio(allTree, allBaseline)
		);
	}

	/**
	 * Prints the closing note on what the run did and did not prove.
	 */
	private static void printFooter() {
		System.out.printf("%n=== METHOD ===%n");
		System.out.println(
			"  Every variant was built from the SAME live tree's buckets in tree order and references the SAME live"
		);
		System.out.println(
			"  TransactionalBitmap instances for multi-record buckets. The walk stops at Bitmap on every side, so"
		);
		System.out.println(
			"  bitmap bytes appear in no column; add the census's `bitmapBytes` to every side to get absolute heap."
		);
		System.out.println(
			"  V1/V2 are bare arrays in a container object - literally the shape `candidateSpineOf` prices. V3 has no"
		);
		System.out.println(
			"  bare-array form and additionally pays the front-coded column's own object header."
		);
		System.out.printf("  Fields neither reflection nor JOL could read: %,d.%n", inaccessibleFieldCount);
	}

	/**
	 * Writes one TSV row per weighed tree, so every table above can be re-derived without a second run.
	 *
	 * @param readings    every reading of the run
	 * @param catalogName the catalog label, used in the file name
	 * @param reportDir   directory the TSV is written into
	 */
	private static void writeTsv(
		@Nonnull List<Reading> readings,
		@Nonnull String catalogName,
		@Nonnull Path reportDir
	) throws IOException {
		Files.createDirectories(reportDir);
		final Path target = reportDir.resolve("value-dedup-representation-" + catalogName + ".tsv");
		try (final BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
			writer.write(
				"entityType\treference\tattribute\tlocale\tscope\tkind\tlever\tsampleType\tstratum\tbuckets\t" +
					"records\tmulti\tsortable\tcontainerKeyBytes\tengineTreeBytes\tbitmapBytes\tremovableBytes\t" +
					"jolV0\tprojectedDictSpine\tjolV1\tprojectedContainerSpine\tjolV2\tjolV3\tfrontCodedColumnBytes\t" +
					"jolV4"
			);
			writer.newLine();
			for (int i = 0; i < readings.size(); i++) {
				final Reading reading = readings.get(i);
				final SampledTree sample = reading.sample();
				final AttributeIndexKey key = sample.attributeKey();
				final StringBuilder row = new StringBuilder(256);
				row.append(sample.entityType()).append('\t')
					.append(key.referenceName() == null ? "" : key.referenceName()).append('\t')
					.append(key.attributeName()).append('\t')
					.append(key.locale() == null ? "" : key.locale().toLanguageTag()).append('\t')
					.append(sample.scope()).append('\t')
					.append(sample.kind()).append('\t')
					.append(sample.lever()).append('\t')
					.append(sample.sampleTypeName()).append('\t')
					.append(STRATA_LABELS[sample.stratum()]).append('\t')
					.append(sample.bucketCount()).append('\t')
					.append(sample.recordCount()).append('\t')
					.append(sample.multiCount()).append('\t')
					.append(sample.sortable()).append('\t')
					.append(sample.containerKeyBytes()).append('\t')
					.append(sample.treeBytes()).append('\t')
					.append(sample.bitmapBytes()).append('\t')
					.append(sample.removableBytes()).append('\t')
					.append(reading.jolV0()).append('\t')
					.append(reading.projectedDictionarySpine()).append('\t')
					.append(reading.jolV1()).append('\t')
					.append(reading.projectedContainerSpine()).append('\t')
					.append(reading.jolV2()).append('\t')
					.append(reading.jolV3()).append('\t')
					.append(reading.frontCodedColumnBytes()).append('\t')
					.append(reading.jolV4());
				writer.write(row.toString());
				writer.newLine();
			}
		}
		System.out.printf("%nTSV written to %s (%,d rows).%n", target, readings.size());
	}

	/* ========================================== support ========================================== */

	/**
	 * Pages through every index of a collection in {@link IndexBrowseOrdering#MAP_ORDER}, resolving each row back to
	 * its live index. Copied from {@link ValueDedupCensus} so the two tools visit exactly the same set of indexes.
	 *
	 * @param collection the collection to walk
	 * @param visitor    called once per index
	 */
	private static void browse(@Nonnull EntityCollection collection, @Nonnull IndexVisitor visitor) {
		int pageNumber = 1;
		int visited = 0;
		int total = Integer.MAX_VALUE;
		while (visited < total) {
			final IndexBrowseResult page = collection.browseIndexes(
				new IndexBrowseCriteria(
					pageNumber, IndexBrowseCriteria.MAX_PAGE_SIZE,
					IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
					Set.of(), Set.of(), Set.of()
				)
			);
			total = page.totalRecordCount();
			final BrowsedIndex[] rows = page.indexes();
			if (rows.length == 0) {
				break;
			}
			for (int i = 0; i < rows.length; i++) {
				final BrowsedIndex row = rows[i];
				final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(row.indexPrimaryKey());
				if (index == null) {
					throw new GenericEvitaInternalError(
						"Index `" + row.indexPrimaryKey() + "` of collection `" + collection.getEntityType() +
							"` was browsed but cannot be resolved!",
						"Browsed index cannot be resolved!"
					);
				}
				visitor.visit(index, row);
			}
			visited += rows.length;
			if (visited % PROGRESS_LOG_INTERVAL < rows.length && visited < total) {
				System.out.printf("      %,d / %,d indexes%n", visited, total);
			}
			pageNumber++;
		}
	}

	/**
	 * Reads the kind of a browsed row, refusing the one shape a collection browse cannot legitimately return.
	 *
	 * @param collection the collection the row belongs to
	 * @param row        the browsed row
	 * @return the row's index kind
	 */
	@Nonnull
	private static EntityIndexType indexTypeOf(@Nonnull EntityCollection collection, @Nonnull BrowsedIndex row) {
		final EntityIndexType indexType = row.indexType();
		if (indexType == null) {
			throw new GenericEvitaInternalError(
				"Collection `" + collection.getEntityType() + "` browsed index `" + row.indexPrimaryKey() +
					"` without an index type!",
				"Collection browse returned an index without a type!"
			);
		}
		return indexType;
	}

	/**
	 * Reads one bucket value out of a tree through its cursor, to classify an owned sort tree without materializing
	 * its whole bucket array.
	 *
	 * @param tree the tree to sample
	 * @return its first bucket value, or `null` when the tree holds no buckets
	 */
	@Nullable
	private static Serializable firstValueOf(@Nonnull InvertedIndex tree) {
		final Iterator<ValueToRecord> iterator = tree.getValueIterator();
		return iterator.hasNext() ? iterator.next().getValue() : null;
	}

	/**
	 * Renders the domain identity the sample is bucketed by - the census's `(entityType, scope, key, kind)` domain,
	 * flattened into one label.
	 *
	 * @param entityType the owning collection
	 * @param key        the attribute key
	 * @param scope      the owning index's scope
	 * @param kind       which structure the tree comes from
	 * @return the rendered label
	 */
	@Nonnull
	private static String domainLabelOf(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey key,
		@Nonnull Scope scope,
		@Nonnull DomainKind kind
	) {
		final StringBuilder label = new StringBuilder(80);
		label.append(entityType).append(' ');
		if (key.referenceName() != null) {
			label.append(key.referenceName()).append('.');
		}
		label.append(key.attributeName());
		if (key.locale() != null) {
			label.append(' ').append(key.locale().toLanguageTag());
		}
		if (scope != Scope.LIVE) {
			label.append(" (").append(scope).append(')');
		}
		if (kind != DomainKind.FILTER) {
			label.append(' ').append(kind);
		}
		return label.toString();
	}

	/**
	 * Builds the factory the engine selects for a `String` key. Natural order is passed deliberately: the flag only
	 * switches the column's byte-compare fast path on the *search* path and changes no stored byte, so a localized
	 * domain measures the same either way.
	 *
	 * @return the factory instance
	 */
	@Nonnull
	private static Object stringColumnFactory() {
		try {
			final Method forKey = openMethod(VALUE_COLUMN_FACTORY_CLASS, "forKey", Class.class, Comparator.class);
			return forKey.invoke(null, String.class, null);
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot obtain the engine's own value-column factory for a `String` key!",
				"Cannot obtain the value-column factory for a String key!",
				e
			);
		}
	}

	/**
	 * Loads an engine class by name, because its own package-private visibility makes it unnameable from here.
	 *
	 * @param className fully qualified class name
	 * @return the loaded class
	 */
	@Nonnull
	private static Class<?> openClass(@Nonnull String className) {
		try {
			return Class.forName(className);
		} catch (final ClassNotFoundException e) {
			throw new GenericEvitaInternalError(
				"Cannot load `" + className + "` - the spike reaches it by name because it is not public.",
				"Cannot load a class the representation spike needs!",
				e
			);
		}
	}

	/**
	 * Opens one declared method for invocation.
	 *
	 * @param owner          the declaring class
	 * @param methodName     the method to open
	 * @param parameterTypes its parameter types
	 * @return the opened method
	 */
	@Nonnull
	private static Method openMethod(
		@Nonnull Class<?> owner,
		@Nonnull String methodName,
		@Nonnull Class<?>... parameterTypes
	) {
		try {
			final Method method = owner.getDeclaredMethod(methodName, parameterTypes);
			method.setAccessible(true);
			return method;
		} catch (final NoSuchMethodException | SecurityException e) {
			throw new GenericEvitaInternalError(
				"Cannot open `" + owner.getSimpleName() + "#" + methodName + "` - the spike calls it because the " +
					"declaring type is not public.",
				"Cannot open a method the representation spike needs!",
				e
			);
		}
	}

	/**
	 * Opens one declared field for reading.
	 *
	 * @param owner     the declaring class
	 * @param fieldName the field to open
	 * @return the opened field
	 */
	@Nonnull
	private static Field openField(@Nonnull Class<?> owner, @Nonnull String fieldName) {
		try {
			final Field field = owner.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field;
		} catch (final NoSuchFieldException | SecurityException e) {
			throw new GenericEvitaInternalError(
				"Cannot open `" + owner.getSimpleName() + "#" + fieldName + "` - the spike reads it because no " +
					"accessor exposes it.",
				"Cannot open a field the representation spike needs!",
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
	 * Reads the private value tree of an owner-mode sort index.
	 *
	 * @param sortIndex the sort index to read
	 * @return its owned value tree
	 */
	@Nonnull
	private static InvertedIndex ownedTreeOf(@Nonnull OwnerSortIndex sortIndex) {
		try {
			return (InvertedIndex) OWNED_TREE_FIELD.get(sortIndex);
		} catch (final IllegalAccessException e) {
			throw new GenericEvitaInternalError(
				"Cannot read `OwnerSortIndex#ownedTree`!",
				"Cannot read the owned value tree of a sort index!",
				e
			);
		}
	}

	/**
	 * Resolves the directory the catalog is copied into, creating a fresh temporary one when unconfigured.
	 *
	 * @return the working directory
	 */
	@Nonnull
	private static Path resolveWorkDir() throws IOException {
		final String configured = System.getProperty(ValueDedupCensus.WORK_DIR_PROPERTY);
		return configured == null || configured.isBlank()
			? Files.createTempDirectory("evita-value-dedup-representation")
			: Path.of(configured);
	}

	/**
	 * Resolves the directory the TSV is written into, defaulting to the working directory.
	 *
	 * @param workDir the working directory
	 * @return the report directory
	 */
	@Nonnull
	private static Path resolveReportDir(@Nonnull Path workDir) {
		final String configured = System.getProperty(ValueDedupCensus.REPORT_DIR_PROPERTY);
		return configured == null || configured.isBlank() ? workDir : Path.of(configured);
	}

	/**
	 * Copies the named catalog out of the snapshot into the working directory, so boot-time recovery cannot alter the
	 * snapshot every later run starts from.
	 *
	 * @param dataDir     snapshot directory holding the catalog
	 * @param workDir     the directory to copy into
	 * @param catalogName catalog to copy
	 * @return the working directory to boot against
	 */
	@Nonnull
	private static Path copyCatalog(
		@Nonnull Path dataDir,
		@Nonnull Path workDir,
		@Nonnull String catalogName
	) throws IOException {
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
	 * Waits until the named catalog finishes loading, failing fast on a corrupted one.
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
						"compression, the snapshot was written with `" + ValueDedupCensus.COMPRESS_PROPERTY +
						"` disabled.",
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
		throw new IllegalStateException("Catalog `" + catalogName + "` did not become usable within the load timeout!");
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
					ValueDedupRepresentationSpike.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Reads an optional positive integer property.
	 *
	 * @param propertyName name of the property
	 * @param fallback     value used when the property is unset
	 * @return the parsed value
	 */
	private static int intProperty(@Nonnull String propertyName, int fallback) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		final int parsed = Integer.parseInt(value.trim());
		if (parsed <= 0) {
			throw new GenericEvitaInternalError(
				"System property `" + propertyName + "` must be positive but is " + parsed + "!",
				"A sample-size system property is not positive!"
			);
		}
		return parsed;
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
	 * Truncates a label to a column width, marking the truncation.
	 *
	 * @param text  the label
	 * @param width the column width
	 * @return the label, truncated if needed
	 */
	@Nonnull
	private static String trim(@Nonnull String text, int width) {
		return text.length() <= width ? text : text.substring(0, width - 1) + "~";
	}

	/**
	 * Renders a byte count in the largest unit that keeps it above one.
	 *
	 * @param value the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String bytes(long value) {
		if (value < 0L) {
			return "-" + bytes(-value);
		}
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
	 * Renders a byte count that may legitimately be negative, keeping the sign explicit.
	 *
	 * @param value the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String signedBytes(long value) {
		return value > 0L ? "+" + bytes(value) : bytes(value);
	}

	/**
	 * Renders a signed share as a percentage.
	 *
	 * @param part  the numerator
	 * @param whole the denominator
	 * @return the rendered share
	 */
	@Nonnull
	private static String signedPercent(long part, long whole) {
		if (whole == 0L) {
			return "-";
		}
		return String.format(Locale.ROOT, "%+.1f%%", 100.0 * part / whole);
	}

	/**
	 * Renders a ratio to two decimals, reporting an undefined ratio as `-`.
	 *
	 * @param part  the numerator
	 * @param whole the denominator
	 * @return the rendered ratio
	 */
	@Nonnull
	private static String ratio(long part, long whole) {
		if (whole == 0L) {
			return "-";
		}
		return String.format(Locale.ROOT, "%.2fx", (double) part / whole);
	}

	/* =========================================== state ========================================== */

	/**
	 * One reference field of one class, together with the route the walk reads it through.
	 *
	 * @param field      the field itself
	 * @param reflective `true` when `setAccessible` succeeded and the field is read by plain reflection
	 * @param offset     the JOL/Unsafe field offset used when it did not, or {@link #UNREADABLE}
	 */
	private record WalkField(@Nonnull Field field, boolean reflective, long offset) {

		/**
		 * Offset marking a field neither route can read. Such a field is counted once, warned about once, and
		 * thereafter read as `null` rather than throwing on every object of its class.
		 */
		static final long UNREADABLE = -1L;

		/**
		 * Reads this field out of one object.
		 *
		 * @param owner the object to read from
		 * @return the referenced object, or `null` when the field is unreadable
		 */
		@Nullable
		Object read(@Nonnull Object owner) {
			try {
				if (this.reflective) {
					return this.field.get(owner);
				}
				return this.offset == UNREADABLE ? null : JOL_VM.getObject(owner, this.offset);
			} catch (final IllegalAccessException e) {
				throw new GenericEvitaInternalError(
					"Field `" + this.field.getDeclaringClass().getName() + "#" + this.field.getName() +
						"` was opened but cannot be read - the walk would silently under-count from here on!",
					"An opened field of the walk cannot be read!",
					e
				);
			}
		}
	}

	/**
	 * Visitor over the indexes of one collection.
	 */
	@FunctionalInterface
	private interface IndexVisitor {

		/**
		 * Called once per browsed index.
		 *
		 * @param index the live index
		 * @param row   the browsed row naming it
		 */
		void visit(@Nonnull EntityIndex index, @Nonnull BrowsedIndex row);
	}

	/**
	 * The exact-sized container the two levers propose: one key column, one postings column, an overflow column that
	 * exists only when the tree has a multi-record bucket, and a sort-slot column that exists only when the domain
	 * also serves ordering.
	 *
	 * Four reference fields, which is exactly what `ValueDedupCensus#candidateSpineOf` prices as its `fixed` term.
	 * The key column is typed `Object` so one class serves all three key shapes - an `int[]` of value ids (`V1`), a
	 * primitive key array (`V2`) or a front-coded string column (`V3`) - without any of them paying for a field the
	 * others do not use.
	 */
	private static final class ExactContainer {
		/** The key column - value ids, a primitive key array, or a front-coded string column. */
		@Nonnull private final Object keys;
		/** The lone record id of each single-record bucket; don't-care where {@link #overflow} holds a bitmap. */
		@Nonnull private final int[] postings;
		/** The multi-record bitmaps, `null` when the tree has none. */
		@Nullable private final TransactionalBitmap[] overflow;
		/** The canonical-order permutation, `null` when the domain does not serve ordering. */
		@Nullable private final int[] sortSlots;

		ExactContainer(
			@Nonnull Object keys,
			@Nonnull int[] postings,
			@Nullable TransactionalBitmap[] overflow,
			@Nullable int[] sortSlots
		) {
			this.keys = keys;
			this.postings = postings;
			this.overflow = overflow;
			this.sortSlots = sortSlots;
		}
	}

	/**
	 * One reduced value tree picked for weighing, together with everything the census would have recorded about it.
	 *
	 * @param tree              the live tree; held by reference, never copied
	 * @param domainLabel       the census domain this tree belongs to, flattened into one label
	 * @param entityType        the owning collection
	 * @param attributeKey      the attribute key
	 * @param scope             the owning index's scope
	 * @param kind              which structure the tree comes from
	 * @param lever             which lever could reduce this domain
	 * @param sampleTypeName    runtime class of a sample bucket value, so the key family stays auditable
	 * @param containerKeyBytes primitive key width, or `0` when the container lever does not apply
	 * @param stringKeyed       whether the keys are `String`s, which is what the front-coded variant needs
	 * @param bucketCount       `K`
	 * @param recordCount       `M`
	 * @param multiCount        how many buckets hold more than one record
	 * @param sortable          whether the tree also serves ordering
	 * @param stratum           index of the `K` band this tree falls in
	 * @param treeBytes         the engine's own figure for the tree
	 * @param bitmapBytes       the record bitmaps of its multi-record buckets
	 */
	private record SampledTree(
		@Nonnull InvertedIndex tree,
		@Nonnull String domainLabel,
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeKey,
		@Nonnull Scope scope,
		@Nonnull DomainKind kind,
		@Nonnull Lever lever,
		@Nonnull String sampleTypeName,
		int containerKeyBytes,
		boolean stringKeyed,
		int bucketCount,
		long recordCount,
		int multiCount,
		boolean sortable,
		int stratum,
		long treeBytes,
		long bitmapBytes
	) {

		/**
		 * @return the census's `removable` figure for this tree - what the representation swap could reclaim
		 */
		long removableBytes() {
			return this.treeBytes - this.bitmapBytes;
		}
	}

	/**
	 * Everything measured about one sampled tree.
	 *
	 * @param sample                   the tree that was weighed
	 * @param jolV0                    deep-retained size of the live tree, bitmaps excluded
	 * @param jolV1                    deep-retained size of the dictionary container
	 * @param jolV2                    deep-retained size of the primitive container, or {@link #NOT_APPLICABLE}
	 * @param jolV3                    deep-retained size of the front-coded container, or {@link #NOT_APPLICABLE}
	 * @param jolV4                    deep-retained size of the B+ tree alternative
	 * @param frontCodedColumnBytes    the front-coded column's own accounting, or {@link #NOT_APPLICABLE}
	 * @param projectedDictionarySpine `candidateSpineOf` with a 4-byte id key
	 * @param projectedContainerSpine  `candidateSpineOf` with the primitive key width, or {@link #NOT_APPLICABLE}
	 */
	private record Reading(
		@Nonnull SampledTree sample,
		long jolV0,
		long jolV1,
		long jolV2,
		long jolV3,
		long jolV4,
		long frontCodedColumnBytes,
		long projectedDictionarySpine,
		long projectedContainerSpine
	) {
	}

	/**
	 * A reservoir of one `(domain, stratum)` cell. Uniform within the cell, so a stratum's numbers are not dominated
	 * by whichever trees the browse happened to reach first.
	 */
	private static final class SampleCell {
		/** The retained trees. */
		@Nonnull private final SampledTree[] reservoir;
		/** How many trees this cell has been offered in total. */
		private int offered;

		SampleCell(int limit) {
			this.reservoir = new SampledTree[limit];
		}

		/**
		 * Offers one tree to the reservoir.
		 *
		 * @param candidate the tree offered
		 * @param random    the shared, seeded sampler
		 */
		void offer(@Nonnull SampledTree candidate, @Nonnull Random random) {
			if (this.offered < this.reservoir.length) {
				this.reservoir[this.offered] = candidate;
			} else {
				final int slot = random.nextInt(this.offered + 1);
				if (slot < this.reservoir.length) {
					this.reservoir[slot] = candidate;
				}
			}
			this.offered++;
		}

		/**
		 * @return how many trees the cell currently holds
		 */
		int retained() {
			return Math.min(this.offered, this.reservoir.length);
		}

		/**
		 * Copies the first `count` retained trees into the sample.
		 *
		 * @param target the sample being assembled
		 * @param count  how many to copy
		 */
		void drainInto(@Nonnull List<SampledTree> target, int count) {
			for (int i = 0; i < count; i++) {
				target.add(this.reservoir[i]);
			}
		}
	}

	/**
	 * A growable series of relative errors, summarized by percentile. A plain `double[]` rather than a collection:
	 * the series is filled once per table cell and read once, and boxing every error would allocate more than the
	 * structures being measured.
	 */
	private static final class ErrorSeries {
		/** The collected relative errors. */
		@Nonnull private double[] errors = new double[64];
		/** How many are live. */
		private int size;

		/**
		 * Records one `(projected, measured)` pair as a relative error. A zero measurement is dropped rather than
		 * divided by - it cannot occur for a structure that has at least an object header, so a zero would be a
		 * defect in the walk and is counted nowhere rather than silently becoming an infinity.
		 *
		 * @param projected the modelled figure
		 * @param measured  the weighed figure
		 */
		void add(long projected, long measured) {
			if (measured == 0L) {
				return;
			}
			if (this.size == this.errors.length) {
				this.errors = Arrays.copyOf(this.errors, this.errors.length * 2);
			}
			this.errors[this.size++] = (double) (projected - measured) / measured;
		}

		/**
		 * @param quantile the quantile to read, in `[0, 1]`
		 * @return the rendered percentile, or `-` when the series is empty
		 */
		@Nonnull
		String percentile(double quantile) {
			if (this.size == 0) {
				return "-";
			}
			final double[] sorted = Arrays.copyOf(this.errors, this.size);
			Arrays.sort(sorted);
			return render(sorted[Math.min(this.size - 1, (int) (this.size * quantile))]);
		}

		/**
		 * @return the rendered smallest error, or `-` when the series is empty
		 */
		@Nonnull
		String min() {
			if (this.size == 0) {
				return "-";
			}
			double smallest = this.errors[0];
			for (int i = 1; i < this.size; i++) {
				if (this.errors[i] < smallest) {
					smallest = this.errors[i];
				}
			}
			return render(smallest);
		}

		/**
		 * @return the rendered largest error, or `-` when the series is empty
		 */
		@Nonnull
		String max() {
			if (this.size == 0) {
				return "-";
			}
			double largest = this.errors[0];
			for (int i = 1; i < this.size; i++) {
				if (this.errors[i] > largest) {
					largest = this.errors[i];
				}
			}
			return render(largest);
		}

		/**
		 * Renders one relative error as a signed percentage.
		 *
		 * @param error the error to render
		 * @return the rendered figure
		 */
		@Nonnull
		private static String render(double error) {
			return String.format(Locale.ROOT, "%+.1f%%", 100.0 * error);
		}
	}
}
