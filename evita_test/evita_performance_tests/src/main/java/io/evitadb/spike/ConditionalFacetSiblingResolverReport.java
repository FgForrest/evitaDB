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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
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
import io.evitadb.core.transaction.Transaction;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.and;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.intersects;

/**
 * Times the sibling-resolver walk of issue #1529 against a real catalog, in the three shapes that matter, and
 * reports the one number the whole decision still lacks: **nanoseconds per probed partition**.
 *
 * # The arms
 *
 * - **A** — the pre-`5db4385e1` walk: a boxed `getAllTrackedReferencedEntityPrimaryKeys()` keySet, then
 *   `getAllReferenceIndexes(pk)` per key, which hashes into the same map a second time and allocates an `int[]`
 *   plus two `Optional`s per entry.
 * - **B** — current `HEAD`: `forEachReferenceIndexPrimaryKey`, one `entrySet` pass, nothing allocated per
 *   partition. Shipped on a cost model alone; this puts a number on it.
 * - **C** — the hybrid reverse index: an `ownerPK -> partitionPK` map covering only partitions of at most `T`
 *   owners, with the (few) larger partitions left on the walk.
 * - **D** — arm B with the per-partition `getIndexByPrimaryKeyIfExists` replaced by a plain int-keyed hash get.
 *   `B - D` is therefore the **ceiling** on everything an index-lookup optimization could recover: the
 *   `ThreadLocal` read, the transactional-layer resolution, the `Optional` and the two capturing lambdas the
 *   engine accessor allocates per partition. No engine change can beat a bare hash lookup, so no engine change
 *   can recover more than this.
 * - **E** — arm D with the partition bitmaps pre-resolved too, so the probe is the `and()` and nothing else.
 *   `D - E` is the ceiling on hoisting `TransactionalBitmap#getRoaringBitmap`, and `E` is the irreducible
 *   intersection cost that no amount of lookup work removes.
 *
 * - **F** — arm B with an allocation-free `PersistentRoaringBitmap#intersects` gate in front of the `and()`.
 *   The walk materialises an intersection bitmap plus an `int[]` for **every** probed partition, including the
 *   overwhelming majority that share no owner with the affected set at all. `intersects` short-circuits on the
 *   first common bit and allocates nothing, so the `and()` runs only where it produces something. Unlike D and
 *   E this one is implementable as written — it changes only the order of two operations that already exist.
 *
 * - **G** — the same hybrid as C, but with the reverse map **split per `ReferencedTypeEntityIndex`** instead
 *   of merged across the collection, which is where the structure most naturally lives. Each index also
 *   carries the union of the owners its covered partitions hold, so the affected set is intersected against
 *   that once per index and only owners that can hit are looked up. C and G emit identical pairs and differ
 *   only in layout: C pays one hash lookup per affected owner, G pays one per `(owner, reference)` that hits.
 *   The delta is the price of the placement, and it decides whether the map can be a field on the type index
 *   or has to be a collection-level structure with a much harder transactional story.
 *
 * D and E are **not implementable** as they stand — a real walk cannot pre-resolve a transactional bitmap and
 * still see the transaction's own writes. They exist to bound what step 1 of the #1529 plan (hoisting the
 * loop-invariant transaction resolution) could ever be worth, before that seam is cut into the engine.
 *
 * All three are **probe-only**: they stop at the intersection and never call `getOrCreateIndexByPrimaryKey`.
 * That matches how the 2026-09-09 decomposition defined "the walk" (0.47 ms) against the whole resolver
 * (2.0 ms), and it keeps the arms read-only so they can share one live catalog.
 *
 * Every arm is checksummed on the `(owner, partition)` pairs it emits, with an order-independent accumulator, so
 * an arm that computes a different answer cannot be compared as if it were faster.
 *
 * # Two series, never mixed
 *
 * - **WARM** — all arms back to back inside one round, order rotated per round, paired deltas. This answers
 *   *which arm is faster* and is the only shape with the statistical power to do so: a cross-JVM A/B on this
 *   loop once produced a confident +11.6 % (p=0.004) that failed to reproduce at +0.5 %.
 * - **COLD** — one arm per round, with the working set evicted by streaming a buffer larger than the LLC
 *   **outside** the timed region. This is the only series that can answer whether the per-partition constant
 *   degrades once the partition set exceeds cache, because after the first warm round nothing is ever cold
 *   again. Rotation cancels bias *between* arms; it does not make any measurement cold.
 *
 * # Bound transaction, deliberately
 *
 * Production triggers run inside a transaction, and two of the walk's per-partition costs exist only then:
 * `TransactionalBitmap#getRoaringBitmap` and `TransactionalDataStoreMemoryBuffer#getIndexIfExists` each resolve
 * a transactional layer, where the `WARM_UP` buffer calls straight through. Measuring without one understates
 * `ALIVE` — the wrong direction of error for this issue. The same walk is therefore also measured *without* a
 * bound transaction, and the difference is reported as a result: it is the ceiling on what hoisting the
 * loop-invariant transaction resolution out of the loop could ever recover.
 *
 * ```
 * java -Xmx48g -cp <cp> io.evitadb.spike.ConditionalFacetSiblingResolverReport <dir> <catalog> <collection> [T]
 * ```
 *
 * @author Claude (issue #1529 sibling-resolver timing), FG Forrest a.s. (c) 2026
 */
