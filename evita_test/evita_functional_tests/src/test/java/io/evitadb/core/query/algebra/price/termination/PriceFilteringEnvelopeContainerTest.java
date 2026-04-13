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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PriceFilteringEnvelopeContainer} verifying OR-like union semantics,
 * caching, hashing, and metadata propagation.
 *
 * @author evitaDB
 */
@DisplayName("PriceFilteringEnvelopeContainer")
class PriceFilteringEnvelopeContainerTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final OffsetDateTime VALID_IN =
		OffsetDateTime.of(2025, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC);

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should compute OR-like union of inner formulas")
		void shouldComputeOrLikeUnionOfInnerFormulas() {
			final PriceFilteringEnvelopeContainer formula = createFormula(
				new String[]{"basic"}, CZK, null,
				new int[]{1, 3, 5}, new int[]{2, 3, 6}
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 2, 3, 5, 6}, result.getArray());
		}

		@Test
		@DisplayName("should return single formula result when only one inner formula")
		void shouldReturnSingleFormulaResultWhenOnlyOneInnerFormula() {
			final PriceFilteringEnvelopeContainer formula = new PriceFilteringEnvelopeContainer(
				new String[]{"basic"}, CZK, null,
				createConstantFormula(10, 20, 30)
			);

			assertArrayEquals(new int[]{10, 20, 30}, formula.compute().getArray());
		}

		@Test
		@DisplayName("should expose metadata via getters")
		void shouldExposeMetadataViaGetters() {
			final String[] priceLists = {"basic", "vip"};
			final PriceFilteringEnvelopeContainer formula = new PriceFilteringEnvelopeContainer(
				priceLists, CZK, VALID_IN,
				createConstantFormula(1)
			);

			assertSame(priceLists, formula.getPriceLists());
			assertSame(CZK, formula.getCurrency());
			assertSame(VALID_IN, formula.getValidIn());
		}

		@Test
		@DisplayName("should handle null metadata gracefully")
		void shouldHandleNullMetadataGracefully() {
			final PriceFilteringEnvelopeContainer formula = new PriceFilteringEnvelopeContainer(
				null, null, null,
				createConstantFormula(1, 2)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 2}, result.getArray());
			assertNull(formula.getPriceLists());
			assertNull(formula.getCurrency());
			assertNull(formula.getValidIn());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return empty bitmap when wrapping single empty formula")
		void shouldReturnEmptyBitmapWhenWrappingSingleEmptyFormula() {
			final PriceFilteringEnvelopeContainer formula = new PriceFilteringEnvelopeContainer(
				null, null, null,
				EmptyFormula.INSTANCE
			);

			assertEquals(0, formula.compute().size());
		}

		@Test
		@DisplayName("should return EmptyFormula when cloned with zero inner formulas")
		void shouldReturnEmptyFormulaWhenClonedWithZeroInnerFormulas() {
			final PriceFilteringEnvelopeContainer original = new PriceFilteringEnvelopeContainer(
				new String[]{"basic"}, CZK, null,
				createConstantFormula(1)
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve metadata in clone")
		void shouldPreserveMetadataInClone() {
			final String[] priceLists = {"basic"};
			final PriceFilteringEnvelopeContainer original = new PriceFilteringEnvelopeContainer(
				priceLists, CZK, VALID_IN,
				createConstantFormula(1, 2)
			);

			final Formula clone = original.getCloneWithInnerFormulas(createConstantFormula(3, 4));

			assertTrue(clone instanceof PriceFilteringEnvelopeContainer);
			final PriceFilteringEnvelopeContainer typedClone = (PriceFilteringEnvelopeContainer) clone;
			assertSame(priceLists, typedClone.getPriceLists());
			assertSame(CZK, typedClone.getCurrency());
			assertSame(VALID_IN, typedClone.getValidIn());
			assertArrayEquals(new int[]{3, 4}, typedClone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identical construction")
		void shouldProduceIdenticalHashForIdenticalConstruction() {
			final PriceFilteringEnvelopeContainer a = new PriceFilteringEnvelopeContainer(
				null, null, null,
				createConstantFormula(1, 2, 3)
			);
			final PriceFilteringEnvelopeContainer b = new PriceFilteringEnvelopeContainer(
				null, null, null,
				createConstantFormula(1, 2, 3)
			);

			assertEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce same hash regardless of metadata differences")
		void shouldProduceSameHashRegardlessOfMetadataDifferences() {
			// metadata (priceLists, currency, validIn) is NOT included in the hash
			final ConstantFormula innerFormula = createConstantFormula(1, 2, 3);
			final long hashA = new PriceFilteringEnvelopeContainer(
				new String[]{"basic"}, CZK, VALID_IN, innerFormula
			).getHash();
			final long hashB = new PriceFilteringEnvelopeContainer(
				new String[]{"vip"}, Currency.getInstance("EUR"), null, innerFormula
			).getHash();

			// includeAdditionalHash returns 0, so metadata doesn't affect hash
			assertEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should produce different hash for different inner data")
		void shouldProduceDifferentHashForDifferentInnerData() {
			final long hashA = new PriceFilteringEnvelopeContainer(
				null, null, null, createConstantFormula(1, 2, 3)
			).getHash();
			final long hashB = new PriceFilteringEnvelopeContainer(
				null, null, null, createConstantFormula(4, 5, 6)
			).getHash();

			assertNotEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return sum of inner formulas cardinality")
		void shouldReturnSumOfInnerFormulasCardinality() {
			final ConstantFormula a = createConstantFormula(1, 2, 3);
			final ConstantFormula b = createConstantFormula(4, 5);
			final PriceFilteringEnvelopeContainer formula = new PriceFilteringEnvelopeContainer(
				null, null, null, a, b
			);

			assertEquals(
				a.getEstimatedCardinality() + b.getEstimatedCardinality(),
				formula.getEstimatedCardinality()
			);
		}
	}

	/**
	 * Creates a {@link ConstantFormula} wrapping the given bitmap values.
	 *
	 * @param values the bitmap values
	 * @return new constant formula
	 */
	@Nonnull
	private static ConstantFormula createConstantFormula(int... values) {
		return new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values)));
	}

	/**
	 * Creates a {@link PriceFilteringEnvelopeContainer} with two inner constant formulas
	 * and optional metadata.
	 *
	 * @param priceLists the price list names (nullable)
	 * @param currency   the currency (nullable)
	 * @param validIn    the validity timestamp (nullable)
	 * @param valuesA    bitmap values for the first inner formula
	 * @param valuesB    bitmap values for the second inner formula
	 * @return new formula instance
	 */
	@Nonnull
	private static PriceFilteringEnvelopeContainer createFormula(
		@Nullable String[] priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull int[] valuesA,
		@Nonnull int[] valuesB
	) {
		return new PriceFilteringEnvelopeContainer(
			priceLists, currency, validIn,
			createConstantFormula(valuesA),
			createConstantFormula(valuesB)
		);
	}
}
