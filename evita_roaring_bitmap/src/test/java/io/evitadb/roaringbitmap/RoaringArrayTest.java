package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link RoaringArray}, the ordered key/container store backing
 * {@link PersistentRoaringBitmap}. They cover bulk append of another store, backing-array
 * capacity growth, and the structural, clone and serialization invariants that must survive
 * local copy-on-write / delta optimizations.
 */
@DisplayName("RoaringArray")
public class RoaringArrayTest {

	@Nested
	@DisplayName("Bulk append of another store")
	class AppendOperations {

		@Test
		@DisplayName("Appending an empty store leaves size and capacity unchanged")
		public void whenAppendEmpty_ShouldBeUnchanged() {
			RoaringArray array = new RoaringArray();
			array.keys = new char[2];
			array.values = new Container[2];
			array.size = 1;

			RoaringArray appendage = new RoaringArray();
			appendage.size = 0;
			appendage.keys = new char[4];
			appendage.values = new Container[4];

			array.append(appendage);
			assertEquals(1, array.size);
			assertEquals(2, array.keys.length);
		}

		@Test
		@DisplayName("Appending to an empty store adopts the appendage size")
		public void whenAppendToEmpty_ShouldEqualAppendage() {
			RoaringArray array = new RoaringArray();
			array.size = 0;
			array.keys = new char[4];
			array.values = new Container[4];

			RoaringArray appendage = new RoaringArray();
			appendage.size = 3;
			appendage.keys = new char[4];
			appendage.values = new Container[4];

			array.append(appendage);

			assertEquals(3, array.size);
			assertEquals(4, array.keys.length);
		}

		@Test
		@DisplayName("Appending two non-empty stores sums their sizes")
		public void whenAppendNonEmpty_SizeShouldEqualSumOfSizes() {
			RoaringArray array = new RoaringArray();
			array.size = 2;
			array.keys = new char[]{0, 2, 0, 0};
			array.values = new Container[4];

			RoaringArray appendage = new RoaringArray();
			appendage.size = 3;
			appendage.keys = new char[]{5, 6, 7, 0};
			appendage.values = new Container[4];

			array.append(appendage);

			assertEquals(5, array.size);
		}

		@Test
		@DisplayName("Appending non-empty stores keeps keys monotonically ascending")
		public void whenAppendNonEmpty_ResultantKeysShouldBeMonotonic() {
			RoaringArray array = new RoaringArray();
			array.size = 2;
			array.keys = new char[]{0, 2, 0, 0};
			array.values = new Container[4];

			RoaringArray appendage = new RoaringArray();
			appendage.size = 3;
			appendage.keys = new char[]{5, 6, 7, 0};
			appendage.values = new Container[4];

			array.append(appendage);

			assertArrayEquals(new char[]{0, 2, 5, 6, 7}, array.keys);
		}
	}

	@Nested
	@DisplayName("Backing array capacity")
	class BackingArrayCapacity {

		@Test
		@DisplayName("extendArray does not reallocate when capacity already suffices")
		public void resizeOnlyIfNecessary() {
			char[] keys = new char[1];
			int size = 0;
			Container[] values = new Container[1];
			RoaringArray array = new RoaringArray(keys, values, size);
			array.extendArray(1);
			assertSame(keys, array.keys, "Keys were not reallocated");
		}
	}

	/**
	 * Regression guards for the structural, clone and serialization operations that back
	 * {@link PersistentRoaringBitmap}. These lock in the behaviour inherited from upstream Roaring
	 * so that later local modifications (e.g. copy-on-write / delta optimizations) cannot silently
	 * shift a shift/resize boundary, break unsigned key ordering, or corrupt the little-endian
	 * serialization round-trip without a test turning red.
	 */
	@Nested
	@DisplayName("Structural, clone and serialization invariants")
	class StructuralInvariants {

		/**
		 * Builds a single-value container (cardinality 1) holding the low-16 value {@code v}.
		 */
		private Container container(final int v) {
			return new ArrayContainer(v, v + 1);
		}

