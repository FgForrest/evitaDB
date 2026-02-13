/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.filter;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive test suite for {@link FormulaOptimizer} verifying correctness of boolean algebra
 * optimizations applied during query formula AST post-processing.
 *
 * <p>The optimizer applies three categories of structural rewrites:</p>
 * <ul>
 *   <li><b>Conjunction elimination</b> — an AND-like container with any {@link EmptyFormula} child
 *       is replaced by {@link EmptyFormula} (the conjunction is unsatisfiable)</li>
 *   <li><b>OR unwrapping</b> — an {@link OrFormula} whose only non-empty child is a single formula
 *       is replaced by that child (the container is redundant)</li>
 *   <li><b>DeMorgan's law</b> — {@code S \ (A ∪ B)} is rewritten as
 *       {@code (S \ A) ∩ (S \ B)}, replacing an expensive OR inside a NOT with cheaper
 *       AND of NOTs</li>
 * </ul>
 *
 * <p>Tests are organized into nested groups by category; each group verifies both structural
 * properties of the optimized tree and semantic equivalence with the original formula.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("FormulaOptimizer correctness tests")
class FormulaOptimizerTest {

	/**
	 * Runs the {@link FormulaOptimizer} on the given input formula and returns the optimized result.
	 *
	 * @param input the formula tree to optimize
	 * @return the optimized formula, never {@code null} (returns {@link EmptyFormula} for empty trees)
	 */
	@Nonnull
	private static Formula optimize(@Nonnull Formula input) {
		final FormulaOptimizer optimizer = new FormulaOptimizer();
		input.accept(optimizer);
		return optimizer.getPostProcessedFormula();
	}

