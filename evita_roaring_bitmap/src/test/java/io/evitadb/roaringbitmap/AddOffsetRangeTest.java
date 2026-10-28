package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins unsigned range clipping and private container ownership when adding aligned or unaligned
 * offsets, including carries at both ends of the unsigned 32-bit universe.
 */
public class AddOffsetRangeTest {

	/**
	 * An aligned positive shift must discard the maximum unsigned value instead of wrapping its key
	 * back to zero and retaining a spurious value.
	 */
	@Test
	public void alignedPositiveOffsetDropsValuesAboveUnsignedRange() {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(-1);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.addOffset(source, 65536);

		assertArrayEquals(new int[0], result.toArray(), "an overflowing aligned key must be dropped");
		assertArrayEquals(new int[]{-1}, source.toArray());
	}

	/**
	 * An aligned negative shift must drop an underflowing first chunk before appending later chunks,
	 * otherwise the wrapped first key makes the following append fail its ordering check.
	 */
	@Test
	public void alignedNegativeOffsetDropsUnderflowBeforeAppendingSurvivors() {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(5, 70000);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.addOffset(source, -65536);

		assertArrayEquals(new int[]{4464}, result.toArray());
		assertArrayEquals(new int[]{5, 70000}, source.toArray());
	}

	/**
	 * Aligned offsets of either sign must retain only valid chunks in unsigned ascending order, even
	 * when clipping crosses the signed integer boundary or leaves only an edge chunk.
	 */
	@ParameterizedTest
	@ValueSource(longs = {65536, -65536, 0xFFFF0000L, -0xFFFF0000L})
	public void alignedOffsetsClipChunksAtBothEnds(final long offset) {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(
			0, 1, 65535, 65536, 65537, Integer.MAX_VALUE, Integer.MIN_VALUE, -65537, -65536, -2, -1);

		assertShiftMatchesModel(source, offset, "aligned offset=" + offset);
	}

	/**
	 * Unaligned shifts must discard the out-of-range half of an edge container while preserving its
	 * valid half and merging carries from adjacent chunks.
	 */
	@ParameterizedTest
	@ValueSource(longs = {1, -1, 65537, -65537, 0xFFFFFFFFL, -0xFFFFFFFFL, 65535, -65535, 64, -64})
	public void unalignedOffsetsClipValuesAtBothEnds(final long offset) {
		assertShiftMatchesModel(
			PersistentRoaringBitmap.bitmapOf(0, 1, -2, -1), offset, "edge values, offset=" + offset);
		assertShiftMatchesModel(
			PersistentRoaringBitmap.bitmapOf(0, 65535, 65536, 65537, -65537, -65536, -1),
			offset, "chunk boundaries, offset=" + offset);
	}

	/**
	 * No unsigned 32-bit value can survive an offset whose magnitude exceeds the universe's maximum
	 * value, including negative offsets that previously wrapped through the aligned path.
	 */
	@ParameterizedTest
	@ValueSource(longs = {
		0x100000000L, -0x100000000L, 0x100000001L, -0x100000001L, Long.MAX_VALUE, Long.MIN_VALUE
	})
	public void offsetsBeyondUnsignedRangeReturnEmptyBitmap(final long offset) {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(0, 1, -2, -1);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.addOffset(source, offset);

		assertTrue(result.isEmpty(), "offset=" + offset + " must discard every value");
		assertArrayEquals(new int[]{0, 1, -2, -1}, source.toArray());
	}

	/**
	 * A zero offset must return an equal, distinct bitmap so callers can mutate the copy independently.
	 */
	@Test
	public void zeroOffsetReturnsEqualCopy() {
		final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(0, 65535, 65536, -2, -1);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.addOffset(source, 0);

		assertEquals(source, result);
		assertNotSame(source, result);
		assertNotSame(source.highLowContainer, result.highLowContainer);
	}

