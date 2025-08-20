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

import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * The root statistics computer computes hierarchy statistics for entire hierarchy tree. The computer traverses
 * the hierarchy deeply respecting the `scopePredicate` and excluding traversal of tree nodes matching
 * `exclusionPredicate`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class RootStatisticsComputer extends AbstractHierarchyStatisticsComputer {

	public RootStatisticsComputer(
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

	@Nonnull
	@Override
	protected List<Accumulator> createStatistics(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		// we always start with root nodes, but we respect the children exclusion
		final Bitmap hierarchyNodes = this.context.rootHierarchyNodesSupplier().get();
		final ChildrenStatisticsHierarchyVisitor visitor = new ChildrenStatisticsHierarchyVisitor(
			executionContext,
			this.context.removeEmptyResults(),
			0,
			hierarchyNodes::contains,
			scopePredicate,
			filterPredicate,
			value -> this.context.directlyQueriedEntitiesFormulaProducer().apply(value, this.statisticsBase),
			this.entityFetcher,
			this.statisticsType
		);
		this.context.entityIndex().traverseHierarchy(
			visitor,
			filterPredicate
		);

		return visitor.getAccumulators();
	}

}
