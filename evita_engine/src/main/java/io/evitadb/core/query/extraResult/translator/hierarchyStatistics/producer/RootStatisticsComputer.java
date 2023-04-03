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

import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.StatisticsHierarchyVisitor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class RootStatisticsComputer extends AbstractHierarchyStatisticsComputer {

	public RootStatisticsComputer(
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
		final Deque<Accumulator> accumulator = new LinkedList<>();

		// accumulator is used to gather information about its children gradually
		final Accumulator root = new Accumulator(null, () -> 0);
		accumulator.add(root);

		// we always start with root nodes, but we respect the children exclusion
		context.entityIndex().traverseHierarchy(
			new StatisticsHierarchyVisitor(
				context.removeEmptyResults(),
				scopePredicate, filterPredicate,
				filteredEntityPks, accumulator,
				context.hierarchyReferencingEntityPks(), entityFetcher,
				statisticsType
			),
			ofNullable(context.hierarchyWithin())
				.map(HierarchyFilterConstraint::getExcludedChildrenIds)
				.orElse(EMPTY_IDS)
		);

		return root.getChildren();
	}

}
