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

import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.reference.producer.FacetSummaryAdapter;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link FacetSummaryOfReference} to
 * {@link ReferenceSummaryProducer}. It delegates all logic to {@link ReferenceSummaryOfReferenceTranslator}
 * to avoid code duplication.
 * The producer instance has all pointers necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * TOBEDONE JNO - remove also
 * io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator#createProducerInternal
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated can be removed after FacetSummaryOfReference constraint is removed
 */
// TOBEDONE: deprecated - remove when FacetSummary constraint is removed (https://github.com/FgForrest/evitaDB/issues/538)
@Deprecated(since = "2026.2", forRemoval = true)
public class FacetSummaryOfReferenceTranslator
	implements RequireConstraintTranslator<FacetSummaryOfReference>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(
		@Nonnull FacetSummaryOfReference facetSummaryOfReference,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		return ReferenceSummaryOfReferenceTranslator.createProducerInternal(
			facetSummaryOfReference.getReferenceName(),
			facetSummaryOfReference.getStatisticsDepth(),
			facetSummaryOfReference.getFacetEntityRequirement().orElse(null),
			facetSummaryOfReference.getGroupEntityRequirement().orElse(null),
			facetSummaryOfReference.getFilterBy().orElse(null),
			facetSummaryOfReference.getFilterGroupBy().orElse(null),
			facetSummaryOfReference.getOrderBy().orElse(null),
			facetSummaryOfReference.getOrderGroupBy().orElse(null),
			FacetSummaryAdapter.INSTANCE,
			extraResultPlanner
		);
	}

}
