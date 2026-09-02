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

import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.roaringbitmap.HeapLayout;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;

import static io.evitadb.spike.footprint.FootprintSpikeSupport.banner;
import static io.evitadb.spike.footprint.FootprintSpikeSupport.note;
import static io.evitadb.spike.footprint.FootprintSpikeSupport.observation;
import static io.evitadb.spike.footprint.FootprintSpikeSupport.ownedSize;
import static io.evitadb.spike.footprint.FootprintSpikeSupport.row;
import static io.evitadb.spike.footprint.FootprintSpikeSupport.section;

/**
 * Memory-footprint spike for the **bitmap layer** — the bottom of the index stack, and therefore the first
 * thing every higher estimate is built on. Every entity index, attribute index, price index and facet index
 * ultimately stores record ids in one of the {@link io.evitadb.index.bitmap.Bitmap} implementations, so an
 * error here does not stay here: it is multiplied by the number of bitmaps a collection holds.
 *
 * Run: `java … io.evitadb.spike.footprint.BitmapFootprintSpike`. No input data — every structure is built
 * in-process, which is deliberate: the shapes that matter are the *container encodings* roaring picks, and
 * those are reproduced far more precisely by construction than by sampling a real catalog.
 *
 * # What was decided, and what the losing candidates got wrong
 *
 * `PersistentRoaringBitmap.getHeapSizeInBytes(HeapLayout)` won and is what production uses. The spike is
 * kept because it is the evidence, and because the two rejected candidates are the ones anybody would reach
 * for first:
 *
 * - **`getLongSizeInBytes()`** — roaring's own figure. Counts only container *payload*, and counts it by
 *   **cardinality rather than capacity**: no object headers, no array headers, no `RoaringArray` backbone,
 *   no slack in a growable container's backing array. Its own javadoc warns it "may be 10x" off; measured
 *   here it reaches **39x** under. Rejected because it under-reports, which rule 3 forbids outright.
 * - **structural** — `getLongSizeInBytes()` plus every object and array the walk above it must pay for,
 *   derived from the publicly visible {@link PersistentRoaringBitmap#getContainerCount()}. Cheap and
 *   implementable from outside the roaring package, and it fixes the header blindness — but not the slack,
 *   because a container's allocated capacity is visible only from inside that package. Rejected because
 *   the capacity section below shows two bitmaps holding the identical records **18x apart in heap** while
 *   agreeing on every statistic this candidate can read. No amount of tuning reaches a figure the inputs
 *   do not contain.
 * - **capacity-aware** — {@link PersistentRoaringBitmap#getHeapSizeInBytes} reads the allocated lengths
 *   directly. Still `O(containers)` with no iteration over records, and **byte-exact against JOL on every
 *   shape measured here**. The cost is an API addition to a vendored module, paid once.
 *
 * # The sharing question this spike exists to answer
 *
 * `PersistentRoaringBitmap` is a copy-on-write fork. `or`, `xor`, `andNot` and `clone()` do **not** copy the
 * containers they carry over unchanged — the result *aliases* them with its input, flagged in a parallel
 * `shared[]` array, and the first in-place write clones just-in-time. This is not an exotic path: the
 * transactional commit path runs straight through it, because
 * {@link io.evitadb.index.bitmap.BitmapChanges#getMergedBitmap()} merges via `or` then `andNot`. Every
 * committed bitmap therefore aliases its predecessor for every chunk the transaction did not touch.
 *
 * Read against rule 1 alone — a structure owns what dies with it — the natural conclusion is "do not charge
 * shared containers", and it is wrong. **Rule 2 governs this case**: the co-owner here is the *superseded
 * version*, which is garbage-in-waiting rather than a peer. It is collected quickly, and the survivor is
 * then the sole owner of every container it was aliasing. The measurement below prices what excluding them
 * would have cost.
 *
 * A second, independent reason blocks the excluding reading even for anyone who wanted it: `shared[i]` is
 * raised when a container becomes co-owned and lowered **only when this bitmap clones it for its own
 * write** — never when the co-owner dies. It is a monotone "was aliased at some point" flag, not a live
 * reference count, so it cannot distinguish a live co-owner from a collected one.
 *
 * @author Claude (bitmap-layer memory-footprint spike), FG Forrest a.s. (c) 2026
 */
