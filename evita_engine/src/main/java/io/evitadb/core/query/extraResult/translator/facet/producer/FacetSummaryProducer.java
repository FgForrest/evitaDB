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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.filter.FacetInSet;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * {@link FacetSummaryProducer} creates {@link FacetSummary} instance and does the heavy lifting to compute all
 * information necessary. The producer executes following logic:
 *
 * - from all gathered {@link FacetReferenceIndex} collect all facets organized into respective groups
 * - it merges all facets from all indexes by {@link OrFormula} so that it has full group-facet-entityId information
 * - each facet statistics is combined by {@link AndFormula} with {@link #filterFormula} that contains the output of the
 * filtered query excluding user-defined parts (e.g. without subtrees in {@link UserFilter} query)
 * - the result allows to list all facets that correspond to the result entities that returned as the query response
 *
 * When requested {@link RequestImpact} is computed for each facet that is not already requested and computes
 * potential the difference in the returned entities count should this facet be selected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetSummaryProducer implements ExtraResultProducer {
	private static final String ERROR_SANITY_CHECK = "Sanity check!";

	/**
	 * Default implementation of the {@link EntityClassifier} fetcher method that simply converts the data into
	 * plain {@link EntityReference} type containing only the data in the input of the function.
	 */
	private static final BiFunction<String, int[], EntityClassifier[]> ENTITY_REFERENCE_CONVERTER =
		(entityType, facetIds) -> Arrays.stream(facetIds).mapToObj(it -> new EntityReference(entityType, it)).toArray(EntityClassifier[]::new);

	/**
	 * Reference to the query context that allows to access entity bodies.
	 */
	@Nonnull private final QueryContext queryContext;
	/**
	 * Filter formula produces all entity ids that are going to be returned by current query (including user-defined
	 * filter).
	 */
	private final Formula filterFormula;
	/**
	 * Contains references to all {@link FacetIndex#getFacetingEntities()} that were involved in query resolution.
	 */
	private final List<Map<String, FacetReferenceIndex>> facetIndexes;
	/**
	 * Contains index of all requested {@link FacetInSet#getFacetIds()} in the input query grouped by their
	 * {@link FacetInSet#getReferenceName()}.
	 */
	private final Map<String, IntSet> requestedFacets;
	/**
	 * Contains the facet summary configuration set specifically for facets of certain reference.
	 * The {@link ReferenceSchema#getName()} is used as a key of this map.
	 */
	@Nonnull
	private final Map<String, FacetSummaryRequest> entityReferenceFetcher = new HashMap<>();
	/**
	 * Contains a default settings for facet summary construction and entity fetching.
	 */
	@Nullable
	private FacetSummaryRequest defaultRequest;

	public FacetSummaryProducer(
		@Nonnull QueryContext queryContext,
		@Nonnull Formula filterFormula,
		@Nonnull List<Map<String, FacetReferenceIndex>> facetIndexes,
		@Nonnull Map<String, IntSet> requestedFacets
	) {
		this.queryContext = queryContext;
		this.filterFormula = filterFormula;
		this.facetIndexes = facetIndexes;
		this.requestedFacets = requestedFacets;
	}

	/**
	 * Registers default settings for facet summary in terms of entity richness (both group and facet) and also
	 * a default type of statistics depth. These settings will be used for all facet references that are not explicitly
	 * configured by {@link #requireReferenceFacetSummary(String, FacetStatisticsDepth, EntityFetch, EntityGroupFetch)}.
	 */
	public void requireDefaultFacetSummary(
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.defaultRequest = new FacetSummaryRequest(
			facetEntityRequirement,
			groupEntityRequirement,
			createFetcherFunction(facetEntityRequirement),
			createFetcherFunction(groupEntityRequirement),
			facetStatisticsDepth
		);
	}

	/**
	 * Registers specific settings for facets of certain reference with passed `referenceName` that will
	 * extend / override the default settings set in
	 * {@link #requireDefaultFacetSummary(FacetStatisticsDepth, EntityFetch, EntityGroupFetch)}, should there be any.
	 */
	public void requireReferenceFacetSummary(
		@Nonnull String referenceName,
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.entityReferenceFetcher.put(
			referenceName,
			new FacetSummaryRequest(
				facetEntityRequirement,
				groupEntityRequirement,
				createFetcherFunction(facetEntityRequirement),
				createFetcherFunction(groupEntityRequirement),
				facetStatisticsDepth
			)
		);
	}

	@Nonnull
	@Override
	public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull List<T> entities) {
		// create facet calculators - in reaction to requested depth level
		final MemoizingFacetCalculator universalCalculator = new MemoizingFacetCalculator(queryContext, filterFormula);
		// fabrication is a little transformation hell
		return new FacetSummary(
			facetIndexes
				.stream()
				// we need Stream<FacetReferenceIndex>
				.flatMap(it -> it.values().stream())
				.filter(it -> defaultRequest != null || entityReferenceFetcher.containsKey(it.getReferenceName()))
				.collect(
					Collectors.groupingBy(
						// group them by Facet#type
						FacetReferenceIndex::getReferenceName,
						// reduce and transform data from indexes to FacetGroupStatistics
						Collectors.mapping(
							Function.identity(),
							new FacetGroupStatisticsCollector(
								// translates Facet#type to EntitySchema#reference#groupType
								referenceName -> queryContext.getSchema().getReferenceOrThrowException(referenceName),
								referenceName -> ofNullable(entityReferenceFetcher.get(referenceName))
									.map(referenceRequest -> {
										if (defaultRequest == null) {
											return referenceRequest;
										}
										final EntityFetch combinedFacetEntityRequirement = Optional.ofNullable(referenceRequest.getFacetEntityRequirement())
											.map(it -> it.combineWith(defaultRequest.getFacetEntityRequirement()))
											.orElse(defaultRequest.getFacetEntityRequirement());
										final EntityGroupFetch combinedGroupEntityRequirement = Optional.ofNullable(referenceRequest.getGroupEntityRequirement())
											.map(it -> it.combineWith(defaultRequest.getGroupEntityRequirement()))
											.orElse(defaultRequest.getGroupEntityRequirement());
										return new FacetSummaryRequest(
											combinedFacetEntityRequirement,
											combinedGroupEntityRequirement,
											createFetcherFunction(combinedFacetEntityRequirement),
											createFetcherFunction(combinedGroupEntityRequirement),
											referenceRequest.getFacetStatisticsDepth()
										);
									})
									.orElse(defaultRequest),
								requestedFacets,
								universalCalculator,
								universalCalculator
							)
						)
					)
				)
				.values()
				.stream()
				.flatMap(Collection::stream)
				.collect(Collectors.toList())
		);
	}

	/**
	 * Returns a function that allows to fetch {@link EntityClassifier} for passed `entityType` and multiple `groupIds`
	 * that represents primary keys of the group entity. The form and richness of the returned {@link EntityClassifier}
	 * is controlled by the passed `groupFetch` argument.
	 */
	private BiFunction<String, int[], EntityClassifier[]> createFetcherFunction(@Nullable EntityGroupFetch groupFetch) {
		return groupFetch == null ?
			ENTITY_REFERENCE_CONVERTER :
			(entityType, groupIds) -> queryContext.fetchEntities(entityType, groupIds, groupFetch).toArray(EntityClassifier[]::new);
	}

	/**
	 * Returns a function that allows to fetch {@link EntityClassifier} for passed `entityType` and multiple `facetIds`
	 * that represents primary keys of the group entity. The form and richness of the returned {@link EntityClassifier}
	 * is controlled by the passed `entityFetch` argument.
	 */
	private BiFunction<String, int[], EntityClassifier[]> createFetcherFunction(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ?
			ENTITY_REFERENCE_CONVERTER :
			(entityType, facetIds) -> queryContext.fetchEntities(entityType, facetIds, entityFetch).toArray(EntityClassifier[]::new);
	}

	/**
	 * Collector translates data from {@link FacetReferenceIndex} to {@link FacetGroupStatistics}.
	 */
	@RequiredArgsConstructor
	private static class FacetGroupStatisticsCollector implements Collector<FacetReferenceIndex, HashMap<Integer, EntityTypeGroupAccumulator>, Collection<FacetGroupStatistics>> {
		/**
		 * Translates {@link FacetInSet#getReferenceName()} to {@link EntitySchema#getReference(String)}.
		 */
		private final Function<String, ReferenceSchemaContract> referenceSchemaLocator;
		/**
		 * Function allowing to locate the appropriate {@link FacetSummaryRequest} for facets of particular
		 * {@link ReferenceSchema#getName()}.
		 */
		private final Function<String, FacetSummaryRequest> referenceRequestLocator;
		/**
		 * Contains for each {@link FacetInSet#getType()} set of requested facets.
		 */
		private final Map<String, IntSet> requestedFacets;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;

		/**
		 * Returns TRUE if facet with `facetId` of specified `referenceName` was requested by the user.
		 */
		public boolean isRequested(@Nonnull String referenceName, int facetId) {
			return ofNullable(requestedFacets.get(referenceName))
				.map(it -> it.contains(facetId))
				.orElse(false);
		}

		@Override
		public Supplier<HashMap<Integer, EntityTypeGroupAccumulator>> supplier() {
			return HashMap::new;
		}

		@Override
		public BiConsumer<HashMap<Integer, EntityTypeGroupAccumulator>, FacetReferenceIndex> accumulator() {
			return (acc, facetEntityTypeIndex) -> facetEntityTypeIndex
				.getFacetGroupIndexesAsStream()
				.forEach(groupIx -> {
					final String referenceName = facetEntityTypeIndex.getReferenceName();
					// get or create separate accumulator for the group statistics
					final EntityTypeGroupAccumulator groupAcc = acc.computeIfAbsent(
						groupIx.getGroupId(),
						gId -> new EntityTypeGroupAccumulator(
							referenceSchemaLocator.apply(referenceName),
							referenceRequestLocator.apply(referenceName),
							gId,
							countCalculator,
							impactCalculator
						)
					);
					// create fct that can resolve whether the facet is requested for this entity type
					final IntFunction<Boolean> isRequestedResolver = facetId -> isRequested(referenceName, facetId);
					// now go through all facets in the index and register their statistics
					groupIx.getFacetIdIndexes()
						.values()
						.forEach(facetIx ->
							groupAcc.addStatistics(facetIx, isRequestedResolver)
						);
				});
		}

		@Override
		public BinaryOperator<HashMap<Integer, EntityTypeGroupAccumulator>> combiner() {
			return (left, right) -> {
				// combine two HashMap<Integer, EntityTypeGroupAccumulator> together, right one is fully merged into left
				right.forEach((key, value) -> left.merge(key, value, EntityTypeGroupAccumulator::combine));
				return left;
			};
		}

		@Override
		public Function<HashMap<Integer, EntityTypeGroupAccumulator>, Collection<FacetGroupStatistics>> finisher() {
			return entityAcc -> {
				final Map<String, Map<Integer, EntityClassifier>> groupEntities = entityAcc
					.values()
					.stream()
					.filter(it -> it.getGroupId() != null)
					.collect(
						Collectors.groupingBy(it -> it.getReferenceSchema().getName())
					)
					.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							FacetGroupStatisticsCollector::fetchGroups
						)
					);

				final Map<String, Map<Integer, EntityClassifier>> facetEntities = entityAcc
					.values()
					.stream()
					.collect(
						Collectors.groupingBy(it -> it.getReferenceSchema().getName())
					)
					.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							FacetGroupStatisticsCollector::fetchFacetEntities
						)
					);

				return entityAcc
					.values()
					.stream()
					.map(groupAcc -> {
						if (groupAcc.getFacetStatistics().isEmpty()) {
							return null;
						} else {
							// collect all facet statistics
							final ReferenceSchemaContract referenceSchema = groupAcc.getReferenceSchema();
							final Map<Integer, EntityClassifier> facetEntitiesIndex = Objects.requireNonNull(facetEntities.get(referenceSchema.getName()));
							final Map<Integer, FacetStatistics> facetStatistics = groupAcc.getFacetStatistics()
								.entrySet()
								.stream()
								// exclude those that has no results after base formula application
								.filter(it -> it.getValue().hasAnyResults())
								.map(it -> {
									final EntityClassifier entity = facetEntitiesIndex.get(it.getKey());
									return ofNullable(entity).map(e -> it.getValue().toFacetStatistics(e)).orElse(null);
								})
								.filter(Objects::nonNull)
								.collect(
									Collectors.toMap(
										it -> it.facetEntity().getPrimaryKey(),
										Function.identity()
									)
								);
							// create facet group statistics
							final Map<Integer, EntityClassifier> groupEntitiesIndex = groupEntities.get(referenceSchema.getName());
							final EntityClassifier groupEntity;

							if (groupAcc.getGroupId() == null) {
								groupEntity = null;
							} else if (referenceSchema.isReferencedGroupTypeManaged()) {
								groupEntity = ofNullable(groupEntitiesIndex.get(groupAcc.getGroupId()))
									.orElseGet(() -> new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupAcc.getGroupId()));
							} else {
								groupEntity = new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupAcc.getGroupId());
							}

							return new FacetGroupStatistics(
								// translate Facet#type to EntitySchema#reference#groupType
								referenceSchema,
								groupEntity,
								facetStatistics
							);
						}
					})
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			};
		}

		/**
		 * Method returns an index of fetched {@link EntityClassifier facet groups} indexed by their primary key
		 * for passed {@link EntityTypeGroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchGroups(@Nonnull Entry<String, List<EntityTypeGroupAccumulator>> entry) {
			final int[] groupIds = entry.getValue()
				.stream()
				.map(EntityTypeGroupAccumulator::getGroupId)
				.filter(Objects::nonNull)
				.mapToInt(it -> it)
				.toArray();
			if (ArrayUtils.isEmpty(groupIds)) {
				return Collections.emptyMap();
			} else {
				final EntityTypeGroupAccumulator groupAcc = entry.getValue()
					.stream()
					.findFirst()
					.orElseThrow(() -> new EvitaInternalError(ERROR_SANITY_CHECK));
				return Arrays.stream(
						groupAcc.getFacetSummaryRequest()
							.getGroupEntityFetcher(groupAcc.getReferenceSchema())
							.apply(groupAcc.getReferenceSchema().getReferencedGroupType(), groupIds)
					)
					.collect(
						Collectors.toMap(
							EntityClassifier::getPrimaryKey,
							Function.identity()
						)
					);
			}
		}

		/**
		 * Method returns an index of fetched {@link EntityClassifier facets} indexed by their primary key
		 * for passed {@link EntityTypeGroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchFacetEntities(@Nonnull Entry<String, List<EntityTypeGroupAccumulator>> entry) {
			final int[] facetIds = entry.getValue()
				.stream()
				.map(EntityTypeGroupAccumulator::getFacetStatistics)
				.map(Map::values)
				.flatMap(Collection::stream)
				.mapToInt(FacetAccumulator::getFacetId)
				.distinct()
				.toArray();

			if (ArrayUtils.isEmpty(facetIds)) {
				return Collections.emptyMap();
			} else {
				final EntityTypeGroupAccumulator groupAcc = entry.getValue()
					.stream()
					.findFirst()
					.orElseThrow(() -> new EvitaInternalError(ERROR_SANITY_CHECK));
				return Arrays.stream(
						groupAcc.getFacetSummaryRequest()
							.getFacetEntityFetcher(groupAcc.getReferenceSchema())
							.apply(groupAcc.getReferenceSchema().getReferencedEntityType(), facetIds)
					)
					.collect(
						Collectors.toMap(
							EntityClassifier::getPrimaryKey,
							Function.identity()
						)
					);
			}
		}

		@Override
		public Set<Characteristics> characteristics() {
			return Set.of(Characteristics.UNORDERED);
		}
	}

	/**
	 * This mutable accumulator contains statistics for all facets of same `entityType` and `groupId`.
	 */
	@Data
	private static class EntityTypeGroupAccumulator {
		/**
		 * Contains {@link ReferenceSchema} related to {@link FacetInSet#getReferenceName()}.
		 */
		@Nonnull private final ReferenceSchemaContract referenceSchema;
		/**
		 * Contains configuration of the facet summary requirement that controls output of this accumulator.
		 */
		@Nonnull private final FacetSummaryRequest facetSummaryRequest;
		/**
		 * Contains group id of the facets in this accumulator.
		 */
		@Nullable private final Integer groupId;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;
		/**
		 * Contains statistic accumulator for each of the facet.
		 */
		private final Map<Integer, FacetAccumulator> facetStatistics = new HashMap<>();

		public EntityTypeGroupAccumulator(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull FacetSummaryRequest facetSummaryRequest,
			@Nullable Integer groupId,
			@Nonnull FacetCalculator countCalculator,
			@Nonnull ImpactCalculator impactCalculator
		) {
			this.referenceSchema = referenceSchema;
			this.facetSummaryRequest = facetSummaryRequest;
			this.groupId = groupId;
			this.countCalculator = countCalculator;
			this.impactCalculator = facetSummaryRequest.getFacetStatisticsDepth() == FacetStatisticsDepth.COUNTS ?
				ImpactCalculator.NO_IMPACT : impactCalculator;
		}

		/**
		 * Registers new {@link FacetAccumulator} statistics in the local state.
		 */
		public void addStatistics(
			@Nonnull FacetIdIndex facetIx,
			@Nonnull IntFunction<Boolean> requestedResolver
		) {
			this.facetStatistics.compute(
				facetIx.getFacetId(),
				(fId, facetAccumulator) -> {
					final FacetAccumulator newAccumulator = new FacetAccumulator(
						referenceSchema,
						fId,
						groupId,
						requestedResolver.apply(fId),
						facetIx.getRecords(),
						countCalculator,
						impactCalculator
					);
					if (facetAccumulator == null) {
						return newAccumulator;
					} else {
						return facetAccumulator.combine(newAccumulator);
					}
				}
			);
		}

		/**
		 * Combines two EntityTypeGroupAccumulator together. It adds everything from the `otherAccumulator` to self
		 * instance and returns self.
		 */
		public EntityTypeGroupAccumulator combine(EntityTypeGroupAccumulator otherAccumulator) {
			Assert.isPremiseValid(referenceSchema.equals(otherAccumulator.referenceSchema), ERROR_SANITY_CHECK);
			Assert.isPremiseValid(Objects.equals(groupId, otherAccumulator.groupId), ERROR_SANITY_CHECK);
			otherAccumulator.getFacetStatistics()
				.forEach((key, value) -> this.facetStatistics.merge(key, value, FacetAccumulator::combine));
			return this;
		}

	}

	/**
	 * This mutable accumulator contains statistics for single facet.
	 */
	@Data
	private static class FacetAccumulator {
		private static final Formula[] EMPTY_INT_FORMULA = new Formula[0];
		private static final Bitmap[] EMPTY_BITMAP = new Bitmap[0];
		/**
		 * Contains {@link ReferenceSchema}.
		 */
		private final ReferenceSchemaContract referenceSchema;
		/**
		 * Contains facet group id - primary key of {@link ReferenceSchema#getReferencedGroupType()} entity.
		 */
		private final Integer facetGroupId;
		/**
		 * Contains facetId - primary key of {@link ReferenceSchema#getReferencedEntityType()} entity.
		 */
		@Getter private final int facetId;
		/**
		 * Contains TRUE if this particular facet was requested by in the input query.
		 */
		private final boolean requested;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;
		/**
		 * Contains finished result formula so that {@link #getCount()} can be called multiple times without performance
		 * penalty.
		 */
		private Formula resultFormula;
		/**
		 * Contains bitmaps of all entity primary keys that posses this facet. All bitmaps need to be combined with OR
		 * relation in order to get full entity primary key list.
		 */
		private List<Bitmap> facetEntityIds = new LinkedList<>();

		public FacetAccumulator(
			@Nonnull ReferenceSchemaContract referenceSchema,
			int facetId,
			@Nullable Integer facetGroupId,
			boolean requested,
			@Nonnull Bitmap facetEntityIds,
			@Nonnull FacetCalculator countCalculator,
			@Nonnull ImpactCalculator impactCalculator
		) {
			this.referenceSchema = referenceSchema;
			this.facetId = facetId;
			this.facetGroupId = facetGroupId;
			this.requested = requested;
			this.countCalculator = countCalculator;
			this.impactCalculator = impactCalculator;
			this.facetEntityIds.add(facetEntityIds);
		}

		/**
		 * Produces final result of this accumulator.
		 */
		public FacetStatistics toFacetStatistics(@Nonnull EntityClassifier facetEntity) {
			return new FacetStatistics(
				facetEntity,
				requested,
				getCount(),
				impactCalculator.calculateImpact(
					this.referenceSchema, this.facetId, this.facetGroupId, this.requested,
					this.facetEntityIds.toArray(EMPTY_BITMAP)
				)
			);
		}

		/**
		 * Combines two FacetAccumulator together. It adds everything from the `otherAccumulator` to self
		 * instance and returns self.
		 */
		public FacetAccumulator combine(FacetAccumulator otherAccumulator) {
			Assert.isPremiseValid(facetId == otherAccumulator.facetId, ERROR_SANITY_CHECK);
			Assert.isPremiseValid(requested == otherAccumulator.requested, ERROR_SANITY_CHECK);
			this.facetEntityIds.addAll(otherAccumulator.getFacetEntityIds());
			return this;
		}

		/**
		 * Returns true if there is at least one entity in the query result that has this facet.
		 */
		public boolean hasAnyResults() {
			return getCount() > 0;
		}

		/**
		 * Returns count of all entities in the query response that has this facet.
		 */
		public int getCount() {
			if (resultFormula == null) {
				// we need to combine all collected facet formulas and then AND them with base formula to get rid
				// of entity primary keys that haven't passed the filter logic
				resultFormula = countCalculator.createCountFormula(
					referenceSchema, facetId, facetGroupId,
					facetEntityIds.toArray(EMPTY_BITMAP)
				);
			}
			// this is the most expensive call in this very class
			return resultFormula.compute().size();
		}
	}

	@RequiredArgsConstructor
	private static class FacetSummaryRequest {
		@Getter @Nullable private final EntityFetch facetEntityRequirement;
		@Getter @Nullable private final EntityGroupFetch groupEntityRequirement;

		private final BiFunction<String, int[], EntityClassifier[]> facetEntityFetcher;
		private final BiFunction<String, int[], EntityClassifier[]> groupEntityFetcher;

		/**
		 * Contains {@link io.evitadb.api.query.require.FacetSummary#getFacetStatisticsDepth()} information.
		 */
		@Getter private final FacetStatisticsDepth facetStatisticsDepth;

		public BiFunction<String, int[], EntityClassifier[]> getFacetEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return referenceSchema.isReferencedEntityTypeManaged() ? facetEntityFetcher : ENTITY_REFERENCE_CONVERTER;
		}

		public BiFunction<String, int[], EntityClassifier[]> getGroupEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return referenceSchema.isReferencedGroupTypeManaged() ? groupEntityFetcher : ENTITY_REFERENCE_CONVERTER;
		}

	}

}
