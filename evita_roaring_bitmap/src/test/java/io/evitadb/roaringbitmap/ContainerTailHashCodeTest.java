package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two container hash codes that now read only the tail of their backing array.
 *
 * {@link ArrayContainer#hashCode()} and {@link RunContainer#hashCode()} compute the upstream recurrence
 * `hash += 31 * hash + entry`, which the `+=` makes `hash = 32 * hash + entry`. In 32-bit arithmetic the
 * entry seven places from the end is multiplied by `2^35 == 0`, so everything before the last seven entries
 * contributes nothing and the loop can start there. The point of this class is that the shortened loop is an
 * **identity**: the value the methods return did not change, and {@link WholeArrayEquivalence} proves it
 * against the vendored full loop rather than against itself.
 *
 * {@link DocumentedCollisions} then asserts the property that identity implies — two containers agreeing only
 * in their last seven entries hash the same — so that a future improvement to the hash is a conscious change
 * with a failing test in front of it, rather than a silent one.
 */
@DisplayName("Container hash codes read only the tail that can contribute")
public class ContainerTailHashCodeTest {
	/**
	 * How many trailing entries can influence the polynomial. Repeated here rather than read from the
	 * container, so that a change to the constant has to be argued against this test instead of carried by it.
	 */
	private static final int CONTRIBUTING = 7;

	@Nested
	@DisplayName("the shortened loop is the whole-array loop")
	class WholeArrayEquivalence {

		@Test
		@DisplayName("array containers agree with the full loop at every cardinality from 0 to 12")
		void shouldMatchTheFullLoopOnShortArrayContainers() {
			final char[] values = sortedDistinctValues(20260918L, 12);
			for (int cardinality = 0; cardinality <= values.length; cardinality++) {
				final int at = cardinality;
				assertEquals(
					wholeArrayHash(values, cardinality),
					new ArrayContainer(cardinality, values).hashCode(),
					() -> "array container holding " + at + " values"
				);
			}
		}

		@Test
		@DisplayName("array containers agree with the full loop at cardinalities up to the array limit")
		void shouldMatchTheFullLoopOnLargeArrayContainers() {
			final Random random = new Random(4711L);
			for (int attempt = 0; attempt < 500; attempt++) {
				final int cardinality = 1 + random.nextInt(ArrayContainer.DEFAULT_MAX_SIZE);
				final char[] values = sortedDistinctValues(random.nextLong(), cardinality);
				assertEquals(
					wholeArrayHash(values, cardinality),
					new ArrayContainer(cardinality, values).hashCode(),
					() -> "array container holding " + cardinality + " values"
				);
			}
		}

		@Test
		@DisplayName("run containers agree with the full loop over their live run list")
		void shouldMatchTheFullLoopOnRunContainers() {
			final Random random = new Random(13L);
			for (int attempt = 0; attempt < 500; attempt++) {
				final RunContainer container = randomRunContainer(random);
				final int runs = container.numberOfRuns();
				assertEquals(
					wholeArrayHash(liveRunList(container), runs * 2),
					container.hashCode(),
					"run container with " + runs + " runs"
				);
			}
		}

		@Test
		@DisplayName("only the live region is read, never the slack of the backing array")
		void shouldIgnoreTheBackingArraysSlack() {
			// a container's backing array grows geometrically and is never trimmed, so most containers carry
			// slack; a hash reading `content.length` instead of the cardinality would take it in
			final char[] values = sortedDistinctValues(99L, 40);
			final char[] padded = Arrays.copyOf(values, values.length * 4);
			for (int i = values.length; i < padded.length; i++) {
				padded[i] = (char) (0xBEEF + i);
			}

			assertEquals(
				new ArrayContainer(values.length, values).hashCode(),
				new ArrayContainer(values.length, padded).hashCode(),
				"two containers holding the same values must hash the same whatever their slack"
			);
		}
	}

	@Nested
	@DisplayName("the collisions this hash has always had")
	class DocumentedCollisions {

		@Test
		@DisplayName("two full containers sharing only their last seven values hash the same")
		void shouldCollideWhenOnlyTheTailAgrees() {
			// documented behaviour, asserted so that improving the hash is a deliberate act: this collision
			// predates the loop being shortened - the older values were already multiplied by zero
			final int cardinality = ArrayContainer.DEFAULT_MAX_SIZE;
			final char[] first = sortedDistinctValues(1L, cardinality);
			final char[] second = sortedDistinctValues(2L, cardinality);
			System.arraycopy(first, cardinality - CONTRIBUTING, second, cardinality - CONTRIBUTING, CONTRIBUTING);

			assertTrue(
				differsBeforeTheTail(first, second, cardinality),
				"the two containers must genuinely differ outside the tail, or the assertion below is empty"
			);
			assertEquals(
				new ArrayContainer(cardinality, first).hashCode(),
				new ArrayContainer(cardinality, second).hashCode(),
				"4096-value containers sharing only their last seven values collide"
			);
			assertNotEquals(
				new ArrayContainer(cardinality, first),
				new ArrayContainer(cardinality, second),
				"...while remaining unequal, which is what makes the collision a distribution question"
			);
		}

		@Test
		@DisplayName("changing any of the last seven values changes the hash")
		void shouldSeparateContainersThatDifferInTheTail() {
			final int cardinality = 64;
			final char[] values = sortedDistinctValues(7L, cardinality);
			for (int offset = 1; offset <= CONTRIBUTING; offset++) {
				final char[] changed = values.clone();
				final int index = cardinality - offset;
				changed[index] = (char) (changed[index] ^ 1);
				final int at = offset;
				assertNotEquals(
					new ArrayContainer(cardinality, values).hashCode(),
					new ArrayContainer(cardinality, changed).hashCode(),
					() -> "flipping a bit of the value " + at + " places from the end"
				);
			}
		}

		@Test
		@DisplayName("two run containers sharing only their last seven run entries hash the same")
		void shouldCollideWhenOnlyTheRunTailAgrees() {
			// the collision property is pinned above for the sparse encoding alone, while the run hash runs the
			// same shortened recurrence over a different array - the interleaved `value, length` pairs - so it
			// has to be pinned here too or a change to one of the two loops goes unnoticed
			final RunContainer first = fourRunsStartingAt(10);
			final RunContainer second = fourRunsStartingAt(20);

			assertEquals(4, first.numberOfRuns(), "eight entries, so the first one falls outside the tail");
			assertEquals(4, second.numberOfRuns());
			assertNotEquals(
				(int) first.getValue(0), (int) second.getValue(0),
				"the two containers must genuinely differ outside the tail, or the assertion below is empty"
			);
			assertEquals(
				first.hashCode(), second.hashCode(),
				"run containers sharing only their last seven run entries collide"
			);
			assertNotEquals(first, second, "...while remaining unequal, as they do in the sparse encoding");
		}

		// Known limitation: an array container and a run container holding the same values compare equal in
		// both directions yet hash differently, because the run hash reads the interleaved `value, length` pairs
		// rather than the values the container holds - the two encodings break the hashCode contract
		@Test
		@DisplayName("an array container and a run container over the same values compare equal and hash apart")
		void shouldHashDifferentlyAcrossEncodingsThatCompareEqual() {
			final ArrayContainer asValues = new ArrayContainer(3, new char[]{1, 2, 3});
			final RunContainer asRuns = new RunContainer();
			assertSame(asRuns, asRuns.iadd(1, 4), "iadd must keep the run encoding");

			assertEquals(asValues, asRuns, "the two encodings hold the same three values");
			assertEquals(asRuns, asValues, "...and compare equal the other way round too");
			assertNotEquals(
				asValues.hashCode(), asRuns.hashCode(),
				"equal containers hashing apart is what breaks the contract"
			);
			// pinned as values, so that a change to either loop has to be argued against this test rather than
			// merely keeping the two sides different from each other
			assertEquals(1091, asValues.hashCode(), "32 * (32 * (32 * 0 + 1) + 2) + 3, over the three values");
			assertEquals(34, asRuns.hashCode(), "32 * (32 * 0 + 1) + 2, over the run's value and length");
		}
	}

	/**
	 * Builds a four-run container whose first run starts at `firstValue` and whose three remaining runs are
	 * fixed, so that two such containers differ in exactly the one entry the hash cannot see.
	 *
	 * @param firstValue first value of the leading run
	 * @return the container, holding four runs and therefore eight hash entries
	 */
	@Nonnull
	private static RunContainer fourRunsStartingAt(final int firstValue) {
		final RunContainer container = new RunContainer();
		assertSame(
			container, container.iadd(firstValue, firstValue + 6), "iadd must keep the run encoding"
		);
		assertSame(container, container.iadd(100, 104));
		assertSame(container, container.iadd(200, 204));
		assertSame(container, container.iadd(300, 304));
		return container;
	}

	/**
	 * The vendored whole-array loop, kept here verbatim as the reference the shortened one is checked
	 * against. It must not be simplified — its whole value is that it is the code that used to run.
	 *
	 * @param entries the backing array
	 * @param length  number of leading entries to hash
	 * @return the hash
	 */
	private static int wholeArrayHash(@Nonnull final char[] entries, final int length) {
		int hash = 0;
		for (int k = 0; k < length; ++k) {
			hash += 31 * hash + entries[k];
		}
		return hash;
	}

	/**
	 * Whether two equal-length value lists differ somewhere before their last {@link #CONTRIBUTING} entries.
	 *
	 * @param first       first value list
	 * @param second      second value list
	 * @param cardinality number of live entries in both
	 * @return `true` when they differ outside the tail
	 */
	private static boolean differsBeforeTheTail(
		@Nonnull final char[] first,
		@Nonnull final char[] second,
		final int cardinality
	) {
		for (int k = 0; k < cardinality - CONTRIBUTING; k++) {
			if (first[k] != second[k]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reconstructs a run container's live `value, length` pairs through its public accessors, which is
	 * exactly the region its hash reads.
	 *
	 * @param container the container
	 * @return the interleaved run list, `2 * numberOfRuns()` entries long
	 */
	@Nonnull
	private static char[] liveRunList(@Nonnull final RunContainer container) {
		final int runs = container.numberOfRuns();
		final char[] entries = new char[runs * 2];
		for (int run = 0; run < runs; run++) {
			entries[2 * run] = container.getValue(run);
			entries[2 * run + 1] = container.getLength(run);
		}
		return entries;
	}

	/**
	 * Draws a run container of one to forty runs spread across the 16-bit universe.
	 *
	 * @param random the generator
	 * @return the container
	 */
	@Nonnull
	private static RunContainer randomRunContainer(@Nonnull final Random random) {
		final RunContainer container = new RunContainer();
		int next = random.nextInt(64);
		final int runs = 1 + random.nextInt(40);
		for (int run = 0; run < runs && next < 65536; run++) {
			final int length = 1 + random.nextInt(200);
			final int end = Math.min(65536, next + length);
			// `iadd` is declared to return a Container because other encodings may convert; a run container
			// adding a range stays one, and the test would be about something else if it did not
			assertSame(container, container.iadd(next, end), "iadd must keep the run encoding");
			next = end + 1 + random.nextInt(600);
		}
		return container;
	}

	/**
	 * Draws a sorted, duplicate-free value list, i.e. the shape a sparse container's backing array has.
	 *
	 * @param seed  seed of the pseudo-random generator
	 * @param count number of values to draw
	 * @return the values, ascending
	 */
	@Nonnull
	private static char[] sortedDistinctValues(final long seed, final int count) {
		final Random random = new Random(seed);
		final boolean[] taken = new boolean[65536];
		final char[] values = new char[count];
		int drawn = 0;
		while (drawn < count) {
			final int candidate = random.nextInt(65536);
			if (!taken[candidate]) {
				taken[candidate] = true;
				values[drawn++] = (char) candidate;
			}
		}
		Arrays.sort(values);
		return values;
	}

}
