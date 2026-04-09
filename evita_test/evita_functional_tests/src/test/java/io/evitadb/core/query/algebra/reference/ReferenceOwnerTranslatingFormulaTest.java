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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ReferenceOwnerTranslatingFormula} verifying computation, memoization,
 * cloning, hashing, cardinality estimation, and cost behaviour.
 *
 * @author evitaDB
 */
@DisplayName("ReferenceOwnerTranslatingFormula")
class ReferenceOwnerTranslatingFormulaTest {

	private static final long TRANSACTIONAL_ID = 42L;
	private static final int WORST_CARDINALITY = 100;

	/**
	 * Simple expander that maps each referenced entity PK to a pair of owner PKs:
	 * `pk -> {pk * 10, pk * 10 + 1}`.
	 */
	private static final IntFunction<Bitmap> SIMPLE_EXPANDER =
		pk -> new BaseBitmap(pk * 10, pk * 10 + 1);

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should translate single inner PK to expanded owner PKs")
		void shouldTranslateSingleInnerPkToExpandedOwnerPks() {
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(3)),
				SIMPLE_EXPANDER
			);

			final Bitmap result = formula.compute();

			// PK 3 -> {30, 31}
			assertArrayEquals(new int[]{30, 31}, result.getArray());
		}

		@Test
		@DisplayName("should translate multiple inner PKs and union results")
		void shouldTranslateMultipleInnerPksAndUnionResults() {
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				SIMPLE_EXPANDER
			);

			final Bitmap result = formula.compute();

			// PK 1 -> {10, 11}, PK 2 -> {20, 21}, PK 3 -> {30, 31}
			assertArrayEquals(new int[]{10, 11, 20, 21, 30, 31}, result.getArray());
		}

		@Test
		@DisplayName("should handle overlapping expanded results by producing distinct union")
		void shouldHandleOverlappingExpandedResultsByProducingDistinctUnion() {
			// expander that always returns the same bitmap regardless of input
			final IntFunction<Bitmap> overlappingExpander = pk -> new BaseBitmap(1, 2, 3);
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(10, 20)),
				overlappingExpander
			);

			final Bitmap result = formula.compute();

			// union of {1,2,3} and {1,2,3} should be {1,2,3}
			assertArrayEquals(new int[]{1, 2, 3}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return EmptyBitmap when inner formula produces empty result")
		void shouldReturnEmptyBitmapWhenInnerFormulaProducesEmptyResult() {
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				EmptyFormula.INSTANCE,
				SIMPLE_EXPANDER
			);

			final Bitmap result = formula.compute();

			assertSame(EmptyBitmap.INSTANCE, result);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve expander and transactional ID in clone")
		void shouldPreserveExpanderAndTransactionalIdInClone() {
			final Formula innerFormula = new ConstantFormula(new ArrayBitmap(4));
			final ReferenceOwnerTranslatingFormula original = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				innerFormula,
				SIMPLE_EXPANDER
			);

			final Formula newInner = new ConstantFormula(new ArrayBitmap(7));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			final ReferenceOwnerTranslatingFormula clonedFormula =
				assertInstanceOf(ReferenceOwnerTranslatingFormula.class, clone);

			// clone should use the new inner formula — PK 7 -> {70, 71}
			assertArrayEquals(new int[]{70, 71}, clonedFormula.compute().getArray());
		}

		@Test
		@DisplayName("should preserve cardinality estimate in clone")
		void shouldPreserveCardinalityEstimateInClone() {
			final ReferenceOwnerTranslatingFormula original = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(1)),
				SIMPLE_EXPANDER
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(2))
			);

			assertEquals(WORST_CARDINALITY, clone.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final ReferenceOwnerTranslatingFormula formulaA = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				SIMPLE_EXPANDER
			);
			final ReferenceOwnerTranslatingFormula formulaB = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				SIMPLE_EXPANDER
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
			assertEquals(
				formulaA.getTransactionalIdHash(),
				formulaB.getTransactionalIdHash()
			);
		}
	}

	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should produce different hash for different transactional IDs")
		void shouldProduceDifferentHashForDifferentTransactionalIds() {
			final Formula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final ReferenceOwnerTranslatingFormula formulaA = createFormula(
				100L, WORST_CARDINALITY, inner, SIMPLE_EXPANDER
			);
			final ReferenceOwnerTranslatingFormula formulaB = createFormula(
				200L, WORST_CARDINALITY, inner, SIMPLE_EXPANDER
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final ReferenceOwnerTranslatingFormula formulaA = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(1)),
				SIMPLE_EXPANDER
			);
			final ReferenceOwnerTranslatingFormula formulaB = createFormula(
				TRANSACTIONAL_ID, WORST_CARDINALITY,
				new ConstantFormula(new ArrayBitmap(999)),
				SIMPLE_EXPANDER
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return worst cardinality passed in constructor")
		void shouldReturnWorstCardinalityPassedInConstructor() {
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, 500,
				new ConstantFormula(new ArrayBitmap(1)),
				SIMPLE_EXPANDER
			);

			assertEquals(500, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return zero worst cardinality when set to zero")
		void shouldReturnZeroWorstCardinalityWhenSetToZero() {
			final ReferenceOwnerTranslatingFormula formula = createFormula(
				TRANSACTIONAL_ID, 0,
				EmptyFormula.INSTANCE,
				SIMPLE_EXPANDER
			);

			assertEquals(0, formula.getEstimatedCardinality());
		}
	}

	/**
	 * Creates a {@link ReferenceOwnerTranslatingFormula} using the package-private constructor,
	 * which avoids the need for a real {@link io.evitadb.index.GlobalEntityIndex}.
	 *
	 * @param transactionalId   transactional ID used in hash computation
	 * @param worstCardinality  worst-case cardinality estimate
	 * @param innerFormula      inner formula producing referenced entity PKs
	 * @param expander          function mapping a referenced PK to owner entity PKs
	 * @return new formula instance
	 */
	@Nonnull
	private static ReferenceOwnerTranslatingFormula createFormula(
		long transactionalId,
		int worstCardinality,
		@Nonnull Formula innerFormula,
		@Nonnull IntFunction<Bitmap> expander
	) {
		return new ReferenceOwnerTranslatingFormula(
			transactionalId, worstCardinality, innerFormula, expander
		);
	}

}
