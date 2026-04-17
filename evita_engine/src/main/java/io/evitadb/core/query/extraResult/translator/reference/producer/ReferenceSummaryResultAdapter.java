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
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/**
 * Factory that converts the intermediate statistics map built by
 * {@link ReferenceSummaryProducer} into the concrete {@link EvitaResponseExtraResult} DTO
 * returned to the caller. Two implementations exist while the migration from the deprecated
 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} extra result to the new
 * {@link io.evitadb.api.requestResponse.extraResult.ReferenceSummary} extra result is in
 * flight:
 *
 * - {@link FacetSummaryAdapter} — wraps the map in
 *   {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} (deprecated, kept for
 *   backward compatibility while the deprecated require constraints
 *   {@link io.evitadb.api.query.require.FacetSummary} /
 *   {@link io.evitadb.api.query.require.FacetSummaryOfReference} are still supported).
 * - {@link ReferenceSummaryAdapter} — wraps the map in
 *   {@link io.evitadb.api.requestResponse.extraResult.ReferenceSummary} (new canonical
 *   form produced by the {@link io.evitadb.api.query.require.ReferenceSummary} /
 *   {@link io.evitadb.api.query.require.ReferenceSummaryOfReference} constraints).
 *
 * The producer is wired with exactly one adapter at construction time; the translators pick
 * which one based on the originating require constraint. When a request mixes both forms,
 * two producer instances are registered — one per adapter — so both DTOs appear in the
 * response.
 *
 * Each adapter also serves as a factory for {@link ReferenceGroupStatistics} instances via
 * {@link #createGroupStatistics}: the canonical adapter creates plain
 * {@link ReferenceGroupStatistics}, while the deprecated adapter creates the
 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics}
 * subtype so that the deprecated {@code FacetSummary#getFacetGroupStatistics(...)} methods
 * (which rely on {@code instanceof} checks) keep working for existing callers.
 *
 * Once the deprecated constraints and the
 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics}
 * subtype are removed, this interface and both implementations can be deleted: the factory
 * and wrapping calls will collapse into direct constructor calls inside
 * {@link ReferenceSummaryProducer#fabricate}.
 *
 * @param <T> the concrete {@link ReferenceGroupStatistics} subtype produced by this adapter
 *            — the canonical adapter pins {@code T} to {@link ReferenceGroupStatistics}
 *            itself, while the deprecated adapter pins it to
 *            {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics}
 *            so that {@link #createResult} can consume the intermediate map without an
 *            unchecked cast.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ReferenceSummaryResultAdapter<T extends ReferenceGroupStatistics> {

	/**
	 * Creates a {@link ReferenceGroupStatistics} instance (or an appropriate subtype) for
	 * a single reference group. The canonical adapter creates plain
	 * {@link ReferenceGroupStatistics}; the deprecated adapter creates the
	 * {@link io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics}
	 * subtype to preserve backward-compatible {@code instanceof} checks.
	 *
	 * @param referenceSchema the reference schema this group belongs to
	 * @param groupEntity     the entity representing this group, or {@code null} for
	 *                        non-grouped references
	 * @param count           number of distinct entities possessing any reference in this group
	 * @param facetStatistics per-facet statistics indexed by facet primary key
	 * @return the created group statistics instance (never {@code null})
	 */
	@Nonnull
	T createGroupStatistics(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable EntityClassifier groupEntity,
		int count,
		@Nonnull Map<Integer, FacetStatistics> facetStatistics
	);

	/**
	 * Wraps the intermediate statistics map produced by {@link ReferenceSummaryProducer}
	 * into the concrete extra-result DTO for this adapter form.
	 *
	 * @param statisticsByReferenceName per-reference group statistics; keys are reference
	 *                                  names, values are collections of group statistics as
	 *                                  computed by the producer
	 * @return the wrapped extra-result DTO (never {@code null})
	 */
	@Nonnull
	EvitaResponseExtraResult createResult(
		@Nonnull Map<String, Collection<T>> statisticsByReferenceName
	);

}
