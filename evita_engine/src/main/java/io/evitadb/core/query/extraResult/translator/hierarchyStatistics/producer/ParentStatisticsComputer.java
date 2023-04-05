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
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ParentStatisticsHierarchyVisitor;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ParentStatisticsComputer extends AbstractHierarchyStatisticsComputer {
	@Nullable private final SiblingsStatisticsTravelingComputer siblingsStatisticsComputer;

	public ParentStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType,
		@Nullable SiblingsStatisticsTravelingComputer siblingsStatisticsComputer
	) {
		super(context, entityFetcher, scopePredicate, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE, statisticsBase, statisticsType);
		this.siblingsStatisticsComputer = siblingsStatisticsComputer;
	}

	@Nonnull
	@Override
	protected List<LevelInfo> createStatistics(
		@Nonnull Formula filteredEntityPks,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		if (context.hierarchyFilter() instanceof HierarchyWithin hierarchyWithin) {
			final EntityIndex entityIndex = context.entityIndex();

			final ChildrenStatisticsHierarchyVisitor childVisitor = new ChildrenStatisticsHierarchyVisitor(
				context.removeEmptyResults(),
				0,
				(hierarchyNodeId, level, distance) -> distance == 0,
				filterPredicate,
				filteredEntityPks,
				context.hierarchyReferencingEntityPks(), entityFetcher,
				statisticsType
			);
			entityIndex.traverseHierarchyFromNode(
				childVisitor,
				hierarchyWithin.getParentId(),
				false,
				hierarchyWithin.getExcludedChildrenIds()
			);

			final List<LevelInfo> childVisitorResult = childVisitor.getResult();
			Assert.isPremiseValid(childVisitorResult.size() == 1, "Expected exactly one node!");
			final LevelInfo startNode = childVisitorResult.get(0);

			final SiblingsStatisticsTravelingComputer siblingsComputerToUse;
			if (siblingsStatisticsComputer != null) {
				siblingsComputerToUse = siblingsStatisticsComputer;
			} else if (statisticsType.isEmpty()) {
				siblingsComputerToUse = null;
			} else {
				siblingsComputerToUse = new SiblingsStatisticsTravelingComputer(
					context, entityPk -> new EntityReference(context.entitySchema().getName(), entityPk),
					HierarchyTraversalPredicate.ONLY_DIRECT_DESCENDANTS,
					filterPredicate, statisticsBase, statisticsType
				);
			}

			final ParentStatisticsHierarchyVisitor parentVisitor = new ParentStatisticsHierarchyVisitor(
				scopePredicate,
				filterPredicate.and(value -> value != startNode.entity().getPrimaryKey()),
				filteredEntityPks,
				context.hierarchyReferencingEntityPks(), entityFetcher,
				statisticsType,
				siblingsComputerToUse,
				siblingsStatisticsComputer == null
			);
			entityIndex.traverseHierarchyToRoot(
				parentVisitor,
				hierarchyWithin.getParentId()
			);
			return parentVisitor.getResult(startNode, filteredEntityPks);
		} else {
			return Collections.emptyList();
		}
	}

}
