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
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.FilterIndexView;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.VMLayout;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Ranks every in-memory index structure of a real catalog by the heap it occupies, so a memory campaign chooses its
 * next target from a measurement rather than from a hunch.
 *
 * # Why this exists
 *
 * {@link RangeIndexFootprintProbe} and {@link PriceIndexFootprintProbe} each price **one** structure family, which
 * answers "is this port worth doing" but never "which port is worth doing first". The engine's own statistics surface
 * stops one step short of the answer too: {@link io.evitadb.api.statistics.IndexDetail} reports a single
 * `heapSizeInBytes` per named index and does not decompose it, so a catalog-wide Pareto over structure families has to
 * be walked. This probe walks it.
 *
 * # What is measured exactly, and what is a residual
 *
 * Every family below is charged by asking the structure's own `getHeapSizeInBytes()` — the same accounting
 * {@link io.evitadb.api.statistics.IndexDetail} reports and the one `EntityIndexHeapSizeTest` cross-checks against a
 * JOL walk. Nothing here models a layout.
 *
 * What the public surface cannot reach is **not estimated**. The census sums {@link EntityIndex#getHeapSizeInBytes()}
 * independently and reports the difference as one explicit `residual` row: the hierarchy index, the entity-id bitmaps,
 * the `ReferencedTypeEntityIndex` cardinality and histogram state, the {@link io.evitadb.index.facet.FacetIndex} shell
 * above its per-reference indexes, and the whole {@link io.evitadb.index.attribute.AttributeIndex} map scaffolding
 * priced by {@link AttributeIndexScaffoldingProbe}. A residual is a subtraction between two measured quantities, which
 * is the one thing that can be said about the unreachable part without inventing a model of it.
 *
 * # The two double-counting hazards
 *
 * **A reduced entity index's price index holds instances the super index also holds**, and **a filter index is a view
 * over a value tree the attribute index charges once**. Both are handled the same way: every structure is folded
 * through an {@link IdentityHashMap} and charged once, while the number of *references* to it is counted separately so
 * the sharing ratio is visible rather than assumed. The Pareto table reports distinct bytes; the residual arithmetic
 * uses referenced bytes, because that is what the entity-index totals it is subtracted from actually charge.
 *
 * The filter family carries a third case the type system makes explicit: an {@link OwnerFilterIndex} charges its value
 * tree and range index itself, whereas a {@link FilterIndexView} charges neither. The two are told apart by their type
 * rather than by a convention, and a third implementation reaching here fails the run instead of being folded into
 * whichever branch happens to come first.
 *
 * # The price-record census
 *
 * The same walk answers a second question issue #1486 needs: how many **distinct** {@link PriceRecordContract}
 * instances a catalog holds against how many times they are referenced, split by concrete class — the input to any
 * columnar-layout arithmetic over price bodies. It is folded through its own identity map, which is sized for tens of
 * millions of entries; `probe.priceRecords=false` switches it off when that memory is wanted elsewhere.
 *
 * # Running it
 *
 * <pre>
 * java -Xmx24g -cp &lt;performance-tests classpath&gt; \
 *      -Dprobe.catalog=&lt;name&gt; -Dprobe.dataDir=/path/holding/the/catalog/folder -Dprobe.copyData=false \
 *      io.evitadb.spike.trigram.CatalogIndexFootprintCensus
 * </pre>
 *
 * Keep `-Xmx` **below 32 GB**: above it the VM leaves the compressed-oops regime, every reference becomes 8 bytes and
 * the printed figures stop meaning what the sibling probes' figures mean. Prepend every reactor `target/classes` ahead
 * of the resolved classpath, or the run measures whichever build the local repository happens to hold.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogIndexFootprintCensus {

	/**
	 * Name of the catalog to open.
	 */
	private static final String CATALOG_NAME_PROPERTY = "probe.catalog";
	/**
	 * Directory holding the catalog folder.
	 */
	private static final String DATA_DIR_PROPERTY = "probe.dataDir";
	/**
	 * Whether to copy the catalog into a scratch directory before opening it.
	 */
	private static final String COPY_DATA_PROPERTY = "probe.copyData";
	/**
	 * Whether the snapshot was written with compression enabled.
	 */
	private static final String COMPRESS_PROPERTY = "probe.compress";
	/**
	 * Whether to run the distinct price-record census, which needs an identity map over every price record.
	 */
	private static final String PRICE_RECORDS_PROPERTY = "probe.priceRecords";
	/**
	 * How many attribute names the per-attribute table prints.
	 */
	private static final int TOP_ATTRIBUTES = 10;
	/**
	 * How many families the per-collection table prints for each collection.
	 */
	private static final int TOP_FAMILIES_PER_COLLECTION = 5;
	/**
	 * How long to wait for the catalog to finish its asynchronous load before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * Every structure family this census can charge exactly.
	 */
	private enum Family {

		/**
		 * Per-attribute unique indexes, standalone owners and folded views alike.
		 */
		ATTRIBUTE_UNIQUE("attribute unique"),
		/**
		 * Per-attribute filter indexes — the view object and its query memos, without the tree beneath it.
		 */
		ATTRIBUTE_FILTER("attribute filter"),
		/**
		 * The value trees the filter (and folded unique) views read, charged once per tree.
		 */
		ATTRIBUTE_VALUE_TREE("attribute value tree"),
		/**
		 * The range indexes filter views over range-typed attributes read, charged once per index.
		 */
		ATTRIBUTE_RANGE("attribute range"),
		/**
		 * Per-attribute sort indexes.
		 */
		ATTRIBUTE_SORT("attribute sort"),
		/**
		 * Per-attribute chain indexes.
		 */
		ATTRIBUTE_CHAIN("attribute chain"),
		/**
		 * Price super indexes, which own their price records.
		 */
		PRICE_SUPER("price super index"),
		/**
		 * Price reference indexes, which borrow the super index's records.
		 */
		PRICE_REF("price ref index"),
		/**
		 * Per-reference facet indexes.
		 */
		FACET("facet reference index");

		/**
		 * How the family prints.
		 */
		private final String label;

		/**
		 * Creates a family.
		 *
		 * @param label how the family prints
		 */
		Family(@Nonnull String label) {
			this.label = label;
		}

		/**
		 * @return how the family prints
		 */
		@Nonnull
		String label() {
			return this.label;
		}

	}

	/**
	 * Entry point.
	 *
	 * @param args ignored — every input is a system property
	 */
	public static void main(@Nonnull String[] args) {
		try {
			run();
		} catch (final IOException e) {
			throw new GenericEvitaInternalError("Catalog index footprint census failed!", e.getMessage(), e);
		}
	}

	/**
	 * Opens the catalog, walks every index and prints the census tables.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(DATA_DIR_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(System.getProperty(COPY_DATA_PROPERTY, "true"));
		final boolean compress = Boolean.parseBoolean(System.getProperty(COMPRESS_PROPERTY, "true"));
		final boolean priceRecords = Boolean.parseBoolean(System.getProperty(PRICE_RECORDS_PROPERTY, "true"));
		final Path storageDir = copyData ? copyCatalog(dataDir, catalogName) : dataDir;

		System.out.printf("Catalog index footprint census - catalog `%s` from `%s`%n", catalogName, storageDir);
		System.out.printf("VM layout: %s%n", VMLayout.current());
		System.out.printf("max heap: %s%n%n", humanReadable(Runtime.getRuntime().maxMemory()));

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
			// taken BEFORE the walk, because the walk's own identity maps are hundreds of megabytes and would
			// otherwise be reported as catalog residency
			final long usedHeapAfterLoad = usedHeapAfterCollection();
			System.out.printf("JVM used heap after load and two collections: %s%n", humanReadable(usedHeapAfterLoad));

			final CatalogIndexFootprintCensus census = new CatalogIndexFootprintCensus(priceRecords);
			census.walk(catalog);
			census.print(catalog, usedHeapAfterLoad);
		}
	}

	/**
	 * Whether the distinct price-record census runs.
	 */
	private final boolean priceRecordCensus;
	/**
	 * Every structure charged so far, so a shared one is charged once.
	 */
	private final Map<Object, Boolean> chargedStructures = new IdentityHashMap<>(1 << 20);
	/**
	 * Every price record charged so far, or `null` when the price-record census is switched off.
	 */
	@Nullable private final Map<PriceRecordContract, Boolean> chargedPriceRecords;
	/**
	 * Catalog-wide tallies, one per family.
	 */
	private final Map<Family, Tally> catalogTallies = new EnumMap<>(Family.class);
	/**
	 * Per-collection tallies, one map per entity type.
	 */
	private final Map<String, Map<Family, Tally>> perCollectionTallies = new TreeMap<>();
	/**
	 * Per-attribute-name tallies, summed over every attribute family.
	 */
	private final Map<String, Tally> perAttributeTallies = new HashMap<>(256);
	/**
	 * Per-collection entity-index counts and totals, so the residual can be shown per collection too.
	 */
	private final Map<String, Tally> perCollectionEntityIndexes = new TreeMap<>();
	/**
	 * Distinct and referenced price-record counts, keyed by concrete class name.
	 */
	private final Map<String, long[]> priceRecordClasses = new TreeMap<>();
	/**
	 * How many entity indexes the walk visited.
	 */
	private long entityIndexCount;
	/**
	 * What those entity indexes say they occupy, in total.
	 */
	private long entityIndexTotalBytes;
	/**
	 * How many times a price record was referenced by a price index.
	 */
	private long priceRecordReferences;
	/**
	 * How many distinct price records were seen.
	 */
	private long priceRecordDistinct;

	/**
	 * Creates a census.
	 *
	 * @param priceRecordCensus whether to count distinct price records, which costs an identity map over all of them
	 */
	private CatalogIndexFootprintCensus(boolean priceRecordCensus) {
		this.priceRecordCensus = priceRecordCensus;
		this.chargedPriceRecords = priceRecordCensus ? new IdentityHashMap<>(1 << 22) : null;
	}

	/**
	 * Walks every collection's entity indexes and charges every structure the public surface reaches.
	 *
	 * @param catalog the loaded catalog
	 */
	private void walk(@Nonnull Catalog catalog) {
		for (final String entityType : new TreeSet<>(catalog.getEntityTypes())) {
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			final Map<Family, Tally> collectionTallies = this.perCollectionTallies
				.computeIfAbsent(entityType, key -> new EnumMap<>(Family.class));
			final Tally entityIndexTally = this.perCollectionEntityIndexes
				.computeIfAbsent(entityType, key -> new Tally());
			browse(collection, index -> {
				final long indexBytes = index.getHeapSizeInBytes();
				this.entityIndexCount++;
				this.entityIndexTotalBytes += indexBytes;
				entityIndexTally.add(indexBytes, true);
				chargeAttributes(index, collectionTallies);
				chargePrices(index, collectionTallies);
				chargeFacets(index, collectionTallies);
			});
		}
	}

	/**
	 * Charges every attribute sub-index one entity index reaches through {@link io.evitadb.index.attribute.AttributeIndexContract}.
	 *
	 * @param index             the entity index being walked
	 * @param collectionTallies the tallies of the collection it belongs to
	 */
	private void chargeAttributes(@Nonnull EntityIndex index, @Nonnull Map<Family, Tally> collectionTallies) {
		for (final AttributeIndexKey key : index.getUniqueIndexes()) {
			final UniqueIndex uniqueIndex = index.getUniqueIndex(key);
			if (uniqueIndex != null) {
				charge(Family.ATTRIBUTE_UNIQUE, uniqueIndex, uniqueIndex.getHeapSizeInBytes(), collectionTallies, key);
			}
		}
		for (final AttributeIndexKey key : index.getSortIndexes()) {
			final SortIndex sortIndex = index.getSortIndex(key);
			if (sortIndex != null) {
				charge(Family.ATTRIBUTE_SORT, sortIndex, sortIndex.getHeapSizeInBytes(), collectionTallies, key);
			}
		}
		for (final AttributeIndexKey key : index.getChainIndexes()) {
			final ChainIndex chainIndex = index.getChainIndex(key);
			if (chainIndex != null) {
				charge(Family.ATTRIBUTE_CHAIN, chainIndex, chainIndex.getHeapSizeInBytes(), collectionTallies, key);
			}
		}
		for (final AttributeIndexKey key : index.getFilterIndexes()) {
			final FilterIndex filterIndex = index.getFilterIndex(key);
			if (filterIndex == null) {
				continue;
			}
			charge(Family.ATTRIBUTE_FILTER, filterIndex, filterIndex.getHeapSizeInBytes(), collectionTallies, key);
			// an owner charges its tree and its range index inside the figure just charged; a view charges neither, so
			// only a view's tree and range index are charged again here. Getting this wrong in either direction moves
			// hundreds of megabytes between two rows of the same table, which is why it is a type test and not a
			// convention
			if (filterIndex instanceof FilterIndexView) {
				final InvertedIndex invertedIndex = filterIndex.getInvertedIndex();
				charge(
					Family.ATTRIBUTE_VALUE_TREE, invertedIndex, invertedIndex.getHeapSizeInBytes(),
					collectionTallies, key
				);
				final RangeIndex rangeIndex = filterIndex.getRangeIndex();
				if (rangeIndex != null) {
					charge(
						Family.ATTRIBUTE_RANGE, rangeIndex, rangeIndex.getHeapSizeInBytes(), collectionTallies, key
					);
				}
			} else if (!(filterIndex instanceof OwnerFilterIndex)) {
				throw new GenericEvitaInternalError(
					"Filter index implementation `" + filterIndex.getClass().getName() + "` is neither an owner nor " +
						"a view - teach this census which of the two it accounts like before trusting the total.",
					"Unknown filter index implementation!"
				);
			}
		}
	}

	/**
	 * Charges every price index one entity index owns, and folds its price records into the distinct-record census.
	 *
	 * @param index             the entity index being walked
	 * @param collectionTallies the tallies of the collection it belongs to
	 */
	private void chargePrices(@Nonnull EntityIndex index, @Nonnull Map<Family, Tally> collectionTallies) {
		final PriceIndexContract priceIndex = index.getPriceIndex();
		for (final PriceListAndCurrencyPriceIndex<?> perPriceList : priceIndex.getPriceListAndCurrencyIndexes()) {
			final boolean firstSight;
			if (perPriceList instanceof final PriceListAndCurrencyPriceSuperIndex superIndex) {
				firstSight = charge(
					Family.PRICE_SUPER, superIndex, superIndex.getHeapSizeInBytes(), collectionTallies, null
				);
			} else if (perPriceList instanceof final PriceListAndCurrencyPriceRefIndex refIndex) {
				firstSight = charge(
					Family.PRICE_REF, refIndex, refIndex.getHeapSizeInBytes(), collectionTallies, null
				);
			} else {
				throw new GenericEvitaInternalError(
					"Price index implementation `" + perPriceList.getClass().getName() + "` prices itself through no " +
						"accessor this census knows - teach it before trusting the total.",
					"Unknown price index implementation!"
				);
			}
			if (this.priceRecordCensus && firstSight) {
				chargePriceRecords(perPriceList);
			}
		}
	}

	/**
	 * Folds one price index's records into the distinct-record census.
	 *
	 * Only a **first-sighted** price index contributes, so a price index reached twice does not inflate the reference
	 * count with references that do not exist. The sharing this counts is the one between a super index and the
	 * reference indexes that borrow from it, which is a different instance each time.
	 *
	 * @param priceIndex the price index whose records to fold in
	 */
	private void chargePriceRecords(@Nonnull PriceListAndCurrencyPriceIndex<?> priceIndex) {
		final Map<PriceRecordContract, Boolean> charged = this.chargedPriceRecords;
		if (charged == null) {
			throw new GenericEvitaInternalError(
				"The price-record census ran without its identity map - the two are switched on and off together.",
				"Price record census ran without its identity map!"
			);
		}
		final PriceRecordContract[] records = priceIndex.getPriceRecords();
		for (int i = 0; i < records.length; i++) {
			final PriceRecordContract record = records[i];
			this.priceRecordReferences++;
			final long[] counters = this.priceRecordClasses
				.computeIfAbsent(record.getClass().getSimpleName(), key -> new long[2]);
			counters[1]++;
			if (charged.put(record, Boolean.TRUE) == null) {
				this.priceRecordDistinct++;
				counters[0]++;
			}
		}
	}

	/**
	 * Charges every per-reference facet index one entity index owns.
	 *
	 * The {@link io.evitadb.index.facet.FacetIndex} shell above them has no public accessor, so it stays in the
	 * residual — it is one object and one map spine per entity index, which is scaffolding rather than payload.
	 *
	 * @param index             the entity index being walked
	 * @param collectionTallies the tallies of the collection it belongs to
	 */
	private void chargeFacets(@Nonnull EntityIndex index, @Nonnull Map<Family, Tally> collectionTallies) {
		for (final Map.Entry<String, FacetReferenceIndex> entry : index.getFacetingEntities().entrySet()) {
			final FacetReferenceIndex facetIndex = entry.getValue();
			charge(Family.FACET, facetIndex, facetIndex.getHeapSizeInBytes(), collectionTallies, null);
		}
	}

	/**
	 * Charges one structure to its family, its collection and — when it belongs to an attribute — its attribute name.
	 *
	 * @param family            the family the structure belongs to
	 * @param structure         the structure itself, used for identity only
	 * @param heapBytes         what it says it occupies
	 * @param collectionTallies the tallies of the collection it was reached from
	 * @param attributeKey      the attribute it belongs to, or `null` for a structure that belongs to no attribute
	 * @return whether this is the first time the structure was seen
	 */
	private boolean charge(
		@Nonnull Family family,
		@Nonnull Object structure,
		long heapBytes,
		@Nonnull Map<Family, Tally> collectionTallies,
		@Nullable AttributeIndexKey attributeKey
	) {
		final boolean firstSight = this.chargedStructures.put(structure, Boolean.TRUE) == null;
		this.catalogTallies.computeIfAbsent(family, key -> new Tally()).add(heapBytes, firstSight);
		collectionTallies.computeIfAbsent(family, key -> new Tally()).add(heapBytes, firstSight);
		if (attributeKey != null) {
			this.perAttributeTallies
				.computeIfAbsent(attributeKey.attributeName(), key -> new Tally())
				.add(heapBytes, firstSight);
		}
		return firstSight;
	}

	/**
	 * Prints every table this census produces.
	 *
	 * @param catalog           the catalog that was walked
	 * @param usedHeapAfterLoad the JVM's used heap right after the load, before the walk allocated anything
	 */
	private void print(@Nonnull Catalog catalog, long usedHeapAfterLoad) {
		final long accountedReferencedBytes = referencedBytesTotal();
		final long residual = this.entityIndexTotalBytes - accountedReferencedBytes;

		System.out.printf("%n=== PARETO - EVERY INDEX STRUCTURE THE PUBLIC SURFACE REACHES ===%n");
		System.out.printf(
			"%-24s %12s %14s %14s %9s %12s%n",
			"family", "distinct", "references", "heap (distinct)", "share", "B / instance"
		);
		final List<Map.Entry<Family, Tally>> ranked = new ArrayList<>(this.catalogTallies.entrySet());
		ranked.sort(Comparator.comparingLong((Map.Entry<Family, Tally> e) -> e.getValue().distinctBytes).reversed());
		for (final Map.Entry<Family, Tally> entry : ranked) {
			final Tally tally = entry.getValue();
			System.out.printf(
				"%-24s %,12d %,14d %14s %8s%% %,12d%n",
				entry.getKey().label(), tally.distinctInstances, tally.references,
				humanReadable(tally.distinctBytes), share(tally.distinctBytes, this.entityIndexTotalBytes),
				tally.distinctInstances == 0 ? 0L : tally.distinctBytes / tally.distinctInstances
			);
		}
		System.out.printf(
			"%-24s %,12d %14s %14s %8s%% %,12d%n",
			"residual (unreached)", this.entityIndexCount, "-", humanReadable(residual),
			share(residual, this.entityIndexTotalBytes),
			this.entityIndexCount == 0 ? 0L : residual / this.entityIndexCount
		);
		System.out.printf(
			"%n%-24s %,12d %14s %14s %8s%%%n",
			"ENTITY INDEX TOTAL", this.entityIndexCount, "-", humanReadable(this.entityIndexTotalBytes), "100.0"
		);
		System.out.printf(
			"  charged once: %s   charged per reference: %s   sharing: %.2fx%n",
			humanReadable(distinctBytesTotal()), humanReadable(accountedReferencedBytes),
			distinctBytesTotal() == 0L ? 0.0d : (double) accountedReferencedBytes / distinctBytesTotal()
		);
		System.out.println(
			"  residual = entity index total - referenced family bytes. It holds the hierarchy index, the entity-id\n" +
				"  bitmaps, the reference-type cardinality and histogram state, the facet index shell and the whole\n" +
				"  attribute-index map scaffolding - none of which the public surface exposes as an object to price."
		);

		printCatalogIndexes(catalog);
		printPerCollection();
		printPerAttribute();
		printPriceRecords();

		System.out.printf("%n=== WHAT THE ACCOUNTING EXPLAINS ===%n");
		System.out.printf("  JVM used heap after load, two collections : %s%n", humanReadable(usedHeapAfterLoad));
		System.out.printf("  entity index accounting                   : %s%n", humanReadable(this.entityIndexTotalBytes));
		System.out.printf(
			"  share of used heap explained             : %s%%%n",
			share(this.entityIndexTotalBytes, usedHeapAfterLoad)
		);
	}

	/**
	 * Prints the catalog-level indexes, which hold the global unique values and belong to no collection.
	 *
	 * @param catalog the catalog that was walked
	 */
	private void printCatalogIndexes(@Nonnull Catalog catalog) {
		System.out.printf("%n=== CATALOG-LEVEL INDEXES (global unique) ===%n");
		System.out.printf("%-24s %14s%n", "scope", "heap");
		long total = 0L;
		for (final Scope scope : Scope.values()) {
			final CatalogIndex catalogIndex = catalog.getCatalogIndexIfExits(scope).orElse(null);
			if (catalogIndex == null) {
				System.out.printf("%-24s %14s%n", scope.name(), "-");
			} else {
				final long bytes = catalogIndex.getHeapSizeInBytes();
				total += bytes;
				System.out.printf("%-24s %14s%n", scope.name(), humanReadable(bytes));
			}
		}
		System.out.printf("%-24s %14s%n", "TOTAL", humanReadable(total));
	}

	/**
	 * Prints, for every collection, the families that dominate it.
	 */
	private void printPerCollection() {
		System.out.printf("%n=== PER COLLECTION - TOP %d FAMILIES ===%n", TOP_FAMILIES_PER_COLLECTION);
		for (final Map.Entry<String, Map<Family, Tally>> entry : this.perCollectionTallies.entrySet()) {
			final Tally entityIndexes = this.perCollectionEntityIndexes.get(entry.getKey());
			if (entityIndexes == null || entityIndexes.distinctInstances == 0L) {
				continue;
			}
			long referenced = 0L;
			for (final Tally tally : entry.getValue().values()) {
				referenced += tally.referencedBytes;
			}
			System.out.printf(
				"%n  %s - %,d entity indexes, %s total, %s residual%n",
				entry.getKey(), entityIndexes.distinctInstances, humanReadable(entityIndexes.distinctBytes),
				humanReadable(entityIndexes.distinctBytes - referenced)
			);
			final List<Map.Entry<Family, Tally>> ranked = new ArrayList<>(entry.getValue().entrySet());
			ranked.sort(
				Comparator.comparingLong((Map.Entry<Family, Tally> e) -> e.getValue().distinctBytes).reversed()
			);
			final int rows = Math.min(TOP_FAMILIES_PER_COLLECTION, ranked.size());
			for (int i = 0; i < rows; i++) {
				final Map.Entry<Family, Tally> row = ranked.get(i);
				System.out.printf(
					"      %-22s %,12d %14s %8s%%%n",
					row.getKey().label(), row.getValue().distinctInstances,
					humanReadable(row.getValue().distinctBytes),
					share(row.getValue().distinctBytes, entityIndexes.distinctBytes)
				);
			}
		}
	}

	/**
	 * Prints the attribute names carrying the most heap across every attribute family.
	 */
	private void printPerAttribute() {
		System.out.printf("%n=== TOP %d ATTRIBUTE NAMES (every attribute family summed) ===%n", TOP_ATTRIBUTES);
		final List<Map.Entry<String, Tally>> ranked = new ArrayList<>(this.perAttributeTallies.entrySet());
		ranked.sort(Comparator.comparingLong((Map.Entry<String, Tally> e) -> e.getValue().distinctBytes).reversed());
		System.out.printf("%-40s %12s %14s %9s%n", "attribute", "distinct", "heap", "share");
		final int rows = Math.min(TOP_ATTRIBUTES, ranked.size());
		for (int i = 0; i < rows; i++) {
			final Map.Entry<String, Tally> row = ranked.get(i);
			System.out.printf(
				"%-40s %,12d %14s %8s%%%n",
				row.getKey(), row.getValue().distinctInstances, humanReadable(row.getValue().distinctBytes),
				share(row.getValue().distinctBytes, this.entityIndexTotalBytes)
			);
		}
	}

	/**
	 * Prints the distinct price-record census, or the reason there is none.
	 */
	private void printPriceRecords() {
		System.out.printf("%n=== PRICE RECORDS - DISTINCT INSTANCES BY IDENTITY ===%n");
		if (!this.priceRecordCensus) {
			System.out.printf("  switched off by -D%s=false%n", PRICE_RECORDS_PROPERTY);
			return;
		}
		System.out.printf("%-36s %14s %16s %10s%n", "class", "distinct", "references", "sharing");
		for (final Map.Entry<String, long[]> entry : this.priceRecordClasses.entrySet()) {
			final long[] counters = entry.getValue();
			System.out.printf(
				"%-36s %,14d %,16d %9.2fx%n",
				entry.getKey(), counters[0], counters[1],
				counters[0] == 0L ? 0.0d : (double) counters[1] / counters[0]
			);
		}
		System.out.printf(
			"%-36s %,14d %,16d %9.2fx%n",
			"TOTAL", this.priceRecordDistinct, this.priceRecordReferences,
			this.priceRecordDistinct == 0L ? 0.0d : (double) this.priceRecordReferences / this.priceRecordDistinct
		);
	}

	/**
	 * @return the family bytes summed as the entity-index totals charge them, once per reference
	 */
	private long referencedBytesTotal() {
		long total = 0L;
		for (final Tally tally : this.catalogTallies.values()) {
			total += tally.referencedBytes;
		}
		return total;
	}

	/**
	 * @return the family bytes summed once per distinct instance, which is the real heap they occupy
	 */
	private long distinctBytesTotal() {
		long total = 0L;
		for (final Tally tally : this.catalogTallies.values()) {
			total += tally.distinctBytes;
		}
		return total;
	}

	/**
	 * Runs two collections and reports what the JVM still holds, which is the closest a probe gets to resident heap
	 * without a heap dump.
	 *
	 * @return the used heap in bytes
	 */
	private static long usedHeapAfterCollection() {
		final Runtime runtime = Runtime.getRuntime();
		System.gc();
		System.gc();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	/**
	 * Visits every index of a collection, one browse page at a time.
	 *
	 * Browsing in {@link IndexBrowseOrdering#MAP_ORDER} rather than scanning primary keys upward is deliberate: index
	 * primary keys are sparse, so a scan bounded by the index count stops short of the last index.
	 *
	 * @param collection the collection to walk
	 * @param visitor    invoked once per live index
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
			for (final BrowsedIndex row : rows) {
				final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(row.indexPrimaryKey());
				if (index == null) {
					// the browse read an immutable snapshot and this census opens no writing session, so an index
					// named by a row must still be resolvable - a miss is a defect, not a race to tolerate
					throw new GenericEvitaInternalError(
						"Index `" + row.indexPrimaryKey() + "` of collection `" + collection.getEntityType() +
							"` was browsed but cannot be resolved!",
						"Browsed index cannot be resolved!"
					);
				}
				visitor.visit(index);
			}
			visited += rows.length;
			pageNumber++;
		}
	}

	/**
	 * Waits for the catalog's asynchronous load to finish, refusing to measure one that failed to open.
	 *
	 * @param evita       the running instance
	 * @param catalogName the catalog to wait for
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
	 * Copies the catalog folder into a scratch directory so the run never mutates the snapshot it measures.
	 *
	 * @param dataDir     directory holding the catalog folder
	 * @param catalogName the catalog folder's name
	 * @return the scratch directory to point the storage options at
	 */
	@Nonnull
	private static Path copyCatalog(@Nonnull Path dataDir, @Nonnull String catalogName) throws IOException {
		final Path workDir = Files.createTempDirectory("index-census-");
		FileUtils.copyDirectory(
			dataDir.resolve(catalogName).toFile(), workDir.resolve(catalogName).toFile()
		);
		return workDir;
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
					CatalogIndexFootprintCensus.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Renders one quantity's share of another.
	 *
	 * @param part  the part
	 * @param whole the whole
	 * @return the share, to one decimal place
	 */
	@Nonnull
	private static String share(long part, long whole) {
		return whole == 0L ? "-" : String.format("%.1f", 100.0d * part / whole);
	}

	/**
	 * Renders a byte count in the unit a reader can compare at a glance.
	 *
	 * @param bytes the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String humanReadable(long bytes) {
		if (bytes > -1024L && bytes < 1024L) {
			return bytes + " B";
		} else if (bytes > -1024L * 1024L && bytes < 1024L * 1024L) {
			return String.format("%.1f KB", bytes / 1024.0d);
		} else {
			return String.format("%.1f MB", bytes / (1024.0d * 1024.0d));
		}
	}

	/**
	 * Receives every live index of a collection.
	 */
	@FunctionalInterface
	private interface IndexVisitor {

		/**
		 * Visits one index.
		 *
		 * @param index the live index
		 */
		void visit(@Nonnull EntityIndex index);

	}

	/**
	 * Counters accumulated for one family, one collection or one attribute name.
	 *
	 * Both a distinct and a referenced total are kept, because the two answer different questions: the distinct total
	 * is the heap the structures really occupy, while the referenced total is what the entity-index figures charge and
	 * is therefore the only one the residual may be subtracted from.
	 */
	private static class Tally {

		/**
		 * How many distinct instances were charged.
		 */
		private long distinctInstances;
		/**
		 * How many times an index pointed at one, shared instances included.
		 */
		private long references;
		/**
		 * What the distinct instances say they occupy.
		 */
		private long distinctBytes;
		/**
		 * What they occupy summed once per reference, as the enclosing index figures charge them.
		 */
		private long referencedBytes;

		/**
		 * Folds one sighting into this tally.
		 *
		 * @param heapBytes  what the structure says it occupies
		 * @param firstSight whether this is the first time the structure was seen
		 */
		void add(long heapBytes, boolean firstSight) {
			this.references++;
			this.referencedBytes += heapBytes;
			if (firstSight) {
				this.distinctInstances++;
				this.distinctBytes += heapBytes;
			}
		}

	}

}