public class ConditionalFacetSiblingResolverReport {

	/**
	 * How long the report waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * Rounds of the warm series — each runs all arms back to back with their order rotated.
	 */
	private static final int WARM_ROUNDS = 400;

	/**
	 * Rounds of the cold series. One arm per round, so the per-arm sample is a third of this; the cold series
	 * needs more rounds than the warm one for the same confidence because it has no pairing.
	 */
	private static final int COLD_ROUNDS = 180;

	/**
	 * Rounds discarded before recording, so the arms are compared at steady state rather than during
	 * interpretation and OSR compilation.
	 */
	private static final int WARMUP_ROUNDS = 60;

	/**
	 * Eviction buffer for the cold series: comfortably more than twice the 16 MiB LLC of the measured box, so a
	 * single streaming pass leaves nothing of the partition working set resident.
	 */
	private static final int EVICTION_BYTES = 128 * 1024 * 1024;

	/**
	 * Sink the eviction pass sums into, so the JIT cannot delete the pass it exists to perform.
	 */
	@SuppressWarnings("unused")
	private static volatile long evictionSink;

	public static void main(@Nonnull String[] args) {
		if (args.length < 3) {
			System.err.println(
				"Usage: ConditionalFacetSiblingResolverReport <storageDir> <catalogName> <collection> [threshold]"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final String collectionName = args[2];
		final int threshold = args.length > 3 ? Integer.parseInt(args[3]) : 16;
		// The counterfactual: references that are FOR_FILTERING today but would join the walk the moment a
		// client switches them to FOR_FILTERING_AND_PARTITIONING. Their type indexes already advertise exactly
		// the partitions the walk would visit, so the switch can be simulated without touching the schema.
		final List<String> extraSiblings = args.length > 4 && !args[4].isBlank()
			? new ArrayList<>(List.of(args[4].split(",")))
			: List.of();

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

			final List<String> partitioned = new ArrayList<>();
			String trigger = null;
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				if (reference.getReferenceIndexType(Scope.LIVE)
					== ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) {
					partitioned.add(reference.getName());
				}
				if (reference.getFacetedPartiallyInScope(Scope.LIVE) != null) {
					trigger = reference.getName();
				}
			}
			partitioned.addAll(extraSiblings);
			partitioned.sort(String::compareTo);
			// the walk excludes the reference the trigger fires for; on a schema with no conditional facet at
			// all there is nothing to exclude and every partitioned reference is a sibling
			final String mutated = trigger;
			partitioned.remove(mutated);

			final Walk walk = new Walk(collection, partitioned, threshold);
			System.out.printf(
				"catalog v%d %s | collection `%s` | trigger `%s` | siblings %s%n",
				catalog.getVersion(), catalog.getCatalogState(), collectionName, mutated, partitioned
			);
			System.out.printf(
				"partitions: %,d probed by the walk, %,d covered by the reverse map at T=%d, %,d left on it%n",
				walk.totalPartitions, walk.coveredPartitions, threshold, walk.residualPartitions
			);
			System.out.printf(
				"reverse map: %,d owners, %,d memberships%n%n", walk.reverse.size(), walk.memberships
			);

			for (final Shape shape : shapes(collection, mutated)) {
				System.out.printf("=== %s — %,d affected owners ===%n", shape.name, shape.owners.size());
				runSeries(walk, shape);
			}
		}
	}

