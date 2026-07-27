package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Random;

/**
 * Regression tests for {@link BitSetUtil}, ported from the upstream RoaringBitmap test suite.
 * They pin down the conversion between {@link BitSet} / {@link ByteBuffer} and
 * {@link PersistentRoaringBitmap}, covering empty, full, sparse, gapped and randomly populated
 * inputs as well as the rejection of bitmaps holding negative bits.
 */
@DisplayName("BitSetUtil")
public class TestBitSetUtil {
	private static BitSet appendRandomBitset(
		final Random random, final int offset, final BitSet bitset, final int nbits) {
		for (int i = 0; i < nbits; i++) {
			final boolean b = random.nextBoolean();
			bitset.set(offset + i, b);
		}
		return bitset;
	}

	private static BitSet randomBitset(final Random random, final int offset, final int length) {
		final BitSet bitset = new BitSet();
		return appendRandomBitset(random, offset, bitset, length);
	}

	private void assertEqualBitsets(final BitSet bitset, final PersistentRoaringBitmap bitmap) {
		assertTrue(BitSetUtil.equals(bitset, bitmap), "bitset and bitmap do not match");
		assertEquals(bitset, BitSetUtil.bitsetOf(bitmap), "bitsetOf doesn't match");
		assertEquals(
			bitset, BitSetUtil.bitsetOfWithoutCopy(bitmap), "bitsetOfWithoutCopy doesn't match");
		assertEquals(
			bitset, BitSet.valueOf(BitSetUtil.toByteArray(bitmap)), "toByteArray doesn't match");

		PersistentRoaringBitmap runBitmap = bitmap.clone();
		runBitmap.runOptimize();
		assertEquals(
			bitset, BitSetUtil.bitsetOf(runBitmap), "bitsetOf doesn't match for run optimized bitmap");
	}

	@Nested
	@DisplayName("Building a bitmap from a BitSet")
	class FromBitSet {

