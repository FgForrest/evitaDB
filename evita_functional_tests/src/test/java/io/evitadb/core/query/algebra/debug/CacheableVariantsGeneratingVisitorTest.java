/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.debug;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test verifies {@link CacheableVariantsGeneratingVisitor} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
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
	void shouldGenerateNoResultsForNonCacheableFormulas() {
		final CacheableVariantsGeneratingVisitor visitor = new CacheableVariantsGeneratingVisitor();
		EmptyFormula.INSTANCE.accept(visitor);
		assertTrue(visitor.getFormulaVariants().isEmpty());
	}

	@Test
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
						new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
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
}