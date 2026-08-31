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
import io.evitadb.index.bPlusTree.BucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.VMLayout;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * **P1 of the catalog-wide value-ids line - the per-domain stratified dedup decision census.** Boots an embedded
 * evitaDB against a catalog that already exists on disk and answers, one semantic domain at a time: *if this domain's
 * reduced value trees were replaced by id-keyed exact arrays referencing the canonical owner tree, how many bytes
 * would actually be saved?*
 *
 * It is the sibling of {@link TrigramReplicationCensus} and reuses its boot, walk and reporting machinery. Where that
 * census answers *where the attribute heap sits*, this one answers *what moving it would cost and return* - measured
 * current bytes against a byte-exact projection of the v1 candidate representation, minus the host increment the
 * canonical owner would have to start paying.
 *
 * # The domain
 *
 * A **domain** is `(entityType, scope, AttributeIndexKey, kind)` where the kind is one of:
 *
 * - `FILTER` - the front-coded filter value tree, {@link FilterIndex#getInvertedIndex()};
 * - `OWNER_SORT` - an {@link OwnerSortIndex}'s private value tree (a sort-only attribute); a {@link SortIndexView}
 *   owns no values and is skipped, because the tree it reads is the `FILTER` domain's tree and is already counted;
 * - `COMPOUND` - an {@link OwnerSortIndex} over a sortable attribute compound, whose bucket values are
 *   {@link ComparableArray}.
 *
 * Trees are collected from the two reduced index kinds ({@link EntityIndexType#REFERENCED_ENTITY} and
 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY}); {@link EntityIndexType#GLOBAL} and the two `*_ENTITY_TYPE` kinds
 * are walked as **canonical-owner candidates** instead. The classification is an exhaustive switch that throws on an
 * unrecognised kind rather than skipping it - a silently dropped kind would understate every number in the table.
 *
 * Canonical owner resolution, in order: the GLOBAL filter tree for the same key; else the reference-type filter tree
 * for the same key under the reduced index's own reference; else the GLOBAL owner-sort tree; else `MISSING`. A
 * `MISSING` row is reported, never dropped - those rows are the interesting failures of the union-owner assumption.
 *
 * # The ledger
 *
 * Per reduced tree, with `K` buckets and `M` records:
 *
 * ```text
 * treeBytes      = the tree's own getHeapSizeInBytes()   (for OWNER_SORT the OWNED tree, not SortIndex's total:
 *                                                         sortedRecords survives dedup and must not enter either side)
 * bitmapBytes    = the record bitmaps of buckets with cardinality > 1  (they survive dedup unchanged)
 * removableBytes = treeBytes - bitmapBytes
 *
 * fixed          = objectHeader + 4 x referenceSize
 * ids            = arrayHeader + 4K
 * postings       = arrayHeader + 4K + multiCount x referenceSize + (multiCount > 0 ? arrayHeader + refSize x K : 0)
 * sortSlots      = sortable ? arrayHeader + 4K : 0
 * candidateSpine = fixed + ids + postings + sortSlots
 * saving         = removableBytes - candidateSpine
 * ```
 *
 * Every term is rounded up to the running VM's object alignment through {@link VMLayout}, so the projection is exact
 * arithmetic on the actual layout rather than on constants that were true on somebody else's JVM. Varints, Roaring
 * re-encoding and transactional-layer overhead are deliberately **not** modelled: this is the steady committed-state
 * ledger.
 *
 * The host increment is charged once per domain, not per tree: zero when the canonical owner already
 * {@link InvertedIndex#carriesValueIds()} (the trigram line already paid for it - flagged `shared`), otherwise the id
 * column the owner would grow, `leafCount x (arrayHeader + 4 x leafCapacity)` plus a small allocator. The `+dir`
 * column prices a reverse id-to-value directory and is **informational only** - it is never added to the net, because
 * a dedup-only domain needs no reverse lookup.
 *
 * # Decisions this class took where the plan deferred them
 *
 * 1. **Scope does not live in {@link AttributeIndexKey}** - that record carries reference name, attribute name and
 *    locale only. Scope is read from the owning index's {@link io.evitadb.index.EntityIndexKey} and carried as a
 *    separate component of the domain key, which is what the design's "(entity type, scope, key, locale)" domain
 *    actually requires.
 * 2. **Replication `r` is `sum(K) / V_union`** - how many trees replicate the average distinct value, which is the
 *    quantity the dedup decision turns on. `M` is printed in its own column, so the record-side ratio `M / V_union`
 *    remains computable from the same row.
 * 3. **The `multiCount x referenceSize` term of `postings` is charged as the plan writes it**, on top of the
 *    exact-sized overflow reference array. It over-charges the candidate by one reference per multi-record bucket and
 *    therefore *under*-reports savings - the conservative direction for a go/no-go table. The total over-charge is
 *    printed under the decision table so the sensitivity is visible rather than buried.
 * 4. **`ALREADY_PRIMITIVE` also absorbs boxed non-compound keys** (a `Locale`, a `Currency`). They are not literally
 *    primitive, but they are excluded from the savings ledger for the same reason - the census has no measured spine
 *    model for them - and they are rare and low-cardinality. The label follows the plan's vocabulary.
 * 5. **The A3 cross-check is computed inside this run**, not by re-running {@link TrigramReplicationCensus}: the same
 *    walk accumulates that census's definition of "reduced value trees" (every non-GLOBAL index) beside this one's
 *    (the two reduced kinds only). The two differ exactly by the reference-type indexes, which A3 measured at
 *    &le;0.2% of the attribute heap - hence the ~10% tolerance.
 * 6. **A tree with no buckets is counted and skipped**, not thrown on. It is a legitimate transient shape and the
 *    count is reported, so the skip is never silent.
 * 7. **The coverage gap counts distinct values**, and the owner is probed once per newly-seen value rather than once
 *    per bucket occurrence - the probe is driven off the domain's union map. {@link InvertedIndex#contains} applies
 *    the *owner's* normalizer to the already-normalized reduced value; that is deliberate and is the
 *    provably-identical-normalizer argument, so the census never re-normalizes on its own.
 * 8. **The per-domain strata table is capped on stdout** at the {@link #STRATA_DOMAIN_LIMIT} domains with the largest
 *    removable bytes, because a fan-out catalog has hundreds of domains and six strata each. The TSV carries every
 *    stratum of every domain.
 *
 * # Reflection
 *
 * Three private/protected fields are read reflectively, in the spirit of {@link TrigramReplicationCensus}'s single
 * read and for the same reason - the spike may not edit the engine:
 * {@link EntityIndex}`.attributeIndex`, {@link OwnerSortIndex}`.ownedTree` and {@link InvertedIndex}`.buckets`. The
 * last one is needed only for the host increment, which prices the owner's id column from the tree's own leaf count
 * and leaf capacity rather than from a hard-coded block size.
 *
 * # Configuration
 *
 * The properties are {@link TrigramReplicationCensus}'s, so one command line serves both, plus one of its own:
 *
 * | Property | Meaning |
 * |---|---|
 * | `evita.trigram.catalogName` | **required** - the catalog to walk |
 * | `evita.trigram.dataDir` | **required** - storage directory containing a `<catalogName>/` subfolder |
 * | `evita.trigram.workDir` | working copy location; defaults to a fresh temp directory |
 * | `evita.trigram.reportDir` | where the TSV is written; defaults to the work directory |
 * | `evita.trigram.copyData` | `false` opens `dataDir` in place instead of copying (default `true`) |
 * | `evita.trigram.compress` | storage compression of the snapshot (default `true`) |
 * | `evita.trigram.entityTypes` | comma-separated collection allow-list; default: every collection |
 *
 * # Running it
 *
 * ```shell
 * java -Xmx4g \
 *   --add-opens java.base/java.lang=ALL-UNNAMED \
 *   --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
 *   --add-opens java.base/java.math=ALL-UNNAMED \
 *   --add-opens java.base/java.util=ALL-UNNAMED \
 *   -Devita.trigram.catalogName=evita \
 *   -Devita.trigram.dataDir=/path/to/snapshot \
 *   -Devita.trigram.workDir=/path/to/work \
 *   -Devita.trigram.reportDir=/path/to/work \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.ValueDedupCensus
 * ```
 *
 * The benchmark jar shades the engine, so it must be repackaged after every engine reinstall or the run measures
 * stale code. Every run needs its own working directory - an embedded boot takes a cwd-relative `export/` folder lock.
 *
 * @author Claude (catalog-wide value ids spike), FG Forrest a.s. (c) 2026
 */
public class ValueDedupCensus {

	/**
	 * System property naming the catalog to walk. Shared with {@link TrigramReplicationCensus}.
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
	 * System property naming the directory the TSV report is written into. Defaults to the working directory.
	 */
	public static final String REPORT_DIR_PROPERTY = "evita.trigram.reportDir";

	/**
	 * System property switching the pre-boot copy off, opening the data directory in place.
	 */
	public static final String COPY_DATA_PROPERTY = "evita.trigram.copyData";

	/**
	 * System property matching {@link StorageOptions#compress()} to the snapshot being read. Defaults to `true`: a
	 * reader with compression enabled opens both forms, one without it reports a compressed snapshot as corrupted.
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
	 * How often progress is reported while a collection's indexes are being measured. Fan-out catalogs carry 20k+
	 * indexes per collection and a silent census is indistinguishable from a hung one.
	 */
	private static final int PROGRESS_LOG_INTERVAL = 25_000;

	/**
	 * How many domains the per-domain strata table prints on stdout, largest removable bytes first. The TSV is never
	 * truncated.
	 */
	private static final int STRATA_DOMAIN_LIMIT = 40;

	/**
	 * Flat cost charged for the owner's value-id allocator object when a domain has to grow an id column. It is a
	 * handful of fields and a counter; the plan's approximation is kept rather than measured, because it is three
	 * orders of magnitude below every other term in the ledger.
	 */
	private static final long VALUE_ID_ALLOCATOR_BYTES = 32L;

	/**
	 * Per-leaf cost of the two leaf maps a reverse id-to-value directory would need, used only by the informational
	 * `+dir` column.
	 */
	private static final long REVERSE_DIRECTORY_LEAF_BYTES = 48L;

	/**
	 * Band edges of the tree-size strata, upper bounds inclusive. The last stratum is open-ended.
	 */
	private static final int[] STRATA_UPPER_BOUNDS = {1, 4, 16, 64, 256, Integer.MAX_VALUE};

	/**
	 * Human labels of {@link #STRATA_UPPER_BOUNDS}, aligned by position.
	 */
	private static final String[] STRATA_LABELS = {"1", "2-4", "5-16", "17-64", "65-256", ">256"};

	/**
	 * Relative distance from zero within which a net saving counts as `MARGINAL` rather than `WIN` or `LOSE`.
	 */
	private static final double MARGINAL_BAND = 0.10d;

	/**
	 * Column layout of the decision table's header row. Kept beside {@link #DECISION_ROW_FORMAT} and identical to it
	 * except for the conversions, so a column added to one and forgotten in the other is visible at a glance.
	 */
	private static final String DECISION_HEADER_FORMAT =
		"%-18s %-24s %-6s %-8s %-10s %-7s %-12s %7s %6s %6s %10s %12s %6s " +
			"%11s %11s %11s %11s %11s %4s %-8s%n";

	/**
	 * Column layout of the decision table's data rows.
	 */
	private static final String DECISION_ROW_FORMAT =
		"%-18s %-24s %-6s %-8s %-10s %-7s %-12s %,7d %,6d %,6d %,10d %,12d %6s " +
			"%11s %11s %11s %11s %11s %,4d %-8s%n";

	/**
	 * Relative drift the A3 cross-check tolerates before it reports a failure. The two definitions differ by the
	 * reference-type indexes, which A3 measured at &le;0.2% of the attribute heap.
	 */
	private static final double A3_TOLERANCE = 0.10d;

	/**
	 * The `attributeIndex` field of {@link EntityIndex}, opened once. See {@link TrigramReplicationCensus} for why the
	 * census reads it reflectively instead of summing the publicly reachable sub-index families.
	 */
	private static final Field ATTRIBUTE_INDEX_FIELD = openField(EntityIndex.class, "attributeIndex");

	/**
	 * The `ownedTree` field of {@link OwnerSortIndex} - the private value tree of a sort-only attribute or a sortable
	 * compound. {@link SortIndex#getHeapSizeInBytes()} folds `sortedRecords` in, and `sortedRecords` survives dedup
	 * untouched, so the census must reach the owned tree itself rather than the index total.
	 */
	private static final Field OWNED_TREE_FIELD = openField(OwnerSortIndex.class, "ownedTree");

	/**
	 * The `buckets` field of {@link InvertedIndex} - the bucket B+ tree. Read only for canonical owners, to price the
	 * host id column from the tree's real leaf count and leaf capacity.
	 */
	private static final Field BUCKETS_FIELD = openField(InvertedIndex.class, "buckets");

	/**
	 * Entry point. Any failure exits the JVM explicitly: a partially constructed Evita instance has already started
	 * non-daemon threads, so a failed boot would otherwise leave a hanging JVM behind that still holds the
	 * storage-folder locks and blocks every subsequent run against the same directories.
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
	 * Performs the census as described in the class JavaDoc: copies the snapshot aside, boots against the copy, walks
	 * every selected collection and emits the tables plus the TSV.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(DATA_DIR_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(System.getProperty(COPY_DATA_PROPERTY, "true"));
		final boolean compress = Boolean.parseBoolean(System.getProperty(COMPRESS_PROPERTY, "true"));
		final Set<String> entityTypeFilter = parseListProperty(ENTITY_TYPES_PROPERTY);

		final Path workDir = resolveWorkDir();
		final Path storageDir = copyData ? copyCatalog(dataDir, workDir, catalogName) : dataDir;
		final Path reportDir = resolveReportDir(workDir);

		System.out.printf(
			"Value dedup census - catalog `%s` from `%s`%n", catalogName, storageDir
		);
		System.out.printf("VM layout: %s%n", VMLayout.current());

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
			censusCatalog(catalog, entityTypeFilter, catalogName, reportDir);
		}
	}

	/* ========================================== census =========================================== */

	/**
	 * Walks every selected collection, then prints the decision table, the strata tables, the headline and the
	 * self-checks, and writes the TSV.
	 *
	 * Collections are processed one at a time and each one's domains are finalized before the next begins, so the
	 * per-domain union maps - by far the largest transient structure here - are released as soon as they have yielded
	 * their distinct-value count.
	 *
	 * @param catalog          the loaded catalog
	 * @param entityTypeFilter collections to visit; empty means every collection
	 * @param catalogName      the catalog label, used in the TSV file name
	 * @param reportDir        directory the TSV is written into
	 */
	private static void censusCatalog(
		@Nonnull Catalog catalog,
		@Nonnull Set<String> entityTypeFilter,
		@Nonnull String catalogName,
		@Nonnull Path reportDir
	) throws IOException {
		final List<Domain> allDomains = new ArrayList<>(256);
		final CatalogTotals totals = new CatalogTotals();

		final Set<String> entityTypes = new TreeSet<>(catalog.getEntityTypes());
		for (final String entityType : entityTypes) {
			if (!entityTypeFilter.isEmpty() && !entityTypeFilter.contains(entityType)) {
				continue;
			}
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			System.out.printf("  %-28s walking %,d indexes...%n", entityType, collection.getIndexCount());
			final long start = System.nanoTime();

			final OwnerRegistry owners = collectOwners(collection, totals);
			final Map<DomainKey, Domain> domains = new LinkedHashMap<>(64);
			walkReducedIndexes(collection, owners, domains, totals);
			for (final Domain domain : domains.values()) {
				domain.finish();
				allDomains.add(domain);
			}

			System.out.printf(
				"  %-28s done in %,d ms - %,d domains, %,d trees.%n",
				entityType, (System.nanoTime() - start) / 1_000_000, domains.size(), countTrees(domains)
			);
		}

		allDomains.sort((left, right) -> Long.compare(right.removableBytes, left.removableBytes));

		printDecisionTable(allDomains);
		printCatalogStrataTable(allDomains);
		printDomainStrataTable(allDomains);
		printHeadline(allDomains, totals);
		printSelfChecks(allDomains, totals);
		writeTsv(allDomains, catalogName, reportDir);
	}

	/**
	 * Sums the trees measured across a collection's domains, for the per-collection progress line.
	 *
	 * @param domains the collection's domains
	 * @return how many reduced trees were measured
	 */
	private static long countTrees(@Nonnull Map<DomainKey, Domain> domains) {
		long trees = 0L;
		for (final Domain domain : domains.values()) {
			trees += domain.treeCount;
		}
		return trees;
	}

	/**
	 * First pass over a collection: registers the canonical-owner candidates - the GLOBAL index's trees and the
	 * reference-type indexes' trees - and accumulates their share of the A3 cross-check numbers.
	 *
	 * The registry has to be complete before any reduced tree is measured, because owner resolution and the coverage
	 * probe both need the owner instance in hand while the reduced buckets are being walked.
	 *
	 * @param collection the collection to scan
	 * @param totals     catalog-wide accumulators
	 * @return the owner registry for this collection
	 */
	@Nonnull
	private static OwnerRegistry collectOwners(@Nonnull EntityCollection collection, @Nonnull CatalogTotals totals) {
		final OwnerRegistry registry = new OwnerRegistry();
		browse(
			collection,
			(index, row) -> {
				final IndexRole role = roleOf(indexTypeOf(collection, row));
				switch (role) {
					case GLOBAL_OWNER, REFERENCE_TYPE_OWNER -> registerOwner(index, row, role, registry, totals);
					// reduced indexes are the second pass's business; nothing is measured here
					case REDUCED -> {
					}
				}
			}
		);
		return registry;
	}

	/**
	 * Registers one owner-candidate index: every filter tree it holds, plus every owned sort tree, keyed so a reduced
	 * tree can find its canonical owner later. Its filter trees also feed the GLOBAL / reference-type halves of the
	 * A3 cross-check.
	 *
	 * @param index    the live owner index
	 * @param row      the browsed row naming it
	 * @param role     whether it is the GLOBAL index or a reference-type index
	 * @param registry the registry being assembled
	 * @param totals   catalog-wide accumulators
	 */
	private static void registerOwner(
		@Nonnull EntityIndex index,
		@Nonnull BrowsedIndex row,
		@Nonnull IndexRole role,
		@Nonnull OwnerRegistry registry,
		@Nonnull CatalogTotals totals
	) {
		final AttributeIndex attributeIndex = attributeIndexOf(index);
		final Scope scope = index.getIndexKey().scope();
		final String referenceName = row.referenceName();

		if (role == IndexRole.GLOBAL_OWNER) {
			totals.globalAttributeBytes += attributeIndex.getHeapSizeInBytes();
			totals.globalIndexCount++;
		} else {
			totals.referenceTypeAttributeBytes += attributeIndex.getHeapSizeInBytes();
			totals.referenceTypeIndexCount++;
		}

		for (final AttributeIndexKey key : attributeIndex.getFilterIndexes()) {
			final FilterIndex filterIndex = attributeIndex.getFilterIndex(key);
			if (filterIndex == null) {
				throw new GenericEvitaInternalError(
					"Filter index key `" + key + "` resolves to no filter index!",
					"Filter index key resolves to no filter index!"
				);
			}
			final InvertedIndex tree = filterIndex.getInvertedIndex();
			final long treeBytes = tree.getHeapSizeInBytes();
			if (role == IndexRole.GLOBAL_OWNER) {
				totals.globalFilterTreeBytes += treeBytes;
				registry.globalFilterOwners.putIfAbsent(new OwnerKey(scope, null, key), tree);
			} else {
				totals.referenceTypeFilterTreeBytes += treeBytes;
				registry.referenceTypeFilterOwners.putIfAbsent(new OwnerKey(scope, referenceName, key), tree);
			}
		}

		if (role == IndexRole.GLOBAL_OWNER) {
			for (final AttributeIndexKey key : attributeIndex.getSortIndexes()) {
				final SortIndex sortIndex = attributeIndex.getSortIndex(key);
				if (sortIndex == null) {
					throw new GenericEvitaInternalError(
						"Sort index key `" + key + "` resolves to no sort index!",
						"Sort index key resolves to no sort index!"
					);
				}
				if (sortIndex instanceof final OwnerSortIndex ownerSortIndex) {
					registry.globalSortOwners.putIfAbsent(
						new OwnerKey(scope, null, key), ownedTreeOf(ownerSortIndex)
					);
				}
			}
		}
	}

	/**
	 * Second pass over a collection: measures every value tree of every reduced index and folds it into its domain.
	 *
	 * @param collection the collection to scan
	 * @param owners     the owner registry built by the first pass
	 * @param domains    the collection's domains, filled in here
	 * @param totals     catalog-wide accumulators
	 */
	private static void walkReducedIndexes(
		@Nonnull EntityCollection collection,
		@Nonnull OwnerRegistry owners,
		@Nonnull Map<DomainKey, Domain> domains,
		@Nonnull CatalogTotals totals
	) {
		browse(
			collection,
			(index, row) -> {
				final IndexRole role = roleOf(indexTypeOf(collection, row));
				switch (role) {
					case REDUCED -> measureReducedIndex(collection, index, row, owners, domains, totals);
					// owner candidates were measured by the first pass
					case GLOBAL_OWNER, REFERENCE_TYPE_OWNER -> {
					}
				}
			}
		);
	}

	/**
	 * Measures one reduced index: its filter trees under `FILTER`, and the owned tree of every {@link OwnerSortIndex}
	 * under `OWNER_SORT` or `COMPOUND`.
	 *
	 * A {@link SortIndexView} is skipped on purpose - it reads the shared tree the filter family already accounted
	 * for, so measuring it would double-count the very bytes the decision turns on.
	 *
	 * @param collection the owning collection
	 * @param index      the live reduced index
	 * @param row        the browsed row naming it
	 * @param owners     the owner registry
	 * @param domains    the collection's domains
	 * @param totals     catalog-wide accumulators
	 */
	private static void measureReducedIndex(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndex index,
		@Nonnull BrowsedIndex row,
		@Nonnull OwnerRegistry owners,
		@Nonnull Map<DomainKey, Domain> domains,
		@Nonnull CatalogTotals totals
	) {
		final AttributeIndex attributeIndex = attributeIndexOf(index);
		final Scope scope = index.getIndexKey().scope();
		final String entityType = collection.getEntityType();
		final String referenceName = row.referenceName();

		totals.reducedAttributeBytes += attributeIndex.getHeapSizeInBytes();
		totals.reducedIndexCount++;

		for (final AttributeIndexKey key : attributeIndex.getFilterIndexes()) {
			final FilterIndex filterIndex = attributeIndex.getFilterIndex(key);
			if (filterIndex == null) {
				throw new GenericEvitaInternalError(
					"Filter index key `" + key + "` resolves to no filter index!",
					"Filter index key resolves to no filter index!"
				);
			}
			final SortIndex sortIndex = attributeIndex.getSortIndex(key);
			// a filter tree needs a canonical-order permutation only when the SAME tree also serves ordering, which is
			// exactly the folded-view case; an OwnerSortIndex holds a tree of its own and is its own domain below
			final boolean sortable = sortIndex instanceof SortIndexView;
			final InvertedIndex tree = filterIndex.getInvertedIndex();
			totals.reducedFilterTreeBytes += tree.getHeapSizeInBytes();
			foldTree(
				new DomainKey(entityType, scope, key, DomainKind.FILTER), referenceName, tree, sortable,
				owners, domains, totals
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
				// a view owns no values - the tree beneath it is the filter domain's, already measured above
				continue;
			}
			final InvertedIndex tree = ownedTreeOf(ownerSortIndex);
			final DomainKind kind = kindOfOwnedTree(tree);
			foldTree(
				new DomainKey(entityType, scope, key, kind), referenceName, tree, true, owners, domains, totals
			);
		}
	}

	/**
	 * Measures one reduced value tree and folds the reading into its domain, creating the domain (and resolving its
	 * canonical owner) on first sight.
	 *
	 * The bucket walk does five things in one pass, because every one of them is `O(K)` and the bucket array is
	 * materialized once: it counts records, prices the surviving record bitmaps, feeds the domain's union map,
	 * probes the canonical owner for newly-seen values, and classifies the domain's value class from the first bucket.
	 *
	 * @param domainKey     identity of the domain this tree belongs to
	 * @param referenceName reference the owning reduced index belongs to, used to resolve a reference-type owner
	 * @param tree          the value tree to measure
	 * @param sortable      whether this tree also serves ordering, which adds the sort-slot term to the projection
	 * @param owners        the owner registry
	 * @param domains       the collection's domains
	 * @param totals        catalog-wide accumulators
	 */
	private static void foldTree(
		@Nonnull DomainKey domainKey,
		@Nullable String referenceName,
		@Nonnull InvertedIndex tree,
		boolean sortable,
		@Nonnull OwnerRegistry owners,
		@Nonnull Map<DomainKey, Domain> domains,
		@Nonnull CatalogTotals totals
	) {
		final ValueToRecordBitmap[] buckets = tree.getValueToRecordBitmap();
		final int bucketCount = buckets.length;
		if (bucketCount == 0) {
			totals.emptyTreeCount++;
			return;
		}

		Domain domain = domains.get(domainKey);
		if (domain == null) {
			final Serializable sample = buckets[0].getValue();
			domain = new Domain(domainKey, valueClassOf(sample, domainKey.attributeKey().locale()));
			domain.bindOwner(owners.resolve(domainKey, referenceName, sample));
			domains.put(domainKey, domain);
		}

		final long treeBytes = tree.getHeapSizeInBytes();
		long records = 0L;
		long bitmapBytes = 0L;
		int multiCount = 0;
		for (int i = 0; i < bucketCount; i++) {
			final ValueToRecordBitmap bucket = buckets[i];
			final int cardinality = bucket.size();
			records += cardinality;
			if (cardinality > 1) {
				multiCount++;
				bitmapBytes += bucket.getRecordIds().getHeapSizeInBytes();
			}
			final Serializable value = bucket.getValue();
			// probing only on first sight turns the coverage probe from O(sum K) into O(V_union) per domain, and it is
			// also the semantics the plan asks for - distinct reduced values missing from the owner, not occurrences
			if (domain.union.add(hashOf(value)) && domain.owner != null && !domain.owner.contains(value)) {
				domain.coverageGap++;
			}
		}

		if (records < bucketCount) {
			throw new GenericEvitaInternalError(
				"Tree of domain `" + domainKey + "` holds " + records + " records in " + bucketCount +
					" buckets - a bucket cannot hold fewer than one record!",
				"Value tree holds fewer records than buckets!"
			);
		}
		if (bitmapBytes > treeBytes) {
			throw new GenericEvitaInternalError(
				"Tree of domain `" + domainKey + "` charges " + bitmapBytes + " B of record bitmaps out of " +
					treeBytes + " B of tree - the bitmaps are part of the tree and cannot exceed it!",
				"Record bitmaps exceed the tree that holds them!"
			);
		}

		domain.addTree(bucketCount, records, treeBytes, bitmapBytes, multiCount, sortable);
		totals.treeCount++;
	}

	/* ====================================== classification ====================================== */

	/**
	 * Classifies an index kind into the role the census gives it. Exhaustive over {@link EntityIndexType} and throwing
	 * on anything unrecognised: a new kind that silently fell through would be missing from every number in the table
	 * while the table still looked complete.
	 *
	 * @param indexType the browsed index's kind
	 * @return the role the census gives that kind
	 */
	@Nonnull
	private static IndexRole roleOf(@Nonnull EntityIndexType indexType) {
		return switch (indexType) {
			case GLOBAL -> IndexRole.GLOBAL_OWNER;
			case REFERENCED_ENTITY_TYPE, REFERENCED_GROUP_ENTITY_TYPE -> IndexRole.REFERENCE_TYPE_OWNER;
			case REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY -> IndexRole.REDUCED;
		};
	}

	/**
	 * Classifies an owned sort tree by the shape of its values: a sortable attribute compound stores
	 * {@link ComparableArray} buckets, everything else is a sort-only scalar attribute.
	 *
	 * An empty tree cannot be classified from its contents and is treated as `OWNER_SORT`; it carries no buckets, so
	 * {@link #foldTree} drops it before the classification can affect any number.
	 *
	 * @param tree the owned value tree
	 * @return the domain kind it belongs to
	 */
	@Nonnull
	private static DomainKind kindOfOwnedTree(@Nonnull InvertedIndex tree) {
		final Serializable sample = sampleValueOf(tree);
		return sample instanceof ComparableArray ? DomainKind.COMPOUND : DomainKind.OWNER_SORT;
	}

	/**
	 * Reads one bucket value out of a tree through its cursor, without materializing the whole bucket array. Used
	 * wherever the census needs the *shape* of a tree's keys rather than its contents - classifying an owned sort tree
	 * and checking that a candidate canonical owner speaks the same value language as the domain asking for it.
	 *
	 * @param tree the tree to sample
	 * @return its first bucket value in ascending order, or `null` when the tree holds no buckets
	 */
	@Nullable
	private static Serializable sampleValueOf(@Nonnull InvertedIndex tree) {
		final Iterator<ValueToRecord> iterator = tree.getValueIterator();
		return iterator.hasNext() ? iterator.next().getValue() : null;
	}

	/**
	 * Decides whether a candidate canonical owner can hold a domain's values at all, by comparing the shape of the two
	 * sides' keys.
	 *
	 * This is not defensive padding. {@link InvertedIndex#contains} pushes the probe value through the *owner's* own
	 * normalizer and comparator, so probing a boxed `ComparableArray` against a front-coded string tree would not
	 * return `false` - it would throw a `ClassCastException` from inside the engine and take the whole census with it,
	 * several minutes into a walk. A candidate that speaks a different value language is not this domain's owner, and
	 * the resolution falls through to the next candidate exactly as if it had not been found.
	 *
	 * @param domainSample a value from the domain's own trees
	 * @param ownerSample  a value from the candidate owner, `null` when the candidate is empty
	 * @return `true` when the two sides carry keys of the same family
	 */
	private static boolean sameValueFamily(@Nonnull Serializable domainSample, @Nullable Serializable ownerSample) {
		if (ownerSample == null) {
			// an empty owner cannot contradict anything and can still adopt the domain's values
			return true;
		}
		if (domainSample instanceof ComparableArray || ownerSample instanceof ComparableArray) {
			return domainSample instanceof ComparableArray && ownerSample instanceof ComparableArray;
		}
		if (domainSample instanceof String || ownerSample instanceof String) {
			return domainSample instanceof String && ownerSample instanceof String;
		}
		return domainSample.getClass() == ownerSample.getClass();
	}

	/**
	 * Classifies a domain's value class from a sample bucket value.
	 *
	 * Only the two front-coded string classes and the boxed compound class are eligible for the savings ledger; a key
	 * that already sits in a primitive leaf column pays no front-coding overhead to reclaim, and sweeping such domains
	 * in is the "a dictionary always saves" fallacy this census exists to refute. See decision 4 in the class JavaDoc
	 * for why a boxed non-compound key lands in the same bucket.
	 *
	 * @param sample a value taken from the tree's first bucket
	 * @param locale the domain's locale, `null` when the attribute is not localized
	 * @return the value class of the domain
	 */
	@Nonnull
	private static ValueClass valueClassOf(@Nonnull Serializable sample, @Nullable Locale locale) {
		if (sample instanceof ComparableArray) {
			return ValueClass.COMPOUND;
		}
		if (sample instanceof String) {
			return locale == null ? ValueClass.STRING : ValueClass.LOCALIZED_STRING;
		}
		return ValueClass.ALREADY_PRIMITIVE;
	}

	/**
	 * Renders a value into the canonical string form the union hash is taken over. Strings are their own canonical
	 * form; a compound is joined element-wise with separators that cannot occur inside a rendered element, so two
	 * different compounds cannot collapse into one string.
	 *
	 * @param value the bucket value
	 * @return its canonical string form
	 */
	@Nonnull
	private static CharSequence canonicalFormOf(@Nonnull Serializable value) {
		if (value instanceof final String text) {
			return text;
		}
		if (value instanceof final ComparableArray compound) {
			final Serializable[] elements = compound.array();
			final StringBuilder result = new StringBuilder(16 * elements.length + 8);
			for (int i = 0; i < elements.length; i++) {
				// U+0000 marks an absent element and U+0001 separates them; neither can occur inside a rendered
				// element, so two different compounds can never collapse onto one canonical form
				if (elements[i] == null) {
					result.append('\u0000');
				} else {
					result.append(elements[i]);
				}
				result.append('\u0001');
			}
			return result;
		}
		return String.valueOf(value);
	}

	/**
	 * Hashes a bucket value with 64-bit FNV-1a over its canonical string form. Collisions at these cardinalities are
	 * noise: a domain holding a million distinct values has a collision probability around 3e-8 in a 64-bit space, two
	 * orders of magnitude below the rounding of every byte figure the table prints.
	 *
	 * @param value the bucket value
	 * @return its 64-bit hash
	 */
	private static long hashOf(@Nonnull Serializable value) {
		final CharSequence text = canonicalFormOf(value);
		long hash = 0xcbf29ce484222325L;
		final int length = text.length();
		for (int i = 0; i < length; i++) {
			final char character = text.charAt(i);
			hash ^= character & 0xFF;
			hash *= 0x100000001b3L;
			hash ^= character >>> 8;
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	/* ======================================== projection ======================================== */

	/**
	 * Prices the v1 candidate representation of one reduced tree, byte-exactly on the running VM's layout.
	 *
	 * The record bitmaps are deliberately outside this figure: they survive dedup unchanged and are subtracted from
	 * the current side of the ledger too, so counting them on either side would cancel out at best and mislead at
	 * worst. See decision 3 in the class JavaDoc for the one term that is knowingly charged twice.
	 *
	 * @param bucketCount how many buckets the tree holds
	 * @param multiCount  how many of them hold more than one record
	 * @param sortable    whether the domain also serves ordering
	 * @return the candidate spine in bytes
	 */
	private static long candidateSpineOf(int bucketCount, int multiCount, boolean sortable) {
		final VMLayout layout = VMLayout.current();
		final int referenceSize = layout.referenceSize();
		final long fixed = layout.sizeOfObject(4L * referenceSize);
		final long ids = layout.sizeOfArray(bucketCount, Integer.BYTES);
		long postings = layout.sizeOfArray(bucketCount, Integer.BYTES) + (long) multiCount * referenceSize;
		if (multiCount > 0) {
			postings += layout.sizeOfArray(bucketCount, referenceSize);
		}
		final long sortSlots = sortable ? layout.sizeOfArray(bucketCount, Integer.BYTES) : 0L;
		return fixed + ids + postings + sortSlots;
	}

	/**
	 * Prices what the canonical owner would have to start paying to host a domain's value ids: one exact-capacity
	 * `int` column per leaf, plus the allocator.
	 *
	 * The leaf capacity is read off the tree rather than assumed, because the inverted index's block size is a tuned
	 * constant that has already moved once and a hard-coded `256` would silently misprice every host increment the
	 * day it moves again.
	 *
	 * @param owner the canonical owner tree
	 * @return the id-column cost in bytes
	 */
	private static long hostIdColumnBytes(@Nonnull InvertedIndex owner) {
		final BucketBPlusTree<?> tree = bucketTreeOf(owner);
		if (!(tree instanceof final TransactionalBucketBPlusTree<?> transactionalTree)) {
			throw new GenericEvitaInternalError(
				"Bucket tree of an inverted index is a `" + tree.getClass().getName() +
					"`, which exposes no leaf capacity - the host increment cannot be priced!",
				"Bucket tree exposes no leaf capacity!"
			);
		}
		final int leafCount = tree.leafPageHandles().size();
		final int leafCapacity = transactionalTree.getValueBlockSize();
		return leafCount * VMLayout.current().sizeOfArray(leafCapacity, Integer.BYTES) + VALUE_ID_ALLOCATOR_BYTES;
	}

	/**
	 * Prices the reverse id-to-value directory a domain would need **if** it also had to resolve ids back to values -
	 * a `long[]` slot per allocated id plus the two per-leaf maps. Reported beside the net saving and never added to
	 * it: a dedup-only filter domain resolves nothing in reverse, and charging it here would be the census answering a
	 * question nobody asked.
	 *
	 * @param owner the canonical owner tree
	 * @return the informational directory cost in bytes
	 */
	private static long reverseDirectoryBytes(@Nonnull InvertedIndex owner) {
		final BucketBPlusTree<?> tree = bucketTreeOf(owner);
		return 8L * owner.getBucketCount() + (long) tree.leafPageHandles().size() * REVERSE_DIRECTORY_LEAF_BYTES;
	}

	/* ========================================= reporting ======================================== */

	/**
	 * Prints one row per domain - the table the census exists to produce.
	 *
	 * Rows are ordered by removable bytes descending, so the domains that could matter are at the top regardless of
	 * how the verdict came out; a large removable figure with a `LOSE` verdict is exactly the finding that stops the
	 * dedup line from being built for that shape.
	 *
	 * @param domains every domain of the catalog, already sorted
	 */
	private static void printDecisionTable(@Nonnull List<Domain> domains) {
		System.out.printf("%n=== DECISION TABLE (one row per domain) ===%n");
		System.out.printf(
			DECISION_HEADER_FORMAT,
			"entityType", "attribute", "locale", "scope", "kind", "class", "owner", "trees", "K p50", "K p95",
			"V_union", "M", "r", "removable", "spine", "host", "+dir", "net", "gap", "verdict"
		);
		for (int i = 0; i < domains.size(); i++) {
			final Domain domain = domains.get(i);
			System.out.printf(
				DECISION_ROW_FORMAT,
				trim(domain.key.entityType(), 18), trim(attributeLabelOf(domain.key), 24),
				trim(localeLabelOf(domain.key), 6), domain.key.scope(), domain.key.kind(),
				domain.valueClass.label(), domain.ownerLabel(), domain.treeCount, domain.bucketP50,
				domain.bucketP95, domain.unionSize, domain.recordCount, ratio(domain.bucketCount, domain.unionSize),
				bytes(domain.removableBytes), bytes(domain.spineBytes), bytes(domain.hostIncrementBytes),
				bytes(domain.reverseDirectoryBytes), signedBytes(domain.netSavingBytes), domain.coverageGap,
				domain.verdict.label()
			);
		}
		long overcharge = 0L;
		for (int i = 0; i < domains.size(); i++) {
			overcharge += domains.get(i).spineOverchargeBytes;
		}
		System.out.println(
			"  `owner` marked with a trailing `*` already carries value ids, so its host increment is zero - the\n" +
				"  trigram line paid for that column already. `+dir` is informational and is NOT part of `net`.\n" +
				"  `r` is sum(K) / V_union - how many trees replicate the average distinct value of the domain."
		);
		System.out.printf(
			"  spine includes %s of deliberate over-charge (one reference per multi-record bucket, on top of\n" +
				"  the exact-sized overflow array) - savings are understated by at most that much.%n",
			bytes(overcharge)
		);
	}

	/**
	 * Prints the catalog-wide roll-up across the tree-size strata - the single table that shows whether the prize
	 * sits in the tiny trees or the dense ones.
	 *
	 * @param domains every domain of the catalog
	 */
	private static void printCatalogStrataTable(@Nonnull List<Domain> domains) {
		System.out.printf("%n=== STRATA - WHOLE CATALOG (eligible domains only) ===%n");
		final Stratum[] rollUp = new Stratum[STRATA_LABELS.length];
		for (int i = 0; i < rollUp.length; i++) {
			rollUp[i] = new Stratum();
		}
		for (int i = 0; i < domains.size(); i++) {
			final Domain domain = domains.get(i);
			if (!domain.valueClass.eligible()) {
				continue;
			}
			for (int band = 0; band < rollUp.length; band++) {
				rollUp[band].addAll(domain.strata[band]);
			}
		}
		printStrataHeader();
		final Stratum total = new Stratum();
		for (int band = 0; band < rollUp.length; band++) {
			printStratumRow(STRATA_LABELS[band], rollUp[band]);
			total.addAll(rollUp[band]);
		}
		printStratumRow("TOTAL", total);
	}

	/**
	 * Prints the per-domain strata table, capped at {@link #STRATA_DOMAIN_LIMIT} domains by removable bytes. The TSV
	 * carries every stratum of every domain, so nothing is lost - only the console is spared a table that would run to
	 * thousands of lines on a fan-out catalog.
	 *
	 * @param domains every domain of the catalog, already sorted by removable bytes
	 */
	private static void printDomainStrataTable(@Nonnull List<Domain> domains) {
		final int limit = Math.min(STRATA_DOMAIN_LIMIT, domains.size());
		System.out.printf(
			"%n=== STRATA - PER DOMAIN (top %d of %d by removable bytes; full set in the TSV) ===%n",
			limit, domains.size()
		);
		for (int i = 0; i < limit; i++) {
			final Domain domain = domains.get(i);
			System.out.printf(
				"%n  %s / %s / %s / %s / %s  [%s, owner %s, %s]%n",
				domain.key.entityType(), attributeLabelOf(domain.key), localeLabelOf(domain.key),
				domain.key.scope(), domain.key.kind(), domain.valueClass.label(), domain.ownerLabel(),
				domain.verdict.label()
			);
			printStrataHeader();
			final Stratum total = new Stratum();
			for (int band = 0; band < STRATA_LABELS.length; band++) {
				if (domain.strata[band].treeCount == 0) {
					continue;
				}
				printStratumRow(STRATA_LABELS[band], domain.strata[band]);
				total.addAll(domain.strata[band]);
			}
			printStratumRow("TOTAL", total);
		}
	}

	/**
	 * Prints the shared header of every strata table.
	 */
	private static void printStrataHeader() {
		System.out.printf(
			"    %-10s %10s %13s %15s %13s %13s %13s%n",
			"K stratum", "trees", "sum K", "sum M", "removable", "spine", "saving"
		);
	}

	/**
	 * Prints one row of a strata table.
	 *
	 * @param label the stratum label
	 * @param stratum the stratum to render
	 */
	private static void printStratumRow(@Nonnull String label, @Nonnull Stratum stratum) {
		System.out.printf(
			"    %-10s %,10d %,13d %,15d %13s %13s %13s%n",
			label, stratum.treeCount, stratum.bucketCount, stratum.recordCount,
			bytes(stratum.removableBytes), bytes(stratum.spineBytes), signedBytes(stratum.savingBytes)
		);
	}

	/**
	 * Prints the catalog headline: what the dedup line would actually return, set against the A3 replication ceiling
	 * measured in the same walk.
	 *
	 * The ceiling is quoted twice on purpose. The attribute-heap figure is the number A3 reports and the one people
	 * remember; the value-tree figure is the part this line can actually reach, and the difference between them is the
	 * membership bitmaps, postings and scaffolding a reduced index keeps no matter what.
	 *
	 * @param domains every domain of the catalog
	 * @param totals  catalog-wide accumulators
	 */
	private static void printHeadline(@Nonnull List<Domain> domains, @Nonnull CatalogTotals totals) {
		long eligibleRemovable = 0L;
		long eligibleSpine = 0L;
		long eligibleHost = 0L;
		long eligibleNet = 0L;
		long eligibleDir = 0L;
		int eligibleDomains = 0;
		int skippedDomains = 0;
		int winners = 0;
		for (int i = 0; i < domains.size(); i++) {
			final Domain domain = domains.get(i);
			if (!domain.valueClass.eligible()) {
				skippedDomains++;
				continue;
			}
			eligibleDomains++;
			eligibleRemovable += domain.removableBytes;
			eligibleSpine += domain.spineBytes;
			eligibleHost += domain.hostIncrementBytes;
			eligibleDir += domain.reverseDirectoryBytes;
			eligibleNet += domain.netSavingBytes;
			if (domain.verdict == Verdict.WIN) {
				winners++;
			}
		}

		System.out.printf("%n=== HEADLINE - WHAT THE DEDUP LINE WOULD RETURN ===%n");
		System.out.printf(
			"reduced indexes walked                     : %,13d%n", totals.reducedIndexCount
		);
		System.out.printf(
			"A3 ceiling - reduced attribute heap        : %13s%n", bytes(totals.reducedAttributeBytes)
		);
		System.out.printf(
			"A3 ceiling - reduced value trees           : %13s (%s of the attribute heap above)%n",
			bytes(totals.reducedFilterTreeBytes),
			percent(totals.reducedFilterTreeBytes, totals.reducedAttributeBytes)
		);
		System.out.printf(
			"domains: %,d eligible, %,d skipped (already-primitive keys), %,d verdict WIN%n",
			eligibleDomains, skippedDomains, winners
		);
		System.out.printf(
			"  removable now (tree minus surviving bitmaps) : %13s%n", bytes(eligibleRemovable)
		);
		System.out.printf(
			"  candidate spine                              : %13s%n", bytes(eligibleSpine)
		);
		System.out.printf(
			"  host increment (owner id columns)            : %13s%n", bytes(eligibleHost)
		);
		System.out.printf(
			"  NET SAVING                                   : %13s (%s of the reduced attribute heap)%n",
			signedBytes(eligibleNet), percent(Math.max(eligibleNet, 0L), totals.reducedAttributeBytes)
		);
		System.out.printf(
			"  reverse directory if ever needed (excluded)  : %13s%n", bytes(eligibleDir)
		);
		System.out.println(
			"\nRead the net saving as the steady committed-state ledger only: no varints, no Roaring re-encoding\n" +
				"and no transactional-layer overhead are modelled, and `sortedRecords` stays out of both sides."
		);
	}

	/**
	 * Prints the self-checks. The A3 cross-check is reported rather than thrown on: by the time it runs, the tables
	 * are already on the console, and killing the process would destroy the evidence somebody needs to work out which
	 * of the two definitions drifted.
	 *
	 * @param domains every domain of the catalog
	 * @param totals  catalog-wide accumulators
	 */
	private static void printSelfChecks(@Nonnull List<Domain> domains, @Nonnull CatalogTotals totals) {
		System.out.printf("%n=== SELF-CHECKS ===%n");

		final long a3Reduced = totals.reducedFilterTreeBytes + totals.referenceTypeFilterTreeBytes;
		final long ownReduced = totals.reducedFilterTreeBytes;
		final double drift = a3Reduced == 0L ? 0.0d : (double) (a3Reduced - ownReduced) / a3Reduced;
		System.out.printf(
			"A3 cross-check - reduced value trees, A3 definition (every non-GLOBAL index) : %13s%n", bytes(a3Reduced)
		);
		System.out.printf(
			"A3 cross-check - reduced value trees, this census (FILTER-kind reduced only) : %13s%n", bytes(ownReduced)
		);
		System.out.printf(
			"A3 cross-check - drift                                                       : %12.2f%%  [%s]%n",
			100.0d * drift, Math.abs(drift) <= A3_TOLERANCE ? "PASS" : "FAIL - suspect this tool, not the catalog"
		);
		System.out.printf(
			"  (the two differ exactly by the %,d reference-type indexes, worth %s of value trees)%n",
			totals.referenceTypeIndexCount, bytes(totals.referenceTypeFilterTreeBytes)
		);

		System.out.printf("invariants - trees measured                     : %,13d%n", totals.treeCount);
		System.out.printf("invariants - empty trees skipped (K == 0)       : %,13d%n", totals.emptyTreeCount);
		System.out.println("invariants - M >= K >= 1 per tree               :          PASS (throws otherwise)");
		System.out.println("invariants - bitmapBytes <= treeBytes per tree  :          PASS (throws otherwise)");
		System.out.println("invariants - V_union <= sum K per domain        :          PASS (throws otherwise)");

		int entityLevelDomains = 0;
		int entityLevelGaps = 0;
		int missingOwners = 0;
		for (int i = 0; i < domains.size(); i++) {
			final Domain domain = domains.get(i);
			if (domain.ownerRole == OwnerRole.MISSING) {
				missingOwners++;
			}
			if (domain.key.attributeKey().referenceName() == null) {
				entityLevelDomains++;
				if (domain.coverageGap > 0) {
					entityLevelGaps++;
				}
			}
		}
		System.out.printf(
			"coverage   - entity-level domains with a gap    : %,13d of %,d  [%s]%n",
			entityLevelGaps, entityLevelDomains,
			entityLevelGaps == 0 ? "PASS" : "INVESTIGATE - union-owner death is falsified for those domains"
		);
		System.out.printf(
			"coverage   - domains with no canonical owner    : %,13d of %,d%n", missingOwners, domains.size()
		);
	}

	/**
	 * Writes the machine-readable report: one `DOMAIN` line per domain, then one `STRATUM` line per non-empty
	 * stratum of every domain, sharing a single column set so the file loads as one table.
	 *
	 * @param domains     every domain of the catalog
	 * @param catalogName the catalog label, used in the file name
	 * @param reportDir   the directory to write into
	 */
	private static void writeTsv(
		@Nonnull List<Domain> domains,
		@Nonnull String catalogName,
		@Nonnull Path reportDir
	) throws IOException {
		Files.createDirectories(reportDir);
		final Path target = reportDir.resolve("value-dedup-census-" + catalogName + ".tsv");
		try (
			final BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)
		) {
			writer.write(
				"section\tentityType\treferenceName\tattributeName\tlocale\tscope\tkind\tvalueClass\towner\t" +
					"sharedWithTrigram\tstratum\ttrees\tsumK\tsumM\tkP50\tkP95\tvUnion\treplication\ttreeBytes\t" +
					"bitmapBytes\tremovableBytes\tspineBytes\thostIncrementBytes\treverseDirectoryBytes\t" +
					"netSavingBytes\tcoverageGap\tverdict\n"
			);
			for (int i = 0; i < domains.size(); i++) {
				final Domain domain = domains.get(i);
				writeDomainRow(writer, domain);
				for (int band = 0; band < STRATA_LABELS.length; band++) {
					if (domain.strata[band].treeCount > 0) {
						writeStratumRow(writer, domain, STRATA_LABELS[band], domain.strata[band]);
					}
				}
			}
		}
		System.out.printf("%nTSV written to `%s`.%n", target);
	}

	/**
	 * Writes one `DOMAIN` line of the TSV.
	 *
	 * @param writer the open writer
	 * @param domain the domain to write
	 */
	private static void writeDomainRow(@Nonnull BufferedWriter writer, @Nonnull Domain domain) throws IOException {
		final StringBuilder row = new StringBuilder(320);
		appendDomainIdentity(row, "DOMAIN", domain);
		row.append("ALL").append('\t')
			.append(domain.treeCount).append('\t')
			.append(domain.bucketCount).append('\t')
			.append(domain.recordCount).append('\t')
			.append(domain.bucketP50).append('\t')
			.append(domain.bucketP95).append('\t')
			.append(domain.unionSize).append('\t')
			.append(ratio(domain.bucketCount, domain.unionSize)).append('\t')
			.append(domain.treeBytes).append('\t')
			.append(domain.bitmapBytes).append('\t')
			.append(domain.removableBytes).append('\t')
			.append(domain.spineBytes).append('\t')
			.append(domain.hostIncrementBytes).append('\t')
			.append(domain.reverseDirectoryBytes).append('\t')
			.append(domain.netSavingBytes).append('\t')
			.append(domain.coverageGap).append('\t')
			.append(domain.verdict.label()).append('\n');
		writer.write(row.toString());
	}

	/**
	 * Writes one `STRATUM` line of the TSV. The columns that only make sense for a whole domain - the distinct-value
	 * count, the host increment, the coverage gap and the verdict - are left empty rather than repeated, so a reader
	 * summing a column cannot double-count them.
	 *
	 * @param writer  the open writer
	 * @param domain  the domain the stratum belongs to
	 * @param label   the stratum label
	 * @param stratum the stratum to write
	 */
	private static void writeStratumRow(
		@Nonnull BufferedWriter writer,
		@Nonnull Domain domain,
		@Nonnull String label,
		@Nonnull Stratum stratum
	) throws IOException {
		final StringBuilder row = new StringBuilder(320);
		appendDomainIdentity(row, "STRATUM", domain);
		row.append(label).append('\t')
			.append(stratum.treeCount).append('\t')
			.append(stratum.bucketCount).append('\t')
			.append(stratum.recordCount).append('\t')
			.append('\t').append('\t').append('\t').append('\t')
			.append(stratum.treeBytes).append('\t')
			.append(stratum.bitmapBytes).append('\t')
			.append(stratum.removableBytes).append('\t')
			.append(stratum.spineBytes).append('\t')
			.append('\t').append('\t')
			.append(stratum.savingBytes).append('\t')
			.append('\t')
			.append('\n');
		writer.write(row.toString());
	}

	/**
	 * Appends the section marker and the eight identity columns shared by both TSV row shapes.
	 *
	 * @param row     the row being assembled
	 * @param section the section marker of the row being written
	 * @param domain  the domain being written
	 */
	private static void appendDomainIdentity(
		@Nonnull StringBuilder row,
		@Nonnull String section,
		@Nonnull Domain domain
	) {
		final AttributeIndexKey key = domain.key.attributeKey();
		row.append(section).append('\t')
			.append(domain.key.entityType()).append('\t')
			.append(key.referenceName() == null ? "" : key.referenceName()).append('\t')
			.append(key.attributeName()).append('\t')
			.append(key.locale() == null ? "" : key.locale().toLanguageTag()).append('\t')
			.append(domain.key.scope()).append('\t')
			.append(domain.key.kind()).append('\t')
			.append(domain.valueClass.label()).append('\t')
			.append(domain.ownerRole).append('\t')
			.append(domain.sharedWithTrigram).append('\t');
	}

	/* ========================================== support ========================================= */

	/**
	 * Pages through every index of a collection in {@link IndexBrowseOrdering#MAP_ORDER} - the one ordering documented
	 * as the cheap exhaustive walk and exempted from the ranked-paging depth cap - resolving each row back to its live
	 * index and handing both to the visitor.
	 *
	 * Primary keys are sparse, so the older "scan primary keys until `getIndexCount()` hits" approach can stop before
	 * the last index; a census that misses indexes answers the wrong question.
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
				final BrowsedIndex row = rows[i];
				final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(row.indexPrimaryKey());
				if (index == null) {
					// the browse read an immutable snapshot and this spike opens no writing session, so an index named
					// by a row must still be resolvable - a miss is a defect, not a race to tolerate
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
			// only a catalog-level index reports no kind, and a collection browse cannot return one
			throw new GenericEvitaInternalError(
				"Collection `" + collection.getEntityType() + "` browsed index `" + row.indexPrimaryKey() +
					"` without an index type!",
				"Collection browse returned an index without a type!"
			);
		}
		return indexType;
	}

	/**
	 * Opens one declared field for reading.
	 *
	 * The spike runs from the shaded benchmark jar on the class path, so every evitaDB class sits in the unnamed module
	 * and the field opens without an `--add-opens` flag. A failure here means the field was renamed or the jar was put
	 * on the module path, and either way the census cannot produce its numbers - so it fails at class initialization
	 * rather than reporting a ledger it silently could not compute.
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
				"Cannot open `" + owner.getSimpleName() + "#" + fieldName + "` - the census reads it because no " +
					"accessor exposes it.",
				"Cannot open a field the census needs!",
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
	 * Reads the bucket B+ tree beneath an inverted index.
	 *
	 * @param invertedIndex the index to read
	 * @return its bucket tree
	 */
	@Nonnull
	private static BucketBPlusTree<?> bucketTreeOf(@Nonnull InvertedIndex invertedIndex) {
		try {
			return (BucketBPlusTree<?>) BUCKETS_FIELD.get(invertedIndex);
		} catch (final IllegalAccessException e) {
			throw new GenericEvitaInternalError(
				"Cannot read `InvertedIndex#buckets`!",
				"Cannot read the bucket tree of an inverted index!",
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
		final String configured = System.getProperty(WORK_DIR_PROPERTY);
		return configured == null || configured.isBlank()
			? Files.createTempDirectory("evita-value-dedup-census")
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
		final String configured = System.getProperty(REPORT_DIR_PROPERTY);
		return configured == null || configured.isBlank() ? workDir : Path.of(configured);
	}

	/**
	 * Copies the named catalog out of the snapshot into the working directory, so that boot-time WAL recovery and
	 * storage compaction cannot alter the snapshot every later run has to start from.
	 *
	 * Only the `&lt;catalogName&gt;/` subfolder of the working directory is deleted beforehand - never the working
	 * directory itself, which may be a location the caller keeps other things in.
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
						"compression, the snapshot was written with `" + COMPRESS_PROPERTY + "` disabled.",
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
					ValueDedupCensus.class.getSimpleName() + " JavaDoc for the full list.",
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
	 * Renders the attribute half of a domain key - `reference.attribute` for a reference-level attribute, the bare
	 * attribute name otherwise.
	 *
	 * @param key the domain key
	 * @return the rendered label
	 */
	@Nonnull
	private static String attributeLabelOf(@Nonnull DomainKey key) {
		final AttributeIndexKey attributeKey = key.attributeKey();
		if (attributeKey.referenceName() == null) {
			return attributeKey.attributeName();
		}
		final StringBuilder label = new StringBuilder(48);
		return label.append(attributeKey.referenceName()).append('.').append(attributeKey.attributeName()).toString();
	}

	/**
	 * Renders the locale of a domain key, or a dash when the attribute is not localized.
	 *
	 * @param key the domain key
	 * @return the rendered locale tag
	 */
	@Nonnull
	private static String localeLabelOf(@Nonnull DomainKey key) {
		final Locale locale = key.attributeKey().locale();
		return locale == null ? "-" : locale.toLanguageTag();
	}

	/**
	 * Truncates a label to a column width, marking the truncation so a reader never mistakes a cut name for a real one.
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
	 * Renders a byte count that may legitimately be negative, keeping the sign explicit so a loss never reads as a
	 * gain in a scanned column.
	 *
	 * @param value the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String signedBytes(long value) {
		return value > 0L ? "+" + bytes(value) : bytes(value);
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
		return String.format(Locale.ROOT, "%.2f", (double) part / whole);
	}

	/* =========================================== state ========================================== */

	/**
	 * What the census does with an index of a given kind.
	 */
	private enum IndexRole {
		/** The one GLOBAL index of a collection and scope - the first canonical-owner candidate. */
		GLOBAL_OWNER,
		/** A reference-type index - the canonical-owner candidate for reference-level attributes. */
		REFERENCE_TYPE_OWNER,
		/** A per-referenced-entity index - the fan-out the dedup line targets. */
		REDUCED
	}

	/**
	 * Which structure a domain's trees come from.
	 */
	private enum DomainKind {
		/** The front-coded filter value tree shared by the filter index and any folded sort view over it. */
		FILTER,
		/** The private value tree of a sort-only scalar attribute. */
		OWNER_SORT,
		/** The private value tree of a sortable attribute compound, keyed by boxed `ComparableArray` values. */
		COMPOUND
	}

	/**
	 * The shape of a domain's keys, which decides whether it can pay for dedup at all.
	 */
	private enum ValueClass {
		/** Non-localized `String` keys in a front-coded column. */
		STRING("STR", true),
		/** Localized `String` keys in a front-coded column, ordered by collation. */
		LOCALIZED_STRING("LOC_STR", true),
		/** Boxed `ComparableArray` keys of a sortable attribute compound. */
		COMPOUND("CMPND", true),
		/** Keys that already sit in a primitive (or boxed non-compound) leaf column - excluded from the ledger. */
		ALREADY_PRIMITIVE("PRIM", false);

		private final String label;
		private final boolean eligible;

		ValueClass(@Nonnull String label, boolean eligible) {
			this.label = label;
			this.eligible = eligible;
		}

		/**
		 * @return the short label the tables print
		 */
		@Nonnull
		String label() {
			return this.label;
		}

		/**
		 * @return `true` when domains of this class enter the savings ledger
		 */
		boolean eligible() {
			return this.eligible;
		}
	}

	/**
	 * Where a domain's canonical value owner was found.
	 */
	private enum OwnerRole {
		/** The GLOBAL index's filter tree for the same attribute key. */
		GLOBAL,
		/** A reference-type index's filter tree for the same attribute key. */
		REF_TYPE,
		/** The GLOBAL index's owner-mode sort tree, for a sort-only or compound domain. */
		GLOBAL_SORT,
		/** No canonical owner exists - the union-owner assumption does not hold for this domain. */
		MISSING
	}

	/**
	 * The census's verdict on a domain.
	 */
	private enum Verdict {
		/** Net saving above the marginal band - worth the per-domain opt-in. */
		WIN("WIN"),
		/** Net loss below the marginal band - dedup costs more than it returns here. */
		LOSE("LOSE"),
		/** Net saving inside the marginal band around zero - not worth the machinery. */
		MARGINAL("MARGINAL"),
		/** Already-primitive keys - nothing to reclaim, excluded from the ledger. */
		SKIP("SKIP"),
		/** The canonical owner does not hold every reduced value - union-owner death is falsified here. */
		GAP("GAP!");

		private final String label;

		Verdict(@Nonnull String label) {
			this.label = label;
		}

		/**
		 * @return the label the tables print
		 */
		@Nonnull
		String label() {
			return this.label;
		}
	}

	/**
	 * Identity of one domain: the semantic unit a canonical value space would be scoped to.
	 *
	 * Scope is a separate component rather than part of `attributeKey`, because {@link AttributeIndexKey} carries only
	 * reference name, attribute name and locale - see decision 1 in the class JavaDoc.
	 *
	 * @param entityType   the collection the domain belongs to
	 * @param scope        live or archived data set
	 * @param attributeKey reference name, attribute (or compound) name and locale
	 * @param kind         which structure the domain's trees come from
	 */
	private record DomainKey(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull AttributeIndexKey attributeKey,
		@Nonnull DomainKind kind
	) {
	}

	/**
	 * Lookup key of the canonical-owner registry. The reference name is `null` for a GLOBAL owner and names the
	 * reference for a reference-type owner, which keeps two references' identically-named attributes apart.
	 *
	 * @param scope         live or archived data set
	 * @param referenceName the owning reference, `null` for the GLOBAL index
	 * @param attributeKey  the attribute key the tree serves
	 */
	private record OwnerKey(
		@Nonnull Scope scope,
		@Nullable String referenceName,
		@Nonnull AttributeIndexKey attributeKey
	) {
	}

	/**
	 * The canonical-owner candidates of one collection, filled by the first pass and read by the second.
	 */
	private static final class OwnerRegistry {
		/** GLOBAL index filter trees, keyed by scope and attribute key. */
		private final Map<OwnerKey, InvertedIndex> globalFilterOwners = new HashMap<>(64);
		/** Reference-type index filter trees, keyed by scope, reference name and attribute key. */
		private final Map<OwnerKey, InvertedIndex> referenceTypeFilterOwners = new HashMap<>(64);
		/** GLOBAL index owner-mode sort trees, keyed by scope and attribute key. */
		private final Map<OwnerKey, InvertedIndex> globalSortOwners = new HashMap<>(32);

		/**
		 * Resolves the canonical owner of a domain, in the order the design fixes: the GLOBAL filter tree first
		 * (entity-level attributes), then the reference-type filter tree of the reduced index's own reference
		 * (reference-level attributes), then the GLOBAL owner-sort tree (sort-only and compound domains).
		 *
		 * @param domainKey     the domain being resolved
		 * @param referenceName the reference the reduced index belongs to
		 * @return the binding, with role `MISSING` and no tree when nothing owns these values
		 */
		@Nonnull
		OwnerBinding resolve(
			@Nonnull DomainKey domainKey,
			@Nullable String referenceName,
			@Nonnull Serializable domainSample
		) {
			final OwnerKey globalKey = new OwnerKey(domainKey.scope(), null, domainKey.attributeKey());
			final InvertedIndex global = this.globalFilterOwners.get(globalKey);
			if (global != null && sameValueFamily(domainSample, sampleValueOf(global))) {
				return new OwnerBinding(OwnerRole.GLOBAL, global);
			}
			final InvertedIndex referenceType = this.referenceTypeFilterOwners.get(
				new OwnerKey(domainKey.scope(), referenceName, domainKey.attributeKey())
			);
			if (referenceType != null && sameValueFamily(domainSample, sampleValueOf(referenceType))) {
				return new OwnerBinding(OwnerRole.REF_TYPE, referenceType);
			}
			final InvertedIndex globalSort = this.globalSortOwners.get(globalKey);
			if (globalSort != null && sameValueFamily(domainSample, sampleValueOf(globalSort))) {
				return new OwnerBinding(OwnerRole.GLOBAL_SORT, globalSort);
			}
			return new OwnerBinding(OwnerRole.MISSING, null);
		}
	}

	/**
	 * A resolved canonical owner - where it was found and the tree itself.
	 *
	 * @param role where the owner was found
	 * @param tree the owner tree, `null` only when the role is `MISSING`
	 */
	private record OwnerBinding(@Nonnull OwnerRole role, @Nullable InvertedIndex tree) {
	}

	/**
	 * One tree-size stratum of a domain: the same six figures the decision table carries, restricted to the trees
	 * whose bucket count falls in this band.
	 */
	private static final class Stratum {
		/** How many trees fell in this band. */
		private long treeCount;
		/** Sum of their bucket counts. */
		private long bucketCount;
		/** Sum of their record counts. */
		private long recordCount;
		/** Sum of their measured heap. */
		private long treeBytes;
		/** Sum of the record bitmaps that survive dedup. */
		private long bitmapBytes;
		/** Sum of `treeBytes - bitmapBytes`. */
		private long removableBytes;
		/** Sum of the projected candidate spines. */
		private long spineBytes;
		/** Sum of `removableBytes - spineBytes`. */
		private long savingBytes;

		/**
		 * Folds another stratum in, so the catalog roll-up needs no second walk.
		 *
		 * @param other the stratum to add
		 */
		void addAll(@Nonnull Stratum other) {
			this.treeCount += other.treeCount;
			this.bucketCount += other.bucketCount;
			this.recordCount += other.recordCount;
			this.treeBytes += other.treeBytes;
			this.bitmapBytes += other.bitmapBytes;
			this.removableBytes += other.removableBytes;
			this.spineBytes += other.spineBytes;
			this.savingBytes += other.savingBytes;
		}
	}

	/**
	 * One domain's running totals, its strata and its verdict.
	 *
	 * A mutable class rather than a record: a domain accumulates over thousands of trees encountered across a whole
	 * collection walk, and its owner binding, union map and percentiles are all filled in at different points of that
	 * walk.
	 */
	private static final class Domain {
		/** Identity of the domain. */
		@Nonnull private final DomainKey key;
		/** Shape of its keys, taken from the first tree's first bucket. */
		@Nonnull private final ValueClass valueClass;
		/** Distinct-value hashes across every tree of the domain; released by {@link #finish()}. */
		@Nonnull private LongHashCounter union = new LongHashCounter(64);
		/** Bucket counts of every tree, kept for the percentiles. */
		@Nonnull private int[] bucketCounts = new int[16];
		/** Per-band totals, aligned with {@link #STRATA_LABELS}. */
		@Nonnull private final Stratum[] strata = new Stratum[STRATA_LABELS.length];
		/** Where the canonical owner was found. */
		@Nonnull private OwnerRole ownerRole = OwnerRole.MISSING;
		/** The canonical owner tree, `null` when none was found. */
		@Nullable private InvertedIndex owner;
		/** `true` when the owner already carries value ids, so the host increment is zero. */
		private boolean sharedWithTrigram;
		/** How many trees the domain holds. */
		private long treeCount;
		/** Sum of their bucket counts. */
		private long bucketCount;
		/** Sum of their record counts. */
		private long recordCount;
		/** Sum of their measured heap. */
		private long treeBytes;
		/** Sum of the record bitmaps that survive dedup. */
		private long bitmapBytes;
		/** Sum of `treeBytes - bitmapBytes`. */
		private long removableBytes;
		/** Sum of the projected candidate spines. */
		private long spineBytes;
		/** The part of {@link #spineBytes} that is the deliberate over-charge of decision 3. */
		private long spineOverchargeBytes;
		/** What the owner would have to start paying to host this domain's ids. */
		private long hostIncrementBytes;
		/** Informational cost of a reverse directory; never part of the net. */
		private long reverseDirectoryBytes;
		/** `sum(saving) - hostIncrement`. */
		private long netSavingBytes;
		/** How many distinct reduced values the owner does not hold. */
		private long coverageGap;
		/** Distinct values across the domain, computed by {@link #finish()}. */
		private long unionSize;
		/** Median bucket count across the domain's trees. */
		private int bucketP50;
		/** 95th-percentile bucket count across the domain's trees. */
		private int bucketP95;
		/** The verdict, computed by {@link #finish()}. */
		@Nonnull private Verdict verdict = Verdict.MARGINAL;

		Domain(@Nonnull DomainKey key, @Nonnull ValueClass valueClass) {
			this.key = key;
			this.valueClass = valueClass;
			for (int i = 0; i < this.strata.length; i++) {
				this.strata[i] = new Stratum();
			}
		}

		/**
		 * Attaches the resolved canonical owner.
		 *
		 * @param binding the resolved owner
		 */
		void bindOwner(@Nonnull OwnerBinding binding) {
			this.ownerRole = binding.role();
			this.owner = binding.tree();
			this.sharedWithTrigram = this.owner != null && this.owner.carriesValueIds();
		}

		/**
		 * Folds one measured tree into the domain and into its stratum.
		 *
		 * @param buckets     the tree's bucket count
		 * @param records     the tree's record count
		 * @param treeBytes   the tree's measured heap
		 * @param bitmapBytes the record bitmaps of its multi-record buckets
		 * @param multiCount  how many buckets hold more than one record
		 * @param sortable    whether the tree also serves ordering
		 */
		void addTree(int buckets, long records, long treeBytes, long bitmapBytes, int multiCount, boolean sortable) {
			final long removable = treeBytes - bitmapBytes;
			final long spine = candidateSpineOf(buckets, multiCount, sortable);
			final long saving = removable - spine;

			this.treeCount++;
			this.bucketCount += buckets;
			this.recordCount += records;
			this.treeBytes += treeBytes;
			this.bitmapBytes += bitmapBytes;
			this.removableBytes += removable;
			this.spineBytes += spine;
			this.spineOverchargeBytes += (long) multiCount * VMLayout.current().referenceSize();

			if (this.treeCount > this.bucketCounts.length) {
				this.bucketCounts = Arrays.copyOf(this.bucketCounts, this.bucketCounts.length * 2);
			}
			this.bucketCounts[(int) this.treeCount - 1] = buckets;

			final Stratum stratum = this.strata[stratumOf(buckets)];
			stratum.treeCount++;
			stratum.bucketCount += buckets;
			stratum.recordCount += records;
			stratum.treeBytes += treeBytes;
			stratum.bitmapBytes += bitmapBytes;
			stratum.removableBytes += removable;
			stratum.spineBytes += spine;
			stratum.savingBytes += saving;
		}

		/**
		 * Closes the domain: computes the distinct-value count and percentiles, prices the host increment, and settles
		 * the verdict. Releases the union map and the percentile buffer, which together dominate the census's live
		 * heap on a fan-out catalog.
		 */
		void finish() {
			this.unionSize = this.union.size();
			if (this.unionSize > this.bucketCount) {
				throw new GenericEvitaInternalError(
					"Domain `" + this.key + "` holds " + this.unionSize + " distinct values across only " +
						this.bucketCount + " buckets - a distinct value needs a bucket to live in!",
					"A domain holds more distinct values than buckets!"
				);
			}
			this.union = new LongHashCounter(1);

			final int treeTotal = (int) this.treeCount;
			final int[] sorted = Arrays.copyOf(this.bucketCounts, treeTotal);
			Arrays.sort(sorted);
			this.bucketP50 = treeTotal == 0 ? 0 : sorted[Math.min(treeTotal - 1, treeTotal / 2)];
			this.bucketP95 = treeTotal == 0 ? 0 : sorted[Math.min(treeTotal - 1, (int) (treeTotal * 0.95d))];
			this.bucketCounts = new int[0];

			if (this.owner != null) {
				this.hostIncrementBytes = this.sharedWithTrigram ? 0L : hostIdColumnBytes(this.owner);
				this.reverseDirectoryBytes = reverseDirectoryBytes(this.owner);
			}
			this.netSavingBytes = (this.removableBytes - this.spineBytes) - this.hostIncrementBytes;

			if (!this.valueClass.eligible()) {
				this.verdict = Verdict.SKIP;
			} else if (this.coverageGap > 0L) {
				this.verdict = Verdict.GAP;
			} else if (this.netSavingBytes > (long) (MARGINAL_BAND * this.removableBytes)) {
				this.verdict = Verdict.WIN;
			} else if (this.netSavingBytes < -(long) (MARGINAL_BAND * this.removableBytes)) {
				this.verdict = Verdict.LOSE;
			} else {
				this.verdict = Verdict.MARGINAL;
			}
			// the owner is a live engine structure; dropping the reference keeps the census from pinning anything the
			// catalog itself would otherwise be free to replace
			this.owner = null;
		}

		/**
		 * Renders the owner column, marking an owner that already carries value ids so a zero host increment can be
		 * told apart from a missing one.
		 *
		 * @return the rendered owner label
		 */
		@Nonnull
		String ownerLabel() {
			return this.sharedWithTrigram ? this.ownerRole + "*" : this.ownerRole.name();
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
	}

	/**
	 * Catalog-wide accumulators that are not per domain: the two halves of the A3 cross-check, the index and tree
	 * counts, and the attribute-heap split the headline sets the net saving against.
	 */
	private static final class CatalogTotals {
		/** Attribute-index heap of the GLOBAL indexes. */
		private long globalAttributeBytes;
		/** Attribute-index heap of the reference-type indexes. */
		private long referenceTypeAttributeBytes;
		/** Attribute-index heap of the reduced indexes - the A3 replication ceiling. */
		private long reducedAttributeBytes;
		/** Filter value trees of the GLOBAL indexes. */
		private long globalFilterTreeBytes;
		/** Filter value trees of the reference-type indexes - the difference between the two A3 definitions. */
		private long referenceTypeFilterTreeBytes;
		/** Filter value trees of the reduced indexes. */
		private long reducedFilterTreeBytes;
		/** How many GLOBAL indexes were walked. */
		private int globalIndexCount;
		/** How many reference-type indexes were walked. */
		private int referenceTypeIndexCount;
		/** How many reduced indexes were walked. */
		private int reducedIndexCount;
		/** How many reduced value trees were measured. */
		private long treeCount;
		/** How many reduced value trees held no buckets and were skipped. */
		private long emptyTreeCount;
	}

	/**
	 * An open-addressed `long` to `int` counter, used as the per-domain union map.
	 *
	 * A `HashMap<Long, Integer>` would box both halves of every entry, and a domain of a fan-out catalog holds
	 * hundreds of thousands of them. A zero count marks a free slot, which is unambiguous here because every entry is
	 * created by an increment and therefore never reads zero.
	 */
	private static final class LongHashCounter {
		private long[] keys;
		private int[] counts;
		private int size;
		private int mask;

		LongHashCounter(int initialCapacity) {
			int capacity = 4;
			while (capacity < initialCapacity) {
				capacity <<= 1;
			}
			this.keys = new long[capacity];
			this.counts = new int[capacity];
			this.mask = capacity - 1;
		}

		/**
		 * Increments the count of one key.
		 *
		 * @param key the key to count
		 * @return `true` when the key had not been seen before
		 */
		boolean add(long key) {
			int slot = slotOf(key, this.mask);
			while (this.counts[slot] != 0) {
				if (this.keys[slot] == key) {
					this.counts[slot]++;
					return false;
				}
				slot = (slot + 1) & this.mask;
			}
			this.keys[slot] = key;
			this.counts[slot] = 1;
			this.size++;
			if (this.size * 4 > this.keys.length * 3) {
				grow();
			}
			return true;
		}

		/**
		 * @return how many distinct keys have been counted
		 */
		int size() {
			return this.size;
		}

		/**
		 * Doubles the table and re-inserts every live entry.
		 */
		private void grow() {
			final long[] oldKeys = this.keys;
			final int[] oldCounts = this.counts;
			final int newCapacity = oldKeys.length << 1;
			final long[] newKeys = new long[newCapacity];
			final int[] newCounts = new int[newCapacity];
			final int newMask = newCapacity - 1;
			for (int i = 0; i < oldKeys.length; i++) {
				if (oldCounts[i] == 0) {
					continue;
				}
				int slot = slotOf(oldKeys[i], newMask);
				while (newCounts[slot] != 0) {
					slot = (slot + 1) & newMask;
				}
				newKeys[slot] = oldKeys[i];
				newCounts[slot] = oldCounts[i];
			}
			this.keys = newKeys;
			this.counts = newCounts;
			this.mask = newMask;
		}

		/**
		 * Folds a 64-bit key onto a table slot. The keys are already FNV-1a hashes, so only the fold is needed.
		 *
		 * @param key  the key
		 * @param mask the table mask
		 * @return the starting slot
		 */
		private static int slotOf(long key, int mask) {
			return (int) (key ^ (key >>> 32)) & mask;
		}
	}

	/**
	 * Callback the index walk hands each resolved index to.
	 */
	@FunctionalInterface
	private interface IndexVisitor {

		/**
		 * Visits one index of a collection.
		 *
		 * @param index the live index
		 * @param row   the browsed row that named it
		 */
		void visit(@Nonnull EntityIndex index, @Nonnull BrowsedIndex row);
	}

}