public class BitmapFootprintSpike {
	/** Number of values in a chunk — a container holds the low 16 bits of one chunk of the value space. */
	private static final int CHUNK = 1 << 16;
	/** Cardinality above which roaring switches a chunk from an array container to a bitmap container. */
	private static final int ARRAY_CONTAINER_MAX = 4096;
	/**
	 * Smallest capacity `RoaringArray` ever allocates for its `keys`/`values`/`shared` arrays. Mirrors
	 * `RoaringArray.INITIAL_CAPACITY`, which is package-private — an estimator outside the roaring package
	 * has to restate it, and gets the empty and single-container cases wrong without it.
	 */
	private static final int BACKBONE_INITIAL_CAPACITY = 4;
	/**
	 * The running VM's layout, restated in the form the roaring module accepts. That module depends on
	 * nothing but `jsr305` and so cannot detect the layout itself; evitaDB detects it once and hands it in.
	 */
	private static final HeapLayout HEAP_LAYOUT = new HeapLayout(
		VMLayout.current().referenceSize(),
		VMLayout.current().objectHeaderSize(),
		VMLayout.current().arrayHeaderSize(),
		VMLayout.current().objectAlignment()
	);

	public static void main(String[] args) {
		banner("Bitmap-layer memory footprint - JOL ground truth vs candidate estimators");

		measureImplementations();
		measureContainerShapes();
		measureCapacitySlack();
		measureCopyOnWriteSharing();

		System.out.println();
		System.out.println("Legend: 'roaring' = PersistentRoaringBitmap.getLongSizeInBytes(); " +
			"'structural' = that plus the object/array graph above it, from getContainerCount().");
	}

	/* ============================================================================================ */

	/**
	 * Measures the four {@link io.evitadb.index.bitmap.Bitmap} implementations at a comparable cardinality,
	 * so the choice of implementation can be priced rather than guessed at.
	 */
	private static void measureImplementations() {
		section("Bitmap implementations - 1 000 contiguous record ids");
		final int[] ids = contiguous(1, 1_000);

		// EmptyBitmap is a JVM-wide singleton: whoever points at it owns nothing but the reference slot.
		// Measured against a second reference to the SAME instance, which subtracts the whole graph and
		// leaves the zero that rule 1 demands - the point being that the zero is measured, not assumed
		observation("EmptyBitmap.INSTANCE (shared singleton, per owner)",
			ownedSize(EmptyBitmap.INSTANCE, EmptyBitmap.INSTANCE));
		observation("SingleRecordBitmap (1 record, no backing array)", ownedSize(new SingleRecordBitmap(42)));
		observation("ArrayBitmap (CompositeIntArray backing)", ownedSize(new ArrayBitmap(ids)));
		observation("BaseBitmap (roaring backing)", ownedSize(new BaseBitmap(ids)));
		observation("TransactionalBitmap (roaring backing, no open transaction)",
			ownedSize(new TransactionalBitmap(ids)));

		// the transactional wrapper adds only its own object: the `id` is a primitive long and the
		// transactional layer lives in the transaction, not in the bitmap
		final long base = ownedSize(new BaseBitmap(ids));
		final long transactional = ownedSize(new TransactionalBitmap(ids));
		note(String.format(
			"TransactionalBitmap costs %+d B over BaseBitmap at rest - the wrapper object only; " +
				"its BitmapChanges layer is owned by the open transaction, never by the bitmap",
			transactional - base
		));
		System.out.println();
	}

	/**
	 * Measures the three container encodings roaring picks between, plus the multi-chunk case. This is where
	 * a bitmap's cost actually lives, and where the two candidate estimators diverge.
	 */
	private static void measureContainerShapes() {
		section("Roaring container shapes - truth vs candidate estimators");

		// an empty bitmap points at shared empty-array constants inside RoaringArray. Those are static
		// finals owned by the class, so a second empty bitmap as the shared root subtracts exactly them -
		// which is the only way to reach a private static from outside the package
		final PersistentRoaringBitmap empty = new PersistentRoaringBitmap();
		reportRoaring("empty", empty, ownedSize(empty, new PersistentRoaringBitmap()));

		reportRoaring("1 record (array container, 1 value)", roaringOf(contiguous(1, 1)));
		reportRoaring("100 contiguous (array container)", roaringOf(contiguous(1, 100)));
		reportRoaring("4 096 contiguous (array container, at the limit)",
			roaringOf(contiguous(1, ARRAY_CONTAINER_MAX)));
		reportRoaring("4 097 contiguous (flips to bitmap container)",
			roaringOf(contiguous(1, ARRAY_CONTAINER_MAX + 1)));
		reportRoaring("65 536 contiguous (one full chunk, bitmap container)", roaringOf(contiguous(0, CHUNK)));

		// a run container is what a contiguous range collapses to once runOptimize is called - the same
		// data at a fraction of the payload, which is precisely the case a cardinality-based estimate
		// gets most wrong in the opposite direction
		final PersistentRoaringBitmap runs = roaringOf(contiguous(0, CHUNK));
		runs.runOptimize();
		reportRoaring("65 536 contiguous, runOptimize()d (run container)", runs);

		reportRoaring("100 000 sparse over 100 chunks (100 array containers)", roaringOf(sparse(100, 1_000)));
		reportRoaring("100 000 dense over 2 chunks (2 bitmap containers)", roaringOf(contiguous(1, 100_000)));

		note("roaring's own figure counts container payload by CARDINALITY and adds no headers at all; " +
			"the gap it leaves is the object graph, not rounding");
		System.out.println();
	}

