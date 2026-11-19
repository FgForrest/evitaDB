package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PersistentRoaringBitmap#decodeInto(int[])}: it writes the values in the unsigned order of
 * {@link PersistentRoaringBitmap#toArray()} - values that are negative as signed ints last - into the prefix of the
 * caller's array, leaves the rest of the array as it was, returns the cardinality, and refuses an array too short to
 * hold the values.
 */
@DisplayName("Bitmap decoding into a caller's array")
public class DecodeIntoTest {

	/**
	 * Marks the slots of the target that the decode must not touch.
	 */
	private static final int UNTOUCHED = 0x5EED_5EED;

	/**
	 * Builds a bitmap with an array chunk, a bitmap chunk, a run chunk and a chunk of values that are negative as
	 * signed ints.
	 */
	private static PersistentRoaringBitmap mixedBitmap(final Random random) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		// chunk 0: sparse, an array container
		for (int i = 0; i < 100; i++) {
			bitmap.add(random.nextInt(1 << 16));
		}
		// chunk 1: dense, a bitmap container
		for (int i = 0; i < 20_000; i++) {
			bitmap.add((1 << 16) + random.nextInt(1 << 16));
		}
		// chunk 2: one long run, a run container after runOptimize
		bitmap.add((2L << 16) + 100, (2L << 16) + 9_000);
		// the top chunk: negative as signed ints, last in unsigned order
		for (int i = 0; i < 50; i++) {
			bitmap.add(0xFFFF0000 + random.nextInt(1 << 16));
		}
		bitmap.runOptimize();
		return bitmap;
	}

	/**
	 * Returns the bitmap's values as its iterator walks them - unsigned order - independently of the fill loop
	 * {@link PersistentRoaringBitmap#toArray()} shares with the decode.
	 */
	private static int[] iteratedValues(final PersistentRoaringBitmap bitmap) {
		final int[] values = new int[bitmap.getCardinality()];
		final PeekableIntIterator it = bitmap.getIntIterator();
		int i = 0;
		while (it.hasNext()) {
			values[i++] = it.next();
		}
		return values;
	}

	@Test
	@DisplayName("Writes the values of every container kind in unsigned order and returns their count")
	void shouldDecodeEveryContainerKindInUnsignedOrder() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(1));
		final int cardinality = bitmap.getCardinality();
		final int[] target = new int[cardinality];

		assertEquals(cardinality, bitmap.decodeInto(target));
		assertArrayEquals(iteratedValues(bitmap), target);
		// unsigned order: the values that are negative as signed ints come last
		assertTrue(target[0] >= 0);
		assertTrue(target[cardinality - 1] < 0);
		for (int i = 1; i < cardinality; i++) {
			assertTrue(Integer.compareUnsigned(target[i - 1], target[i]) < 0, "ascending unsigned at " + i);
		}
	}

	@Test
	@DisplayName("Fills only the prefix of a longer array and leaves its tail untouched")
	void shouldLeaveTheTailOfALongerArrayUntouched() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(2));
		final int cardinality = bitmap.getCardinality();
		final int[] target = new int[cardinality + 1_000];
		Arrays.fill(target, UNTOUCHED);

		assertEquals(cardinality, bitmap.decodeInto(target));
		assertArrayEquals(iteratedValues(bitmap), Arrays.copyOf(target, cardinality));
		for (int i = cardinality; i < target.length; i++) {
			assertEquals(UNTOUCHED, target[i], "slot " + i + " lies past the values");
		}
	}

	@Test
	@DisplayName("Overwrites what an earlier, longer decode left in the reused array")
	void shouldDecodeAShorterBitmapOverALongerOne() {
		final int[] target = new int[30_000];
		final PersistentRoaringBitmap longer = mixedBitmap(new Random(3));
		final PersistentRoaringBitmap shorter = PersistentRoaringBitmap.bitmapOf(7, 70_000, -1);
		longer.decodeInto(target);
		final int stale = target[3];

		assertEquals(3, shorter.decodeInto(target));
		assertArrayEquals(new int[]{7, 70_000, -1}, Arrays.copyOf(target, 3));
		// the slot right after the shorter bitmap's values still holds the longer decode
		assertEquals(stale, target[3]);
	}

	@Test
	@DisplayName("Writes nothing for an empty bitmap")
	void shouldWriteNothingForAnEmptyBitmap() {
		final int[] target = {UNTOUCHED};

		assertEquals(0, new PersistentRoaringBitmap().decodeInto(target));
		assertEquals(0, new PersistentRoaringBitmap().decodeInto(new int[0]));
		assertEquals(UNTOUCHED, target[0]);
	}

	@Test
	@DisplayName("Refuses an array shorter than the cardinality")
	void shouldRefuseAnArrayShorterThanTheCardinality() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(4));

		assertThrows(
			ArrayIndexOutOfBoundsException.class,
			() -> bitmap.decodeInto(new int[bitmap.getCardinality() - 1])
		);
	}

}
