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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/**
 * Deprecated {@link ReferenceSummaryResultAdapter} implementation that emits the legacy
 * {@link FacetSummary} extra-result DTO. Used by the
 * {@link io.evitadb.core.query.extraResult.translator.facet.FacetSummaryTranslator} and
 * {@link io.evitadb.core.query.extraResult.translator.facet.FacetSummaryOfReferenceTranslator}
 * so that requests referencing the deprecated
 * {@link io.evitadb.api.query.require.FacetSummary} /
 * {@link io.evitadb.api.query.require.FacetSummaryOfReference} constraints keep seeing
 * {@link FacetSummary} under the {@link FacetSummary}{@code .class} key in
 * {@link io.evitadb.api.requestResponse.EvitaResponse#getExtraResult}.
 *
 * The {@link #createGroupStatistics} factory method creates
 * {@link FacetGroupStatistics} instances so that the deprecated
 * {@link FacetSummary#getFacetGroupStatistics(String)} methods (which rely on
 * {@code instanceof} checks) keep returning non-null for existing callers.
 *
 * Exists only to bridge the transition to
 * {@link io.evitadb.api.requestResponse.extraResult.ReferenceSummary}. Delete this class
 * together with the deprecated require constraints and the {@link FacetSummary} DTO.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated Remove together with {@link io.evitadb.api.query.require.FacetSummary},
 *             {@link io.evitadb.api.query.require.FacetSummaryOfReference} and
 *             {@link FacetSummary}.
 */
@Deprecated
public final class FacetSummaryAdapter implements ReferenceSummaryResultAdapter<FacetGroupStatistics> {

	/**
	 * Stateless singleton — the adapter does nothing beyond calling constructors, so a
	 * shared instance avoids allocating a new adapter per query.
	 */
	public static final FacetSummaryAdapter INSTANCE = new FacetSummaryAdapter();

	private FacetSummaryAdapter() {
	}

	@Nonnull
	@Override
	public FacetGroupStatistics createGroupStatistics(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable EntityClassifier groupEntity,
		int count,
		@Nonnull Map<Integer, FacetStatistics> facetStatistics,
		@Nonnull Map<String, HistogramContract> histogramStatistics
	) {
		// deprecated adapter keeps the legacy DTO shape — histogramStatistics are ignored here, the
		// canonical ReferenceSummary adapter carries them forward instead
		return new FacetGroupStatistics(
			referenceSchema,
			groupEntity,
			count,
			facetStatistics
		);
	}

	@Nonnull
	@Override
	public EvitaResponseExtraResult createResult(
		@Nonnull Map<String, Collection<FacetGroupStatistics>> statisticsByReferenceName
	) {
		return new FacetSummary(statisticsByReferenceName);
	}

}
