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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.VMLayout;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Measures what every {@link RangeIndex} of a real catalog costs on the heap, reading the engine's own accounting
 * rather than a model of it.
 *
 * # Why this exists
 *
 * A range index is backed by a {@link io.evitadb.index.bPlusTree.TransactionalLongBPlusTree} at a value block size of
 * 512. Whether that is expensive depends entirely on how many range points a real index actually holds — a question no
 * amount of arithmetic over the tree's shape can answer, and one this probe answers by opening a production catalog
 * and asking each index.
 *
 * # What it reports, and what it deliberately does not
 *
 * Per entity type and for the catalog as a whole: how many range indexes exist, how many range points they hold
 * between them, and what {@link RangeIndex#getHeapSizeInBytes()} says they occupy. It does **not** decompose that
 * figure into "useful" and "wasted" bytes: doing so would need a model of the leaf's internals, and a model is exactly
 * what this probe exists to avoid. Run it on two commits and subtract — the difference is the measurement.
 *
 * # No reflection
 *
 * Every hop is public API. {@link EntityIndex} carries `@Delegate(types = AttributeIndexContract.class)`, so
 * `getFilterIndexes()` and `getFilterIndex(key)` are its own methods; {@link FilterIndex#getRangeIndex()} is a Lombok
 * getter; and both figures this probe prints are public on {@link RangeIndex}.
 *
 * # Running it
 *
 * <pre>
 * java -Xmx8g -cp &lt;performance-tests classpath&gt; \
 *      -Dprobe.catalog=evita -Dprobe.dataDir=/path/holding/the/catalog/folder \
 *      io.evitadb.spike.trigram.AttributeIndexScaffoldingProbe
 * </pre>
 *
 * `probe.copyData` (default `true`) copies the catalog into a scratch directory first, so a run never mutates the
 * snapshot and two runs on two commits see byte-identical input. `probe.compress` (default `true`) must match how the
 * snapshot was written, or the catalog loads CORRUPTED.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeIndexScaffoldingProbe {

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
	 * How long to wait for the catalog to finish its asynchronous load before giving up.
	 */
	/**
	 * What an entirely empty {@code AttributeIndex} reports, in bytes - identical for global, reduced and
	 * reference-type indexes because all three build the same shell regardless of content.
	 *
	 * **Measured at run time rather than pinned**, by asking a directly constructed empty index. It used to be a
	 * literal, which made every run depend on the constant having been re-measured after the last change to the
	 * index's field layout - and it silently stopped being 680 B the moment the sub-index maps became lazily
	 * allocated. The shape it reports is JOL-cross-checked by `EntityIndexHeapSizeTest`.
	 */
	private static final long EMPTY_ATTRIBUTE_INDEX_BYTES =
		new EntityAttributeIndex("emptyAttributeIndexProbe").getHeapSizeInBytes();
	/**
	 * How long to wait for the catalog to finish its asynchronous load before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * Entry point.
	 *
	 * @param args ignored — every input is a system property
	 */
	public static void main(@Nonnull String[] args) {
		try {
			run();
		} catch (final IOException e) {
			throw new GenericEvitaInternalError("Attribute index scaffolding probe failed!", e.getMessage(), e);
		}
	}

	/**
	 * Opens the catalog and prints the per-collection and catalog-wide footprint tables.
	 */
	private static void run() throws IOException {
		final String catalogName = requiredProperty(CATALOG_NAME_PROPERTY);
		final Path dataDir = Path.of(requiredProperty(DATA_DIR_PROPERTY));
		final boolean copyData = Boolean.parseBoolean(System.getProperty(COPY_DATA_PROPERTY, "true"));
		final boolean compress = Boolean.parseBoolean(System.getProperty(COMPRESS_PROPERTY, "true"));
		final Path storageDir = copyData ? copyCatalog(dataDir, catalogName) : dataDir;

		System.out.printf(
			"AttributeIndex scaffolding probe - catalog `%s` from `%s`%n", catalogName, storageDir
		);
		System.out.printf("VM layout: %s%n%n", VMLayout.current());

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
			printFootprint(awaitLoaded(evita, catalogName));
		}
	}

	/**
	 * Walks every collection's indexes and prints one row per entity type plus a catalog total.
	 *
	 * @param catalog the loaded catalog
	 */
	private static void printFootprint(@Nonnull Catalog catalog) {
		final Map<String, Tally> perEntityType = new TreeMap<>();
		final Tally total = new Tally();

		for (final String entityType : new TreeSet<>(catalog.getEntityTypes())) {
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			final Tally tally = perEntityType.computeIfAbsent(entityType, key -> new Tally());
			browse(collection, index -> {
				tally.add(index);
				total.add(index);
			});
		}

		System.out.printf(
			"%-28s %10s %10s %10s %10s %10s %12s %12s%n",
			"entity type", "indexes", "unique", "filter", "sort", "chain", "used/4", "empty/4"
		);
		for (final Map.Entry<String, Tally> entry : perEntityType.entrySet()) {
			if (entry.getValue().indexes > 0) {
				printRow(entry.getKey(), entry.getValue());
			}
		}
		System.out.println();
		printRow("TOTAL", total);

		// the decisive arithmetic: an EMPTY attribute index is measured at startup on this very build
		// (EntityIndexHeapSizeTest cross-checks that shape against JOL), so the floor is a product of two measured
		// quantities rather than a model of the internals - and it moves when the index's shell does
		final long floor = total.indexes * EMPTY_ATTRIBUTE_INDEX_BYTES;
		final long emptyFamilySlots = total.indexes * 4L - total.usedFamilies;
		System.out.println();
		System.out.printf("  entity indexes                : %,d%n", total.indexes);
		System.out.printf(
			"  empty-attribute-index floor   : %s  (%,d x %d B)%n",
			humanReadable(floor), total.indexes, EMPTY_ATTRIBUTE_INDEX_BYTES
		);
		System.out.printf(
			"  of the 4 observable families  : %,d used, %,d empty (%.1f%% empty)%n",
			total.usedFamilies, emptyFamilySlots,
			total.indexes == 0 ? 0.0 : 100.0 * emptyFamilySlots / (total.indexes * 4.0)
		);
		System.out.printf(
			"  indexes with NO attribute idx : %,d (%.1f%%)%n",
			total.attributeEmptyIndexes,
			total.indexes == 0 ? 0.0 : 100.0 * total.attributeEmptyIndexes / total.indexes
		);
		System.out.println(
			"  NOTE: only 4 of the 7 maps are observable through the public contract; the other three\n" +
			"        (sharedValueIndex, sharedRangeIndex, uniqueViewIndex) are not, so the empty-slot\n" +
			"        percentage above is over the observable 4, not over all 7."
		);
	}

	/**
	 * Prints one tally row.
	 *
	 * @param label the row label
	 * @param tally the counters to print
	 */
	private static void printRow(@Nonnull String label, @Nonnull Tally tally) {
		System.out.printf(
			"%-28s %,10d %,10d %,10d %,10d %,10d %,12d %,12d%n",
			label, tally.indexes, tally.unique, tally.filter, tally.sort, tally.chain,
			tally.usedFamilies, tally.indexes * 4L - tally.usedFamilies
		);
	}

	/**
	 * Visits every index of a collection, one browse page at a time.
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
					// the browse read an immutable snapshot and this probe opens no writing session, so an index named
					// by a row must still be resolvable - a miss is a defect, not a race to tolerate
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
		final Path workDir = Files.createTempDirectory("range-index-probe-");
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
					AttributeIndexScaffoldingProbe.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Renders a byte count in the unit a reader can compare at a glance.
	 *
	 * @param bytes the byte count
	 * @return the rendered figure
	 */
	@Nonnull
	private static String humanReadable(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		} else if (bytes < 1024L * 1024L) {
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
	 * Counters accumulated for one entity type, or for the whole catalog.
	 */
	private static class Tally {

		/**
		 * How many entity indexes were seen.
		 */
		private long indexes;
		/**
		 * How many of them report an entirely empty attribute index.
		 */
		private long attributeEmptyIndexes;
		/**
		 * Entries held by each observable family, summed across indexes.
		 */
		private long unique;
		private long filter;
		private long sort;
		private long chain;
		/**
		 * How many of the four observable family slots are non-empty, summed across indexes. The complement over
		 * `4 * indexes` is the count of allocated-but-empty family maps.
		 */
		private long usedFamilies;

		/**
		 * Folds one entity index into this tally.
		 *
		 * @param index the index to account for
		 */
		void add(@Nonnull EntityIndex index) {
			this.indexes++;
			if (index.isAttributeIndexEmpty()) {
				this.attributeEmptyIndexes++;
			}
			final int uniqueCount = index.getUniqueIndexes().size();
			final int filterCount = index.getFilterIndexes().size();
			final int sortCount = index.getSortIndexes().size();
			final int chainCount = index.getChainIndexes().size();
			this.unique += uniqueCount;
			this.filter += filterCount;
			this.sort += sortCount;
			this.chain += chainCount;
			this.usedFamilies += (uniqueCount > 0 ? 1 : 0) + (filterCount > 0 ? 1 : 0)
				+ (sortCount > 0 ? 1 : 0) + (chainCount > 0 ? 1 : 0);
		}

	}

}