	/**
	 * Runs the warm and cold series for one affected-owner shape and prints the readings.
	 *
	 * @param walk  the prepared walk over the sibling partitions
	 * @param shape the affected-owner set being measured
	 */
	private static void runSeries(@Nonnull Walk walk, @Nonnull Shape shape) {
		final PersistentRoaringBitmap affected = getRoaringBitmap(shape.owners);
		final int[] affectedOwners = shape.owners.getArray();
		final Arm[] arms = Arm.values();

		// one throwaway transaction bound for the whole series: the arms are read-only, so no layer is ever
		// created, but every per-partition `getTransactionalMemoryLayerIfExists` now takes the ALIVE path
		final Transaction transaction = new Transaction(new TransactionalBitmap(new BaseBitmap()));

		final long[][] warm = new long[arms.length][WARM_ROUNDS];
		final long[] checksums = new long[arms.length];
		Arrays.fill(checksums, Long.MIN_VALUE);
		Transaction.executeInTransactionIfProvided(transaction, (Runnable) () -> {
			for (int round = -WARMUP_ROUNDS; round < WARM_ROUNDS; round++) {
				for (int slot = 0; slot < arms.length; slot++) {
					// rotate so no arm always runs first into a cache the others then reuse
					final Arm arm = arms[(round + slot + WARMUP_ROUNDS) % arms.length];
					final long start = System.nanoTime();
					final long checksum = walk.run(arm, affected, affectedOwners);
					final long elapsed = System.nanoTime() - start;
					if (round >= 0) {
						warm[arm.ordinal()][round] = elapsed;
					}
					verifyChecksum(checksums, arm, checksum);
				}
			}
		});

		System.out.printf(
			"  %-26s %12s %12s %12s %10s%n", "series / arm", "median ns", "p95 ns", "ns/probe", "probes"
		);
		for (final Arm arm : arms) {
			report("WARM " + arm.label, warm[arm.ordinal()], walk.probesFor(arm));
		}

		// COLD: one arm per round, working set evicted outside the timed region
		final long[][] cold = new long[arms.length][];
		final int perArm = COLD_ROUNDS / arms.length;
		for (final Arm arm : arms) {
			cold[arm.ordinal()] = new long[perArm];
		}
		Transaction.executeInTransactionIfProvided(transaction, (Runnable) () -> {
			for (int round = 0; round < perArm * arms.length; round++) {
				final Arm arm = arms[round % arms.length];
				evictCaches();
				final long start = System.nanoTime();
				final long checksum = walk.run(arm, affected, affectedOwners);
				final long elapsed = System.nanoTime() - start;
				cold[arm.ordinal()][round / arms.length] = elapsed;
				verifyChecksum(checksums, arm, checksum);
			}
		});
		for (final Arm arm : arms) {
			report("COLD " + arm.label, cold[arm.ordinal()], walk.probesFor(arm));
		}

		// the same walk with NO transaction bound - the delta against WARM B is the ceiling on what hoisting
		// the loop-invariant transaction resolution out of the per-partition loop could recover
		final long[] noTx = new long[WARM_ROUNDS];
		for (int round = -WARMUP_ROUNDS; round < WARM_ROUNDS; round++) {
			final long start = System.nanoTime();
			final long checksum = walk.run(Arm.B_SINGLE_PASS, affected, affectedOwners);
			final long elapsed = System.nanoTime() - start;
			if (round >= 0) {
				noTx[round] = elapsed;
			}
			verifyChecksum(checksums, Arm.B_SINGLE_PASS, checksum);
		}
		report("WARM B (no transaction)", noTx, walk.probesFor(Arm.B_SINGLE_PASS));

		// the same, cold: whether the transactional-layer overhead survives once the walk is memory-bound
		final long[] coldNoTx = new long[perArm];
		for (int round = 0; round < perArm; round++) {
			evictCaches();
			final long start = System.nanoTime();
			final long checksum = walk.run(Arm.B_SINGLE_PASS, affected, affectedOwners);
			coldNoTx[round] = System.nanoTime() - start;
			verifyChecksum(checksums, Arm.B_SINGLE_PASS, checksum);
		}
		report("COLD B (no transaction)", coldNoTx, walk.probesFor(Arm.B_SINGLE_PASS));
		System.out.println();
	}

