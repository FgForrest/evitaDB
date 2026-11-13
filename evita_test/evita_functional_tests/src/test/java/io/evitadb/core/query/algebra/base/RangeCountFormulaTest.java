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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.EvitaInvalidUsageException;
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

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract of {@link RangeCountFormula} itself — its identity, its declared premises and the staleness
 * token it reports. {@link RangeCountKernelTest} covers the arithmetic; everything here is about the formula wrapper
 * around it.
 *
 * The identity half matters more than it looks. Two formulas that hash the same over the same data are served each
 * other's answer - `FormulaDeduplicator` keeps one instance for both; the operands are carried as two ORDERED
 * families, and their concatenation alone does not say where one family ends and the other begins.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(QUERY)
@Tag(CACHE)
@DisplayName("RangeCountFormula: identity, premises and staleness token")
class RangeCountFormulaTest {

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

	/**
	 * Verifies detached operand families independently of the legacy hash.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Declared formula key")
	class KeyTest {
		/** Each signed family contributes its own operand identity and hash bits. */
		@Test
		@DisplayName("Changing either signed family changes the key and hash64")
		void shouldIncludeBothFamilies() {
			final Formula base = new RangeCountFormula(new Bitmap[]{bitmap(1)}, new Bitmap[]{bitmap(2)});
			final Formula plus = new RangeCountFormula(new Bitmap[]{bitmap(3)}, new Bitmap[]{bitmap(2)});
			final Formula minus = new RangeCountFormula(new Bitmap[]{bitmap(1)}, new Bitmap[]{bitmap(3)});
			assertNotEquals(base.getKey(), plus.getKey());
			assertNotEquals(base.getKey().getHash64(), plus.getKey().getHash64());
			assertNotEquals(base.getKey(), minus.getKey());
			assertNotEquals(base.getKey().getHash64(), minus.getKey().getHash64());
		}

		/** The family boundary survives identical concatenated operand sequences. */
		@Test
		@DisplayName("Family splits and swapped signs have different keys")
		void shouldDistinguishFamilyBoundaryAndSigns() {
			final Bitmap a = bitmap(1, 2);
			final Bitmap b = bitmap(2, 3);
			final Bitmap c = bitmap(3);
			final Formula first = new RangeCountFormula(new Bitmap[]{a, b}, new Bitmap[]{c});
			final Formula split = new RangeCountFormula(new Bitmap[]{a}, new Bitmap[]{b, c});
			final Formula swapped = new RangeCountFormula(new Bitmap[]{c}, new Bitmap[]{a, b});
			assertNotEquals(first.getKey(), split.getKey());
			assertNotEquals(first.getKey().getHash64(), split.getKey().getHash64());
			assertNotEquals(first.getKey(), swapped.getKey());
			assertArrayEquals(new int[]{1, 2}, first.compute().getArray());
			assertArrayEquals(new int[]{1}, split.compute().getArray());
		}

		/** Canonicalization keeps the multiplicities used by signed counting. */
		@Test
		@DisplayName("Family permutations share keys while repeated operands retain their signs")
		void shouldCanonicalizeFamiliesAndKeepMultiplicity() {
			final Bitmap a = bitmap(1, 2);
			final Bitmap b = bitmap(2, 3);
			final Formula base = new RangeCountFormula(new Bitmap[]{a, b}, new Bitmap[]{a, b});
			final Formula reordered = new RangeCountFormula(new Bitmap[]{b, a}, new Bitmap[]{b, a});
			final Formula extraPlus = new RangeCountFormula(new Bitmap[]{a, b, a}, new Bitmap[]{a, b});
			final Formula extraMinus = new RangeCountFormula(new Bitmap[]{a, b}, new Bitmap[]{a, b, a});
			assertEquals(base.getKey(), reordered.getKey());
			assertEquals(base.getKey().getHash64(), reordered.getKey().getHash64());
			assertNotEquals(base.getKey(), extraPlus.getKey());
			assertNotEquals(base.getKey(), extraMinus.getKey());
			assertArrayEquals(new int[]{1, 2}, extraPlus.compute().getArray());
			assertTrue(base.compute().isEmpty());
		}

		/** Transactional operand versions remain identity inputs even at large widths. */
		@Test
		@DisplayName("Operand versions distinguish keys at large widths")
		void shouldIncludeOperandVersions() {
			final Formula first = new RangeCountFormula(transactionalOperands(101), new Bitmap[0]);
			final Formula second = new RangeCountFormula(transactionalOperands(101), new Bitmap[0]);
			assertNotEquals(first.getKey(), second.getKey());
			assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
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
				() -> new RangeCountFormula(new Bitmap[0], new Bitmap[]{bitmap(1)})
			);
		}

		@Test
		@DisplayName("Inner formulas are refused - this formula carries bitmaps, not children")
		void shouldRefuseInnerFormulas() {
			final RangeCountFormula tested = new RangeCountFormula(new Bitmap[]{bitmap(1, 2)}, new Bitmap[]{bitmap(2)}
			);
			assertEquals(0, tested.getInnerFormulas().length);
			assertThrows(UnsupportedOperationException.class, tested::getCloneWithInnerFormulas);
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
				final RangeCountFormula tested = new RangeCountFormula(plus, minus);
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
			final RangeCountFormula tested = new RangeCountFormula(plus, minus);
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
		}

		@Test
		@DisplayName("Identical families cancel to nothing at the formula level too")
		void shouldCancelIdenticalFamiliesArithmetically() {
			// the pair this formula replaced needed an explicit `disentangle(X, X) = empty` guard in FormulaCloner;
			// here it has to fall out of the arithmetic alone, at the level the guard used to sit on
			final Bitmap[] family = {bitmap(1, 2, 3), bitmap(2, 5), bitmap(9)};
			final RangeCountFormula tested = new RangeCountFormula(family, family);
			assertTrue(tested.compute().isEmpty());
			assertFalse(tested.toString().isEmpty());
		}

		@Test
		@DisplayName("The verbose rendering names both families")
		void shouldRenderBothFamilies() {
			final RangeCountFormula tested = new RangeCountFormula(new Bitmap[]{bitmap(1, 2)}, new Bitmap[]{bitmap(2)}
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
				new Bitmap[]{bitmap(1, 2, 3), bitmap(2, 3)}, new Bitmap[]{bitmap(3), bitmap(3)}
			);
			final Formula and = new AndFormula(rangeCount, new ConstantFormula(bitmap(2, 3, 4)));

			assertSame(rangeCount, and.getInnerFormulas()[0]);
			assertArrayEquals(new int[]{1, 2}, rangeCount.compute().getArray());
			// and the conjunction intersects that with the constant operand - record 3 is gone on both grounds
			assertArrayEquals(new int[]{2}, and.compute().getArray());
		}
	}
}
