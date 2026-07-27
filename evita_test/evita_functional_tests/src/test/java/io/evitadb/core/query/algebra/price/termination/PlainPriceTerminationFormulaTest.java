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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PlainPriceTerminationFormula} verifying delegation, hashing, cloning,
 * and cardinality estimation behavior.
 *
 * @author evitaDB
 */
@DisplayName("PlainPriceTerminationFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class PlainPriceTerminationFormulaTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should delegate compute to inner formula")
		void shouldDelegateComputeToInnerFormula() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 3, 5, 7));
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5, 7}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap when inner formula is empty")
		void shouldReturnEmptyBitmapWhenInnerFormulaIsEmpty() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				EmptyFormula.INSTANCE, createPriceEvaluationContext("basic", CZK)
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return same reference on repeated compute calls (memoization)")
		void shouldReturnSameReferenceOnRepeatedComputeCalls() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(10, 20));
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve evaluation context when cloning with new inner formula")
		void shouldPreserveEvaluationContextWhenCloning() {
			final PriceEvaluationContext context = createPriceEvaluationContext("vip", EUR);
			final ConstantFormula originalInner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PlainPriceTerminationFormula original = new PlainPriceTerminationFormula(
				originalInner, context
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20, 30));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(PlainPriceTerminationFormula.class, clone);
			final PlainPriceTerminationFormula typedClone = (PlainPriceTerminationFormula) clone;
			assertSame(context, typedClone.getPriceEvaluationContext());
			assertArrayEquals(new int[]{10, 20, 30}, typedClone.compute().getArray());
		}

		@Test
		@DisplayName("should throw when cloning with wrong number of inner formulas")
		void shouldThrowWhenCloningWithWrongNumberOfInnerFormulas() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				EmptyFormula.INSTANCE, createPriceEvaluationContext("basic", CZK)
			);

			assertThrows(
				Exception.class,
				() -> formula.getCloneWithInnerFormulas(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				)
			);
		}

		@Test
		@DisplayName("should return self from getCloneWithPricePredicateFilteredOutResults")
		void shouldReturnSelfFromGetCloneWithPricePredicateFilteredOutResults() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), createPriceEvaluationContext("basic", CZK)
			);

			final Formula clone = formula.getCloneWithPricePredicateFilteredOutResults();

			assertSame(formula, clone);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createPriceEvaluationContext("basic", CZK);

			final PlainPriceTerminationFormula formula1 = new PlainPriceTerminationFormula(inner, context);
			final PlainPriceTerminationFormula formula2 = new PlainPriceTerminationFormula(inner, context);

			assertEquals(formula1.getHash(), formula2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different evaluation context")
		void shouldProduceDifferentHashForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PlainPriceTerminationFormula formula1 = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);
			final PlainPriceTerminationFormula formula2 = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("vip", CZK)
			);

			assertNotEquals(formula1.getHash(), formula2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different currency")
		void shouldProduceDifferentHashForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PlainPriceTerminationFormula formula1 = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);
			final PlainPriceTerminationFormula formula2 = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", EUR)
			);

			assertNotEquals(formula1.getHash(), formula2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createPriceEvaluationContext("basic", CZK);
			final PlainPriceTerminationFormula formula1 = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)), context
			);
			final PlainPriceTerminationFormula formula2 = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(4, 5, 6)), context
			);

			assertNotEquals(formula1.getHash(), formula2.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality and cost")
	class CardinalityAndCostTest {

		@Test
		@DisplayName("should delegate estimated cardinality to inner formula")
		void shouldDelegateEstimatedCardinalityToInnerFormula() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5));
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should have operation cost of 1")
		void shouldHaveOperationCostOfOne() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), createPriceEvaluationContext("basic", CZK)
			);

			assertEquals(1, formula.getOperationCost());
		}

		@Test
		@DisplayName("should have non-negative estimated and actual cost with estimated being an upper bound")
		void shouldHaveNonNegativeCostsWithEstimatedAsUpperBound() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);

			final long estimatedCost = formula.getEstimatedCost();
			// trigger computation to get actual cost
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(estimatedCost >= 0, "Estimated cost should be non-negative, was: " + estimatedCost);
			assertTrue(actualCost >= 0, "Actual cost should be non-negative, was: " + actualCost);
			assertTrue(
				estimatedCost >= actualCost,
				"Estimated cost (" + estimatedCost + ") should be >= actual cost (" + actualCost + ")"
			);
		}
	}

	@Nested
	@DisplayName("Contract details")
	class ContractTest {

		@Test
		@DisplayName("should return ALL predicate as requested predicate")
		void shouldReturnAllPredicateAsRequestedPredicate() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), createPriceEvaluationContext("basic", CZK)
			);

			assertSame(PriceAmountPredicate.ALL, formula.getRequestedPredicate());
		}

		@Test
		@DisplayName("should return delegate formula via getDelegate")
		void shouldReturnDelegateFormulaViaGetDelegate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				inner, createPriceEvaluationContext("basic", CZK)
			);

			assertSame(inner, formula.getDelegate());
		}

		@Test
		@DisplayName("should return exactly one inner formula")
		void shouldReturnExactlyOneInnerFormula() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), createPriceEvaluationContext("basic", CZK)
			);

			assertEquals(1, formula.getInnerFormulas().length);
		}

		@Test
		@DisplayName("should produce non-empty toString")
		void shouldProduceNonEmptyToString() {
			final PlainPriceTerminationFormula formula = new PlainPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), createPriceEvaluationContext("basic", CZK)
			);

			assertNotNull(formula.toString());
			assertFalse(formula.toString().isEmpty());
		}
	}

	/**
	 * Creates a {@link PriceEvaluationContext} with a single price index key.
	 *
	 * @param priceList the price list name
	 * @param currency  the currency
	 * @return configured price evaluation context
	 */
	@Nonnull
	private static PriceEvaluationContext createPriceEvaluationContext(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) {
		return new PriceEvaluationContext(
			null,
			new PriceIndexKey(priceList, currency, PriceInnerRecordHandling.NONE)
		);
	}
}
