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

package io.evitadb.core.query.algebra.price.priceIndex;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PriceIndexContainerFormula} verifying delegation, caching, hashing,
 * and price index association.
 *
 * @author evitaDB
 */
@DisplayName("PriceIndexContainerFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class PriceIndexContainerFormulaTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should delegate compute to inner formula")
		void shouldDelegateComputeToInnerFormula() {
			final PriceIndexContainerFormula formula = createFormula("basic", CZK, 1, 3, 5);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5}, result.getArray());
		}

		@Test
		@DisplayName("should return delegate reference via getDelegate")
		void shouldReturnDelegateReferenceViaGetDelegate() {
			final ConstantFormula delegate = createConstantFormula(2, 4);
			final PriceIndexContainerFormula formula = new PriceIndexContainerFormula(
				createPriceIndex("vip", CZK), delegate
			);

			assertSame(delegate, formula.getDelegate());
		}

		@Test
		@DisplayName("should expose price index via getter")
		void shouldExposePriceIndexViaGetter() {
			final PriceListAndCurrencyPriceIndex<?> index = createPriceIndex("basic", CZK);
			final PriceIndexContainerFormula formula = new PriceIndexContainerFormula(
				index, createConstantFormula(1)
			);

			assertSame(index, formula.getPriceIndex());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return empty bitmap when wrapping empty formula")
		void shouldReturnEmptyBitmapWhenWrappingEmptyFormula() {
			final PriceIndexContainerFormula formula = new PriceIndexContainerFormula(
				createPriceIndex("basic", CZK), EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve price index in clone")
		void shouldPreservePriceIndexInClone() {
			final PriceListAndCurrencyPriceIndex<?> index = createPriceIndex("vip", EUR);
			final PriceIndexContainerFormula original = new PriceIndexContainerFormula(
				index, createConstantFormula(1, 2)
			);

			final Formula clone = original.getCloneWithInnerFormulas(createConstantFormula(3, 4));

			assertTrue(clone instanceof PriceIndexContainerFormula);
			final PriceIndexContainerFormula typedClone = (PriceIndexContainerFormula) clone;
			assertSame(index, typedClone.getPriceIndex());
		}

		@Test
		@DisplayName("should use provided inner formula in clone instead of original delegate")
		void shouldUseProvidedInnerFormulaInCloneInsteadOfOriginalDelegate() {
			final PriceIndexContainerFormula original = new PriceIndexContainerFormula(
				createPriceIndex("basic", CZK), createConstantFormula(1, 2, 3)
			);

			final ConstantFormula newDelegate = createConstantFormula(10, 20);
			final Formula clone = original.getCloneWithInnerFormulas(newDelegate);

			final PriceIndexContainerFormula typedClone = (PriceIndexContainerFormula) clone;
			assertSame(newDelegate, typedClone.getDelegate());
			assertArrayEquals(new int[]{10, 20}, typedClone.compute().getArray());
			assertArrayEquals(new int[]{1, 2, 3}, original.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identical construction")
		void shouldProduceIdenticalHashForIdenticalConstruction() {
			final PriceIndexContainerFormula a = createFormula("basic", CZK, 1, 2, 3);
			final PriceIndexContainerFormula b = createFormula("basic", CZK, 1, 2, 3);

			assertEquals(a.getHash(), b.getHash());
		}
	}

	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should produce different hash for different inner data")
		void shouldProduceDifferentHashForDifferentInnerData() {
			final long hashA = createFormula("basic", CZK, 1, 2, 3).getHash();
			final long hashB = createFormula("basic", CZK, 4, 5, 6).getHash();

			assertNotEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should delegate cardinality estimate to inner formula")
		void shouldDelegateCardinalityEstimateToInnerFormula() {
			final ConstantFormula delegate = createConstantFormula(1, 2, 3, 4, 5);
			final PriceIndexContainerFormula formula = new PriceIndexContainerFormula(
				createPriceIndex("basic", CZK), delegate
			);

			assertEquals(delegate.getEstimatedCardinality(), formula.getEstimatedCardinality());
		}
	}

	/**
	 * Creates a {@link PriceListAndCurrencyPriceSuperIndex} for the given price list and currency.
	 *
	 * @param priceList the price list name
	 * @param currency  the currency
	 * @return new price index instance
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceIndex<?> createPriceIndex(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) {
		return new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey(priceList, currency, PriceInnerRecordHandling.NONE)
		);
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
	 * Creates a {@link PriceIndexContainerFormula} with the given price list, currency, and bitmap values.
	 *
	 * @param priceList the price list name
	 * @param currency  the currency
	 * @param values    the bitmap values for the inner constant formula
	 * @return new formula instance
	 */
	@Nonnull
	private static PriceIndexContainerFormula createFormula(
		@Nonnull String priceList,
		@Nonnull Currency currency,
		int... values
	) {
		return new PriceIndexContainerFormula(
			createPriceIndex(priceList, currency),
			createConstantFormula(values)
		);
	}
}
