/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * Backward-compatible wrapper for {@link ReferenceSummary}. Use {@link ReferenceSummary} directly for new code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated Use {@link ReferenceSummary} instead.
 */
@Deprecated
public class FacetSummary extends ReferenceSummary {
	@Serial private static final long serialVersionUID = -5622027322997919409L;

	@SuppressWarnings("unchecked")
	public FacetSummary(@Nonnull Map<String, Collection<FacetGroupStatistics>> referenceStatistics) {
		super(
			(Map<String, Collection<ReferenceGroupStatistics>>) (Map<String, ?>) referenceStatistics
		);
	}

	@SuppressWarnings("unchecked")
	public FacetSummary(@Nonnull Collection<FacetGroupStatistics> referenceStatistics) {
		super(
			(Collection<ReferenceGroupStatistics>) (Collection<?>) referenceStatistics
		);
	}

	/**
	 * Returns statistics for facet group with passed referenced type.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType) {
		final ReferenceGroupStatistics result = super.getReferenceGroupStatistics(referencedEntityType);
		return result instanceof FacetGroupStatistics fgs ? fgs : null;
	}

	/**
	 * Returns statistics for facet group with passed referenced type and primary key of the group.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType, int groupId) {
		final ReferenceGroupStatistics result = super.getReferenceGroupStatistics(referencedEntityType, groupId);
		return result instanceof FacetGroupStatistics fgs ? fgs : null;
	}

	/**
	 * Returns collection of all facet statistics aggregated by their group.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	public Collection<FacetGroupStatistics> getReferenceStatistics() {
		// the underlying objects are FacetGroupStatistics instances created by the producer
		return (Collection<FacetGroupStatistics>) super.getReferenceStatistics();
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final FacetSummary that = (FacetSummary) o;
		return super.equals(that);
	}

	@Nonnull
	@Override
	public String prettyPrint() {
		return super.prettyPrint();
	}

	/**
	 * Pretty prints the facet summary using the provided renderers for groups and facets.
	 *
	 * @param groupRenderer renderer function for group statistics
	 * @param facetRenderer renderer function for facet statistics
	 * @return formatted string representation of the facet summary
	 */
	@SuppressWarnings("unchecked")
	public String prettyPrint(
		@Nonnull Function<ReferenceGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		// FacetGroupStatistics extends ReferenceGroupStatistics, so the cast is safe
		return super.prettyPrint(
			(Function<ReferenceGroupStatistics, String>) (Function<?, ?>) groupRenderer,
			facetRenderer
		);
	}

	@Override
	public String toString() {
		return super.toString().replace("Reference summary", "Facet summary");
	}

	/**
	 * Backward-compatible wrapper for {@link ReferenceGroupStatistics}.
	 * Use {@link ReferenceGroupStatistics} directly for new code.
	 *
	 * @deprecated Use {@link ReferenceSummary.ReferenceGroupStatistics} instead.
	 */
	@Deprecated
	public static class FacetGroupStatistics extends ReferenceGroupStatistics {
		@Serial private static final long serialVersionUID = 6527695818988488639L;

		/**
		 * This constructor should be used only for deserialization.
		 */
		public FacetGroupStatistics(
			@Nonnull String referenceName,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Map<Integer, FacetStatistics> facetStatistics
		) {
			super(referenceName, groupEntity, count, facetStatistics);
		}

		public FacetGroupStatistics(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Map<Integer, FacetStatistics> facetStatistics
		) {
			super(referenceSchema, groupEntity, count, facetStatistics);
		}

		public FacetGroupStatistics(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Collection<FacetStatistics> facetStatistics
		) {
			super(referenceSchema, groupEntity, count, facetStatistics);
		}
	}

}
