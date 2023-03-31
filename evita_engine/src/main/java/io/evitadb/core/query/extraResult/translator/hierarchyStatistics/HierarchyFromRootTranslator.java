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

import io.evitadb.api.query.require.HierarchyFromRoot;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.predicate.FilteredHierarchyEntityPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyFilteringPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyTraversalPredicate;

import java.util.EnumSet;
import java.util.Optional;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class HierarchyFromRootTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyFromRoot>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyFromRoot fromRoot, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final HierarchyStatisticsProducer producer = getHierarchyStatisticsProducer(extraResultPlanningVisitor);
		final Optional<HierarchyStatistics> statistics = fromRoot.getStatistics();
		final HierarchyProducerContext context = producer.getContext(fromRoot.getName());
		final HierarchyEntityPredicate hierarchyEntityPredicate;
		if (fromRoot.getFilterBy().isEmpty() && fromRoot.getStopAt().isEmpty()) {
			hierarchyEntityPredicate = HierarchyEntityPredicate.MATCH_ALL;
		} else {
			final HierarchyFilteringPredicate filteringPredicate = fromRoot.getFilterBy()
				.map(it -> (HierarchyFilteringPredicate) new FilteredHierarchyEntityPredicate(context, it))
				.orElse(HierarchyEntityPredicate.ACCEPT_ALL_NODES_PREDICATE);
			final HierarchyTraversalPredicate traversingPredicate = fromRoot.getStopAt()
				.map(it -> stopAtConstraintToPredicate(context, it))
				.orElse(HierarchyEntityPredicate.NEVER_STOP_PREDICATE);
			hierarchyEntityPredicate = new HierarchyEntityPredicate(
				filteringPredicate,
				traversingPredicate
			);
		}
		producer.computeChildren(
			fromRoot.getOutputName(),
			hierarchyEntityPredicate,
			createEntityFetcher(
				fromRoot,
				fromRoot.getEntityFetch().orElse(null),
				producer
			),
			statistics.map(HierarchyStatistics::getStatisticsBase).orElse(null),
			statistics.map(HierarchyStatistics::getStatisticsType).orElseGet(() -> EnumSet.noneOf(StatisticsType.class))
		);
		return producer;
	}
}
