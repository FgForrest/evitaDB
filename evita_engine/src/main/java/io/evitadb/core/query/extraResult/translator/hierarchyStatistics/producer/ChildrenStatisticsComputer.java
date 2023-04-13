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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * The children statistics computer computes hierarchy statistics for all children of requested hierarchy node in
 * {@link HierarchyWithin} filtering constraint. The computer traverses the hierarchy deeply respecting the
 * `scopePredicate` and excluding traversal of tree nodes matching `exclusionPredicate`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ChildrenStatisticsComputer extends AbstractHierarchyStatisticsComputer {

	public ChildrenStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull HierarchyFilteringPredicate exclusionPredicate,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(context, entityFetcher, exclusionPredicate, scopePredicate, statisticsBase, statisticsType);
	}

	@Nonnull
	@Override
	protected List<Accumulator> createStatistics(
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		final HierarchyFilteringPredicate combinedFilteringPredicate = exclusionPredicate == null ?
			filterPredicate :
			exclusionPredicate.negate().and(filterPredicate);
		final ChildrenStatisticsHierarchyVisitor childrenVisitor = new ChildrenStatisticsHierarchyVisitor(
			context.removeEmptyResults(),
			0,
			scopePredicate,
			combinedFilteringPredicate,
			value -> context.directlyQueriedEntitiesFormulaProducer().apply(value, statisticsBase),
			entityFetcher,
			statisticsType
		);
		if (context.hierarchyFilter() instanceof HierarchyWithinRoot) {
			// if there is within hierarchy root query we start at root nodes
			context.entityIndex().traverseHierarchy(
				childrenVisitor,
				combinedFilteringPredicate.negate()
			);
		} else if (context.hierarchyFilter() instanceof HierarchyWithin hierarchyWithin) {
			// if root node is set, use different traversal method
			context.entityIndex().traverseHierarchyFromNode(
				childrenVisitor,
				hierarchyWithin.getParentId(),
				false,
				combinedFilteringPredicate.negate()
			);
		} else {
			// if there is not within hierarchy constraint query we start at root nodes and use no exclusions
			context.entityIndex().traverseHierarchy(
				childrenVisitor,
				combinedFilteringPredicate.negate()
			);
		}

		return childrenVisitor.getAccumulators();
	}

}
