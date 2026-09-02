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
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link ReferencedEntityIndexPrimaryKeyTranslatingFormula} verifying computation,
 * memoization, cloning, hashing, cardinality estimation, and cost behaviour.
 *
 * @author evitaDB
 */
@DisplayName("ReferencedEntityIndexPrimaryKeyTranslatingFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferencedEntityIndexPrimaryKeyTranslatingFormulaTest {

	private static final long[] TRANSACTIONAL_IDS = {42L};
	private static final long[] ALT_TRANSACTIONAL_IDS = {99L};
	private static final int WORST_CARDINALITY = 100;

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should translate referenced entity PKs to index PKs without superset")
		void shouldTranslateReferencedEntityPksToIndexPksWithoutSuperset() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				// inner formula returns referenced entity PKs {1, 2}
				new ConstantFormula(new ArrayBitmap(1, 2))
			);

			final Bitmap result = formula.compute();

			// referenced entity PK 1 -> index PKs {10, 11}, PK 2 -> index PKs {20, 21}
			assertArrayEquals(new int[]{10, 11, 20, 21}, result.getArray());
		}

		@Test
		@DisplayName("should filter by superset when provided")
		void shouldFilterBySupersetWhenProvided() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			// superset only allows referenced entity PK 1 — PK 2 should be filtered out
			final Bitmap superSet = new BaseBitmap(1);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				superSet, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1, 2))
			);

			final Bitmap result = formula.compute();

			// only PK 1 passes the superset filter -> index PKs {10, 11}
			assertArrayEquals(new int[]{10, 11}, result.getArray());
		}

		@Test
		@DisplayName("should apply expansion function before translation")
		void shouldApplyExpansionFunctionBeforeTranslation() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			// expansion function doubles each PK (maps 5 -> {1, 2})
			final UnaryOperator<Bitmap> expansion = bm -> new BaseBitmap(1, 2);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				expansion,
				// inner formula produces PK 5 (not in the index directly),
				// but expansion replaces it with {1, 2}
				new ConstantFormula(new ArrayBitmap(5))
			);

			final Bitmap result = formula.compute();

			// after expansion: {1, 2} -> index PKs {10, 11, 20, 21}
			assertArrayEquals(new int[]{10, 11, 20, 21}, result.getArray());
		}

		@Test
		@DisplayName("should return empty when superset eliminates all PKs")
		void shouldReturnEmptyWhenSupersetEliminatesAllPks() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			// superset contains PK 999 which doesn't match inner formula result {1, 2}
			final Bitmap superSet = new BaseBitmap(999);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				superSet, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1, 2))
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}
	}

	@Nested
	@DisplayName("Empty inputs")
	class EmptyInputsTest {

		@Test
		@DisplayName("should return EmptyBitmap when inner formula produces empty result")
		void shouldReturnEmptyBitmapWhenInnerFormulaProducesEmptyResult() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertSame(EmptyBitmap.INSTANCE, result);
		}

		@Test
		@DisplayName("should return EmptyBitmap when expansion function returns empty")
		void shouldReturnEmptyBitmapWhenExpansionFunctionReturnsEmpty() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final UnaryOperator<Bitmap> emptyExpansion = bm -> EmptyBitmap.INSTANCE;
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				emptyExpansion,
				new ConstantFormula(new ArrayBitmap(1))
			);

			final Bitmap result = formula.compute();

			assertSame(EmptyBitmap.INSTANCE, result);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve index and cardinality in clone with new inner formula")
		void shouldPreserveIndexAndCardinalityInCloneWithNewInnerFormula() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula original = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1))
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(2))
			);

			assertInstanceOf(ReferencedEntityIndexPrimaryKeyTranslatingFormula.class, clone);
			// clone should use the new inner formula — PK 2 -> index PKs {20, 21}
			assertArrayEquals(new int[]{20, 21}, clone.compute().getArray());
			assertEquals(WORST_CARDINALITY, clone.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should preserve superset in clone")
		void shouldPreserveSupersetInClone() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final Bitmap superSet = new BaseBitmap(2);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula original = createFormula(
				superSet, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1, 2))
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(1, 2))
			);

			// superset {2} should still filter, so only PK 2 -> {20, 21}
			assertArrayEquals(new int[]{20, 21}, clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaA = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1, 2))
			);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaB = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1, 2))
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
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final Formula inner = new ConstantFormula(new ArrayBitmap(1));
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaA = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(), inner
			);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaB = createFormula(
				null, ALT_TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(), inner
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash when empty vs non-empty transactional IDs")
		void shouldProduceDifferentHashWhenEmptyVsNonEmptyTransactionalIds() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final Formula inner = new ConstantFormula(new ArrayBitmap(1));
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaA = createFormula(
				null, ArrayUtils.EMPTY_LONG_ARRAY, index, WORST_CARDINALITY,
				UnaryOperator.identity(), inner
			);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaB = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(), inner
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaA = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1))
			);
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formulaB = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(999))
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
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, 500,
				UnaryOperator.identity(),
				new ConstantFormula(new ArrayBitmap(1))
			);

			assertEquals(500, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return zero worst cardinality when set to zero")
		void shouldReturnZeroWorstCardinalityWhenSetToZero() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, 0,
				UnaryOperator.identity(),
				EmptyFormula.INSTANCE
			);

			assertEquals(0, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Inner formulas")
	class InnerFormulasTest {

		@Test
		@DisplayName("should expose the single inner formula")
		void shouldExposeSingleInnerFormula() {
			final ReferencedTypeEntityIndex index = createPopulatedIndex();
			final Formula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final ReferencedEntityIndexPrimaryKeyTranslatingFormula formula = createFormula(
				null, TRANSACTIONAL_IDS, index, WORST_CARDINALITY,
				UnaryOperator.identity(), inner
			);

			final Formula[] innerFormulas = formula.getInnerFormulas();

			assertEquals(1, innerFormulas.length);
			assertSame(inner, innerFormulas[0]);
		}
	}

	/**
	 * Creates a {@link ReferencedTypeEntityIndex} populated with test data. The index maps:
	 *
	 * - referenced entity PK 1 -> index PKs {10, 11}
	 * - referenced entity PK 2 -> index PKs {20, 21}
	 * - referenced entity PK 3 -> index PK {30}
	 *
	 * @return populated index instance
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex createPopulatedIndex() {
		final EntityIndexKey indexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.DEFAULT_SCOPE, "testReference"
		);
		final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
			1, "testEntity", indexKey
		);
		// insert index PK 10 pointing to referenced entity PK 1
		index.insertPrimaryKeyIfMissing(10, 1);
		// insert index PK 11 also pointing to referenced entity PK 1
		index.insertPrimaryKeyIfMissing(11, 1);
		// insert index PK 20 pointing to referenced entity PK 2
		index.insertPrimaryKeyIfMissing(20, 2);
		// insert index PK 21 also pointing to referenced entity PK 2
		index.insertPrimaryKeyIfMissing(21, 2);
		// insert index PK 30 pointing to referenced entity PK 3
		index.insertPrimaryKeyIfMissing(30, 3);
		return index;
	}

	/**
	 * Creates a {@link ReferencedEntityIndexPrimaryKeyTranslatingFormula} using the package-private
	 * constructor, which allows direct control over all parameters without requiring complex
	 * schema and scope setup.
	 *
	 * @param referencedEntitySuperSet optional superset bitmap to restrict the translation
	 * @param transactionalIds         transactional IDs for cache-key hashing
	 * @param index                    target index for the translation
	 * @param worstCardinality         worst-case cardinality estimate
	 * @param expansionFunction        function applied to inner result before translation
	 * @param innerFormula             inner formula producing referenced entity PKs
	 * @return new formula instance
	 */
	@Nonnull
	private static ReferencedEntityIndexPrimaryKeyTranslatingFormula createFormula(
		@Nullable Bitmap referencedEntitySuperSet,
		@Nonnull long[] transactionalIds,
		@Nonnull ReferencedTypeEntityIndex index,
		int worstCardinality,
		@Nonnull UnaryOperator<Bitmap> expansionFunction,
		@Nonnull Formula innerFormula
	) {
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			referencedEntitySuperSet, transactionalIds, index,
			worstCardinality, expansionFunction, innerFormula
		);
	}

}
