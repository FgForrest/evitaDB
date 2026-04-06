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
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PriceListCombinationFormula} verifying NOT-like subtraction semantics,
 * caching, hashing, and price list metadata.
 *
 * @author evitaDB
 */
@DisplayName("PriceListCombinationFormula")
class PriceListCombinationFormulaTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final PriceEvaluationContext DEFAULT_CONTEXT = new PriceEvaluationContext(
		null,
		new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.NONE)
	);

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should subtract entities present in subtracted formula from superset")
		void shouldSubtractEntitiesPresentInSubtractedFromSuperset() {
			final PriceListCombinationFormula formula = createFormula(
				"subtracted", "superset", DEFAULT_CONTEXT,
				new int[]{2, 4}, new int[]{1, 2, 3, 4, 5}
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5}, result.getArray());
		}

		@Test
		@DisplayName("should return full superset when subtracted is disjoint")
		void shouldReturnFullSupersetWhenSubtractedIsDisjoint() {
			final PriceListCombinationFormula formula = createFormula(
				"subtracted", "superset", DEFAULT_CONTEXT,
				new int[]{10, 20}, new int[]{1, 2, 3}
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 2, 3}, result.getArray());
		}

		@Test
		@DisplayName("should return empty when subtracted contains all superset elements")
		void shouldReturnEmptyWhenSubtractedContainsAllSupersetElements() {
			final PriceListCombinationFormula formula = createFormula(
				"subtracted", "superset", DEFAULT_CONTEXT,
				new int[]{1, 2, 3, 4, 5}, new int[]{1, 2, 3}
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}

		@Test
		@DisplayName("should expose price list names via getters")
		void shouldExposePriceListNamesViaGetters() {
			final PriceListCombinationFormula formula = createFormula(
				"vip", "basic", DEFAULT_CONTEXT,
				new int[]{1}, new int[]{1, 2, 3}
			);

			assertEquals("vip", formula.getSubtractedPriceListName());
			assertEquals("basic", formula.getPriceListName());
		}

		@Test
		@DisplayName("should expose price evaluation context")
		void shouldExposePriceEvaluationContext() {
			final PriceListCombinationFormula formula = createFormula(
				"subtracted", "superset", DEFAULT_CONTEXT,
				new int[]{1}, new int[]{1, 2}
			);

			assertSame(DEFAULT_CONTEXT, formula.getPriceEvaluationContext());
		}

		@Test
		@DisplayName("should return combined price list names")
		void shouldReturnCombinedPriceListNames() {
			final PriceListCombinationFormula formula = createFormula(
				"vip", "basic", DEFAULT_CONTEXT,
				new int[]{1}, new int[]{1, 2}
			);

			assertEquals("basic, vip", formula.getCombinedPriceListNames());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return empty when superset is empty")
		void shouldReturnEmptyWhenSupersetIsEmpty() {
			final PriceListCombinationFormula formula = createFormula(
				"subtracted", "superset", DEFAULT_CONTEXT,
				new int[]{1, 2}, new int[]{3}
			);

			assertArrayEquals(new int[]{3}, formula.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve price list names and context in clone")
		void shouldPreservePriceListNamesAndContextInClone() {
			final PriceListCombinationFormula original = createFormula(
				"vip", "basic", DEFAULT_CONTEXT,
				new int[]{1}, new int[]{1, 2, 3}
			);

			final ConstantFormula newSubtracted = createConstantFormula(2);
			final ConstantFormula newSuperset = createConstantFormula(1, 2, 3, 4);
			final Formula clone = original.getCloneWithInnerFormulas(newSubtracted, newSuperset);

			assertTrue(clone instanceof PriceListCombinationFormula);
			final PriceListCombinationFormula typedClone = (PriceListCombinationFormula) clone;
			assertEquals("vip", typedClone.getSubtractedPriceListName());
			assertEquals("basic", typedClone.getPriceListName());
			assertSame(DEFAULT_CONTEXT, typedClone.getPriceEvaluationContext());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identical construction")
		void shouldProduceIdenticalHashForIdenticalConstruction() {
			final PriceListCombinationFormula a = createFormula(
				"sub", "super", DEFAULT_CONTEXT,
				new int[]{1, 2}, new int[]{1, 2, 3}
			);
			final PriceListCombinationFormula b = createFormula(
				"sub", "super", DEFAULT_CONTEXT,
				new int[]{1, 2}, new int[]{1, 2, 3}
			);

			assertEquals(a.getHash(), b.getHash());
		}
	}

	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should produce different hash for different inner data")
		void shouldProduceDifferentHashForDifferentInnerData() {
			final long hashA = createFormula(
				"sub", "super", DEFAULT_CONTEXT,
				new int[]{1, 2}, new int[]{1, 2, 3}
			).getHash();
			final long hashB = createFormula(
				"sub", "super", DEFAULT_CONTEXT,
				new int[]{4, 5}, new int[]{4, 5, 6}
			).getHash();

			assertNotEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Class ID difference")
	class ClassIdDifferenceTest {

		@Test
		@DisplayName("should have different hash than NotFormula with same children")
		void shouldHaveDifferentHashThanNotFormulaWithSameChildren() {
			final ConstantFormula subtracted = createConstantFormula(1, 2);
			final ConstantFormula superset = createConstantFormula(1, 2, 3, 4);

			final long notFormulaHash = new NotFormula(subtracted, superset).getHash();
			final long priceListComboHash = new PriceListCombinationFormula(
				"sub", "super", DEFAULT_CONTEXT, subtracted, superset
			).getHash();

			assertNotEquals(notFormulaHash, priceListComboHash);
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
	 * Creates a {@link PriceListCombinationFormula} with the given price list names, context,
	 * and bitmap data for subtracted and superset formulas.
	 *
	 * @param subtractedName the name of the subtracted price list
	 * @param supersetName   the name of the superset price list
	 * @param context        the price evaluation context
	 * @param subtracted     the values for the subtracted formula
	 * @param superset       the values for the superset formula
	 * @return new formula instance
	 */
	@Nonnull
	private static PriceListCombinationFormula createFormula(
		@Nonnull String subtractedName,
		@Nonnull String supersetName,
		@Nonnull PriceEvaluationContext context,
		@Nonnull int[] subtracted,
		@Nonnull int[] superset
	) {
		return new PriceListCombinationFormula(
			subtractedName, supersetName, context,
			createConstantFormula(subtracted),
			createConstantFormula(superset)
		);
	}
}
