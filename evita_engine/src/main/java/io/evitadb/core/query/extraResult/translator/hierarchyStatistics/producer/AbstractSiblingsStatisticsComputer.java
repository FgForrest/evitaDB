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
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Abstract ancestor for siblings hierarchy statistics computers. Contains shared logic and data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
abstract class AbstractSiblingsStatisticsComputer extends AbstractHierarchyStatisticsComputer {

	public AbstractSiblingsStatisticsComputer(
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
		final OptionalInt parentNode = getParentNodeId(this.context);
		final Bitmap hierarchyNodes = this.context.rootHierarchyNodesSupplier().get();
		final ChildrenStatisticsHierarchyVisitor visitor = new ChildrenStatisticsHierarchyVisitor(
			executionContext,
			this.context.removeEmptyResults(),
			getDistanceCompensation(),
			hierarchyNodes::contains,
			scopePredicate,
			filterPredicate,
			value -> this.context.directlyQueriedEntitiesFormulaProducer().apply(value, this.statisticsBase),
			this.entityFetcher,
			this.statisticsType
		);
		parentNode.ifPresentOrElse(
			parentNodeId -> {
				// if root node is set, use different traversal method
				this.context.entityIndex().traverseHierarchyFromNode(
					visitor,
					parentNodeId,
					true,
					filterPredicate
				);
			},
			() -> {
				// if there is not within hierarchy constraint query we start at root nodes and use no exclusions
				this.context.entityIndex().traverseHierarchy(
					visitor,
					filterPredicate
				);
			}
		);

		return visitor.getAccumulators();
	}

	/**
	 * The number contains a number that needs to be added to a `distance` variable when the real root is different
	 * to the first visited hierarchy node. It is usually used for siblings computation when the parent node needs
	 * to be omitted and thus the distance needs to be lowered by one.
	 */
	protected abstract int getDistanceCompensation();

	/**
	 * Returns reference to the parent node of the sibling nodes. The result is `empty` for the siblings that represent
	 * the root nodes of the hierarchy tree.
	 */
	@Nonnull
	protected abstract OptionalInt getParentNodeId(@Nonnull HierarchyProducerContext context);

}
