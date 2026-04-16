/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.facet;

import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryTranslator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link FacetSummary} to
 * {@link ReferenceSummaryProducer}. It delegates all logic to {@link ReferenceSummaryTranslator} to avoid
 * code duplication.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
// TODO: can be removed after FacetSummary constraint is removed
public class FacetSummaryTranslator implements RequireConstraintTranslator<FacetSummary>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull FacetSummary facetSummary, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		return ReferenceSummaryTranslator.createProducerInternal(
			facetSummary.getStatisticsDepth(),
			facetSummary.getFacetEntityRequirement(),
			facetSummary.getGroupEntityRequirement(),
			facetSummary.getFilterBy(),
			facetSummary.getFilterGroupBy(),
			facetSummary.getOrderBy(),
			facetSummary.getOrderGroupBy(),
			extraResultPlanner
		);
	}

}
