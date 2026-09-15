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
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;

/**
 * Prices the `ownerPK -> reduced-index-PK` reverse map proposed in issue #1529, against a real catalog, and sets
 * that price beside the work it would remove.
 *
 * # The question
 *
 * The reverse map turns the sibling resolver's walk from `O(all partitions of the collection)` into
 * `O(affected owners x partitions per owner)`. The cost is a permanent, resident structure, priced here at the
 * placement this harness was written to evaluate — one per {@link ReferencedTypeEntityIndex}. That is a
 * *candidate* placement and not where the structure landed: `ReducedIndexMembership` hangs off
 * `GlobalEntityIndex`, keyed by reference name. The pricing carries over unchanged, because it is a function of
 * the owners and partitions of one reference either way. Whether the trade is worth taking cannot be argued
 * from the shape of the structure — it depends on how many *owners* a real reference has and how many
 * partitions each sits in, which is a property of somebody's catalog.
 *
 * # Why it is built the expensive way
 *
 * The structure that ships is a {@link ReducedIndexMembership}, and this harness registers into a real one rather
 * than into a stand-in. That is deliberate and was not always so: pricing a hand-built map here charged only the
 * `ownerPK -> reduced-index-PK` entries and silently omitted the three bitmaps
 * {@link ReducedIndexMembership#getHeapSizeInBytes()} also charges — `coveredOwners`, `coveredIndexPrimaryKeys`
 * and `residualIndexPrimaryKeys`. Those grow with the residual set, so the understatement was worst at exactly
 * the low thresholds the sweep exists to compare. Registering into the object removes the divergence by
 * construction: the coverage decision, the residual accounting and the heap figure all come from shipped code,
 * and a duplicate advertisement raises here exactly as it does at load.
 *
 * A plain {@code Map<Integer, int[]>} would be smaller, and is disqualified: it is not a
 * `TransactionalLayerCreator`, so it never reaches `WarmUpSavepoint#verifyRollbackSupported`, and a rolled-back
 * transaction or a failed warm-up mutation would leave it silently diverged from the indexes it mirrors. The
 * `int[]` figure is still reported, as the floor a bespoke rollback-capable structure could aim at — never as a
 * candidate.
 *
 * Pricing goes through the engine's **own** heap accounting, the same call the index-detail statistics surface
 * uses — not JOL, whose graph walk dies on the decorator's lambda and which would in any case follow references
 * the structure merely borrows.
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
					"%-22s %-12s %10s %12s %12s %14s %14s %8s %12s %12s%n",
					"reference", "state", "partitions", "owners", "memberships", "reverse map",
					"int[] floor", "x floor", "walk left", "B/probe"
				);
				System.out.println("-".repeat(135));
				long partitionedBytes = 0L;
				long partitionedFloorBytes = 0L;
				long partitionedPartitions = 0L;
				long partitionedResidual = 0L;
				long allBytes = 0L;
				long allFloorBytes = 0L;
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
						"%-22s %-12s %,10d %,12d %,12d %,11d B %,11d B %8s %,12d %,12d%n",
						reference, partitioned ? "PARTITIONED" : "for-filter",
						reading.partitions, reading.owners, reading.memberships,
						reading.transactionalBytes, reading.intArrayFloorBytes,
						floorRatio(reading.transactionalBytes, reading.intArrayFloorBytes),
						reading.residualProbes,
						saved == 0L ? 0L : reading.transactionalBytes / saved
					);
					allBytes += reading.transactionalBytes;
					allFloorBytes += reading.intArrayFloorBytes;
					allPartitions += reading.partitions;
					allResidual += reading.residualProbes;
					if (partitioned) {
						partitionedBytes += reading.transactionalBytes;
						partitionedFloorBytes += reading.intArrayFloorBytes;
						partitionedPartitions += reading.partitions;
						partitionedResidual += reading.residualProbes;
					}
				}
				System.out.println("-".repeat(135));
				final long partitionedSaved = partitionedPartitions - partitionedResidual;
				final long allSaved = allPartitions - allResidual;
				System.out.printf(
					"%-35s P=%,9d  map=%,11d B  floor=%,11d B (x%s)  walk left=%,8d  %,10d B/probe%n",
					"TOTAL partitioned today", partitionedPartitions, partitionedBytes, partitionedFloorBytes,
					floorRatio(partitionedBytes, partitionedFloorBytes), partitionedResidual,
					partitionedSaved == 0L ? 0L : partitionedBytes / partitionedSaved
				);
				System.out.printf(
					"%-35s P=%,9d  map=%,11d B  floor=%,11d B (x%s)  walk left=%,8d  %,10d B/probe%n",
					"TOTAL if all switched", allPartitions, allBytes, allFloorBytes,
					floorRatio(allBytes, allFloorBytes), allResidual,
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
	 * @param threshold     maximum owners a partition may hold and still be covered by the map; larger ones are
	 *                      counted as residual probes instead
	 * @return the reading for this reference
	 */
	@Nonnull
	private static Reading measure(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		int threshold
	) {
		// The structure itself, not a stand-in for it. Pricing a hand-built `TransactionalMap` here used to omit
		// `coveredOwners`, `coveredIndexPrimaryKeys` and `residualIndexPrimaryKeys`, which
		// `ReducedIndexMembership#getHeapSizeInBytes` charges and which grow with the residual set - so the
		// understatement was worst at exactly the low thresholds the sweep exists to compare. Registering into the
		// real object removes the divergence by construction: the coverage decision, the residual accounting and
		// the heap figure all come from the shipped code.
		final ReducedIndexMembership membership = new ReducedIndexMembership(threshold);
		long partitions = 0L;
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, referenceName);
			if (typeIndex == null) {
				continue;
			}
			final long[] counter = new long[1];
			typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> {
				final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
				if (partition == null) {
					return;
				}
				counter[0]++;
				// The hybrid split is the object's own: a partition holding more owners than the threshold
				// contributes one entry per owner but saves exactly ONE probe, so it is left on the walk instead
				// and the residual walk stays bounded by (total memberships / threshold). A duplicate
				// advertisement raises here exactly as it does at load - a measurement that silently skipped one
				// would price a structure the engine would refuse to build.
				membership.registerIndex(reducedIndexPk, partition.getAllPrimaryKeys());
			});
			partitions += counter[0];
		}

		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		long memberships = 0L;
		long intArrayFloor = 0L;
		final OfInt coveredOwners = membership.getCoveredOwners().iterator();
		while (coveredOwners.hasNext()) {
			final int size = membership.getIndexPrimaryKeys(coveredOwners.nextInt()).size();
			memberships += size;
			// what a sorted int[] per owner would cost instead: the array object plus its payload. Reported as a
			// floor only - a plain int[] map cannot ship, see the class javadoc
			intArrayFloor += layout.sizeOfObject((long) size * Integer.BYTES) + boxedInteger;
		}

		return new Reading(
			partitions, membership.getCoveredOwners().size(), memberships,
			membership.getHeapSizeInBytes(), intArrayFloor,
			membership.getResidualIndexPrimaryKeys().size()
		);
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
	 * Prices the shippable shape against the `int[]` floor, which is the whole point of carrying the floor: the
	 * multiple is what says whether rollback capability costs a rounding error or a structure's worth of heap.
	 *
	 * @param transactionalBytes owned heap of the shippable shape
	 * @param intArrayFloorBytes owned heap of the `int[]` floor
	 * @return the multiple, formatted to one decimal, or `n/a` when the floor is zero
	 */
	@Nonnull
	private static String floorRatio(long transactionalBytes, long intArrayFloorBytes) {
		return intArrayFloorBytes == 0L
			? "n/a"
			: String.format("%.1f", (double) transactionalBytes / (double) intArrayFloorBytes);
	}

	/**
	 * One reference's reverse-map reading.
	 *
	 * @param partitions         how many partitions the reference advertises
	 * @param owners             distinct owner PKs the reverse map would key on
	 * @param memberships        total `(owner, partition)` pairs the map would hold
	 * @param transactionalBytes owned heap of the shipped `ReducedIndexMembership`, by its own accounting
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