		@Test
		@DisplayName("Empty BitSet round-trips to an empty bitmap")
		public void testEmptyBitSet() {
			final BitSet bitset = new BitSet();
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Mix of empty, randomly filled and full blocks round-trips")
		public void testFlipFlapBetweenRandomFullAndEmptyBitSet() {
			final Random random = new Random(1234);
			final int nbitsPerBlock = 1024 * Long.SIZE;
			final int blocks = 50;
			final BitSet bitset = new BitSet(nbitsPerBlock * blocks);

			// i want a mix of empty blocks, randomly filled blocks and full blocks
			for (int block = 0; block < blocks * nbitsPerBlock; block += nbitsPerBlock) {
				int type = random.nextInt(3);
				switch (type) {
					case 0:
						// a block with random set bits
						appendRandomBitset(random, block, bitset, nbitsPerBlock);
						break;
					case 1:
						// a full block
						bitset.set(block, block + nbitsPerBlock);
						break;
					default:
						// and an empty block;
						break;
				}
			}
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Fully set BitSet round-trips")
		public void testFullBitSet() {
			final BitSet bitset = new BitSet();
			final int nbits = 1024 * Long.SIZE * 50;
			bitset.set(0, nbits);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Regularly spaced bits across many gaps and offsets round-trip")
		public void testGapBitmap() {
			for (int gap = 1; gap <= 4096; gap *= 2) {
				for (int offset = 300; offset < 3000; offset += 10) {
					BitSet bitset = new BitSet();
					for (int k = 0; k < 100000; k += gap) {
						bitset.set(k + offset);
					}
					final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
					assertEqualBitsets(bitset, bitmap);
				}
			}
		}

		@Test
		@DisplayName("Randomly populated BitSets round-trip")
		public void testRandomBitmap() {
			final Random random = new Random(1235);
			final int runs = 50;
			final int maxNbits = 500000;
			for (int i = 0; i < runs; i++) {
				final BitSet bitset = randomBitset(random, 0, random.nextInt(maxNbits));
				final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
				assertEqualBitsets(bitset, bitmap);
			}
		}

		@Test
		@DisplayName("Randomly populated BitSets with a high starting offset round-trip")
		public void testRandomBitmap_extended() {
			final Random random = new Random(1245);
			final int runs = 50;
			final int maxNbits = 500000;
			for (int i = 0; i < runs; i++) {
				final BitSet bitset = randomBitset(random, 100000, random.nextInt(maxNbits));
				final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
				assertEqualBitsets(bitset, bitmap);
			}
		}

		@Test
		@DisplayName("Single low bit round-trips")
		public void testSmallBitSet1() {
			final BitSet bitset = new BitSet();
			bitset.set(1);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Bits 1 and 10,000,000 round-trip")
		public void testSmallBitSet1_10000000() {
			final BitSet bitset = new BitSet();
			bitset.set(1);
			bitset.set(10000000);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Single high bit round-trips")
		public void testSmallBitSet10000000() {
			final BitSet bitset = new BitSet();
			bitset.set(10000000);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(bitset);
			assertEqualBitsets(bitset, bitmap);
		}
	}

	/**
	 * These ByteBuffer -> PersistentRoaringBitmap cases replicate the similar BitSet / long[] ->
	 * PersistentRoaringBitmap tests above, exercising the ByteBuffer entry point instead.
	 */
	@Nested
	@DisplayName("Building a bitmap from a ByteBuffer")
	class FromByteBuffer {

		@Test
		@DisplayName("Empty ByteBuffer round-trips to an empty bitmap")
		public void testEmptyByteBuffer() {
			final BitSet bitset = new BitSet();
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Mix of empty, randomly filled and full blocks round-trips")
		public void testFlipFlapBetweenRandomFullAndEmptyByteBuffer() {
			final Random random = new Random(1234);
			final int nbitsPerBlock = 1024 * Long.SIZE;
			final int blocks = 50;
			final BitSet bitset = new BitSet(nbitsPerBlock * blocks);

			// i want a mix of empty blocks, randomly filled blocks and full blocks
			for (int block = 0; block < blocks * nbitsPerBlock; block += nbitsPerBlock) {
				int type = random.nextInt(3);
				switch (type) {
					case 0:
						// a block with random set bits
						appendRandomBitset(random, block, bitset, nbitsPerBlock);
						break;
					case 1:
						// a full block
						bitset.set(block, block + nbitsPerBlock);
						break;
					default:
						// and an empty block;
						break;
				}
			}
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Fully set ByteBuffer round-trips")
		public void testFullByteBuffer() {
			final BitSet bitset = new BitSet();
			final int nbits = 1024 * Long.SIZE * 50;
			bitset.set(0, nbits);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Regularly spaced bits across many gaps and offsets round-trip")
		public void testGapByteBuffer() {
			for (int gap = 1; gap <= 4096; gap *= 2) {
				for (int offset = 300; offset < 3000; offset += 10) {
					BitSet bitset = new BitSet();
					for (int k = 0; k < 100000; k += gap) {
						bitset.set(k + offset);
					}
					final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
					assertEqualBitsets(bitset, bitmap);
				}
			}
		}

		@Test
		@DisplayName("Randomly populated ByteBuffers round-trip")
		public void testRandomByteBuffer() {
			final Random random = new Random(8934);
			final int runs = 100;
			final int maxNbits = 500000;
			for (int i = 0; i < runs; ++i) {
				final int offset = random.nextInt(maxNbits) & Integer.MAX_VALUE;
				final BitSet bitset = randomBitset(random, offset, random.nextInt(maxNbits));
				final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
				assertEqualBitsets(bitset, bitmap);
			}
		}

		@Test
		@DisplayName("Single high bit round-trips")
		public void testByteArrayWithOnly10000000thBitSet() {
			final BitSet bitset = new BitSet();
			bitset.set(10000000);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
			assertEqualBitsets(bitset, bitmap);
		}

		@Test
		@DisplayName("Bits 1 and 10,000,000 round-trip")
		public void testByteArrayWithOnly1And10000000thBitSet() {
			final BitSet bitset = new BitSet();
			bitset.set(1);
			bitset.set(10000000);
			final PersistentRoaringBitmap bitmap = BitSetUtil.bitmapOf(toByteBuffer(bitset));
			assertEqualBitsets(bitset, bitmap);
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandling {

		@Test
		@DisplayName("bitsetOf rejects a bitmap containing negative bits")
		public void testBitmapOfNegative() {
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			bitmap.add(-1);
			IllegalArgumentException exception =
				assertThrows(
					IllegalArgumentException.class,
					() -> {
						BitSetUtil.bitsetOf(bitmap);
					}
				);
			assertEquals("bitmap has negative bits set", exception.getMessage());
		}
	}

	private static ByteBuffer toByteBuffer(BitSet bitset) {
		return ByteBuffer.wrap(bitset.toByteArray());
	}
}
