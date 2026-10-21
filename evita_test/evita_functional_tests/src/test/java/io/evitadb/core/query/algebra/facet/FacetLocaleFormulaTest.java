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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link FacetLocaleFormula} - the owner guard of one localized facet selection - and for the projection of
 * its guards that facet summary counts keep when they discard the rest of the user filter. Every projection is
 * compared by its computed owners against a superset of owners 1-6.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetLocaleFormula owner guard")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FACET)
class FacetLocaleFormulaTest {
	/** The reference whose guards are projected. */
	private static final String REFERENCE_NAME = "brand";
	/** The owners a negation subtracts from when its superset side holds no guard. */
	private static final int[] SUPERSET = {1, 2, 3, 4, 5, 6};

	/**
	 * Creates a formula of fixed owners.
	 *
	 * @param recordIds the owners
	 * @return the formula producing them
	 */
	@Nonnull
	private static Formula constant(int... recordIds) {
		return new ConstantFormula(new ArrayBitmap(recordIds));
	}

	/**
	 * Creates a guarded selection the way the facet translator does - the selection wrapped as a facet selection of
	 * the same reference.
	 *
	 * @param referenceName the reference of the selection
	 * @param owners        the owners holding the locale
	 * @param selected      the owners referencing the selected facets
	 * @return the guarded selection
	 */
	@Nonnull
	private static FacetLocaleFormula guarded(@Nonnull String referenceName, @Nonnull int[] owners, int... selected) {
		return new FacetLocaleFormula(
			referenceName, constant(owners), new FacetHavingFormula(referenceName, constant(selected))
		);
	}

	/**
	 * Projects the guards of {@link #REFERENCE_NAME} and computes the projection.
	 *
	 * @param formula the formula to project
	 * @return the owners of the projection, NULL when it restricts nothing
	 */
	@Nullable
	private static int[] project(@Nonnull Formula formula) {
		final Formula projection = FacetLocaleFormula.retainOwnerConditions(
			formula, REFERENCE_NAME, constant(SUPERSET)
		);
		return projection == null ? null : projection.compute().getArray();
	}

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should keep the selected owners that satisfy the guard")
		void shouldIntersectOwnerConditionWithSelection() {
			final FacetLocaleFormula formula = guarded(REFERENCE_NAME, new int[]{1, 2, 3}, 2, 3, 4);

			final int[] result = formula.compute().getArray();

			assertArrayEquals(new int[]{2, 3}, result);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should become the empty formula when both children are removed")
		void shouldReturnEmptyFormulaWhenClonedWithoutChildren() {
			final FacetLocaleFormula formula = guarded(REFERENCE_NAME, new int[]{1, 2}, 2);

			final Formula clone = formula.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should become the remaining child when the selection carrier is removed")
		void shouldReturnRemainingChildWhenClonedWithSingleChild() {
			final FacetLocaleFormula formula = guarded(REFERENCE_NAME, new int[]{1, 2}, 2);
			final Formula ownerCondition = constant(1, 2);

			final Formula clone = formula.getCloneWithInnerFormulas(ownerCondition);

			assertSame(ownerCondition, clone);
		}

		@Test
		@DisplayName("should keep its reference when cloned with both children")
		void shouldKeepReferenceWhenClonedWithBothChildren() {
			final FacetLocaleFormula formula = guarded(REFERENCE_NAME, new int[]{1, 2}, 2);

			final Formula clone = formula.getCloneWithInnerFormulas(constant(3, 4), constant(4));

			assertInstanceOf(FacetLocaleFormula.class, clone);
			assertArrayEquals(new int[]{3, 4}, project(new UserFilterFormula(clone)),
				"The clone must remain a guard of the same reference");
		}

		@Test
		@DisplayName("should refuse a clone with more than two children")
		void shouldRefuseCloneWithThreeChildren() {
			final FacetLocaleFormula formula = guarded(REFERENCE_NAME, new int[]{1, 2}, 2);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> formula.getCloneWithInnerFormulas(constant(1), constant(2), constant(3))
			);
		}
	}

	@Nested
	@DisplayName("Hash")
	class HashTest {

		@Test
		@DisplayName("should hash guards of different references differently")
		void shouldDifferInHashWhenReferenceDiffers() {
			final FacetLocaleFormula brand = new FacetLocaleFormula(REFERENCE_NAME, constant(1, 2), constant(2));
			final FacetLocaleFormula store = new FacetLocaleFormula("store", constant(1, 2), constant(2));

			assertNotEquals(brand.getHash(), store.getHash());
		}

		@Test
		@DisplayName("should hash identically constructed guards equally")
		void shouldEqualInHashWhenReferenceAndChildrenMatch() {
			final FacetLocaleFormula first = new FacetLocaleFormula(REFERENCE_NAME, constant(1, 2), constant(2));
			final FacetLocaleFormula second = new FacetLocaleFormula(REFERENCE_NAME, constant(1, 2), constant(2));

			assertEquals(first.getHash(), second.getHash());
		}
	}

	@Nested
	@DisplayName("Owner condition projection")
	class ProjectionTest {

