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

package io.evitadb.roaringbitmap;

import io.evitadb.utils.JolHeapSize;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link PersistentRoaringBitmap#getHeapSizeInBytes(HeapLayout)} reports the heap a bitmap
 * really occupies, measured against JOL rather than against a number somebody wrote down.
 *
 * # What is asserted, and why it is asserted this way
 *
 * Every expectation here is **measured before it is compared** — `JolHeapSize` walks the real object graph
 * and the production method is then required to match it exactly. No literal byte counts appear below.
 * That shape is deliberate: the estimates this replaces were tested by asserting the same arithmetic the
 * production code performed, a mirror that goes green whenever both sides are wrong together. It is how a
 * 6x error survived in a fully covered file, and how roaring's own `getLongSizeInBytes()` came to be trusted
 * despite being 39x out in the case the second nested class below reproduces.
 *
 * # What is counted
 *
 * The bitmap's own object, the `RoaringArray` backbone with its `keys` / `values` arrays, the parallel
 * `shared` flags, and every container with its backing array **at that array's allocated length**.
 *
 * Containers aliased with another bitmap under copy-on-write are counted **in full**. In evitaDB that
 * aliasing is essentially always with a *superseded version* of the same logical bitmap — the commit path
 * merges via `or` and `andNot`, both of which alias — and the predecessor is collected shortly afterwards,
 * leaving the survivor sole owner. Reporting the pre-collection split would describe a state that lasts
 * milliseconds. The last nested class pins that decision so it cannot be quietly reversed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(DATA_TYPE)
@DisplayName("PersistentRoaringBitmap heap-size reporting")
class PersistentRoaringBitmapHeapSizeTest {
	/** Number of values in one chunk of the value space; each non-empty chunk gets one container. */
	private static final int CHUNK = 1 << 16;
	/** Cardinality at which roaring stops using an array container for a chunk. */
	private static final int ARRAY_CONTAINER_MAX = 4096;
	/** The running VM's layout, in the form the roaring module accepts. */
	private static final HeapLayout HEAP_LAYOUT = new HeapLayout(
		VMLayout.current().referenceSize(),
		VMLayout.current().objectHeaderSize(),
		VMLayout.current().arrayHeaderSize(),
		VMLayout.current().objectAlignment()
	);

