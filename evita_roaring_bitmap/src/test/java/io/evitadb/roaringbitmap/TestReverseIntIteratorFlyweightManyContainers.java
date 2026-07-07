package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@link ReverseIntIteratorFlyweight}: its chunk index must be wide enough to
 * address every container. A bitmap whose values span more than 32768 distinct 64K blocks makes the
 * backing {@link RoaringArray} hold more than `Short.MAX_VALUE + 1` containers, so the index has to
 * be an `int` (matching the forward {@link IntIteratorFlyweight}). A previous `short pos` field
 * overflowed to a negative value in `wrap()`, making `hasNext()` return `false` immediately and
 * reverse iteration silently yield nothing. This test pins the fix.
 */
@DisplayName("ReverseIntIteratorFlyweight with more than Short.MAX_VALUE containers")
public class TestReverseIntIteratorFlyweightManyContainers {

	@Test
	@DisplayName("Reverse iteration reproduces every value when the bitmap has > 32768 containers")
	public void shouldReverseIterateBitmapWithMoreThanShortMaxContainers() {
		// one value per distinct high-16-bit key forces one container per value, so the container
		// count lands just past the largest index a signed short can hold without overflow
		final int containerCount = Short.MAX_VALUE + 2; // 32769
		final int[] data = new int[containerCount];
		for (int i = 0; i < containerCount; i++) {
			data[i] = i << 16; // low 16 bits zero, high 16 bits = i => a distinct container each
		}

		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(data);

		// setup sanity: exactly one container per value, a count beyond a signed short's range
		assertEquals(containerCount, bitmap.getCardinality());
		assertEquals(containerCount, bitmap.highLowContainer.size());

		final ReverseIntIteratorFlyweight reverseIter = new ReverseIntIteratorFlyweight(bitmap);

		// contract: reverse iteration must reproduce every stored value, highest first
		assertTrue(reverseIter.hasNext());
		assertEquals((containerCount - 1) << 16, reverseIter.next());

		int seen = 1;
		while (reverseIter.hasNext()) {
			reverseIter.next();
			seen++;
		}
		assertEquals(containerCount, seen);
	}
}
