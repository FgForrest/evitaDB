package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PersistentRoaringBitmap#getIntRankIterator()}: on every traversal - stepping with `next`, skipping
 * with `advanceIfNeeded`, and both mixed - the reported rank must equal {@link PersistentRoaringBitmap#rankLong(int)}
 * of the value the iterator stands on, across all three container kinds and across chunks whose values are negative
 * as signed ints (the iterator walks unsigned order).
 */
@DisplayName("Bitmap rank iteration")
public class IntRankIteratorTest {

	/**
	 * Builds a bitmap with an array chunk, a bitmap chunk, a run chunk, a skipped key range and a chunk of values
	 * that are negative as signed ints.
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
		// chunks 3 and 4 absent, chunk 5 holds a handful
		for (int i = 0; i < 7; i++) {
			bitmap.add((5 << 16) + random.nextInt(1 << 16));
		}
		// the top chunk: negative as signed ints, last in unsigned order
		for (int i = 0; i < 50; i++) {
			bitmap.add(0xFFFF0000 + random.nextInt(1 << 16));
		}
		bitmap.runOptimize();
		return bitmap;
	}

	/**
	 * Returns the bitmap's values in unsigned order as longs, for searching the expected next value.
	 */
	private static long[] unsignedValues(final PersistentRoaringBitmap bitmap) {
		final long[] values = new long[bitmap.getCardinality()];
		final PeekableIntIterator it = bitmap.getIntIterator();
		int i = 0;
		while (it.hasNext()) {
			values[i++] = Integer.toUnsignedLong(it.next());
		}
		return values;
	}

	/**
	 * Asserts that the iterator stands on the smallest value at or above `target`, with that value's rank, or is
	 * exhausted when there is none.
	 */
	private static void assertPositionedAt(
		final PersistentRoaringBitmap bitmap,
		final long[] values,
		final PeekableIntRankIterator iterator,
		final long target
	) {
		int expectedIndex = Arrays.binarySearch(values, target);
		if (expectedIndex < 0) {
			expectedIndex = -expectedIndex - 1;
		}
		if (expectedIndex == values.length) {
			assertFalse(iterator.hasNext(), "nothing at or above " + target);
			return;
		}
		assertTrue(iterator.hasNext(), "a value at or above " + target);
		assertEquals(values[expectedIndex], Integer.toUnsignedLong(iterator.peekNext()));
		assertEquals(expectedIndex + 1, iterator.peekNextRank());
		assertEquals(bitmap.rankLong(iterator.peekNext()), iterator.peekNextRank());
	}

	@Test
	@DisplayName("Reports the rank of every value while stepping")
	void shouldReportRankOfEveryValueOnNext() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(1));
		final PeekableIntRankIterator iterator = bitmap.getIntRankIterator();
		int expectedRank = 1;
		while (iterator.hasNext()) {
			final int value = iterator.peekNext();
			assertEquals(expectedRank, iterator.peekNextRank());
			assertEquals(bitmap.rankLong(value), iterator.peekNextRank());
			assertEquals(value, iterator.next());
			expectedRank++;
		}
		assertEquals(bitmap.getCardinality() + 1, expectedRank);
	}

	@Test
	@DisplayName("Reports the rank after skipping, within a chunk and across chunks")
	void shouldReportRankAfterAdvanceIfNeeded() {
		final Random random = new Random(2);
		for (int round = 0; round < 50; round++) {
			final PersistentRoaringBitmap bitmap = mixedBitmap(random);
			final long[] values = unsignedValues(bitmap);
			final long[] targets = new long[200];
			for (int i = 0; i < targets.length; i++) {
				// half of the targets inside the populated low chunks, the rest anywhere in the unsigned range
				targets[i] = random.nextBoolean() ? random.nextInt(6 << 16) : random.nextLong(1L << 32);
			}
			Arrays.sort(targets);
			final PeekableIntRankIterator iterator = bitmap.getIntRankIterator();
			for (final long target : targets) {
				iterator.advanceIfNeeded((int) target);
				assertPositionedAt(bitmap, values, iterator, target);
			}
		}
	}

	@Test
	@DisplayName("Keeps the rank when steps and skips alternate")
	void shouldKeepRankWhenStepsAndSkipsAlternate() {
		final Random random = new Random(3);
		final PersistentRoaringBitmap bitmap = mixedBitmap(random);
		final long[] values = unsignedValues(bitmap);
		final PeekableIntRankIterator iterator = bitmap.getIntRankIterator();
		while (iterator.hasNext()) {
			if (random.nextBoolean()) {
				iterator.next();
			} else {
				final long target = Integer.toUnsignedLong(iterator.peekNext()) + random.nextInt(3_000);
				iterator.advanceIfNeeded((int) Math.min(target, 0xFFFFFFFFL));
				assertPositionedAt(bitmap, values, iterator, Math.min(target, 0xFFFFFFFFL));
			}
			if (iterator.hasNext()) {
				assertEquals(bitmap.rankLong(iterator.peekNext()), iterator.peekNextRank());
			}
		}
	}

	@Test
	@DisplayName("Reports the rank after skipping onto the last value of each container kind and stepping out of it")
	void shouldReportRankOnTheLastValueOfEachContainerKind() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(6));
		final long[] values = unsignedValues(bitmap);
		// chunk 0 is an array container, chunk 1 a bitmap container, chunk 2 a run container
		for (final int chunk : new int[]{0, 1, 2}) {
			// index of the chunk's last value; the value after it opens the next populated chunk
			int last = 0;
			while (values[last + 1] >>> 16 <= chunk) {
				last++;
			}
			final PeekableIntRankIterator iterator = bitmap.getIntRankIterator();
			iterator.advanceIfNeeded((int) values[last]);
			assertPositionedAt(bitmap, values, iterator, values[last]);
			assertEquals((int) values[last], iterator.next());
			assertPositionedAt(bitmap, values, iterator, values[last] + 1);
		}
	}

	@Test
	@DisplayName("An empty bitmap yields an exhausted iterator")
	void shouldBeExhaustedOnEmptyBitmap() {
		final PeekableIntRankIterator iterator = new PersistentRoaringBitmap().getIntRankIterator();
		assertFalse(iterator.hasNext());
		iterator.advanceIfNeeded(42);
		assertFalse(iterator.hasNext());
	}

	@Test
	@DisplayName("A clone keeps its own position and rank")
	void shouldCloneIndependentCursor() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(4));
		final PeekableIntRankIterator origin = bitmap.getIntRankIterator();
		origin.advanceIfNeeded(1 << 16);
		final int value = origin.peekNext();
		final int rank = origin.peekNextRank();
		final PeekableIntRankIterator fork = origin.clone();
		fork.advanceIfNeeded(5 << 16);
		assertEquals(bitmap.rankLong(fork.peekNext()), fork.peekNextRank());
		assertEquals(value, origin.peekNext());
		assertEquals(rank, origin.peekNextRank());
		origin.next();
		assertEquals(rank + 1, origin.peekNextRank());
	}

	@Test
	@DisplayName("A clone stepping or skipping inside the current container leaves the origin where it was")
	void shouldCloneIndependentCursorInsideContainer() {
		final PersistentRoaringBitmap bitmap = mixedBitmap(new Random(5));
		// chunk 0 is an array container, chunk 1 a bitmap container, chunk 2 a run container
		for (final int chunk : new int[]{0, 1, 2}) {
			final PeekableIntRankIterator origin = bitmap.getIntRankIterator();
			origin.advanceIfNeeded(chunk << 16);
			// stand inside the container, past its first value
			origin.next();
			final int value = origin.peekNext();
			final int rank = origin.peekNextRank();
			assertEquals(chunk, value >>> 16, "the origin stays in chunk " + chunk);

			final PeekableIntRankIterator stepped = origin.clone();
			stepped.next();
			stepped.next();
			assertEquals(value, origin.peekNext(), "chunk " + chunk + " after the fork stepped");
			assertEquals(rank, origin.peekNextRank(), "chunk " + chunk + " after the fork stepped");
			assertEquals(rank + 2, stepped.peekNextRank());

			final PeekableIntRankIterator skipped = origin.clone();
			skipped.advanceIfNeeded(value + 1);
			assertEquals(chunk, skipped.peekNext() >>> 16, "the fork's skip stays in chunk " + chunk);
			assertEquals(bitmap.rankLong(skipped.peekNext()), skipped.peekNextRank());
			assertEquals(value, origin.peekNext(), "chunk " + chunk + " after the fork skipped");
			assertEquals(rank, origin.peekNextRank(), "chunk " + chunk + " after the fork skipped");

			assertEquals(value, origin.next());
			assertEquals(rank + 1, origin.peekNextRank());
		}
	}

	@Test
	@DisplayName("Skipping many containers at once keeps the rank")
	void shouldKeepRankWhenSkippingManyContainers() {
		// one value in each of 4,096 containers: a skip to the last one passes every other container
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int key = 0; key < 4_096; key++) {
			bitmap.add((key << 16) | 1);
		}
		final PeekableIntRankIterator iterator = bitmap.getIntRankIterator();
		iterator.advanceIfNeeded((2_000 << 16) | 1);
		assertEquals((2_000 << 16) | 1, iterator.peekNext());
		assertEquals(2_001, iterator.peekNextRank());
		iterator.advanceIfNeeded((4_095 << 16) | 1);
		assertEquals((4_095 << 16) | 1, iterator.peekNext());
		assertEquals(4_096, iterator.peekNextRank());
		iterator.advanceIfNeeded((4_095 << 16) | 2);
		assertFalse(iterator.hasNext());
	}

}
