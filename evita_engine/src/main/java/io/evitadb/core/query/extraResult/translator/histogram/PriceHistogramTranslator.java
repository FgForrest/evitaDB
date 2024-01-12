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

package io.evitadb.core.query.extraResult.translator.histogram;

import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.core.query.sort.price.FilteredPricesSorter;
import io.evitadb.utils.Assert;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link PriceHistogram} to
 * {@link io.evitadb.api.requestResponse.extraResult.PriceHistogram}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(List)}
 * method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceHistogramTranslator implements RequireConstraintTranslator<PriceHistogram> {

	@Override
	public ExtraResultProducer apply(PriceHistogram priceHistogram, ExtraResultPlanningVisitor extraResultPlanner) {
		final EntitySchemaContract schema = extraResultPlanner.getSchema();
		Assert.isTrue(
			schema.isWithPrice(),
			() -> new EntityHasNoPricesException(schema.getName())
		);
		// collect all FilteredPriceRecordAccessor formulas in filtering formula tree
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
			extraResultPlanner.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);
		// find FilteredPricesSorter among the sorters (if any)
		final Optional<FilteredPricesSorter> filteredPricesSorter = ofNullable(
			extraResultPlanner.findSorter(FilteredPricesSorter.class)
		);

		// create price histogram producer that computes the result
		return new PriceHistogramProducer(
			priceHistogram.getRequestedBucketCount(),
			priceHistogram.getBehavior(),
			extraResultPlanner.getQueryContext(),
			extraResultPlanner.getFilteringFormula(),
			filteredPriceRecordAccessors,
			filteredPricesSorter
				.map(FilteredPricesSorter::getPriceRecordsLookupResult)
				.orElse(null),
			extraResultPlanner.getExtraResultCacheAccessor()
		);
	}

}
