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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;

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
		this.facetGroupStatistics = createHashMap(facetGroupStatistics.size());
		for (FacetGroupStatistics stat : facetGroupStatistics) {
			final Integer groupId = Optional.ofNullable(stat.getGroupEntity())
				.map(EntityClassifier::getPrimaryKey)
				.orElse(null);
			final Map<Integer, FacetGroupStatistics> groupById = this.facetGroupStatistics.computeIfAbsent(
				stat.getReferenceName(),
				s -> createHashMap(facetGroupStatistics.size())
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
		return Optional.ofNullable(facetGroupStatistics.get(referencedEntityType))
			.map(it -> it.get(null))
			.orElse(null);
	}

	/**
	 * Returns statistics for facet group with passed referenced type and primary key of the group.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType, int groupId) {
		return Optional.ofNullable(facetGroupStatistics.get(referencedEntityType))
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
		return facetGroupStatistics.equals(that.facetGroupStatistics);
	}

	@Override
	public String toString() {
		return "Facet summary:\n" +
			facetGroupStatistics
				.entrySet()
				.stream()
				.sorted(Entry.comparingByKey())
				.flatMap(groupsByReferenceName ->
					groupsByReferenceName.getValue()
						.entrySet()
						.stream()
						.sorted(Entry.comparingByKey())
						.map(groupById ->
							"\t" + groupsByReferenceName.getKey() + (groupById.getKey() == null ? "" : " " + groupById.getKey()) + ":\n" +
								groupById.getValue()
									.getFacetStatistics()
									.stream()
									.sorted()
									.map(facet -> "\t\t[" + (facet.requested() ? "X" : " ") + "] " + facet.facetEntity().getPrimaryKey() +
										" (" + facet.count() + ")" +
										Optional.ofNullable(facet.impact()).map(RequestImpact::toString).map(it -> " " + it).orElse(""))
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
	 *
	 * @param facetEntity contains entity (or reference to it) representing the facet
	 * @param requested Contains TRUE if the facet was part of the query filtering constraints.
	 * @param count     Contains number of distinct entities in the response that possess of this reference.
	 * @param impact    This field is not null only when this facet is not requested - {@link #requested ()} is FALSE.
	 *                  Contains projected impact on the current response if this facet is also requested in filtering constraints.
	 */
	public record FacetStatistics(
		@Nonnull EntityClassifier facetEntity,
		boolean requested,
		int count,
		@Nullable RequestImpact impact
	) implements Comparable<FacetStatistics>, Serializable {

		@Serial private static final long serialVersionUID = -575288624429566680L;

		@Override
		public int compareTo(FacetStatistics o) {
			//noinspection ConstantConditions
			return Integer.compare(facetEntity().getPrimaryKey(), o.facetEntity().getPrimaryKey());
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetStatistics that = (FacetStatistics) o;
			return Objects.equals(facetEntity(), that.facetEntity()) &&
				requested == that.requested &&
				count == that.count &&
				Objects.equals(impact, that.impact);
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
		 * Contains statistics of individual facets.
		 */
		@Getter(value = AccessLevel.NONE)
		@Setter(value = AccessLevel.NONE)
		@Nonnull
		private final Map<Integer, FacetStatistics> facetStatistics;

		public FacetGroupStatistics(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable EntityClassifier groupEntity, @Nonnull Map<Integer, FacetStatistics> facetStatistics) {
			if (groupEntity != null) {
				Assert.isPremiseValid(
					groupEntity.getType().equals(Optional.ofNullable(referenceSchema.getReferencedGroupType()).orElse(referenceSchema.getReferencedEntityType())),
					"Group entity is from different collection than the group or entity."
				);
			}
			this.referenceName = referenceSchema.getName();
			this.groupEntity = groupEntity;
			this.facetStatistics = facetStatistics;
		}

		public FacetGroupStatistics(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable EntityClassifier groupEntity, @Nonnull Collection<FacetStatistics> facetStatistics) {
			if (groupEntity != null) {
				Assert.isPremiseValid(
					groupEntity.getType().equals(Optional.ofNullable(referenceSchema.getReferencedGroupType()).orElse(referenceSchema.getReferencedEntityType())),
					"Group entity is from different collection than the group or entity."
				);
			}
			this.referenceName = referenceSchema.getName();
			this.groupEntity = groupEntity;
			this.facetStatistics = facetStatistics
				.stream()
				.collect(
					Collectors.toMap(
						it -> it.facetEntity().getPrimaryKey(),
						Function.identity(),
						(facetStatistics1, facetStatistics2) -> {
							throw new EvitaInternalError("Statistics are expected to be unique!");
						}
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
				Optional.ofNullable(groupEntity)
					.map(EntityClassifier::getPrimaryKey)
					.orElse(null),
				facetStatistics
			);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetGroupStatistics that = (FacetGroupStatistics) o;
			return referenceName.equals(that.referenceName) &&
				Objects.equals(groupEntity, that.getGroupEntity()) &&
				facetStatistics.equals(that.facetStatistics);
		}
	}

}
