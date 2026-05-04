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

package io.evitadb.core.query.algebra.price.translate;

import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PriceIdToEntityIdTranslateFormula} verifying construction, hashing, cloning,
 * cardinality estimation, and cache behavior.
 *
 * Note: full computation tests for `compute()` require a subtree containing
 * {@link io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula} implementations
 * with a real {@link io.evitadb.index.price.PriceListAndCurrencyPriceIndex}, which cannot be
 * constructed without mocking or full index setup. The contract tests below cover all aspects
 * that don't require actual price record resolution.
 *
 * @author evitaDB
 */
@DisplayName("PriceIdToEntityIdTranslateFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class PriceIdToEntityIdTranslateFormulaTest {

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final PriceIdToEntityIdTranslateFormula f1 = new PriceIdToEntityIdTranslateFormula(inner);
			final PriceIdToEntityIdTranslateFormula f2 = new PriceIdToEntityIdTranslateFormula(inner);

			assertEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final PriceIdToEntityIdTranslateFormula f1 = new PriceIdToEntityIdTranslateFormula(
				new ConstantFormula(new ArrayBitmap(1, 2))
			);
			final PriceIdToEntityIdTranslateFormula f2 = new PriceIdToEntityIdTranslateFormula(
				new ConstantFormula(new ArrayBitmap(3, 4))
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create clone with new inner formula")
		void shouldCreateCloneWithNewInnerFormula() {
			final ConstantFormula originalInner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PriceIdToEntityIdTranslateFormula original =
				new PriceIdToEntityIdTranslateFormula(originalInner);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20, 30));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(PriceIdToEntityIdTranslateFormula.class, clone);
			final PriceIdToEntityIdTranslateFormula typedClone =
				(PriceIdToEntityIdTranslateFormula) clone;
			assertSame(newInner, typedClone.getDelegate());
		}

		@Test
		@DisplayName("should throw when cloning with wrong number of inner formulas")
		void shouldThrowWhenCloningWithWrongNumberOfInnerFormulas() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1))
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
		@DisplayName("should create clone with computation callback")
		void shouldCreateCloneWithComputationCallback() {
			final PriceIdToEntityIdTranslateFormula original =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1))
				);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(5, 6));
			final CacheableFormula clone = original.getCloneWithComputationCallback(
				f -> { /* no-op callback */ }, newInner
			);

			assertInstanceOf(PriceIdToEntityIdTranslateFormula.class, clone);
			final PriceIdToEntityIdTranslateFormula typedClone =
				(PriceIdToEntityIdTranslateFormula) clone;
			assertSame(newInner, typedClone.getDelegate());
		}
	}

	@Nested
	@DisplayName("Cardinality and cost")
	class CardinalityAndCostTest {

		@Test
		@DisplayName("should delegate estimated cardinality to inner formula")
		void shouldDelegateEstimatedCardinalityToInnerFormula() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5))
				);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should report operation cost of 3527")
		void shouldReportOperationCostOf3527() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1))
				);

			assertEquals(3527, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("Contract details")
	class ContractTest {

		@Test
		@DisplayName("should return delegate formula via getDelegate")
		void shouldReturnDelegateFormulaViaGetDelegate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(inner);

			assertSame(inner, formula.getDelegate());
		}

		@Test
		@DisplayName("should return exactly one inner formula")
		void shouldReturnExactlyOneInnerFormula() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1))
				);

			assertEquals(1, formula.getInnerFormulas().length);
		}

		@Test
		@DisplayName("should produce fixed toString description")
		void shouldProduceFixedToStringDescription() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(
					new ConstantFormula(new ArrayBitmap(1))
				);

			assertEquals("TRANSLATE PRICE ID TO ENTITY ID", formula.toString());
		}

		@Test
		@DisplayName("should return empty result when inner formula produces empty bitmap")
		void shouldReturnEmptyResultWhenInnerFormulaProducesEmptyBitmap() {
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(EmptyFormula.INSTANCE);

			assertTrue(formula.compute().isEmpty());
		}

		@Test
		@DisplayName("should return price id bitmap from delegate")
		void shouldReturnPriceIdBitmapFromDelegate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(100, 200, 300));
			final PriceIdToEntityIdTranslateFormula formula =
				new PriceIdToEntityIdTranslateFormula(inner);

			// getPriceIdBitmap delegates to inner formula's compute
			assertArrayEquals(new int[]{100, 200, 300}, formula.getPriceIdBitmap().getArray());
		}
	}

}
