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
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/**
 * {@link ReferenceSummaryResultAdapter} implementation that emits the canonical
 * {@link ReferenceSummary} extra-result DTO. Used by the
 * {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryTranslator}
 * and
 * {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator}
 * so that requests referencing
 * {@link io.evitadb.api.query.require.ReferenceSummary} /
 * {@link io.evitadb.api.query.require.ReferenceSummaryOfReference} receive a
 * {@link ReferenceSummary} under the {@link ReferenceSummary}{@code .class} key in
 * {@link io.evitadb.api.requestResponse.EvitaResponse#getExtraResult}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReferenceSummaryAdapter implements ReferenceSummaryResultAdapter<ReferenceGroupStatistics> {

	/**
	 * Stateless singleton — avoids allocating a new adapter per query.
	 */
	public static final ReferenceSummaryAdapter INSTANCE = new ReferenceSummaryAdapter();

	private ReferenceSummaryAdapter() {
	}

	@Nonnull
	@Override
	public ReferenceGroupStatistics createGroupStatistics(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable EntityClassifier groupEntity,
		int count,
		@Nonnull Map<Integer, FacetStatistics> facetStatistics
	) {
		return new ReferenceGroupStatistics(
			referenceSchema,
			groupEntity,
			count,
			facetStatistics
		);
	}

	@Nonnull
	@Override
	public EvitaResponseExtraResult createResult(
		@Nonnull Map<String, Collection<ReferenceGroupStatistics>> statisticsByReferenceName
	) {
		return new ReferenceSummary(statisticsByReferenceName);
	}

}
