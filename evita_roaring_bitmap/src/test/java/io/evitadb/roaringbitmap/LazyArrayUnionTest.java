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
 * Pins the sparse-accumulator policy of the lazy union: what it computes, when it engages, and that it
 * never writes through a container somebody else still owns.
 *
 * A fold of many bitmaps used to promote the accumulator's chunk to an 8 KiB {@link BitmapContainer} the
 * first time two inputs shared a key, whatever the two cardinalities were. It now merges two sparse chunks
 * as value lists while their combined cardinality fits
 * {@link PersistentRoaringBitmap#LAZY_ARRAY_UNION_BOUND}, which is what CRoaring has always done. The
 * result has to be *identical* — same values, same cardinality, same canonical container encoding — so most
 * of this class is a differential test against two independent ways of computing the same union.
 *
 * The rest is the part a differential test cannot see. Whether the policy engaged at all is invisible in
 * the answer, so {@link Promotion} reaches into the accumulator mid-fold and asserts the container shape
 * directly; and copy-on-write is invisible until something is corrupted, so {@link CopyOnWrite} unions
 * bitmaps that co-own their containers and checks the co-owners afterwards.
 */
@DisplayName("Lazy unions keep a small overlap sparse")
public class LazyArrayUnionTest {
	/**
	 * Input counts the differential sweep folds, chosen around the policy's two edges: the common shapes
	 * (2-16 inputs are 87% of the production workload's unions), and `65` / `200`, which cross
	 * `FastAggregation`'s wide-fold cap and therefore take the promote-immediately path.
	 */
	private static final int[] INPUT_COUNTS = {2, 3, 4, 8, 16, 65, 200};
	/**
	 * Seeds per input count. Several, because one draw can miss the key layouts the fold branches on.
	 */
	private static final int SEEDS = 8;
	/**
	 * Number of distinct 16-bit keys the generated bitmaps draw from, so that the fold meets shared keys,
	 * receiver-only keys and source-only keys rather than one shape of overlap.
	 */
	private static final int KEY_SPACE = 3;
	/**
	 * Largest cardinality a chunk can have and still be canonically an {@link ArrayContainer}.
	 */
	private static final int ARRAY_CONTAINER_LIMIT = ArrayContainer.DEFAULT_MAX_SIZE;

	@Test
	@DisplayName("the bound stays inside the sparse encoding")
	void shouldKeepTheBoundInsideTheSparseEncoding() {
		// the sparse branch's claim that `ior` hands back an ArrayContainer with a known cardinality holds only
		// while the bound stays at or below the array container's own limit. The constant exists so a benchmark
		// can sweep it, and a sweep past that limit would quietly turn `shouldPromoteTheAccumulatorOverTheBound`'s
		// `bound + 1` inputs into bitmap containers - changing what this class measures without failing anything
		assertTrue(
			PersistentRoaringBitmap.LAZY_ARRAY_UNION_BOUND <= ARRAY_CONTAINER_LIMIT,
			"the lazy-union bound must stay at or below ArrayContainer.DEFAULT_MAX_SIZE, but was "
				+ PersistentRoaringBitmap.LAZY_ARRAY_UNION_BOUND
		);
	}

	@Nested
	@DisplayName("the union is the union")
	class Differential {

		@Test
		@DisplayName("naive_or matches a pairwise or-fold and a flat build, for every input count")
		void shouldMatchBothReferenceUnions() {
			for (int c = 0; c < INPUT_COUNTS.length; c++) {
				for (int seed = 0; seed < SEEDS; seed++) {
					final int inputCount = INPUT_COUNTS[c];
					final PersistentRoaringBitmap[] inputs = randomSparseBitmaps(inputCount, seed);
					final int[] expectedValues = distinctValues(inputs);

					final PersistentRoaringBitmap actual = FastAggregation.naive_or(inputs);
					final PersistentRoaringBitmap byFold = orFold(inputs);
					final PersistentRoaringBitmap byFlatBuild = PersistentRoaringBitmap.bitmapOf(expectedValues);

					final String context = inputCount + " inputs, seed " + seed;
					assertEquals(byFold, actual, () -> "naive_or != or-fold for " + context);
					assertEquals(byFlatBuild, actual, () -> "naive_or != flat build for " + context);
					assertEquals(
						expectedValues.length, actual.getCardinality(),
						() -> "cardinality for " + context
					);
					assertArrayEquals(expectedValues, actual.toArray(), () -> "values for " + context);
				}
			}
		}

		@Test
		@DisplayName("every chunk of the result carries its canonical encoding")
		void shouldProduceCanonicalContainers() {
			for (int c = 0; c < INPUT_COUNTS.length; c++) {
				for (int seed = 0; seed < SEEDS; seed++) {
					final int inputCount = INPUT_COUNTS[c];
					final PersistentRoaringBitmap[] inputs = randomSparseBitmaps(inputCount, seed);
					final int[] expectedValues = distinctValues(inputs);
					final PersistentRoaringBitmap actual = FastAggregation.naive_or(inputs);

					for (int i = 0; i < actual.highLowContainer.size(); i++) {
						final char key = actual.highLowContainer.getKeyAtIndex(i);
						final Container container = actual.highLowContainer.getContainerAtIndex(i);
						final int cardinalityForKey = countForKey(expectedValues, key);
						final String context = inputCount + " inputs, seed " + seed + ", key " + (int) key
							+ " holding " + cardinalityForKey + " values";
						if (cardinalityForKey <= ARRAY_CONTAINER_LIMIT) {
							assertInstanceOf(ArrayContainer.class, container, () -> "sparse chunk for " + context);
						}
						assertEquals(
							cardinalityForKey, container.getCardinality(),
							() -> "chunk cardinality for " + context
						);
					}
				}
			}
		}

		@Test
		@DisplayName("the iterator overload folds a wide input to the same union as the varargs one")
		void shouldKeepThePolicyForAFoldOfUnknownWidth() {
			// an iterator cannot say how many bitmaps it will yield, so that overload keeps the sparse policy at
			// any width while the varargs one turns it off past its cap. The two therefore take different
			// branches for this input and still have to agree on the answer
			final PersistentRoaringBitmap[] inputs = new PersistentRoaringBitmap[200];
			for (int i = 0; i < inputs.length; i++) {
				inputs[i] = singleKeyBitmap(0, i, 1);
			}

			final PersistentRoaringBitmap byIterator = FastAggregation.naive_or(Arrays.asList(inputs).iterator());
			final PersistentRoaringBitmap byVarargs = FastAggregation.naive_or(inputs);

			assertEquals(byVarargs, byIterator, "the two overloads must fold to the same union");
			assertArrayEquals(expectedRun(inputs.length), byIterator.toArray(), "the union's values");
			assertEquals(inputs.length, byIterator.getCardinality(), "the union's cardinality");
		}
	}

	@Nested
	@DisplayName("the bound decides whether the accumulator promotes")
	class Promotion {

		@Test
		@DisplayName("a fold that stays under the bound keeps an ArrayContainer with a known cardinality")
		void shouldKeepTheAccumulatorSparseUnderTheBound() {
			final PersistentRoaringBitmap accumulator = new PersistentRoaringBitmap();
			accumulator.naivelazyor(singleKeyBitmap(0, 0, 4));
			accumulator.naivelazyor(singleKeyBitmap(0, 100, 4));

			final Container chunk = accumulator.highLowContainer.getContainerAtIndex(0);
			assertInstanceOf(
				ArrayContainer.class, chunk,
				"a 4 + 4 fold is far below the bound and must not have allocated an 8 KiB word array"
			);
			assertEquals(
				8, chunk.getCardinality(),
				"the sparse fold leaves the cardinality known, so repairAfterLazy has nothing to recompute"
			);

			// repairAfterLazy must be a no-op here, not merely produce the right answer: the same instance
			// comes back, which is the direct statement that no repair ran
			accumulator.repairAfterLazy();
			assertSame(
				chunk, accumulator.highLowContainer.getContainerAtIndex(0),
				"a sparse accumulator must survive repairAfterLazy untouched"
			);
		}

		@Test
		@DisplayName("a fold that crosses the bound promotes to a lazy BitmapContainer")
		void shouldPromoteTheAccumulatorOverTheBound() {
			final int bound = PersistentRoaringBitmap.LAZY_ARRAY_UNION_BOUND;
			final PersistentRoaringBitmap accumulator = new PersistentRoaringBitmap();
			accumulator.naivelazyor(singleKeyBitmap(0, 0, bound + 1));
			accumulator.naivelazyor(singleKeyBitmap(0, 40000, bound + 1));

			final Container chunk = accumulator.highLowContainer.getContainerAtIndex(0);
			assertInstanceOf(
				BitmapContainer.class, chunk,
				"a fold of two chunks whose cardinalities sum past the bound must promote"
			);
			assertEquals(
				-1, chunk.getCardinality(),
				"the promoted accumulator is left lazy for repairAfterLazy to count"
			);

			accumulator.repairAfterLazy();
			assertEquals(2 * (bound + 1), accumulator.getCardinality(), "the repaired union");
		}

		@Test
		@DisplayName("an accumulator that has promoted never returns to the sparse shape")
		void shouldNotDemoteAnAlreadyPromotedAccumulator() {
			final int bound = PersistentRoaringBitmap.LAZY_ARRAY_UNION_BOUND;
			final PersistentRoaringBitmap accumulator = new PersistentRoaringBitmap();
			accumulator.naivelazyor(singleKeyBitmap(0, 0, bound + 1));
			accumulator.naivelazyor(singleKeyBitmap(0, 40000, bound + 1));
			// a one-value chunk would satisfy the bound against a sparse accumulator, but this one is a
			// bitmap now and the sparse branch can no longer apply
			accumulator.naivelazyor(singleKeyBitmap(0, 60000, 1));

			assertInstanceOf(
				BitmapContainer.class, accumulator.highLowContainer.getContainerAtIndex(0),
				"a promoted accumulator must stay a bitmap for the rest of the fold"
			);
		}

		@Test
		@DisplayName("a wide fold promotes immediately, a narrow one does not")
		void shouldDisableTheSparseShapeForWideFolds() {
			// 65 inputs is one past FastAggregation's cap; every input carries the same single value, so the
			// union never grows past one value and the cardinality bound can never be what promotes it — the
			// same 65 bitmaps folded by hand with the policy left on stay sparse, which is what makes this an
			// assertion about the cap rather than about the bound
			final PersistentRoaringBitmap[] inputs = new PersistentRoaringBitmap[65];
			for (int i = 0; i < inputs.length; i++) {
				inputs[i] = singleKeyBitmap(0, 0, 1);
			}

			final PersistentRoaringBitmap wide = new PersistentRoaringBitmap();
			for (int i = 0; i < inputs.length; i++) {
				wide.naivelazyor(inputs[i], false);
			}
			assertInstanceOf(
				BitmapContainer.class, wide.highLowContainer.getContainerAtIndex(0),
				"a fold that declined the sparse policy must promote on the first shared key"
			);

			final PersistentRoaringBitmap narrow = new PersistentRoaringBitmap();
			for (int i = 0; i < inputs.length; i++) {
				narrow.naivelazyor(inputs[i], true);
			}
			assertInstanceOf(
				ArrayContainer.class, narrow.highLowContainer.getContainerAtIndex(0),
				"the very same 65 one-value chunks stay sparse when the policy is left on"
			);

			// and both answers are the same, which is the point of the cap being a performance switch
			wide.repairAfterLazy();
			narrow.repairAfterLazy();
			assertArrayEquals(narrow.toArray(), wide.toArray(), "the cap must not change the union");
		}

		@Test
		@DisplayName("the suffix a bulk merge finishes keeps the policy the walked keys had")
		void shouldKeepTheSuffixSparseWhenTheBulkMergeFinishesTheFold() {
			// the source's first key sits below the receiver's only key, so `naivelazyor` leaves its own loop
			// straight away and the shared key is folded inside the bulk merge instead. That is the policy's
			// second call site, and every other case in this class finishes through the loop
			final PersistentRoaringBitmap sparse = singleKeyBitmap(5, 0, 4);
			sparse.naivelazyor(twoKeyBitmap(1, 0, 4, 5, 100, 4));

			assertEquals(2, sparse.highLowContainer.size(), "the borrowed key and the merged one");
			assertEquals(5, sparse.highLowContainer.getKeyAtIndex(1), "the shared key comes second");
			final Container shared = sparse.highLowContainer.getContainerAtIndex(1);
			assertInstanceOf(
				ArrayContainer.class, shared,
				"a 4 + 4 merge is far below the bound and must stay sparse in the bulk path too"
			);
			assertEquals(8, shared.getCardinality(), "the sparse merge leaves the cardinality known");

			// the same shape with the policy declined promotes, which is what makes the assertion above an
			// assertion about the policy rather than about the two cardinalities
			final PersistentRoaringBitmap promoted = singleKeyBitmap(5, 0, 4);
			promoted.naivelazyor(twoKeyBitmap(1, 0, 4, 5, 100, 4), false);
			assertInstanceOf(
				BitmapContainer.class, promoted.highLowContainer.getContainerAtIndex(1),
				"a fold that declined the sparse policy must promote in the bulk path as well"
			);

			// and both spellings compute the same union
			sparse.repairAfterLazy();
			promoted.repairAfterLazy();
			assertArrayEquals(sparse.toArray(), promoted.toArray(), "the policy must not change the union");
			assertEquals(12, sparse.getCardinality(), "four borrowed values and eight merged ones");
		}

		@Test
		@DisplayName("FastAggregation.naive_or applies the cap by input count")
		void shouldApplyTheCapByInputCount() {
			final PersistentRoaringBitmap[] inputs = new PersistentRoaringBitmap[200];
			for (int i = 0; i < inputs.length; i++) {
				inputs[i] = singleKeyBitmap(0, i, 1);
			}
			final PersistentRoaringBitmap[] narrow = new PersistentRoaringBitmap[64];
			System.arraycopy(inputs, 0, narrow, 0, narrow.length);

			assertArrayEquals(
				expectedRun(200), FastAggregation.naive_or(inputs).toArray(),
				"a 200-input union"
			);
			assertArrayEquals(
				expectedRun(64), FastAggregation.naive_or(narrow).toArray(),
				"a 64-input union"
			);
		}
	}

	@Nested
	@DisplayName("copy-on-write survives the sparse shape")
	class CopyOnWrite {

		@Test
		@DisplayName("the inputs of a union are left exactly as they were")
		void shouldNotMutateTheInputs() {
			for (int c = 0; c < INPUT_COUNTS.length; c++) {
				for (int s = 0; s < SEEDS; s++) {
					final int seed = s;
					final int count = INPUT_COUNTS[c];
					final PersistentRoaringBitmap[] inputs = randomSparseBitmaps(count, seed);
					final int[][] before = new int[inputs.length][];
					for (int i = 0; i < inputs.length; i++) {
						before[i] = inputs[i].toArray();
					}

					FastAggregation.naive_or(inputs);

					for (int i = 0; i < inputs.length; i++) {
						final int index = i;
						assertArrayEquals(
							before[i], inputs[i].toArray(),
							() -> "input " + index + " of a " + count + "-input union, seed " + seed
						);
					}
				}
			}
		}

		@Test
		@DisplayName("a result whose containers are co-owned by an earlier union is not corrupted")
		void shouldNotCorruptAnEarlierResult() {
			// `or` carries chunks over by structural sharing, so `earlier` and `a` co-own a container; the
			// fold below then meets that very container as its accumulator's chunk and merges into it
			final PersistentRoaringBitmap a = singleKeyBitmap(0, 0, 4);
			final PersistentRoaringBitmap b = singleKeyBitmap(1, 0, 4);
			final PersistentRoaringBitmap earlier = PersistentRoaringBitmap.or(a, b);
			final PersistentRoaringBitmap c = singleKeyBitmap(0, 100, 4);

			final int[] earlierBefore = earlier.toArray();
			final int[] aBefore = a.toArray();
			final int[] bBefore = b.toArray();

			final PersistentRoaringBitmap result = FastAggregation.naive_or(earlier, c);

			assertArrayEquals(earlierBefore, earlier.toArray(), "the earlier union");
			assertArrayEquals(aBefore, a.toArray(), "the first operand of the earlier union");
			assertArrayEquals(bBefore, b.toArray(), "the second operand of the earlier union");
			assertArrayEquals(
				union(earlierBefore, c.toArray()), result.toArray(), "the new union's values"
			);
		}

		@Test
		@DisplayName("folding a bitmap together with the union it came from corrupts neither")
		void shouldSurviveAnInputThatCoOwnsTheAccumulator() {
			final PersistentRoaringBitmap a = singleKeyBitmap(0, 0, 4);
			final PersistentRoaringBitmap b = singleKeyBitmap(1, 0, 4);
			final PersistentRoaringBitmap earlier = PersistentRoaringBitmap.or(a, b);

			final int[] earlierBefore = earlier.toArray();
			final int[] aBefore = a.toArray();

			// the accumulator borrows `earlier`'s key-0 chunk, and the next input's key-0 chunk is the very
			// same instance - the fold must clone before it merges, or all three bitmaps change at once
			final PersistentRoaringBitmap result = FastAggregation.naive_or(earlier, a, singleKeyBitmap(0, 9, 4));

			assertArrayEquals(earlierBefore, earlier.toArray(), "the earlier union");
			assertArrayEquals(aBefore, a.toArray(), "the bitmap folded in twice over");
			assertArrayEquals(
				union(earlierBefore, new int[]{9, 10, 11, 12}), result.toArray(), "the new union's values"
			);
		}

		@Test
		@DisplayName("a union's result can be mutated without touching the bitmaps it was built from")
		void shouldOwnWhatItReturns() {
			final PersistentRoaringBitmap a = singleKeyBitmap(0, 0, 4);
			final PersistentRoaringBitmap b = singleKeyBitmap(0, 100, 4);
			final int[] aBefore = a.toArray();
			final int[] bBefore = b.toArray();

			final PersistentRoaringBitmap result = FastAggregation.naive_or(a, b);
			result.add(50000);
			result.remove(0);

			assertArrayEquals(aBefore, a.toArray(), "the first input");
			assertArrayEquals(bBefore, b.toArray(), "the second input");
			assertTrue(result.contains(50000), "the added value");
		}
	}

	/**
	 * Builds a bitmap holding `count` consecutive values inside a single 16-bit key.
	 *
	 * @param key   the chunk key
	 * @param from  first low 16-bit value
	 * @param count how many consecutive values to store
	 * @return the bitmap
	 */
	@Nonnull
	private static PersistentRoaringBitmap singleKeyBitmap(final int key, final int from, final int count) {
		final int[] values = new int[count];
		for (int i = 0; i < count; i++) {
			values[i] = (key << 16) | (from + i);
		}
		return PersistentRoaringBitmap.bitmapOf(values);
	}

	/**
	 * Builds a bitmap holding a run of consecutive values inside each of two 16-bit keys, so that a fold can
	 * be given a source whose first key sits below the receiver's.
	 *
	 * @param firstKey     key of the first chunk
	 * @param firstFrom    first low 16-bit value of the first chunk
	 * @param firstCount   how many consecutive values the first chunk holds
	 * @param secondKey    key of the second chunk
	 * @param secondFrom   first low 16-bit value of the second chunk
	 * @param secondCount  how many consecutive values the second chunk holds
	 * @return the bitmap
	 */
	@Nonnull
	private static PersistentRoaringBitmap twoKeyBitmap(
		final int firstKey,
		final int firstFrom,
		final int firstCount,
		final int secondKey,
		final int secondFrom,
		final int secondCount
	) {
		final int[] values = new int[firstCount + secondCount];
		for (int i = 0; i < firstCount; i++) {
			values[i] = (firstKey << 16) | (firstFrom + i);
		}
		for (int i = 0; i < secondCount; i++) {
			values[firstCount + i] = (secondKey << 16) | (secondFrom + i);
		}
		return PersistentRoaringBitmap.bitmapOf(values);
	}

	/**
	 * The values a fold of `count` one-value bitmaps built by {@link #singleKeyBitmap} must produce.
	 *
	 * @param count number of inputs
	 * @return the expected values, ascending
	 */
	@Nonnull
	private static int[] expectedRun(final int count) {
		final int[] values = new int[count];
		for (int i = 0; i < count; i++) {
			values[i] = i;
		}
		return values;
	}

	/**
	 * Draws `count` sparse bitmaps of one to three chunks, each chunk holding between one and 64 values —
	 * the shape the production workload's unions actually fold.
	 *
	 * @param count number of bitmaps to draw
	 * @param seed  seed of the pseudo-random generator
	 * @return the bitmaps
	 */
	@Nonnull
	private static PersistentRoaringBitmap[] randomSparseBitmaps(final int count, final int seed) {
		final Random random = new Random(seed * 1_000_003L + count);
		final PersistentRoaringBitmap[] inputs = new PersistentRoaringBitmap[count];
		for (int i = 0; i < count; i++) {
			final TreeSet<Integer> values = new TreeSet<>();
			final int keyCount = 1 + random.nextInt(KEY_SPACE);
			for (int k = 0; k < keyCount; k++) {
				final int key = random.nextInt(KEY_SPACE);
				final int valuesInKey = 1 + random.nextInt(64);
				for (int v = 0; v < valuesInKey; v++) {
					values.add((key << 16) | random.nextInt(65536));
				}
			}
			inputs[i] = PersistentRoaringBitmap.bitmapOf(toArray(values));
		}
		return inputs;
	}

	/**
	 * The ascending distinct union of every input's values, computed without touching a roaring bitmap.
	 *
	 * @param inputs the bitmaps
	 * @return their union's values, ascending
	 */
	@Nonnull
	private static int[] distinctValues(@Nonnull final PersistentRoaringBitmap[] inputs) {
		final TreeSet<Integer> values = new TreeSet<>();
		for (int i = 0; i < inputs.length; i++) {
			final int[] own = inputs[i].toArray();
			for (int v = 0; v < own.length; v++) {
				values.add(own[v]);
			}
		}
		return toArray(values);
	}

	/**
	 * The union of two ascending value arrays, as a third one.
	 *
	 * @param first  first operand
	 * @param second second operand
	 * @return their union, ascending
	 */
	@Nonnull
	private static int[] union(@Nonnull final int[] first, @Nonnull final int[] second) {
		final TreeSet<Integer> values = new TreeSet<>();
		for (int i = 0; i < first.length; i++) {
			values.add(first[i]);
		}
		for (int i = 0; i < second.length; i++) {
			values.add(second[i]);
		}
		return toArray(values);
	}

	/**
	 * How many of the given values fall inside one 16-bit chunk.
	 *
	 * @param values ascending values
	 * @param key    the chunk key
	 * @return the count
	 */
	private static int countForKey(@Nonnull final int[] values, final char key) {
		int count = 0;
		for (int i = 0; i < values.length; i++) {
			if ((char) (values[i] >>> 16) == key) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Folds the inputs pairwise through the public binary `or`, which is an independent implementation of
	 * the same union — it repairs eagerly and never uses the lazy accumulator at all.
	 *
	 * @param inputs the bitmaps
	 * @return their union
	 */
	@Nonnull
	private static PersistentRoaringBitmap orFold(@Nonnull final PersistentRoaringBitmap[] inputs) {
		PersistentRoaringBitmap folded = new PersistentRoaringBitmap();
		for (int i = 0; i < inputs.length; i++) {
			folded = PersistentRoaringBitmap.or(folded, inputs[i]);
		}
		return folded;
	}

	/**
	 * Unboxes an ordered set of values into an ascending array.
	 *
	 * @param values the set
	 * @return its contents, ascending
	 */
	@Nonnull
	private static int[] toArray(@Nonnull final TreeSet<Integer> values) {
		final int[] array = new int[values.size()];
		int next = 0;
		for (final Integer value : values) {
			array[next++] = value;
		}
		return array;
	}

}
