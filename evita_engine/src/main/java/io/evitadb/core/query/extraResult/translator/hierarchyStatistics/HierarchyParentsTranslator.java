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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyParents;
import io.evitadb.api.query.require.HierarchySiblings;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.ParentStatisticsComputer;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.SiblingsStatisticsTravelingComputer;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyParents} to
 * {@link ParentStatisticsComputer} optionally accompanied by {@link SiblingsStatisticsTravelingComputer} registered
 * inside {@link HierarchyStatisticsProducer}. The computer instance has all pointer necessary to compute result.
 * All operations in this translator are relatively cheap comparing to final result computation, that is deferred to
 * {@link HierarchyStatisticsProducer#fabricate(List)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyParentsTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyParents>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyParents parents, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final HierarchyStatisticsProducer producer = getHierarchyStatisticsProducer(extraResultPlanningVisitor);
		final Optional<HierarchyStatistics> statistics = parents.getStatistics();
		final HierarchyProducerContext context = producer.getContext(parents.getName());
		final HierarchyTraversalPredicate scopePredicate = parents.getStopAt()
			.map(it -> stopAtConstraintToPredicate(TraversalDirection.BOTTOM_UP, it, context.queryContext(), context.entityIndex(), context.referenceSchema()))
			.orElse(HierarchyTraversalPredicate.NEVER_STOP_PREDICATE);
		final SiblingsStatisticsTravelingComputer siblingsStatisticsComputer = parents.getSiblings()
			.map(it -> createComputer(context, it, parents.getEntityFetch().orElse(null), statistics.orElse(null)))
			.orElse(null);

		if (context.hierarchyFilter() instanceof HierarchyWithin) {
			producer.addComputer(
				parents.getName(),
				parents.getOutputName(),
				new ParentStatisticsComputer(
					context,
					createEntityFetcher(
						parents.getEntityFetch().orElse(null),
						producer.getContext(parents.getName())
					),
					context.hierarchyFilterPredicateProducer(),
					extraResultPlanningVisitor.getQueryContext().getHierarchyHavingPredicate(),
					scopePredicate,
					statistics.map(HierarchyStatistics::getStatisticsBase).orElse(null),
					statistics.map(HierarchyStatistics::getStatisticsType).orElseGet(() -> EnumSet.noneOf(StatisticsType.class)),
					siblingsStatisticsComputer
				)
			);
		} else {
			throw new EvitaInvalidUsageException(
				"Add `HierarchyWithin` constraint to a filtering constraint if you want to collect statistics for `HierarchyParents`."
			);
		}
		return producer;
	}

	/**
	 * Method creates instance of the {@link SiblingsStatisticsTravelingComputer} for resolving the siblings of each
	 * traversed parent node.
	 */
	@Nonnull
	private SiblingsStatisticsTravelingComputer createComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchySiblings siblings,
		@Nullable EntityFetch parentEntityFetch,
		@Nullable HierarchyStatistics parentStatistics
	) {
		final Optional<HierarchyStatistics> statistics = siblings.getStatistics().or(() -> ofNullable(parentStatistics));
		final HierarchyTraversalPredicate scopePredicate = siblings.getStopAt()
			.map(it -> stopAtConstraintToPredicate(TraversalDirection.TOP_DOWN, it, context.queryContext(), context.entityIndex(), context.referenceSchema()))
			.orElse((hierarchyNodeId, level, distance) -> distance == 0);
		return new SiblingsStatisticsTravelingComputer(
			context,
			createEntityFetcher(
				siblings.getEntityFetch().orElse(parentEntityFetch),
				context
			),
			context.hierarchyFilterPredicateProducer(),
			context.queryContext().getHierarchyHavingPredicate(),
			scopePredicate,
			statistics.map(HierarchyStatistics::getStatisticsBase).orElse(null),
			statistics.map(HierarchyStatistics::getStatisticsType).orElseGet(() -> EnumSet.noneOf(StatisticsType.class))
		);
	}
}
