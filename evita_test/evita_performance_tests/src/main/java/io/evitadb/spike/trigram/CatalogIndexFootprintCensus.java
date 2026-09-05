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
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
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
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
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
	 * Whether to decompose the four largest families into their components.
	 */
	private static final String DECOMPOSE_PROPERTY = "probe.decompose";
	/**
	 * What one empty {@link io.evitadb.index.attribute.AttributeIndex} reports, measured directly by
	 * {@link AttributeIndexScaffoldingProbe} against the engine's own accounting.
	 *
	 * Pinned here rather than re-measured because the sibling probe measures it from a directly constructed empty
	 * index, which this census has no reason to build. If `AttributeIndex` gains or loses a field, re-measure it there
	 * and update this constant — the scaffolding row of the residual table is the only thing that depends on it.
	 */
	private static final long EMPTY_ATTRIBUTE_INDEX_BYTES = 680L;
	/**
	 * Initial capacity of {@link io.evitadb.index.EntityIndex}'s flush-ordering list, replicated from its own
	 * `INITIAL_COMPONENT_CAPACITY`, which is private. The list never shrinks below it, so the array it charges is
	 * `max(this, size())` slots.
	 */
	private static final int INITIAL_COMPONENT_CAPACITY = 8;
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

			final boolean decompose = Boolean.parseBoolean(System.getProperty(DECOMPOSE_PROPERTY, "true"));
			final CatalogIndexFootprintCensus census = new CatalogIndexFootprintCensus(priceRecords, decompose);
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
	 * Whether the four largest families are decomposed into their components.
	 */
	private final boolean decompose;
	/**
	 * Component breakdown of the price super indexes.
	 */
	private final Breakdown priceSuperBreakdown = new Breakdown();
	/**
	 * Component breakdown of the price reference indexes.
	 */
	private final Breakdown priceRefBreakdown = new Breakdown();
	/**
	 * Component breakdown of the attribute sort indexes.
	 */
	private final Breakdown sortBreakdown = new Breakdown();
	/**
	 * Component breakdown of the entity-index residual.
	 */
	private final Breakdown residualBreakdown = new Breakdown();
	/**
	 * One representative sort index per attribute name, so the top-ten table can name the value type it sorts on.
	 */
	private final Map<String, SortIndex> sortSamples = new HashMap<>(256);
	/**
	 * Per-attribute-name sort-index heap, so the sort table can rank attributes independently of the other families.
	 */
	private final Map<String, Tally> sortPerAttribute = new HashMap<>(256);
	/**
	 * How many sort indexes are owners, views, and how many sit in a global rather than a reduced index.
	 */
	private long sortOwners;
	/**
	 * How many sort indexes are stateless views over a shared value tree.
	 */
	private long sortViews;
	/**
	 * How many sort indexes sit in a global entity index.
	 */
	private long sortInGlobalIndexes;
	/**
	 * How many record ids the sort indexes order between them.
	 */
	private long sortedRecordCount;
	/**
	 * How many distinct values the sort indexes order by, summed.
	 */
	private long sortDistinctValueCount;
	/**
	 * How many entity indexes of each concrete class the walk visited.
	 */
	private final Map<String, Long> entityIndexClasses = new TreeMap<>();
	/**
	 * How many hierarchy nodes the catalog's entity indexes hold, orphans included.
	 */
	private long hierarchyNodeCount;
	/**
	 * How many per-language entity-id bitmaps exist across every entity index.
	 */
	private long languageBitmapCount;
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
	private CatalogIndexFootprintCensus(boolean priceRecordCensus, boolean decompose) {
		this.priceRecordCensus = priceRecordCensus;
		this.decompose = decompose;
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
				if (this.decompose) {
					decomposeEntityIndex(index);
				}
			});
		}
	}

	/**
	 * Enumerates the components of {@link EntityIndex#getHeapSizeInBytes()} that the family walk does not reach, so
	 * the residual row of the Pareto table stops being one undifferentiated number.
	 *
	 * Two rows are **exact** — the entity-id bitmap and the hierarchy orphan bitmap are both handed out by a public
	 * accessor as the very instance the index charges. The rest are marked `(inferred)` and are the implementation's
	 * own arithmetic applied to a publicly observable count: they are reproducible from the source, but a change to
	 * that source silently changes them, which an exact reading could never do.
	 *
	 * @param index the entity index to decompose
	 */
	private void decomposeEntityIndex(@Nonnull EntityIndex index) {
		final VMLayout layout = VMLayout.current();
		this.residualBreakdown.total(0L);
		this.entityIndexClasses.merge(index.getClass().getSimpleName(), 1L, Long::sum);
		this.residualBreakdown.add("entity id bitmap", index.getAllPrimaryKeys().getHeapSizeInBytes());
		this.residualBreakdown.add("hierarchy orphan bitmap", index.getOrphanHierarchyNodes().getHeapSizeInBytes());
		final int nodes = index.getHierarchySizeIncludingOrphans();
		this.hierarchyNodeCount += nodes;
		if (nodes > 0) {
			// HierarchyIndex#getHeapSizeInBytes: a boxed key, the node record itself, and a boxed parent for every
			// node that has one - assumed here for all of them, since only the roots do not and there are few
			this.residualBreakdown.add(
				"hierarchy nodes (inferred)",
				nodes * (2L * layout.sizeOfObject(Integer.BYTES)
					+ layout.sizeOfObject(Integer.BYTES + layout.referenceSize()))
			);
		}
		this.languageBitmapCount += index.getLanguages().size();
		this.residualBreakdown.add("attribute index scaffolding (inferred)", EMPTY_ATTRIBUTE_INDEX_BYTES);
		this.residualBreakdown.add("dirty flag (inferred)", layout.sizeOfObject(Long.BYTES + 1L));
		this.residualBreakdown.add(
			"entity index object shell (inferred)",
			layout.sizeOfObject(
				Long.BYTES + 2L * Integer.BYTES + 2L + 13L * layout.referenceSize()
					+ ownFieldBytesOf(index) * layout.referenceSize()
			)
		);
		this.residualBreakdown.add(
			"flush ordering list and wrappers (inferred)",
			layout.sizeOfObject(2L * Integer.BYTES + layout.referenceSize())
				+ layout.sizeOfArray(
					Math.max(INITIAL_COMPONENT_CAPACITY, index.getRegisteredComponents().size()), layout.referenceSize()
				)
				+ layout.sizeOfObject(2L * layout.referenceSize())
		);
		if (index.getActivity() != null) {
			this.residualBreakdown.add("usage activity holder (inferred)", layout.sizeOfObject(5L * Long.BYTES));
		}
	}

	/**
	 * Reports how many reference slots the concrete entity index class declares beyond the base's own thirteen.
	 *
	 * Replicated from each class's `getHeapSizeInBytes`, because the field count is what the object header arithmetic
	 * turns on and no accessor reports it. An unknown implementation fails the run rather than being charged the
	 * base's own figure, which would understate it silently.
	 *
	 * @param index the entity index to classify
	 * @return how many extra reference-sized fields the concrete class declares
	 */
	private static long ownFieldBytesOf(@Nonnull EntityIndex index) {
		if (index instanceof GlobalEntityIndex) {
			// the priceIndex and trigramIndex slots
			return 2L;
		} else if (index instanceof ReferencedTypeEntityIndex) {
			// priceIndex, indexPrimaryKeyCardinality, cardinalityIndexes, histogramIndexes, histogramComponent
			return 5L;
		} else if (index instanceof ReducedGroupEntityIndex) {
			// the reduced base's priceIndex slot, plus cardinalityDirty / pkCardinalities
			// / referencedPrimaryKeysIndex / cardinalityIndexes / histogramIndexes / histogramComponent
			return 7L;
		} else if (index instanceof ReducedEntityIndex) {
			// the reduced base's priceIndex slot alone
			return 1L;
		}
		throw new GenericEvitaInternalError(
			"Entity index implementation `" + index.getClass().getName() + "` declares a field count this census " +
				"does not know - teach it before trusting the shell row.",
			"Unknown entity index implementation!"
		);
	}

	/**
	 * Reads a price index's footprint through whichever concrete accessor it declares.
	 *
	 * @param priceIndex the index to price
	 * @return what it says it occupies
	 */
	private static long priceIndexHeapOf(@Nonnull PriceListAndCurrencyPriceIndex<?> priceIndex) {
		if (priceIndex instanceof final PriceListAndCurrencyPriceSuperIndex superIndex) {
			return superIndex.getHeapSizeInBytes();
		} else if (priceIndex instanceof final PriceListAndCurrencyPriceRefIndex refIndex) {
			return refIndex.getHeapSizeInBytes();
		}
		throw new GenericEvitaInternalError(
			"Price index implementation `" + priceIndex.getClass().getName() + "` prices itself through no accessor " +
				"this census knows.",
			"Unknown price index implementation!"
		);
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
				final long heapBytes = sortIndex.getHeapSizeInBytes();
				final boolean firstSight = charge(
					Family.ATTRIBUTE_SORT, sortIndex, heapBytes, collectionTallies, key
				);
				if (firstSight && this.decompose) {
					decomposeSortIndex(sortIndex, index, key, heapBytes);
				}
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
	 * Splits one sort index's footprint into the components `SortIndex#getSharedHeapSizeInBytes` charges, and counts
	 * the shape of the family alongside.
	 *
	 * Every row here is `(inferred)`: the sort index publishes its ordering as a **copy**
	 * ({@link SortIndex#getSortedRecords()}) rather than as the `TransactionalUnorderedIntArray` it charges, so the
	 * length is observable and the object is not. The `comparatorBase` array is not observable at all and is charged
	 * as a single comparator source, which is right for a plain sortable attribute and understates a sortable
	 * compound.
	 *
	 * What falls into the breakdown's residual is the part that matters most for a decision: an
	 * {@link OwnerSortIndex} additionally charges the value tree it owns, and a {@link SortIndexView} charges nothing
	 * beyond the shared rows. The owner/view split is counted so the residual can be read against it.
	 *
	 * @param sortIndex the index to decompose
	 * @param owner     the entity index it belongs to, which decides the global/reduced count
	 * @param key       the attribute it orders on
	 * @param heapBytes what it says it occupies in total
	 */
	private void decomposeSortIndex(
		@Nonnull SortIndex sortIndex,
		@Nonnull EntityIndex owner,
		@Nonnull AttributeIndexKey key,
		long heapBytes
	) {
		final VMLayout layout = VMLayout.current();
		this.sortBreakdown.total(heapBytes);
		if (sortIndex instanceof OwnerSortIndex) {
			this.sortOwners++;
		} else if (sortIndex instanceof SortIndexView) {
			this.sortViews++;
		} else {
			throw new GenericEvitaInternalError(
				"Sort index implementation `" + sortIndex.getClass().getName() + "` is neither an owner nor a view - " +
					"teach this census which value tree it charges before trusting the residual.",
				"Unknown sort index implementation!"
			);
		}
		if (owner instanceof GlobalEntityIndex) {
			this.sortInGlobalIndexes++;
		}
		final int records = sortIndex.getSortedRecords().length;
		this.sortedRecordCount += records;
		this.sortDistinctValueCount += sortIndex.getDistinctValueCount();
		this.sortBreakdown.add("sortedRecords int[] (inferred)", layout.sizeOfArray(records, Integer.BYTES));
		this.sortBreakdown.add(
			"object shell (inferred)",
			layout.sizeOfObject(Long.BYTES + Integer.BYTES + 10L * layout.referenceSize())
		);
		this.sortBreakdown.add("dirty flag (inferred)", layout.sizeOfObject(Long.BYTES + 1L));
		this.sortBreakdown.add(
			"sortIndexChanges shell (inferred)", layout.sizeOfObject(3L * layout.referenceSize())
		);
		this.sortBreakdown.add(
			"comparatorBase, one source (inferred)",
			layout.sizeOfArray(1, layout.referenceSize()) + layout.sizeOfObject(3L * layout.referenceSize())
		);
		this.sortPerAttribute.computeIfAbsent(key.attributeName(), name -> new Tally()).add(heapBytes, true);
		this.sortSamples.putIfAbsent(key.attributeName(), sortIndex);
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
			final long heapBytes = priceIndexHeapOf(perPriceList);
			final boolean superIndex = perPriceList instanceof PriceListAndCurrencyPriceSuperIndex;
			final boolean firstSight = charge(
				superIndex ? Family.PRICE_SUPER : Family.PRICE_REF, perPriceList, heapBytes, collectionTallies, null
			);
			if (firstSight) {
				// materialized once and handed to both consumers - the tree walk behind it is the expensive part, and
				// a second call would double it for every index in the catalog
				final PriceRecordContract[] records = this.priceRecordCensus || this.decompose
					? perPriceList.getPriceRecords() : null;
				if (records != null && this.priceRecordCensus) {
					chargePriceRecords(records);
				}
				if (records != null && this.decompose) {
					decomposePriceIndex(perPriceList, superIndex, records.length, heapBytes);
				}
			}
		}
	}

	/**
	 * Splits one price index's footprint into the components its own accounting charges.
	 *
	 * The reachable half is exact: the entity-id bitmap is handed out as the instance the index charges, and every
	 * {@link EntityPrices} value of a super index is reachable through {@link PriceListAndCurrencyPriceSuperIndex#getEntityPrices(int)}
	 * keyed by the very bitmap the index publishes. What no accessor exposes — the element B+ tree of price records,
	 * the `indexedPriceIds` bitmap and the validity {@link RangeIndex} — is left to the breakdown's own residual row
	 * rather than being estimated.
	 *
	 * The three `EntityPrices` arrays are separated using the implementations' own arithmetic: the two whose lengths
	 * are publicly readable are priced directly, and the third falls out of the subtraction, which makes it exact
	 * rather than inferred. A single-price entity reports zero for it, correctly — its one array *is* the lowest-price
	 * array.
	 *
	 * @param priceIndex  the index to decompose
	 * @param superIndex  whether it is a super index, which owns its record bodies and its entity-price map
	 * @param recordCount how many price records its tree holds
	 * @param heapBytes   what the index says it occupies in total
	 */
	private void decomposePriceIndex(
		@Nonnull PriceListAndCurrencyPriceIndex<?> priceIndex,
		boolean superIndex,
		int recordCount,
		long heapBytes
	) {
		final VMLayout layout = VMLayout.current();
		final Breakdown breakdown = superIndex ? this.priceSuperBreakdown : this.priceRefBreakdown;
		breakdown.total(heapBytes);
		final Bitmap entityIds = priceIndex.getIndexedPriceEntityIds();
		breakdown.add("indexedPriceEntityIds bitmap", entityIds.getHeapSizeInBytes());
		breakdown.add("dirty and terminated flags (inferred)", 2L * layout.sizeOfObject(Long.BYTES + 1L));
		breakdown.add(
			"object shell (inferred)",
			layout.sizeOfObject(Long.BYTES + (superIndex ? 10L : 9L) * layout.referenceSize())
		);
		if (!superIndex) {
			return;
		}
		// only a super index owns the bodies - a reference index stores the very same instances and prices them at
		// zero, which is why this row exists on one side of the table only
		breakdown.add("price record bodies", recordCount * layout.sizeOfObject(5L * Integer.BYTES));
		final PriceListAndCurrencyPriceSuperIndex superPriceIndex = (PriceListAndCurrencyPriceSuperIndex) priceIndex;
		final long boxedKey = layout.sizeOfObject(Integer.BYTES);
		final long entityPricesShell = layout.sizeOfObject(3L * layout.referenceSize());
		final OfInt iterator = entityIds.iterator();
		while (iterator.hasNext()) {
			final int entityPrimaryKey = iterator.nextInt();
			final EntityPrices entityPrices = superPriceIndex.getEntityPrices(entityPrimaryKey);
			if (entityPrices == null) {
				// the entity ids come from this index's own bitmap, so every one of them must resolve - a miss means
				// the two disagree, which would silently understate the very row this method exists to produce
				throw new GenericEvitaInternalError(
					"Entity `" + entityPrimaryKey + "` is in the price index's own entity bitmap but has no entity " +
						"prices!",
					"Indexed price entity has no entity prices!"
				);
			}
			final long internalPriceIds = layout.sizeOfArray(
				entityPrices.getInternalPriceIds().length, Integer.BYTES
			);
			final long lowestPrices = layout.sizeOfArray(
				entityPrices.getLowestPriceRecords().length, layout.referenceSize()
			);
			breakdown.add("entityPrices - internalPriceIds int[]", internalPriceIds);
			breakdown.add("entityPrices - lowestPrice ref[]", lowestPrices);
			breakdown.add(
				"entityPrices - prices ref[]",
				entityPrices.getHeapSizeInBytes() - entityPricesShell - internalPriceIds - lowestPrices
			);
			breakdown.add("entityPrices - object shells", entityPricesShell);
			breakdown.add("entityPrices - boxed map keys (inferred)", boxedKey);
		}
	}

	/**
	 * Folds one price index's records into the distinct-record census.
	 *
	 * Only a **first-sighted** price index contributes, so a price index reached twice does not inflate the reference
	 * count with references that do not exist. The sharing this counts is the one between a super index and the
	 * reference indexes that borrow from it, which is a different instance each time.
	 *
	 * @param records the records one first-sighted price index holds, materialized once by the caller
	 */
	private void chargePriceRecords(@Nonnull PriceRecordContract[] records) {
		final Map<PriceRecordContract, Boolean> charged = this.chargedPriceRecords;
		if (charged == null) {
			throw new GenericEvitaInternalError(
				"The price-record census ran without its identity map - the two are switched on and off together.",
				"Price record census ran without its identity map!"
			);
		}
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
		if (this.decompose) {
			printBreakdown("PRICE SUPER INDEX", this.priceSuperBreakdown);
			printBreakdown("PRICE REF INDEX", this.priceRefBreakdown);
			printBreakdown("ATTRIBUTE SORT INDEX", this.sortBreakdown);
			printSortShape();
			printResidualBreakdown(residual);
		}
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
	 * Prints one family's component breakdown, ordered by size, with everything unreached as one residual row.
	 *
	 * @param label     the family's name
	 * @param breakdown the components charged for it
	 */
	private static void printBreakdown(@Nonnull String label, @Nonnull Breakdown breakdown) {
		System.out.printf(
			"%n=== %s - COMPONENTS (%,d instances, %s) ===%n",
			label, breakdown.instances, humanReadable(breakdown.totalBytes)
		);
		System.out.printf("%-42s %14s %9s %14s%n", "component", "heap", "share", "charges");
		final List<Map.Entry<String, long[]>> ranked = new ArrayList<>(breakdown.rows.entrySet());
		ranked.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
		for (final Map.Entry<String, long[]> row : ranked) {
			System.out.printf(
				"%-42s %14s %8s%% %,14d%n",
				row.getKey(), humanReadable(row.getValue()[0]),
				share(row.getValue()[0], breakdown.totalBytes), row.getValue()[1]
			);
		}
		final long residual = breakdown.totalBytes - breakdown.accounted();
		System.out.printf(
			"%-42s %14s %8s%% %14s%n",
			"other (unreached)", humanReadable(residual), share(residual, breakdown.totalBytes), "-"
		);
	}

	/**
	 * Prints what the sort-index family is made of structurally, which is what its unreached residual has to be read
	 * against.
	 */
	private void printSortShape() {
		System.out.printf("%n=== ATTRIBUTE SORT INDEX - SHAPE ===%n");
		System.out.printf("  %-38s %,14d%n", "sort indexes", this.sortOwners + this.sortViews);
		System.out.printf("  %-38s %,14d%n", "  ...owners (own their value tree)", this.sortOwners);
		System.out.printf("  %-38s %,14d%n", "  ...views (share a filter index tree)", this.sortViews);
		System.out.printf("  %-38s %,14d%n", "  ...in a global entity index", this.sortInGlobalIndexes);
		System.out.printf(
			"  %-38s %,14d%n", "  ...in a reduced entity index",
			this.sortOwners + this.sortViews - this.sortInGlobalIndexes
		);
		System.out.printf("  %-38s %,14d%n", "record ids ordered, summed", this.sortedRecordCount);
		System.out.printf("  %-38s %,14d%n", "distinct values ordered by, summed", this.sortDistinctValueCount);

		System.out.printf("%n  top %d attributes by sort-index heap:%n", TOP_ATTRIBUTES);
		final List<Map.Entry<String, Tally>> ranked = new ArrayList<>(this.sortPerAttribute.entrySet());
		ranked.sort(Comparator.comparingLong((Map.Entry<String, Tally> e) -> e.getValue().distinctBytes).reversed());
		System.out.printf("  %-34s %12s %14s %9s %-22s%n", "attribute", "indexes", "heap", "share", "value type");
		final int rows = Math.min(TOP_ATTRIBUTES, ranked.size());
		for (int i = 0; i < rows; i++) {
			final Map.Entry<String, Tally> row = ranked.get(i);
			System.out.printf(
				"  %-34s %,12d %14s %8s%% %-22s%n",
				row.getKey(), row.getValue().distinctInstances, humanReadable(row.getValue().distinctBytes),
				share(row.getValue().distinctBytes, this.sortBreakdown.totalBytes), valueTypeOf(row.getKey())
			);
		}
	}

	/**
	 * Names the runtime type one attribute is sorted on, read from a representative index's own ordered values.
	 *
	 * Only the top rows are asked, because materializing a sort index's values is `O(distinct values)` and doing it
	 * for every attribute in the catalog would cost more than the whole rest of the walk.
	 *
	 * @param attributeName the attribute to name the type of
	 * @return the simple class name of its first ordered value, or a dash when it orders nothing
	 */
	@Nonnull
	private String valueTypeOf(@Nonnull String attributeName) {
		final SortIndex sample = this.sortSamples.get(attributeName);
		if (sample == null) {
			return "-";
		}
		final Serializable[] values = sample.getSortedRecordValues();
		return values.length == 0 ? "-" : values[0].getClass().getSimpleName();
	}

	/**
	 * Prints what the residual of the Pareto table is made of, and how much of it is still unexplained.
	 *
	 * @param residual the Pareto table's residual row, in bytes
	 */
	private void printResidualBreakdown(long residual) {
		System.out.printf(
			"%n=== RESIDUAL - COMPONENTS (%,d entity indexes, %s) ===%n",
			this.entityIndexCount, humanReadable(residual)
		);
		System.out.printf("%-42s %14s %9s %14s%n", "component", "heap", "share", "charges");
		final List<Map.Entry<String, long[]>> ranked = new ArrayList<>(this.residualBreakdown.rows.entrySet());
		ranked.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
		long accounted = 0L;
		for (final Map.Entry<String, long[]> row : ranked) {
			accounted += row.getValue()[0];
			System.out.printf(
				"%-42s %14s %8s%% %,14d%n",
				row.getKey(), humanReadable(row.getValue()[0]), share(row.getValue()[0], residual),
				row.getValue()[1]
			);
		}
		final long stillUnexplained = residual - accounted;
		System.out.printf(
			"%-42s %14s %8s%% %14s%n",
			"still unexplained", humanReadable(stillUnexplained), share(stillUnexplained, residual), "-"
		);
		System.out.printf(
			"  hierarchy nodes: %,d   per-language entity id bitmaps: %,d%n",
			this.hierarchyNodeCount, this.languageBitmapCount
		);
		for (final Map.Entry<String, Long> entry : this.entityIndexClasses.entrySet()) {
			System.out.printf("  %-40s %,14d%n", entry.getKey(), entry.getValue());
		}
		System.out.println(
			"  Still unexplained holds the per-language entity-id bitmaps, the four persisted `original*` baselines,\n" +
				"  the attribute-index map spines above their empty-index floor, the facet index shell, and the\n" +
				"  reference-type cardinality and histogram state - none of which any accessor reaches."
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
	 * A component breakdown of one family: every row an accessor or the implementation's own arithmetic could reach,
	 * against the family total, so what is left over is a subtraction rather than a guess.
	 */
	private static final class Breakdown {

		/**
		 * Rows in first-seen order, each holding its summed bytes and how many times it was charged.
		 */
		private final Map<String, long[]> rows = new LinkedHashMap<>();
		/**
		 * What the family reports in total.
		 */
		private long totalBytes;
		/**
		 * How many instances of the family were decomposed.
		 */
		private long instances;

		/**
		 * Records one instance's total, which the rows are set against.
		 *
		 * @param bytes what the instance says it occupies
		 */
		void total(long bytes) {
			this.totalBytes += bytes;
			this.instances++;
		}

		/**
		 * Adds one component charge.
		 *
		 * @param label the row it belongs to
		 * @param bytes what it occupies
		 */
		void add(@Nonnull String label, long bytes) {
			this.rows.computeIfAbsent(label, key -> new long[2])[0] += bytes;
			this.rows.get(label)[1]++;
		}

		/**
		 * Sums every row of the breakdown, which is what the family residual is set against.
		 *
		 * @return the bytes the rows account for
		 */
		long accounted() {
			long total = 0L;
			for (final Map.Entry<String, long[]> entry : this.rows.entrySet()) {
				total += entry.getValue()[0];
			}
			return total;
		}

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
