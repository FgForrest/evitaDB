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
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ParentStatisticsHierarchyVisitor;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * The parent statistics computer computes hierarchy statistics for all parents of requested hierarchy node.
 * The computer traverses the hierarchy deeply respecting the `scopePredicate` and excluding traversal of tree nodes
 * matching `exclusionPredicate`. The parent computer can co-operate with the {@link SiblingsStatisticsTravelingComputer}
 * so that it can compute siblings of each returned parent level.
 *
 * @see SiblingsStatisticsTravelingComputer for more information
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ParentStatisticsComputer extends AbstractHierarchyStatisticsComputer {
	/**
	 * Optional siblings computer used for computing the hierarchy statistics for each of the parent node.
	 */
	@Nullable private final SiblingsStatisticsTravelingComputer siblingsStatisticsComputer;

	public ParentStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate exclusionPredicate,
		@Nullable HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType,
		@Nullable SiblingsStatisticsTravelingComputer siblingsStatisticsComputer
	) {
		super(
			context, entityFetcher,
			hierarchyFilterPredicateProducer,
			exclusionPredicate, scopePredicate,
			statisticsBase, statisticsType
		);
		this.siblingsStatisticsComputer = siblingsStatisticsComputer;
	}

	@Nonnull
	@Override
	protected List<Accumulator> createStatistics(
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		if (context.hierarchyFilter() instanceof HierarchyWithin) {
			final EntityIndex<?> entityIndex = context.entityIndex();
			final Bitmap hierarchyNodes = context.queryContext().getRootHierarchyNodes();

			final ChildrenStatisticsHierarchyVisitor childVisitor = new ChildrenStatisticsHierarchyVisitor(
				context.removeEmptyResults(),
				0,
				hierarchyNodes::contains,
				(hierarchyNodeId, level, distance) -> distance == 0,
				filterPredicate,
				value -> context.directlyQueriedEntitiesFormulaProducer().apply(value, statisticsBase),
				entityFetcher,
				statisticsType
			);

			Assert.isTrue(
				hierarchyNodes.size() == 1,
				"In order to generate parent hierarchy statistics the HierarchyWithin filter must select exactly " +
					"one parent node. Currently, it selects `" + hierarchyNodes.size() + "` nodes."
			);
			final int parentNodeId = hierarchyNodes.getFirst();

			entityIndex.traverseHierarchyFromNode(
				childVisitor,
				parentNodeId,
				false,
				filterPredicate
			);

			final List<Accumulator> children = childVisitor.getAccumulators();
			Assert.isPremiseValid(children.size() == 1, "Expected exactly one node but found `" + children.size() + "`!");
			final Accumulator startNode = children.get(0);

			final SiblingsStatisticsTravelingComputer siblingsComputerToUse;
			if (siblingsStatisticsComputer != null) {
				siblingsComputerToUse = siblingsStatisticsComputer;
			} else if (statisticsType.isEmpty()) {
				siblingsComputerToUse = null;
			} else {
				siblingsComputerToUse = new SiblingsStatisticsTravelingComputer(
					context, entityPk -> new EntityReference(context.entitySchema().getName(), entityPk),
					context.hierarchyFilterPredicateProducer(),
					havingPredicate,
					HierarchyTraversalPredicate.ONLY_DIRECT_DESCENDANTS,
					statisticsBase, statisticsType
				);
			}

			final HierarchyFilteringPredicate exceptStartNode = new MatchNodeIdHierarchyFilteringPredicate(
				startNode.getEntity().getPrimaryKey()
			).negate();
			final ParentStatisticsHierarchyVisitor parentVisitor = new ParentStatisticsHierarchyVisitor(
				hierarchyNodes::contains,
				scopePredicate,
				filterPredicate.and(exceptStartNode),
				value -> context.directlyQueriedEntitiesFormulaProducer().apply(value, statisticsBase),
				entityFetcher,
				siblingsComputerToUse,
				siblingsStatisticsComputer == null
			);
			entityIndex.traverseHierarchyToRoot(
				parentVisitor,
				parentNodeId
			);
			return parentVisitor.getResult(startNode);
		} else {
			return Collections.emptyList();
		}
	}

}