	/**
	 * Prices the residual error the structural estimate cannot remove: **capacity slack**. A growable
	 * container's backing array is sized by its insertion *history*, and roaring never shrinks it on removal
	 * — so two bitmaps holding the identical record set can carry very different capacities, and cardinality
	 * cannot tell them apart. Measured here by building one bitmap directly and another that reached the
	 * same set after growing large and being emptied back down.
	 */
	private static void measureCapacitySlack() {
		section("Capacity slack - the same records, two insertion histories");

		final int[] survivors = contiguous(1, 100);
		final PersistentRoaringBitmap fresh = roaringOf(survivors);
		// grow past the array-container limit, then delete back down to the same hundred records. The
		// container keeps the capacity it grew to; roaring has no shrink path
		final PersistentRoaringBitmap grown = roaringOf(contiguous(1, ARRAY_CONTAINER_MAX));
		for (int id = ARRAY_CONTAINER_MAX; id > 100; id--) {
			grown.remove(id);
		}

		final long freshTruth = ownedSize(fresh);
		final long grownTruth = ownedSize(grown);
		observation("100 records, inserted directly", freshTruth);
		observation("100 records, after growing to 4 096 and deleting back down", grownTruth);
		row("fresh [structural]", freshTruth, structuralEstimate(fresh));
		row("grown [structural]", grownTruth, structuralEstimate(grown));
		row("fresh [capacity-aware]", freshTruth, fresh.getHeapSizeInBytes(HEAP_LAYOUT));
		row("grown [capacity-aware]", grownTruth, grown.getHeapSizeInBytes(HEAP_LAYOUT));

		note(String.format(
			"identical record sets, %,d B apart (%.1fx) - both report cardinality 100, so no estimate " +
				"derived from cardinality alone can be tight AND never under-report",
			Math.abs(grownTruth - freshTruth),
			(double) Math.max(freshTruth, grownTruth) / Math.min(freshTruth, grownTruth)
		));
		System.out.println();
	}

	/**
	 * Reproduces the copy-on-write aliasing the transactional commit path actually performs, and prices it
	 * from both sides — the answer to whether a shared container may be charged to a bitmap that holds it.
	 */
	private static void measureCopyOnWriteSharing() {
		section("Copy-on-write aliasing - the commit path's or/andNot merge");

		// version 1: a hundred chunks, the shape a mature index bitmap has
		final PersistentRoaringBitmap v1 = roaringOf(sparse(100, 1_000));
		// version 2: exactly what BitmapChanges.getMergedBitmap() computes - or the insertions in, andNot
		// the removals out. One chunk is touched; the other ninety-nine are carried over by reference
		final PersistentRoaringBitmap insertions = roaringOf(new int[]{7 * CHUNK + 50_000});
		final PersistentRoaringBitmap removals = roaringOf(new int[]{7 * CHUNK + 1});
		final PersistentRoaringBitmap v2 = PersistentRoaringBitmap.andNot(
			PersistentRoaringBitmap.or(v1, insertions), removals
		);

		final long v1Alone = ownedSize(v1);
		final long v2Alone = ownedSize(v2);
		final long v2ExcludingV1 = ownedSize(v2, v1);

		observation("v1 alone (deep retained)", v1Alone);
		observation("v2 alone (deep retained)", v2Alone);
		observation("v2 excluding everything v1 reaches", v2ExcludingV1);

		note(String.format(
			"v2 aliases %,d B of v1's containers - %.1f%% of its own footprint. v1 is the SUPERSEDED " +
				"version, so rule 2 charges all of it to v2: v1 is collected shortly and v2 is then the " +
				"sole owner. Reporting %,d B would describe a state lasting milliseconds",
			v2Alone - v2ExcludingV1, 100.0 * (v2Alone - v2ExcludingV1) / v2Alone, v2ExcludingV1
		));
		note("the flag could not arbitrate even if we wanted it to: shared[i] is raised on aliasing and " +
			"lowered only when THIS bitmap clones the container for its own write - never when the co-owner " +
			"dies. After the predecessor is collected, the survivor is sole owner while still flagged shared");
		System.out.println();
	}

