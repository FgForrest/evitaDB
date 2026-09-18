/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract of {@link RangeCountFormula} itself — its identity, its declared premises and the staleness
 * token it hands the formula cache. {@link RangeCountKernelTest} covers the arithmetic; everything here is about the
 * formula wrapper around it.
 *
 * The identity half matters more than it looks. This formula is cacheable, so two formulas that hash the same are
 * served each other's answer; the operands are carried as two ORDERED families, and their concatenation alone does
 * not say where one family ends and the other begins.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(QUERY)
@Tag(CACHE)
@DisplayName("RangeCountFormula: identity, premises and staleness token")
class RangeCountFormulaTest {
	/**
	 * Arbitrary non-zero index id — any high-cardinality formula requires one, and the value itself is opaque.
	 */
	private static final long INDEX_ID = 42L;

	/**
	 * Builds a non-transactional bitmap from the given record ids.
	 *
	 * @param values the record ids
	 * @return a bitmap holding them
	 */
	@Nonnull
	private static Bitmap bitmap(int... values) {
		return new BaseBitmap(values);
	}

	/**
	 * Builds an array of distinct transactional bitmaps, each carrying its own
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} id.
	 *
	 * @param count how many operands to build
	 * @return the operands, each holding a single distinct record id
	 */
	@Nonnull
	private static Bitmap[] transactionalOperands(int count) {
		final Bitmap[] operands = new Bitmap[count];
		for (int i = 0; i < count; i++) {
			operands[i] = new TransactionalBitmap(i + 1);
		}
		return operands;
	}

	@Nested
	@DisplayName("Formula identity")
	class Identity {

		@Test
		@DisplayName("Two different splits of the same operand sequence into families hash differently")
		void shouldHashDifferentFamilySplitsOfTheSameOperandsDifferently() {
			final Bitmap a = bitmap(1, 2);
			final Bitmap b = bitmap(2, 3);
			final Bitmap c = bitmap(3);

			final RangeCountFormula twoPlusOne = new RangeCountFormula(INDEX_ID, new Bitmap[]{a, b}, new Bitmap[]{c});
			final RangeCountFormula onePlusTwo = new RangeCountFormula(INDEX_ID, new Bitmap[]{a}, new Bitmap[]{b, c});

			// CALIBRATION: the two formulas hold the SAME three operands in the SAME order - only the position of
			// the family boundary differs. `includeAdditionalHash` therefore folds an identical sequence of operand
			// tokens in both cases, and the only thing left that can tell them apart is the FAMILY_SEPARATOR folded
			// in between the families. Erase the boundary from both renderings and they coincide, which is the
			// statement "without the separator these two hash the same" made without touching the production code.
			assertEquals(
				operandSequenceOf(twoPlusOne), operandSequenceOf(onePlusTwo),
				"The fixture proves nothing unless both formulas carry the same operand sequence"
			);

			assertNotEquals(
				twoPlusOne.getHash(), onePlusTwo.getHash(),
				"Two different family splits of one operand sequence must not share a formula-cache key"
			);
			// and the distinction is not academic - the two genuinely compute different records
			assertArrayEquals(new int[]{1, 2}, twoPlusOne.compute().getArray());
			assertArrayEquals(new int[]{1}, onePlusTwo.compute().getArray());
		}

		@Test
		@DisplayName("Swapping the plus and minus families changes the hash")
		void shouldHashSwappedFamiliesDifferently() {
			final Bitmap[] first = {bitmap(1, 2, 3)};
			final Bitmap[] second = {bitmap(2, 3, 4)};

			final RangeCountFormula forward = new RangeCountFormula(INDEX_ID, first, second);
			final RangeCountFormula backward = new RangeCountFormula(INDEX_ID, second, first);

			// this one passes even without the separator, because the operand sequence itself is reordered -
			// it is kept as the cheap sibling of the assertion above, not as a substitute for it
			assertNotEquals(forward.getHash(), backward.getHash());
			assertArrayEquals(new int[]{1}, forward.compute().getArray());
			assertArrayEquals(new int[]{4}, backward.compute().getArray());
		}

