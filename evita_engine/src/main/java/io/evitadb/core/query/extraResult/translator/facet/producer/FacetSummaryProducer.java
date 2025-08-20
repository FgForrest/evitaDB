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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
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
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.collection.IntegerIntoBitmapCollector;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
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
	private static final TriFunction<QueryExecutionContext, String, int[], EntityClassifier[]> ENTITY_REFERENCE_CONVERTER =
		(context, entityType, facetIds) -> Arrays.stream(facetIds).mapToObj(it -> new EntityReference(entityType, it)).toArray(EntityClassifier[]::new);

	/**
	 * Filter formula produces all entity ids that are going to be returned by current query (including user-defined
	 * filter).
	 */
	private final Formula filterFormula;
	/**
	 * Filter formula produces all entity ids that are going to be returned by current query (excluding user-defined
	 * filter).
	 */
	private final Formula filterFormulaWithoutUserFilter;
	/**
	 * Contains references to all {@link FacetIndex#getFacetingEntities()} that were involved in query resolution.
	 */
	private final List<Map<String, FacetReferenceIndex>> facetIndexes;
	/**
	 * Contains index of all requested {@link FacetHaving()} facets in the input query grouped by their
	 * {@link FacetHaving#getReferenceName()}.
	 */
	private final Map<String, Bitmap> requestedFacets;
	/**
	 * Contains the facet summary configuration set specifically for facets of certain reference.
	 * The {@link ReferenceSchema#getName()} is used as a key of this map.
	 */
	@Nonnull
	private final Map<String, FacetSummaryRequest> facetSummaryRequests = createLinkedHashMap(16);
	/**
	 * Contains a default settings for facet summary construction and entity fetching.
	 */
	@Nullable
	private DefaultFacetSummaryRequest defaultRequest;

	/**
	 * Returns a function that allows to fetch {@link EntityClassifier} for passed `entityType` and multiple `facetIds`
	 * that represents primary keys of the group entity. The form and richness of the returned {@link EntityClassifier}
	 * is controlled by the passed `entityFetch` argument.
	 */
	@Nonnull
	private static <T extends EntityFetchRequire> TriFunction<QueryExecutionContext, String, int[], EntityClassifier[]> createFetcherFunction(
		@Nonnull QueryExecutionContext executionContext,
		@Nullable T entityFetch
	) {
		if (entityFetch == null) {
			return ENTITY_REFERENCE_CONVERTER;
		} else {
			final T enrichedEntityFetch = executionContext.enrichEntityFetch(entityFetch);
			return (context, entityType, facetIds) ->
				context.fetchEntities(entityType, facetIds, enrichedEntityFetch).toArray(EntityClassifier[]::new);
		}
	}

	public FacetSummaryProducer(
		@Nonnull Formula filterFormula,
		@Nonnull Formula filterFormulaWithoutUserFilter,
		@Nonnull List<Map<String, FacetReferenceIndex>> facetIndexes,
		@Nonnull Map<String, Bitmap> requestedFacets
	) {
		this.filterFormula = filterFormula;
		this.filterFormulaWithoutUserFilter = filterFormulaWithoutUserFilter;
		this.facetIndexes = facetIndexes;
		this.requestedFacets = requestedFacets;
	}

	/**
	 * Registers default settings for facet summary in terms of entity richness (both group and facet) and also
	 * a default type of statistics depth. These settings will be used for all facet references that are not explicitly
	 * configured by {@link #requireReferenceFacetSummary(ReferenceSchemaContract, FacetStatisticsDepth, IntPredicate, IntPredicate, NestedContextSorter, NestedContextSorter, EntityFetch, EntityGroupFetch)}.
	 */
	public void requireDefaultFacetSummary(
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> facetPredicate,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> groupPredicate,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> facetSorter,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.defaultRequest = new DefaultFacetSummaryRequest(
			facetPredicate, groupPredicate,
			facetSorter, groupSorter,
			facetEntityRequirement,
			groupEntityRequirement,
			facetStatisticsDepth
		);
	}

	/**
	 * Registers specific settings for facets of certain reference with passed `referenceName` that will
	 * extend / override the default settings set in
	 * {@link #requireDefaultFacetSummary(FacetStatisticsDepth, Function, Function, Function, Function, EntityFetch, EntityGroupFetch)},
	 * should there be any.
	 */
	public void requireReferenceFacetSummary(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FacetStatisticsDepth facetStatisticsDepth,
		@Nullable IntPredicate facetPredicate,
		@Nullable IntPredicate groupPredicate,
		@Nullable NestedContextSorter facetSorter,
		@Nullable NestedContextSorter groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		this.facetSummaryRequests.put(
			referenceSchema.getName(),
			new FacetSummaryRequest(
				this.facetSummaryRequests.size() + 1,
				referenceSchema,
				facetPredicate, groupPredicate,
				facetSorter, groupSorter,
				facetEntityRequirement,
				groupEntityRequirement,
				facetStatisticsDepth
			)
		);
	}

	@Nonnull
	@Override
	public EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
		// create facet calculators - in reaction to the requested depth level
		final MemoizingFacetCalculator universalCalculator = new MemoizingFacetCalculator(
			context, this.filterFormula, this.filterFormulaWithoutUserFilter
		);
		// fabrication is a little transformation hell
		final AtomicInteger counter = new AtomicInteger();
		return new FacetSummary(
			this.facetIndexes
				.stream()
				// we need Stream<FacetReferenceIndex>
				.flatMap(it -> it.values().stream())
				.filter(it -> this.defaultRequest != null || this.facetSummaryRequests.containsKey(it.getReferenceName()))
				.collect(
					Collectors.groupingBy(
						// group them by Facet#type
						FacetReferenceIndex::getReferenceName,
						// reduce and transform data from indexes to FacetGroupStatistics
						Collectors.mapping(
							Function.identity(),
							new FacetGroupStatisticsCollector(
								context,
								// translates Facet#type to EntitySchema#reference#groupType
								referenceName -> context.getSchema().getReferenceOrThrowException(referenceName),
								referenceSchema -> ofNullable(this.facetSummaryRequests.get(referenceSchema.getName()))
									.map(referenceRequest -> {
										if (this.defaultRequest == null) {
											return referenceRequest;
										}
										final EntityFetch combinedFacetEntityRequirement = ofNullable(referenceRequest.facetEntityRequirement())
											.map(it -> it.combineWith(this.defaultRequest.facetEntityRequirement()))
											.orElse(this.defaultRequest.facetEntityRequirement());
										final EntityGroupFetch combinedGroupEntityRequirement = ofNullable(referenceRequest.groupEntityRequirement())
											.map(it -> it.combineWith(this.defaultRequest.groupEntityRequirement()))
											.orElse(this.defaultRequest.groupEntityRequirement());
										return new FacetSummaryRequest(
											referenceRequest.order(),
											referenceRequest.referenceSchema(),
											ofNullable(referenceRequest.facetPredicate())
												.orElseGet(
													() -> ofNullable(this.defaultRequest.facetPredicate())
														.map(refPredicate -> refPredicate.apply(referenceRequest.referenceSchema()))
														.orElse(null)
												),
											ofNullable(referenceRequest.groupPredicate())
												.orElseGet(
													() -> ofNullable(this.defaultRequest.groupPredicate())
														.map(refPredicate -> refPredicate.apply(referenceRequest.referenceSchema()))
														.orElse(null)
												),
											ofNullable(referenceRequest.facetSorter())
												.orElseGet(
													() -> ofNullable(this.defaultRequest.facetSorter())
														.map(refPredicate -> refPredicate.apply(referenceRequest.referenceSchema()))
														.orElse(null)
												),
											ofNullable(referenceRequest.groupSorter())
												.orElseGet(
													() -> ofNullable(this.defaultRequest.groupSorter())
														.map(refPredicate -> refPredicate.apply(referenceRequest.referenceSchema()))
														.orElse(null)
												),
											combinedFacetEntityRequirement,
											combinedGroupEntityRequirement,
											referenceRequest.facetStatisticsDepth()
										);
									})
									.orElseGet(
										() -> {
											final Optional<DefaultFacetSummaryRequest> defaultRequestOpt = ofNullable(this.defaultRequest);
											final EntityFetch facetEntityRequirement = defaultRequestOpt
												.map(DefaultFacetSummaryRequest::facetEntityRequirement)
												.orElse(null);
											final EntityGroupFetch groupEntityRequirement = defaultRequestOpt
												.map(DefaultFacetSummaryRequest::groupEntityRequirement)
												.orElse(null);
											return new FacetSummaryRequest(
												this.facetSummaryRequests.size() + counter.incrementAndGet(),
												referenceSchema,
												defaultRequestOpt
													.map(DefaultFacetSummaryRequest::facetPredicate)
													.map(refPredicate -> refPredicate.apply(referenceSchema))
													.orElse(null),
												defaultRequestOpt
													.map(DefaultFacetSummaryRequest::groupPredicate)
													.map(refPredicate -> refPredicate.apply(referenceSchema))
													.orElse(null),
												defaultRequestOpt
													.map(DefaultFacetSummaryRequest::facetSorter)
													.map(refPredicate -> refPredicate.apply(referenceSchema))
													.orElse(null),
												defaultRequestOpt
													.map(DefaultFacetSummaryRequest::groupSorter)
													.map(refPredicate -> refPredicate.apply(referenceSchema))
													.orElse(null),
												facetEntityRequirement,
												groupEntityRequirement,
												defaultRequestOpt
													.map(DefaultFacetSummaryRequest::facetStatisticsDepth)
													.orElse(FacetStatisticsDepth.COUNTS)
											);
										}),
								this.requestedFacets,
								universalCalculator,
								universalCalculator
							)
						)
					)
				)
		);
	}

	@Nonnull
	@Override
	public String getDescription() {
		if (this.facetSummaryRequests.size() == 1) {
			return "facet summary for `" + this.facetSummaryRequests.keySet().iterator().next() + "` references";
		} else {
			return "facet summary for " + this.facetSummaryRequests.keySet().stream().map(it -> '`' + it + '`').collect(Collectors.joining(" ,")) + " references";
		}
	}

	/**
	 * Collector translates data from {@link FacetReferenceIndex} to {@link FacetGroupStatistics}.
	 */
	@RequiredArgsConstructor
	private static class FacetGroupStatisticsCollector implements Collector<FacetReferenceIndex, LinkedHashMap<Integer, GroupAccumulator>, Collection<FacetGroupStatistics>> {
		/**
		 * The query execution context to provide access to the schema and other necessary data.
		 */
		private final QueryExecutionContext context;
		/**
		 * Translates {@link FacetHaving#getReferenceName()} to {@link EntitySchema#getReference(String)}.
		 */
		private final Function<String, ReferenceSchemaContract> referenceSchemaLocator;
		/**
		 * Function allowing to locate the appropriate {@link FacetSummaryRequest} for facets of particular
		 * {@link ReferenceSchema#getName()}.
		 */
		private final Function<ReferenceSchemaContract, FacetSummaryRequest> referenceRequestLocator;
		/**
		 * Contains for each {@link FacetHaving#getType()} set of requested facets.
		 */
		private final Map<String, Bitmap> requestedFacets;
		/**
		 * Facet calculator computes the entity count that relate to each facet.
		 */
		private final FacetCalculator countCalculator;
		/**
		 * Impact calculator computes the potential entity count returned should the facet be selected as well.
		 */
		private final ImpactCalculator impactCalculator;

		/**
		 * Method returns an index of fetched {@link EntityClassifier facet groups} indexed by their primary key
		 * for passed {@link GroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchGroups(
			@Nonnull QueryExecutionContext context,
			@Nonnull Entry<String, List<GroupAccumulator>> entry
		) {
			final int[] groupIds = entry.getValue()
				.stream()
				.map(GroupAccumulator::getGroupId)
				.filter(Objects::nonNull)
				.mapToInt(it -> it)
				.toArray();
			if (ArrayUtils.isEmpty(groupIds)) {
				return Collections.emptyMap();
			} else {
				final GroupAccumulator groupAcc = entry.getValue()
					.stream()
					.findFirst()
					.orElseThrow(() -> new GenericEvitaInternalError(ERROR_SANITY_CHECK));
				return Arrays.stream(
						groupAcc.getFacetSummaryRequest()
							.getGroupEntityFetcher(context, groupAcc.getReferenceSchema())
							.apply(groupIds)
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
		 * for passed {@link GroupAccumulator} entry.
		 */
		@Nonnull
		private static Map<Integer, EntityClassifier> fetchFacetEntities(
			@Nonnull QueryExecutionContext context,
			@Nonnull Entry<String, List<GroupAccumulator>> entry
		) {
			final int[] facetIds = entry.getValue()
				.stream()
				.map(GroupAccumulator::getFacetStatistics)
				.map(Map::values)
				.flatMap(Collection::stream)
				.mapToInt(FacetAccumulator::getFacetId)
				.distinct()
				.toArray();

			if (ArrayUtils.isEmpty(facetIds)) {
				return Collections.emptyMap();
			} else {
				final GroupAccumulator groupAcc = entry.getValue()
					.stream()
					.findFirst()
					.orElseThrow(() -> new GenericEvitaInternalError(ERROR_SANITY_CHECK));
				return Arrays.stream(
						groupAcc.getFacetSummaryRequest()
							.getFacetEntityFetcher(context, groupAcc.getReferenceSchema())
							.apply(facetIds)
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
		 * Returns group entity object from the `groupEntitiesIndex` in case the referenced group is managed.
		 */
		@Nullable
		private static EntityClassifier getGroupEntity(
			@Nonnull GroupAccumulator groupAcc,
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull Map<Integer, EntityClassifier> groupEntitiesIndex
		) {
			if (groupAcc.getGroupId() == null) {
				return null;
			} else if (referenceSchema.isReferencedGroupTypeManaged()) {
				return ofNullable(groupEntitiesIndex.get(groupAcc.getGroupId()))
					.orElseGet(() -> new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupAcc.getGroupId()));
			} else {
				return new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupAcc.getGroupId());
			}
		}

		/**
		 * Method collects all group ids in the {@link Bitmap} containers and returns them indexed by
		 * {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Bitmap> getGroupIdsByReferenceName(@Nonnull Map<Integer, GroupAccumulator> entityAcc) {
			return entityAcc.values()
				.stream()
				.filter(it -> it.getGroupId() != null)
				.collect(
					Collectors.groupingBy(
						it -> it.getReferenceSchema().getName(),
						Collectors.mapping(GroupAccumulator::getGroupId, IntegerIntoBitmapCollector.INSTANCE)
					)
				);
		}

		/**
		 * Method fetches facet entity bodies if requested (otherwise simple {@link EntityReference} is used) and
		 * indexes them by their {@link EntityClassifier#getPrimaryKey()} in the maps that are then returned in
		 * index where the key is  {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Map<Integer, EntityClassifier>> getFacetEntitiesIndexedByReferenceName(
			@Nonnull QueryExecutionContext context,
			@Nonnull Collection<GroupAccumulator> entityAcc
		) {
			return entityAcc
				.stream()
				.collect(
					Collectors.groupingBy(it -> it.getReferenceSchema().getName())
				)
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> FacetGroupStatisticsCollector.fetchFacetEntities(context, it)
					)
				);
		}

		/**
		 * Method fetches facet group entity bodies if requested (otherwise simple {@link EntityReference} is used) and
		 * indexes them by their {@link EntityClassifier#getPrimaryKey()} in the maps that are then returned in
		 * index where the key is  {@link ReferenceSchemaContract#getName()}.
		 */
		@Nonnull
		private static Map<String, Map<Integer, EntityClassifier>> getGroupEntitiesIndexedByReferenceName(
			@Nonnull QueryExecutionContext context,
			@Nonnull Collection<GroupAccumulator> accumulators
		) {
			return accumulators
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
						it -> FacetGroupStatisticsCollector.fetchGroups(context, it)
					)
				);
		}

		/**
		 * This method takes a map of facet statistics and a nested context sorter, and returns an array of sorted facet primary keys.
		 *
		 * @param theFacetStatistics map of facet statistics, where the key is the facet primary key and the value is the facet accumulator
		 * @param sorter             nested context sorter used for sorting the facets
		 * @return array of sorted facet primary keys
		 */
		@Nonnull
		private static int[] getSortedFacets(@Nonnull Map<Integer, FacetAccumulator> theFacetStatistics, @Nonnull NestedContextSorter sorter) {
			// if the sorter is defined, sort them
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// collect all entity primary keys
			theFacetStatistics.keySet().forEach(writer::add);
			// create sorted array using the sorter
			return sorter.sortAndSlice(new BaseBitmap(writer.get()));
		}

		/**
		 * Compares two {@link GroupAccumulator} objects based on their facet group summaries.
		 * The comparison logic is as follows:
		 * 1. If the reference schema of o1 and o2 are different, compare based on the order of their facet summary requests.
		 * 2. If the facet summary request of o1 has a group sorter defined, the facet group summaries are sorted using the sorter.
		 * The sorted group summaries are then used to determine the order of o1 and o2 based on their group ids.
		 * 3. If the facet summary request of o2 has a group sorter defined, the facet group summaries are sorted using the sorter.
		 * The sorted group summaries are then used to determine the order of o1 and o2 based on their group ids.
		 * 4. If neither o1 or o2 have a group sorter defined and o1's group id is null, o1 is considered greater than o2.
		 * 5. If neither o1 or o2 have a group sorter defined and o2's group id is null, o1 is considered less than o2.
		 * 6. If neither o1 or o2 have a group sorter defined and both o1 and o2 have group ids, compare based on their group ids.
		 *
		 * @param groupIdIndex   the index of facet groups by reference name
		 * @param sortedGroupIds the sorted group ids by reference name
		 * @param o1             the first GroupAccumulator object to compare
		 * @param o2             the second GroupAccumulator object to compare
		 * @return a negative integer, zero, or a positive integer as o1 is less than, equal to, or greater than o2
		 */
		private static int compareFacetGroupSummaries(@Nonnull Map<String, Bitmap> groupIdIndex, @Nonnull Map<String, int[]> sortedGroupIds, @Nonnull GroupAccumulator o1, @Nonnull GroupAccumulator o2) {
			if (o1.getReferenceSchema() != o2.getReferenceSchema()) {
				return Integer.compare(o1.getFacetSummaryRequest().order(), o2.getFacetSummaryRequest().order());
			} else if (o1.getFacetSummaryRequest().groupSorter() != null) {
				final NestedContextSorter sorter = o1.getFacetSummaryRequest().groupSorter();
				// create sorted array using the sorter
				final String referenceName = o1.getFacetSummaryRequest().referenceSchema().getName();
				final int[] sortedEntities = sortedGroupIds.computeIfAbsent(
					referenceName,
					theReferenceName -> sorter.sortAndSlice(groupIdIndex.get(theReferenceName))
				);
				return Integer.compare(
					ArrayUtils.indexOf(Objects.requireNonNull(o1.getGroupId()), sortedEntities),
					ArrayUtils.indexOf(Objects.requireNonNull(o2.getGroupId()), sortedEntities)
				);
			} else {
				if (o1.getGroupId() == null) {
					return 1;
				} else if (o2.getGroupId() == null) {
					return -1;
				} else {
					return Integer.compare(o1.getGroupId(), o2.getGroupId());
				}
			}
		}

		/**
		 * Returns TRUE if facet with `facetId` of specified `referenceName` was requested by the user.
		 */
		public boolean isRequested(@Nonnull String referenceName, int facetId) {
			return ofNullable(this.requestedFacets.get(referenceName))
				.map(it -> it.contains(facetId))
				.orElse(false);
		}

		@Override
		public Supplier<LinkedHashMap<Integer, GroupAccumulator>> supplier() {
			return LinkedHashMap::new;
		}

		@Override
		public BiConsumer<LinkedHashMap<Integer, GroupAccumulator>, FacetReferenceIndex> accumulator() {
			return (acc, facetEntityTypeIndex) -> {
				final ReferenceSchemaContract referenceSchema = this.referenceSchemaLocator.apply(
					facetEntityTypeIndex.getReferenceName()
				);
				final FacetSummaryRequest facetSummaryRequest = this.referenceRequestLocator.apply(referenceSchema);

				final Stream<FacetGroupIndex> groupIndexesAsStream = ofNullable(facetSummaryRequest.groupPredicate())
					.map(
						predicate -> facetEntityTypeIndex.getFacetGroupIndexesAsStream()
							.filter(groupIx -> ofNullable(groupIx.getGroupId()).map(predicate::test).orElse(false))
					)
					.orElseGet(facetEntityTypeIndex::getFacetGroupIndexesAsStream);

				groupIndexesAsStream
					.forEach(groupIx -> {
						final String referenceName = facetEntityTypeIndex.getReferenceName();
						// get or create separate accumulator for the group statistics
						final GroupAccumulator groupAcc = acc.computeIfAbsent(
							groupIx.getGroupId(),
							gId -> new GroupAccumulator(
								referenceSchema,
								facetSummaryRequest,
								gId,
								this.countCalculator,
								this.impactCalculator
							)
						);
						// create fct that can resolve whether the facet is requested for this entity type
						final IntFunction<Boolean> isRequestedResolver = facetId -> isRequested(referenceName, facetId);
						// now go through all facets in the index and register their statistics
						final Stream<FacetIdIndex> facetIndexStream = groupIx.getFacetIdIndexes()
							.values()
							.stream();
						ofNullable(facetSummaryRequest.facetPredicate())
							.ifPresentOrElse(
								predicate ->
									facetIndexStream
										.filter(facetIx -> predicate.test(facetIx.getFacetId()))
										.forEach(facetIx -> groupAcc.addStatistics(facetIx, isRequestedResolver)),
								() ->
									facetIndexStream
										.forEach(facetIx -> groupAcc.addStatistics(facetIx, isRequestedResolver))
							);
					});
			};
		}

		@Override
		public BinaryOperator<LinkedHashMap<Integer, GroupAccumulator>> combiner() {
			return (left, right) -> {
				// combine two HashMap<Integer, GroupAccumulator> together, right one is fully merged into left
				right.forEach((key, value) -> left.merge(key, value, GroupAccumulator::combine));
				return left;
			};
		}

		@Override
		public Function<LinkedHashMap<Integer, GroupAccumulator>, Collection<FacetGroupStatistics>> finisher() {
			return entityAcc -> {
				final Map<String, Map<Integer, EntityClassifier>> groupEntities = getGroupEntitiesIndexedByReferenceName(this.context, entityAcc.values());
				final Map<String, Map<Integer, EntityClassifier>> facetEntities = getFacetEntitiesIndexedByReferenceName(this.context, entityAcc.values());
				final Map<String, Bitmap> groupIdIndex = getGroupIdsByReferenceName(entityAcc);

				final Map<String, int[]> sortedGroupIds = new HashMap<>(groupIdIndex.size());
				final Stream<GroupAccumulator> groupStream = entityAcc
					.values()
					.stream()
					.sorted((o1, o2) -> compareFacetGroupSummaries(groupIdIndex, sortedGroupIds, o1, o2));

				return groupStream
					.map(groupAcc -> {
						final Map<Integer, FacetAccumulator> theFacetStatistics = groupAcc.getFacetStatistics();
						if (theFacetStatistics.isEmpty()) {
							return null;
						} else {
							final ReferenceSchemaContract referenceSchema = groupAcc.getReferenceSchema();
							// compute overall count for group
							final Formula entityMatchingAnyOfGroupFacetFormula = this.countCalculator.createGroupCountFormula(
								referenceSchema, groupAcc.getGroupId(),
								groupAcc.getFacetStatistics()
									.values()
									.stream()
									.flatMap(it -> it.getFacetEntityIds().stream())
									.toArray(Bitmap[]::new)
							);
							final int entityMatchingAnyOfGroupFacet = entityMatchingAnyOfGroupFacetFormula.compute().size();
							if (entityMatchingAnyOfGroupFacet == 0) {
								return null;
							}

							// collect all facet statistics
							final Map<Integer, EntityClassifier> facetEntitiesIndex = Objects.requireNonNull(facetEntities.get(referenceSchema.getName()));
							final Stream<FacetAccumulator> facetStream = ofNullable(groupAcc.getFacetSummaryRequest().facetSorter())
								.map(
									sorter -> Arrays.stream(getSortedFacets(theFacetStatistics, sorter))
										.mapToObj(theFacetStatistics::get)
								)
								.orElseGet(
									() -> theFacetStatistics.values()
										.stream()
										.sorted(Comparator.comparingInt(FacetAccumulator::getFacetId))
								);

							final Map<Integer, FacetStatistics> facetStatistics = facetStream
								.filter(Objects::nonNull)
								// exclude those that has no results after base formula application
								.filter(FacetAccumulator::hasAnyResults)
								.map(
									it -> ofNullable(facetEntitiesIndex.get(it.getFacetId()))
										.map(it::toFacetStatistics)
										.orElse(null)
								)
								.filter(Objects::nonNull)
								.collect(
									Collectors.toMap(
										it -> it.getFacetEntity().getPrimaryKey(),
										Function.identity(),
										(o, o2) -> {
											throw new IllegalStateException("Unexpectedly found two facets in stream!");
										},
										// we need to maintain the order in map
										LinkedHashMap::new
									)
								);

							// create facet group statistics
							final Map<Integer, EntityClassifier> groupEntitiesIndex = groupEntities.get(referenceSchema.getName());
							final EntityClassifier groupEntity = getGroupEntity(groupAcc, referenceSchema, groupEntitiesIndex);

							return new FacetGroupStatistics(
								// translate Facet#type to EntitySchema#reference#groupType
								referenceSchema,
								groupEntity,
								entityMatchingAnyOfGroupFacet,
								facetStatistics
							);
						}
					})
					.filter(Objects::nonNull)
					.filter(it -> !it.getFacetStatistics().isEmpty())
					.collect(Collectors.toList());
			};
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
	private static class GroupAccumulator {
		/**
		 * Contains {@link ReferenceSchema} related to {@link FacetHaving#getReferenceName()}.
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
		private final Map<Integer, FacetAccumulator> facetStatistics = createLinkedHashMap(32);

		public GroupAccumulator(
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
			this.impactCalculator = facetSummaryRequest.facetStatisticsDepth() == FacetStatisticsDepth.COUNTS ?
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
						this.referenceSchema,
						fId,
						this.groupId,
						requestedResolver.apply(fId),
						facetIx.getRecords(),
						this.countCalculator,
						this.impactCalculator
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
		 * Combines two GroupAccumulator together. It adds everything from the `otherAccumulator` to self
		 * instance and returns self.
		 */
		public GroupAccumulator combine(GroupAccumulator otherAccumulator) {
			Assert.isPremiseValid(this.referenceSchema.equals(otherAccumulator.referenceSchema), ERROR_SANITY_CHECK);
			Assert.isPremiseValid(Objects.equals(this.groupId, otherAccumulator.groupId), ERROR_SANITY_CHECK);
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
		private static final Formula[] EMPTY_INT_FORMULA = Formula.EMPTY_FORMULA_ARRAY;
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
				this.requested,
				getCount(),
				this.impactCalculator.calculateImpact(
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
			Assert.isPremiseValid(this.facetId == otherAccumulator.facetId, ERROR_SANITY_CHECK);
			Assert.isPremiseValid(this.requested == otherAccumulator.requested, ERROR_SANITY_CHECK);
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
			if (this.resultFormula == null) {
				// we need to combine all collected facet formulas and then AND them with base formula to get rid
				// of entity primary keys that haven't passed the filter logic
				this.resultFormula = this.countCalculator.createCountFormula(
					this.referenceSchema, this.facetId, this.facetGroupId,
					this.facetEntityIds.toArray(EMPTY_BITMAP)
				);
			}
			// this is the most expensive call in this very class
			return this.resultFormula.compute().size();
		}
	}

	/**
	 * Record captures the facet summary requirements.
	 */
	@RequiredArgsConstructor
	private static class FacetSummaryRequest {
		private final int order;
		private final @Nonnull ReferenceSchemaContract referenceSchema;
		private final @Nullable IntPredicate facetPredicate;
		private final @Nullable IntPredicate groupPredicate;
		private final @Nullable NestedContextSorter facetSorter;
		private final @Nullable NestedContextSorter groupSorter;
		private final @Nullable EntityFetch facetEntityRequirement;
		private final @Nullable EntityGroupFetch groupEntityRequirement;
		private final @Nonnull FacetStatisticsDepth facetStatisticsDepth;
		private Function<int[], EntityClassifier[]> entityFetcherFunction;
		private Function<int[], EntityClassifier[]> entityGroupFetcherFunction;

		public int order() {
			return this.order;
		}

		@Nonnull
		public ReferenceSchemaContract referenceSchema() {
			return this.referenceSchema;
		}

		@Nullable
		public IntPredicate facetPredicate() {
			return this.facetPredicate;
		}

		@Nullable
		public IntPredicate groupPredicate() {
			return this.groupPredicate;
		}

		@Nullable
		public NestedContextSorter facetSorter() {
			return this.facetSorter;
		}

		@Nullable
		public NestedContextSorter groupSorter() {
			return this.groupSorter;
		}

		@Nullable
		public EntityFetch facetEntityRequirement() {
			return this.facetEntityRequirement;
		}

		@Nullable
		public EntityGroupFetch groupEntityRequirement() {
			return this.groupEntityRequirement;
		}

		@Nonnull
		public FacetStatisticsDepth facetStatisticsDepth() {
			return this.facetStatisticsDepth;
		}

		/**
		 * Returns a function that fetches an array of {@link EntityClassifier} instances based on the provided facet IDs.
		 * The function is initialized depending on whether the referenced entity type in the schema is managed.
		 *
		 * @param context the {@link QueryExecutionContext} containing the execution context for queries
		 * @param referenceSchema the {@link ReferenceSchemaContract} defining the schema for the referenced entity
		 * @return a {@link Function} that maps an array of facet IDs to an array of {@link EntityClassifier}
		 */
		@Nonnull
		public Function<int[], EntityClassifier[]> getFacetEntityFetcher(
			@Nonnull QueryExecutionContext context,
			@Nonnull ReferenceSchemaContract referenceSchema
		) {
			if (this.entityFetcherFunction == null) {
				this.entityFetcherFunction = referenceSchema.isReferencedEntityTypeManaged() ?
					facetIds -> createFetcherFunction(context, this.facetEntityRequirement).apply(context, referenceSchema.getReferencedEntityType(), facetIds) :
					facetIds -> ENTITY_REFERENCE_CONVERTER.apply(context, referenceSchema.getReferencedEntityType(), facetIds);
			}
			return this.entityFetcherFunction;
		}

		/**
		 * Returns a function that can fetch a group of {@link EntityClassifier} instances based on the provided group IDs.
		 * The function is initialized based on whether the referenced group type in the provided schema is managed.
		 *
		 * @param context the {@link QueryExecutionContext} containing the execution context for queries.
		 * @param referenceSchema the {@link ReferenceSchemaContract} that defines the schema for the referenced group.
		 * @return a {@link Function} that maps an array of group IDs to an array of {@link EntityClassifier}.
		 */
		@Nonnull
		public Function<int[], EntityClassifier[]> getGroupEntityFetcher(
			@Nonnull QueryExecutionContext context,
			@Nonnull ReferenceSchemaContract referenceSchema
		) {
			if (this.entityGroupFetcherFunction == null) {
				this.entityGroupFetcherFunction = referenceSchema.isReferencedGroupTypeManaged() ?
					groupIds -> createFetcherFunction(context, this.groupEntityRequirement).apply(context, Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupIds) :
					groupIds -> ENTITY_REFERENCE_CONVERTER.apply(context, Objects.requireNonNull(referenceSchema.getReferencedGroupType()), groupIds);
			}
			return this.entityGroupFetcherFunction;
		}

	}

	/**
	 * Record captures the facet summary requirements.
	 *
	 * @param facetStatisticsDepth Contains {@link io.evitadb.api.query.require.FacetSummary#getStatisticsDepth()} information.
	 */
	private record DefaultFacetSummaryRequest(
		@Nullable Function<ReferenceSchemaContract, IntPredicate> facetPredicate,
		@Nullable Function<ReferenceSchemaContract, IntPredicate> groupPredicate,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> facetSorter,
		@Nullable Function<ReferenceSchemaContract, NestedContextSorter> groupSorter,
		@Nullable EntityFetch facetEntityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nonnull FacetStatisticsDepth facetStatisticsDepth
	) {

	}

}