	/* ========================================= estimators ======================================== */

	/**
	 * The structural candidate: roaring's payload figure plus every object and array the graph above it
	 * needs, sized from the publicly visible container count.
	 *
	 * Deliberately pessimistic in one place — each container's `cardinality`/`nbrruns` int field is counted
	 * twice, once inside roaring's payload figure and once in the container object's own size. Four bytes per
	 * container, in the direction rule 3 asks for, in exchange for not needing per-container introspection
	 * the roaring package does not expose.
	 *
	 * @param bitmap the bitmap to price
	 * @return the estimated heap footprint in bytes
	 */
	private static long structuralEstimate(@Nonnull PersistentRoaringBitmap bitmap) {
		final VMLayout layout = VMLayout.current();
		final int containers = bitmap.getContainerCount();
		// the backbone arrays are never shorter than the initial capacity, so a bitmap holding zero or one
		// container still pays for four slots - without this floor the empty case reads 24% under
		final int backbone = Math.max(BACKBONE_INITIAL_CAPACITY, containers);
		// what roaring itself accounts for, stripped of its own bookkeeping (a flat 8, plus 2 per container)
		final long payload = Math.max(0L, bitmap.getLongSizeInBytes() - 8L - 2L * containers);
		return layout.sizeOfObject(2L * layout.referenceSize())                    // PersistentRoaringBitmap
			+ layout.sizeOfObject(2L * layout.referenceSize() + 4L + 1L)           // RoaringArray: refs, size, frozen
			+ layout.sizeOfArray(backbone, 2)                                      // char[] keys
			+ layout.sizeOfArray(backbone, layout.referenceSize())                 // Container[] values
			+ layout.sizeOfArray(backbone, 1)                                      // boolean[] shared
			+ containers * (layout.sizeOfObject(4L + layout.referenceSize())       // each container object
			+ layout.arrayHeaderSize())                                            // its backing array's header
			+ payload;
	}

	/* ========================================== helpers ========================================== */

	/**
	 * Prints one container-shape row for a bitmap whose owned size needs no shared-root subtraction.
	 *
	 * @param label  what the shape is
	 * @param bitmap the bitmap to measure and price
	 */
	private static void reportRoaring(@Nonnull String label, @Nonnull PersistentRoaringBitmap bitmap) {
		reportRoaring(label, bitmap, ownedSize(bitmap));
	}

	/**
	 * Prints one container-shape row against an already-computed truth, for shapes whose measurement needs a
	 * shared root subtracted first.
	 *
	 * @param label  what the shape is
	 * @param bitmap the bitmap to price
	 * @param truth  its JOL-measured owned size in bytes
	 */
	private static void reportRoaring(@Nonnull String label, @Nonnull PersistentRoaringBitmap bitmap, long truth) {
		row(label + " [roaring]", truth, bitmap.getLongSizeInBytes());
		row(label + " [structural]", truth, structuralEstimate(bitmap));
		row(label + " [capacity-aware]", truth, bitmap.getHeapSizeInBytes(HEAP_LAYOUT));
	}

	/**
	 * Builds a roaring bitmap over the given record ids.
	 *
	 * @param ids the record ids to add
	 * @return a freshly built bitmap owning everything it reaches
	 */
	@Nonnull
	private static PersistentRoaringBitmap roaringOf(@Nonnull int[] ids) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(ids);
		return bitmap;
	}

	/**
	 * Produces `count` consecutive record ids starting at `from`.
	 *
	 * @param from  the first record id
	 * @param count how many ids to produce
	 * @return the record ids in ascending order
	 */
	@Nonnull
	private static int[] contiguous(int from, int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = from + i;
		}
		return ids;
	}

	/**
	 * Produces record ids spread across `chunks` distinct chunks of the value space, `perChunk` in each — the
	 * shape that forces roaring into many small array containers rather than a few dense ones.
	 *
	 * @param chunks   how many chunks of the value space to touch
	 * @param perChunk how many contiguous ids to place in each chunk
	 * @return the record ids in ascending order
	 */
	@Nonnull
	private static int[] sparse(int chunks, int perChunk) {
		final int[] ids = new int[chunks * perChunk];
		int i = 0;
		for (int chunk = 0; chunk < chunks; chunk++) {
			for (int offset = 0; offset < perChunk; offset++) {
				ids[i++] = chunk * CHUNK + offset;
			}
		}
		return ids;
	}
}
