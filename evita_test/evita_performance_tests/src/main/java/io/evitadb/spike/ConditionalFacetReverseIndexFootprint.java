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

package io.evitadb.spike;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;

/**
 * Prices the `ownerPK -> reduced-index-PK` reverse map proposed in issue #1529, against a real catalog, and sets
 * that price beside the work it would remove.
 *
 * # The question
 *
 * The reverse map turns the sibling resolver's walk from `O(all partitions of the collection)` into
 * `O(affected owners x partitions per owner)`. The cost is a permanent, resident structure on every
 * {@link ReferencedTypeEntityIndex}. Whether that trade is worth taking cannot be argued from the shape of the
 * structure — it depends on how many *owners* a real reference has and how many partitions each sits in, which is
 * a property of somebody's catalog.
 *
 * # Why it is built the expensive way
 *
 * The map is built here as a {@link TransactionalMap} of {@link TransactionalBitmap}, which is the **only** shape
 * that could ship. A plain {@code Map<Integer, int[]>} would be roughly half the size, and is disqualified: it is not a
 * `TransactionalLayerCreator`, so it never reaches `WarmUpSavepoint#verifyRollbackSupported`, and a rolled-back
 * transaction or a failed warm-up mutation would leave it silently diverged from the indexes it mirrors. The
 * `int[]` figure is still reported, as the floor a bespoke rollback-capable structure could aim at — never as a
 * candidate.
 *
 * Pricing goes through the engine's **own** heap accounting
 * ({@link TransactionalMap#getHeapSizeInBytes}, {@link TransactionalBitmap#getHeapSizeInBytes}), the same call the
 * index-detail statistics surface uses — not JOL, whose graph walk dies on the decorator's lambda and which would
 * in any case follow references the map merely borrows.
 *
 * # Every reference, not only the partitioned ones
 *
 * A reference indexed `FOR_FILTERING` still owns a `REFERENCED_*_TYPE` index whose advertising map already holds
 * the partitions it *would* contribute the moment its index type is switched to
 * `FOR_FILTERING_AND_PARTITIONING`. Since that switch is one schema edit — and one the enum's own javadoc
 * recommends for references that are filtered on hard — the cost of the reverse map is reported for **every**
 * indexed reference, so both the current bill and the one a client can trigger are visible.
 *
 * ```
 * java -Xmx48g -cp <cp> io.evitadb.spike.ConditionalFacetReverseIndexFootprint <dir> <catalog> <collection>
 * ```
 *
 * @author Claude (issue #1529 reverse-index footprint), FG Forrest a.s. (c) 2026
 */
public class ConditionalFacetReverseIndexFootprint {