	/**
	 * Records an arm's first checksum and rejects any later disagreement — an arm that computes a different
	 * answer must never be compared as if it were merely faster.
	 *
	 * @param checksums per-arm accumulator of first-seen checksums
	 * @param arm       the arm that just ran
	 * @param checksum  the checksum it produced
	 */
	private static void verifyChecksum(@Nonnull long[] checksums, @Nonnull Arm arm, long checksum) {
		if (checksums[arm.ordinal()] == Long.MIN_VALUE) {
			checksums[arm.ordinal()] = checksum;
		} else if (checksums[arm.ordinal()] != checksum) {
			throw new IllegalStateException(
				"Arm " + arm + " is not deterministic: " + checksums[arm.ordinal()] + " != " + checksum
			);
		}
		for (final Arm other : Arm.values()) {
			if (checksums[other.ordinal()] != Long.MIN_VALUE && checksums[other.ordinal()] != checksum) {
				throw new IllegalStateException(
					"Arm " + arm + " disagrees with " + other + ": " + checksum + " != "
						+ checksums[other.ordinal()]
				);
			}
		}
	}

	/**
	 * Prints one reading as median, p95 and nanoseconds per probed partition.
	 *
	 * @param label   what was measured
	 * @param samples the per-round durations
	 * @param probes  how many partitions this arm actually probes
	 */
	private static void report(@Nonnull String label, @Nonnull long[] samples, long probes) {
		final long[] sorted = samples.clone();
		Arrays.sort(sorted);
		final long median = sorted[sorted.length / 2];
		System.out.printf(
			"  %-26s %,12d %,12d %12s %,10d%n",
			label, median, sorted[(int) (sorted.length * 0.95)],
			probes == 0L ? "n/a" : String.format("%.1f", median / (double) probes), probes
		);
	}

	/**
	 * Streams a buffer larger than twice the LLC, touching one byte per cache line, so the partition working set
	 * is evicted before the next timed walk. Runs OUTSIDE any timed region.
	 */
	private static void evictCaches() {
		final byte[] buffer = new byte[EVICTION_BYTES];
		long sum = 0L;
		for (int i = 0; i < buffer.length; i += 64) {
			buffer[i] = (byte) i;
			sum += buffer[i];
		}
		evictionSink = sum;
	}

