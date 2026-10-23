package io.evitadb.roaringbitmap;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins unsigned range boundaries and container ownership for both forms of {@code orNot}, using a
 * plain set model so capacity estimates cannot silently discard chunks or import out-of-range bits.
 */
public class OrNotRangeBoundaryTest {

	private static final long SEED = 0x1754E4L;
	private static final int CHUNK_SIZE = 1 << 16;
	private static final long UNIVERSE_SIZE = 1L << 32;

	/**
	 * A full container beyond the last complemented key must not suppress the one requested value.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void fullContainerBeyondRangeDoesNotSuppressComplement(final boolean staticForm) {
		assertMatchesModel(
			new PersistentRoaringBitmap(), PersistentRoaringBitmap.bitmapOfRange(CHUNK_SIZE, 2L * CHUNK_SIZE),
			1, staticForm, "full container beyond range");
	}

	/**
	 * An empty range must produce an empty result even when a full operand made the old capacity
	 * negative before either implementation started processing keys.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void zeroRangeWithFullOperandIsEmpty(final boolean staticForm) {
		assertMatchesModel(
			new PersistentRoaringBitmap(), PersistentRoaringBitmap.bitmapOfRange(0, CHUNK_SIZE),
			0, staticForm, "empty range and empty receiver");
	}

	/**
	 * Restoring sparse receiver chunks inside full operand chunks leaves a positive underestimated
	 * capacity; the missing final chunk must still be complemented instead of silently truncated.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void fullContainersBeyondRangeDoNotTruncatePositiveCapacity(final boolean staticForm) {
		final PersistentRoaringBitmap x1 = PersistentRoaringBitmap.bitmapOf(1, CHUNK_SIZE + 1);
		final PersistentRoaringBitmap x2 = PersistentRoaringBitmap.bitmapOfRange(0, 2L * CHUNK_SIZE);
		x2.add(3L * CHUNK_SIZE, 6L * CHUNK_SIZE);

		assertMatchesModel(x1, x2, 3L * CHUNK_SIZE, staticForm, "positive capacity truncation");
	}

	/**
	 * When only the operand has the boundary chunk, its values past the boundary must not be copied
	 * into the result by a partial container complement.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void partialChunkDoesNotCopyOperandValuesBeyondRange(final boolean staticForm) {
		assertMatchesModel(
			new PersistentRoaringBitmap(), PersistentRoaringBitmap.bitmapOf(7),
			1, staticForm, "operand-only partial chunk");
	}

	/**
	 * A zero range preserves every receiver container. The static copy must own them independently,
	 * while the in-place no-op must retain its backing arrays and copy-on-write flags.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void zeroRangePreservesReceiverAndStaticOwnership(final boolean staticForm) {
		assertMatchesModel(
			randomBitmap(new Random(SEED)), PersistentRoaringBitmap.bitmapOfRange(0, CHUNK_SIZE),
			0, staticForm, "empty range with every container shape");
	}

	/**
	 * Validation must still reject negative bounds before doing any work on either bitmap.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void negativeRangeRemainsIllegal(final boolean staticForm) {
		final PersistentRoaringBitmap x1 = PersistentRoaringBitmap.bitmapOf(1);
		final PersistentRoaringBitmap x2 = PersistentRoaringBitmap.bitmapOf(2);
		final String where = context(x1, x2, -1, staticForm, "negative range");

		assertThrows(IllegalArgumentException.class, () -> {
			if (staticForm) {
				PersistentRoaringBitmap.orNot(x1, x2, -1);
			} else {
				x1.orNot(x2, -1);
			}
		}, where);
		assertTrue(PersistentRoaringBitmap.bitmapOf(1).equals(x1), where + ": x1 changed");
		assertTrue(PersistentRoaringBitmap.bitmapOf(2).equals(x2), where + ": x2 changed");
	}

	/**
	 * Seeded operands place arrays, bitmaps, partial runs, full runs and holes on keys zero through
	 * eight, exercising their positions below, at and above exact and adjacent chunk boundaries.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void seededBoundariesMatchUnsignedSetModel(final boolean staticForm) {
		final Random random = new Random(SEED);
		for (int iteration = 0; iteration < 32; iteration++) {
			final long rangeEnd;
			if (iteration < 2) {
				rangeEnd = iteration;
			} else if (iteration < 26) {
				rangeEnd = (long) ((iteration - 2) / 3 + 1) * CHUNK_SIZE + (iteration - 2) % 3 - 1;
			} else {
				rangeEnd = random.nextInt(9 * CHUNK_SIZE + 1);
			}
			assertMatchesModel(
				randomBitmap(random), randomBitmap(random), rangeEnd, staticForm, "iteration=" + iteration);
		}
	}

	/**
	 * Full-universe complements use a nearly full operand and a plain set of holes, keeping the model
	 * cheap while checking unsigned values on both sides of the signed boundary and at 2^32 - 1.
	 */
	@ParameterizedTest(name = "staticForm={0}")
	@ValueSource(booleans = {true, false})
	public void fullUniverseMatchesUnsignedSetOfHoles(final boolean staticForm) {
		final Random random = new Random(SEED);
		for (int iteration = 0; iteration < 3; iteration++) {
			final Set<Long> holes = new HashSet<>();
			if (iteration > 0) {
				Collections.addAll(holes, 0L, 1L, 65535L, 65536L, 2147483647L, 2147483648L, 4294967294L, 4294967295L);
			}
			if (iteration == 2) {
				for (int i = 0; i < 16; i++) {
					holes.add(Integer.toUnsignedLong(random.nextInt()));
				}
			}
			final PersistentRoaringBitmap x1 = iteration == 0
				? new PersistentRoaringBitmap()
				: PersistentRoaringBitmap.bitmapOf(1, 70000, Integer.MIN_VALUE, -1);
			final PersistentRoaringBitmap x2 = PersistentRoaringBitmap.bitmapOfRange(0, UNIVERSE_SIZE);
			for (final long hole : holes) {
				x2.remove((int) hole);
			}
			final Set<Long> expected = unsignedValues(x1);
			expected.addAll(holes);
			final String where = "seed=" + SEED + ", iteration=" + iteration + ", staticForm=" + staticForm
				+ ", rangeEnd=" + UNIVERSE_SIZE + ", x1=" + describe(x1) + ", x2=full universe except " + holes;

			assertOperation(x1, x2, UNIVERSE_SIZE, expected, staticForm, where);
		}
	}

