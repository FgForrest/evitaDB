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

package io.evitadb.spike.footprint;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Measures what the `MEMORY_FOOTPRINT` statistics component would cost on a real catalog, and what it would report.
 *
 * # Why this exists as a spike rather than a test
 *
 * The question it answers - *is a per-collection heap walk affordable as a statistics component, or does the surface
 * have to be per-index?* - can only be answered against a dataset with production shape. A synthetic fixture answers
 * the arithmetic (that is `ContainerIndexHeapSizeTest`'s job, against JOL) but not the cost, because cost here is a
 * function of how many indexes a collection holds and how much each one carries, and both are properties of somebody's
 * real catalog. The dataset is therefore named on the command line and nothing in CI depends on it.
 *
 * # What is measured
 *
 * Per collection, and separately for every index the collection holds:
 *
 * - the wall time of {@link EntityIndex#getHeapSizeInBytes()}, which is `O(index contents)`;
 * - the bytes it reports.
 *
 * Indexes are enumerated by storage primary key rather than by walking the index map, so the enumeration itself does
 * not allocate an entry object per index and cannot be mistaken for part of the cost being measured.
 *
 * The readings are printed as a table so they can be pasted into a decision record. Two of them decide the surface:
 * the **total** per collection is what a collection-level component would have to pay on every request, and the
 * **maximum single index** is what a per-index call would pay instead.
 *
 * Run with a heap large enough for the catalog - the indexes are resident, only entity bodies stay on disk:
 *
 * ```
 * java -Xmx48g -cp <classpath> io.evitadb.spike.footprint.IndexMemoryFootprintSpike <storageDir> <catalogName>
 * ```
 *
 * @author Claude (index memory-footprint measurement), FG Forrest a.s. (c) 2026
 */
public class IndexMemoryFootprintSpike {

	/**
	 * How many of the slowest indexes of each collection are listed individually.
	 */
	private static final int SLOWEST_LISTED = 5;

	/**
	 * How long the spike waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println("Usage: IndexMemoryFootprintSpike <storageDirectory> <catalogName>");
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];

		final long openStart = System.nanoTime();
		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(storageDirectory)
							.build()
					)
					.server(
						ServerOptions.builder()
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			)
		) {
			final long openNanos = System.nanoTime() - openStart;
			System.out.printf("Catalog storage opened in %s%n", millis(openNanos));

			final Catalog catalog = awaitLoaded(evita, catalogName);
			System.out.printf(
				"Catalog `%s`, version %d, state %s%n%n",
				catalogName, catalog.getVersion(), catalog.getCatalogState()
			);

			// the live heap before any walk: the baseline the estimate is judged against
			final long liveHeapBefore = liveHeapBytes();

			long catalogBytes = 0L;
			long catalogNanos = 0L;
			int catalogIndexes = 0;
			for (final String entityType : catalog.getEntityTypes()) {
				final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
				final CollectionReading reading = measureCollection(collection);
				reading.print();
				catalogBytes += reading.totalBytes;
				catalogNanos += reading.totalNanos;
				catalogIndexes += reading.measured.size();
			}

			System.out.printf(
				"%n=== CATALOG TOTAL (cold): %d indexes, %s, walked in %s ===%n",
				catalogIndexes, bytes(catalogBytes), millis(catalogNanos)
			);

			// a second pass over the same structures: the first one pays every cache miss, and a statistics component
			// polled even occasionally would be paying the warm price
			long warmNanos = 0L;
			for (final String entityType : catalog.getEntityTypes()) {
				warmNanos += measureCollection(
					catalog.getCollectionForEntityOrThrowException(entityType)
				).totalNanos;
			}
			System.out.printf("=== CATALOG TOTAL (warm second pass): walked in %s ===%n", millis(warmNanos));

			// what the estimate is worth: the whole point of the component is to tell an operator how much memory the
			// indexes take, so the figure has to be compared against what the JVM is actually holding
			System.out.printf(
				"%n=== LIVE HEAP: %s before the walk, %s after; indexes estimated at %s ===%n",
				bytes(liveHeapBefore), bytes(liveHeapBytes()), bytes(catalogBytes)
			);
		}
	}

	/**
	 * Waits until the named catalog finishes loading.
	 *
	 * The instance loads its catalogs on background threads, so the first `getCatalogInstance` after the constructor
	 * returns hands back an {@link io.evitadb.core.catalog.UnusableCatalog} placeholder rather than a loaded one. A
	 * measurement taken against that placeholder would be measuring nothing.
	 *
	 * @param evita       the instance whose catalog is awaited
	 * @param catalogName name of the catalog
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
	 * Times {@link EntityIndex#getHeapSizeInBytes()} on every index of one collection.
	 *
	 * @param collection the collection to measure
	 * @return the per-index readings plus their totals
	 */
	@Nonnull
	private static CollectionReading measureCollection(@Nonnull EntityCollection collection) {
		final int indexCount = collection.getIndexCount();
		final List<IndexReading> measured = new ArrayList<>(indexCount);
		long totalBytes = 0L;
		long totalNanos = 0L;

		// enumerate by storage primary key: the map's own iteration would allocate an entry per index and show up
		// inside the figure being measured
		int found = 0;
		for (int primaryKey = 1; found < indexCount && primaryKey <= indexCount * 4; primaryKey++) {
			final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(primaryKey);
			if (index == null) {
				continue;
			}
			found++;
			final long start = System.nanoTime();
			final long heapBytes = index.getHeapSizeInBytes();
			final long elapsed = System.nanoTime() - start;
			measured.add(new IndexReading(index.getIndexKey(), index.getAllPrimaryKeys().size(), heapBytes, elapsed));
			totalBytes += heapBytes;
			totalNanos += elapsed;
		}
		return new CollectionReading(collection.getEntityType(), indexCount, measured, totalBytes, totalNanos);
	}

	/**
	 * Returns what the JVM is holding after a best-effort collection.
	 *
	 * `System.gc()` is a hint, so this is an upper bound on the live set rather than a measurement of it - which is
	 * the safe direction here, since the comparison it feeds is "does the estimate account for the heap" and an
	 * inflated baseline can only make the estimate look worse than it is.
	 *
	 * @return the occupied heap in bytes
	 */
	private static long liveHeapBytes() {
		final Runtime runtime = Runtime.getRuntime();
		for (int i = 0; i < 3; i++) {
			System.gc();
			try {
				Thread.sleep(200L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return runtime.totalMemory() - runtime.freeMemory();
	}

	/**
	 * Renders a nanosecond reading as milliseconds with three decimals.
	 */
	@Nonnull
	private static String millis(long nanos) {
		return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
	}

	/**
	 * Renders a byte count in the largest unit that keeps it above one.
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
	 * One index's reading.
	 *
	 * @param indexKey    key identifying the index
	 * @param entityCount how many entities the index covers
	 * @param heapBytes   what the index reported
	 * @param nanos       how long the report took
	 */
	private record IndexReading(
		@Nonnull EntityIndexKey indexKey,
		int entityCount,
		long heapBytes,
		long nanos
	) {
	}

	/**
	 * One collection's readings and their totals.
	 *
	 * @param entityType the collection's entity type
	 * @param indexCount how many indexes the collection reports holding
	 * @param measured   the per-index readings, in enumeration order
	 * @param totalBytes sum of every index's reported footprint
	 * @param totalNanos sum of every index's measurement time
	 */
	private record CollectionReading(
		@Nonnull String entityType,
		int indexCount,
		@Nonnull List<IndexReading> measured,
		long totalBytes,
		long totalNanos
	) {

		/**
		 * Prints the collection's totals, its per-kind breakdown, and the slowest individual indexes.
		 */
		void print() {
			System.out.printf(
				"--- %s: %d indexes, %s, walked in %s ---%n",
				this.entityType, this.indexCount, bytes(this.totalBytes), millis(this.totalNanos)
			);
			if (this.measured.isEmpty()) {
				return;
			}
			// per-kind breakdown: which index family the time and the bytes actually went into
			for (final EntityIndexType type : EntityIndexType.values()) {
				long kindBytes = 0L;
				long kindNanos = 0L;
				int kindCount = 0;
				for (final IndexReading reading : this.measured) {
					if (reading.indexKey().type() == type) {
						kindBytes += reading.heapBytes();
						kindNanos += reading.nanos();
						kindCount++;
					}
				}
				if (kindCount > 0) {
					System.out.printf(
						"    %-28s %6d indexes  %10s  %12s%n",
						type, kindCount, bytes(kindBytes), millis(kindNanos)
					);
				}
			}
			final IndexReading[] slowest = this.measured.toArray(IndexReading[]::new);
			Arrays.sort(slowest, Comparator.comparingLong(IndexReading::nanos).reversed());
			for (int i = 0; i < Math.min(SLOWEST_LISTED, slowest.length); i++) {
				final IndexReading reading = slowest[i];
				System.out.printf(
					"    slowest #%d: %s entities=%d %s in %s%n",
					i + 1, reading.indexKey(), reading.entityCount(),
					bytes(reading.heapBytes()), millis(reading.nanos())
				);
			}
			System.out.println();
		}
	}

}