		/**
		 * Builds a store whose entries carry the given ascending keys, each with a 1-bit container.
		 */
		private RoaringArray arrayOf(final int... keys) {
			final RoaringArray array = new RoaringArray(Math.max(keys.length, 1));
			for (final int key : keys) {
				array.append((char) key, container(key));
			}
			return array;
		}

		@Test
		@DisplayName("insertNewKeyValueAt shifts the tail right and keeps keys ascending")
		void shouldPreserveAscendingOrderWhenInsertingInTheMiddle() {
			final RoaringArray array = arrayOf(1, 3, 5);

			array.insertNewKeyValueAt(1, (char) 2, container(2));

			assertEquals(4, array.size);
			assertArrayKeys(array, 1, 2, 3, 5);
			assertTrue(array.validate(), "keys must stay strictly ascending");
		}

		@Test
		@DisplayName("removeAtIndex shifts the tail left and clears the vacated last slot")
		void shouldClearVacatedSlotWhenRemovingAtIndex() {
			final RoaringArray array = arrayOf(1, 2, 3);

			array.removeAtIndex(1);

			assertEquals(2, array.size);
			assertEquals(1, (int) array.getKeyAtIndex(0));
			assertEquals(3, (int) array.getKeyAtIndex(1));
			// the freed trailing slot must not retain a stale reference or key
			assertEquals(0, (int) array.keys[2]);
			assertNull(array.values[2]);
			assertTrue(array.validate());
		}

		@Test
		@DisplayName("removeIndexRange removes [begin, end) and clears every vacated trailing slot")
		void shouldRemoveHalfOpenRangeAndClearTail() {
			final RoaringArray array = arrayOf(1, 2, 3, 4, 5);

			array.removeIndexRange(1, 4);

			assertEquals(2, array.size);
			assertEquals(1, (int) array.getKeyAtIndex(0));
			assertEquals(5, (int) array.getKeyAtIndex(1));
			assertNull(array.values[2]);
			assertNull(array.values[3]);
			assertNull(array.values[4]);
			assertTrue(array.validate());
		}

		@Test
		@DisplayName("removeIndexRange is a no-op when end is not greater than begin")
		void shouldDoNothingWhenRemoveRangeIsEmpty() {
			final RoaringArray array = arrayOf(1, 2, 3);

			array.removeIndexRange(2, 2);
			array.removeIndexRange(3, 1);

			assertEquals(3, array.size);
			assertArrayKeys(array, 1, 2, 3);
		}

		@Test
		@DisplayName("copyRange shifts the [begin, end) block down to newBegin without touching size")
		void shouldShiftBlockDownWhenCopyingRange() {
			final RoaringArray array = arrayOf(10, 20, 30, 40);

			// move entries at indices 2..3 down to start at index 1, overwriting 20 and 30
			array.copyRange(2, 4, 1);

			// copyRange is a raw structural move; it does not adjust size
			assertEquals(4, array.size);
			assertEquals(10, (int) array.keys[0]);
			assertEquals(30, (int) array.keys[1]);
			assertEquals(40, (int) array.keys[2]);
			// the source tail slot is left as-is by copyRange
			assertEquals(40, (int) array.keys[3]);
		}

		@Test
		@DisplayName("appendCopy deep-clones the source container instead of sharing the reference")
		void shouldDeepCloneContainerWhenAppendingCopy() {
			final RoaringArray source = arrayOf(7);
			final RoaringArray target = new RoaringArray(2);

			target.appendCopy(source, 0);

			assertEquals(1, target.size);
			assertEquals(7, (int) target.getKeyAtIndex(0));
			assertNotSame(
				source.getContainerAtIndex(0), target.getContainerAtIndex(0),
				"appendCopy must clone the container, not alias it"
			);
			assertEquals(source.getContainerAtIndex(0), target.getContainerAtIndex(0));
		}