	/**
	 * Builds a bounded mathematical union in unsigned long arithmetic without using bitmap operators.
	 */
	private static void assertMatchesModel(
		final PersistentRoaringBitmap x1, final PersistentRoaringBitmap x2, final long rangeEnd,
		final boolean staticForm, final String label) {
		final Set<Long> expected = unsignedValues(x1);
		final Set<Long> excluded = unsignedValues(x2);
		for (long value = 0; value < rangeEnd; value++) {
			if (!excluded.contains(value)) {
				expected.add(value);
			}
		}
		assertOperation(x1, x2, rangeEnd, expected, staticForm, context(x1, x2, rangeEnd, staticForm, label));
	}

	/**
	 * Checks values, operand stability, full static ownership and writes through a result whose
	 * in-place form starts as a copy-on-write peer of x1, including carried remainder chunks.
	 */
	private static void assertOperation(
		final PersistentRoaringBitmap x1, final PersistentRoaringBitmap x2, final long rangeEnd,
		final Set<Long> expected, final boolean staticForm, final String where) {
		final PersistentRoaringBitmap before1 = PersistentRoaringBitmap.fromBitmap(x1);
		final PersistentRoaringBitmap before2 = PersistentRoaringBitmap.fromBitmap(x2);
		final PersistentRoaringBitmap result;
		if (staticForm) {
			result = assertDoesNotThrow(() -> PersistentRoaringBitmap.orNot(x1, x2, rangeEnd), where);
			for (int i = 0; i < result.getContainerCount(); i++) {
				assertFalse(result.isShared(i), where + ": static result must own slot " + i);
				for (final PersistentRoaringBitmap input : new PersistentRoaringBitmap[]{x1, x2}) {
					final int inputIndex = input.highLowContainer.getIndex(result.getKeyAtIndex(i));
					if (inputIndex >= 0) {
						assertNotSame(input.getContainerAtIndex(inputIndex), result.getContainerAtIndex(i), where);
					}
				}
			}
		} else {
			result = x1.clone();
			final char[] keys = result.highLowContainer.keys;
			final Container[] values = result.highLowContainer.values;
			final boolean[] shared = result.shared;
			assertDoesNotThrow(() -> result.orNot(x2, rangeEnd), where);
			if (rangeEnd == 0) {
				assertSame(keys, result.highLowContainer.keys, where + ": zero range replaced keys");
				assertSame(values, result.highLowContainer.values, where + ": zero range replaced values");
				assertSame(shared, result.shared, where + ": zero range replaced shared flags");
			}
		}
		assertEquals((long) expected.size(), result.getLongCardinality(), where + ": cardinality");
		final IntIterator iterator = result.getIntIterator();
		Long unexpected = null;
		while (iterator.hasNext()) {
			final long value = Integer.toUnsignedLong(iterator.next());
			if (!expected.contains(value)) {
				unexpected = value;
				break;
			}
		}
		assertEquals(null, unexpected, where + ": unexpected unsigned value");
		assertTrue(before1.equals(x1), where + ": operation changed x1");
		assertTrue(before2.equals(x2), where + ": operation changed x2");
		for (int i = result.getContainerCount() - 1; i >= 0; i--) {
			final int base = result.getKeyAtIndex(i) << 16;
			result.remove(base | result.getContainerAtIndex(i).first());
			result.flip(base | 65535);
		}
		result.add(9 * CHUNK_SIZE + 31);
		assertTrue(before1.equals(x1), where + ": result mutation changed x1");
		assertTrue(before2.equals(x2), where + ": result mutation changed x2");
	}

