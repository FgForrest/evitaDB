package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link PersistentRoaringBitmap} serialized-size accounting, ported from the
 * upstream RoaringBitmap test suite. They pin down that the reported and maximum serialized sizes
 * stay within their expected bounds for empty, singleton and ranged bitmaps.
 */
@DisplayName("PersistentRoaringBitmap serialized size")
public class TestSerializedSize {

	@Test
	public void testLucaSize() {
		System.out.println("testLucaSize");
		PersistentRoaringBitmap rb =
			PersistentRoaringBitmap.bitmapOf(2946000, 2997491, 10478289, 10490227, 10502444, 19866827);
		System.out.println("cardinality = " + rb.getCardinality());
		System.out.println("total size in bytes = " + rb.getSizeInBytes());
		assertTrue(rb.getSizeInBytes() <= 50);
	}

	@Test
	public void testEmpty() {
		PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		long c = PersistentRoaringBitmap.maximumSerializedSize(0, 0);
		long ac = rb.serializedSizeInBytes();
		assertTrue(ac <= c);
		rb.runOptimize();
		long rac = rb.serializedSizeInBytes();
		assertTrue(rac <= c);
	}

	@Test
	public void testOne() {
		for (int k = 0; k < 100000; k += 100) {
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			rb.add(k);
			long c = PersistentRoaringBitmap.maximumSerializedSize(1, k + 1);
			long ac = rb.serializedSizeInBytes();
			assertTrue(ac <= c);
			rb.runOptimize();
			long rac = rb.serializedSizeInBytes();
			assertTrue(rac <= c);
		}
	}

	@Test
	public void testRange() {
		for (int k = 0; k < 100000; k += 100) {
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			rb.add(0L, (long) (k + 1));
			long c = PersistentRoaringBitmap.maximumSerializedSize(rb.getCardinality(), k + 1);
			long ac = rb.serializedSizeInBytes();
			assertTrue(ac <= c);
			rb.runOptimize();
			long rac = rb.serializedSizeInBytes();
			assertTrue(rac <= c);
		}
	}

	@Test
	public void testLarge() {
		for (long scale = 15; scale < 2048; scale *= 15) {
			final int N = 1000000;
			PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
			int universe_size = 0;
			for (int k = 0; k < N; ++k) {
				int val = (int) (scale * k);
				if (val > universe_size) universe_size = val;
				rb.add((int) (scale * k));
			}
			universe_size++;
			long c = PersistentRoaringBitmap.maximumSerializedSize(rb.getCardinality(), universe_size);
			long ac = rb.serializedSizeInBytes();
			assertTrue(ac <= c);
			rb.runOptimize();
			long rac = rb.serializedSizeInBytes();
			assertTrue(rac <= c);
		}
	}

	@Test
	public void testManyRanges() {
		for (int stepsize = 1; stepsize < 32; ++stepsize)
			for (long step = 1; step < 500; ++step) {
				PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
				int universe_size = 0;

				for (int i = 0; i < step; ++i) {
					final int maxv = i * (1 << 16) + stepsize;
					rb.add(i * (1L << 16), i * (1L << 16) + stepsize);
					if (maxv > universe_size) universe_size = maxv;
				}
				long c = PersistentRoaringBitmap.maximumSerializedSize(rb.getCardinality(), universe_size);
				long ac = rb.serializedSizeInBytes();
				assertTrue(ac <= c);
				rb.runOptimize();
				long rac = rb.serializedSizeInBytes();
				assertTrue(rac <= c);
			}
	}

	private static int[] firstPrimes(int n) {
		int status = 1, num = 3;
		int[] answer = new int[n];
		for (int count = 0; count < n; ) {
			double s = Math.sqrt(num);
			for (int j = 2; j <= s; j++) {
				if (num % j == 0) {
					status = 0;
					break;
				}
			}
			if (status != 0) {
				answer[count] = num;
				count++;
			}
			status = 1;
			num++;
		}
		return answer;
	}

	@Test
	public void testPrimeSerializedSize() {
		System.out.println("[testPrimeSerializedSize]");
		for (int j = 1000; j < 1000 * 1000; j *= 10) {
			int[] primes = firstPrimes(j);
			PersistentRoaringBitmap rb = PersistentRoaringBitmap.bitmapOf(primes);
			long vagueupperbound =
				PersistentRoaringBitmap.maximumSerializedSize(rb.getCardinality(), Integer.MAX_VALUE);
			long upperbound =
				PersistentRoaringBitmap.maximumSerializedSize(rb.getCardinality(), primes[primes.length - 1] + 1);

			long actual = rb.serializedSizeInBytes();
			System.out.println(
				"cardinality = "
					+ rb.getCardinality()
					+ " serialized size = "
					+ actual
					+ " silly upper bound = "
					+ vagueupperbound
					+ " better upper bound = "
					+ upperbound);
			assertTrue(actual <= vagueupperbound);
			assertTrue(upperbound <= vagueupperbound);
			assertTrue(actual <= upperbound);
		}
	}
}
