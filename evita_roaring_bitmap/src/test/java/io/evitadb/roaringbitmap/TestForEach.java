package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link PersistentRoaringBitmap#forEach}, ported from the upstream
 * RoaringBitmap test suite. They pin down that the consumer visits every set bit in order and
 * exactly cardinality-many times across continuous and densely populated bitmaps.
 */
@DisplayName("PersistentRoaringBitmap.forEach")
public class TestForEach {

	@Test
	public void testContinuous() {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(100L, 10000L);

		final MutableInteger cardinality = new MutableInteger();
		bitmap.forEach(
			new IntConsumer() {
				int expected = 100;

				@Override
				public void accept(int value) {
					cardinality.value++;
					assertEquals(value, this.expected++);
				}
			});
		assertEquals(cardinality.value, bitmap.getCardinality());
	}

	@Test
	public void testDense() {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int k = 0; k < 100000; k += 3) bitmap.add(k);

		final MutableInteger cardinality = new MutableInteger();
		bitmap.forEach(
			new IntConsumer() {
				int expected = 0;

				@Override
				public void accept(int value) {
					cardinality.value++;
					assertEquals(value, this.expected);
					this.expected += 3;
				}
			});
		assertEquals(cardinality.value, bitmap.getCardinality());
	}

	@Test
	public void testSparse() {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int k = 0; k < 100000; k += 3000) bitmap.add(k);

		final MutableInteger cardinality = new MutableInteger();
		bitmap.forEach(
			new IntConsumer() {
				int expected = 0;

				@Override
				public void accept(int value) {
					cardinality.value++;
					assertEquals(value, this.expected);
					this.expected += 3000;
				}
			});
		assertEquals(cardinality.value, bitmap.getCardinality());
	}
}

class MutableInteger {
	public int value = 0;
}