	/**
	 * Builds the affected-owner shapes: the sparse and dense ends of the real partition-size distribution of the
	 * reference the trigger fires for. A trigger's affected owners are exactly the members of one of that
	 * reference's partitions, which is what `resolveForGroupEntityAttribute` resolves.
	 *
	 * @param collection the collection being measured
	 * @param mutated    the reference the trigger fires for
	 * @return the shapes to measure
	 */
	@Nonnull
	private static List<Shape> shapes(@Nonnull EntityCollection collection, @Nullable String mutated) {
		final List<Shape> shapes = new ArrayList<>(2);
		if (mutated == null) {
			return shapes;
		}
		final ReferencedTypeEntityIndex typeIndex = typeIndex(
			collection, EntityIndexType.REFERENCED_ENTITY_TYPE, mutated
		);
		if (typeIndex == null) {
			return shapes;
		}
		final List<Integer> pks = new ArrayList<>();
		typeIndex.forEachReferenceIndexPrimaryKey(pks::add);
		Bitmap smallest = null;
		Bitmap largest = null;
		for (final int pk : pks) {
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(pk);
			if (partition == null) {
				continue;
			}
			final Bitmap owners = partition.getAllPrimaryKeys();
			if (owners.size() >= 20 && (smallest == null || owners.size() < smallest.size())) {
				smallest = owners;
			}
			if (largest == null || owners.size() > largest.size()) {
				largest = owners;
			}
		}
		if (smallest != null) {
			shapes.add(new Shape("SPARSE", smallest));
		}
		if (largest != null) {
			shapes.add(new Shape("DENSE", largest));
		}
		return shapes;
	}

	/**
	 * Resolves one `REFERENCED_*_TYPE` index of a reference.
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
		throw new IllegalStateException("Index " + family + "/" + referenceName + " has the wrong type!");
	}

	/**
	 * Waits until the catalog finishes its background load.
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
	 * The walk implementations under comparison: three candidates, and two unimplementable bounds that say how
	 * much of the walk any lookup optimization could ever remove.
	 */
	private enum Arm {
		A_BOXED_KEYSET("A boxed keySet"),
		B_SINGLE_PASS("B single pass"),
		C_HYBRID_REVERSE("C hybrid reverse"),
		D_INDEX_PRERESOLVED("D index preresolved"),
		E_BITMAP_PRERESOLVED("E bitmap preresolved"),
		F_INTERSECTS_FIRST("F intersects first"),
		G_PER_INDEX_HYBRID("G per-index hybrid");

		private final String label;

		Arm(@Nonnull String label) {
			this.label = label;
		}
	}

	/**
	 * One affected-owner set, taken from a real partition of the reference the trigger fires for.
	 *
	 * @param name   the shape's label
	 * @param owners the affected owner primary keys
	 */
	private record Shape(@Nonnull String name, @Nonnull Bitmap owners) {
	}

	/**
	 * One type index's own slice of the hybrid: its reverse map, the union of the owners its covered
	 * partitions hold, and the partitions of its own that stayed above the threshold.
	 *
	 * @param reverse       owner primary key to the covered partitions of this index holding it
	 * @param coveredOwners union of this index's covered partitions' members, so the affected set can be
	 *                      intersected against it once instead of probing the map per affected owner
	 * @param residual      this index's partitions left on the walk
	 */
	private record Slice(
		@Nonnull Map<Integer, int[]> reverse,
		@Nonnull PersistentRoaringBitmap coveredOwners,
		@Nonnull int[] residual
	) {

		/**
		 * Packs one index's accumulated lists into the immutable slice the timed loop reads.
		 *
		 * @param building accumulated owner to partition lists
		 * @param residual accumulated above-threshold partitions
		 * @return the packed slice
		 */
		@Nonnull
		static Slice of(@Nonnull Map<Integer, List<Integer>> building, @Nonnull List<Integer> residual) {
			final Map<Integer, int[]> packed = CollectionUtils.createHashMap(Math.max(building.size(), 1));
			final PersistentRoaringBitmap owners = new PersistentRoaringBitmap();
			for (final Map.Entry<Integer, List<Integer>> entry : building.entrySet()) {
				final List<Integer> value = entry.getValue();
				final int[] partitions = new int[value.size()];
				for (int i = 0; i < partitions.length; i++) {
					partitions[i] = value.get(i);
				}
				packed.put(entry.getKey(), partitions);
				owners.add(entry.getKey());
			}
			final int[] left = new int[residual.size()];
			for (int i = 0; i < left.length; i++) {
				left[i] = residual.get(i);
			}
			return new Slice(packed, owners, left);
		}
	}

