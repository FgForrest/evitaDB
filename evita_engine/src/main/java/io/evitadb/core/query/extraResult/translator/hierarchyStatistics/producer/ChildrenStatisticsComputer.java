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
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ChildrenStatisticsComputer extends AbstractHierarchyStatisticsComputer {

	public ChildrenStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(context, entityFetcher, scopePredicate, filterPredicate, statisticsBase, statisticsType);
	}

	@Nonnull
	@Override
	protected List<LevelInfo> createStatistics(
		@Nonnull Formula filteredEntityPks,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		final ChildrenStatisticsHierarchyVisitor childrenVisitor = new ChildrenStatisticsHierarchyVisitor(
			context.removeEmptyResults(),
			0,
			scopePredicate, filterPredicate,
			filteredEntityPks,
			context.hierarchyReferencingEntityPks(), entityFetcher,
			statisticsType
		);
		if (context.hierarchyFilter() instanceof HierarchyWithinRoot hierarchyWithinRoot) {
			// if there is within hierarchy root query we start at root nodes
			context.entityIndex().traverseHierarchy(
				childrenVisitor,
				hierarchyWithinRoot.getExcludedChildrenIds()
			);
		} else if (context.hierarchyFilter() instanceof HierarchyWithin hierarchyWithin) {
			// if root node is set, use different traversal method
			context.entityIndex().traverseHierarchyFromNode(
				childrenVisitor,
				hierarchyWithin.getParentId(),
				false,
				hierarchyWithin.getExcludedChildrenIds()
			);
		} else {
			// if there is not within hierarchy constraint query we start at root nodes and use no exclusions
			context.entityIndex().traverseHierarchy(
				childrenVisitor
			);
		}

		return childrenVisitor.getResult();
	}

}
