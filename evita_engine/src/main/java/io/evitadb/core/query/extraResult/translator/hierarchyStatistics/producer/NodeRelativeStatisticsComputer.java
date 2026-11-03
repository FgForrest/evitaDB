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
	/**
	 * The predicate matching the node the statistics start at. It is created while the query is planned, so that its
	 * filter is checked against the schema up front - also when the statistics are never computed, because the query
	 * matches nothing.
	 */
	private final FilteringFormulaHierarchyEntityPredicate parentIdPredicate;

	/**
	 * Creates the computer of the statistics of the children of the node matched by `parentIdPredicate`.
	 *
	 * @param context                          the context of the enclosing hierarchy requirement
	 * @param entityFetcher                    the fetcher of the bodies of the nodes
	 * @param hierarchyFilterPredicateProducer the producer of the predicate of the nodes the query filter admits
	 * @param exclusionPredicate               the predicate of the nodes the hierarchy filter excludes
	 * @param scopePredicate                   the predicate bounding the traversal
	 * @param statisticsBase                   the base the statistics are computed from
	 * @param statisticsType                   the statistics to compute
	 * @param parentIdPredicate                the predicate matching the node the statistics start at
	 */
	public NodeRelativeStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate exclusionPredicate,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType,
		@Nonnull FilteringFormulaHierarchyEntityPredicate parentIdPredicate
	) {
		super(
			context, entityFetcher,
			hierarchyFilterPredicateProducer,
			exclusionPredicate, scopePredicate,
			statisticsBase, statisticsType
		);
		this.parentIdPredicate = parentIdPredicate;
	}

	@Nonnull
	@Override
	protected List<Accumulator> createStatistics(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	) {
		this.parentIdPredicate.initializeIfNotAlreadyInitialized(executionContext);
		final Bitmap parentId = this.parentIdPredicate.getFilteringFormula().compute();

		if (!parentId.isEmpty()) {
			Assert.isTrue(
				parentId.size() == 1,
				() -> "The filter by constraint: `" + this.parentIdPredicate.getFilterDescription() + "` matches " +
					"multiple (" + parentId.size() + ") hierarchy nodes! " +
					"Hierarchy statistics computation expects only a single node will be matched (due to performance reasons)."
			);
			final Bitmap hierarchyNodes = this.context.rootHierarchyNodesSupplier().get();
			// we always start at specific node, but we respect the excluded children
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
			this.context.entityIndex().traverseHierarchyFromNode(
				visitor,
				parentId.getFirst(),
				false,
				filterPredicate
			);
			return visitor.getAccumulators();
		} else {
			return Collections.emptyList();
		}

	}

}
