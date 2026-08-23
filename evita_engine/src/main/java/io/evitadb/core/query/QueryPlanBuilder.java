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
import io.evitadb.index.Index;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.usage.SchemaCapabilityUsage;
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
	 *
	 * This is also where the winning index set is counted as **queried** ({@link IndexActivity}). The seam is here
	 * rather than on {@link QueryPlan} because this is the last point that still holds the index *instances*, which
	 * the plan itself does not - it carries only their description string. A query answered without any index at all
	 * goes through {@link #empty(QueryPlanningContext)} instead and correctly counts nothing.
	 *
	 * **One increment means "the planner handed this plan back", not "this plan produced a response".** The set counted
	 * is the one that won the cost comparison, and a losing variant is normally never built - but two callers build a
	 * plan only to take a single piece off it: `ReferencedEntityFetcher` reads `getSorters()` when references are
	 * ordered by a referenced entity's property, and `HavingTranslatorHelper` reads `getFilter()`. Both count, and that
	 * is the intended reading - the sorter and the formula do run against those very indexes.
	 *
	 * **Under the two verification debug modes one query counts an index repeatedly, and counts losing candidates
	 * too.** With {@link io.evitadb.api.query.require.DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} or
	 * {@link io.evitadb.api.query.require.DebugMode#VERIFY_POSSIBLE_CACHING_TREES} enabled,
	 * {@link QueryPlanner#verifyConsistentResultsInAllPlans} builds and executes the preferred plan, every alternative
	 * and every cacheable variant, after which the preferred plan is built once more to be returned. Those plans
	 * genuinely execute against the indexes they name, so the extra increments are work performed rather than a
	 * miscount, and are left standing; exact arithmetic on these readings simply requires a session with no
	 * verification debug mode enabled.
	 *
	 * This is also where the schema capabilities the query asked for are counted as **requested**
	 * ({@link SchemaCapabilityUsage}), and the two readings deliberately behave differently under those debug modes.
	 * The capability side counts **once per logical query regardless**, because
	 * {@link QueryPlanningContext#drainRequestedCapabilities()} hands the accumulator over and leaves the context
	 * holding nothing - every further build of that same query finds an empty list. That difference is not an
	 * inconsistency: an index counts a physical read that genuinely happened again, while a capability counts a
	 * question the query asked, and asking it a second time to verify the answer does not make it a second question.
	 * The empty-plan short-circuit {@link #empty(QueryPlanningContext)} counts neither, and on the capability side it
	 * cannot: it is taken when index selection comes back empty, which is *before* the filter is translated even once,
	 * so nothing has been accumulated yet and there is nothing a flush could find.
	 *
	 * The cost is `O(winning set)` volatile increments plus one per distinct capability the query named, both bounded
	 * from below by the reads the query is about to perform on those very indexes.
	 */
	@Nonnull
	public QueryPlan build() {
		ofNullable(this.queryContext.getQueryFinishedEvent())
			.ifPresent(FinishedEvent::startExecuting);
		final List<? extends Index<?>> winningIndexes = this.targetIndexes.getIndexes();
		final List<SchemaCapabilityUsage> requestedCapabilities = this.queryContext.drainRequestedCapabilities();
		if (!winningIndexes.isEmpty() || !requestedCapabilities.isEmpty()) {
			// one instant for both readings, so a single query cannot stamp them with two different moments
			final long now = System.currentTimeMillis();
			for (final Index<?> index : winningIndexes) {
				// absent on a server that does not track usage statistics - no holder was ever allocated for the index
				final IndexActivity activity = index.getActivity();
				if (activity != null) {
					activity.recordQuery(now);
				}
			}
			// the holders were resolved while the schema was being looked up anyway, so this is an increment per
			// distinct capability the query named - no lookup, no hashing, no allocation
			for (SchemaCapabilityUsage requestedCapability : requestedCapabilities) {
				requestedCapability.recordRequested(now);
			}
		}
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