		@Test
		@DisplayName("should restrict nothing when the user filter holds no guard")
		void shouldReturnNullWhenNoGuardExists() {
			final Formula formula = new UserFilterFormula(new FacetHavingFormula(REFERENCE_NAME, constant(1, 2)));

			assertNull(project(formula));
		}

		@Test
		@DisplayName("should restrict nothing when the only guard belongs to another reference")
		void shouldReturnNullWhenGuardBelongsToAnotherReference() {
			final Formula formula = new UserFilterFormula(guarded("store", new int[]{1, 2}, 2));

			assertNull(project(formula));
		}

		@Test
		@DisplayName("should restrict nothing when the guard is part of the mandatory filter")
		void shouldReturnNullWhenGuardIsOutsideUserFilter() {
			final Formula formula = new AndFormula(
				new NotFormula(guarded(REFERENCE_NAME, new int[]{1, 2, 3}, 1, 3), constant(SUPERSET)),
				new UserFilterFormula(constant(2, 4))
			);

			assertNull(project(formula));
		}

		@Test
		@DisplayName("should keep the owner condition of a guarded selection")
		void shouldReturnOwnerConditionOfGuard() {
			final Formula formula = new UserFilterFormula(guarded(REFERENCE_NAME, new int[]{1, 2, 3}, 2));

			assertArrayEquals(new int[]{1, 2, 3}, project(formula));
		}

		@Test
		@DisplayName("should drop a conjunct that holds no guard")
		void shouldDropUnrelatedConjunct() {
			final Formula formula = new UserFilterFormula(
				guarded(REFERENCE_NAME, new int[]{1, 2, 3}, 2), constant(5)
			);

			assertArrayEquals(new int[]{1, 2, 3}, project(formula));
		}

		@Test
		@DisplayName("should subtract the guard from the superset when the selection is negated")
		void shouldSubtractGuardFromSupersetWhenNegated() {
			final Formula formula = new UserFilterFormula(
				new NotFormula(guarded(REFERENCE_NAME, new int[]{1, 2, 3}, 2), constant(2, 4))
			);

			assertArrayEquals(new int[]{4, 5, 6}, project(formula));
		}

		@Test
		@DisplayName("should subtract from the guard of the superset side when both sides are guarded")
		void shouldSubtractFromGuardedSupersetWhenBothSidesAreGuarded() {
			final Formula formula = new UserFilterFormula(
				new NotFormula(
					guarded(REFERENCE_NAME, new int[]{1, 2}, 1),
					guarded(REFERENCE_NAME, new int[]{1, 2, 3, 4}, 3)
				)
			);

			assertArrayEquals(new int[]{3, 4}, project(formula));
		}

		@Test
		@DisplayName("should unite the guards of a disjunction")
		void shouldUniteGuardsOfDisjunction() {
			final Formula formula = new UserFilterFormula(
				new OrFormula(
					guarded(REFERENCE_NAME, new int[]{1, 2}, 1),
					guarded(REFERENCE_NAME, new int[]{3}, 3)
				)
			);

			assertArrayEquals(new int[]{1, 2, 3}, project(formula));
		}

		@Test
		@DisplayName("should keep the guard when a disjunct selects no facet of the reference")
		void shouldKeepGuardWhenDisjunctIsUnrelated() {
			final Formula formula = new UserFilterFormula(
				new OrFormula(guarded(REFERENCE_NAME, new int[]{1, 2}, 1), constant(5), EmptyFormula.INSTANCE)
			);

			assertArrayEquals(new int[]{1, 2}, project(formula));
		}

		@Test
		@DisplayName("should keep the guard when a disjunct selects facets of another reference")
		void shouldKeepGuardWhenDisjunctSelectsOtherReference() {
			final Formula formula = new UserFilterFormula(
				new OrFormula(
					guarded(REFERENCE_NAME, new int[]{1, 2}, 1),
					new FacetHavingFormula("store", constant(5))
				)
			);

			assertArrayEquals(new int[]{1, 2}, project(formula));
		}

		@Test
		@DisplayName("should restrict nothing when a disjunct selects facets of the reference without a guard")
		void shouldReturnNullWhenDisjunctSelectsSameReferenceUnguarded() {
			final Formula formula = new UserFilterFormula(
				new OrFormula(
					guarded(REFERENCE_NAME, new int[]{1, 2}, 1),
					new FacetHavingFormula(REFERENCE_NAME, constant(5))
				)
			);

			assertNull(project(formula));
		}

		@Test
		@DisplayName("should confine the guard to its scope and admit the whole of an unguarded scope")
		void shouldConfineGuardToItsScope() {
			final Formula formula = new UserFilterFormula(
				new OrFormula(
					ScopeContainerFormula.restrictingScope(
						Scope.LIVE, constant(1, 2, 3, 4), guarded(REFERENCE_NAME, new int[]{1, 2, 5}, 1)
					),
					ScopeContainerFormula.restrictingScope(Scope.ARCHIVED, constant(7, 8), constant(7))
				)
			);

			assertArrayEquals(new int[]{1, 2, 7, 8}, project(formula));
		}
	}
}