		@Test
		@DisplayName("getIndex / getContainerIndex resolve keys unsigned across the 0x8000 boundary")
		void shouldTreatKeysAsUnsignedWhenLookingUp() {
			final RoaringArray array = arrayOf(0x0001, 0x7FFF, 0x8000, 0xFFFF);

			assertEquals(0, array.getContainerIndex((char) 0x0001));
			assertEquals(2, array.getContainerIndex((char) 0x8000));
			assertEquals(3, array.getContainerIndex((char) 0xFFFF));
			// an absent key returns a negative insertion point, never a wrong positive index
			assertTrue(array.getContainerIndex((char) 0x4000) < 0);

			// getIndex fast-paths the terminal key and binary-searches the rest, both unsigned
			assertEquals(3, array.getIndex((char) 0xFFFF));
			assertEquals(2, array.getIndex((char) 0x8000));
		}

		@Test
		@DisplayName("advanceUntil finds the next key >= x unsigned across the sign boundary")
		void shouldAdvanceUnsignedAcrossSignBoundary() {
			final RoaringArray array = arrayOf(0x0001, 0x7FFF, 0x8000, 0xFFFF);

			// from before the start, the first key >= 0x8000 sits at index 2 (unsigned, not signed)
			assertEquals(2, array.advanceUntil((char) 0x8000, -1));
			assertEquals(3, array.advanceUntil((char) 0xFFFF, 0));
			// nothing beyond the last slot: advanceUntil returns size
			assertEquals(array.size, array.advanceUntil((char) 0xFFFF, 3));
		}

		@Test
		@DisplayName("clone deep-copies the backing arrays and every container")
		void shouldReturnIndependentDeepCopyWhenCloning() throws CloneNotSupportedException {
			final RoaringArray original = arrayOf(1, 2);

			final RoaringArray copy = original.clone();

			assertEquals(original.size, copy.size);
			assertEquals(original, copy);
			assertNotSame(original.keys, copy.keys, "keys array must be copied");
			assertNotSame(original.values, copy.values, "values array must be copied");
			for (int i = 0; i < original.size; i++) {
				assertNotSame(
					original.getContainerAtIndex(i), copy.getContainerAtIndex(i),
					"clone must not share container references"
				);
				assertEquals(original.getContainerAtIndex(i), copy.getContainerAtIndex(i));
			}
		}

		@Test
		@DisplayName("serialize -> deserialize round-trips the little-endian format with unsigned keys")
		void shouldRoundTripThroughLittleEndianSerialization() throws IOException {
			final RoaringArray original = arrayOf(0x0001, 0x8000, 0xFFFF);

			final ByteArrayOutputStream bos = new ByteArrayOutputStream(original.serializedSizeInBytes());
			try (DataOutputStream out = new DataOutputStream(bos)) {
				original.serialize(out);
			}
			final byte[] bytes = bos.toByteArray();
			assertEquals(original.serializedSizeInBytes(), bytes.length);

			final RoaringArray restored = new RoaringArray();
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
				restored.deserialize(in);
			}

			assertEquals(original.size, restored.size);
			assertEquals(original, restored);
			assertTrue(restored.validate());
			// the high-bit key must survive the little-endian, unsigned encoding intact
			assertEquals(0x8000, (int) restored.getKeyAtIndex(1));
		}

		@Test
		@DisplayName("equals compares two normally-constructed empty stores without throwing")
		void shouldEqualTwoEmptyStoresWithoutNullPointer() {
			// empty stores keep non-null backing arrays, so the ranged Arrays.equals stays null-safe
			assertEquals(new RoaringArray(), new RoaringArray());
		}

		/**
		 * Asserts the live key prefix of {@code array} equals the given unsigned keys, in order.
		 */
		private void assertArrayKeys(final RoaringArray array, final int... expected) {
			final char[] actual = new char[expected.length];
			final char[] want = new char[expected.length];
			for (int i = 0; i < expected.length; i++) {
				actual[i] = array.getKeyAtIndex(i);
				want[i] = (char) expected[i];
			}
			assertArrayEquals(want, actual);
		}
	}
}
