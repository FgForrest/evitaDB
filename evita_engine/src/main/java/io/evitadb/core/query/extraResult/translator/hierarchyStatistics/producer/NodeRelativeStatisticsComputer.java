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

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.ChildrenStatisticsHierarchyVisitor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * The node relative statistics computer computes hierarchy statistics for all children of particular parent node in
 * the hierarchy tree. The computer traverses the hierarchy deeply respecting the `scopePredicate` and excluding
 * traversal of tree nodes matching `exclusionPredicate`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class NodeRelativeStatisticsComputer extends AbstractHierarchyStatisticsComputer {
	private final FilterBy parentId;

	public NodeRelativeStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate exclusionPredicate,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType,
		@Nonnull FilterBy parentId
	) {
		super(context, entityFetcher, hierarchyFilterPredicateProducer, exclusionPredicate, scopePredicate, statisticsBase, statisticsType);
		this.parentId = parentId;
	}

	@Nonnull
	@Override
	protected List<Accumulator> createStatistics(
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		final FilteringFormulaHierarchyEntityPredicate parentIdPredicate = new FilteringFormulaHierarchyEntityPredicate(
			context.queryContext(),
			context.entityIndex(),
			parentId,
			context.referenceSchema()
		);
		final Bitmap parentId = parentIdPredicate.getFilteringFormula().compute();

		if (!parentId.isEmpty()) {
			Assert.isTrue(
				parentId.size() == 1,
				() -> "The filter by constraint: `" + parentIdPredicate.getFilterBy() + "` matches multiple (" + parentId.size() + ") hierarchy nodes! " +
					"Hierarchy statistics computation expects only single node will be matched (due to performance reasons)."
			);
			// we always start at specific node, but we respect the excluded children
			final ChildrenStatisticsHierarchyVisitor visitor = new ChildrenStatisticsHierarchyVisitor(
				context.removeEmptyResults(),
				0,
				scopePredicate,
				filterPredicate,
				value -> context.directlyQueriedEntitiesFormulaProducer().apply(value, statisticsBase),
				entityFetcher,
				statisticsType
			);
			context.entityIndex().traverseHierarchyFromNode(
				visitor,
				parentId.getFirst(),
				false,
				filterPredicate.negate()
			);
			return visitor.getAccumulators();
		} else {
			return Collections.emptyList();
		}

	}

}
