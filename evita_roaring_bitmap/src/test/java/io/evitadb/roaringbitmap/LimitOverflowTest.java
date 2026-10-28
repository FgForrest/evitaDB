package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins cardinality limits near the signed integer boundary and the independent ownership of both
 * copied and truncated containers returned by {@link PersistentRoaringBitmap#limit(int)}.
 */
public class LimitOverflowTest {

	/**
	 * A full unsigned universe must stop inside the container whose inclusion would overflow an int
	 * cardinality sum, rather than accidentally copying all remaining containers.
	 */
	@Test
	public void limitOfFullUniverseStopsAtMaxIntCardinality() {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOfRange(0, 1L << 32);

		final PersistentRoaringBitmap result = source.limit(Integer.MAX_VALUE);

		assertEquals((long) Integer.MAX_VALUE, result.getLongCardinality());
		assertEquals(Integer.MAX_VALUE - 1L, Integer.toUnsignedLong(result.last()));
		assertEquals(1L << 32, source.getLongCardinality());
	}

	/**
	 * Exhausting the limit exactly at a container boundary must neither add an empty container nor
	 * copy the following container, even when the next inclusion would overflow a signed int.
	 */
	@Test
	public void limitOfFullUniverseStopsAtContainerBoundary() {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOfRange(0, 1L << 32);
		final int maxcardinality = 65536 * 32767;

		final PersistentRoaringBitmap result = source.limit(maxcardinality);

		assertEquals((long) maxcardinality, result.getLongCardinality());
		assertEquals(maxcardinality - 1L, Integer.toUnsignedLong(result.last()));
		assertEquals(32767, result.getContainerCount());
	}

	/**
	 * Starting one container into the universe puts the cutoff after the signed value boundary;
	 * limiting must still stop inside that container and return its last value as an unsigned int.
	 */
	@Test
	public void limitStopsInsideContainerAfterSignedValueBoundary() {
		final long start = 1L << 16;
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOfRange(start, 1L << 32);

		final PersistentRoaringBitmap result = source.limit(Integer.MAX_VALUE);

		assertEquals((long) Integer.MAX_VALUE, result.getLongCardinality());
		assertEquals(start + Integer.MAX_VALUE - 1L, Integer.toUnsignedLong(result.last()));
		assertEquals(start, Integer.toUnsignedLong(result.first()));
	}

	/**
	 * Zero and negative limits retain the existing empty-result behaviour and leave the source intact.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, -1, Integer.MIN_VALUE})
	public void nonpositiveLimitReturnsEmptyBitmap(final int maxcardinality) {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOfRange(0, 1L << 32);

		final PersistentRoaringBitmap result = source.limit(maxcardinality);

		assertTrue(result.isEmpty());
		assertEquals(0L, result.getLongCardinality());
		assertEquals(1L << 32, source.getLongCardinality());
	}

	/**
	 * Array, bitmap and run containers must be owned independently after both copying a whole chunk
	 * and truncating the next, so writes through either bitmap cannot alter the other's contents.
	 */
	@ParameterizedTest
	@ValueSource(ints = {16, 10000, 65536})
	public void limitOwnsCopiedAndTruncatedContainers(final int containerCardinality) {
		final PersistentRoaringBitmap source = new PersistentRoaringBitmap();
		for (int chunk = 0; chunk < 2; chunk++) {
			final int base = chunk << 16;
			if (containerCardinality == 65536) {
				source.add((long) base, (long) base + containerCardinality);
			} else {
				for (int offset = 0; offset < containerCardinality; offset++) {
					source.add(base + 2 * offset);
				}
			}
		}
		final int maxcardinality = containerCardinality + containerCardinality / 2;

		final PersistentRoaringBitmap result = source.limit(maxcardinality);

		assertEquals((long) maxcardinality, result.getLongCardinality());
		assertEquals(2, result.getContainerCount());
		for (int chunk = 0; chunk < 2; chunk++) {
			assertNotSame(source.getContainerAtIndex(chunk), result.getContainerAtIndex(chunk));
			assertFalse(source.isShared(chunk));
			assertFalse(result.isShared(chunk));
		}

		result.remove(0);
		result.remove(65536);
		assertTrue(source.contains(0), "writing the copied container must not change the source");
		assertTrue(source.contains(65536), "writing the truncated container must not change the source");

		source.remove(2);
		source.remove(65538);
		assertTrue(result.contains(2), "writing the source must not change the copied container");
		assertTrue(result.contains(65538), "writing the source must not change the truncated container");
	}
}
