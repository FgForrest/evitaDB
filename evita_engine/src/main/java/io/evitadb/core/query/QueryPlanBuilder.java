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

package io.evitadb.core.query;

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.FetchRequirementCollector;
import io.evitadb.api.requestResponse.chunk.DefaultSlicer;
import io.evitadb.api.requestResponse.chunk.Slicer;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.PrefetchFormulaVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * This DTO represents the carrier object that combines the constructed filtering {@link #filterFormula} with the link to
 * {@link #targetIndexes} used for evaluation so that they can be reused for extra result computation and also
 * optional reference to {@link #prefetchFormulaVisitor} that is capable of prefetching entity bodies that could
 * be used for filtering/sorting instead of accessing the indexes.
 */
@RequiredArgsConstructor
public class QueryPlanBuilder implements FetchRequirementCollector {
	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	private final QueryPlanningContext queryContext;
	/**
	 * Filtering formula tree.
	 */
	@Nonnull
	@Getter private final Formula filterFormula;
	/**
	 * Reference to {@link FilterByVisitor} used for creating filterFormula.
	 */
	@Getter private final FilterByVisitor filterByVisitor;
	/**
	 * Indexes that were used for creating {@link #filterFormula}.
	 */
	@Nonnull
	@Getter private final TargetIndexes<?> targetIndexes;
	/**
	 * Optional visitor that collected information about target entities so that they can
	 * be fetched upfront and filtered/ordered by their properties.
	 */
	@Nonnull
	@Getter private final PrefetchFormulaVisitor prefetchFormulaVisitor;
	/**
	 * The sorters that is responsible for ordering the filtered results.
	 */
	@Nullable
	@Getter private Collection<Sorter> sorters;
	/**
	 * The `slicer` variable represents an instance of the Slicer interface used to determine the offset and limit
	 * for paginating query results. By default, it is set to the `DefaultSlicer` instance. The slicer can be customized
	 * to apply different pagination strategies by invoking the {@link #setSlicer} method.
	 */
	@Nullable
	@Getter private Slicer slicer = DefaultSlicer.INSTANCE;
	/**
	 * Collection of {@link ExtraResultProducer} that compute additional results requested in response.
	 */
	@Nonnull
	@Getter private Collection<ExtraResultProducer> extraResultProducers = Collections.emptyList();

	/**
	 * Returns empty query plan.
	 */
	@Nonnull
	public static QueryPlan empty(@Nonnull QueryPlanningContext queryContext) {
		return new QueryPlan(
			queryContext,
			"None",
			EmptyFormula.INSTANCE,
			null,
			List.of(NoSorter.INSTANCE),
			DefaultSlicer.INSTANCE,
			Collections.emptyList()
		);
	}

	@Override
	public void addRequirementsToPrefetch(@Nonnull EntityContentRequire... require) {
		this.queryContext.addRequirementToPrefetch(require);
	}

	@Nonnull
	@Override
	public EntityContentRequire[] getRequirementsToPrefetch() {
		return this.queryContext.getRequirementsToPrefetch();
	}

	/**
	 * Returns description of the variant of this builder (source index).
	 */
	@Nonnull
	public String getDescription() {
		return this.targetIndexes.getIndexDescription();
	}

	/**
	 * Returns description of the variant of this builder (source index).
	 */
	@Nonnull
	public String getDescriptionWithCosts() {
		return this.targetIndexes.toStringWithCosts(getEstimatedCost());
	}

	/**
	 * Returns estimated costs for computing filtered result.
	 *
	 * @see Formula#getEstimatedCost()
	 */
	public long getEstimatedCost() {
		return this.filterFormula.getEstimatedCost();
	}

	/**
	 * Method accepts a sorters that should be used for sorting the filtered results.
	 *
	 * @param sorters the list of sorters that defines the sorting logic to be applied to the query results
	 */
	public void setSorters(@Nonnull List<Sorter> sorters) {
		this.sorters = sorters;
	}

	/**
	 * Sets the slicer that will be used to determine the offset and limit for query results.
	 *
	 * @param slicer the slicer responsible for calculating offset and limit
	 */
	public void setSlicer(@Nonnull Slicer slicer) {
		this.slicer = slicer;
	}

	/**
	 * Method accepts a collection of extra result producers that compute additional results requested in the response.
	 */
	public void setExtraResultProducers(@Nonnull Collection<ExtraResultProducer> extraResultProducers) {
		this.extraResultProducers = extraResultProducers;
	}

	/**
	 * Creates a final query plan instance.
	 */
	@Nonnull
	public QueryPlan build() {
		ofNullable(this.queryContext.getQueryFinishedEvent())
			.ifPresent(FinishedEvent::startExecuting);
		// propagate all collected requirements to the prefetch formula visitor
		this.prefetchFormulaVisitor.addRequirement(
			this.queryContext.getRequirementsToPrefetch()
		);
		return new QueryPlan(
			this.queryContext,
			this.targetIndexes.getIndexDescription(),
			this.filterFormula,
			this.prefetchFormulaVisitor.createPrefetcherIfNeededOrWorthwhile().orElse(null),
			this.sorters == null || this.sorters.isEmpty() ? List.of(NoSorter.INSTANCE) : this.sorters,
			this.slicer,
			this.extraResultProducers
		);
	}
}
