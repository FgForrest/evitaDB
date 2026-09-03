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

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.ArrayUtils.EMPTY_BYTE_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_INT_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_LONG_ARRAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * - **Live content, not capacity.** A column's backing array follows what it holds: an empty one owns nothing at
 *   all, and the figure moves as keys are inserted and removed. This rule is the inverse of the one this class
 *   pinned until the columns were given a logical `capacity()` over a content-sized backing — the whole point of
 *   that work was that a leaf holding four values stops paying for a 256-slot block. Growth doubles, so the figure
 *   tracks content in steps rather than exactly.
 * - **Shared objects are not charged to their holders.** {@link LongValueColumn}'s codec is a JVM-wide enum constant,
 *   and every empty column parks on shared empty arrays — both cost only their reference slot.
 * - **Elements are the caller's policy, not the column's.** {@link BoxedObjectColumn} charges reference slots alone by
 *   default; the sizer overload adds the referenced objects. Nothing hard-codes which elements are shared.
 *
 * @author Claude (B+ tree column heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("B+ tree column heap-size reporting")
class ColumnHeapSizeTest {
	/**
	 * Leaf block size used throughout — large enough that the gap between a full block and a sparse one is visible,
	 * small enough to stay readable.
	 */
	private static final int BLOCK_SIZE = 64;

	/**
	 * How many keys the "populated" fixtures hold. Deliberately not a power of two, so the backing array carries real
	 * growth slack (32 slots for 30 keys) and the arithmetic has to price the allocation rather than the live count.
	 */
	private static final int POPULATED_ENTRIES = 30;

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

	/**
	 * Builds a deterministic ascending {@link DateTimeRange} for the given ordinal, whose two bounds carry a varying
	 * zone offset so the column's `meta` array is populated rather than uniform. This class measures **size**, never
	 * content: an offset lost or misaligned by a lockstep failure weighs the same and is invisible here, and is
	 * pinned by the offset-level assertions in {@code RangeValueColumnTest} instead.
	 *
	 * @param ordinal the ordinal to derive the range from
	 * @return an ascending, deterministic date-time range
	 */
	@Nonnull
	private static DateTimeRange dateTimeRange(int ordinal) {
		final ZoneOffset offset = ZoneOffset.ofTotalSeconds((ordinal % 5 - 2) * 1800);
		final LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0).plusDays(ordinal);
		return DateTimeRange.between(from.atOffset(offset), from.plusDays(1).atOffset(offset));
	}

	@Nested
	@DisplayName("matches the measured heap for every implementation")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForIntValueColumn() {
			final ValueColumn<Integer> column = new IntValueColumn<>(BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, i);
			}
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForLongValueColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> column = new LongValueColumn<>(codec, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, i);
			}
			// the codec is excluded from the measurement because it is a shared enum constant, not this column's
			assertEquals(JolHeapSize.ownedSize(column, codec), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForInstantKeyedLongValueColumn() {
			// a temporal key is priced as the single `long` it now is - one array, one header. The measurement is
			// kept as its own case rather than folded into the integral one above because the temporal shape is the
			// one that used to carry a second (nanos) array, and this is what pins that it no longer does
			final LongKeyCodec codec = LongKeyCodec.forType(Instant.class);
			final ValueColumn<Instant> column = new LongValueColumn<>(codec, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, Instant.ofEpochMilli(i));
			}
			// the codec is excluded from the measurement because it is a shared enum constant, not this column's
			assertEquals(JolHeapSize.ownedSize(column, codec), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForDateTimeRangeValueColumn() {
			// the three-array shape: `from`, `to` and the `meta` word carrying both bounds' zone offsets. The `kind`
			// enum constant is excluded because it is a shared JVM-wide constant, not this column's storage
			final ValueColumn<DateTimeRange> column =
				new RangeValueColumn<>(RangeKind.DATE_TIME, 0, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, dateTimeRange(i));
			}
			assertEquals(JolHeapSize.ownedSize(column, RangeKind.DATE_TIME), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForNumericRangeValueColumn() {
			// the two-array shape: a numeric range needs no `meta`, so that field stays on the shared empty array
			// and must not be charged - which is what makes this column 16 B per key against the date-time kind's 24
			final ValueColumn<NumberRange<Integer>> column =
				new RangeValueColumn<>(RangeKind.INTEGER_NUMBER, 0, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, IntegerNumberRange.between(i, i + 1));
			}
			assertEquals(
				JolHeapSize.ownedSize(column, RangeKind.INTEGER_NUMBER, EMPTY_LONG_ARRAY),
				column.getHeapSizeInBytes()
			);
			// and the missing third array is worth exactly what it costs: the same key count in the date-time shape
			// carries one more `long` per slot
			final ValueColumn<DateTimeRange> dateTimeShape =
				new RangeValueColumn<>(RangeKind.DATE_TIME, 0, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				dateTimeShape.insertKeyAt(i, dateTimeRange(i));
			}
			assertTrue(
				column.getHeapSizeInBytes() < dateTimeShape.getHeapSizeInBytes(),
				"a numeric range column must be cheaper than a date-time one of the same length"
			);
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
			final RecordColumn column = new IntRecordColumn(BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertAt(i, i + 1L);
			}
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForLongRecordColumn() {
			final RecordColumn column = new LongRecordColumn(BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertAt(i, i + 1L);
			}
			assertEquals(JolHeapSize.ownedSize(column), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForOverflowColumn() {
			// this is the one column that owns even its empty backing array, and its constructor justifies that by
			// the figure matching a JOL walk exactly - so the claim is measured here rather than only transitively
			// through the leaf walk, where a divergence would report as a leaf fault instead of as this column's
			final OverflowColumn empty = new OverflowColumn(BLOCK_SIZE);
			assertEquals(JolHeapSize.ownedSize(empty), empty.getHeapSizeInBytes());

			final TransactionalBitmap[] bitmaps = new TransactionalBitmap[POPULATED_ENTRIES];
			final OverflowColumn column = new OverflowColumn(BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				bitmaps[i] = new TransactionalBitmap(i, i + 1);
				column.insertAt(i, bitmaps[i]);
			}
			// the bitmaps are excluded from the measurement because the leaf charges them one by one; billing them
			// here as well would count every multi-record bucket twice
			assertEquals(JolHeapSize.ownedSize(column, (Object[]) bitmaps), column.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForATrimmedColumn() {
			// the commit merge's trim rebuilds the backing array at the floor of four slots; the arithmetic has to
			// follow the shrunk allocation, not the capacity the column still reports
			final ValueColumn<Integer> column = new IntValueColumn<>(BLOCK_SIZE);
			for (int i = 0; i < BLOCK_SIZE; i++) {
				column.insertKeyAt(i, i);
			}
			column.fillEmpty(1, BLOCK_SIZE);
			final ValueColumn<Integer> trimmed = column.trimmed();
			assertEquals(JolHeapSize.ownedSize(trimmed), trimmed.getHeapSizeInBytes());
			assertTrue(trimmed.getHeapSizeInBytes() < column.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("excludes structure it does not own")
	class Ownership {

		@Test
		void shouldNotChargeTheSharedCodecToTheLongColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> column = new LongValueColumn<>(codec, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				column.insertKeyAt(i, i);
			}

			// the codec really is reachable from the column, so a naive deep walk would bill it here...
			final long naiveDeepWalk = JolHeapSize.ownedSize(column);
			assertTrue(naiveDeepWalk > column.getHeapSizeInBytes());

			// ...and every column of this key type would be billed for the same one enum constant
			final ValueColumn<Integer> sibling = new LongValueColumn<>(codec, BLOCK_SIZE);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				sibling.insertKeyAt(i, i);
			}
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
		void shouldNotChargeTheSharedEmptyArraysToAnyFreshPrimitiveColumn() {
			// the same rule, now across the whole family: since the backing arrays follow the live content, EVERY
			// empty column parks on the shared constants and owns nothing at all. A created-and-never-written tree
			// is the dominant shape in a reduced index, so this is where most of the reclaimed bytes come from
			assertEquals(
				JolHeapSize.ownedSize(new IntValueColumn<Integer>(BLOCK_SIZE), EMPTY_INT_ARRAY),
				new IntValueColumn<Integer>(BLOCK_SIZE).getHeapSizeInBytes()
			);
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			assertEquals(
				JolHeapSize.ownedSize(new LongValueColumn<Integer>(codec, BLOCK_SIZE), codec, EMPTY_LONG_ARRAY),
				new LongValueColumn<Integer>(codec, BLOCK_SIZE).getHeapSizeInBytes()
			);
			final LongKeyCodec instantCodec = LongKeyCodec.forType(Instant.class);
			assertEquals(
				JolHeapSize.ownedSize(
					new LongValueColumn<Instant>(instantCodec, BLOCK_SIZE), instantCodec, EMPTY_LONG_ARRAY),
				new LongValueColumn<Instant>(instantCodec, BLOCK_SIZE).getHeapSizeInBytes()
			);
			assertEquals(
				JolHeapSize.ownedSize(
					new RangeValueColumn<DateTimeRange>(RangeKind.DATE_TIME, 0, BLOCK_SIZE),
					RangeKind.DATE_TIME, EMPTY_LONG_ARRAY
				),
				new RangeValueColumn<DateTimeRange>(RangeKind.DATE_TIME, 0, BLOCK_SIZE).getHeapSizeInBytes()
			);
			assertEquals(
				JolHeapSize.ownedSize(
					new RangeValueColumn<NumberRange<Integer>>(RangeKind.INTEGER_NUMBER, 0, BLOCK_SIZE),
					RangeKind.INTEGER_NUMBER, EMPTY_LONG_ARRAY
				),
				new RangeValueColumn<NumberRange<Integer>>(
					RangeKind.INTEGER_NUMBER, 0, BLOCK_SIZE).getHeapSizeInBytes()
			);
			assertEquals(
				JolHeapSize.ownedSize(new IntRecordColumn(BLOCK_SIZE), EMPTY_INT_ARRAY),
				new IntRecordColumn(BLOCK_SIZE).getHeapSizeInBytes()
			);
			assertEquals(
				JolHeapSize.ownedSize(new LongRecordColumn(BLOCK_SIZE), EMPTY_LONG_ARRAY),
				new LongRecordColumn(BLOCK_SIZE).getHeapSizeInBytes()
			);
		}

		@Test
		void shouldChargeTheBoxedColumnsOwnEmptyArray() {
			// the boxed column is the one that does NOT park on a shared constant: `asBoxedArray` hands its backing
			// array out as an `M[]` and the caller checkcasts it to the erased element type, which an `Object[]`
			// fails. It therefore allocates a zero-length array of its real component type and pays the sixteen
			// bytes - which also keeps the arithmetic and a JOL walk in agreement without a sixth shared constant
			final ValueColumn<UUID> column = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			assertEquals(JolHeapSize.ownedSize(column, UUID.class), column.getHeapSizeInBytes());
		}

		@Test
		void shouldNotDoubleChargeAnEmptyBackingArrayAcrossADuplicate() {
			// the boxed column charges its backing array unconditionally, because it cannot use the shared-array
			// exclusion the primitive columns use - `asBoxedArray` has to hand out the erased component type. Its
			// duplicate therefore has to OWN the array it is charged for, even when that array is empty
			final ValueColumn<UUID> column = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			final ValueColumn<UUID> copy = column.duplicate();
			assertNotSame(
				column.asBoxedArray(), copy.asBoxedArray(),
				"an empty duplicate must own its array, or the pair reports one array twice"
			);

			final long reported = column.getHeapSizeInBytes() + copy.getHeapSizeInBytes();
			final long measured = JolHeapSize.ownedSize(column, UUID.class)
				+ JolHeapSize.ownedSize(copy, UUID.class);
			assertEquals(
				measured, reported,
				"the pair must report exactly the bytes a walk of both finds them owning"
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

			// the sparse column owns four reference slots against the full one's sixty-four, so even the
			// element-free figure separates them now
			assertTrue(full.getHeapSizeInBytes() > sparse.getHeapSizeInBytes());
			assertTrue(
				full.getHeapSizeInBytes(JolHeapSize::shallowSize)
					> sparse.getHeapSizeInBytes(JolHeapSize::shallowSize),
				"only the live slots may contribute element bytes"
			);
		}

		@Test
		void shouldNotAskTheSizerToPriceAClearedSlot() {
			// a cleared tail addresses nothing, so the sizer must not be asked to price it - a sizer that threw on
			// null would otherwise blow up on every leaf that has ever shed a key
			final ValueColumn<UUID> column = new BoxedObjectColumn<>(UUID.class, BLOCK_SIZE);
			for (int i = 0; i < 5; i++) {
				column.insertKeyAt(i, uuid(i));
			}
			// one live key over a backing array of eight, so seven slots must be null and must contribute nothing
			column.fillEmpty(1, BLOCK_SIZE);
			assertEquals(
				column.getHeapSizeInBytes() + JolHeapSize.shallowSize(uuid(0)),
				column.getHeapSizeInBytes(JolHeapSize::shallowSize)
			);
		}

		@Test
		void shouldIgnoreTheSizerInPrimitiveColumns() {
			final ValueColumn<Integer> column = new IntValueColumn<>(BLOCK_SIZE);
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
	@DisplayName("prices live content rather than capacity")
	class ContentTracking {

		@Test
		void shouldChargeLiveContentRatherThanTheWholeBlock() {
			final ValueColumn<Integer> empty = new IntValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Integer> full = new IntValueColumn<>(BLOCK_SIZE);
			for (int i = 0; i < BLOCK_SIZE; i++) {
				full.insertKeyAt(i, i);
			}

			// the footprint moves with the content: an empty block owns no storage at all, a full one owns all of it
			assertTrue(empty.getHeapSizeInBytes() < full.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(empty, EMPTY_INT_ARRAY), empty.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(full), full.getHeapSizeInBytes());
		}

		@Test
		void shouldScaleWithEntryCountNotBlockSize() {
			// same content, four times the logical block size: the figure must not move, because nothing was
			// allocated for the slack
			final ValueColumn<Integer> small = new IntValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Integer> large = new IntValueColumn<>(BLOCK_SIZE * 4);
			for (int i = 0; i < POPULATED_ENTRIES; i++) {
				small.insertKeyAt(i, i);
				large.insertKeyAt(i, i);
			}

			assertEquals(small.getHeapSizeInBytes(), large.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(large), large.getHeapSizeInBytes());

			// ...while the same block size holding four times the content does move it
			final ValueColumn<Integer> denser = new IntValueColumn<>(BLOCK_SIZE);
			for (int i = 0; i < BLOCK_SIZE; i++) {
				denser.insertKeyAt(i, i);
			}
			assertTrue(denser.getHeapSizeInBytes() > small.getHeapSizeInBytes());
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
