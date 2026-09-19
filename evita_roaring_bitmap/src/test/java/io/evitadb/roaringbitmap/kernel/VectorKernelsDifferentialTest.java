package io.evitadb.roaringbitmap.kernel;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test: every kernel of {@link VectorBitmapKernels} must return exactly what
 * {@link ScalarBitmapKernels} returns, on every input shape the dense containers can present.
 *
 * The scalar implementation is the definition of correct here — it is the code the module ran before the
 * kernels were extracted and the oracle {@link VectorKernels}' own startup self-test uses. This test goes
 * wider than that self-test: several seeds, the full density range from empty to saturated, single bits
 * parked on word boundaries, and ranges chosen to land inside one word, on a word boundary and across many.
 *
 * Both implementations are instantiated **explicitly**. Going through {@link VectorKernels#BITMAP} would
 * mean testing whichever one the provider happened to select, which on a JVM without the incubator module is
 * the scalar one compared against itself — a test that cannot fail. When the vector implementation genuinely
 * cannot be loaded the whole class is skipped with a message that says so, rather than passing silently.
 */
@DisplayName("Vector bitmap kernels agree with the scalar reference")
public class VectorKernelsDifferentialTest {
	/**
	 * Word count of a dense container.
	 */
	private static final int WORDS = 1024;
	/**
	 * Seeds the samples are drawn from. Several, because a single seed can hide a lane-indexing mistake that
	 * only shows up for a particular bit pattern.
	 */
	private static final long[] SEEDS = {1L, 42L, 987654321L, -13L};
	/**
	 * Fractions of set bits the samples cover: empty, sparse, the density at which a repaired union bitmap
	 * demotes back to a value list, half, nearly saturated, saturated.
	 */
	private static final double[] DENSITIES = {0.0d, 0.01d, 0.0625d, 0.5d, 0.99d, 1.0d};

	/**
	 * Names of the four fused kernels, in the order {@link #applyFused} dispatches them.
	 */
	private static final String[] FUSED_NAMES = {"and", "or", "xor", "andNot"};
	/**
	 * Names of the three two-operand extraction kernels, in the order {@link #applyExtract} dispatches them.
	 */
	private static final String[] EXTRACT_NAMES = {"extractAnd", "extractAndNot", "extractXor"};
	/**
	 * The reference implementation.
	 */
	private static final BitmapKernels SCALAR = ScalarBitmapKernels.INSTANCE;
	/**
	 * The implementation under test, loaded reflectively so that its absence skips rather than fails.
	 */
	private static BitmapKernels vector;
	/**
	 * The samples, one per seed and density combination, plus the boundary-bit shapes.
	 */
	private static long[][] samples;

	@BeforeAll
	static void loadVectorKernels() {
		vector = loadVectorImplementation();
		Assumptions.assumeTrue(
			vector != null,
			"VectorBitmapKernels could not be loaded - the JVM was most likely started without " +
				"`--add-modules jdk.incubator.vector`, so there is no vector implementation to compare against."
		);
		samples = buildSamples();
	}

	@Nested
	@DisplayName("whole-container kernels")
	class WholeContainer {

		@Test
		@DisplayName("cardinality matches on every sample")
		void shouldCountCardinalityLikeScalar() {
			for (int i = 0; i < samples.length; i++) {
				assertEquals(
					SCALAR.cardinality(samples[i]), vector.cardinality(samples[i]),
					() -> "cardinality"
				);
			}
		}

		@Test
		@DisplayName("the four counting kernels answer identically, because they are the scalar ones")
		void shouldCountCombinationsLikeScalar() {
			// these four are NOT vectorized: JMH put the lane version at 0.83-0.90x of the auto-vectorized
			// scalar reduction, so `VectorBitmapKernels` delegates them. The assertions below are therefore
			// trivially true today, and they are kept on purpose - they are what would catch a future
			// vector formulation of these kernels that got a lane wrong
			for (int i = 0; i < samples.length; i++) {
				for (int j = 0; j < samples.length; j++) {
					final long[] a = samples[i];
					final long[] b = samples[j];
					final int first = i;
					final int second = j;
					assertEquals(
						SCALAR.andCardinality(a, b), vector.andCardinality(a, b),
						() -> "andCardinality of samples " + first + " and " + second
					);
					assertEquals(
						SCALAR.orCardinality(a, b), vector.orCardinality(a, b),
						() -> "orCardinality of samples " + first + " and " + second
					);
					assertEquals(
						SCALAR.xorCardinality(a, b), vector.xorCardinality(a, b),
						() -> "xorCardinality of samples " + first + " and " + second
					);
					assertEquals(
						SCALAR.andNotCardinality(a, b), vector.andNotCardinality(a, b),
						() -> "andNotCardinality of samples " + first + " and " + second
					);
				}
			}
		}

		@Test
		@DisplayName("the four fused kernels write the same words and return the same count")
		void shouldFuseLikeScalar() {
			final long[] expected = new long[WORDS];
			final long[] actual = new long[WORDS];
			for (int i = 0; i < samples.length; i++) {
				for (int j = 0; j < samples.length; j++) {
					final long[] a = samples[i];
					final long[] b = samples[j];
					final String context = " of samples " + i + " and " + j;
					assertFusedAgree("and" + context, SCALAR.and(a, b, expected), expected, vector.and(a, b, actual), actual);
					assertFusedAgree("or" + context, SCALAR.or(a, b, expected), expected, vector.or(a, b, actual), actual);
					assertFusedAgree("xor" + context, SCALAR.xor(a, b, expected), expected, vector.xor(a, b, actual), actual);
					assertFusedAgree(
						"andNot" + context,
						SCALAR.andNot(a, b, expected), expected, vector.andNot(a, b, actual), actual
					);
				}
			}
		}

		@Test
		@DisplayName("the fused kernels behave identically when the destination is the first operand")
		void shouldFuseInPlaceLikeScalar() {
			for (int i = 0; i < samples.length; i++) {
				for (int j = 0; j < samples.length; j++) {
					for (int operation = 0; operation < FUSED_NAMES.length; operation++) {
						final long[] expected = samples[i].clone();
						final long[] actual = samples[i].clone();
						final long[] b = samples[j];
						assertFusedAgree(
							FUSED_NAMES[operation] + " into its first operand, samples " + i + " and " + j,
							applyFused(SCALAR, operation, expected, b, expected), expected,
							applyFused(vector, operation, actual, b, actual), actual
						);
					}
				}
			}
		}

		@Test
		@DisplayName("the fused kernels behave identically when both operands and the destination are one array")
		void shouldFuseFullyAliasedLikeScalar() {
			for (int i = 0; i < samples.length; i++) {
				for (int operation = 0; operation < FUSED_NAMES.length; operation++) {
					final long[] expected = samples[i].clone();
					final long[] actual = samples[i].clone();
					assertFusedAgree(
						FUSED_NAMES[operation] + " fully aliased on sample " + i,
						applyFused(SCALAR, operation, expected, expected, expected), expected,
						applyFused(vector, operation, actual, actual, actual), actual
					);
				}
			}
		}

		@Test
		@DisplayName("a fully aliased operand yields the algebraic identities, not merely the same words")
		void shouldSatisfyTheSelfAliasedIdentities() {
			// the differential assertions above would pass even if both implementations were wrong in the
			// same way; these pin what the answers have to BE
			for (int i = 0; i < samples.length; i++) {
				final long[] original = samples[i];
				final long[] selfAnd = original.clone();
				assertEquals(SCALAR.cardinality(original), vector.and(selfAnd, selfAnd, selfAnd));
				assertArrayEquals(original, selfAnd, "a & a must reproduce a");

				final long[] selfOr = original.clone();
				assertEquals(SCALAR.cardinality(original), vector.or(selfOr, selfOr, selfOr));
				assertArrayEquals(original, selfOr, "a | a must reproduce a");

				final long[] selfAndNot = original.clone();
				assertEquals(0, vector.andNot(selfAndNot, selfAndNot, selfAndNot));
				assertArrayEquals(new long[WORDS], selfAndNot, "a & ~a must be empty");

				final long[] selfXor = original.clone();
				assertEquals(0, vector.xor(selfXor, selfXor, selfXor));
				assertArrayEquals(new long[WORDS], selfXor, "a ^ a must be empty");
			}
		}
	}

	@Nested
	@DisplayName("cardinalityInRange")
	class RangeCounting {

		@Test
		@DisplayName("matches on ranges that start and end inside a single word")
		void shouldCountSingleWordRangesLikeScalar() {
			for (int word = 0; word < WORDS; word += 97) {
				for (int start = 0; start < 64; start += 7) {
					for (int end = start; end <= 64; end += 5) {
						assertRangeAgrees(word * 64 + start, word * 64 + end);
					}
				}
			}
		}

		@Test
		@DisplayName("matches on word-aligned ranges, including the whole container")
		void shouldCountAlignedRangesLikeScalar() {
			for (int startWord = 0; startWord < WORDS; startWord += 53) {
				for (int endWord = startWord; endWord <= WORDS; endWord += 61) {
					assertRangeAgrees(startWord * 64, endWord * 64);
				}
			}
			assertRangeAgrees(0, WORDS * 64);
			assertRangeAgrees(0, 0);
			assertRangeAgrees(WORDS * 64, WORDS * 64);
		}

		@Test
		@DisplayName("matches on the boundary ranges a container can present")
		void shouldCountBoundaryRangesLikeScalar() {
			assertRangeAgrees(0, 0);
			assertRangeAgrees(7, 7);
			assertRangeAgrees(WORDS * 64, WORDS * 64);
			assertRangeAgrees(0, 1);
			assertRangeAgrees(0, 64);
			assertRangeAgrees(0, 65);
			assertRangeAgrees(63, 64);
			assertRangeAgrees(63, 65);
			assertRangeAgrees(64, 128);
			// `end == 65536` must never index word 1024, which is what the `(end - 1) / 64` end word buys
			assertRangeAgrees(65535, WORDS * 64);
			assertRangeAgrees(65472, WORDS * 64);
			assertRangeAgrees(0, WORDS * 64);
		}

		@Test
		@DisplayName("matches on randomly drawn ranges")
		void shouldCountRandomRangesLikeScalar() {
			final Random random = new Random(20260918L);
			for (int attempt = 0; attempt < 2000; attempt++) {
				final int first = random.nextInt(WORDS * 64 + 1);
				final int second = random.nextInt(WORDS * 64 + 1);
				assertRangeAgrees(Math.min(first, second), Math.max(first, second));
			}
		}

		/**
		 * Asserts that both implementations count the same bits in `[start, end)` on every sample.
		 *
		 * @param start first bit index (inclusive)
		 * @param end   bit index one past the last (exclusive)
		 */
		private void assertRangeAgrees(final int start, final int end) {
			for (int i = 0; i < samples.length; i++) {
				final int sample = i;
				assertEquals(
					SCALAR.cardinalityInRange(samples[i], start, end),
					vector.cardinalityInRange(samples[i], start, end),
					() -> "cardinalityInRange(" + start + ", " + end + ") of sample " + sample
				);
			}
		}
	}

	@Nested
	@DisplayName("extraction kernels")
	class Extraction {

		@Test
		@DisplayName("extract decodes the same positions as the scalar loop on every sample")
		void shouldExtractLikeScalar() {
			final char[] expected = new char[WORDS * 64];
			final char[] actual = new char[WORDS * 64];
			for (int i = 0; i < samples.length; i++) {
				final int sample = i;
				final int expectedCount = SCALAR.extract(samples[i], expected);
				final int actualCount = vector.extract(samples[i], actual);
				assertEquals(expectedCount, actualCount, () -> "count of extract on sample " + sample);
				assertArrayEquals(
					Arrays.copyOf(expected, expectedCount), Arrays.copyOf(actual, actualCount),
					() -> "values of extract on sample " + sample
				);
			}
		}

		@Test
		@DisplayName("extract returns the population count and writes nothing past it")
		void shouldExtractExactlyTheSetBits() {
			// the differential assertion above would pass if both implementations dropped the same bit;
			// this pins the answer against an independently computed count and against the bit positions
			// themselves
			final char[] out = new char[WORDS * 64];
			for (int i = 0; i < samples.length; i++) {
				Arrays.fill(out, (char) 0xFFFF);
				final int count = vector.extract(samples[i], out);
				assertEquals(SCALAR.cardinality(samples[i]), count, "extract must return the population count");
				int previous = -1;
				for (int v = 0; v < count; v++) {
					final int value = out[v];
					assertTrue(value > previous, "extracted values must ascend strictly");
					assertTrue(
						(samples[i][value >>> 6] & (1L << value)) != 0L,
						"every extracted value must name a bit that is actually set"
					);
					previous = value;
				}
				if (count < out.length) {
					assertEquals(0xFFFF, out[count], "extract must not write past the values it counted");
				}
			}
		}

		@Test
		@DisplayName("the int variant carries the write offset and the base through unchanged")
		void shouldExtractWithBaseLikeScalar() {
			final int[] expected = new int[WORDS * 64 + 8];
			final int[] actual = new int[WORDS * 64 + 8];
			final int[] bases = {0, 1 << 16, 7 << 16, 0xFFFF << 16};
			final int[] offsets = {0, 1, 5};
			for (int i = 0; i < samples.length; i++) {
				for (int b = 0; b < bases.length; b++) {
					for (int o = 0; o < offsets.length; o++) {
						final int sample = i;
						final int base = bases[b];
						final int offset = offsets[o];
						final int expectedCount = SCALAR.extract(samples[i], expected, offset, base);
						final int actualCount = vector.extract(samples[i], actual, offset, base);
						assertEquals(
							expectedCount, actualCount,
							() -> "count of extract(int[]) on sample " + sample + ", base " + base
						);
						assertArrayEquals(
							Arrays.copyOfRange(expected, offset, offset + expectedCount),
							Arrays.copyOfRange(actual, offset, offset + actualCount),
							() -> "values of extract(int[]) on sample " + sample + ", base " + base
								+ ", offset " + offset
						);
					}
				}
			}
		}

		@Test
		@DisplayName("the three two-operand extraction kernels match on every ordered pair of samples")
		void shouldExtractCombinationsLikeScalar() {
			final char[] expected = new char[WORDS * 64];
			final char[] actual = new char[WORDS * 64];
			for (int i = 0; i < samples.length; i++) {
				for (int j = 0; j < samples.length; j++) {
					for (int operation = 0; operation < EXTRACT_NAMES.length; operation++) {
						final String context = EXTRACT_NAMES[operation] + " of samples " + i + " and " + j;
						final int expectedCount = applyExtract(SCALAR, operation, samples[i], samples[j], expected);
						final int actualCount = applyExtract(vector, operation, samples[i], samples[j], actual);
						assertEquals(expectedCount, actualCount, () -> "count of " + context);
						assertArrayEquals(
							Arrays.copyOf(expected, expectedCount), Arrays.copyOf(actual, actualCount),
							() -> "values of " + context
						);
					}
				}
			}
		}

		@Test
		@DisplayName("the two-operand extraction kernels agree with their counting twins")
		void shouldExtractAsManyValuesAsTheCountingKernelsReport() {
			final char[] out = new char[WORDS * 64];
			for (int i = 0; i < samples.length; i++) {
				final long[] a = samples[i];
				for (int j = 0; j < samples.length; j++) {
					final long[] b = samples[j];
					assertEquals(SCALAR.andCardinality(a, b), vector.extractAnd(a, b, out), "extractAnd count");
					assertEquals(
						SCALAR.andNotCardinality(a, b), vector.extractAndNot(a, b, out), "extractAndNot count"
					);
					assertEquals(SCALAR.xorCardinality(a, b), vector.extractXor(a, b, out), "extractXor count");
				}
			}
		}
	}

	/**
	 * Invokes one of the four fused kernels by index, so that each aliasing shape is written once rather
	 * than four times.
	 *
	 * @param kernels   implementation to invoke
	 * @param operation index into {@link #FUSED_NAMES}
	 * @param a         first operand
	 * @param b         second operand
	 * @param out       destination
	 * @return the population count the kernel returned
	 */
	private static int applyFused(
		@Nonnull final BitmapKernels kernels,
		final int operation,
		@Nonnull final long[] a,
		@Nonnull final long[] b,
		@Nonnull final long[] out
	) {
		return switch (operation) {
			case 0 -> kernels.and(a, b, out);
			case 1 -> kernels.or(a, b, out);
			case 2 -> kernels.xor(a, b, out);
			case 3 -> kernels.andNot(a, b, out);
			default -> throw new IllegalArgumentException("Unknown fused kernel index: " + operation);
		};
	}

	/**
	 * Invokes one of the three two-operand extraction kernels by index.
	 *
	 * @param kernels   implementation to invoke
	 * @param operation index into {@link #EXTRACT_NAMES}
	 * @param a         first operand
	 * @param b         second operand
	 * @param out       destination
	 * @return the number of values the kernel wrote
	 */
	private static int applyExtract(
		@Nonnull final BitmapKernels kernels,
		final int operation,
		@Nonnull final long[] a,
		@Nonnull final long[] b,
		@Nonnull final char[] out
	) {
		return switch (operation) {
			case 0 -> kernels.extractAnd(a, b, out);
			case 1 -> kernels.extractAndNot(a, b, out);
			case 2 -> kernels.extractXor(a, b, out);
			default -> throw new IllegalArgumentException("Unknown extraction kernel index: " + operation);
		};
	}

	/**
	 * Asserts that a fused kernel produced the same count and the same words as the reference did.
	 *
	 * @param context       what is being compared, for the failure message
	 * @param expectedCount count the reference returned
	 * @param expectedWords words the reference wrote
	 * @param actualCount   count the vector kernel returned
	 * @param actualWords   words the vector kernel wrote
	 */
	private static void assertFusedAgree(
		@Nonnull final String context,
		final int expectedCount,
		@Nonnull final long[] expectedWords,
		final int actualCount,
		@Nonnull final long[] actualWords
	) {
		assertEquals(expectedCount, actualCount, () -> "count returned by " + context);
		assertArrayEquals(expectedWords, actualWords, () -> "words written by " + context);
	}

	/**
	 * Loads {@link VectorBitmapKernels} the way {@link VectorKernels} does, so that a JVM without the
	 * incubator module skips the test instead of failing to link it.
	 *
	 * @return the vector implementation, or `null` when it is not available on this JVM
	 */
	@Nullable
	private static BitmapKernels loadVectorImplementation() {
		try {
			final Class<?> implementation = Class.forName(
				"io.evitadb.roaringbitmap.kernel.VectorBitmapKernels",
				true,
				VectorKernelsDifferentialTest.class.getClassLoader()
			);
			return (BitmapKernels) implementation.getDeclaredConstructor().newInstance();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * Builds the sample set: one word array per seed and density combination, plus the shapes that park a
	 * single bit on a word boundary — the first and last bit of the container, and both sides of the first,
	 * a middle and the last word boundary.
	 *
	 * @return the samples, each {@link #WORDS} words long
	 */
	@Nonnull
	private static long[][] buildSamples() {
		// the last six park a single bit on the boundary between two vector lanes, for the 2-, 4- and
		// 8-lane shapes the API offers - a block-stepping kernel that mis-indexes a lane loses exactly one
		// of these and nothing else
		final int[] singleBits = {
			0, 63, 64, 65, 511 * 64 - 1, 511 * 64, WORDS * 64 - 65, WORDS * 64 - 1,
			2 * 64 - 1, 2 * 64, 4 * 64 - 1, 4 * 64, 8 * 64 - 1, 8 * 64
		};
		final long[][] built = new long[SEEDS.length * DENSITIES.length + singleBits.length + 1][];
		int next = 0;
		for (int s = 0; s < SEEDS.length; s++) {
			for (int d = 0; d < DENSITIES.length; d++) {
				built[next++] = randomWords(SEEDS[s], DENSITIES[d]);
			}
		}
		for (int b = 0; b < singleBits.length; b++) {
			final long[] single = new long[WORDS];
			single[singleBits[b] >>> 6] = 1L << singleBits[b];
			built[next++] = single;
		}
		built[next] = clusteredWords();
		return built;
	}

	/**
	 * A clustered sample: 64 bits inside a single 1024-value stretch and nothing anywhere else. This is the
	 * shape a repaired union bitmap actually has — a handful of values in one neighbourhood and 1008 empty
	 * words — and it is the shape the extraction kernels' empty-block skip exists for.
	 *
	 * @return the sample
	 */
	@Nonnull
	private static long[] clusteredWords() {
		final long[] words = new long[WORDS];
		final int firstBit = 4096;
		for (int i = 0; i < 64; i++) {
			final int bit = firstBit + i * 13;
			words[bit >>> 6] |= 1L << bit;
		}
		return words;
	}

	/**
	 * Draws a word array whose bits are set with the given probability.
	 *
	 * @param seed    seed of the pseudo-random generator
	 * @param density fraction of set bits, `0.0` for empty and `1.0` for saturated
	 * @return a freshly built word array
	 */
	@Nonnull
	private static long[] randomWords(final long seed, final double density) {
		final long[] words = new long[WORDS];
		if (density <= 0.0d) {
			return words;
		}
		if (density >= 1.0d) {
			Arrays.fill(words, ~0L);
			return words;
		}
		final Random random = new Random(seed);
		for (int bit = 0; bit < WORDS * 64; bit++) {
			if (random.nextDouble() < density) {
				words[bit >>> 6] |= 1L << bit;
			}
		}
		return words;
	}

}