		@Test
		@DisplayName("Operand order inside a family is part of the formula identity")
		void shouldReportOrderAsSignificant() {
			final RangeCountFormula tested = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1)}, new Bitmap[]{bitmap(2)}
			);
			// the sibling of the separator invariant: were the order insignificant, `initFields` would sort the
			// hash array and the family boundary would stop being observable at all
			assertTrue(tested.isFormulaOrderSignificant());
		}

		/**
		 * Renders the operands in the order `includeAdditionalHash` folds them, with the family boundary erased.
		 *
		 * `toStringVerbose` prints the plus family, then the boundary marker, then the minus family; replacing the
		 * marker with an ordinary separator leaves exactly the operand sequence the hash would see if no
		 * `FAMILY_SEPARATOR` were folded in.
		 *
		 * @param formula the formula to render
		 * @return the operand sequence, boundary-free
		 */
		@Nonnull
		private String operandSequenceOf(@Nonnull RangeCountFormula formula) {
			final String verbose = formula.toStringVerbose();
			assertTrue(verbose.startsWith("RANGE COUNT: +["), "Unexpected rendering: " + verbose);
			return verbose.substring("RANGE COUNT: +[".length()).replace("] -[", ", ");
		}
	}

	@Nested
	@DisplayName("Declared premises")
	class Premises {

		@Test
		@DisplayName("An empty plus family is refused - EmptyFormula is the answer, not a zero-valued count")
		void shouldRejectAnEmptyPlusFamily() {
			// RangeIndex#createRangeCountFormulaIfNecessary short-circuits before it can build one, so this contract
			// is only reachable by a direct caller - which is precisely why it is asserted rather than trusted
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new RangeCountFormula(INDEX_ID, new Bitmap[0], new Bitmap[]{bitmap(1)})
			);
		}

		@Test
		@DisplayName("Inner formulas are refused - this formula carries bitmaps, not children")
		void shouldRefuseInnerFormulas() {
			final RangeCountFormula tested = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1, 2)}, new Bitmap[]{bitmap(2)}
			);
			assertEquals(0, tested.getInnerFormulas().length);
			assertThrows(UnsupportedOperationException.class, tested::getCloneWithInnerFormulas);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.getCloneWithComputationCallback(it -> {
				}, new ConstantFormula(bitmap(9)))
			);
		}

		@Test
		@DisplayName("A computation-callback clone computes the same records and fires the callback once")
		void shouldCloneWithComputationCallback() {
			final RangeCountFormula tested = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1, 2), bitmap(2)}, new Bitmap[]{bitmap(2)}
			);
			final AtomicInteger callbackCount = new AtomicInteger();
			final CacheableFormula clone = tested.getCloneWithComputationCallback(it -> callbackCount.incrementAndGet());

			assertNotSame(tested, clone);
			assertArrayEquals(tested.compute().getArray(), clone.compute().getArray());
			assertEquals(1, callbackCount.get(), "The computation callback must fire exactly once per computation");
		}

		@Test
		@DisplayName("A high-cardinality formula without a staleness token is refused outright")
		void shouldRefuseAHighCardinalityFormulaWithNoStalenessToken() {
			final Bitmap[] plus = transactionalOperands(TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY);
			final Bitmap[] minus = transactionalOperands(1);
			// `initFields` gathers the ids inside the constructor, so the refusal surfaces at construction time
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new RangeCountFormula(0L, plus, minus)
			);
		}
	}

	@Nested
	@DisplayName("Staleness token")
	class StalenessToken {

		@Test
		@DisplayName("At the cardinality threshold the per-operand ids are gathered, one above it the index id is")
		void shouldSeedTheStalenessTokenFromTheIndexIdAboveTheCardinalityThreshold() {
			final int threshold = TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY;

			// exactly AT the threshold - `plus.length + minus.length > EXCESSIVE_HIGH_CARDINALITY` is still false
			final RangeCountFormula atThreshold = new RangeCountFormula(
				INDEX_ID, transactionalOperands(threshold - 1), transactionalOperands(1)
			);
			assertEquals(threshold, atThreshold.gatherBitmapIdsInternal().length);

			// one above it - the per-bitmap tokens are dropped for the index's own id
			final RangeCountFormula aboveThreshold = new RangeCountFormula(
				INDEX_ID, transactionalOperands(threshold), transactionalOperands(1)
			);
			assertArrayEquals(new long[]{INDEX_ID}, aboveThreshold.gatherBitmapIdsInternal());
		}

		@Test
		@DisplayName("Non-transactional operands contribute no staleness token")
		void shouldGatherNoIdsForNonTransactionalOperands() {
			final RangeCountFormula tested = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1, 2)}, new Bitmap[]{bitmap(2)}
			);
			assertEquals(0, tested.gatherBitmapIdsInternal().length);
		}
	}

	@Nested
	@DisplayName("Planner inputs")
	class PlannerInputs {

		@Test
		@DisplayName("The estimated cardinality is never below the computed one")
		void shouldNeverUnderstateItsCardinality() {
			// the planner sizes downstream buffers from this number, so understating it is a correctness bug rather
			// than a mis-estimate; the union of the plus family is the tightest cheap upper bound there is
			for (int iteration = 0; iteration < 200; iteration++) {
				final Random random = new Random(iteration * 31L + 7L);
				final Bitmap[] plus = randomFamily(random, 1, 6, 40, 500);
				final Bitmap[] minus = randomFamily(random, 0, 6, 40, 500);
				final RangeCountFormula tested = new RangeCountFormula(INDEX_ID, plus, minus);
				final int computed = tested.compute().size();
				assertTrue(
					computed <= tested.getEstimatedCardinality(),
					() -> "Estimated cardinality " + tested.getEstimatedCardinality() +
						" understates the computed " + tested.compute().size() +
						" for plus=" + Arrays.toString(plus) + " minus=" + Arrays.toString(minus)
				);
			}
		}

		@Test
		@DisplayName("The actual cost prices every endpoint at the operation cost, exactly as the estimate does")
		void shouldPriceTheActualCostAtTheOperationCost() {
			final Bitmap[] plus = {bitmap(1, 2, 3), bitmap(2, 3)};
			final Bitmap[] minus = {bitmap(3)};
			final RangeCountFormula tested = new RangeCountFormula(INDEX_ID, plus, minus);
			// 3 + 2 endpoints on the plus side and 1 on the minus side - every one of them is scattered
			final long endpoints = 6L;

			// the cost accessors answer Long.MAX_VALUE until the result is memoized
			assertArrayEquals(new int[]{1, 2, 3}, tested.compute().getArray());

			// This formula carries no inner formulas, so there is nothing else to price and the honest actual cost
			// is the endpoint count scaled by the same per-element constant the estimate uses. An inner node may
			// leave the scaling to its parent - that is how the pair this formula replaced was priced, with the
			// multiplier applied one level up - but this node has no parent to apply it, so dropping it would
			// under-report the cost by a factor of getOperationCost().
			assertEquals(endpoints * tested.getOperationCost(), tested.getCost());
			assertEquals(tested.getEstimatedCost(), tested.getCost());

			// and that is what reaches the formula cache: `AbstractFormula#getCostToPerformanceInternal` divides the
			// actual cost by the result size, and CacheEden both admits and ranks entries on the resulting ratio
			assertEquals(tested.getCost() / 3, tested.getCostToPerformanceRatio());
		}

		@Test
		@DisplayName("Identical families cancel to nothing at the formula level too")
		void shouldCancelIdenticalFamiliesArithmetically() {
			// the pair this formula replaced needed an explicit `disentangle(X, X) = empty` guard in FormulaCloner;
			// here it has to fall out of the arithmetic alone, at the level the guard used to sit on
			final Bitmap[] family = {bitmap(1, 2, 3), bitmap(2, 5), bitmap(9)};
			final RangeCountFormula tested = new RangeCountFormula(INDEX_ID, family, family);
			assertTrue(tested.compute().isEmpty());
			assertFalse(tested.toString().isEmpty());
		}

		@Test
		@DisplayName("The verbose rendering names both families")
		void shouldRenderBothFamilies() {
			final RangeCountFormula tested = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1, 2)}, new Bitmap[]{bitmap(2)}
			);
			assertEquals("RANGE COUNT: +1 operands, -1 operands", tested.toString());
			assertTrue(tested.toStringVerbose().contains("] -["), tested.toStringVerbose());
		}

		/**
		 * Builds one randomised operand family.
		 *
		 * @param random       source of randomness
		 * @param minOperands  lower bound on operands
		 * @param maxOperands  upper bound on operands
		 * @param maxPerBitmap upper bound on record ids per operand
		 * @param idSpace      exclusive upper bound on a record id
		 * @return the family, never holding an empty operand
		 */
		@Nonnull
		private Bitmap[] randomFamily(
			@Nonnull Random random, int minOperands, int maxOperands, int maxPerBitmap, int idSpace
		) {
			final int operandCount = minOperands + random.nextInt(maxOperands - minOperands + 1);
			final Bitmap[] family = new Bitmap[operandCount];
			for (int i = 0; i < operandCount; i++) {
				final TreeSet<Integer> values = new TreeSet<>();
				final int size = 1 + random.nextInt(maxPerBitmap);
				for (int j = 0; j < size; j++) {
					values.add(random.nextInt(idSpace) + 1);
				}
				final int[] array = new int[values.size()];
				int index = 0;
				for (final Integer value : values) {
					array[index++] = value;
				}
				family[i] = bitmap(array);
			}
			return family;
		}
	}

	@Nested
	@DisplayName("Formula tree integration")
	class FormulaTree {

		@Test
		@DisplayName("A range count formula participates in a surrounding AND without being rebuilt")
		void shouldParticipateInASurroundingConjunction() {
			// signed counts: 1 -> +1, 2 -> +2, 3 -> +2 - 2 = 0, so record 3 cancels and the range count answers {1, 2}
			final RangeCountFormula rangeCount = new RangeCountFormula(
				INDEX_ID, new Bitmap[]{bitmap(1, 2, 3), bitmap(2, 3)}, new Bitmap[]{bitmap(3), bitmap(3)}
			);
			final Formula and = new AndFormula(rangeCount, new ConstantFormula(bitmap(2, 3, 4)));

			assertSame(rangeCount, and.getInnerFormulas()[0]);
			assertArrayEquals(new int[]{1, 2}, rangeCount.compute().getArray());
			// and the conjunction intersects that with the constant operand - record 3 is gone on both grounds
			assertArrayEquals(new int[]{2}, and.compute().getArray());
		}
	}
}