	/**
	 * The prepared walk: the sibling type indexes, and the hybrid reverse map over their small partitions.
	 */
	private static final class Walk {
		private final EntityCollection collection;
		private final List<ReferencedTypeEntityIndex> typeIndexes = new ArrayList<>();
		/** partition storage PK to its already-resolved index, for the D bound */
		private final IntObjectMap<EntityIndex> resolvedIndexes = new IntObjectHashMap<>(4096);
		/** partition storage PK to its already-resolved membership bitmap, for the E bound */
		private final IntObjectMap<PersistentRoaringBitmap> resolvedBitmaps = new IntObjectHashMap<>(4096);
		private final Map<Integer, int[]> reverse = CollectionUtils.createHashMap(1024);
		/** one reverse map per type index, the placement arm G prices against C's merged one */
		private final List<Slice> slices = new ArrayList<>();
		private final int[] residual;
		private final long totalPartitions;
		private final long coveredPartitions;
		private final long residualPartitions;
		private final long memberships;

		Walk(@Nonnull EntityCollection collection, @Nonnull List<String> siblings, int threshold) {
			this.collection = collection;
			long total = 0L;
			long covered = 0L;
			long members = 0L;
			final Map<Integer, List<Integer>> building = CollectionUtils.createHashMap(1024);
			final List<Integer> big = new ArrayList<>();
			for (final String sibling : siblings) {
				for (final EntityIndexType family : new EntityIndexType[]{
					EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
				}) {
					final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, sibling);
					if (typeIndex == null) {
						continue;
					}
					this.typeIndexes.add(typeIndex);
					final Map<Integer, List<Integer>> sliceBuilding = CollectionUtils.createHashMap(1024);
					final List<Integer> sliceResidual = new ArrayList<>();
					final List<Integer> pks = new ArrayList<>();
					typeIndex.forEachReferenceIndexPrimaryKey(pks::add);
					for (final int pk : pks) {
						final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(pk);
						if (partition == null) {
							continue;
						}
						total++;
						final Bitmap owners = partition.getAllPrimaryKeys();
						// the D/E bounds resolve every partition once here, so their timed loops pay only the
						// substitute lookup (D) or nothing at all (E)
						this.resolvedIndexes.put(pk, partition);
						this.resolvedBitmaps.put(pk, getRoaringBitmap(owners));
						if (owners.size() > threshold) {
							big.add(pk);
							sliceResidual.add(pk);
							continue;
						}
						covered++;
						members += owners.size();
						final OfInt it = owners.iterator();
						while (it.hasNext()) {
							final int owner = it.nextInt();
							building.computeIfAbsent(owner, __ -> new ArrayList<>(2)).add(pk);
							sliceBuilding.computeIfAbsent(owner, __ -> new ArrayList<>(2)).add(pk);
						}
					}
					this.slices.add(Slice.of(sliceBuilding, sliceResidual));
				}
			}
			for (final Map.Entry<Integer, List<Integer>> entry : building.entrySet()) {
				final List<Integer> value = entry.getValue();
				final int[] packed = new int[value.size()];
				for (int i = 0; i < packed.length; i++) {
					packed[i] = value.get(i);
				}
				this.reverse.put(entry.getKey(), packed);
			}
			this.residual = new int[big.size()];
			for (int i = 0; i < this.residual.length; i++) {
				this.residual[i] = big.get(i);
			}
			this.totalPartitions = total;
			this.coveredPartitions = covered;
			this.residualPartitions = big.size();
			this.memberships = members;
		}

		/**
		 * Returns how many partitions the given arm actually probes, which is the denominator of its
		 * nanoseconds-per-probe figure.
		 *
		 * @param arm the arm
		 * @return the probe count
		 */
		long probesFor(@Nonnull Arm arm) {
			return arm == Arm.C_HYBRID_REVERSE || arm == Arm.G_PER_INDEX_HYBRID
				? this.residualPartitions : this.totalPartitions;
		}

