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
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.StatisticsHierarchyVisitor;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
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
		@Nonnull HierarchyEntityPredicate nodeFilter,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(context, entityFetcher, nodeFilter, statisticsBase, statisticsType);
	}

	@Nonnull
	@Override
	protected List<LevelInfo> createStatistics(
		@Nonnull RoaringBitmap filteredEntityPks,
		@Nonnull HierarchyEntityPredicate nodePredicate
	) {
		final Deque<Accumulator> accumulator = new LinkedList<>();

		// accumulator is used to gather information about its children gradually
		final Accumulator root = new Accumulator(null, () -> 0);
		accumulator.add(root);

		if (context.hierarchyWithin() instanceof HierarchyWithinRoot hierarchyWithinRoot) {
			// if there is within hierarchy root query we start at root nodes
			context.entityIndex().traverseHierarchy(
				new StatisticsHierarchyVisitor(
					context.removeEmptyResults(), nodePredicate,
					filteredEntityPks, accumulator,
					context.hierarchyReferencingEntityPks(), entityFetcher,
					statisticsType
				),
				hierarchyWithinRoot.getExcludedChildrenIds()
			);
		} else if (context.hierarchyWithin() instanceof HierarchyWithin hierarchyWithin) {
			// if root node is set, use different traversal method
			context.entityIndex().traverseHierarchyFromNode(
				new StatisticsHierarchyVisitor(
					context.removeEmptyResults(), nodePredicate,
					filteredEntityPks, accumulator,
					context.hierarchyReferencingEntityPks(), entityFetcher,
					statisticsType
				),
				hierarchyWithin.getParentId(),
				hierarchyWithin.isExcludingRoot(),
				hierarchyWithin.getExcludedChildrenIds()
			);
		} else {
			// if there is not within hierarchy constraint query we start at root nodes and use no exclusions
			context.entityIndex().traverseHierarchy(
				new StatisticsHierarchyVisitor(
					context.removeEmptyResults(), nodePredicate,
					filteredEntityPks, accumulator,
					context.hierarchyReferencingEntityPks(),
					entityFetcher,
					statisticsType
				)
			);
		}

		return root.getChildren();
	}

}
