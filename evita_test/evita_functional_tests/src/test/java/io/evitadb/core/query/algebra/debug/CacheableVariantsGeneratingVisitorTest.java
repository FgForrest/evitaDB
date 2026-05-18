/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.debug;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesAndFilteredOutRecords;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesForHistogram;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link CacheableVariantsGeneratingVisitor} contract. The visitor walks the filter formula
 * tree in debug mode and produces one variant per cacheable sub-formula by replacing it with its flattened
 * payload — `VERIFY_POSSIBLE_CACHING_TREES` runs each variant through the query plan and asserts identical
 * results.
 *
 * The filter planner constructs every outer {@link LowestPriceTerminationFormula} with its histogram
 * side-output flag set at construction time when `priceHistogram` is requested. The visitor therefore
 * relies on the LP instance's own flag to pick the correct flattened payload class (the histogram
 * sibling for flagged LPs, the regular flavour otherwise) and needs no histogram-specific branching of
 * its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CacheableVariantsGeneratingVisitor")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
class CacheableVariantsGeneratingVisitorTest {

	private static void verifyGeneratedVariants(List<Formula> formulaVariants, int expectedCount) {
		assertEquals(expectedCount, formulaVariants.size());
		final Set<CachePayloadHeader> replacements = new HashSet<>();
		for (Formula formulaVariant : formulaVariants) {
			final Collection<CachePayloadHeader> cacheableFormulas = FormulaFinder.find(formulaVariant, CachePayloadHeader.class, LookUp.DEEP);
			assertEquals(1, cacheableFormulas.size(), "Each formula must have exactly one cached form.");
			replacements.add(cacheableFormulas.iterator().next());
		}
		assertEquals(expectedCount, replacements.size(), "There must be exactly five unique replacements!");
	}

	@Nonnull
	private static ConstantFormula toConstantFormula(int... recordIds) {
		return new ConstantFormula(new ArrayBitmap(recordIds));
	}

	@Test
	@DisplayName("should generate no variants for non-cacheable formulas")
	void shouldGenerateNoResultsForNonCacheableFormulas() {
		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();
		EmptyFormula.INSTANCE.accept(visitor);
		assertTrue(visitor.getFormulaVariants().isEmpty());
	}

	@Test
	@DisplayName("should generate variants for complex cacheable formula tree")
	void shouldGenerateVariantsForComplexFormula() {
		final OrFormula complexFormula =
			/* #1 */
			new OrFormula(
				toConstantFormula(1, 2),
				toConstantFormula(2, 3),
				/* #2 */
				new AndFormula(
					toConstantFormula(1, 2),
					/* #3 */
					new NotFormula(
						toConstantFormula(1, 2),
						/* #4 */
						new AndFormula(
							/* #5 */
							new AndFormula(
								toConstantFormula(1, 2),
								toConstantFormula(1, 2),
								toConstantFormula(1, 2)
							),
							/* #6 */
							new OrFormula(
								toConstantFormula(1, 2),
								toConstantFormula(1, 2)
							)
						)
					),
					toConstantFormula(2, 3)
				),
				toConstantFormula(8, 6)
			);

		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();
		complexFormula.accept(visitor);
		final List<Formula> formulaVariants = visitor.getFormulaVariants();
		verifyGeneratedVariants(formulaVariants, 6);
	}