	/**
	 * Creates a {@link ConstantFormula} holding a bitmap with the given integer values.
	 *
	 * @param values the primary keys to include in the bitmap; must not be empty
	 * @return a new constant formula wrapping the specified values
	 */
	@Nonnull
	private static ConstantFormula constant(@Nonnull int... values) {
		return new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values)));
	}

	@Nested
	@DisplayName("Conjunction (AND) with EmptyFormula elimination")
	class ConjunctionEliminationTest {

		@Test
		@DisplayName("AND(A, EMPTY) should collapse to EmptyFormula")
		void andWithTrailingEmpty_shouldCollapseToEmpty() {
			final Formula input = new AndFormula(
				constant(1, 2, 3),
				EmptyFormula.INSTANCE
			);
			final Formula result = optimize(input);
			assertInstanceOf(EmptyFormula.class, result);
		}

		@Test
		@DisplayName("AND(EMPTY, A) should collapse to EmptyFormula")
		void andWithLeadingEmpty_shouldCollapseToEmpty() {
			final Formula input = new AndFormula(
				EmptyFormula.INSTANCE,
				constant(1, 2, 3)
			);
			final Formula result = optimize(input);
			assertInstanceOf(EmptyFormula.class, result);
		}

		@Test
		@DisplayName("AND(A, B, EMPTY) should collapse to EmptyFormula")
		void andWithEmptyAmongMultipleChildren_shouldCollapseToEmpty() {
			final Formula input = new AndFormula(
				constant(1, 2, 3),
				constant(2, 3, 4),
				EmptyFormula.INSTANCE
			);
			final Formula result = optimize(input);
			assertInstanceOf(EmptyFormula.class, result);
		}

		@Test
		@DisplayName("AND(A, B) without empty children should remain unchanged")
		void andWithoutEmpty_shouldRemainUnchanged() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(2, 3, 4);
			final Formula input = new AndFormula(a, b);
			final Formula result = optimize(input);

			assertInstanceOf(AndFormula.class, result);
			assertArrayEquals(new int[]{2, 3}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("OR formula simplification")
	class OrSimplificationTest {

		@Test
		@DisplayName("OR(A, EMPTY) should unwrap to A")
		void orWithOneNonEmptyAndTrailingEmpty_shouldUnwrap() {
			final ConstantFormula a = constant(1, 2, 3);
			final Formula input = new OrFormula(a, EmptyFormula.INSTANCE);
			final Formula result = optimize(input);

			assertSame(a, result);
		}

		@Test
		@DisplayName("OR(EMPTY, A) should unwrap to A")
		void orWithOneNonEmptyAndLeadingEmpty_shouldUnwrap() {
			final ConstantFormula a = constant(1, 2, 3);
			final Formula input = new OrFormula(EmptyFormula.INSTANCE, a);
			final Formula result = optimize(input);

			assertSame(a, result);
		}

		@Test
		@DisplayName("OR(EMPTY, EMPTY) should return EmptyFormula")
		void orWithAllEmpty_shouldReturnEmpty() {
			final Formula input = new OrFormula(EmptyFormula.INSTANCE, EmptyFormula.INSTANCE);
			final Formula result = optimize(input);

			assertInstanceOf(EmptyFormula.class, result);
		}

		@Test
		@DisplayName("OR(A, B) with multiple non-empty children should remain unchanged")
		void orWithMultipleNonEmpty_shouldRemainUnchanged() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(4, 5, 6);
			final Formula input = new OrFormula(a, b);
			final Formula result = optimize(input);

			assertInstanceOf(OrFormula.class, result);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.compute().getArray());
		}

		@Test
		@DisplayName("OR(A, EMPTY, B) with two non-empty children should keep OR semantics")
		void orWithTwoNonEmptyAndOneEmpty_shouldKeepOrWithNonEmpty() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(4, 5, 6);
			final Formula input = new OrFormula(a, EmptyFormula.INSTANCE, b);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("DeMorgan's law structural correctness — S \\ (A ∪ B) = (S \\ A) ∩ (S \\ B)")
	class DeMorganStructuralTest {

		@Test
		@DisplayName("NOT(OR(A, B), S) should become AND(NOT(A, S), NOT(B, S))")
		void notWithOrSubtracted_shouldApplyDeMorgan_twoChildren() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(new OrFormula(a, b), s);
			final Formula result = optimize(input);

			assertInstanceOf(AndFormula.class, result);
			assertEquals(2, result.getInnerFormulas().length);
			assertInstanceOf(NotFormula.class, result.getInnerFormulas()[0]);
			assertInstanceOf(NotFormula.class, result.getInnerFormulas()[1]);
		}

		@Test
		@DisplayName("NOT(OR(A, B, C), S) should become AND(NOT(A, S), NOT(B, S), NOT(C, S))")
		void notWithOrSubtracted_shouldApplyDeMorgan_threeChildren() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula c = constant(5, 6);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7);

			final Formula input = new NotFormula(new OrFormula(a, b, c), s);
			final Formula result = optimize(input);

			assertInstanceOf(AndFormula.class, result);
			assertEquals(3, result.getInnerFormulas().length);
			for (Formula child : result.getInnerFormulas()) {
				assertInstanceOf(NotFormula.class, child);
			}
		}

		@Test
		@DisplayName("NOT(A, S) with non-OR subtracted should not trigger DeMorgan")
		void notWithNonOrSubtracted_shouldNotApplyDeMorgan() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(a, s);
			final Formula result = optimize(input);

			assertInstanceOf(NotFormula.class, result);
			assertArrayEquals(new int[]{3, 4, 5}, result.compute().getArray());
		}

		@Test
		@DisplayName("NOT(A, OR(X, Y)) should not trigger DeMorgan when only superset is OR")
		void notWithOrSuperset_shouldNotApplyDeMorgan() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula x = constant(1, 2, 3);
			final ConstantFormula y = constant(4, 5, 6);

			final Formula input = new NotFormula(a, new OrFormula(x, y));
			final Formula result = optimize(input);

			assertFalse(
				result instanceof AndFormula,
				"DeMorgan should not trigger when superset is OR"
			);
			assertArrayEquals(new int[]{3, 4, 5, 6}, result.compute().getArray());
		}

		@Test
		@DisplayName("NOT(OR(A, EMPTY), S) with single-child OR should unwrap OR first")
		void notWithSingleChildOr_shouldUnwrapFirst() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(
				new OrFormula(a, EmptyFormula.INSTANCE),
				s
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{3, 4, 5}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("DeMorgan's law semantic correctness — optimized compute() must equal original")
	class DeMorganSemanticTest {

		@Test
		@DisplayName("Overlapping bitmaps: S={1..8}, A={1,2,3}, B={3,4,5}")
		void overlappingBitmaps() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(3, 4, 5);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7, 8);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{6, 7, 8}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Disjoint bitmaps: S={1..6}, A={1,2}, B={5,6}")
		void disjointBitmaps() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(5, 6);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{3, 4}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Identical bitmaps: A and B are the same set")
		void identicalBitmaps() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(1, 2, 3);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{4, 5}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Subtracted covers entire superset yielding empty result")
		void subtractedCoversEntireSuperset() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(2, 3);
			final ConstantFormula s = constant(1, 2, 3);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[0], optimized.compute().getArray());
		}

		@Test
		@DisplayName("Three OR children: S={1..10}, A={1,2,3}, B={4,5,6}, C={7,8}")
		void threeOrChildren() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(4, 5, 6);
			final ConstantFormula c = constant(7, 8);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

			final Formula original = new NotFormula(new OrFormula(a, b, c), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{9, 10}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Subtracted union exceeds superset yielding empty result")
		void subtractedExceedsSuperset() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(4, 5, 6);
			final ConstantFormula s = constant(3, 4, 5);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[0], optimized.compute().getArray());
		}

		@Test
		@DisplayName("Subtracted elements partially outside superset")
		void subtractedPartiallyOutsideSuperset() {
			final ConstantFormula a = constant(2, 3, 10);
			final ConstantFormula b = constant(4, 20);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{1, 5}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Subtracted has no overlap with superset — superset returned unchanged")
		void noOverlapBetweenSubtractedAndSuperset() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula s = constant(10, 11, 12);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[]{10, 11, 12}, optimized.compute().getArray());
		}

		@Test
		@DisplayName("Single-element bitmaps")
		void singleElementBitmaps() {
			final ConstantFormula a = constant(1);
			final ConstantFormula b = constant(2);
			final ConstantFormula s = constant(1);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());
			assertArrayEquals(new int[0], optimized.compute().getArray());
		}

		@Test
		@DisplayName("Large bitmaps (hundreds of elements)")
		void largeBitmaps() {
			final int[] aValues = new int[100];
			final int[] bValues = new int[100];
			final int[] sValues = new int[300];

			for (int i = 0; i < 100; i++) aValues[i] = i + 1;
			for (int i = 0; i < 100; i++) bValues[i] = i + 51;
			for (int i = 0; i < 300; i++) sValues[i] = i + 1;

			final ConstantFormula a = constant(aValues);
			final ConstantFormula b = constant(bValues);
			final ConstantFormula s = constant(sValues);

			final Formula original = new NotFormula(new OrFormula(a, b), s);
			final Formula optimized = optimize(original);

			assertArrayEquals(original.compute().getArray(), optimized.compute().getArray());

			final int[] expected = new int[150];
			for (int i = 0; i < 150; i++) expected[i] = i + 151;
			assertArrayEquals(expected, optimized.compute().getArray());
		}
	}

	@Nested
	@DisplayName("NotFormula children optimization edge cases")
	class NotFormulaChildOptimizationTest {

		@Test
		@DisplayName("NOT where subtracted collapses to empty should return superset")
		void notWhereSubtractedBecomesEmpty_shouldReturnSuperset() {
			final ConstantFormula x = constant(1, 2, 3);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(
				new AndFormula(x, EmptyFormula.INSTANCE),
				s
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, result.compute().getArray());
		}

		@Test
		@DisplayName("NOT where superset collapses to empty should return EmptyFormula")
		void notWhereSupersetBecomesEmpty_shouldReturnEmpty() {
			final ConstantFormula x = constant(1, 2, 3);
			final ConstantFormula y = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(
				x,
				new AndFormula(y, EmptyFormula.INSTANCE)
			);
			final Formula result = optimize(input);

			assertInstanceOf(EmptyFormula.class, result);
		}

		@Test
		@DisplayName("NOT with both children surviving should preserve semantics")
		void notWithBothChildrenSurviving_shouldPreserveSemantics() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula s = constant(1, 2, 3, 4);

			final Formula input = new NotFormula(a, s);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{3, 4}, result.compute().getArray());
		}

		@Test
		@DisplayName("NOT where deeply nested subtracted AND collapses should return superset")
		void notWhereSubtractedIsNestedAndWithEmpty_shouldReturnSuperset() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6);

			final Formula input = new NotFormula(
				new AndFormula(a, new AndFormula(b, EmptyFormula.INSTANCE)),
				s
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.compute().getArray());
		}

		@Test
		@DisplayName("NOT with bitmap-based OR should NOT collapse to EmptyFormula")
		void notWithBitmapBasedOr_shouldNotCollapseToEmpty() {
			// Bitmap-based OrFormula uses the (long[], Bitmap...) constructor,
			// which stores data in this.bitmaps and getInnerFormulas() returns empty array.
			// DeMorgan must not produce EmptyFormula for this case.
			final OrFormula bitmapOr = new OrFormula(
				new long[]{1L},
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(4, 5)
			);
			final ConstantFormula superset = constant(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
			final NotFormula notFormula = new NotFormula(bitmapOr, superset);
			final AttributeFormula input = new AttributeFormula(
				false,
				new AttributeKey("quantity"),
				notFormula
			);

			final Formula result = optimize(input);

			assertNotEquals(EmptyFormula.INSTANCE, result,
				"Bitmap-based OR inside NOT must not collapse to EmptyFormula");
			assertArrayEquals(new int[]{6, 7, 8, 9, 10}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Nested and complex formula trees")
	class NestedFormulaTest {

		@Test
		@DisplayName("AND(OR(A, EMPTY), B) should unwrap the OR inside AND")
		void andContainingOrWithEmpty_shouldUnwrapOrInsideAnd() {
			final ConstantFormula a = constant(1, 2, 3, 4);
			final ConstantFormula b = constant(2, 3, 4, 5);

			final Formula input = new AndFormula(
				new OrFormula(a, EmptyFormula.INSTANCE),
				b
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{2, 3, 4}, result.compute().getArray());
		}

		@Test
		@DisplayName("AND(NOT(OR(A, B), S), C) should apply DeMorgan inside AND")
		void andContainingDeMorganNot_shouldProduceCorrectResult() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(3, 4, 5);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7, 8);
			final ConstantFormula c = constant(7, 8, 9);

			final Formula input = new AndFormula(
				new NotFormula(new OrFormula(a, b), s),
				c
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{7, 8}, result.compute().getArray());
		}

		@Test
		@DisplayName("OR(AND(A, EMPTY), B) should eliminate dead AND branch")
		void orContainingAndWithEmpty_shouldRemoveDeadBranch() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(4, 5, 6);

			final Formula input = new OrFormula(
				new AndFormula(a, EmptyFormula.INSTANCE),
				b
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{4, 5, 6}, result.compute().getArray());
		}

		@Test
		@DisplayName("Deeply nested tree with AND/OR/NOT combines all optimizations correctly")
		void deeplyNestedOptimization_shouldProduceCorrectResult() {
			final ConstantFormula x = constant(99);
			final ConstantFormula a = constant(1, 2, 3, 4, 5);
			final ConstantFormula b = constant(1, 2);
			final ConstantFormula c = constant(3, 4);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

			final Formula input = new AndFormula(
				new OrFormula(
					new AndFormula(x, EmptyFormula.INSTANCE),
					a
				),
				new NotFormula(new OrFormula(b, c), s)
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{5}, result.compute().getArray());
		}

		@Test
		@DisplayName("OR(NOT(OR(A, B), S), C) should apply DeMorgan inside OR")
		void notInsideOr_shouldHandleCorrectly() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6);
			final ConstantFormula c = constant(1, 7);

			final Formula input = new OrFormula(
				new NotFormula(new OrFormula(a, b), s),
				c
			);
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{1, 5, 6, 7}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Identity, idempotency and structural properties")
	class StructuralPropertyTest {

		@Test
		@DisplayName("Unchanged formula should preserve same object identity")
		void unchangedFormula_shouldPreserveIdentity() {
			final ConstantFormula a = constant(1, 2, 3);
			final Formula result = optimize(a);
			assertSame(a, result);
		}

		@Test
		@DisplayName("EmptyFormula should pass through as EmptyFormula.INSTANCE")
		void emptyFormula_shouldReturnEmptyInstance() {
			final Formula result = optimize(EmptyFormula.INSTANCE);
			assertSame(EmptyFormula.INSTANCE, result);
		}

		@Test
		@DisplayName("Optimizing an already-optimized formula should produce the same result")
		void optimizer_shouldBeIdempotent() {
			final ConstantFormula a = constant(1, 2, 3);
			final ConstantFormula b = constant(3, 4, 5);
			final ConstantFormula s = constant(1, 2, 3, 4, 5, 6, 7);

			final Formula input = new NotFormula(new OrFormula(a, b), s);
			final Formula optimizedOnce = optimize(input);
			final Formula optimizedTwice = optimize(optimizedOnce);

			assertArrayEquals(
				optimizedOnce.compute().getArray(),
				optimizedTwice.compute().getArray()
			);
		}

		@Test
		@DisplayName("AND with identical non-empty children should compute correctly")
		void andWithIdenticalChildren_shouldComputeCorrectly() {
			final ConstantFormula a = constant(1, 2, 3);
			final Formula input = new AndFormula(a, constant(1, 2, 3));
			final Formula result = optimize(input);

			assertArrayEquals(new int[]{1, 2, 3}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("NotFormula getter methods verification")
	class NotFormulaGetterTest {

		@Test
		@DisplayName("getSubtractedFormula() and getSupersetFormula() should return correct formulas")
		void notFormulaGetters_shouldReturnCorrectFormulas() {
			final ConstantFormula subtracted = constant(1, 2);
			final ConstantFormula superset = constant(1, 2, 3, 4);

			final NotFormula notFormula = new NotFormula(subtracted, superset);

			assertSame(subtracted, notFormula.getSubtractedFormula());
			assertSame(superset, notFormula.getSupersetFormula());
		}

		@Test
		@DisplayName("DeMorgan-transformed NOTs should have correct subtracted/superset references")
		void deMorganTransformedNots_shouldHaveCorrectGetters() {
			final ConstantFormula a = constant(1, 2);
			final ConstantFormula b = constant(3, 4);
			final ConstantFormula s = constant(1, 2, 3, 4, 5);

			final Formula input = new NotFormula(new OrFormula(a, b), s);
			final Formula result = optimize(input);

			assertInstanceOf(AndFormula.class, result);
			for (Formula child : result.getInnerFormulas()) {
				final NotFormula not = (NotFormula) child;
				assertSame(s, not.getSupersetFormula());
				assertTrue(
					not.getSubtractedFormula() == a || not.getSubtractedFormula() == b,
					"Subtracted formula should be A or B"
				);
			}
		}
	}
}
