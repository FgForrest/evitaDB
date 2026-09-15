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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstantFormula} - the leaf formula that hands its delegate bitmap straight back as the result.
 *
 * The class carries a memoized cardinality, and what makes that memo safe is an invariant nothing else states:
 * {@code estimatedCost} is derived from the very same {@code delegate.size()} and is already frozen at construction
 * by {@code AbstractFormula#initFields}, so the memo introduces no new commitment - it freezes the same number, and
 * freezes it later. The tests below pin exactly that, plus the two properties that make the {@code -1} sentinel
 * unreachable as a legitimate cardinality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ConstantFormula — delegate bitmap leaf")
@Tag(ENGINE)
@Tag(QUERY)
class ConstantFormulaTest {

	@Nested
	@DisplayName("Cardinality memoization")
	class CardinalityMemoization {

		@Test
		@DisplayName("should report the delegate size as the estimated cardinality")
		void shouldReportDelegateSizeAsEstimatedCardinality() {
			assertEquals(5, new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getEstimatedCardinality());
		}

		@Test
		@DisplayName("should report the same cardinality on repeated calls")
		void shouldReportTheSameCardinalityOnRepeatedCalls() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			final int first = formula.getEstimatedCardinality();

			assertEquals(first, formula.getEstimatedCardinality());
			assertEquals(first, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should agree with the cost frozen at construction time")
		void shouldAgreeWithTheCostFrozenAtConstruction() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			// this is the invariant the memo rests on: `getEstimatedCostInternal()` returns the same
			// `delegate.size()` and the constructor freezes it, so memoizing the cardinality commits to nothing the
			// instance had not already committed to
			assertEquals(formula.getEstimatedCost(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should keep cardinality and cost in step after the memory is cleared")
		void shouldKeepCardinalityAndCostInStepAfterClearMemory() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));
			formula.compute();

			// `clearMemory` drops the computed result and the costs derived from it, but deliberately not
			// `estimatedCost` - leaving the cardinality memo alone is what keeps the pair consistent
			formula.clearMemory();

			assertEquals(formula.getEstimatedCost(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should not depend on the order cost and cardinality are read in")
		void shouldNotDependOnTheOrderCostAndCardinalityAreRead() {
			final ConstantFormula cardinalityFirst = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));
			final ConstantFormula costFirst = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			final int cardinality = cardinalityFirst.getEstimatedCardinality();
			final long cost = costFirst.getEstimatedCost();

			assertEquals(cost, cardinality);
			assertEquals(cardinalityFirst.getEstimatedCost(), costFirst.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Empty delegate")
	class EmptyDelegate {

		@Test
		@DisplayName("should reject an empty delegate bitmap")
		void shouldRejectAnEmptyDelegate() {
			assertThrows(GenericEvitaInternalError.class, () -> new ConstantFormula(new BaseBitmap()));
		}

		@Test
		@DisplayName("should never report the sentinel value as a cardinality")
		void shouldNeverReportTheSentinelValueAsCardinality() {
			// together with the rejection above this is what makes the `-1` memo sentinel unreachable as a real
			// value: a delegate always holds at least one key, so a duplicated resolution recomputes the identical
			// number rather than mistaking a legitimate cardinality for "not resolved yet"
			assertTrue(new ConstantFormula(new BaseBitmap(7)).getEstimatedCardinality() >= 1);
		}
	}

	@Nested
	@DisplayName("Result and identity")
	class ResultAndIdentity {

		@Test
		@DisplayName("should return the very delegate bitmap instance as the result")
		void shouldReturnTheDelegateBitmapInstance() {
			final BaseBitmap delegate = new BaseBitmap(1, 3, 4, 5, 8);

			assertSame(delegate, new ConstantFormula(delegate).compute());
		}

		@Test
		@DisplayName("should refuse to be cloned with inner formulas")
		void shouldRejectInnerFormulas() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			assertThrows(UnsupportedOperationException.class, formula::getCloneWithInnerFormulas);
		}

		@Test
		@DisplayName("should gather no transactional ids for a plain bitmap")
		void shouldGatherNoTransactionalIdsForPlainBitmap() {
			assertEquals(0, new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).gatherTransactionalIds().length);
		}

		@Test
		@DisplayName("should hash equal contents alike and differing contents apart")
		void shouldHashByDelegateContents() {
			// a plain bitmap carries no transactional id, so its contents are the only cache discriminator
			assertEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash()
			);
			assertNotEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 9)).getHash()
			);
		}
	}

}
