package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins range containment at missing chunks and the unsigned universe's exclusive upper bound,
 * with a seeded value-by-value model covering every container shape.
 */
public class ContainsRangeTest {

	private static final int CHUNK_SIZE = 1 << 16;
	private static final long UNIVERSE_END = 1L << 32;
	private static final int[] KEYS = {0, 1, 2, 3, 0xFFFC, 0xFFFD, 0xFFFE, 0xFFFF};

	/**
	 * A missing partial tail beyond the final stored chunk must make containment false.
	 */
	@Test
	public void missingTailChunkAtEndIsNotContained() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 65536);

		assertTrue(bitmap.validate());
		assertFalse(bitmap.contains(0, 65537), "the required tail chunk with key 1 is absent");
	}

	/**
	 * The exclusive bound 2^32 must require the intervening chunks instead of wrapping to key zero.
	 */
	@Test
	public void firstChunkDoesNotContainRangeThroughUniverseEnd() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 65536);

		assertTrue(bitmap.validate());
		assertFalse(bitmap.contains(0, UNIVERSE_END), "one full chunk cannot contain the unsigned universe");
	}

	/**
	 * An insertion point pointing at key 3 cannot satisfy the partial tail required from key 1.
	 */
	@Test
	public void laterChunkCannotStandInForMissingTailChunk() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 65536);
		bitmap.add(196608);

		assertTrue(bitmap.validate());
		assertFalse(bitmap.contains(0, 65537), "key 3 must not substitute for the missing tail at key 1");
	}

	/**
	 * The last unsigned value forms a valid single-value range ending at 2^32.
	 */
	@Test
	public void maximumUnsignedValueIsContainedAtUniverseEnd() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(-1);

		assertTrue(bitmap.validate());
		assertTrue(bitmap.contains(0xFFFFFFFFL, UNIVERSE_END), "the maximum unsigned value is present");
	}

	/**
	 * A range ending at 2^32 requires every value through the end of the final chunk.
	 */
	@Test
	public void fullFinalChunkIsRequiredAtUniverseEnd() {
		final long minimum = UNIVERSE_END - 2L * CHUNK_SIZE;
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(minimum, UNIVERSE_END);

		assertTrue(bitmap.validate());
		assertTrue(bitmap.contains(minimum, UNIVERSE_END), "both final chunks are complete");
		bitmap.remove(-1);
		assertFalse(bitmap.contains(minimum, UNIVERSE_END), "the final chunk is missing its last value");
	}

	/**
	 * Empty and reversed ranges retain their existing false result, including at unsigned endpoints.
	 */
	@Test
	public void emptyAndReversedRangesRemainFalse() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(0, 65536, -1);

		for (final long value : new long[]{0, 1, 65536, 0xFFFFFFFFL}) {
			assertFalse(bitmap.contains(value, value), "empty range at " + value);
		}
		assertFalse(bitmap.contains(65537, 0), "reversed range");
		assertFalse(bitmap.contains(0xFFFFFFFFL, 65536), "reversed range from the last chunk");
	}

	/**
	 * Invalid endpoints still fail the range sanity check before empty or reversed ranges return false.
	 */
	@Test
	public void outOfRangeArgumentsStillThrowBeforeCheckingOrder() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final long[][] ranges = {
			{-1, 0}, {UNIVERSE_END, UNIVERSE_END}, {UNIVERSE_END + 1, 0}, {Long.MIN_VALUE, 0},
			{Long.MAX_VALUE, 0}, {0, -1}, {0, UNIVERSE_END + 1}, {0, Long.MIN_VALUE}, {0, Long.MAX_VALUE}
		};
		for (final long[] range : ranges) {
			final boolean invalidStart = range[0] < 0 || range[0] >= UNIVERSE_END;
			final String expected = invalidStart
				? "rangeStart=" + range[0] + " should be in [0, 0xffffffff]"
				: "rangeEnd=" + range[1] + " should be in [0, 0xffffffff + 1]";
			final IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class, () -> bitmap.contains(range[0], range[1]), Arrays.toString(range));
			assertEquals(expected, exception.getMessage(), Arrays.toString(range));
		}
	}

	/**
	 * Intersection already checks actual chunk keys and keeps 2^32 as an unwrapped bound; these cases
	 * distinguish its correct behavior from the containment defects.
	 */
	@Test
	public void intersectsUsesActualKeysAndUnsignedUniverseEnd() {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 65536);

		assertFalse(bitmap.intersects(65536, 65537), "the tail chunk is absent");
		assertFalse(bitmap.intersects(0xFFFF0000L, UNIVERSE_END), "the final chunk is absent");
		assertTrue(bitmap.intersects(0, UNIVERSE_END), "the first chunk intersects the universe");
		bitmap.add(196608);
		assertFalse(bitmap.intersects(65536, 65537), "key 3 cannot substitute for key 1");
		assertTrue(PersistentRoaringBitmap.bitmapOf(-1).intersects(0xFFFFFFFFL, UNIVERSE_END));
	}

	/**
	 * Seeded fixtures with missing, full, run, array and bitmap chunks must match a plain boolean model
	 * around chunk boundaries, empty ranges, single values and the exclusive bound 2^32. The model
	 * checks values directly and does not depend on rangeCardinality.
	 */
	@Test
	public void randomRangesMatchValueByValueModel() {
		final long seed = 0x1754E12L;
		final Random random = new Random(seed);
		final List<Long> boundaries = new ArrayList<>();
		for (final int key : new int[]{0, 1, 2, 3, 4, 0xFFFC, 0xFFFD, 0xFFFE, 0xFFFF, 0x10000}) {
			for (int delta = -1; delta <= 1; delta++) {
				final long value = (long) key * CHUNK_SIZE + delta;
				if (value >= 0 && value <= UNIVERSE_END) {
					boundaries.add(value);
				}
			}
		}
		for (int iteration = 0; iteration < 64; iteration++) {
			final boolean[][] model = new boolean[KEYS.length][CHUNK_SIZE];
			final StringBuilder inputs = new StringBuilder();
			final PersistentRoaringBitmap bitmap = randomBitmap(random, model, iteration, inputs);
			final String where = "seed=" + seed + ", iteration=" + iteration + ", inputs=" + inputs;
			assertTrue(bitmap.validate(), where);
			for (final long minimum : boundaries) {
				if (minimum == UNIVERSE_END) {
					continue;
				}
				for (final long supremum : boundaries) {
					if (minimum <= supremum) {
						assertRangeMatchesModel(bitmap, model, minimum, supremum, where);
					}
				}
			}
			for (int probe = 0; probe < 64; probe++) {
				final long minimum = (long) KEYS[random.nextInt(KEYS.length)] * CHUNK_SIZE
					+ random.nextInt(CHUNK_SIZE);
				final long supremum = Math.min(UNIVERSE_END, minimum + random.nextInt(2 * CHUNK_SIZE));
				assertRangeMatchesModel(bitmap, model, minimum, supremum, where);
				assertRangeMatchesModel(bitmap, model, minimum, minimum + 1, where);
				assertRangeMatchesModel(bitmap, model, minimum, minimum, where);
			}
		}
	}

	/**
	 * Builds randomized chunks and records their exact input values independently in a boolean model.
	 * The first fixture guarantees that each encoding is exercised even if later random choices drift.
	 */
	private static PersistentRoaringBitmap randomBitmap(
		final Random random, final boolean[][] model, final int iteration, final StringBuilder inputs
	) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int slot = 0; slot < KEYS.length; slot++) {
			final int shape = iteration == 0 ? slot % 5 : random.nextInt(5);
			final long base = (long) KEYS[slot] * CHUNK_SIZE;
			inputs.append(" key=").append(KEYS[slot]);
			if (shape == 0) {
				inputs.append(" absent;");
				continue;
			}
			if (shape == 1 || shape == 2) {
				final int start = shape == 1 ? 0 : random.nextInt(CHUNK_SIZE - 1);
				final int end = shape == 1 ? CHUNK_SIZE : start + 1 + random.nextInt(CHUNK_SIZE - start);
				bitmap.add(base + start, base + end);
				Arrays.fill(model[slot], start, end, true);
				inputs.append(" run=[").append(start).append(',').append(end).append(");");
			} else if (shape == 3) {
				final int[] values = new int[1 + random.nextInt(128)];
				for (int i = 0; i < values.length; i++) {
					values[i] = random.nextInt(CHUNK_SIZE);
					bitmap.add((int) (base + values[i]));
					model[slot][values[i]] = true;
				}
				inputs.append(" array=").append(Arrays.toString(values)).append(';');
			} else {
				final int start = random.nextInt(256);
				final int stride = 2 + random.nextInt(4);
				final int count = 5000 + random.nextInt(5000);
				for (int i = 0; i < count; i++) {
					final int value = start + i * stride;
					bitmap.add((int) (base + value));
					model[slot][value] = true;
				}
				inputs.append(" bitmap start=").append(start).append(" stride=").append(stride)
					.append(" count=").append(count).append(';');
			}
			final Container container = bitmap.getContainerAtIndex(bitmap.highLowContainer.getIndex((char) KEYS[slot]));
			assertTrue(
				shape <= 2 ? container instanceof RunContainer
					: shape == 3 ? container instanceof ArrayContainer : container instanceof BitmapContainer,
				"fixture shape=" + shape + ", inputs=" + inputs);
		}
		return bitmap;
	}

	/**
	 * Checks containment value by value, stopping at the first absent value, including unmodeled keys.
	 * Intersection checks only modeled chunks because every other key is empty. Empty ranges preserve
	 * the API's false result rather than mathematical vacuous containment.
	 */
	private static void assertRangeMatchesModel(
		final PersistentRoaringBitmap bitmap, final boolean[][] model,
		final long minimum, final long supremum, final String where
	) {
		final String context = where + ", minimum=" + minimum + ", supremum=" + supremum;
		boolean intersects = false;
		for (int slot = 0; slot < KEYS.length && !intersects; slot++) {
			final long base = (long) KEYS[slot] * CHUNK_SIZE;
			final long end = Math.min(supremum, base + CHUNK_SIZE);
			for (long value = Math.max(minimum, base); value < end; value++) {
				if (model[slot][(int) (value - base)]) {
					intersects = true;
					break;
				}
			}
		}
		assertEquals(intersects, bitmap.intersects(minimum, supremum), context + ", intersects");
		boolean expected = minimum < supremum;
		for (long value = minimum; value < supremum; value++) {
			final int key = (int) (value >>> 16);
			final int slot = key < 4 ? key : key >= 0xFFFC ? key - 0xFFF8 : -1;
			if (slot < 0 || !model[slot][(int) value & 0xFFFF]) {
				expected = false;
				break;
			}
		}
		assertEquals(expected, bitmap.contains(minimum, supremum), context + ", contains");
	}
}