	/**
	 * Enumerates a bounded bitmap into an ordinary set using unsigned long values.
	 */
	private static Set<Long> unsignedValues(final PersistentRoaringBitmap bitmap) {
		final Set<Long> values = new HashSet<>();
		final IntIterator iterator = bitmap.getIntIterator();
		while (iterator.hasNext()) {
			values.add(Integer.toUnsignedLong(iterator.next()));
		}
		return values;
	}

	/**
	 * Randomizes chunk positions and contents while retaining every encoding and one missing chunk;
	 * bitmap chunks are arithmetic progressions so assertion messages can describe their exact input.
	 */
	private static PersistentRoaringBitmap randomBitmap(final Random random) {
		final List<Integer> shapes = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 0, 1, 2, 3, -1));
		Collections.shuffle(shapes, random);
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int key = 0; key < shapes.size(); key++) {
			final Container container;
			switch (shapes.get(key)) {
				case 0:
					container = new ArrayContainer();
					for (int i = 0; i < 24; i++) {
						container.add((char) random.nextInt(CHUNK_SIZE));
					}
					break;
				case 1:
					container = new BitmapContainer();
					final int stride = 2 + random.nextInt(6);
					for (int value = random.nextInt(stride); value < CHUNK_SIZE; value += stride) {
						container.add((char) value);
					}
					break;
				case 2:
					final int start = random.nextInt(CHUNK_SIZE / 2);
					container = new RunContainer().iadd(start, start + 1 + random.nextInt(CHUNK_SIZE / 2));
					break;
				case 3:
					container = RunContainer.full();
					break;
				default:
					continue;
			}
			bitmap.highLowContainer.append((char) key, container);
		}
		return bitmap;
	}

	/**
	 * Includes the replay seed, operation form, bound and both exact bounded input descriptions in
	 * every assertion, including assertions wrapping operations that may throw before comparison.
	 */
	private static String context(
		final PersistentRoaringBitmap x1, final PersistentRoaringBitmap x2, final long rangeEnd,
		final boolean staticForm, final String label) {
		return "seed=" + SEED + ", " + label + ", staticForm=" + staticForm + ", rangeEnd=" + rangeEnd
			+ ", x1=" + describe(x1) + ", x2=" + describe(x2);
	}

	/**
	 * Describes these inputs without expanding full chunks: arrays list their values, runs give their
	 * inclusive endpoints, and bitmap progressions give their endpoints and stride.
	 */
	private static String describe(final PersistentRoaringBitmap bitmap) {
		final StringBuilder description = new StringBuilder("{");
		for (int i = 0; i < bitmap.getContainerCount(); i++) {
			final Container container = bitmap.getContainerAtIndex(i);
			if (i > 0) {
				description.append(", ");
			}
			description.append((int) bitmap.getKeyAtIndex(i)).append(':');
			if (container instanceof ArrayContainer) {
				description.append("array").append(container);
			} else {
				description.append(container instanceof BitmapContainer ? "bitmap" : "run")
					.append('[').append((int) container.first()).append("..").append((int) container.last());
				if (container instanceof BitmapContainer) {
					description.append(" step ").append(container.select(1) - container.first());
				}
				description.append(']');
			}
		}
		return description.append('}').toString();
	}
}
