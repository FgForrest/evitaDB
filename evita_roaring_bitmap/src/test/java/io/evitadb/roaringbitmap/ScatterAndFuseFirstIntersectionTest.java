package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the array-operand write paths of {@link BitmapContainer} and its fuse-first intersection policy.
 *
 * `ilazyor(ArrayContainer)`, `ior(ArrayContainer)`, `or(ArrayContainer)` and `loadData(ArrayContainer)` set
 * one bit per value through two shared helpers, `scatterInto` and `scatterIntoCounting`. A word-batched form
 * of those helpers was written and then withdrawn after it cost 7.3% end to end on the workload it was meant to
 * speed up, so the helpers must stay bit-for-bit equivalent to the plain per-value loops, including the
 * cardinality bookkeeping. The bulk of this class is a differential test against exactly those loops, kept
 * here as private reference implementations; whichever form the helpers take next, the reference is what
 * they must reproduce.
 *
 * Three things the differential alone would not reach are checked separately: the value layouts a scatter
 * has to get right ({@link Shapes} - a single word, both sides of a word boundary, the last word of the
 * chunk), that a lazy union leaves the cardinality at `-1` for {@link BitmapContainer#repairAfterLazy()} to
 * resolve ({@link Laziness}), and that a union which fills the chunk still promotes to a full
 * {@link RunContainer} ({@link Saturation}) - the branch that reads the cardinality the counting helper
 * produced.
 *
 * {@link BitmapIntersectionPolicy} covers a separate change to the same class: `and(BitmapContainer)`,
 * `iand(BitmapContainer)` and `iandNot(BitmapContainer)` run the fused kernel first and demote afterwards,
 * instead of counting the result before deciding which container to build. The answer must not move, so the
 * group pins the container type and the values on both sides of the demotion threshold against a plain
 * word-wise reference loop.
 */
@DisplayName("Array scatter helpers and fuse-first intersection match the per-value forms")
public class ScatterAndFuseFirstIntersectionTest {
	/**
	 * Array lengths swept by the differential. Small values dominate the production workload (median 4), the
	 * middle of the range crosses several words, and `4096` is the largest cardinality an
	 * {@link ArrayContainer} can canonically hold.
	 */
	private static final int[] LENGTHS = {0, 1, 2, 3, 4, 7, 16, 64, 1000, 4096};
	/**
	 * Seeds drawn per length and per generator, because one draw can miss a word layout the loop branches on.
	 */
	private static final int SEEDS = 8;
	/**
	 * Numbers of bits pre-set in the receiving bitmap, so the cardinality delta is exercised against an empty
	 * receiver, a sparse one and a dense one - the last two overlapping the operand often enough that many
	 * writes set no new bit at all.
	 */
	private static final int[] RECEIVER_FILLS = {0, 100, 5000, 40000};

	@Nested
	@DisplayName("the helpers compute what the inline per-value loop computed")
	class Differential {

		@Test
		@DisplayName("ilazyor sets exactly the same words")
		void ilazyorMatchesReference() {
			forEachCase((receiver, operand) -> {
				final BitmapContainer expected = receiver.clone();
				referenceIlazyor(expected, operand);

				final BitmapContainer actual = receiver.clone();
				actual.ilazyor(operand);

				assertArrayEquals(expected.bitmap, actual.bitmap, describe(receiver, operand));
				assertEquals(expected.cardinality, actual.cardinality, describe(receiver, operand));
			});
		}

		@Test
		@DisplayName("ior sets the same words and keeps the same cardinality")
		void iorMatchesReference() {
			forEachCase((receiver, operand) -> {
				final BitmapContainer expected = receiver.clone();
				referenceIor(expected, operand);

				final BitmapContainer actual = receiver.clone();
				final BitmapContainer returned = actual.ior(operand);

				assertArrayEquals(expected.bitmap, actual.bitmap, describe(receiver, operand));
				assertEquals(expected.cardinality, actual.cardinality, describe(receiver, operand));
				assertEquals(cardinalityOf(actual.bitmap), actual.cardinality, describe(receiver, operand));
				assertSame(actual, returned, describe(receiver, operand));
			});
		}

		@Test
		@DisplayName("or produces the same container and leaves the receiver alone")
		void orMatchesReference() {
			forEachCase((receiver, operand) -> {
				final BitmapContainer expected = referenceOr(receiver, operand);

				final long[] receiverBefore = Arrays.copyOf(receiver.bitmap, receiver.bitmap.length);
				final int receiverCardinalityBefore = receiver.cardinality;
				final Container actual = receiver.or(operand);

				assertArrayEquals(receiverBefore, receiver.bitmap, describe(receiver, operand));
				assertEquals(receiverCardinalityBefore, receiver.cardinality, describe(receiver, operand));
				assertEquals(expected.cardinality, actual.getCardinality(), describe(receiver, operand));
				if (expected.cardinality == BitmapContainer.MAX_CAPACITY) {
					assertInstanceOf(RunContainer.class, actual, describe(receiver, operand));
				} else {
					final BitmapContainer actualBitmap = assertInstanceOf(
						BitmapContainer.class, actual, describe(receiver, operand));
					assertArrayEquals(expected.bitmap, actualBitmap.bitmap, describe(receiver, operand));
				}
			});
		}

		@Test
		@DisplayName("toBitmapContainer promotes to the same words")
		void toBitmapContainerMatchesReference() {
			forEachCase((receiver, operand) -> {
				final BitmapContainer expected = new BitmapContainer();
				referenceLoadData(expected, operand);

				final BitmapContainer actual = operand.toBitmapContainer();

				assertArrayEquals(expected.bitmap, actual.bitmap, describe(receiver, operand));
				assertEquals(expected.cardinality, actual.cardinality, describe(receiver, operand));
				assertEquals(operand.cardinality, cardinalityOf(actual.bitmap), describe(receiver, operand));
			});
		}
	}

	@Nested
	@DisplayName("the value layouts a scatter has to get right")
	class Shapes {

		@Test
		@DisplayName("values sharing one word, straddling a boundary, or sitting in the last word")
		void handPickedLayoutsMatchReference() {
			for (final char[] values : layouts()) {
				final ArrayContainer operand = new ArrayContainer(values);
				for (final int fill : RECEIVER_FILLS) {
					final BitmapContainer receiver = bitmapWith(new Random(fill), fill);

					final BitmapContainer expectedLazy = receiver.clone();
					referenceIlazyor(expectedLazy, operand);
					final BitmapContainer actualLazy = receiver.clone();
					actualLazy.ilazyor(operand);
					assertArrayEquals(expectedLazy.bitmap, actualLazy.bitmap, Arrays.toString(values));

					final BitmapContainer expectedIor = receiver.clone();
					referenceIor(expectedIor, operand);
					final BitmapContainer actualIor = receiver.clone();
					actualIor.ior(operand);
					assertArrayEquals(expectedIor.bitmap, actualIor.bitmap, Arrays.toString(values));
					assertEquals(expectedIor.cardinality, actualIor.cardinality, Arrays.toString(values));

					final BitmapContainer expectedLoad = new BitmapContainer();
					referenceLoadData(expectedLoad, operand);
					final BitmapContainer actualLoad = operand.toBitmapContainer();
					assertArrayEquals(expectedLoad.bitmap, actualLoad.bitmap, Arrays.toString(values));
					assertEquals(expectedLoad.cardinality, actualLoad.cardinality, Arrays.toString(values));
				}
			}
		}

		@Test
		@DisplayName("an operand confined to a single word touches only that word")
		void singleWordOperandTouchesOneWord() {
			final ArrayContainer operand = new ArrayContainer(new char[]{192, 200, 231, 255});
			final BitmapContainer receiver = new BitmapContainer();

			receiver.ior(operand);

			assertEquals(4, receiver.cardinality);
			for (int word = 0; word < receiver.bitmap.length; ++word) {
				if (word == 3) {
					assertEquals(4, Long.bitCount(receiver.bitmap[word]));
				} else {
					assertEquals(0L, receiver.bitmap[word], "word " + word);
				}
			}
		}
	}

	@Nested
	@DisplayName("the lazy union stays lazy")
	class Laziness {

		@Test
		@DisplayName("ilazyor leaves the cardinality at -1 until repairAfterLazy")
		void cardinalityStaysUnknownUntilRepaired() {
			for (final int length : LENGTHS) {
				final ArrayContainer operand = new ArrayContainer(spread(new Random(length), length));
				for (final int fill : RECEIVER_FILLS) {
					final BitmapContainer receiver = bitmapWith(new Random(fill), fill);
					final int expectedCardinality = cardinalityOf(
						referenceOr(receiver, operand).bitmap);

					final Container lazy = receiver.ilazyor(operand);

					assertInstanceOf(BitmapContainer.class, lazy);
					assertEquals(-1, ((BitmapContainer) lazy).cardinality);
					assertEquals(-1, lazy.getCardinality());

					final Container repaired = lazy.repairAfterLazy();

					assertEquals(expectedCardinality, repaired.getCardinality());
					if (expectedCardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
						assertInstanceOf(ArrayContainer.class, repaired);
					} else {
						assertInstanceOf(BitmapContainer.class, repaired);
					}
				}
			}
		}

		@Test
		@DisplayName("an empty operand still invalidates the cardinality")
		void emptyOperandStillInvalidates() {
			final BitmapContainer receiver = bitmapWith(new Random(7), 5000);
			final long[] before = Arrays.copyOf(receiver.bitmap, receiver.bitmap.length);
			final int cardinalityBefore = receiver.cardinality;

			final Container lazy = receiver.ilazyor(new ArrayContainer(new char[0]));

			assertEquals(-1, ((BitmapContainer) lazy).cardinality);
			assertArrayEquals(before, receiver.bitmap);
			assertEquals(cardinalityBefore, receiver.repairAfterLazy().getCardinality());
		}
	}

	@Nested
	@DisplayName("a saturating union still promotes")
	class Saturation {

		@Test
		@DisplayName("or over the last missing values returns the full run container")
		void orFillingTheChunkPromotes() {
			final char[] missing = {0, 1, 63, 64, 4095, 32768, 65534, 65535};
			final BitmapContainer receiver = fullExcept(missing);
			assertEquals(BitmapContainer.MAX_CAPACITY - missing.length, receiver.cardinality);

			final Container result = receiver.or(new ArrayContainer(missing.clone()));

			assertInstanceOf(RunContainer.class, result);
			assertEquals(BitmapContainer.MAX_CAPACITY, result.getCardinality());
		}

		@Test
		@DisplayName("ior over the last missing values reaches the full cardinality")
		void iorFillingTheChunkCountsEveryBit() {
			final char[] missing = {0, 1, 63, 64, 4095, 32768, 65534, 65535};
			final BitmapContainer receiver = fullExcept(missing);

			final BitmapContainer result = receiver.ior(new ArrayContainer(missing.clone()));

			assertEquals(BitmapContainer.MAX_CAPACITY, result.cardinality);
			assertTrue(result.isFull());
		}
	}

	@Nested
	@DisplayName("bitmap-by-bitmap intersections fuse first and demote afterwards")
	class BitmapIntersectionPolicy {

		@Test
		@DisplayName("a dense and() stays a bitmap container and leaves both operands alone")
		void denseAndStaysABitmap() {
			final BitmapContainer left = new BitmapContainer(0, 40000);
			final BitmapContainer right = new BitmapContainer(10000, BitmapContainer.MAX_CAPACITY);
			final long[] expected = intersect(left.bitmap, right.bitmap);

			final Container result = left.and(right);

			final BitmapContainer dense = assertInstanceOf(BitmapContainer.class, result);
			assertArrayEquals(expected, dense.bitmap);
			assertEquals(30000, dense.cardinality);
			assertEquals(30000, cardinalityOf(dense.bitmap));
			assertEquals(40000, left.cardinality);
			assertEquals(BitmapContainer.MAX_CAPACITY - 10000, right.cardinality);
		}

		@Test
		@DisplayName("a sparse and() demotes to the array container the reference describes")
		void sparseAndDemotes() {
			final BitmapContainer left = new BitmapContainer(0, 5000);
			final BitmapContainer right = new BitmapContainer(4990, 20000);
			final long[] expected = intersect(left.bitmap, right.bitmap);

			final Container result = left.and(right);

			final ArrayContainer sparse = assertInstanceOf(ArrayContainer.class, result);
			assertEquals(10, sparse.cardinality);
			assertArrayEquals(valuesOf(expected), Arrays.copyOf(sparse.content, sparse.cardinality));
		}

		@Test
		@DisplayName("and() switches container at exactly DEFAULT_MAX_SIZE")
		void andSwitchesAtTheThreshold() {
			final BitmapContainer everything = new BitmapContainer(0, BitmapContainer.MAX_CAPACITY);

			final Container atThreshold = new BitmapContainer(0, ArrayContainer.DEFAULT_MAX_SIZE).and(everything);
			final Container overThreshold =
				new BitmapContainer(0, ArrayContainer.DEFAULT_MAX_SIZE + 1).and(everything);

			assertInstanceOf(ArrayContainer.class, atThreshold);
			assertEquals(ArrayContainer.DEFAULT_MAX_SIZE, atThreshold.getCardinality());
			assertInstanceOf(BitmapContainer.class, overThreshold);
			assertEquals(ArrayContainer.DEFAULT_MAX_SIZE + 1, overThreshold.getCardinality());
		}

		@Test
		@DisplayName("a disjoint and() produces an empty container")
		void disjointAndIsEmpty() {
			final Container result = new BitmapContainer(0, 1000).and(new BitmapContainer(2000, 3000));

			assertInstanceOf(ArrayContainer.class, result);
			assertEquals(0, result.getCardinality());
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("a dense iand() returns the receiver, intersected and counted")
		void denseIandReturnsTheReceiver() {
			final BitmapContainer receiver = new BitmapContainer(0, 40000);
			final BitmapContainer other = new BitmapContainer(10000, BitmapContainer.MAX_CAPACITY);
			final long[] expected = intersect(receiver.bitmap, other.bitmap);

			final Container result = receiver.iand(other);

			assertSame(receiver, result);
			assertArrayEquals(expected, receiver.bitmap);
			assertEquals(30000, receiver.cardinality);
		}

		@Test
		@DisplayName("a sparse iand() demotes, and the receiver it consumed holds the intersection")
		void sparseIandDemotes() {
			final BitmapContainer receiver = new BitmapContainer(0, 5000);
			final BitmapContainer other = new BitmapContainer(4990, 20000);
			final long[] expected = intersect(receiver.bitmap, other.bitmap);

			final Container result = receiver.iand(other);

			final ArrayContainer sparse = assertInstanceOf(ArrayContainer.class, result);
			assertEquals(10, sparse.cardinality);
			assertArrayEquals(valuesOf(expected), Arrays.copyOf(sparse.content, sparse.cardinality));
			assertArrayEquals(expected, receiver.bitmap);
			assertEquals(10, receiver.cardinality);
		}

		@Test
		@DisplayName("a dense iandNot() returns the receiver, subtracted and counted")
		void denseIandNotReturnsTheReceiver() {
			final BitmapContainer receiver = new BitmapContainer(0, 60000);
			final BitmapContainer other = new BitmapContainer(0, 1000);
			final long[] expected = subtract(receiver.bitmap, other.bitmap);

			final Container result = receiver.iandNot(other);

			assertSame(receiver, result);
			assertArrayEquals(expected, receiver.bitmap);
			assertEquals(59000, receiver.cardinality);
		}

		@Test
		@DisplayName("a sparse iandNot() demotes to the array container the reference describes")
		void sparseIandNotDemotes() {
			final BitmapContainer receiver = new BitmapContainer(0, 5000);
			final BitmapContainer other = new BitmapContainer(10, BitmapContainer.MAX_CAPACITY);
			final long[] expected = subtract(receiver.bitmap, other.bitmap);

			final Container result = receiver.iandNot(other);

			final ArrayContainer sparse = assertInstanceOf(ArrayContainer.class, result);
			assertEquals(10, sparse.cardinality);
			assertArrayEquals(valuesOf(expected), Arrays.copyOf(sparse.content, sparse.cardinality));
		}

		@Test
		@DisplayName("and(), iand() and iandNot() agree with a plain word loop across densities")
		void randomisedIntersectionsMatchTheReference() {
			for (final int leftFill : RECEIVER_FILLS) {
				for (final int rightFill : RECEIVER_FILLS) {
					for (int seed = 0; seed < SEEDS; ++seed) {
						final BitmapContainer left = bitmapWith(new Random(seed * 7L + leftFill), leftFill);
						final BitmapContainer right = bitmapWith(new Random(seed * 11L + rightFill), rightFill);
						final String label = leftFill + "/" + rightFill + "/" + seed;

						final long[] expectedAnd = intersect(left.bitmap, right.bitmap);
						final Container and = left.and(right);
						assertEquals(cardinalityOf(expectedAnd), and.getCardinality(), label);
						assertArrayEquals(valuesOf(expectedAnd), valuesOf(and), label);

						final Container iand = left.clone().iand(right);
						assertEquals(cardinalityOf(expectedAnd), iand.getCardinality(), label);
						assertArrayEquals(valuesOf(expectedAnd), valuesOf(iand), label);

						final long[] expectedAndNot = subtract(left.bitmap, right.bitmap);
						final Container iandNot = left.clone().iandNot(right);
						assertEquals(cardinalityOf(expectedAndNot), iandNot.getCardinality(), label);
						assertArrayEquals(valuesOf(expectedAndNot), valuesOf(iandNot), label);
					}
				}
			}
		}
	}

	/**
	 * Word-wise `a AND b`, computed by the most obvious loop there is, as the reference the fused kernel is
	 * compared against.
	 *
	 * @param a first word array
	 * @param b second word array
	 * @return the intersected words
	 */
	@Nonnull
	private static long[] intersect(@Nonnull final long[] a, @Nonnull final long[] b) {
		final long[] result = new long[a.length];
		for (int i = 0; i < a.length; ++i) {
			result[i] = a[i] & b[i];
		}
		return result;
	}

	/**
	 * Word-wise `a AND NOT b`, the reference twin of {@link #intersect}.
	 *
	 * @param a first word array
	 * @param b word array to subtract
	 * @return the difference words
	 */
	@Nonnull
	private static long[] subtract(@Nonnull final long[] a, @Nonnull final long[] b) {
		final long[] result = new long[a.length];
		for (int i = 0; i < a.length; ++i) {
			result[i] = a[i] & ~b[i];
		}
		return result;
	}

	/**
	 * Decodes the set bits of `words` into ascending values, independently of any extraction kernel.
	 *
	 * @param words word array to decode
	 * @return the values the words represent, in ascending order
	 */
	@Nonnull
	private static char[] valuesOf(@Nonnull final long[] words) {
		final char[] result = new char[cardinalityOf(words)];
		int index = 0;
		for (int w = 0; w < words.length; ++w) {
			long word = words[w];
			while (word != 0L) {
				result[index++] = (char) (w * 64 + Long.numberOfTrailingZeros(word));
				word &= word - 1;
			}
		}
		return result;
	}

	/**
	 * Reads a container's values out through its public iterator, so a bitmap result and an array result can
	 * be compared on equal terms.
	 *
	 * @param container container to read
	 * @return its values in ascending order
	 */
	@Nonnull
	private static char[] valuesOf(@Nonnull final Container container) {
		final char[] result = new char[container.getCardinality()];
		final CharIterator iterator = container.getCharIterator();
		int index = 0;
		while (iterator.hasNext()) {
			result[index++] = iterator.next();
		}
		return result;
	}

	/**
	 * Runs `assertion` over the full cross product of value generators, lengths, seeds and receiver fills.
	 *
	 * @param assertion check to run for one receiver/operand pair
	 */
	private static void forEachCase(@Nonnull final ScatterCase assertion) {
		for (final int length : LENGTHS) {
			for (int seed = 0; seed < SEEDS; ++seed) {
				final char[] spreadValues = spread(new Random(seed * 31L + length), length);
				final char[] clusteredValues = clustered(new Random(seed * 17L + length), length);
				for (final int fill : RECEIVER_FILLS) {
					final BitmapContainer receiver = bitmapWith(new Random(fill * 13L + seed), fill);
					assertion.check(receiver, new ArrayContainer(spreadValues.clone()));
					assertion.check(receiver, new ArrayContainer(clusteredValues.clone()));
				}
			}
		}
	}

	/**
	 * Hand-picked value layouts: the empty array, single values at both ends of the chunk, both sides of
	 * several word boundaries, a whole word, an operand confined to one word, and operands whose last word
	 * holds more than one value - the case a missing final flush loses.
	 *
	 * @return the layouts, each already sorted and distinct
	 */
	@Nonnull
	private static char[][] layouts() {
		final char[] wholeWord = new char[64];
		for (int i = 0; i < 64; ++i) {
			wholeWord[i] = (char) (128 + i);
		}
		final char[] lastWord = new char[64];
		for (int i = 0; i < 64; ++i) {
			lastWord[i] = (char) (65472 + i);
		}
		return new char[][]{
			{},
			{0},
			{65535},
			{63},
			{64},
			{63, 64},
			{63, 64, 65},
			{0, 63, 64, 127, 128, 65471, 65472, 65535},
			{192, 200, 231, 255},
			{65530, 65531, 65532, 65533, 65534, 65535},
			wholeWord,
			lastWord
		};
	}

	/**
	 * Draws `length` distinct values spread uniformly over the whole 16-bit chunk.
	 *
	 * @param random source of randomness
	 * @param length number of values to draw
	 * @return the values in ascending order
	 */
	@Nonnull
	private static char[] spread(@Nonnull final Random random, final int length) {
		final TreeSet<Character> values = new TreeSet<>();
		while (values.size() < length) {
			values.add((char) random.nextInt(BitmapContainer.MAX_CAPACITY));
		}
		return toArray(values);
	}

	/**
	 * Draws `length` distinct values in tight clusters, so that long runs of them share one 64-bit word -
	 * the layout a word-aware scatter would branch on, and the one a uniform draw almost never produces at
	 * small lengths.
	 *
	 * @param random source of randomness
	 * @param length number of values to draw
	 * @return the values in ascending order
	 */
	@Nonnull
	private static char[] clustered(@Nonnull final Random random, final int length) {
		final TreeSet<Character> values = new TreeSet<>();
		while (values.size() < length) {
			final int word = random.nextInt(BitmapContainer.MAX_CAPACITY / 64);
			final int inWord = 1 + random.nextInt(64);
			for (int i = 0; i < inWord && values.size() < length; ++i) {
				values.add((char) (word * 64 + random.nextInt(64)));
			}
		}
		return toArray(values);
	}

	/**
	 * Unboxes an ordered set of values into the `char[]` an {@link ArrayContainer} wraps.
	 *
	 * @param values ordered values
	 * @return the same values as a primitive array
	 */
	@Nonnull
	private static char[] toArray(@Nonnull final TreeSet<Character> values) {
		final char[] result = new char[values.size()];
		int index = 0;
		for (final Character value : values) {
			result[index++] = value;
		}
		return result;
	}

	/**
	 * Builds a bitmap container holding `bits` randomly chosen values (fewer, if a draw repeats).
	 *
	 * @param random source of randomness
	 * @param bits   number of draws
	 * @return the populated container, with its cardinality current
	 */
	@Nonnull
	private static BitmapContainer bitmapWith(@Nonnull final Random random, final int bits) {
		final BitmapContainer container = new BitmapContainer();
		for (int i = 0; i < bits; ++i) {
			container.add((char) random.nextInt(BitmapContainer.MAX_CAPACITY));
		}
		return container;
	}

	/**
	 * Builds a bitmap container in which every value is set except those in `missing`.
	 *
	 * @param missing values to leave clear
	 * @return the nearly saturated container, with its cardinality current
	 */
	@Nonnull
	private static BitmapContainer fullExcept(@Nonnull final char[] missing) {
		final BitmapContainer container = new BitmapContainer(0, BitmapContainer.MAX_CAPACITY);
		for (final char value : missing) {
			container.bitmap[value >>> 6] &= ~(1L << value);
			container.cardinality--;
		}
		return container;
	}

	/**
	 * Counts the set bits of `words` independently of any container's bookkeeping.
	 *
	 * @param words word array to count
	 * @return the number of set bits
	 */
	private static int cardinalityOf(@Nonnull final long[] words) {
		int result = 0;
		for (final long word : words) {
			result += Long.bitCount(word);
		}
		return result;
	}

	/**
	 * Names the failing case, since the sweep runs thousands of them.
	 *
	 * @param receiver receiving container
	 * @param operand  array operand
	 * @return a short description of the pair
	 */
	@Nonnull
	private static String describe(@Nonnull final BitmapContainer receiver, @Nonnull final ArrayContainer operand) {
		return "receiver cardinality " + receiver.cardinality + ", operand cardinality " + operand.cardinality;
	}

	/**
	 * Upstream's inline per-value loop of `ilazyor(ArrayContainer)`, kept verbatim as the reference the
	 * differential compares against.
	 *
	 * @param target   container to mutate
	 * @param operand values to add
	 */
	private static void referenceIlazyor(@Nonnull final BitmapContainer target, @Nonnull final ArrayContainer operand) {
		target.cardinality = -1;
		final int c = operand.cardinality;
		for (int k = 0; k < c; ++k) {
			final char v = operand.content[k];
			final int i = v >>> 6;
			target.bitmap[i] |= (1L << v);
		}
	}

	/**
	 * Upstream's inline per-value loop of `ior(ArrayContainer)`, including its branchless
	 * `(before - after) >>> 63` cardinality accounting.
	 *
	 * @param target  container to mutate
	 * @param operand values to add
	 */
	private static void referenceIor(@Nonnull final BitmapContainer target, @Nonnull final ArrayContainer operand) {
		final int c = operand.cardinality;
		for (int k = 0; k < c; ++k) {
			final int i = operand.content[k] >>> 6;
			final long before = target.bitmap[i];
			final long after = before | (1L << operand.content[k]);
			target.bitmap[i] = after;
			target.cardinality += (int) ((before - after) >>> 63);
		}
	}

	/**
	 * Upstream's inline per-value loop of `or(ArrayContainer)`: a clone of the receiver, then the same
	 * accounting {@link #referenceIor} performs. The promotion to a full {@link RunContainer} is
	 * deliberately left out so the caller can inspect the words.
	 *
	 * @param source  container to copy
	 * @param operand values to add
	 * @return the union as a bitmap container, never promoted
	 */
	@Nonnull
	private static BitmapContainer referenceOr(
		@Nonnull final BitmapContainer source, @Nonnull final ArrayContainer operand) {
		final BitmapContainer answer = source.clone();
		referenceIor(answer, operand);
		return answer;
	}

	/**
	 * Upstream's inline per-value loop of `loadData(ArrayContainer)`.
	 *
	 * @param target  container to populate
	 * @param operand source values
	 */
	private static void referenceLoadData(
		@Nonnull final BitmapContainer target, @Nonnull final ArrayContainer operand) {
		target.cardinality = operand.cardinality;
		for (int k = 0; k < operand.cardinality; ++k) {
			final char x = operand.content[k];
			target.bitmap[x / 64] |= (1L << x);
		}
	}

	/**
	 * One differential check, applied to every receiver/operand pair the sweep produces.
	 */
	@FunctionalInterface
	private interface ScatterCase {

		/**
		 * Asserts the helper-backed result against the reference for one pair.
		 *
		 * @param receiver receiving container, which the check must not leave mutated
		 * @param operand  array operand
		 */
		void check(@Nonnull BitmapContainer receiver, @Nonnull ArrayContainer operand);
	}
}
