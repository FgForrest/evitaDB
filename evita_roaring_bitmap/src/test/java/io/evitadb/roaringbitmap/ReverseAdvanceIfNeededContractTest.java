package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-condition tests for {@link PeekableCharIterator#advanceIfNeeded(char)} on the three _reverse_
 * container cursors, and for the reverse seek they back.
 *
 * The interface contract is direction-aware: a reverse iterator "advances while the next value is
 * larger than `thresholdVal`". So after `advanceIfNeeded(m)` exactly one of two things must hold —
 * the cursor is exhausted, or `peekNext() <= m`. Nothing may be left sitting _above_ the bound.
 *
 * The interesting input is a threshold below the container's smallest value, because there the
 * correct answer is "exhausted" rather than "repositioned". The upstream test suite never probes it:
 * it only advances to values that are actually present in the bitmap, so a cursor that clamps at the
 * first slot instead of exhausting still passes every upstream assertion. The three shapes did not
 * agree on that edge — which is why this is asserted per shape rather than once.
 *
 * The last test pins the user-visible consequence, since
 * {@link PersistentLongRoaringBitmap#getReverseLongIterator()} forwards its seek straight down to
 * these cursors.
 */
@DisplayName("Reverse advanceIfNeeded post-condition")
public class ReverseAdvanceIfNeededContractTest {

	/**
	 * Asserts the direction-aware post-condition: after seeking to `maxval` the cursor is either
	 * exhausted or positioned at a value that does not exceed `maxval`.
	 */
	private static void assertAtOrBelow(final PeekableCharIterator iterator, final char maxval) {
		if (iterator.hasNext()) {
			final char peeked = iterator.peekNext();
			assertTrue(
				peeked <= maxval,
				"reverse cursor left at " + (int) peeked + ", above the requested bound " + (int) maxval);
		}
	}

	/**
	 * Builds a single-container bitmap and hands back that container, so each test can pin one shape.
	 */
	private static Container singleContainerOf(final PersistentRoaringBitmap bitmap) {
		assertEquals(1, bitmap.highLowContainer.size(), "test fixture must hold exactly one container");
		return bitmap.highLowContainer.getContainerAtIndex(0);
	}

	@Test
	@DisplayName("Array container: seeking below the smallest value exhausts the cursor")
	public void arrayContainerSeekBelowMinimumExhausts() {
		// two values only - comfortably under DEFAULT_MAX_SIZE, so the container stays an array
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(1000, 2000);
		final Container container = singleContainerOf(bitmap);
		assertTrue(container instanceof ArrayContainer, "fixture must produce an array container");

		final PeekableCharIterator iterator = container.getReverseCharIterator();
		iterator.advanceIfNeeded((char) 500);

		assertAtOrBelow(iterator, (char) 500);
		assertFalse(iterator.hasNext(), "no value is at or below 500, so the cursor must be exhausted");
	}

	@Test
	@DisplayName("Bitmap container: seeking below the smallest value exhausts the cursor")
	public void bitmapContainerSeekBelowMinimumExhausts() {
		// more than DEFAULT_MAX_SIZE values, none below 5000, forces a bitmap container
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int value = 5000; value < 5000 + ArrayContainer.DEFAULT_MAX_SIZE + 1; value++) {
			bitmap.add(value);
		}
		final Container container = singleContainerOf(bitmap);
		assertTrue(container instanceof BitmapContainer, "fixture must produce a bitmap container");

		final PeekableCharIterator iterator = container.getReverseCharIterator();
		iterator.advanceIfNeeded((char) 4000);

		assertAtOrBelow(iterator, (char) 4000);
		assertFalse(iterator.hasNext(), "no value is at or below 4000, so the cursor must be exhausted");
	}

	@Test
	@DisplayName("Run container: seeking below the smallest value exhausts the cursor")
	public void runContainerSeekBelowMinimumExhausts() {
		// one dense run, then runOptimize collapses it into a run container
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(5000L, 9000L);
		assertTrue(bitmap.runOptimize(), "fixture must collapse into a run container");
		final Container container = singleContainerOf(bitmap);
		assertTrue(container instanceof RunContainer, "fixture must produce a run container");

		final PeekableCharIterator iterator = container.getReverseCharIterator();
		iterator.advanceIfNeeded((char) 4000);

		assertAtOrBelow(iterator, (char) 4000);
		assertFalse(iterator.hasNext(), "no value is at or below 4000, so the cursor must be exhausted");
	}

	@Test
	@DisplayName("Every shape holds the post-condition across a sweep of thresholds")
	public void allShapesHoldPostConditionAcrossThresholds() {
		final PersistentRoaringBitmap arrayBacked = PersistentRoaringBitmap.bitmapOf(1000, 2000, 3000);

		final PersistentRoaringBitmap bitmapBacked = new PersistentRoaringBitmap();
		for (int value = 5000; value < 5000 + ArrayContainer.DEFAULT_MAX_SIZE + 1; value++) {
			bitmapBacked.add(value);
		}

		final PersistentRoaringBitmap runBacked = new PersistentRoaringBitmap();
		runBacked.add(5000L, 9000L);
		runBacked.runOptimize();

		final PersistentRoaringBitmap[] fixtures = {arrayBacked, bitmapBacked, runBacked};
		for (int fixture = 0; fixture < fixtures.length; fixture++) {
			final Container container = singleContainerOf(fixtures[fixture]);
			// sweep thresholds below, inside and above the populated span
			for (int threshold = 0; threshold <= 10000; threshold += 250) {
				final PeekableCharIterator iterator = container.getReverseCharIterator();
				iterator.advanceIfNeeded((char) threshold);
				assertAtOrBelow(iterator, (char) threshold);
			}
		}
	}

	@Test
	@DisplayName("Reverse int seek crosses into the next chunk when the current one holds nothing low enough")
	public void reverseIntSeekCrossesChunkWhenNoValueQualifies() {
		// chunk 0 holds the only value that satisfies the seek, while chunk 1 holds an array container
		// whose values all sit above the requested bound
		final PersistentRoaringBitmap bitmap =
			PersistentRoaringBitmap.bitmapOf(500, 65536 + 1000, 65536 + 2000);

		final PeekableIntIterator iterator = bitmap.getReverseIntIterator();
		assertEquals(65536 + 2000, iterator.peekNext());

		iterator.advanceIfNeeded(65536 + 700);

		assertTrue(iterator.hasNext(), "value 500 is at or below the bound, so iteration must continue");
		assertEquals(500, iterator.peekNext());

		// the reusable flyweight walks the same chunks and must agree
		final ReverseIntIteratorFlyweight flyweight = new ReverseIntIteratorFlyweight(bitmap);
		flyweight.advanceIfNeeded(65536 + 700);
		assertTrue(flyweight.hasNext(), "the flyweight must descend past the exhausted chunk too");
		assertEquals(500, flyweight.peekNext());
	}

	@Test
	@DisplayName("Reverse long seek crosses into the next chunk when the current one holds nothing low enough")
	public void reverseLongSeekCrossesChunkWhenNoValueQualifies() {
		final PersistentLongRoaringBitmap bitmap = new PersistentLongRoaringBitmap();
		// chunk 0 holds the only value that satisfies the seek...
		bitmap.addLong(500L);
		// ...while chunk 1 holds an array container whose values all sit above the requested bound
		bitmap.addLong(65536L + 1000L);
		bitmap.addLong(65536L + 2000L);

		final PeekableLongIterator iterator = bitmap.getReverseLongIterator();
		assertEquals(65536L + 2000L, iterator.peekNext());

		// the bound falls inside chunk 1's key range but below every value stored there, so the seek
		// has to abandon that chunk and descend into chunk 0
		iterator.advanceIfNeeded(65536L + 700L);

		assertTrue(iterator.hasNext(), "value 500 is at or below the bound, so iteration must continue");
		assertEquals(500L, iterator.peekNext());
	}
}
