package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
		// exact array comparisons: BitSet.valueOf ignores trailing zero bytes and words, so it cannot see
		// an output that is too long
		assertArrayEquals(
			bitset.toByteArray(), BitSetUtil.toByteArray(bitmap), "toByteArray bytes don't match");
		assertEquals(
			bitset, BitSet.valueOf(BitSetUtil.toByteArray(bitmap)), "toByteArray doesn't match");
		assertArrayEquals(
			bitset.toLongArray(), BitSetUtil.toLongArray(bitmap), "toLongArray doesn't match");

		PersistentRoaringBitmap runBitmap = bitmap.clone();
		runBitmap.runOptimize();
		assertArrayEquals(
			bitset.toByteArray(),
			BitSetUtil.toByteArray(runBitmap),
			"toByteArray bytes don't match for run optimized bitmap");
		assertEquals(
			bitset, BitSetUtil.bitsetOf(runBitmap), "bitsetOf doesn't match for run optimized bitmap");
		assertArrayEquals(
			bitset.toLongArray(),
			BitSetUtil.toLongArray(runBitmap),
			"toLongArray doesn't match for run optimized bitmap");
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
	@DisplayName("Exporting a bitmap as word and byte arrays")
	class ToWordAndByteArrays {

		@Test
		@DisplayName("Word arrays are sized to the word holding the last value")
		public void testLastValueAtWordBoundaries() {
			for (final int value : new int[] {0, 1, 62, 63, 64, 65, 127, 128, 65535, 65536, 65599, 65600}) {
				final BitSet single = new BitSet();
				single.set(value);
				assertEqualBitsets(single, PersistentRoaringBitmap.bitmapOf(value));

				final BitSet range = new BitSet();
				range.set(0, value + 1);
				assertEqualBitsets(range, PersistentRoaringBitmap.bitmapOfRange(0, value + 1));
			}
		}

		@Test
		@DisplayName("Byte arrays are sized to the byte holding the last value")
		public void testLastValueAtByteBoundaries() {
			for (final int value :
				new int[] {0, 1, 7, 8, 9, 55, 56, 63, 64, 65, 127, 128, 65535, 65536, 65543, 65544}) {
				final BitSet single = new BitSet();
				single.set(value);
				assertEqualBitsets(single, PersistentRoaringBitmap.bitmapOf(value));

				final BitSet range = new BitSet();
				range.set(0, value + 1);
				assertEqualBitsets(range, PersistentRoaringBitmap.bitmapOfRange(0, value + 1));
			}
		}

		@Test
		@DisplayName("toByteArray matches BitSet byte for byte across array, bitmap and run containers with gaps")
		public void testToByteArrayMixedContainers() {
			final Random random = new Random(8570);
			for (int trial = 0; trial < 50; trial++) {
				final BitSet bitset = new BitSet();
				final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
				// containers of every type, with gaps, and the last one ending at an arbitrary bit
				final int containers = 1 + random.nextInt(6);
				for (int c = 0; c < containers; c++) {
					final int base = (2 * c + random.nextInt(2)) << 16;
					switch (random.nextInt(3)) {
						case 0 -> { // array
							for (int k = 0; k < 100; k++) {
								final int value = base + random.nextInt(1 << 16);
								bitset.set(value);
								bitmap.add(value);
							}
						}
						case 1 -> { // bitmap
							for (int k = 0; k < 10000; k++) {
								final int value = base + random.nextInt(1 << 16);
								bitset.set(value);
								bitmap.add(value);
							}
						}
						case 2 -> { // run
							final int start = base + random.nextInt(1 << 15);
							final int end = start + 1 + random.nextInt(1 << 15);
							bitset.set(start, end);
							bitmap.add((long) start, (long) end);
						}
						default -> throw new IllegalStateException("random.nextInt(3) returned a value outside [0, 3)");
					}
				}
				bitmap.runOptimize();
				assertArrayEquals(bitset.toByteArray(), BitSetUtil.toByteArray(bitmap));
			}
		}
	}

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