	/**
	 * Seeded small bitmaps spread over the entire unsigned universe must agree with long arithmetic
	 * for random aligned, unaligned and within-chunk offsets of both signs.
	 */
	@Test
	public void randomOffsetsMatchUnsignedLongModel() {
		final long seed = 0x1754E3L;
		final Random random = new Random(seed);
		for (int iteration = 0; iteration < 128; iteration++) {
			final PersistentRoaringBitmap source = PersistentRoaringBitmap.bitmapOf(
				0, 1, 65534, 65535, 65536, 65537, Integer.MAX_VALUE, Integer.MIN_VALUE, -65537, -65536, -2, -1);
			for (int i = 0; i < 32; i++) {
				source.add(random.nextInt());
			}
			final int boundary = random.nextInt(1 << 16) << 16;
			source.add(boundary - 1);
			source.add(boundary);
			source.add(boundary + 1);
			source.add(random.nextInt(1 << 16));
			source.add(-1 - random.nextInt(1 << 16));
			final long aligned = (1L + random.nextInt(0xFFFF)) << 16;
			final long unaligned = Integer.toUnsignedLong(random.nextInt()) | 1L;
			final long withinChunk = 1L + random.nextInt(0xFFFF);
			for (final long offset : new long[]{aligned, -aligned, unaligned, -unaligned, withinChunk, -withinChunk}) {
				assertShiftMatchesModel(
					source, offset, "seed=" + seed + ", iteration=" + iteration + ", offset=" + offset);
			}
		}
	}

	/**
	 * Array, bitmap and run containers must remain private after copying, splitting or merging chunks.
	 * Mutating every result chunk must leave both the source and its copy-on-write peer unchanged.
	 * Single-value removals write into existing storage before a range flip can replace the container.
	 */
	@ParameterizedTest
	@ValueSource(ints = {16, 10000, 65536})
	public void resultOwnsContainersIndependentlyOfSource(final int containerCardinality) {
		final PersistentRoaringBitmap source = new PersistentRoaringBitmap();
		for (final int key : new int[]{0, 1, 65534, 65535}) {
			final long base = (long) key << 16;
			if (containerCardinality == 65536) {
				source.add(base, base + 65536);
			} else {
				for (int i = 0; i < containerCardinality; i++) {
					source.add((int) (base + 2 * i));
				}
				source.add((int) (base + 65535));
			}
		}
		final PersistentRoaringBitmap peer = source.clone();
		final int[] sourceBefore = source.toArray();
		for (final long offset : new long[]{0, 65536, -65536, 1, -1, 65537, -65537}) {
			final String where = "cardinality=" + containerCardinality + ", offset=" + offset;
			final PersistentRoaringBitmap result = PersistentRoaringBitmap.addOffset(source, offset);
			final int[] resultBefore = result.toArray();
			assertArrayEquals(shiftedValues(sourceBefore, offset), resultBefore, where);
			for (int i = 0; i < result.getContainerCount(); i++) {
				assertFalse(result.isShared(i), where + ": result must own its containers");
				for (int j = 0; j < source.getContainerCount(); j++) {
					assertNotSame(source.getContainerAtIndex(j), result.getContainerAtIndex(i), where);
				}
			}
			for (int i = result.getContainerCount() - 1; i >= 0; i--) {
				final long base = (long) result.getKeyAtIndex(i) << 16;
				result.remove((int) base | result.getContainerAtIndex(i).first());
				result.flip(base, base + 65536);
			}
			assertFalse(Arrays.equals(resultBefore, result.toArray()), where + ": mutation must change the result");
			assertArrayEquals(sourceBefore, source.toArray(), where + ": source was written through");
			assertArrayEquals(sourceBefore, peer.toArray(), where + ": peer was written through");
		}
	}

	/**
	 * Compares ordered values against the plain model and checks the source survives the operation;
	 * the context also identifies the random seed if the implementation throws before comparison.
	 */
	private static void assertShiftMatchesModel(
		final PersistentRoaringBitmap source, final long offset, final String where) {
		final int[] sourceBefore = source.toArray();
		final PersistentRoaringBitmap result = assertDoesNotThrow(
			() -> PersistentRoaringBitmap.addOffset(source, offset), where);

		assertArrayEquals(shiftedValues(sourceBefore, offset), result.toArray(), where);
		assertArrayEquals(sourceBefore, source.toArray(), where + ": source was changed");
	}

	/**
	 * Computes the mathematical result without bitmap operations, treating each source int as unsigned
	 * and discarding sums outside the 32-bit range before converting back to the public int encoding.
	 */
	private static int[] shiftedValues(final int[] values, final long offset) {
		return Arrays.stream(values)
			.mapToLong(Integer::toUnsignedLong)
			.map(value -> value + offset)
			.filter(value -> value >= 0 && value <= 0xFFFFFFFFL)
			.sorted()
			.mapToInt(value -> (int) value)
			.toArray();
	}
}
