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

import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.PrettyPrintable;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
public class FacetSummary implements EvitaResponseExtraResult, PrettyPrintable {
	@Serial private static final long serialVersionUID = -5622027322997919409L;
	/**
	 * Contains statistics of facets aggregated into facet groups ({@link Reference#getGroup()}).
	 */
	@Getter(value = AccessLevel.NONE)
	@Setter(value = AccessLevel.NONE)
	@Nonnull
	private final Map<String, ReferenceStatistics> referenceStatistics;

	public FacetSummary(@Nonnull Map<String, Collection<FacetGroupStatistics>> referenceStatistics) {
		final Map<String, ReferenceStatistics> result = createLinkedHashMap(referenceStatistics.size());
		for (Entry<String, Collection<FacetGroupStatistics>> entry : referenceStatistics.entrySet()) {
			final FacetGroupStatistics nonGroupedStatistics = entry.getValue()
				.stream()
				.filter(it -> it.getGroupEntity() == null)
				.findFirst()
				.orElse(null);
			result.put(
				entry.getKey(),
				new ReferenceStatistics(
					nonGroupedStatistics,
					entry.getValue().stream()
						.filter(it -> it.getGroupEntity() != null)
						.collect(
							Collectors.toMap(
								it -> it.getGroupEntity().getPrimaryKey(),
								Function.identity(),
								(o, o2) -> {
									throw new GenericEvitaInternalError(
										"Unexpected duplicate facet group statistics."
									);
								},
								LinkedHashMap::new
							)
						)
				)
			);
		}
		this.referenceStatistics = Collections.unmodifiableMap(result);
	}

