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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The siblings statistics computer computes hierarchy statistics for all siblings of requested hierarchy node.
 * The computer traverses the hierarchy deeply respecting the `scopePredicate` and excluding traversal of tree nodes
 * matching `exclusionPredicate`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SiblingsStatisticsComputer extends AbstractSiblingsStatisticsComputer {

	public SiblingsStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate exclusionPredicate,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(context, entityFetcher, hierarchyFilterPredicateProducer, exclusionPredicate, scopePredicate, statisticsBase, statisticsType);
	}

	@Override
	protected int getDistanceCompensation() {
		return -1;
	}

	@Override
	@Nonnull
	protected OptionalInt getParentNodeId(@Nonnull HierarchyProducerContext context) {
		if (context.hierarchyFilter() instanceof HierarchyWithin) {
			final Bitmap hierarchyNodes = context.queryContext().getRootHierarchyNodes();
			Assert.isTrue(
				hierarchyNodes.size() == 1,
				"In order to generate sibling hierarchy statistics the HierarchyWithin filter must select exactly " +
					"one parent node. Currently, it selects `" + hierarchyNodes.size() + "` nodes."
			);
			final int parentNodeId = hierarchyNodes.getFirst();

			return context.entityIndex().getParentNode(parentNodeId);
		} else {
			return OptionalInt.empty();
		}
	}

}