		/**
		 * Runs one arm and returns an order-independent checksum of the `(owner, partition)` pairs it emits.
		 *
		 * @param arm            the implementation to run
		 * @param affected       the affected owner primary keys
		 * @param affectedOwners the same set, materialised as primitives
		 * @return the checksum
		 */
		long run(@Nonnull Arm arm, @Nonnull PersistentRoaringBitmap affected, @Nonnull int[] affectedOwners) {
			return switch (arm) {
				case A_BOXED_KEYSET -> runBoxed(affected);
				case B_SINGLE_PASS -> runSinglePass(affected);
				case C_HYBRID_REVERSE -> runHybrid(affected, affectedOwners);
				case D_INDEX_PRERESOLVED -> runPreresolved(affected, false);
				case E_BITMAP_PRERESOLVED -> runPreresolved(affected, true);
				case F_INTERSECTS_FIRST -> runIntersectsFirst(affected);
				case G_PER_INDEX_HYBRID -> runPerIndexHybrid(affected);
			};
		}

		/**
		 * Arm A — the pre-`5db4385e1` shape: a boxed keySet iteration plus a second hash into the same map.
		 *
		 * @param affected the affected owner primary keys
		 * @return the checksum
		 */
		private long runBoxed(@Nonnull PersistentRoaringBitmap affected) {
			long checksum = 0L;
			for (int i = 0; i < this.typeIndexes.size(); i++) {
				final ReferencedTypeEntityIndex typeIndex = this.typeIndexes.get(i);
				for (final Integer referencedPk : typeIndex.getAllTrackedReferencedEntityPrimaryKeys()) {
					for (final int pk : typeIndex.getAllReferenceIndexes(referencedPk)) {
						checksum += probe(pk, affected);
					}
				}
			}
			return checksum;
		}

		/**
		 * Arm B — current `HEAD`: one `entrySet` pass, nothing allocated per partition.
		 *
		 * @param affected the affected owner primary keys
		 * @return the checksum
		 */
		private long runSinglePass(@Nonnull PersistentRoaringBitmap affected) {
			final long[] checksum = new long[1];
			for (int i = 0; i < this.typeIndexes.size(); i++) {
				this.typeIndexes.get(i).forEachReferenceIndexPrimaryKey(
					pk -> checksum[0] += probe(pk, affected)
				);
			}
			return checksum[0];
		}

		/**
		 * Arm C — the hybrid: a reverse lookup per affected owner for the covered partitions, plus the ordinary
		 * walk over the few partitions left above the threshold.
		 *
		 * @param affected       the affected owner primary keys
		 * @param affectedOwners the same set, materialised, so the hot loop iterates primitives
		 * @return the checksum
		 */
		private long runHybrid(@Nonnull PersistentRoaringBitmap affected, @Nonnull int[] affectedOwners) {
			long checksum = 0L;
			for (final int owner : affectedOwners) {
				final int[] partitions = this.reverse.get(owner);
				if (partitions != null) {
					for (final int pk : partitions) {
						checksum += pair(owner, pk);
					}
				}
			}
			for (final int pk : this.residual) {
				checksum += probe(pk, affected);
			}
			return checksum;
		}

		/**
		 * Arm G — the same hybrid as C with the reverse map split per type index. Each slice's covered-owner
		 * union is intersected against the affected set once, so only owners that can hit are looked up; the
		 * pairs emitted are identical to C's and only the number of hash lookups differs.
		 *
		 * @param affected the affected owner primary keys
		 * @return the checksum
		 */
		private long runPerIndexHybrid(@Nonnull PersistentRoaringBitmap affected) {
			long checksum = 0L;
			for (int i = 0; i < this.slices.size(); i++) {
				final Slice slice = this.slices.get(i);
				for (final int owner : and(slice.coveredOwners(), affected).toArray()) {
					final int[] partitions = slice.reverse().get(owner);
					if (partitions != null) {
						for (final int pk : partitions) {
							checksum += pair(owner, pk);
						}
					}
				}
				for (final int pk : slice.residual()) {
					checksum += probe(pk, affected);
				}
			}
			return checksum;
		}

