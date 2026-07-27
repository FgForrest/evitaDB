package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for `advanceIfNeeded` on the reverse int iterators, with the emphasis on UNSIGNED
 * ordering across the `0x8000` chunk-key boundary.
 *
 * The post-condition after `advanceIfNeeded(maxval)` is: either the iterator is exhausted, or
 * `peekNext()` compares less than or equal to `maxval` as an unsigned 32-bit value. The chunk-skipping
 * loop compares chunk keys via `hs >>> 16`; had it used the arithmetic shift instead, a bitmap holding
 * a value in chunk `0x8000` and a probe in chunk `0x7FFF` would leave the cursor parked ABOVE the
 * bound, because signed comparison ranks `0x8000` below `0x7FFF`. The first test pins exactly that.
 */
@DisplayName("Reverse int iterator advanceIfNeeded unsigned contract")
public class ReverseIntIteratorUnsignedContractTest {

	/**
	 * Chunk keys spanning both halves of the unsigned range, including the two straddling the signed
	 * wrap.
	 */
	private static final int[] CHUNK_KEYS = {0x0000, 0x0001, 0x7FFE, 0x7FFF, 0x8000, 0x8001, 0xFFFF};

	/**
	 * Builds a bitmap holding values in every {@link #CHUNK_KEYS} chunk, cycling the container shape so
	 * array, bitmap and run chunks all sit on both sides of the signed wrap.
	 */
	private static PersistentRoaringBitmap mixedShapeBitmap() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int index = 0; index < CHUNK_KEYS.length; index++) {
			final long base = (long) CHUNK_KEYS[index] << 16;
			if (index % 3 == 0) {
				// sparse -> array chunk
				bitmap.add((int) (base + 5));
				bitmap.add((int) (base + 1000));
				bitmap.add((int) (base + 60000));
			} else if (index % 3 == 1) {
				// dense scatter -> bitmap chunk
				for (int offset = 0; offset < 2 * ArrayContainer.DEFAULT_MAX_SIZE; offset++) {
					bitmap.add((int) (base + offset * 3L));
				}
			} else {
				// contiguous -> run chunk after runOptimize
				bitmap.add(base + 2000L, base + 12000L);
			}
		}
		bitmap.runOptimize();
		return bitmap;
	}

	/**
	 * Asserts the post-condition, and additionally that exhaustion is only ever reported when the
	 * bitmap genuinely holds nothing at or below the bound.
	 */
	private static void assertPostCondition(
		final PeekableIntIterator iterator, final int maxval, final int[] allValuesAscending,
		final String where
	) {
		final boolean somethingQualifies =
			allValuesAscending.length > 0 && Integer.compareUnsigned(allValuesAscending[0], maxval) <= 0;
		if (!iterator.hasNext()) {
			assertFalse(
				somethingQualifies,
				where + ": iterator exhausted although " + Integer.toUnsignedString(allValuesAscending[0])
					+ " is at or below " + Integer.toUnsignedString(maxval));
			return;
		}
		final int peeked = iterator.peekNext();
		assertTrue(
			Integer.compareUnsigned(peeked, maxval) <= 0,
			where + ": peekNext " + Integer.toUnsignedString(peeked) + " exceeds bound "
				+ Integer.toUnsignedString(maxval));
	}

	/**
	 * The discriminating case for unsigned chunk-key comparison: the only value above the bound lives
	 * in chunk `0x8000`, whose key ranks BELOW the probe's chunk `0x7FFF` under signed comparison.
	 */
	@Test
	@DisplayName("A chunk above the signed wrap is skipped for a probe below it")
	public void chunkAboveSignedWrapIsSkipped() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(5);
		bitmap.add(0x80000005);

		final PeekableIntIterator iterator = bitmap.getReverseIntIterator();
		assertEquals(0x80000005, iterator.peekNext(), "reverse iteration must start at the unsigned maximum");

		iterator.advanceIfNeeded(0x7FFF0000);

		assertTrue(iterator.hasNext(), "value 5 is at or below the bound, so iteration must continue");
		assertEquals(5, iterator.peekNext());

		final ReverseIntIteratorFlyweight flyweight = new ReverseIntIteratorFlyweight(bitmap);
		flyweight.advanceIfNeeded(0x7FFF0000);
		assertTrue(flyweight.hasNext(), "the flyweight must skip the chunk above the signed wrap too");
		assertEquals(5, flyweight.peekNext());
	}

	/**
	 * A probe at the unsigned maximum must not move a cursor that already sits below it, and a probe
	 * below every stored value must exhaust the cursor rather than park it on a too-large value.
	 */
	@Test
	@DisplayName("Probes at the extremes of the unsigned range behave")
	public void probesAtUnsignedExtremes() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(0x80000000);
		bitmap.add(0xFFFFFFFF);

		final PeekableIntIterator top = bitmap.getReverseIntIterator();
		top.advanceIfNeeded(0xFFFFFFFF);
		assertTrue(top.hasNext());
		assertEquals(0xFFFFFFFF, top.peekNext(), "a bound at the unsigned maximum must not move the cursor");

		final PeekableIntIterator below = bitmap.getReverseIntIterator();
		below.advanceIfNeeded(0x7FFFFFFF);
		assertFalse(below.hasNext(), "every stored value is above the bound, so the cursor must exhaust");

		final ReverseIntIteratorFlyweight flyweight = new ReverseIntIteratorFlyweight(bitmap);
		flyweight.advanceIfNeeded(0x7FFFFFFF);
		assertFalse(flyweight.hasNext(), "the flyweight must exhaust as well");
	}

	/**
	 * An empty bitmap yields an already-exhausted reverse cursor that survives any probe.
	 */
	@Test
	@DisplayName("An empty bitmap tolerates any probe")
	public void emptyBitmapToleratesAnyProbe() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (final int probe : new int[]{0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF}) {
			final PeekableIntIterator iterator = bitmap.getReverseIntIterator();
			iterator.advanceIfNeeded(probe);
			assertFalse(iterator.hasNext(), "empty bitmap must stay exhausted");

			final ReverseIntIteratorFlyweight flyweight = new ReverseIntIteratorFlyweight(bitmap);
			flyweight.advanceIfNeeded(probe);
			assertFalse(flyweight.hasNext(), "empty bitmap must stay exhausted for the flyweight too");
		}
	}

	/**
	 * Sweeps deterministic probes at, just below and just above every chunk boundary of a bitmap whose
	 * chunks mix all three container shapes on both sides of the signed wrap.
	 */
	@Test
	@DisplayName("Every chunk boundary holds the post-condition for both reverse cursors")
	public void chunkBoundarySweepHoldsPostCondition() {
		final PersistentRoaringBitmap bitmap = mixedShapeBitmap();
		final int[] values = bitmap.toArray();

		for (final int key : CHUNK_KEYS) {
			final long base = (long) key << 16;
			for (final long delta : new long[]{-1L, 0L, 1L, 4999L, 60000L, 65535L}) {
				final long candidate = base + delta;
				if (candidate < 0 || candidate > 0xFFFFFFFFL) {
					continue;
				}
				final int probe = (int) candidate;
				final String where = "key=" + Integer.toHexString(key) + " probe="
					+ Integer.toUnsignedString(probe);

				final PeekableIntIterator iterator = bitmap.getReverseIntIterator();
				iterator.advanceIfNeeded(probe);
				assertPostCondition(iterator, probe, values, where);

				final ReverseIntIteratorFlyweight flyweight = new ReverseIntIteratorFlyweight(bitmap);
				flyweight.advanceIfNeeded(probe);
				assertPostCondition(flyweight, probe, values, where + " (flyweight)");
			}
		}
	}

	/**
	 * Randomized sweep over the whole unsigned range, including a strictly descending probe sequence on
	 * one cursor (the monotonic usage the contract is written for) as well as fresh-cursor probes.
	 */
	@Test
	@DisplayName("Randomized probes hold the post-condition, including a descending probe sequence")
	public void randomizedProbesHoldPostCondition() {
		final PersistentRoaringBitmap bitmap = mixedShapeBitmap();
		final int[] values = bitmap.toArray();

		for (int seed = 0; seed < 25; seed++) {
			final Random random = new Random(seed);

			for (int attempt = 0; attempt < 200; attempt++) {
				final int probe = random.nextInt();
				final PeekableIntIterator iterator = bitmap.getReverseIntIterator();
				iterator.advanceIfNeeded(probe);
				assertPostCondition(iterator, probe, values, "seed " + seed + " fresh probe");
			}

			// one cursor, strictly descending probes -- the monotonic idiom advanceIfNeeded is built for
			final PeekableIntIterator descending = bitmap.getReverseIntIterator();
			long probe = 0xFFFFFFFFL;
			while (probe >= 0) {
				descending.advanceIfNeeded((int) probe);
				assertPostCondition(descending, (int) probe, values, "seed " + seed + " descending probe");
				if (!descending.hasNext()) {
					break;
				}
				probe -= 1 + random.nextInt(1 << 24);
			}
		}
	}
}