	@Test
	@DisplayName("should not generate variants for formulas containing user filter")
	void shouldNotGenerateFormulasContainingUserFilter() {
		// high complexity, but contains user filter
		final Formula complexFormula =
			new OrFormula(
				toConstantFormula(1, 2),
				toConstantFormula(2, 3),
				new UserFilterFormula(
					toConstantFormula(1, 2),
					new NotFormula(
						new FacetGroupOrFormula(Entities.PARAMETER, 10, new ArrayBitmap(1), new ArrayBitmap(12)),
						new AndFormula(
							new AndFormula(
								new FacetGroupOrFormula(Entities.PARAMETER, 2, new ArrayBitmap(1), new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.BRAND, 1, new ArrayBitmap(1), new ArrayBitmap(1)),
								new FacetGroupOrFormula(Entities.STORE, 1, new ArrayBitmap(1), new ArrayBitmap(2))
							),
							new OrFormula(
								new FacetGroupOrFormula(Entities.BRAND, 1, new ArrayBitmap(2), new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.STORE, 1, new ArrayBitmap(3), new ArrayBitmap(9))
							)
						)
					),
					toConstantFormula(2, 3)
				),
				new PlainPriceTerminationFormula(
					new PriceHandlingContainerFormula(
						PriceInnerRecordHandling.NONE,
						new AndFormula(
							toConstantFormula(1, 2),
							toConstantFormula(1, 2),
							toConstantFormula(1, 2)
						)
					),
					new PriceEvaluationContext(
						null, new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
					)
				),
				/* #1 */
				new AndFormula(
					/* #2 */
					new AndFormula(
						toConstantFormula(1, 2),
						toConstantFormula(1, 2),
						toConstantFormula(1, 2)
					),
					/* #3 */
					new OrFormula(
						toConstantFormula(1, 2),
						toConstantFormula(1, 2)
					)
				)
			);

		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();
		complexFormula.accept(visitor);
		final List<Formula> formulaVariants = visitor.getFormulaVariants();
		verifyGeneratedVariants(formulaVariants, 3);
	}

	/**
	 * When the filter planner constructs an LP with its histogram side-output flag enabled, the variants
	 * visitor naturally produces the histogram-sibling flattened payload. The flag lives on the LP instance,
	 * so the visitor needs no special-casing to pick the right payload class.
	 */
	@Test
	@DisplayName("should flatten histogram-flagged LP into the histogram-sibling payload")
	@Tag(HISTOGRAM)
	@Tag(PRICE)
	void shouldFlattenHistogramFlaggedLpIntoHistogramSiblingPayload() {
		final LowestPriceTerminationFormula histogramLp = newLp(true);
		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();

		histogramLp.accept(visitor);

		final List<Formula> variants = visitor.getFormulaVariants();
		assertEquals(1, variants.size());
		assertInstanceOf(FlattenedFormulaWithFilteredPricesForHistogram.class, variants.get(0));
	}

	/**
	 * Regression sibling — when the LP is constructed without the histogram flag (the non-histogram path),
	 * the variants visitor produces the original (non-histogram) flattened payload class. Together with the
	 * test above this proves the visitor faithfully reflects the LP's construction-time flag and never
	 * silently upgrades a plain LP to the heavier histogram payload.
	 */
	@Test
	@DisplayName("should flatten plain LP into the non-histogram payload")
	@Tag(HISTOGRAM)
	@Tag(PRICE)
	void shouldFlattenPlainLpIntoNonHistogramPayload() {
		final LowestPriceTerminationFormula plainLp = newLp(false);
		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();

		plainLp.accept(visitor);

		final List<Formula> variants = visitor.getFormulaVariants();
		assertEquals(1, variants.size());
		assertInstanceOf(FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.class, variants.get(0));
	}

	/**
	 * Creates a {@link LowestPriceTerminationFormula} with an {@link EmptyFormula} delegate so `compute()`
	 * short-circuits on the empty branch — the visitor's `toSerializableFormula` invocation still needs
	 * `compute()` and `getRecordsFilteredOutByPredicate()` to return non-null values.
	 *
	 * @param collectPerInnerRecordPrices whether this LP should expose the histogram side-output
	 * @return a freshly constructed LP wrapping an empty delegate
	 */
	@Nonnull
	private static LowestPriceTerminationFormula newLp(boolean collectPerInnerRecordPrices) {
		return new LowestPriceTerminationFormula(
			EmptyFormula.INSTANCE,
			new PriceEvaluationContext(
				null, new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER,
			collectPerInnerRecordPrices
		);
	}
}
