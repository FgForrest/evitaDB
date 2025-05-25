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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The siblings statistics computer computes hierarchy statistics for all siblings of hierarchy node that is changed
 * during the computation (hence the `traveling` word in the name). The computer traverses the hierarchy deeply
 * respecting the `scopePredicate` and excluding traversal of tree nodes matching `exclusionPredicate`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SiblingsStatisticsTravelingComputer extends AbstractSiblingsStatisticsComputer {
	/**
	 * Contains the reference to the parent node for which the siblings should be computed. NULL value represents
	 * the virtual non-existent root = siblings will be the root hierarchical nodes. The value is changing as
	 * traveling computer is re-used for computing siblings of different hierarchy tree nodes.
	 */
	@Nullable
	private Integer parentNodeId;

	public SiblingsStatisticsTravelingComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate exclusionPredicate,
		@Nullable HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(
			context, entityFetcher,
			hierarchyFilterPredicateProducer,
			exclusionPredicate, scopePredicate,
			statisticsBase, statisticsType
		);
	}

	@Override
	protected int getDistanceCompensation() {
		return -1;
	}

	/**
	 * Fabricates single collection of {@link LevelInfo} for requested hierarchical entity type. It respects
	 * the {@link EntityLocaleEquals} and {@link HierarchyWithin} constraints used in the query. It also uses
	 * `filteringFormula` to limit the reported cardinalities in level info objects.
	 */
	@Nonnull
	public List<Accumulator> createStatistics(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nullable Integer parentNodeId,
		int exceptNodeId
	) {
		try {
			Assert.isPremiseValid(this.parentNodeId == null, "The context was not properly cleared!");
			this.parentNodeId = parentNodeId;
			// the language predicate is used to filter out entities that doesn't have requested language variant
			final HierarchyFilteringPredicate exceptPivotNode = new MatchNodeIdHierarchyFilteringPredicate(exceptNodeId).negate();
			return createStatistics(
				executionContext,
				this.scopePredicate,
				filterPredicate.and(exceptPivotNode)
			);
		} finally {
			this.parentNodeId = null;
		}
	}

	@Override
	@Nonnull
	protected OptionalInt getParentNodeId(@Nonnull HierarchyProducerContext context) {
		return this.parentNodeId == null ? OptionalInt.empty() : OptionalInt.of(this.parentNodeId);
	}

}
