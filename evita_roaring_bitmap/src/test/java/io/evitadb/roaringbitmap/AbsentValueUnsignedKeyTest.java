package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link PersistentRoaringBitmap#nextAbsentValue(int)} and
 * {@link PersistentRoaringBitmap#previousAbsentValue(int)} for bitmaps holding values from 2^31 on.
 * The container key of such values is 32,768 or more, so shifting it left by 16 yields a negative
 * `int`; a signed comparison against it picks the wrong container for every probe below 2^31.
 * A `java.util.BitSet` model cannot reach these values, so the model here is a plain `contains`
 * scan over the unsigned universe.
 */
public class AbsentValueUnsignedKeyTest {

	private static final long UNIVERSE_END = 1L << 32;
	private static final long[] PROBES = {
		0L, 5L, 6L, 65_535L, 65_536L,
		(1L << 31) - 65_537L, (1L << 31) - 1L, 1L << 31,
		(1L << 31) + 5L, (1L << 31) + 6L, (1L << 31) + 65_536L,
		UNIVERSE_END - 65_537L, UNIVERSE_END - 1L
	};

	/**
	 * Every bitmap whose values or blocks lie at or above the signed boundary agrees with the scan.
	 */
	@Test
	public void absentValuesMatchContainsScan() {
		final PersistentRoaringBitmap[] bitmaps = {
			PersistentRoaringBitmap.bitmapOf((int) 2_147_483_648L),
			PersistentRoaringBitmap.bitmapOf((int) 2_147_483_653L),
			PersistentRoaringBitmap.bitmapOf(5, (int) 2_147_483_653L),
			PersistentRoaringBitmap.bitmapOfRange(2_147_418_112L, 2_147_549_184L),
			PersistentRoaringBitmap.bitmapOfRange(4_294_901_760L, UNIVERSE_END)
		};
		for (final PersistentRoaringBitmap bitmap : bitmaps) {
			for (final long probe : PROBES) {
				assertEquals(
					expectedNext(bitmap, probe), bitmap.nextAbsentValue((int) probe),
					"nextAbsentValue(" + probe + ") of " + bitmap
				);
				assertEquals(
					expectedPrevious(bitmap, probe), bitmap.previousAbsentValue((int) probe),
					"previousAbsentValue(" + probe + ") of " + bitmap
				);
			}
		}
	}

	/**
	 * The "no absent value" answer `-1` must not trip the result assertion, whose `(int) -1` is the
	 * stored value 4,294,967,295.
	 */
	@Test
	public void previousAbsentValueReturnsMinusOneWhenNoneExists() {
		assertEquals(-1L, PersistentRoaringBitmap.bitmapOf(0, -1).previousAbsentValue(0));
	}

	private static long expectedNext(PersistentRoaringBitmap bitmap, long from) {
		for (long value = from; value < UNIVERSE_END; value++) {
			if (!bitmap.contains((int) value)) {
				return value;
			}
		}
		return -1L;
	}

	private static long expectedPrevious(PersistentRoaringBitmap bitmap, long from) {
		for (long value = from; value >= 0; value--) {
			if (!bitmap.contains((int) value)) {
				return value;
			}
		}
		return -1L;
	}
}
