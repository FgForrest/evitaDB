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
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.exception.PriceNotIndexedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

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
 * LP built outside {@code userFilter} scope. The producer receives the query's filtering formula, intersected
 * with the processing scopes if they are narrower, and relaxes the price-between carriers at fabrication time.
 * It harvests the histogram-aware accessors from that relaxed tree.
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
			schema, Capability.PRICED, scopes
		);

		// there is a single price histogram slot in the response, so a second `priceHistogram` requirement either
		// asks for exactly what the first one did - and is redundant - or contradicts it. Deciding that here rather
		// than in `EvitaRequest#isPriceHistogramRequested` is what makes the verdict independent of the query plan:
		// this translator runs for every `priceHistogram` requirement, whereas that accessor is reached only from
		// the four price filter translators and therefore never runs for a query that filters on something else.
		final PriceHistogramProducer existingProducer =
			extraResultPlanner.findExistingProducer(PriceHistogramProducer.class);
		if (existingProducer != null) {
			if (!existingProducer.getScopes().equals(scopes)) {
				throw new EvitaInvalidUsageException(
					"The price histogram was requested for different scope sets " +
						existingProducer.getScopes() + " and " + scopes + "."
				);
			}
			final int bucketCount = priceHistogram.getRequestedBucketCount();
			final HistogramBehavior behavior = priceHistogram.getBehavior();
			if (existingProducer.getBucketCount() != bucketCount || existingProducer.getBehavior() != behavior) {
				final String reason = "Price histogram was already requested with a different bucket count or " +
					"behavior - there may be only a single price histogram request in the query";
				throw new EvitaInvalidUsageException(
					reason + ": " + existingProducer.getBucketCount() + "/" + existingProducer.getBehavior() +
						" and " + bucketCount + "/" + behavior + ".",
					reason + "."
				);
			}
			// the two agree, so the first producer already computes exactly what this requirement asks for
			return existingProducer;
		}

		final QueryPlanningContext queryContext = extraResultPlanner.getQueryContext();
		return new PriceHistogramProducer(
			priceHistogram.getRequestedBucketCount(),
			priceHistogram.getBehavior(),
			queryContext,
			restrictToScopes(queryContext, extraResultPlanner.getFilteringFormula(), scopes),
			// an enum set keeps the declaration order, so a refusal message names the scopes alike on every run
			Collections.unmodifiableSet(EnumSet.copyOf(scopes))
		);
	}

	/**
	 * Restricts the query's filtering formula to the entities of the histogram's scopes.
	 *
	 * @param queryContext     the planning context of the query
	 * @param filteringFormula the query's filtering formula
	 * @param scopes           the processing scopes of the histogram
	 * @return the filtering formula itself when the scopes are the query's scopes, otherwise the formula intersected
	 *         with all entities of the scopes
	 */
	@Nonnull
	private static Formula restrictToScopes(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Formula filteringFormula,
		@Nonnull Set<Scope> scopes
	) {
		if (scopes.equals(queryContext.getScopes())) {
			return filteringFormula;
		}
		final Formula scopeEntities = FormulaFactory.or(
			scopes.stream()
				.map(queryContext::getGlobalEntityIndexIfExists)
				.flatMap(Optional::stream)
				.map(EntityIndex::getAllPrimaryKeysFormula)
				.toArray(Formula[]::new)
		);
		// Preserve the memoized root and keep the scope restriction outside the price carriers relaxed later.
		return new AndFormula(filteringFormula, scopeEntities);
	}

}