		/**
		 * Arm F — arm B with the `and()` gated behind an allocation-free intersection test. Everything else is
		 * identical, including the index lookup and the bitmap resolution, so the delta against B is exactly
		 * what the discarded intersection bitmaps and their `int[]`s cost.
		 *
		 * @param affected the affected owner primary keys
		 * @return the checksum
		 */
		private long runIntersectsFirst(@Nonnull PersistentRoaringBitmap affected) {
			final long[] checksum = new long[1];
			for (int i = 0; i < this.typeIndexes.size(); i++) {
				this.typeIndexes.get(i).forEachReferenceIndexPrimaryKey(
					pk -> {
						final EntityIndex probed = this.collection.getIndexByPrimaryKeyIfExists(pk);
						if (probed == null) {
							return;
						}
						final PersistentRoaringBitmap owners = getRoaringBitmap(probed.getAllPrimaryKeys());
						if (!intersects(owners, affected)) {
							return;
						}
						for (final int owner : and(owners, affected).toArray()) {
							checksum[0] += pair(owner, pk);
						}
					}
				);
			}
			return checksum[0];
		}

		/**
		 * Arms D and E — the two bounds. Both keep arm B's traversal verbatim, so the delta against B isolates
		 * the lookup and nothing else; they differ only in how far the per-partition resolution is skipped.
		 *
		 * @param affected     the affected owner primary keys
		 * @param skipBitmap   `true` for E: the membership bitmap is pre-resolved as well, leaving only the
		 *                     intersection in the timed region
		 * @return the checksum
		 */
		private long runPreresolved(@Nonnull PersistentRoaringBitmap affected, boolean skipBitmap) {
			final long[] checksum = new long[1];
			for (int i = 0; i < this.typeIndexes.size(); i++) {
				this.typeIndexes.get(i).forEachReferenceIndexPrimaryKey(
					pk -> {
						final PersistentRoaringBitmap owners;
						if (skipBitmap) {
							owners = this.resolvedBitmaps.get(pk);
							if (owners == null) {
								return;
							}
						} else {
							final EntityIndex probed = this.resolvedIndexes.get(pk);
							if (probed == null) {
								return;
							}
							owners = getRoaringBitmap(probed.getAllPrimaryKeys());
						}
						for (final int owner : and(owners, affected).toArray()) {
							checksum[0] += pair(owner, pk);
						}
					}
				);
			}
			return checksum[0];
		}

		/**
		 * Probes one partition exactly as `collectOwnersOfReducedIndexes` does and folds every matched owner
		 * into the checksum.
		 *
		 * @param pk       the partition's storage primary key
		 * @param affected the affected owner primary keys
		 * @return the checksum contribution
		 */
		private long probe(int pk, @Nonnull PersistentRoaringBitmap affected) {
			final EntityIndex probed = this.collection.getIndexByPrimaryKeyIfExists(pk);
			if (probed == null) {
				return 0L;
			}
			final int[] owners = and(getRoaringBitmap(probed.getAllPrimaryKeys()), affected).toArray();
			long checksum = 0L;
			for (final int owner : owners) {
				checksum += pair(owner, pk);
			}
			return checksum;
		}

		/**
		 * Order-independent contribution of one emitted `(owner, partition)` pair.
		 *
		 * @param owner the owner primary key
		 * @param pk    the partition's storage primary key
		 * @return the contribution
		 */
		private static long pair(int owner, int pk) {
			return (long) owner * 1000003L + pk;
		}
	}

	/**
	 * Not instantiable — this is a `main()`-style report.
	 */
	private ConditionalFacetSiblingResolverReport() {
	}
}