	/**
	 * Builds a bitmap over the given record ids.
	 *
	 * @param ids the record ids to add
	 * @return a freshly built bitmap
	 */
	@Nonnull
	private static PersistentRoaringBitmap bitmapOf(@Nonnull int... ids) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(ids);
		return bitmap;
	}

	/**
	 * Produces `count` consecutive record ids starting at `from`.
	 *
	 * @param from  the first record id
	 * @param count how many ids to produce
	 * @return the ids in ascending order
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
	 * Asserts the reported heap size equals what JOL measures for the same bitmap.
	 *
	 * @param bitmap the bitmap to check
	 */
	private static void assertMatchesMeasuredHeap(@Nonnull PersistentRoaringBitmap bitmap) {
		assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes(HEAP_LAYOUT));
	}

	@Nested
	@DisplayName("matches the measured heap for every container encoding")
	class ContainerEncodings {

		@Test
		void shouldMatchMeasuredHeapWhenBitmapIsEmpty() {
			// an empty bitmap still pays for the backbone arrays at their initial capacity, which is why
			// a cardinality-derived figure answers 8 bytes for something occupying well over a hundred
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			assertMatchesMeasuredHeap(bitmap);
		}

		@Test
		void shouldMatchMeasuredHeapWhenChunkUsesArrayContainer() {
			assertMatchesMeasuredHeap(bitmapOf(contiguous(1, 100)));
		}

		@Test
		void shouldMatchMeasuredHeapWhenChunkUsesBitmapContainer() {
			assertMatchesMeasuredHeap(bitmapOf(contiguous(0, CHUNK)));
		}

		@Test
		void shouldMatchMeasuredHeapWhenChunkUsesRunContainer() {
			final PersistentRoaringBitmap bitmap = bitmapOf(contiguous(0, CHUNK));
			bitmap.runOptimize();
			assertMatchesMeasuredHeap(bitmap);
		}

		@Test
		void shouldMatchMeasuredHeapWhenBitmapSpansManyChunks() {
			final int[] ids = new int[100 * 1_000];
			int i = 0;
			for (int chunk = 0; chunk < 100; chunk++) {
				for (int offset = 0; offset < 1_000; offset++) {
					ids[i++] = chunk * CHUNK + offset;
				}
			}
			assertMatchesMeasuredHeap(bitmapOf(ids));
		}
	}

	@Nested
	@DisplayName("prices capacity rather than cardinality")
	class CapacitySlack {

		@Test
		void shouldReportRetainedCapacityAfterRecordsAreRemoved() {
			// grow past the array-container limit, then delete back down. Roaring has no shrink path, so
			// the container keeps the capacity it grew to and the heap cost stays with it
			final PersistentRoaringBitmap grown = bitmapOf(contiguous(1, ARRAY_CONTAINER_MAX));
			for (int id = ARRAY_CONTAINER_MAX; id > 100; id--) {
				grown.remove(id);
			}
			assertMatchesMeasuredHeap(grown);
		}

		@Test
		void shouldDistinguishTwoBitmapsHoldingTheSameRecords() {
			// the pair that makes the case: identical record sets, and every publicly visible statistic
			// (cardinality, container count, getLongSizeInBytes) agrees on both - only the allocated
			// capacity differs, and only from inside the roaring package is it visible
			final PersistentRoaringBitmap fresh = bitmapOf(contiguous(1, 100));
			final PersistentRoaringBitmap grown = bitmapOf(contiguous(1, ARRAY_CONTAINER_MAX));
			for (int id = ARRAY_CONTAINER_MAX; id > 100; id--) {
				grown.remove(id);
			}

			assertEquals(fresh.getCardinality(), grown.getCardinality());
			assertEquals(fresh.getLongSizeInBytes(), grown.getLongSizeInBytes());
			assertTrue(
				grown.getHeapSizeInBytes(HEAP_LAYOUT) > 10 * fresh.getHeapSizeInBytes(HEAP_LAYOUT),
				"the grown bitmap retains an order of magnitude more heap than the fresh one"
			);
			assertMatchesMeasuredHeap(fresh);
			assertMatchesMeasuredHeap(grown);
		}
	}

	@Nested
	@DisplayName("charges copy-on-write containers shared with a superseded version")
	class CopyOnWriteSharing {

		@Test
		void shouldChargeContainersAliasedWithThePreviousVersion() {
			// reproduces exactly what BitmapChanges.getMergedBitmap() computes at commit: or the
			// insertions in, andNot the removals out. Both operations alias every container the
			// transaction did not touch - so the bitmap must span MANY chunks for the scenario to be
			// representative. A dense two-chunk bitmap would leave only one container aliased and would
			// understate the effect to the point of testing nothing
			final int[] ids = new int[100 * 1_000];
			int i = 0;
			for (int chunk = 0; chunk < 100; chunk++) {
				for (int offset = 0; offset < 1_000; offset++) {
					ids[i++] = chunk * CHUNK + offset;
				}
			}
			final PersistentRoaringBitmap previous = bitmapOf(ids);
			// touch exactly one of the hundred chunks; the other ninety-nine are carried over by reference
			final PersistentRoaringBitmap merged = PersistentRoaringBitmap.andNot(
				PersistentRoaringBitmap.or(previous, bitmapOf(7 * CHUNK + 50_000)),
				bitmapOf(7 * CHUNK + 1)
			);

			// measured WITHOUT `previous` as a shared root, which is the whole decision: `previous` is a
			// superseded version awaiting collection, not a peer that outlives this bitmap
			assertEquals(JolHeapSize.ownedSize(merged), merged.getHeapSizeInBytes(HEAP_LAYOUT));

			// and the exclusion that was rejected really would have gutted the figure - if this stops
			// being true the scenario has stopped aliasing and no longer tests what it claims to
			assertTrue(
				JolHeapSize.ownedSize(merged, previous) * 10 < merged.getHeapSizeInBytes(HEAP_LAYOUT),
				"excluding the superseded version must lose the overwhelming majority of the footprint"
			);
		}
	}

	/**
	 * A `TransactionalBitmap` outside a transaction - every bitmap of an index a warm-up load builds - mutates its
	 * roaring backbone in place with plain stores: a removal that empties a chunk shifts the tail left, nulls the
	 * vacated last slot and only then lowers `size`, and an insertion that fills the backbone reallocates `keys` and
	 * `values` before raising `size`. The heap walk that `EntityCollection#describeIndex` runs is a reader with no
	 * happens-before edge to that writer: it takes no snapshot, holds no transaction and runs on a management thread.
	 * It can therefore hold a `size` whose last slot reads `null` - it loaded the count before a concurrent removal
	 * nulled the slot, a plain interleaving - or a `size` past the `values` array it reads - it saw the raised count
	 * before the reallocated array behind it, which needs the stores to become visible out of program order.
	 *
	 * Both states are built by setting the package-visible `size` of the backbone directly, which moves the count
	 * without touching either array - exactly what such a reader observes. Each test asserts the figure the walk
	 * reports rather than merely that nothing was thrown.
	 */
	@Nested
	@DisplayName("steps over a container slot a concurrent in-place writer has not published")
	class UnpublishedContainerSlot {

		@Test
		void shouldStepOverASlotAConcurrentRemovalHasNulled() {
			// three chunks; emptying the first one shifts the other two left and nulls the third slot
			final PersistentRoaringBitmap bitmap = bitmapOf(1, CHUNK + 1, 2 * CHUNK + 1);
			final int sizeTheReaderLoaded = bitmap.highLowContainer.size();
			bitmap.remove(1);
			// the consistent post-removal figure: the arrays keep their length, the emptied container is gone
			final long expected = bitmap.getHeapSizeInBytes(HEAP_LAYOUT);
			assertEquals(JolHeapSize.ownedSize(bitmap), expected);

			bitmap.highLowContainer.size = sizeTheReaderLoaded;
			assertNull(
				bitmap.highLowContainer.values[sizeTheReaderLoaded - 1],
				"the admitted slot must read null - that nulled slot IS the defect"
			);

			assertEquals(
				expected, bitmap.getHeapSizeInBytes(HEAP_LAYOUT),
				"the heap walk must step over the nulled slot - it holds no container to charge"
			);
		}

		@Test
		void shouldStopAtTheValuesArrayWhenTheCountRunsAheadOfIt() {
			// fill the backbone to its exact capacity, so the next chunk has to reallocate `values`
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			int chunk = 0;
			do {
				bitmap.add(chunk++ * CHUNK + 1);
			} while (bitmap.highLowContainer.size() < bitmap.highLowContainer.values.length);
			final long expected = bitmap.getHeapSizeInBytes(HEAP_LAYOUT);
			assertEquals(JolHeapSize.ownedSize(bitmap), expected);

			// the raised count of the insertion that reallocates, seen before the reallocated array
			bitmap.highLowContainer.size = bitmap.highLowContainer.values.length + 1;

			assertEquals(
				expected, bitmap.getHeapSizeInBytes(HEAP_LAYOUT),
				"the heap walk must stop at the array it read - a count past it admits nothing that array holds"
			);
		}

		@Test
		void shouldLeaveTheHeapWalkUnchangedOnAConsistentBitmap() {
			// churn chunks in and out in place - the warm-up writer's own view, where removals empty containers and
			// insertions reallocate the backbone - and require the walk to keep matching the measured heap exactly
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			final Random random = new Random(42);
			for (int round = 0; round < 500; round++) {
				final int id = random.nextInt(40) * CHUNK + random.nextInt(3);
				if (bitmap.contains(id)) {
					bitmap.remove(id);
				} else {
					bitmap.add(id);
				}
				for (int slot = 0; slot < bitmap.highLowContainer.size(); slot++) {
					assertNotNull(bitmap.highLowContainer.values[slot], "a consistent backbone has no null live slot");
				}
				assertMatchesMeasuredHeap(bitmap);
			}
		}
	}
}
