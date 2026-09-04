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
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.utils.VMLayout;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Measures what a catalog's price indexes cost on the heap, reading the engine's own accounting rather than a model
 * of it. The sibling of {@link RangeIndexFootprintProbe}, and built for the same reason.
 *
 * # Why this exists
 *
 * Every {@link PriceListAndCurrencyPriceIndex} owns two structures that allocate per leaf at a fixed block size: a
 * {@link io.evitadb.index.range.RangeIndex} of price validity, and a
 * {@link io.evitadb.index.bPlusTree.TransactionalElementBPlusTree} of price records. The element tree allocates
 * `Array.newInstance(elementType, blockSize)` per leaf regardless of how much it holds — the same defect the long-keyed
 * tree had. Whether that is expensive here depends on how many price records a real per-(price list, currency) index
 * holds, which only a real catalog can say.
 *
 * # The double-counting hazard this probe exists to avoid
 *
 * **A reduced entity index's `PriceRefIndex` stores the very instances the collection's super index holds.** Summing
 * naively across every entity index therefore counts the same bytes many times over and produces a figure that grows
 * with the number of reduced indexes rather than with the data. Every index is folded into an
 * {@link IdentityHashMap} first, so each instance is charged exactly once; the probe also prints how many references
 * were seen, because the ratio is itself worth knowing.
 *
 * # Running it
 *
 * Identical to {@link RangeIndexFootprintProbe} — same properties, same classpath warning. Prepend every reactor
 * `target/classes` ahead of the resolved classpath, or the run measures whichever build the local repository happens
 * to hold rather than the one under test.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceIndexFootprintProbe {

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
			throw new GenericEvitaInternalError("Price index footprint probe failed!", e.getMessage(), e);
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

		System.out.printf("Price index footprint probe - catalog `%s` from `%s`%n", catalogName, storageDir);
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
		// a reduced index's PriceRefIndex holds the SAME instances the super index does - charge each exactly once
		final Map<PriceListAndCurrencyPriceIndex<?>, Boolean> seen = new IdentityHashMap<>(1024);

		for (final String entityType : new TreeSet<>(catalog.getEntityTypes())) {
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			final Tally tally = perEntityType.computeIfAbsent(entityType, key -> new Tally());
			browse(collection, index -> {
				for (final PriceListAndCurrencyPriceIndex<?> priceIndex : index.getPriceListAndCurrencyIndexes()) {
					tally.references++;
					total.references++;
					if (seen.put(priceIndex, Boolean.TRUE) == null) {
						tally.add(priceIndex);
						total.add(priceIndex);
					}
				}
			});
		}

		System.out.printf(
			"%-28s %10s %12s %12s %14s %14s%n",
			"entity type", "distinct", "references", "records", "heap", "B / record"
		);
		for (final Map.Entry<String, Tally> entry : perEntityType.entrySet()) {
			if (entry.getValue().references > 0) {
				printRow(entry.getKey(), entry.getValue());
			}
		}
		System.out.println();
		printRow("TOTAL", total);
		System.out.printf(
			"%n  distinct instances: %,d of %,d references (%.1fx sharing between super and reduced indexes)%n",
			total.distinct, total.references,
			total.distinct == 0 ? 0.0d : (double) total.references / total.distinct
		);
		System.out.printf(
			"  super: %,d  ref: %,d%n", total.superIndexes, total.refIndexes
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
			"%-28s %,10d %,12d %,12d %14s %,14d%n",
			label, tally.distinct, tally.references, tally.records, humanReadable(tally.heapBytes),
			tally.records == 0 ? 0L : tally.heapBytes / tally.records
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
		final Path workDir = Files.createTempDirectory("price-index-probe-");
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
					PriceIndexFootprintProbe.class.getSimpleName() + " JavaDoc for the full list.",
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
		 * How many distinct price index instances were charged.
		 */
		private long distinct;
		/**
		 * How many times an entity index pointed at one — including the shared instances charged only once.
		 */
		private long references;
		/**
		 * How many price records they hold between them.
		 */
		private long records;
		/**
		 * What they say they occupy.
		 */
		private long heapBytes;
		/**
		 * How many of the distinct instances are super indexes.
		 */
		private long superIndexes;
		/**
		 * How many of the distinct instances are reference indexes.
		 */
		private long refIndexes;

		/**
		 * Folds one distinct price index into this tally.
		 *
		 * The heap accessor is public on both concrete classes but not on the interface, so the two are named
		 * explicitly. A third implementation reaching here is a defect rather than something to skip quietly — a
		 * silently-uncounted index would understate the very figure this probe exists to produce.
		 *
		 * @param priceIndex the index to account for
		 */
		void add(@Nonnull PriceListAndCurrencyPriceIndex<?> priceIndex) {
			this.distinct++;
			this.records += priceIndex.getPriceRecords().length;
			if (priceIndex instanceof final PriceListAndCurrencyPriceSuperIndex superIndex) {
				this.superIndexes++;
				this.heapBytes += superIndex.getHeapSizeInBytes();
			} else if (priceIndex instanceof final PriceListAndCurrencyPriceRefIndex refIndex) {
				this.refIndexes++;
				this.heapBytes += refIndex.getHeapSizeInBytes();
			} else {
				throw new GenericEvitaInternalError(
					"Price index implementation `" + priceIndex.getClass().getName() + "` prices itself through no " +
						"accessor this probe knows - teach it before trusting the total.",
					"Unknown price index implementation!"
				);
			}
		}

	}

}
