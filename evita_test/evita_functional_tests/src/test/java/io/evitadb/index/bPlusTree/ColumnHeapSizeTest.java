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

package io.evitadb.index.bPlusTree;

import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.UUID;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.ArrayUtils.EMPTY_BYTE_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_INT_ARRAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies `getHeapSizeInBytes` across every {@link ValueColumn} and {@link RecordColumn} implementation, against JOL
 * rather than against arithmetic restated from the production code.
 *
 * # Why the expectations are measured
 *
 * Every assertion takes its expected value from a JOL walk of the real object graph. Asserting the same formula the
 * implementation computes produces a test that only fails when someone edits one of the two copies, and stays green
 * while both are wrong together — which is how the estimates this work replaced came to be off by up to 6x inside a
 * fully covered file.
 *
 * # The accounting rules being pinned
 *
 * - **Capacity, not cardinality.** A column is allocated at the leaf block size and pays for every slot, live or not.
 * - **Shared objects are not charged to their holders.** {@link LongValueColumn}'s codec is a JVM-wide enum constant,
 *   and an empty {@link FrontCodedStringColumn} parks on shared empty arrays — both cost only their reference slot.
 * - **Elements are the caller's policy, not the column's.** {@link BoxedObjectColumn} charges reference slots alone by
 *   default; the sizer overload adds the referenced objects. Nothing hard-codes which elements are shared.
 *
 * @author Claude (B+ tree column heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("B+ tree column heap-size reporting")
class ColumnHeapSizeTest {
	/**
	 * Leaf block size used throughout — large enough that capacity slack is visible, small enough to stay readable.
	 */
	private static final int BLOCK_SIZE = 64;

	/**
	 * Produces a deterministic {@link UUID} for the given seed. {@link UUID} is the element type of choice for the
	 * boxed-column tests: it is {@link Comparable}, it holds two `long` fields and **no references**, so its shallow
	 * size is also its deep size and the element arithmetic has nothing hidden in it.
	 *
	 * @param seed the seed to derive both halves from
	 * @return a deterministic UUID
	 */
	@Nonnull
	private static UUID uuid(int seed) {
		return new UUID(seed, -seed);
	}

	/**
	 * Builds the shared-root set a {@link BoxedObjectColumn} measurement has to exclude: every element it merely
	 * points at, plus {@link UUID}`.class` — the component type its `keyType` field addresses, which belongs to the
	 * JVM rather than to any column holding it.
	 *
	 * @param elements the elements stored in the column
	 * @return the roots to subtract from a JOL walk
	 */
	@Nonnull
	private static Object[] sharedRootsOf(@Nonnull UUID[] elements) {
		final Object[] sharedRoots = new Object[elements.length + 1];
		sharedRoots[0] = UUID.class;
		System.arraycopy(elements, 0, sharedRoots, 1, elements.length);
		return sharedRoots;
	}

	@Nested
	@DisplayName("matches the measured heap for every implementation")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForIntValueColumn() {
			final ValueColumn<Integer> column = new IntValueColumn<>(new int[BLOCK_SIZE]);
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForLongValueColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> column = new LongValueColumn<>(codec, new long[BLOCK_SIZE]);
			// the codec is excluded from the measurement because it is a shared enum constant, not this column's
			assertEquals(JolHeapSize.ownedSize(column, codec), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForInstantValueColumn() {
			final ValueColumn<Instant> column = new InstantValueColumn<>(new long[BLOCK_SIZE], new int[BLOCK_SIZE]);
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForPopulatedFrontCodedStringColumn() {
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			for (int i = 0; i < 40; i++) {
				// a shared-prefix corpus, which is what front-coding exists for
				column.insertKeyAt(i, String.format("product-name-%04d", i));
			}
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForIntRecordColumn() {
			final RecordColumn column = new IntRecordColumn(new int[BLOCK_SIZE]);
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForLongRecordColumn() {
			final RecordColumn column = new LongRecordColumn(new long[BLOCK_SIZE]);
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("excludes structure it does not own")
	class Ownership {

		@Test
		void shouldNotChargeTheSharedCodecToTheLongColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> column = new LongValueColumn<>(codec, new long[BLOCK_SIZE]);

			// the codec really is reachable from the column, so a naive deep walk would bill it here...
			final long naiveDeepWalk = JolHeapSize.ownedSize(column);
			assertTrue(naiveDeepWalk > column.getHeapSizeInBytes());

			// ...and every column of this key type would be billed for the same one enum constant
			final ValueColumn<Integer> sibling = new LongValueColumn<>(codec, new long[BLOCK_SIZE]);
			assertEquals(column.getHeapSizeInBytes(), sibling.getHeapSizeInBytes());
		}

		@Test
		void shouldNotChargeTheSharedEmptyArraysToAFreshFrontCodedColumn() {
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			// a fresh column parks both backing fields on the JVM-wide empty arrays; charging them would bill one
			// pair of objects once per empty leaf in the whole catalog
			assertEquals(
				JolHeapSize.ownedSize(column, EMPTY_BYTE_ARRAY, EMPTY_INT_ARRAY),
				column.getHeapSizeInBytes()
			);
		}

		@Test
		void shouldChargeReferenceSlotsButNotElementsByDefault() {
			final ValueColumn<UUID> column = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			final UUID[] elements = new UUID[30];
			for (int i = 0; i < elements.length; i++) {
				elements[i] = uuid(i);
				column.insertKeyAt(i, elements[i]);
			}

			// subtracting the elements AND the component type leaves exactly the column plus its reference slots.
			// `keyType` is the second shared object in this class: a Class instance the JVM owns for the lifetime of
			// its loader, which JOL happily walks into and bills at ~1 kB - once per column, if it were charged
			assertEquals(
				JolHeapSize.ownedSize(column, sharedRootsOf(elements)),
				column.getHeapSizeInBytes()
			);
		}

		@Test
		void shouldAddElementsWhenTheSizerPricesThem() {
			final ValueColumn<UUID> column = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			for (int i = 0; i < 30; i++) {
				column.insertKeyAt(i, uuid(i));
			}

			// a UUID holds two longs and no references, so its shallow size is also its deep size
			assertEquals(
				JolHeapSize.ownedSize(column, UUID.class),
				column.getHeapSizeInBytes(JolHeapSize::shallowSize)
			);
		}

		@Test
		void shouldPriceOnlyLiveSlotsWhenSizing() {
			final ValueColumn<UUID> full = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			final ValueColumn<UUID> sparse = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			for (int i = 0; i < BLOCK_SIZE; i++) {
				full.insertKeyAt(i, uuid(i));
			}
			sparse.insertKeyAt(0, uuid(0));

			// the empty tail addresses nothing, so the sizer must not be asked to price it - a sizer that threw on
			// null would otherwise blow up on every partially filled leaf in the catalog
			assertEquals(full.getHeapSizeInBytes(), sparse.getHeapSizeInBytes());
			assertTrue(
				full.getHeapSizeInBytes(JolHeapSize::shallowSize)
					> sparse.getHeapSizeInBytes(JolHeapSize::shallowSize),
				"only the live slots may contribute element bytes"
			);
		}

		@Test
		void shouldIgnoreTheSizerInPrimitiveColumns() {
			final ValueColumn<Integer> column = new IntValueColumn<>(new int[BLOCK_SIZE]);
			for (int i = 0; i < 30; i++) {
				column.insertKeyAt(i, i);
			}

			// keys live as `int` values inside the array - there is no element to price, and a caller passing a
			// sizer must not accidentally inflate the figure
			assertEquals(
				column.getHeapSizeInBytes(),
				column.getHeapSizeInBytes(element -> 1_000_000L)
			);
		}
	}

	@Nested
	@DisplayName("prices capacity rather than cardinality")
	class CapacitySlack {

		@Test
		void shouldChargeTheWholeBlockRegardlessOfLiveEntries() {
			final ValueColumn<Integer> empty = new IntValueColumn<>(new int[BLOCK_SIZE]);
			final ValueColumn<Integer> full = new IntValueColumn<>(new int[BLOCK_SIZE]);
			for (int i = 0; i < BLOCK_SIZE; i++) {
				full.insertKeyAt(i, i);
			}

			// a leaf block is allocated once and then fills up; its footprint does not move as it does
			assertEquals(empty.getHeapSizeInBytes(), full.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(full), full.getHeapSizeInBytes());
		}

		@Test
		void shouldScaleWithBlockSizeNotEntryCount() {
			final ValueColumn<Integer> small = new IntValueColumn<>(new int[BLOCK_SIZE]);
			final ValueColumn<Integer> large = new IntValueColumn<>(new int[BLOCK_SIZE * 4]);

			assertTrue(large.getHeapSizeInBytes() > small.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(large), large.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("charges structure aliased with a superseded version")
	class CopyOnWriteSharing {

		@Test
		void shouldChargeTheStructurallySharedBlobInFull() {
			final ValueColumn<String> committed = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			for (int i = 0; i < 40; i++) {
				committed.insertKeyAt(i, String.format("product-name-%04d", i));
			}
			// `duplicate` structurally shares `data` / `restartOffsets` with the source rather than copying them
			final ValueColumn<String> duplicate = committed.duplicate();

			// both report the full figure: the predecessor is garbage-in-waiting and the survivor becomes sole owner,
			// so discounting the share would under-report whichever one outlives the other
			assertEquals(committed.getHeapSizeInBytes(), duplicate.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(duplicate), duplicate.getHeapSizeInBytes());

			// and the share is real - excluding the source strips almost the whole footprint of the duplicate
			assertTrue(
				JolHeapSize.ownedSize(duplicate, committed) < duplicate.getHeapSizeInBytes() / 2,
				"the duplicate must genuinely alias the source's backing arrays"
			);
		}
	}
}
