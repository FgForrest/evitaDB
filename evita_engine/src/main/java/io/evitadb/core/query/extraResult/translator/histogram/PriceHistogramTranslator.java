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

package io.evitadb.core.query.extraResult.translator.histogram;

import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.exception.PriceNotIndexedException;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.core.query.sort.price.FilteredPricesSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link PriceHistogram} to
 * {@link io.evitadb.api.requestResponse.extraResult.PriceHistogram}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * By the time this translator runs, every outer {@link LowestPriceTerminationFormula} in the filtering tree
 * has already been constructed with its per-inner-record side-output enabled — the filter planner reads
 * {@code QueryPlanningContext.isPriceHistogramRequested()} at LP-construction time and sets the flag on each
 * LP built outside {@code userFilter} scope. The translator therefore does NOT rewrite the tree; it just
 * hands the filtering formula and the optional sorter result straight to the producer. The producer relaxes
 * the tree (price-between carriers peeled) at fabrication time and harvests the histogram-aware accessors
 * from that single relaxed view — no separate strip pass is needed at planning.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceHistogramTranslator implements RequireConstraintTranslator<PriceHistogram> {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull PriceHistogram priceHistogram, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		final EntitySchemaContract schema = extraResultPlanner.getSchema();
		Assert.isTrue(
			schema.isWithPrice(),
			() -> new EntityHasNoPricesException(schema.getName())
		);

		// get scopes the histogram will be created from
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();
		for (Scope scope : scopes) {
			Assert.isTrue(
				schema.isPriceIndexedInScope(scope),
				() -> new PriceNotIndexedException(schema, scope)
			);
		}
		// a price histogram depends on the flag just as a price filter does - counted here too, so a catalog whose
		// only price usage is the histogram does not report the flag as unused
		extraResultPlanner.getQueryContext().recordRequestedEntityCapability(
			schema, Capability.PRICE_INDEXED, scopes
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
			filteredPricesSorter
				.map(FilteredPricesSorter::getPriceRecordsLookupResult)
				.orElse(null)
		);
	}

}
