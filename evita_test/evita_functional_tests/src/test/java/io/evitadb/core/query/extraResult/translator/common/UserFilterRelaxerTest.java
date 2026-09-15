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

package io.evitadb.core.query.extraResult.translator.common;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.attribute.BetweenAttributeFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.PriceBetweenFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.FILTER;

/**
 * Unit tests for {@link UserFilterRelaxer} — the shared helper that rebuilds a filter formula tree with one of
 * three disjoint range-carrier groups stripped from every {@link UserFilterFormula} inside the tree. The three
 * groups are:
 *
 * - `ATTRIBUTE_HISTOGRAM` — {@link BetweenAttributeFormula} and {@link io.evitadb.core.query.algebra.filter.HistogramHavingFormula}
 * - `FACET_IMPACT` — {@link FacetHavingFormula}
 * - `PRICE_HISTOGRAM` — {@link PriceBetweenFormula}
 *
 * The regression tests pin down the three "no self-contraction" invariants:
 *
 * - attribute-histogram self-computation must strip attribute-range carriers and keep facet + price carriers applied.
 * - facet-impact self-computation must strip facet carriers and keep attribute-range + price carriers applied.
 * - price-histogram self-computation must strip price-range carriers and keep attribute-range + facet carriers applied.
 *
 * The tests exercise the full disjointness matrix with a single composite userFilter containing one carrier per
 * group, one {@link SelectionFormula}-wrapped carrier to lock down the "unwrap before probing" contract, and one
 * plain (non-carrier) sibling to lock down the "never touch non-carriers" contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UserFilterRelaxer")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FILTER)
class UserFilterRelaxerTest {

	@Nested
	@DisplayName("ATTRIBUTE_HISTOGRAM relaxation")
	class AttributeHistogramGroupTest {

		@Test
		@DisplayName("should strip attribute-range carriers and keep facet+price carriers when relaxing ATTRIBUTE_HISTOGRAM")
		void shouldStripAttributeRangeCarriersAndKeepOthersWhenRelaxingAttributeHistogramGroup() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final PriceBetweenFormula priceBetween = newPriceCarrier(30);
			final Formula userFilter = new UserFilterFormula(
				attributeBetween, facetHaving, priceBetween
			);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			assertEquals(
				2, relaxed.getInnerFormulas().length,
				"only facet and price carriers should remain"
			);
			assertSame(facetHaving, relaxed.getInnerFormulas()[0]);
			assertSame(priceBetween, relaxed.getInnerFormulas()[1]);
		}

		@Test
		@DisplayName("should drop the entire UserFilterFormula and return EmptyFormula when no children survive relaxation")
		void shouldDropUserFilterAndReturnEmptyFormulaWhenNoChildrenSurviveAttributeHistogramRelaxation() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final Formula userFilter = new UserFilterFormula(attributeBetween);

			final Optional<Formula> result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM);

			// when the rebuilt userFilter has no surviving children, the relaxer drops it entirely and
			// returns the canonical empty sentinel so downstream AND-chains short-circuit correctly
			assertTrue(
				result.isEmpty(),
				"a userFilter holding nothing but this group's carriers must relax to the ALL-PEELED signal, " +
					"which is an empty Optional - not to a present EmptyFormula, which means the opposite " +
					"(the filter matches nothing)"
			);
		}

		@Test
		@DisplayName("should strip a SelectionFormula-wrapped attribute-range carrier when relaxing ATTRIBUTE_HISTOGRAM")
		void shouldStripSelectionFormulaWrappedAttributeRangeCarrierWhenRelaxingAttributeHistogramGroup() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final SelectionFormula wrappedCarrier = new SelectionFormula(attributeBetween, new NoOpEntityToBitmapFilter());
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final Formula userFilter = new UserFilterFormula(wrappedCarrier, facetHaving);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			// the attribute-range carrier wrapped in SelectionFormula must still be stripped — the relaxer
			// peels the wrapper before probing for the group marker, otherwise prefetch-flavoured range
			// carriers would silently escape relaxation
			assertEquals(
				1, relaxed.getInnerFormulas().length,
				"SelectionFormula-wrapped attribute-range carrier should be stripped"
			);
			assertSame(facetHaving, relaxed.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should retain a plain (non-carrier) AttributeFormula child when relaxing ATTRIBUTE_HISTOGRAM")
		void shouldRetainPlainAttributeFormulaChildWhenRelaxingAttributeHistogramGroup() {
			final AttributeFormula plainAttribute = newPlainAttributeFormula("inStock", 50);
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final Formula userFilter = new UserFilterFormula(plainAttribute, attributeBetween);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			// plain AttributeFormula (untagged — e.g. from attributeEquals / attributeInSet) must stay:
			// it is NOT a range carrier, so it is not part of any of the three groups and is always applied
			assertEquals(1, relaxed.getInnerFormulas().length, "plain AttributeFormula must be retained");
			assertSame(plainAttribute, relaxed.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should preserve the surrounding AND wrapper while rebuilding the inner UserFilterFormula")
		void shouldPreserveSurroundingAndWrapperWhenRebuildingInnerUserFilter() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final Formula mandatory = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5));
			final Formula tree = new AndFormula(
				mandatory,
				new UserFilterFormula(attributeBetween, facetHaving)
			);

			final Formula result = UserFilterRelaxer.relax(tree, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			// the surrounding AND survives (mandatory constraints outside userFilter are always applied);
			// only the userFilter interior is rebuilt with attribute-range carriers stripped
			assertInstanceOf(AndFormula.class, result);
			assertEquals(2, result.getInnerFormulas().length);
			assertSame(mandatory, result.getInnerFormulas()[0]);
			final UserFilterFormula rebuiltUserFilter = assertInstanceOf(
				UserFilterFormula.class, result.getInnerFormulas()[1]
			);
			assertEquals(1, rebuiltUserFilter.getInnerFormulas().length);
			assertSame(facetHaving, rebuiltUserFilter.getInnerFormulas()[0]);
		}
	}

	@Nested
	@DisplayName("FACET_IMPACT relaxation")
	class FacetImpactGroupTest {

		@Test
		@DisplayName("should strip FacetHavingFormula and keep attribute-range+price carriers when relaxing FACET_IMPACT")
		void shouldStripFacetHavingFormulaAndKeepOthersWhenRelaxingFacetImpactGroup() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final PriceBetweenFormula priceBetween = newPriceCarrier(30);
			final Formula userFilter = new UserFilterFormula(
				attributeBetween, facetHaving, priceBetween
			);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.FACET_IMPACT).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			assertEquals(
				2, relaxed.getInnerFormulas().length,
				"only attribute-range and price carriers should remain"
			);
			assertSame(attributeBetween, relaxed.getInnerFormulas()[0]);
			assertSame(priceBetween, relaxed.getInnerFormulas()[1]);
		}
	}

	@Nested
	@DisplayName("PRICE_HISTOGRAM relaxation")
	class PriceHistogramGroupTest {

		@Test
		@DisplayName("should strip PriceBetweenFormula and keep attribute-range+facet carriers when relaxing PRICE_HISTOGRAM")
		void shouldStripPriceBetweenFormulaAndKeepOthersWhenRelaxingPriceHistogramGroup() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final PriceBetweenFormula priceBetween = newPriceCarrier(30);
			final Formula userFilter = new UserFilterFormula(
				attributeBetween, facetHaving, priceBetween
			);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.PRICE_HISTOGRAM).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			assertEquals(
				2, relaxed.getInnerFormulas().length,
				"only attribute-range and facet carriers should remain"
			);
			assertSame(attributeBetween, relaxed.getInnerFormulas()[0]);
			assertSame(facetHaving, relaxed.getInnerFormulas()[1]);
		}
	}

	@Nested
	@DisplayName("Generic structural relaxation (cross-group invariants)")
	class GenericStructuralTest {

		@Test
		@DisplayName("should keep every leaf instance when no UserFilterFormula exists anywhere in the tree")
		void shouldKeepEveryLeafInstanceWhenTreeHasNoUserFilterFormula() {
			// when a subtree contains no UserFilterFormula, the relaxer has nothing to rebuild — it must
			// walk down without synthesising any new nodes; we assert leaf identity so a drift toward
			// "clone everything anyway" is visible as a spurious allocation and a broken assertion
			final ConstantFormula leaf1 = new ConstantFormula(new ArrayBitmap(1, 2));
			final ConstantFormula leaf2 = new ConstantFormula(new ArrayBitmap(3, 4));
			final ConstantFormula leaf3 = new ConstantFormula(new ArrayBitmap(5, 6));
			final Formula tree = new OrFormula(leaf1, new AndFormula(leaf2, leaf3));

			final Formula result = UserFilterRelaxer.relax(tree, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			// the cloner may return the same root reference or a structurally identical tree — what
			// matters is that every leaf survives with its exact instance; allocating new leaves would
			// blow the formula-cache hash equivalence
			assertInstanceOf(OrFormula.class, result);
			final Formula[] topChildren = result.getInnerFormulas();
			assertEquals(2, topChildren.length);
			assertSame(leaf1, topChildren[0]);
			assertInstanceOf(AndFormula.class, topChildren[1]);
			final Formula[] andChildren = topChildren[1].getInnerFormulas();
			assertEquals(2, andChildren.length);
			assertSame(leaf2, andChildren[0]);
			assertSame(leaf3, andChildren[1]);
		}

		@Test
		@DisplayName("should relax a deeply nested UserFilterFormula while keeping every ancestor OR/AND node intact")
		void shouldRelaxDeeplyNestedUserFilterWhileKeepingAncestorsIntact() {
			// the UserFilterFormula can live arbitrarily deep inside the planner's tree — the relaxer
			// must descend through OR/AND wrappers, rebuild the grandchild userFilter, and leave every
			// ancestor node intact
			final ConstantFormula outerLeaf = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula innerLeaf = new ConstantFormula(new ArrayBitmap(2));
			final BetweenAttributeFormula attributeCarrier = newAttributeRangeCarrier("price", 10);
			final ConstantFormula sibling = new ConstantFormula(new ArrayBitmap(99));
			final UserFilterFormula deepUserFilter = new UserFilterFormula(attributeCarrier, sibling);
			final Formula tree = new OrFormula(
				outerLeaf,
				new AndFormula(innerLeaf, deepUserFilter)
			);

			final Formula result = UserFilterRelaxer.relax(tree, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			// outer OR and middle AND must still wrap the result — the rebuild only touches the userFilter
			assertInstanceOf(OrFormula.class, result);
			final Formula[] topChildren = result.getInnerFormulas();
			assertEquals(2, topChildren.length);
			assertSame(outerLeaf, topChildren[0]);
			assertInstanceOf(AndFormula.class, topChildren[1]);
			final Formula[] andChildren = topChildren[1].getInnerFormulas();
			assertSame(innerLeaf, andChildren[0]);
			// the grandchild userFilter has the attribute-range carrier stripped while sibling survives
			final UserFilterFormula rebuiltUserFilter = assertInstanceOf(UserFilterFormula.class, andChildren[1]);
			assertEquals(1, rebuiltUserFilter.getInnerFormulas().length);
			assertSame(sibling, rebuiltUserFilter.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should retain a SelectionFormula wrapping a non-carrier when relaxing ATTRIBUTE_HISTOGRAM")
		void shouldRetainSelectionFormulaWrappingNonCarrierWhenRelaxingAttributeHistogramGroup() {
			// the relaxer unwraps SelectionFormula *only to probe its delegate for the group marker*;
			// when the delegate is NOT a carrier, the whole wrapped child must survive verbatim — any
			// over-eager unwrapping would strip prefetch metadata and break prefetch downstream
			final AttributeFormula plainAttribute = newPlainAttributeFormula("inStock", 50);
			final SelectionFormula wrappedPlain = new SelectionFormula(plainAttribute, new NoOpEntityToBitmapFilter());
			final BetweenAttributeFormula attributeCarrier = newAttributeRangeCarrier("price", 10);
			final Formula userFilter = new UserFilterFormula(wrappedPlain, attributeCarrier);

			final Formula result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			final UserFilterFormula relaxed = assertRelaxedUserFilter(result);
			// only the attribute-range carrier is stripped; the SelectionFormula wrapper remains with
			// its plain delegate
			assertEquals(
				1, relaxed.getInnerFormulas().length,
				"SelectionFormula(plain) must survive — not an attribute-range carrier"
			);
			assertSame(wrappedPlain, relaxed.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should drop UserFilterFormula and return EmptyFormula when no children survive FACET_IMPACT relaxation")
		void shouldDropUserFilterAndReturnEmptyFormulaWhenNoChildrenSurviveFacetImpactRelaxation() {
			// when the rebuild leaves no surviving children, the relaxer must drop the userFilter
			// container and fall through to the canonical empty sentinel (same contract as the
			// attribute-histogram drop case)
			final FacetHavingFormula facetHaving = newFacetCarrier(20);
			final Formula userFilter = new UserFilterFormula(facetHaving);

			final Optional<Formula> result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.FACET_IMPACT);

			assertTrue(
				result.isEmpty(),
				"a userFilter holding nothing but this group's carriers must relax to the ALL-PEELED signal, " +
					"which is an empty Optional - not to a present EmptyFormula, which means the opposite " +
					"(the filter matches nothing)"
			);
		}

		@Test
		@DisplayName("should drop UserFilterFormula and return EmptyFormula when no children survive PRICE_HISTOGRAM relaxation")
		void shouldDropUserFilterAndReturnEmptyFormulaWhenNoChildrenSurvivePriceHistogramRelaxation() {
			// same invariant applied to price-histogram self-relaxation — rebuild with no survivors
			// must collapse to the empty sentinel
			final PriceBetweenFormula priceBetween = newPriceCarrier(30);
			final Formula userFilter = new UserFilterFormula(priceBetween);

			final Optional<Formula> result = UserFilterRelaxer.relax(userFilter, RangeCarrierGroup.PRICE_HISTOGRAM);

			assertTrue(
				result.isEmpty(),
				"a userFilter holding nothing but this group's carriers must relax to the ALL-PEELED signal, " +
					"which is an empty Optional - not to a present EmptyFormula, which means the opposite " +
					"(the filter matches nothing)"
			);
		}

		@Test
		@DisplayName("should rebuild two sibling UserFilterFormulas independently (no cross-contamination)")
		void shouldRebuildTwoSiblingUserFilterFormulasIndependentlyWithoutCrossContamination() {
			// the planner can produce more than one userFilter per query tree (e.g. when a translator
			// synthesises a second one for an extra-result probe); the relaxer must rebuild each
			// userFilter in isolation — neither one may see or leak carriers from the other
			final BetweenAttributeFormula attributeCarrier = newAttributeRangeCarrier("price", 10);
			final FacetHavingFormula facetCarrier = newFacetCarrier(20);
			final PriceBetweenFormula priceCarrier = newPriceCarrier(30);
			final AttributeFormula plain = newPlainAttributeFormula("inStock", 50);
			final UserFilterFormula firstUserFilter = new UserFilterFormula(attributeCarrier, facetCarrier);
			final UserFilterFormula secondUserFilter = new UserFilterFormula(priceCarrier, plain);
			final Formula tree = new AndFormula(firstUserFilter, secondUserFilter);

			final Formula result = UserFilterRelaxer.relax(tree, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM).orElseThrow();

			// surrounding AND survives; both userFilters are rebuilt independently
			assertInstanceOf(AndFormula.class, result);
			final Formula[] andChildren = result.getInnerFormulas();
			assertEquals(2, andChildren.length);

			// first userFilter — attribute-range stripped, facet carrier retained
			final UserFilterFormula firstRebuilt = assertInstanceOf(UserFilterFormula.class, andChildren[0]);
			assertEquals(1, firstRebuilt.getInnerFormulas().length);
			assertSame(facetCarrier, firstRebuilt.getInnerFormulas()[0]);

			// second userFilter — no attribute-range carrier inside, so both children survive untouched
			final UserFilterFormula secondRebuilt = assertInstanceOf(UserFilterFormula.class, andChildren[1]);
			assertEquals(2, secondRebuilt.getInnerFormulas().length);
			assertSame(priceCarrier, secondRebuilt.getInnerFormulas()[0]);
			assertSame(plain, secondRebuilt.getInnerFormulas()[1]);
		}
	}


	/**
	 * The shapes that reach the relaxer only because the planner folds provably-empty `attributeIs(NULL)`
	 * subtractions during planning rather than resolving them during execution. Before that fold an `EmptyFormula`
	 * could not arrive in the relaxer's *input* at all, and every one it saw was its own work - which is why the
	 * "all peeled" signal used to be allowed to share the `EmptyFormula.INSTANCE` value.
	 *
	 * Each row here asserts the relaxer reports a PRESENT value: the filter matches nothing, which is the exact
	 * opposite of "no mandatory filter remains, every record passes".
	 */
	@Nested
	@DisplayName("EmptyFormula arriving in the input (planning-time fold)")
	class GenuinelyEmptyInputTest {

		@Test
		@DisplayName("should pass a bare EmptyFormula input through as a present value")
		void shouldPassBareEmptyFormulaInputThroughAsPresentValue() {
			final Optional<Formula> result = UserFilterRelaxer.relax(
				EmptyFormula.INSTANCE, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(
				result.isPresent(),
				"a filter that was already unsatisfiable must not be reported as ALL-PEELED - that reads as " +
					"\"every record passes\" and widens the histogram baseline to the whole catalog"
			);
			assertSame(EmptyFormula.INSTANCE, result.get());
		}

		@Test
		@DisplayName("should keep a UserFilterFormula that already holds an EmptyFormula")
		void shouldKeepUserFilterThatAlreadyHoldsEmptyFormula() {
			final Formula userFilter = new UserFilterFormula(EmptyFormula.INSTANCE);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(
				result.isPresent(),
				"the userFilter was unsatisfiable on arrival, not emptied by peeling, so it must survive " +
					"relaxation rather than be dropped as though nothing constrained the result"
			);
			assertInstanceOf(UserFilterFormula.class, result.get());
		}


		@Test
		@DisplayName("should still peel the carrier when the EmptyFormula sits in a LIVE disjunction")
		void shouldStillPeelTheCarrierWhenTheEmptyFormulaSitsInALiveDisjunction() {
			// `FormulaOptimizer:255-264` hands back an `OrFormula` UNTOUCHED, dead child included, as soon as two
			// of its alternatives are non-empty - so this shape reaches the relaxer verbatim from a real query.
			// In a disjunction `EmptyFormula` is the identity element: the userFilter is perfectly satisfiable
			// through `liveAlternative`, and relaxation must proceed normally.
			final BetweenAttributeFormula carrier = newAttributeRangeCarrier("price", 10);
			final AttributeFormula liveAlternative = newPlainAttributeFormula("code", 20);
			final Formula userFilter = new UserFilterFormula(
				new OrFormula(carrier, liveAlternative, EmptyFormula.INSTANCE)
			);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(
				result.isPresent(),
				"the userFilter is satisfiable through its live alternative and must not be dropped - dropping it " +
					"reads upstream as \"nothing constrains the result\" and widens the baseline to the catalog"
			);
			final UserFilterFormula relaxed = assertInstanceOf(UserFilterFormula.class, result.get());
			assertTrue(
				FormulaFinder.find(relaxed, BetweenAttributeFormula.class, LookUp.DEEP).isEmpty(),
				"the range carrier must still have been PEELED. Treating the disjunction's dead branch as " +
					"unsatisfiability skips relaxation, and the user's own slider then contracts the very " +
					"histogram it is supposed to span - the self-contraction this class exists to prevent."
			);
			assertFalse(
				FormulaFinder.find(relaxed, AttributeFormula.class, LookUp.DEEP).isEmpty(),
				"the live alternative must survive relaxation - it is a non-carrier and still constrains the result"
			);
		}


		@Test
		@DisplayName("should see through nested AND-in-OR when every alternative is conjunctively empty")
		void shouldSeeThroughNestedAndInOrWhenEveryAlternativeIsConjunctivelyEmpty() {
			// neither alternative holds a bare EmptyFormula as a DIRECT child - each is empty only because its own
			// conjunction is. The recursion has to reach through both levels; stopping at the first container it
			// does not recognise would answer "not provably empty", relaxation would proceed, and the ALL-PEELED
			// signal would then widen the baseline to the whole catalog for a filter matching nothing.
			final Formula userFilter = new UserFilterFormula(
				new OrFormula(
					new AndFormula(newAttributeRangeCarrier("price", 10), EmptyFormula.INSTANCE),
					new AndFormula(newAttributeRangeCarrier("price", 11), EmptyFormula.INSTANCE)
				)
			);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(result.isPresent(), "every alternative is conjunctively empty, so the userFilter matches nothing");
			final UserFilterFormula relaxed = assertInstanceOf(UserFilterFormula.class, result.get());
			assertFalse(
				FormulaFinder.find(relaxed, BetweenAttributeFormula.class, LookUp.DEEP).isEmpty(),
				"peeling must be skipped entirely once the userFilter is known to match nothing"
			);
		}

		@Test
		@DisplayName("should not let a NotFormula subtracting EmptyFormula poison its enclosing conjunction")
		void shouldNotLetANotFormulaSubtractingEmptyFormulaPoisonItsEnclosingConjunction() {
			// `NOT(empty)` is the SUPERSET - the opposite of empty - so the enclosing AND is perfectly satisfiable
			// even though an `EmptyFormula` is reachable inside it. Treating `NotFormula` as conjunctive, or
			// walking through it structurally, would declare this userFilter unsatisfiable and silently drop a
			// live constraint. This is the arm of `isProvablyEmpty` where a mistake is least visible.
			final BetweenAttributeFormula carrier = newAttributeRangeCarrier("price", 10);
			final Formula liveSuperset = new ConstantFormula(new BaseBitmap(1, 2, 3));
			final Formula userFilter = new UserFilterFormula(
				new AndFormula(new NotFormula(EmptyFormula.INSTANCE, liveSuperset), carrier)
			);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(result.isPresent(), "the userFilter is satisfiable and must not be dropped");
			final UserFilterFormula relaxed = assertInstanceOf(UserFilterFormula.class, result.get());
			assertTrue(
				FormulaFinder.find(relaxed, BetweenAttributeFormula.class, LookUp.DEEP).isEmpty(),
				"the carrier must have been PEELED - `NOT(empty)` is the superset, so nothing here is unsatisfiable"
			);
		}

		@Test
		@DisplayName("should treat a disjunction as empty only when EVERY alternative is empty")
		void shouldTreatADisjunctionAsEmptyOnlyWhenEveryAlternativeIsEmpty() {
			final Formula userFilter = new UserFilterFormula(
				new OrFormula(EmptyFormula.INSTANCE, EmptyFormula.INSTANCE)
			);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			assertTrue(
				result.isPresent(),
				"every alternative is empty, so the userFilter genuinely matches nothing and must be kept rather " +
					"than reported as the ALL-PEELED signal"
			);
			assertInstanceOf(UserFilterFormula.class, result.get());
		}

		@Test
		@DisplayName("should keep a UserFilterFormula holding an EmptyFormula beside a peelable carrier")
		void shouldKeepUserFilterHoldingEmptyFormulaBesideAPeelableCarrier() {
			final BetweenAttributeFormula attributeBetween = newAttributeRangeCarrier("price", 10);
			final Formula userFilter = new UserFilterFormula(attributeBetween, EmptyFormula.INSTANCE);

			final Optional<Formula> result = UserFilterRelaxer.relax(
				userFilter, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
			);

			// the mixed case: releasing the user's own slider cannot rescue a userFilter that is unsatisfiable
			// for an unrelated reason, so peeling is pointless here and the subtree is kept exactly as it arrived
			assertTrue(result.isPresent(), "an unsatisfiable userFilter must not relax to the ALL-PEELED signal");
			final UserFilterFormula relaxed = assertInstanceOf(UserFilterFormula.class, result.get());
			assertEquals(
				2, relaxed.getInnerFormulas().length,
				"peeling must be skipped entirely once the userFilter is known to match nothing"
			);
		}
	}

	/**
	 * Casts the relaxer's output to a {@link UserFilterFormula}, asserting that it is present and non-null. Used
	 * by every test that expects a surviving (non-empty) user filter after relaxation.
	 */
	@Nonnull
	private static UserFilterFormula assertRelaxedUserFilter(@Nullable Formula result) {
		assertNotNull(result, "relaxer returned null for a userFilter with surviving children");
		return assertInstanceOf(UserFilterFormula.class, result);
	}

	/**
	 * Builds a {@link BetweenAttributeFormula} around a {@link ConstantFormula} with a single entity PK. The
	 * formula is tagged with {@link io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula} — the
	 * marker the attribute-histogram relaxer strips.
	 */
	@Nonnull
	private static BetweenAttributeFormula newAttributeRangeCarrier(@Nonnull String attributeName, int pk) {
		return new BetweenAttributeFormula(
			false,
			new AttributeKey(attributeName),
			new ConstantFormula(new ArrayBitmap(pk))
		);
	}

	/**
	 * Builds a plain {@link AttributeFormula} (from e.g. `attributeEquals`) around a {@link ConstantFormula} with a
	 * single entity PK. This formula is **not** a range carrier and must never be stripped by the relaxer.
	 */
	@Nonnull
	private static AttributeFormula newPlainAttributeFormula(@Nonnull String attributeName, int pk) {
		return new AttributeFormula(
			false,
			new AttributeKey(attributeName),
			new ConstantFormula(new ArrayBitmap(pk))
		);
	}

	/**
	 * Builds a {@link FacetHavingFormula} wrapper around a {@link ConstantFormula} with a single entity PK. The
	 * wrapper is the FACET_IMPACT carrier type the relaxer peels.
	 */
	@Nonnull
	private static FacetHavingFormula newFacetCarrier(int pk) {
		return new FacetHavingFormula("brand", new ConstantFormula(new ArrayBitmap(pk)));
	}

	/**
	 * Builds a {@link PriceBetweenFormula} wrapper around a {@link ConstantFormula} with a single entity PK. The
	 * wrapper is the PRICE_HISTOGRAM carrier type the relaxer peels.
	 */
	@Nonnull
	private static PriceBetweenFormula newPriceCarrier(int pk) {
		return new PriceBetweenFormula(new ConstantFormula(new ArrayBitmap(pk)));
	}

	/**
	 * Minimal {@link EntityToBitmapFilter} used to construct a {@link SelectionFormula} without pulling in the full
	 * prefetch machinery. The relaxer never calls `filter` or `getEntityRequire` — it only unwraps the delegate via
	 * {@link SelectionFormula#getDelegate()} — so returning trivial values is safe.
	 */
	private static class NoOpEntityToBitmapFilter implements EntityToBitmapFilter {

		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			return new ArrayBitmap();
		}

		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}
	}
}