	public FacetSummary(@Nonnull Collection<FacetGroupStatistics> referenceStatistics) {
		this.referenceStatistics = Collections.unmodifiableMap(
			referenceStatistics
				.stream()
				.collect(
					Collectors.groupingBy(
						FacetGroupStatistics::getReferenceName
					)
				)
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> new ReferenceStatistics(
							it.getValue().stream().filter(group -> group.getGroupEntity() == null).findFirst().orElse(null),
							it.getValue().stream().filter(group -> group.getGroupEntity() != null)
								.collect(
									Collectors.toMap(
										group -> group.getGroupEntity().getPrimaryKey(),
										Function.identity(),
										(o, o2) -> {
											throw new GenericEvitaInternalError(
												"There is already facet group for reference `" + it.getKey() +
													"` with id `" + Objects.requireNonNull(o.getGroupEntity()).getPrimaryKeyOrThrowException() + "`."
											);
										},
										LinkedHashMap::new
									)
								)
						)
					)
				)
		);
	}

	/**
	 * Returns statistics for facet group with passed referenced type.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType) {
		return ofNullable(this.referenceStatistics.get(referencedEntityType))
			.map(ReferenceStatistics::nonGroupedStatistics)
			.orElse(null);
	}

	/**
	 * Returns statistics for facet group with passed referenced type and primary key of the group.
	 */
	@Nullable
	public FacetGroupStatistics getFacetGroupStatistics(@Nonnull String referencedEntityType, int groupId) {
		return ofNullable(this.referenceStatistics.get(referencedEntityType))
			.map(it -> it.getFacetGroupStatistics(groupId))
			.orElse(null);
	}

	/**
	 * Returns collection of all facet statistics aggregated by their group.
	 */
	@Nonnull
	public Collection<FacetGroupStatistics> getReferenceStatistics() {
		return this.referenceStatistics.values()
			.stream()
			.flatMap(
				it -> Stream.concat(
					it.nonGroupedStatistics() == null ? Stream.empty() : Stream.of(it.nonGroupedStatistics()),
					it.groupedStatistics().values().stream()
				)
			)
			.toList();
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.referenceStatistics);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FacetSummary that = (FacetSummary) o;

		for (Entry<String, ReferenceStatistics> referenceEntry : this.referenceStatistics.entrySet()) {
			final ReferenceStatistics statistics = referenceEntry.getValue();
			final ReferenceStatistics thatStatistics = that.referenceStatistics.get(referenceEntry.getKey());

			if (!statistics.equals(thatStatistics)) {
				return false;
			}
		}
		return true;
	}

	@Nonnull
	@Override
	public String prettyPrint() {
		final PrettyPrintingContext context = new PrettyPrintingContext();
		return prettyPrint(
			statistics -> ofNullable(statistics.getGroupEntity())
				.filter(SealedEntity.class::isInstance)
				.map(SealedEntity.class::cast)
				.map(it -> printRepresentative(it, context))
				.orElse(""),
			facetStatistics -> ofNullable(facetStatistics.getFacetEntity())
				.filter(SealedEntity.class::isInstance)
				.map(SealedEntity.class::cast)
				.map(it -> printRepresentative(it, context))
				.orElse("")
		);
	}

	@Override
	public String toString() {
		return "Facet summary with: " + this.referenceStatistics.size() + " references";
	}

	/**
	 * Prints a {@link SealedEntity} in a convenient way.
	 * @param entity Entity to print.
	 * @return String representation of the entity.
	 */
	@Nonnull
	private static String printRepresentative(@Nonnull SealedEntity entity, @Nonnull PrettyPrintingContext context) {
		final AttributeValueSerializablePredicate attributePredicate = ((EntityDecorator) entity).getAttributePredicate();
		if (!attributePredicate.wasFetched()) {
			return "";
		}

		final Set<String> set = attributePredicate.getAttributeSet();
		if (set.isEmpty()) {
			final Set<String> representativeAttributes = context.getRepresentativeAttribute(entity.getSchema());
			return representativeAttributes.stream()
				.map(attribute -> Objects.requireNonNull(EvitaDataTypes.formatValue(entity.getAttribute(attribute))))
				.collect(Collectors.joining(", "));
		} else if (set.size() == 1) {
			return EvitaDataTypes.formatValue(entity.getAttribute(set.iterator().next()));
		} else {
			final Set<String> representativeAttributes = context.getRepresentativeAttribute(entity.getSchema());
			return set.stream()
				.filter(representativeAttributes::contains)
				.map(attribute -> Objects.requireNonNull(EvitaDataTypes.formatValue(entity.getAttribute(attribute))))
				.collect(Collectors.joining(", "));
		}
	}

	public String prettyPrint(
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		return "Facet summary:\n" +
			this.referenceStatistics
				.entrySet()
				.stream()
				.sorted(Entry.comparingByKey())
				.flatMap(
					refStats -> {
						final ReferenceStatistics refStatsValue = refStats.getValue();
						return Stream.concat(
								refStatsValue.nonGroupedStatistics() == null ?
									Stream.empty() :
									Stream.of(refStatsValue.nonGroupedStatistics()),
								refStatsValue
									.groupedStatistics()
									.values()
									.stream()
							)
							.map(statistics -> "\t" + refStats.getKey() + ": " +
								ofNullable(groupRenderer.apply(statistics)).filter(it -> !it.isBlank())
									.orElseGet(() -> ofNullable(statistics.getGroupEntity()).map(EntityClassifier::getPrimaryKey).map(Object::toString).orElse("non-grouped")) +
								" [" + statistics.getCount() + "]:\n" +
								statistics
									.getFacetStatistics()
									.stream()
									.map(facet -> "\t\t[" + (facet.isRequested() ? "X" : (ofNullable(facet.getImpact()).map(RequestImpact::hasSense).orElse(true) ? " " : "-")) + "] " +
										ofNullable(facetRenderer.apply(facet)).filter(it -> !it.isBlank()).orElseGet(() -> String.valueOf(facet.getFacetEntity().getPrimaryKey())) +
										" (" + facet.getCount() + ")" +
										ofNullable(facet.getImpact()).map(RequestImpact::toString).map(it -> " " + it).orElse(""))
									.collect(Collectors.joining("\n"))
							);
					}
				)
				.collect(Collectors.joining("\n"));
	}

	/**
	 * This DTO contains statistics for particular referenced entity - both grouped and non-grouped.
	 */
	private record ReferenceStatistics(
		@Nullable FacetGroupStatistics nonGroupedStatistics,
		@Nonnull Map<Integer, FacetGroupStatistics> groupedStatistics
	) {

		@Nullable
		public FacetGroupStatistics getFacetGroupStatistics(int groupId) {
			return this.groupedStatistics.get(groupId);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			ReferenceStatistics that = (ReferenceStatistics) o;

			if (!Objects.equals(this.nonGroupedStatistics, that.nonGroupedStatistics))
				return false;

			final Map<Integer, FacetGroupStatistics> statistics = groupedStatistics();
			final Map<Integer, FacetGroupStatistics> thatStatistics = that.groupedStatistics();
			if (statistics.size() != thatStatistics.size()) {
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

			return true;
		}

		@Override
		public int hashCode() {
			int result = this.nonGroupedStatistics != null ? this.nonGroupedStatistics.hashCode() : 0;
			result = 31 * result + this.groupedStatistics.hashCode();
			return result;
		}
	}

	/**
	 * This DTO contains information about the impact of adding respective facet into the filtering query. This
	 * would lead to expanding or shrinking the result response in certain way, that is described in this DTO.
	 * This implementation contains only the bare difference and the match count.
	 *
	 * @param difference Projected number of entities that are added or removed from result if the query is altered by adding this
	 *                   facet to filtering query in comparison to current result.
	 * @param matchCount Projected number of filtered entities if the query is altered by adding this facet to filtering query.
	 * @param hasSense   Selection has sense - TRUE if there is at least one entity still present in the result if
	 *                   the query is altered by adding this facet to filtering query. In case of OR relation between
	 *                   facets it's also true only if there is at least one entity present in the result when all other
	 *                   facets in the same group are removed and only this facet is requested.
	 */
	public record RequestImpact(int difference, int matchCount, boolean hasSense) implements Serializable {
		@Serial private static final long serialVersionUID = 8332603848272953977L;

		/**
		 * Returns either positive or negative number when the result expands or shrinks.
		 */
		@Override
		public int difference() {
			return this.difference;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof RequestImpact that)) return false;
			return difference() == that.difference() && this.matchCount() == that.matchCount() && this.hasSense() == that.hasSense();
		}

		@Override
		public int hashCode() {
			return Objects.hash(difference(), matchCount(), hasSense());
		}

		@Nonnull
		@Override
		public String toString() {
			if (this.difference > 0) {
				return "+" + this.difference;
			} else if (this.difference < 0) {
				return String.valueOf(this.difference);
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
			return Objects.hash(this.facetEntity, this.requested, this.count, this.impact);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetStatistics that = (FacetStatistics) o;
			return Objects.equals(getFacetEntity(), that.getFacetEntity()) &&
				this.requested == that.requested &&
				this.count == that.count &&
				Objects.equals(this.impact, that.impact);
		}

		@Override
		public String toString() {
			return "FacetStatistics[" +
				"facetEntity=" + this.facetEntity + ", " +
				"requested=" + this.requested + ", " +
				"count=" + this.count + ", " +
				"impact=" + this.impact + ']';
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

		/**
		 * Method checks that the group entity type matches the group specified in schema.
		 */
		private static void verifyGroupType(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable EntityClassifier groupEntity) {
			if (groupEntity != null) {
				final String schemaGroupType = ofNullable(referenceSchema.getReferencedGroupType())
					.orElse(referenceSchema.getReferencedEntityType());
				Assert.isPremiseValid(
					groupEntity.getType().equals(schemaGroupType),
					() -> "Group entity is from different collection (`" + groupEntity.getType() + "`) than the group or entity (`" + schemaGroupType + "`)."
				);
			}
		}

		/**
		 * This constructor should be used only for deserialization.
		 */
		public FacetGroupStatistics(
			@Nonnull String referenceName,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Map<Integer, FacetStatistics> facetStatistics
		) {
			this.referenceName = referenceName;
			this.groupEntity = groupEntity;
			this.count = count;
			this.facetStatistics = facetStatistics;
		}

		public FacetGroupStatistics(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable EntityClassifier groupEntity,
			int count,
			@Nonnull Map<Integer, FacetStatistics> facetStatistics
		) {
			verifyGroupType(referenceSchema, groupEntity);
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
			verifyGroupType(referenceSchema, groupEntity);
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
							throw new GenericEvitaInternalError("Statistics are expected to be unique!");
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
			return this.facetStatistics.get(facetId);
		}

		/**
		 * Returns collection of all facet statistics in this group.
		 */
		@Nonnull
		public Collection<FacetStatistics> getFacetStatistics() {
			return Collections.unmodifiableCollection(this.facetStatistics.values());
		}

		@Override
		public int hashCode() {
			return Objects.hash(
				this.referenceName,
				ofNullable(this.groupEntity)
					.map(EntityClassifier::getPrimaryKey)
					.orElse(null),
				this.count,
				this.facetStatistics
			);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final FacetGroupStatistics that = (FacetGroupStatistics) o;
			if (!this.referenceName.equals(that.referenceName) ||
				this.count != that.count ||
				!Objects.equals(this.groupEntity, that.getGroupEntity()) ||
				this.facetStatistics.size() != that.facetStatistics.size()) {
				return false;
			}

			final Iterator<Entry<Integer, FacetStatistics>> it = this.facetStatistics.entrySet().iterator();
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

	/**
	 * Context used by {@link #prettyPrint()} methods.
	 */
	private final static class PrettyPrintingContext {
		/**
		 * Contains set of representative attribute names for each entity type.
		 */
		private final Map<String, Set<String>> representativeAttributes = new HashMap<>();

		/**
		 * Returns set of {@link EntityAttributeSchemaContract#isRepresentative()} names for passed entity schema.
		 * @param entitySchema Entity schema to get representative attributes for.
		 * @return Set of representative attribute names.
		 */
		@Nonnull
		public Set<String> getRepresentativeAttribute(@Nonnull EntitySchemaContract entitySchema) {
			return this.representativeAttributes.computeIfAbsent(
				entitySchema.getName(),
				entityType -> entitySchema
					.getAttributes()
					.values()
					.stream()
					.filter(EntityAttributeSchemaContract::isRepresentative)
					.map(NamedSchemaContract::getName)
					.collect(Collectors.toCollection(LinkedHashSet::new))
			);
		}
	}

}
