/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.lang.reflect.Method;
import java.util.Arrays;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrFormula} verifying boolean disjunction (OR) computation, memoization,
 * cloning, hashing and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("OrFormula — boolean disjunction")
@Tag(ENGINE)
@Tag(QUERY)
class OrFormulaTest {

	private static final long[] INDEX_TRANSACTION_ID = {1L};

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should union three overlapping bitmaps")
		void shouldApplyBooleanOr() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 8},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 4, 5, 8),
					new BaseBitmap(1, 2, 4, 8),
					new BaseBitmap(1, 2, 3, 4, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should union three overlapping formulas")
		void shouldApplyBooleanOrWithFormula() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 8},
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8))),
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should deduplicate across bitmaps with shared elements")
		void shouldConjugateCollectionsOfDuplicateInt() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 7, 8},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5),
					new BaseBitmap(3, 5, 8),
					new BaseBitmap(3, 4, 5, 7)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return union when one bitmap is empty")
		void shouldReturnJoinForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(3, 4),
					new BaseBitmap(),
					new BaseBitmap(1, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should union non-overlapping collections")
		void shouldConjugateNonOverlappingCollections() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 6, 7, 9, 10, 11, 12},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(3, 4, 5, 6, 7),
					new BaseBitmap(10, 11, 12)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should deduplicate completely overlapping collections")
		void shouldConjugateOverlappingCollections() {
			assertArrayEquals(
				new int[]{1, 3, 5, 7, 9, 11},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(1, 3, 5, 7, 9, 11)
				)
					.compute().getArray()
			);
		}
	}

	@Nested
	@DisplayName("Memoization")
	class MemoizationTest {

		@Test
		@DisplayName("should return same instance on repeated compute calls")
		void shouldReturnSameInstanceOnRepeatedCompute() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(3, 4, 5)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated compute calls with formula children")
		void shouldReturnSameInstanceOnRepeatedComputeWithFormulas() {
			final OrFormula formula = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4, 5)))
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("Clear memory")
	class ClearMemoryTest {

		@Test
		@DisplayName("should recompute equal result after clearMemory")
		void shouldRecomputeEqualResultAfterClearMemory() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(3, 4, 5)
			);

			final Bitmap first = formula.compute();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			assertNotSame(first, second);
			assertArrayEquals(first.getArray(), second.getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return EmptyFormula when cloned with zero children")
		void shouldReturnEmptyFormulaWhenClonedWithZeroChildren() {
			final OrFormula original = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4)))
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should return the single child directly when cloned with one child")
		void shouldReturnSingleChildWhenClonedWithOneChild() {
			final ConstantFormula child = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final OrFormula original = new OrFormula(
				child,
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5)))
			);

			final Formula clone = original.getCloneWithInnerFormulas(child);

			assertSame(child, clone);
		}

		@Test
		@DisplayName("should return new OrFormula preserving computation when cloned with two+ children")
		void shouldReturnNewOrFormulaWhenClonedWithMultipleChildren() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4, 5)));
			final OrFormula original = new OrFormula(childA, childB);

			final Formula clone = original.getCloneWithInnerFormulas(childA, childB);

			assertInstanceOf(OrFormula.class, clone);
			assertArrayEquals(original.compute().getArray(), clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically-constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final long hashA = createOrFormula(1, 2, 3).getHash();
			final long hashB = createOrFormula(1, 2, 3).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different children")
		void shouldProduceDifferentHashForDifferentChildren() {
			final long hashA = createOrFormula(1, 2, 3).getHash();
			final long hashB = createOrFormula(7, 8, 9).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce same hash when children are reordered (order-insignificant)")
		void shouldProduceSameHashWhenChildrenReordered() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5, 6)));

			final long hashAB = new OrFormula(childA, childB).getHash();
			final long hashBA = new OrFormula(childB, childA).getHash();

			assertEquals(hashAB, hashBA);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return sum of cardinalities across bitmaps")
		void shouldReturnSumCardinalityForBitmaps() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(4, 5)
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return sum of cardinalities across formula children")
		void shouldReturnSumCardinalityForFormulas() {
			final OrFormula formula = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5)))
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("should have non-negative estimated and actual cost with estimated being an upper bound")
		void shouldHaveNonNegativeCostsWithEstimatedAsUpperBound() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(3, 4, 5, 6, 7)
			);

			final long estimatedCost = formula.getEstimatedCost();
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(estimatedCost >= 0, "Estimated cost should be non-negative, was: " + estimatedCost);
			assertTrue(actualCost >= 0, "Actual cost should be non-negative, was: " + actualCost);
			assertTrue(
				estimatedCost >= actualCost,
				"Estimated cost " + estimatedCost + " should be >= actual cost " + actualCost
			);
		}
	}

	@Nested
	@DisplayName("Cache behavior")
	class CacheBehaviorTest {

		@Test
		@DisplayName("should implement CacheableFormula")
		void shouldImplementCacheableFormula() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2),
				new BaseBitmap(3, 4)
			);

			assertInstanceOf(CacheableFormula.class, formula);
		}
	}

	@Nested
	@DisplayName("High-cardinality staleness guard (issue #37)")
	class HighCardinalityStalenessGuardTest {

		/**
		 * Builds `count` distinct single-element bitmaps so the formula crosses the high-cardinality threshold and takes
		 * the transactional-id fallback branch.
		 *
		 * @param count the number of bitmaps to create
		 * @return the bitmap array
		 */
		@Nonnull
		private static Bitmap[] distinctBitmaps(int count) {
			final Bitmap[] bitmaps = new Bitmap[count];
			for (int i = 0; i < count; i++) {
				bitmaps[i] = new BaseBitmap(i + 1);
			}
			return bitmaps;
		}

		@Test
		@DisplayName("A high-cardinality formula with an EMPTY transactional-id set is rejected")
		void shouldRejectEmptyTransactionalIdSetAboveThreshold() {
			// above EXCESSIVE_HIGH_CARDINALITY (100) the formula keys staleness solely on the transactional-id set; an
			// empty set would make the cached result impossible to invalidate (issue #37), so construction must fail fast
			final Bitmap[] bitmaps = distinctBitmaps(101);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new OrFormula(new long[0], bitmaps)
			);
		}

		@Test
		@DisplayName("A high-cardinality formula with a non-empty transactional-id set is accepted")
		void shouldAcceptNonEmptyTransactionalIdSetAboveThreshold() {
			final Bitmap[] bitmaps = distinctBitmaps(101);
			// a non-empty token (here a two-leaf version set) is the well-formed case and must construct cleanly
			assertDoesNotThrow(() -> new OrFormula(new long[]{42L, 43L}, bitmaps));
		}
	}

	@Nested
	@DisplayName("Folding single-record operands")
	class SingleRecordFold {

		@Test
		@DisplayName("many single-record operands union to exactly what one bitmap per record would")
		void shouldUnionSingleRecordOperandsIntoTheSameAnswer() {
			// An inverted index over near-unique values folds almost entirely single-record buckets, so the compute
			// path merges them into ONE bitmap rather than converting each into its own. This asserts the merge is
			// invisible in the answer: the same ids, in ascending order, as the one-bitmap-per-record shape produces.
			final int[] ids = {97, 3, 51, 12, 88, 1, 64};
			final Bitmap[] singles = new Bitmap[ids.length];
			final Bitmap[] equivalent = new Bitmap[ids.length];
			for (int i = 0; i < ids.length; i++) {
				singles[i] = new SingleRecordBitmap(ids[i]);
				equivalent[i] = new BaseBitmap(ids[i]);
			}
			final int[] expected = ids.clone();
			Arrays.sort(expected);

			assertArrayEquals(expected, new OrFormula(INDEX_TRANSACTION_ID, singles).compute().getArray());
			assertArrayEquals(expected, new OrFormula(INDEX_TRANSACTION_ID, equivalent).compute().getArray());
		}

		@Test
		@DisplayName("single-record and multi-record operands mixed together union correctly")
		void shouldUnionAMixOfSingleAndMultiRecordOperands() {
			final Bitmap[] operands = {
				new SingleRecordBitmap(5),
				new BaseBitmap(2, 7, 40),
				new SingleRecordBitmap(31),
				new SingleRecordBitmap(1),
				new BaseBitmap(7, 99)
			};
			assertArrayEquals(
				new int[]{1, 2, 5, 7, 31, 40, 99},
				new OrFormula(INDEX_TRANSACTION_ID, operands).compute().getArray()
			);
		}

		@Test
		@DisplayName("a record id repeated across buckets appears once, as a union demands")
		void shouldDeduplicateARecordHeldBySeveralBuckets() {
			// An array-valued attribute puts one record into several buckets, so the same id genuinely arrives more
			// than once. The fold sorts and hands the ids over in bulk, which must not turn a duplicate into two
			// entries or disturb the ordering around it.
			final Bitmap[] operands = {
				new SingleRecordBitmap(8),
				new SingleRecordBitmap(3),
				new SingleRecordBitmap(8),
				new BaseBitmap(3, 11),
				new SingleRecordBitmap(11)
			};
			assertArrayEquals(
				new int[]{3, 8, 11},
				new OrFormula(INDEX_TRANSACTION_ID, operands).compute().getArray()
			);
		}

		@Test
		@DisplayName("operands straddling the sign boundary fold in Roaring's unsigned order")
		void shouldFoldOperandsThatStraddleTheSignBoundary() {
			// Nothing on the way into a bucket rules out a negative record id, and Roaring orders ids UNSIGNED - so a
			// negative id belongs in the highest container, not the lowest. The fold sorts before handing the ids over
			// in bulk, and a signed sort would present them highest-container-first and make every later container an
			// insertion at the front of the container array.
			//
			// This pins the ANSWER across the boundary, which no existing case covered; it cannot pin the ordering
			// itself, because a signed sort reaches the same set by a slower route. The counterfactual for that is the
			// shape of the input, not the assertion: ids here occupy four distinct containers on both sides of the
			// boundary, so the two orders are structurally different even where they agree.
			final int[] ids = {-3, 70000, -140000, 5, -70000, 140000, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
			final Bitmap[] singles = new Bitmap[ids.length];
			final Bitmap[] equivalent = new Bitmap[ids.length];
			for (int i = 0; i < ids.length; i++) {
				singles[i] = new SingleRecordBitmap(ids[i]);
				equivalent[i] = new BaseBitmap(ids[i]);
			}
			// signed order, because that is what a `Bitmap` hands back regardless of how Roaring stored it
			// (`RoaringBitmapBackedBitmap#toSignedArray`) - the unsigned ordering is internal to the fill
			final int[] expected = ids.clone();
			Arrays.sort(expected);

			assertArrayEquals(expected, new OrFormula(INDEX_TRANSACTION_ID, singles).compute().getArray());
			assertArrayEquals(expected, new OrFormula(INDEX_TRANSACTION_ID, equivalent).compute().getArray());
		}

		@Test
		@DisplayName("a lone single-record operand is still answered correctly")
		void shouldAnswerWithASingleSingleRecordOperand() {
			// below the fold's threshold, so this takes the untouched conversion path - asserted so the branch that
			// decides NOT to fold is covered too
			assertArrayEquals(
				new int[]{4, 6, 9},
				new OrFormula(INDEX_TRANSACTION_ID, new SingleRecordBitmap(6), new BaseBitmap(4, 9))
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("the union is handed ONE operand for all the folded singles, not one for each")
		void shouldHandTheUnionOneOperandForEveryFoldedSingle() {
			// Every other test in this class asserts the ANSWER, which is bit-identical whether the fold runs or not,
			// so all of them stay green with the fold deleted. This one observes the fold's SHAPE, which is the only
			// thing it changes: the operand array the union receives loses one entry per single-record operand and
			// gains the one bitmap they were merged into.
			//
			// CALIBRATION: reverting `getRoaringBitmaps` to the plain per-operand loop reddens this test and nothing
			// else in this class.
			final Bitmap[] folded = {
				new SingleRecordBitmap(5),
				new BaseBitmap(2, 7, 40),
				new SingleRecordBitmap(31),
				new SingleRecordBitmap(1),
				new BaseBitmap(7, 99)
			};
			assertEquals(
				3, roaringOperandsOf(new OrFormula(INDEX_TRANSACTION_ID, folded)).length,
				"three single-record operands must arrive as ONE bitmap, alongside the two multi-record operands"
			);

			// fewer than two singles is the branch that deliberately does NOT fold, and no assertion on the answer can
			// tell it apart from the folding one
			final Bitmap[] untouched = {
				new SingleRecordBitmap(6),
				new BaseBitmap(4, 9),
				new BaseBitmap(11)
			};
			assertEquals(
				untouched.length, roaringOperandsOf(new OrFormula(INDEX_TRANSACTION_ID, untouched)).length,
				"one single-record operand costs the same either way, so the operands must pass through as they are"
			);
		}

		@Test
		@DisplayName("the fold leaves the formula's own operands, hash and cardinality estimate exactly as they were")
		void shouldLeaveTheFormulaOwnStateUntouchedByTheFold() {
			// The fold builds a fresh array on the way into the union. The formula's OWN operand array is what its
			// hash, its cached identity and its transactional ids are derived from, so a fold that wrote into it would
			// silently move the cache key of every query holding this formula.
			final Bitmap[] operands = {
				new SingleRecordBitmap(5),
				new BaseBitmap(2, 7, 40),
				new SingleRecordBitmap(31),
				new SingleRecordBitmap(1)
			};
			final Bitmap[] operandsBefore = operands.clone();
			final int[][] contentBefore = new int[operands.length][];
			for (int i = 0; i < operands.length; i++) {
				contentBefore[i] = operands[i].getArray();
			}
			final OrFormula formula = new OrFormula(INDEX_TRANSACTION_ID, operands);
			final long hashBefore = formula.getHash();
			final int cardinalityBefore = formula.getEstimatedCardinality();

			formula.compute();

			for (int i = 0; i < operands.length; i++) {
				assertSame(operandsBefore[i], operands[i], "operand " + i + " must be the very instance handed in");
				assertArrayEquals(
					contentBefore[i], operands[i].getArray(),
					"operand " + i + " must hold exactly the ids it was built with"
				);
			}
			assertEquals(hashBefore, formula.getHash(), "computing must not move the formula's cache key");
			assertEquals(cardinalityBefore, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("two single-record operands naming the same record fold to one bitmap and no union at all")
		void shouldFoldTwoOperandsHoldingTheSameRecordIntoOneBitmap() {
			// The fold can shrink the operand array to a SINGLE entry, which sends the computation down its
			// one-bitmap branch - a branch the bitmap-backed constructor could not reach before the fold existed,
			// because it refuses fewer than two operands outright.
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID, new SingleRecordBitmap(42), new SingleRecordBitmap(42)
			);
			assertEquals(
				1, roaringOperandsOf(formula).length,
				"both operands name the same record, so the fold must leave exactly one bitmap behind"
			);
			assertArrayEquals(new int[]{42}, formula.compute().getArray());
		}

		@Test
		@DisplayName("an operand holding nothing at all is converted alongside the folded singles")
		void shouldFoldAlongsideAnEmptyOperand() {
			// the fold's non-single arm converts whatever it meets, and `EmptyBitmap` is the one operand that is
			// neither single-record nor Roaring-backed
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new SingleRecordBitmap(3),
				EmptyBitmap.INSTANCE,
				new SingleRecordBitmap(9),
				new BaseBitmap(5)
			);
			assertEquals(3, roaringOperandsOf(formula).length);
			assertArrayEquals(new int[]{3, 5, 9}, formula.compute().getArray());
		}

		/**
		 * Reaches the private conversion the union is actually handed, so a test can observe the fold's SHAPE rather
		 * than only its answer - which is identical whether the fold runs or not.
		 *
		 * @param formula the formula whose operands to convert
		 * @return the Roaring operands the union would receive
		 */
		@Nonnull
		private Object[] roaringOperandsOf(@Nonnull OrFormula formula) {
			try {
				final Method conversion = OrFormula.class.getDeclaredMethod("getRoaringBitmaps");
				conversion.setAccessible(true);
				return (Object[]) conversion.invoke(formula);
			} catch (ReflectiveOperationException ex) {
				throw new GenericEvitaInternalError(
					"`OrFormula#getRoaringBitmaps` no longer exists - this test would otherwise silently stop "
						+ "observing the fold it is written to guard.", ex
				);
			}
		}
	}

	/**
	 * Creates an {@link OrFormula} wrapping two constant formulas for hash testing.
	 *
	 * @param values the ids of the first constant formula
	 * @return the assembled formula
	 */
	@Nonnull
	private static OrFormula createOrFormula(int... values) {
		return new OrFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(10, 20, 30)))
		);
	}
}
