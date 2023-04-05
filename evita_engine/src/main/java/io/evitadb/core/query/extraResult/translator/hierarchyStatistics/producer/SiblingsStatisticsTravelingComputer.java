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

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SiblingsStatisticsTravelingComputer extends AbstractSiblingsStatisticsComputer {
	private Integer parentNodeId;

	public SiblingsStatisticsTravelingComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		super(
			context, entityFetcher, scopePredicate,
			filterPredicate, statisticsBase, statisticsType
		);
	}

	@Override
	protected int getDistanceModifier() {
		return -1;
	}

	/**
	 * Fabricates single collection of {@link LevelInfo} for requested hierarchical entity type. It respects
	 * the {@link EntityLocaleEquals} and {@link HierarchyWithin} constraints used in the query. It also uses
	 * `filteringFormula` to limit the reported cardinalities in level info objects.
	 */
	@Nonnull
	public List<LevelInfo> createStatistics(
		@Nonnull Formula filteredEntityPks,
		@Nullable Integer parentNodeId,
		int exceptNodeId
	) {
		try {
			Assert.isPremiseValid(this.parentNodeId == null, "The context was not properly cleared!");
			this.parentNodeId = parentNodeId;
			// the language predicate is used to filter out entities that doesn't have requested language variant
			return createStatistics(
				filteredEntityPks,
				scopePredicate,
				filterPredicate.and(nodeId -> nodeId != exceptNodeId)
			);
		} finally {
			this.parentNodeId = null;
		}
	}

	@Override
	@Nonnull
	protected OptionalInt getParentNodeId(@Nonnull HierarchyProducerContext context) {
		return parentNodeId == null ? OptionalInt.empty() : OptionalInt.of(parentNodeId);
	}

}
