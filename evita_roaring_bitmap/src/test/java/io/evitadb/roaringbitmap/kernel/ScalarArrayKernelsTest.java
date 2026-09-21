package io.evitadb.roaringbitmap.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the reference implementation of {@link ArrayKernels}, the seam through which the sparse-container
 * operators are meant to reach a vector formulation of the two-way merge once one wins.
 *
 * Nothing in production calls it yet - the container operators still reach `Util` directly - so a mistake in
 * the delegation would surface at the first caller that adopts the seam rather than at a failing test. What
 * can go wrong is exactly what a delegation can get wrong: a swapped length argument, a count that disagrees
 * with what was written, or a write that runs past the values it counted. Each of the three is asserted here
 * against an intersection computed independently of the merge.
 */
@DisplayName("The scalar sparse-container kernels intersect two sorted value lists")
public class ScalarArrayKernelsTest {
	/**
	 * The implementation under test, and today the only one the provider can publish.
	 */
	private static final ArrayKernels KERNELS = ScalarArrayKernels.INSTANCE;
	/**
	 * Filler written into the destination before every call, so that a write past the returned count is
	 * visible rather than merely plausible.
	 */
	private static final char UNTOUCHED = (char) 0xBEEF;

	@Test
	@DisplayName("writes exactly the values it counts, in ascending order")
	void shouldCountWhatItWrites() {
		final char[][][] pairs = {
			{values(1, 3, 5, 7, 9), values(3, 4, 5, 9, 11)},
			{values(0, 1, 2, 3), values(0, 1, 2, 3)},
			{values(0, 65535), values(65535)},
			{values(1), values(2)},
			{values(32768, 65535), values(1, 32768)}
		};

		for (int p = 0; p < pairs.length; p++) {
			final char[] a = pairs[p][0];
			final char[] b = pairs[p][1];
			final char[] out = new char[Math.min(a.length, b.length) + 1];
			Arrays.fill(out, UNTOUCHED);

			final int written = KERNELS.intersect(a, a.length, b, b.length, out);
			final int counted = KERNELS.intersectCardinality(a, a.length, b, b.length);
			final char[] expected = reference(a, a.length, b, b.length);
			final int pair = p;

			assertEquals(expected.length, counted, () -> "cardinality of pair " + pair);
			assertEquals(counted, written, () -> "the two methods disagree on pair " + pair);
			assertArrayEquals(expected, Arrays.copyOf(out, written), () -> "values written for pair " + pair);
			assertEquals(
				UNTOUCHED, out[written],
				() -> "pair " + pair + " wrote past the count it returned"
			);
		}
	}

	@Test
	@DisplayName("reads only the leading entries the lengths name")
	void shouldHonourThePrefixLengths() {
		// both arrays carry a tail beyond the prefix, and the tails share two values the prefixes do not, so a
		// delegation that passed an array's own length where it meant the prefix picks them up
		final char[] a = values(1, 2, 3, 500, 501);
		final char[] b = values(3, 4, 500, 501, 502);
		final char[] out = new char[8];
		Arrays.fill(out, UNTOUCHED);

		assertEquals(1, KERNELS.intersectCardinality(a, 3, b, 2), "the prefixes share only the value 3");
		assertEquals(1, KERNELS.intersect(a, 3, b, 2, out));
		assertEquals((char) 3, out[0]);
		assertEquals(UNTOUCHED, out[1], "nothing beyond the single shared value may be written");

		assertEquals(3, KERNELS.intersectCardinality(a, a.length, b, b.length), "read in full they share three");
		assertEquals(3, KERNELS.intersect(a, a.length, b, b.length, out));
		assertArrayEquals(values(3, 500, 501), Arrays.copyOf(out, 3));
	}

	@Test
	@DisplayName("an empty prefix or a disjoint pair yields nothing")
	void shouldReturnNothingForDisjointOrEmptyInputs() {
		final char[] a = values(1, 2, 3);
		final char[] b = values(4, 5, 6);
		final char[] out = new char[4];
		Arrays.fill(out, UNTOUCHED);

		assertEquals(0, KERNELS.intersectCardinality(a, a.length, b, b.length), "disjoint lists");
		assertEquals(0, KERNELS.intersect(a, a.length, b, b.length, out));
		assertEquals(0, KERNELS.intersectCardinality(a, 0, b, b.length), "an empty first prefix");
		assertEquals(0, KERNELS.intersect(a, 0, b, b.length, out));
		assertEquals(0, KERNELS.intersectCardinality(a, a.length, b, 0), "an empty second prefix");
		assertEquals(0, KERNELS.intersect(a, a.length, b, 0, out));
		assertEquals(UNTOUCHED, out[0], "an empty intersection must write nothing at all");
	}

	/**
	 * The intersection of two value lists, found by a quadratic scan rather than by a merge, so that it is
	 * not the algorithm under test.
	 *
	 * @param a  first list
	 * @param la number of leading entries of `a` to consider
	 * @param b  second list
	 * @param lb number of leading entries of `b` to consider
	 * @return the shared values, in the order `a` holds them
	 */
	@Nonnull
	private static char[] reference(@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final char[] shared = new char[Math.min(la, lb)];
		int count = 0;
		for (int i = 0; i < la; i++) {
			for (int j = 0; j < lb; j++) {
				if (a[i] == b[j]) {
					shared[count++] = a[i];
					break;
				}
			}
		}
		return Arrays.copyOf(shared, count);
	}

	/**
	 * Builds a value list from plain `int`s, so that the fixtures read as the numbers they are.
	 *
	 * @param values the values, in unsigned-ascending order
	 * @return them as a `char[]`
	 */
	@Nonnull
	private static char[] values(final int... values) {
		final char[] chars = new char[values.length];
		for (int i = 0; i < values.length; i++) {
			chars[i] = (char) values[i];
		}
		return chars;
	}

}
