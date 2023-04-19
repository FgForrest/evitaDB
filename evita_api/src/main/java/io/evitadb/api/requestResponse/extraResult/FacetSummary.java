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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static java.util.Optional.ofNullable;

/**
 * This DTO allows returning summary of all facets that match query filter excluding those inside {@link UserFilter}.
 * DTO contains information about facet groups and individual facets in them as well as appropriate statistics for them.
 *
 * Instance of this class is returned in {@link EvitaResponse#getExtraResult(Class)} when
 * {@link io.evitadb.api.query.require.FacetSummary} require query is used in the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class FacetSummary implements EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = -5622027322997919409L;
	/**
	 * Contains statistics of facets aggregated into facet groups ({@link Reference#getGroup()}).
	 */
	@Getter(value = AccessLevel.NONE)
	@Setter(value = AccessLevel.NONE)
	@Nonnull
	private final Map<String, Map<Integer, FacetGroupStatistics>> facetGroupStatistics;

	public FacetSummary(@Nonnull Collection<FacetGroupStatistics> facetGroupStatistics) {
		this.facetGroupStatistics = createLinkedHashMap(facetGroupStatistics.size());
		for (FacetGroupStatistics stat : facetGroupStatistics) {
			final Integer groupId = ofNullable(stat.getGroupEntity())
				.map(EntityClassifier::getPrimaryKey)
				.orElse(null);
			final Map<Integer, FacetGroupStatistics> groupById = this.facetGroupStatistics.computeIfAbsent(
				stat.getReferenceName(),
				s -> createLinkedHashMap(facetGroupStatistics.size())
			);
			Assert.isPremiseValid(
				!groupById.containsKey(groupId),
				"There is already facet group for reference `" + stat.getReferenceName() + "` with id `" + groupId + "`."
			);
			groupById.put(groupId, stat);
		}
	}

	/**
	 * Returns statistics for facet group with passed referenced type.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType) {
		return ofNullable(facetGroupStatistics.get(referencedEntityType))
			.map(it -> it.get(null))
			.orElse(null);
	}

	/**
	 * Returns statistics for facet group with passed referenced type and primary key of the group.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType, int groupId) {
		return ofNullable(facetGroupStatistics.get(referencedEntityType))
			.map(it -> it.get(groupId))
			.orElse(null);
	}

	/**
	 * Returns collection of all facet statistics aggregated by their group.
	 */
	@Nonnull
	public Collection<FacetGroupStatistics> getFacetGroupStatistics() {
		return facetGroupStatistics.values()
			.stream()
			.flatMap(it -> it.values().stream())
			.toList();
	}

	@Override
	public int hashCode() {
		return Objects.hash(facetGroupStatistics);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FacetSummary that = (FacetSummary) o;

		for (Entry<String, Map<Integer, FacetGroupStatistics>> referenceEntry : facetGroupStatistics.entrySet()) {
			final Map<Integer, FacetGroupStatistics> statistics = referenceEntry.getValue();
			final Map<Integer, FacetGroupStatistics> thatStatistics = that.facetGroupStatistics.get(referenceEntry.getKey());
			if (thatStatistics == null || statistics.size() != thatStatistics.size()) {
				return false;
			} else {
				final Iterator<Entry<Integer, FacetGroupStatistics>> it = statistics.entrySet().iterator();
				final Iterator<Entry<Integer, FacetGroupStatistics>> thatIt = thatStatistics.entrySet().iterator();
				while (it.hasNext()) {
					final Entry<Integer, FacetGroupStatistics> entry = it.next();
					final Entry<Integer, FacetGroupStatistics> thatEntry = thatIt.next();
					if (!Objects.equals(entry.getKey(), thatEntry.getKey()) || !Objects.equals(entry.getValue(), thatEntry.getValue())) {
						return false;
					}
				}
			}
		}
		return facetGroupStatistics.equals(that.facetGroupStatistics);
	}

	@Override
	public String toString() {
		return toString(
			statistics -> "",
			facetStatistics -> ""
		);
	}

	public String toString(
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		return "Facet summary:\n" +
			facetGroupStatistics
				.entrySet()
				.stream()
				.sorted(Entry.comparingByKey())
				.flatMap(groupsByReferenceName ->
					groupsByReferenceName.getValue()
						.values()
						.stream()
						.map(statistics -> "\t" + ofNullable(groupRenderer.apply(statistics)).filter(it -> !it.isBlank()).orElse(groupsByReferenceName.getKey()) +
							" [" + statistics.getCount() + "]:\n" +
							statistics
								.getFacetStatistics()
								.stream()
								.map(facet -> "\t\t[" + (facet.isRequested() ? "X" : " ") + "] " +
									ofNullable(facetRenderer.apply(facet)).filter(it -> !it.isBlank()).orElseGet(() -> String.valueOf(facet.getFacetEntity().getPrimaryKey())) +
									" (" + facet.getCount() + ")" +
									ofNullable(facet.getImpact()).map(RequestImpact::toString).map(it -> " " + it).orElse(""))
								.collect(Collectors.joining("\n"))
						)
				)
				.collect(Collectors.joining("\n"));
	}

	/**
	 * This DTO contains information about the impact of adding respective facet into the filtering query. This
	 * would lead to expanding or shrinking the result response in certain way, that is described in this DTO.
	 * This implementation contains only the bare difference and the match count.
	 *
	 * @param difference Projected number of entities that are added or removed from result if the query is altered by adding this
	 *                   facet to filtering query in comparison to current result.
	 * @param matchCount Projected number of filtered entities if the query is altered by adding this facet to filtering query.
	 */
	public record RequestImpact(int difference, int matchCount) implements Serializable {
		@Serial private static final long serialVersionUID = 8332603848272953977L;

		/**
		 * Returns either positive or negative number when the result expands or shrinks.
		 */
		@Override
		public int difference() {
			return difference;
		}

		/**
		 * Selection has sense - TRUE if there is at least one entity still present in the result if the query is
		 * altered by adding this facet to filtering query.
		 */
		public boolean hasSense() {
			return matchCount > 0;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof RequestImpact that)) return false;
			return difference() == that.difference() && matchCount() == that.matchCount();
		}

		@Override
		public int hashCode() {
			return Objects.hash(difference(), matchCount());
		}

		@Override
		public String toString() {
			if (difference > 0) {
				return "+" + difference;
			} else if (difference < 0) {
				return String.valueOf(difference);
			} else {
				return "0";
			}
		}

	}

	/**
	 * This DTO contains information about single facet statistics of the entities that are present in the response.
	 */
	@SuppressWarnings("ClassCanBeRecord")
	public static final class FacetStatistics implements Comparable<FacetStatistics>, Serializable {
		@Serial private static final long serialVersionUID = -575288624429566680L;
		/**
		 * Contains entity (or reference to it) representing the facet.
		 */
		@Getter @Nonnull private final EntityClassifier facetEntity;
		/**
		 * Contains TRUE if the facet was part of the query filtering constraints.
		 */
		@Getter private final boolean requested;
		/**
		 * Contains number of distinct entities in the response that possess of this reference.
		 */
		@Getter private final int count;
		/**
		 * This field is not null only when this facet is not requested - {@link #requested ()} is FALSE.
		 * Contains projected impact on the current response if this facet is also requested in filtering constraints.
		 */
		@Getter @Nullable private final RequestImpact impact;

		public FacetStatistics(
			@Nonnull EntityClassifier facetEntity,
			boolean requested,
			int count,
			@Nullable RequestImpact impact
		) {
			this.facetEntity = facetEntity;
			this.requested = requested;
			this.count = count;
			this.impact = impact;
		}

		@Override
		public int compareTo(FacetStatistics o) {
			//noinspection ConstantConditions
			return Integer.compare(getFacetEntity().getPrimaryKey(), o.getFacetEntity().getPrimaryKey());
		}

		@Override
		public int hashCode() {
			return Objects.hash(facetEntity, requested, count, impact);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetStatistics that = (FacetStatistics) o;
			return Objects.equals(getFacetEntity(), that.getFacetEntity()) &&
				requested == that.requested &&
				count == that.count &&
				Objects.equals(impact, that.impact);
		}

		@Override
		public String toString() {
			return "FacetStatistics[" +
				"facetEntity=" + facetEntity + ", " +
				"requested=" + requested + ", " +
				"count=" + count + ", " +
				"impact=" + impact + ']';
		}

	}

	/**
	 * This DTO contains information about single facet group and statistics of the facets that relates to it.
	 */
	@Data
	public static class FacetGroupStatistics implements Serializable {
		@Serial private static final long serialVersionUID = 6527695818988488638L;
		/**
		 * Contains reference name of the facet. This type relates to {@link Reference#getReferenceName()}.
		 */
		@Nonnull
		private final String referenceName;
		/**
		 * Contains entity representing this group.
		 */
		@Nullable
		private final EntityClassifier groupEntity;
		/**
		 * Contains number of distinct entities in the response that possess any reference in this group.
		 */
		@Getter
		private final int count;
		/**
		 * Contains statistics of individual facets.
		 */
		@Getter(value = AccessLevel.NONE)
		@Setter(value = AccessLevel.NONE)
		@Nonnull
		private final Map<Integer, FacetStatistics> facetStatistics;

		public FacetGroupStatistics(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Map<Integer, FacetStatistics> facetStatistics
		) {
			if (groupEntity != null) {
				Assert.isPremiseValid(
					groupEntity.getType().equals(ofNullable(referenceSchema.getReferencedGroupType()).orElse(referenceSchema.getReferencedEntityType())),
					"Group entity is from different collection than the group or entity."
				);
			}
			this.referenceName = referenceSchema.getName();
			this.groupEntity = groupEntity;
			this.count = count;
			this.facetStatistics = facetStatistics;
		}

		public FacetGroupStatistics(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Collection<FacetStatistics> facetStatistics
		) {
			if (groupEntity != null) {
				Assert.isPremiseValid(
					groupEntity.getType().equals(ofNullable(referenceSchema.getReferencedGroupType()).orElse(referenceSchema.getReferencedEntityType())),
					"Group entity is from different collection than the group or entity."
				);
			}
			this.referenceName = referenceSchema.getName();
			this.groupEntity = groupEntity;
			this.count = count;
			this.facetStatistics = facetStatistics
				.stream()
				.collect(
					Collectors.toMap(
						it -> it.getFacetEntity().getPrimaryKey(),
						Function.identity(),
						(facetStatistics1, facetStatistics2) -> {
							throw new EvitaInternalError("Statistics are expected to be unique!");
						},
						LinkedHashMap::new
					)
				);
		}

		/**
		 * Returns statistics for facet with passed primary key.
		 */
		@Nullable
		public FacetStatistics getFacetStatistics(int facetId) {
			return facetStatistics.get(facetId);
		}

		/**
		 * Returns collection of all facet statistics in this group.
		 */
		@Nonnull
		public Collection<FacetStatistics> getFacetStatistics() {
			return Collections.unmodifiableCollection(facetStatistics.values());
		}

		@Override
		public int hashCode() {
			return Objects.hash(
				referenceName,
				ofNullable(groupEntity)
					.map(EntityClassifier::getPrimaryKey)
					.orElse(null),
				count,
				facetStatistics
			);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetGroupStatistics that = (FacetGroupStatistics) o;
			if (!referenceName.equals(that.referenceName) ||
				count != that.count ||
				!Objects.equals(groupEntity, that.getGroupEntity()) ||
				facetStatistics.size() != that.facetStatistics.size()) {
				return false;
			}

			final Iterator<Entry<Integer, FacetStatistics>> it = facetStatistics.entrySet().iterator();
			final Iterator<Entry<Integer, FacetStatistics>> thatIt = that.facetStatistics.entrySet().iterator();
			while (it.hasNext()) {
				final Entry<Integer, FacetStatistics> entry = it.next();
				final Entry<Integer, FacetStatistics> thatEntry = thatIt.next();
				if (!Objects.equals(entry.getKey(), thatEntry.getKey()) || !Objects.equals(entry.getValue(), thatEntry.getValue())) {
					return false;
				}
			}

			return true;
		}
	}

}
