package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the batch iterator returned by an (initially) empty
 * {@link PersistentRoaringBitmap}, ported from the upstream RoaringBitmap test suite. It verifies
 * that a fresh bitmap yields an empty batch and that a single added value is then reported by a
 * newly obtained batch iterator.
 */
@DisplayName("Empty Roaring batch iterator")
public class TestEmptyRoaringBatchIterator {

	@Test
	@DisplayName("Batch iterator reflects emptiness and a subsequently added value")
	public void testEmptyRoaringBitmap() {
		PersistentRoaringBitmap roaringBitmap = new PersistentRoaringBitmap();
		BatchIterator iterator = roaringBitmap.getBatchIterator();
		int[] ints = new int[1024];
		int cnt = iterator.nextBatch(ints);
		assertEquals(0, cnt);

		roaringBitmap.add(1);
		iterator = roaringBitmap.getBatchIterator();
		cnt = iterator.nextBatch(ints);
		assertEquals(1, cnt);
	}
}