	/**
	 * How long the probe waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	public static void main(@Nonnull String[] args) {
		if (args.length < 3) {
			System.err.println(
				"Usage: ConditionalFacetReverseIndexFootprint <storageDirectory> <catalogName> <collection>"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final String collectionName = args[2];

		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(StorageOptions.builder().storageDirectory(storageDirectory).build())
					.server(
						ServerOptions.builder()
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			)
		) {
			final Catalog catalog = awaitLoaded(evita, catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(collectionName);
			System.out.printf(
				"Catalog version %d, state %s — collection `%s`: %,d entities, %,d indexes%n%n",
				catalog.getVersion(), catalog.getCatalogState(), collectionName,
				collection.size(), collection.getIndexCount()
			);

			final List<String> references = new ArrayList<>();
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				if (reference.getReferenceIndexType(Scope.LIVE) != ReferenceIndexType.NONE) {
					references.add(reference.getName());
				}
			}
			references.sort(String::compareTo);

			// The sweep. `Integer.MAX_VALUE` is the blanket structure the issue proposes; every lower threshold
			// is the hybrid, which trades a bounded residual walk for a much smaller map.
			final int[] thresholds = {1, 4, 16, 64, 256, 1024, 4096, Integer.MAX_VALUE};
			for (final int threshold : thresholds) {
				System.out.printf(
					"%n=== threshold T=%s — partitions with more than T owners stay on the walk ===%n",
					threshold == Integer.MAX_VALUE ? "unbounded (blanket structure)" : Integer.toString(threshold)
				);
				System.out.printf(
					"%-22s %-12s %10s %12s %12s %14s %12s %12s%n",
					"reference", "state", "partitions", "owners", "memberships", "reverse map",
					"walk left", "B/probe"
				);
				System.out.println("-".repeat(112));
				long partitionedBytes = 0L;
				long partitionedPartitions = 0L;
				long partitionedResidual = 0L;
				long allBytes = 0L;
				long allPartitions = 0L;
				long allResidual = 0L;
				for (final String reference : references) {
					final ReferenceSchemaContract schema = collection.getSchema()
						.getReference(reference).orElseThrow();
					final boolean partitioned = schema.getReferenceIndexType(Scope.LIVE)
						== ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
					final Reading reading = measure(collection, reference, threshold);
					if (reading.partitions == 0L) {
						continue;
					}
					final long saved = reading.partitions - reading.residualProbes;
					System.out.printf(
						"%-22s %-12s %,10d %,12d %,12d %,11d B %,12d %,12d%n",
						reference, partitioned ? "PARTITIONED" : "for-filter",
						reading.partitions, reading.owners, reading.memberships,
						reading.transactionalBytes, reading.residualProbes,
						saved == 0L ? 0L : reading.transactionalBytes / saved
					);
					allBytes += reading.transactionalBytes;
					allPartitions += reading.partitions;
					allResidual += reading.residualProbes;
					if (partitioned) {
						partitionedBytes += reading.transactionalBytes;
						partitionedPartitions += reading.partitions;
						partitionedResidual += reading.residualProbes;
					}
				}
				System.out.println("-".repeat(112));
				final long partitionedSaved = partitionedPartitions - partitionedResidual;
				final long allSaved = allPartitions - allResidual;
				System.out.printf(
					"%-35s P=%,9d  map=%,11d B  walk left=%,8d  %,10d B/probe%n",
					"TOTAL partitioned today", partitionedPartitions, partitionedBytes, partitionedResidual,
					partitionedSaved == 0L ? 0L : partitionedBytes / partitionedSaved
				);
				System.out.printf(
					"%-35s P=%,9d  map=%,11d B  walk left=%,8d  %,10d B/probe%n",
					"TOTAL if all switched", allPartitions, allBytes, allResidual,
					allSaved == 0L ? 0L : allBytes / allSaved
				);
			}
		}
	}

	/**
	 * Builds the reverse map for one reference from its live indexes and prices it.
	 *
	 * The map is discarded before the next reference is measured, so peak heap stays at one reference's worth
	 * rather than the whole collection's.
	 *
	 * @param collection    the collection holding the indexes
	 * @param referenceName the reference to price
	 * @return the reading for this reference
	 */
	@Nonnull
	private static Reading measure(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		int threshold
	) {
		final Map<Integer, TransactionalBitmap> reverse = CollectionUtils.createHashMap(1024);
		long partitions = 0L;
		long residualProbes = 0L;
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, referenceName);
			if (typeIndex == null) {
				continue;
			}
			final long[] counter = new long[2];
			typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> {
				final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
				if (partition == null) {
					return;
				}
				counter[0]++;
				// The hybrid split. A partition contributes one entry per owner to the map but saves exactly ONE
				// probe, so covering a large partition is the worst possible trade - it is left to the walk
				// instead, and the residual walk is bounded by (total memberships / threshold).
				final int size = partition.getAllPrimaryKeys().size();
				if (size > threshold) {
					counter[1]++;
					return;
				}
				// this is the maintenance the write path would perform at each owner-membership boundary:
				// one entry per (owner, partition) pair the owner belongs to
				final OfInt owners = partition.getAllPrimaryKeys().iterator();
				while (owners.hasNext()) {
					reverse.computeIfAbsent(owners.nextInt(), __ -> new TransactionalBitmap(new BaseBitmap()))
						.add(reducedIndexPk);
				}
			});
			partitions += counter[0];
			residualProbes += counter[1];
		}

		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		long memberships = 0L;
		long intArrayFloor = 0L;
		for (final TransactionalBitmap bitmap : reverse.values()) {
			final int size = bitmap.size();
			memberships += size;
			// what a sorted int[] per owner would cost instead: the array object plus its payload. Reported as a
			// floor only - a plain int[] map cannot ship, see the class javadoc
			intArrayFloor += layout.sizeOfObject((long) size * Integer.BYTES) + boxedInteger;
		}

		final long transactionalBytes = new TransactionalMap<>(reverse)
			.getHeapSizeInBytes(key -> boxedInteger, TransactionalBitmap::getHeapSizeInBytes);

		return new Reading(partitions, reverse.size(), memberships, transactionalBytes, intArrayFloor,
			residualProbes);
	}

	/**
	 * Resolves one `REFERENCED_*_TYPE` index of a reference, whatever its declared index type.
	 *
	 * @param collection    the collection holding the indexes
	 * @param family        the referenced-type index family
	 * @param referenceName the reference whose type index is resolved
	 * @return the type index, or `null` when the reference has none of that kind
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndexType family,
		@Nonnull String referenceName
	) {
		final EntityIndex index = collection.getIndexByKeyIfExists(
			new EntityIndexKey(family, Scope.LIVE, referenceName)
		);
		if (index == null) {
			return null;
		}
		if (index instanceof final ReferencedTypeEntityIndex typeIndex) {
			return typeIndex;
		}
		// an index registered under a REFERENCED_*_TYPE key is a ReferencedTypeEntityIndex by construction
		throw new IllegalStateException(
			"Index " + family + "/" + referenceName + " is a " + index.getClass().getName() + "!"
		);
	}

	/**
	 * Waits until the catalog finishes its background load and becomes a usable {@link Catalog}.
	 *
	 * @param evita       the running engine
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
			try {
				Thread.sleep(500L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog!", e);
			}
		}
		throw new IllegalStateException("Catalog `" + catalogName + "` did not load in time!");
	}

	/**
	 * One reference's reverse-map reading.
	 *
	 * @param partitions         how many partitions the reference advertises
	 * @param owners             distinct owner PKs the reverse map would key on
	 * @param memberships        total `(owner, partition)` pairs the map would hold
	 * @param transactionalBytes owned heap of the shippable `TransactionalMap`/`TransactionalBitmap` shape
	 * @param intArrayFloorBytes owned heap a sorted `int[]` per owner would need instead — a floor, not an option
	 * @param residualProbes     partitions left above the threshold, which the walk must still visit
	 */
	private record Reading(
		long partitions,
		long owners,
		long memberships,
		long transactionalBytes,
		long intArrayFloorBytes,
		long residualProbes
	) {
	}

	/**
	 * Not instantiable — this is a `main()`-style report.
	 */
	private ConditionalFacetReverseIndexFootprint() {
	}
}
