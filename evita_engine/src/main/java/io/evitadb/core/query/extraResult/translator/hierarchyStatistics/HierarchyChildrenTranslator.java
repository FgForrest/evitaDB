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

import io.evitadb.api.query.require.HierarchyChildren;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.AbstractHierarchyStatisticsComputer;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;

import java.util.EnumSet;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} translates {@link HierarchyChildren} to
 * configuration of {@link AbstractHierarchyStatisticsComputer} computer configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class HierarchyChildrenTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyChildren>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyChildren hierarchyChildren, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final HierarchyStatisticsProducer producer = getHierarchyStatisticsProducer(extraResultPlanningVisitor);
		final Optional<HierarchyStatistics> statistics = hierarchyChildren.getStatistics();
		producer.computeChildren(
			hierarchyChildren.getOutputName(),
			hierarchyChildren.getStopAt()
				.map(stopAt -> new HierarchyEntityPredicate(
					value -> true,
					stopAtConstraintToPredicate(producer.getContext(hierarchyChildren.getName()), stopAt))
				)
				.orElse(null),
			createEntityFetcher(
				hierarchyChildren,
				hierarchyChildren.getEntityFetch().orElse(null),
				producer
			),
			statistics.map(HierarchyStatistics::getStatisticsBase).orElse(null),
			statistics.map(HierarchyStatistics::getStatisticsType).orElseGet(() -> EnumSet.noneOf(StatisticsType.class))
		);
		return producer;
	}

}
