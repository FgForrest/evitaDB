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

package io.evitadb.api.functional.facet;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.dataType.Predecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This test verifies whether entities can be filtered by facets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
// TOBEDONE: deprecated - remove all facetSummary tests when FacetSummary constraint is removed (https://github.com/FgForrest/evitaDB/issues/538)
@Slf4j
@Tag(CONTRACT)
@Tag(FACET)
@Tag(FILTER)
public abstract class AbstractEntityByFacetFilteringFunctionalTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	private static final String THOUSAND_PRODUCTS_WITH_FACETS = "ThousandsProductsWithFacets";
	private static final String ATTRIBUTE_TRANSIENT = "transient";
	private static final int SEED = 40;
	private static final String EMPTY_COLLECTION_ENTITY = "someCollectionWithoutEntities";
	private final static int[] STORE_ORDER;
	private static final int STORE_COUNT = 12;
	/**
	 * A small hand-made data set exercising the facet relation settings on reference shapes the generated data set does
	 * not have. Products of {@link #ENTITY_SHAPED_PRODUCT} carry three faceted references:
	 *
	 * - {@link #REF_LABEL} - to labels of type {@link #ENTITY_LABEL}, grouped by {@link #ENTITY_LABEL_GROUP}, a type
	 *   not managed by evitaDB. Labels 1 and 2 belong to group {@link #LABEL_GROUP_A}, label 3 to group
	 *   {@link #LABEL_GROUP_B}, labels 4 and 5 belong to no group.
	 * - {@link #REF_TAG} - to tags of type {@link #ENTITY_TAG}, grouped by the managed type {@link #ENTITY_TAG_GROUP},
	 *   whose attribute {@link #ATTRIBUTE_NOTE} is not filterable. Tag {@link #GROUPED_TAG} belongs to group
	 *   {@link #TAG_GROUP}, tag {@link #UNGROUPED_TAG} belongs to no group. Each tag carries the filterable attribute
	 *   {@link #ATTRIBUTE_CODE} - `tag1` and `tag2`.
	 * - {@link #REF_SOURCE} - to sources of type {@link #ENTITY_SOURCE}, a type not managed by evitaDB, without any
	 *   group. Each reference carries the filterable reference attribute {@link #ATTRIBUTE_CHANNEL}, whose value is
	 *   decided by the source - see {@link #SOURCE_CHANNELS}.
	 *
	 * The labels and tags themselves are managed entities, so that the options can be selected in `facetHaving`
	 * by anything, while the sources can be selected only by what the products themselves store about them.
	 *
	 * | product | labels | tags | sources |
	 * |---------|--------|------|---------|
	 * | 1       | 1      | 1    | 1       |
	 * | 2       | 1, 3   | 2    | 2       |
	 * | 3       | 2      |      | 1       |
	 * | 4       | 3      | 1, 2 |         |
	 * | 5       | 4      |      | 2       |
	 * | 6       | 1, 4   | 1    | 1, 2    |
	 * | 7       | 5      | 2    |         |
	 * | 8       | 3, 4   |      | 1       |
	 * | 9       |        | 1    | 2       |
	 * | 10      | 2, 5   |      |         |
	 * | 11      | 1, 4   | 2    |         |
	 * | 12      |        |      | 1       |
	 */
	private static final String FACET_RELATION_SHAPES = "FacetRelationShapes";
	private static final String ENTITY_SHAPED_PRODUCT = "shapedProduct";
	private static final String ENTITY_LABEL = "shapedLabel";
	private static final String ENTITY_LABEL_GROUP = "externalLabelGroup";
	private static final String ENTITY_TAG = "shapedTag";
	private static final String ENTITY_TAG_GROUP = "tagGroup";
	private static final String REF_LABEL = "label";
	private static final String REF_TAG = "tag";
	private static final String ENTITY_SOURCE = "externalSource";
	private static final String REF_SOURCE = "source";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_CHANNEL = "channel";
	private static final int LABEL_GROUP_A = 10;
	private static final int LABEL_GROUP_B = 20;
	/**
	 * A label group no label belongs to.
	 */
	private static final int MISSING_LABEL_GROUP = 30;
	/**
	 * The group of each label, indexed by the label primary key minus one; NULL for a label without a group.
	 */
	private static final Integer[] LABEL_GROUPS = {LABEL_GROUP_A, LABEL_GROUP_A, LABEL_GROUP_B, null, null};
	private static final int TAG_GROUP = 1;
	private static final int GROUPED_TAG = 1;
	private static final int UNGROUPED_TAG = 2;
	/**
	 * A tag no product references.
	 */
	private static final int MISSING_TAG = 3;
	/**
	 * The labels of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_LABELS = {
		{1}, {1, 3}, {2}, {3}, {4}, {1, 4}, {5}, {3, 4}, {}, {2, 5}, {1, 4}, {}
	};
	/**
	 * The tags of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_TAGS = {
		{GROUPED_TAG}, {UNGROUPED_TAG}, {}, {GROUPED_TAG, UNGROUPED_TAG}, {}, {GROUPED_TAG}, {UNGROUPED_TAG}, {},
		{GROUPED_TAG}, {}, {UNGROUPED_TAG}, {}
	};
	/**
	 * The sources of each product of {@link #FACET_RELATION_SHAPES}, indexed by the product primary key minus one.
	 */
	private static final int[][] SHAPED_PRODUCT_SOURCES = {
		{1}, {2}, {1}, {}, {2}, {1, 2}, {}, {1}, {2}, {}, {}, {1}
	};
	/**
	 * The {@link #ATTRIBUTE_CHANNEL} every reference to a source carries, indexed by the source primary key minus one.
	 */
	private static final String[] SOURCE_CHANNELS = {"web", "shop"};
	/**
	 * A source no product references.
	 */
	private static final int MISSING_SOURCE = 3;

	static {
		STORE_ORDER = new int[STORE_COUNT];
		for (int i = 1; i <= STORE_COUNT; i++) {
			STORE_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), STORE_ORDER, STORE_COUNT);
	}

	/**
	 * Encapsulates all parameters needed to compute a facet summary. Uses a builder pattern
	 * to replace the previous telescoping overloads of `computeFacetSummary` with named,
	 * readable parameter assignments.
	 *
	 * Required fields are passed via the builder constructor; optional fields have setter methods.
	 *
	 * @param session the evitaDB session to use for entity lookups
	 * @param schema the entity schema describing references and facets
	 * @param entities the full list of product entities to compute facets from
	 * @param query the query containing filter/require constraints
	 * @param statisticsDepthSupplier function returning the statistics depth per reference name
	 * @param parameterGroupMapping mapping from parameter facet ID to its group ID
	 * @param entityFilter optional predicate to pre-filter entities (e.g. by price availability)
	 * @param referencePredicate optional predicate to filter individual references
	 * @param facetSorterFactory optional factory for facet sorting comparators per reference name
	 * @param facetGroupSorterFactory optional factory for facet group sorting comparators per reference name
	 * @param allowedReferenceNames optional supplier restricting which reference types appear in the summary
	 * @param facetEntityRequirementSupplier optional function providing entity fetch requirements for facet entities
	 * @param groupEntityRequirementSupplier optional function providing entity fetch requirements for group entities
	 * @param selectedFacetProvider optional function providing pre-selected facet IDs per reference name
	 * @param selectedEntitiesPredicate optional predicate for additional entity filtering (e.g. user filter criteria)
	 */
	private record FacetSummaryComputationParams(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nonnull Query query,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nullable Predicate<ReferenceContract> referencePredicate,
		@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nullable Function<String, int[]> selectedFacetProvider,
		@Nullable Predicate<SealedEntity> selectedEntitiesPredicate
	) {

		/**
		 * Builder for constructing [FacetSummaryComputationParams] instances. Required parameters
		 * are passed in the constructor; optional parameters are set via fluent setter methods.
		 */
		static class Builder {
			private final EvitaSessionContract session;
			private final EntitySchemaContract schema;
			private final List<SealedEntity> entities;
			private final Query query;
			private final Function<String, FacetStatisticsDepth> statisticsDepthSupplier;
			private final Map<Integer, Integer> parameterGroupMapping;
			private Predicate<SealedEntity> entityFilter;
			private Predicate<ReferenceContract> referencePredicate;
			private Function<String, Comparator<FacetStatistics>> facetSorterFactory;
			private Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory;
			private Supplier<Set<String>> allowedReferenceNames;
			private Function<String, EntityFetch> facetEntityRequirementSupplier;
			private Function<String, EntityGroupFetch> groupEntityRequirementSupplier;
			private Function<String, int[]> selectedFacetProvider;
			private Predicate<SealedEntity> selectedEntitiesPredicate;

			Builder(
				@Nonnull EvitaSessionContract session,
				@Nonnull EntitySchemaContract schema,
				@Nonnull List<SealedEntity> entities,
				@Nonnull Query query,
				@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
				@Nonnull Map<Integer, Integer> parameterGroupMapping
			) {
				this.session = session;
				this.schema = schema;
				this.entities = entities;
				this.query = query;
				this.statisticsDepthSupplier = statisticsDepthSupplier;
				this.parameterGroupMapping = parameterGroupMapping;
			}

			@Nonnull
			Builder entityFilter(@Nullable Predicate<SealedEntity> entityFilter) {
				this.entityFilter = entityFilter;
				return this;
			}

			@Nonnull
			Builder referencePredicate(@Nullable Predicate<ReferenceContract> referencePredicate) {
				this.referencePredicate = referencePredicate;
				return this;
			}

			@Nonnull
			Builder facetSorterFactory(@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory) {
				this.facetSorterFactory = facetSorterFactory;
				return this;
			}

			@Nonnull
			Builder facetGroupSorterFactory(
					@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory
			) {
				this.facetGroupSorterFactory = facetGroupSorterFactory;
				return this;
			}

			@Nonnull
			Builder allowedReferenceNames(@Nullable Supplier<Set<String>> allowedReferenceNames) {
				this.allowedReferenceNames = allowedReferenceNames;
				return this;
			}

			@Nonnull
			Builder facetEntityRequirementSupplier(@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier) {
				this.facetEntityRequirementSupplier = facetEntityRequirementSupplier;
				return this;
			}

			@Nonnull
			Builder groupEntityRequirementSupplier(@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier) {
				this.groupEntityRequirementSupplier = groupEntityRequirementSupplier;
				return this;
			}

			@Nonnull
			Builder selectedFacetProvider(@Nullable Function<String, int[]> selectedFacetProvider) {
				this.selectedFacetProvider = selectedFacetProvider;
				return this;
			}

			@Nonnull
			Builder selectedEntitiesPredicate(@Nullable Predicate<SealedEntity> selectedEntitiesPredicate) {
				this.selectedEntitiesPredicate = selectedEntitiesPredicate;
				return this;
			}

			@Nonnull
			FacetSummaryComputationParams build() {
				return new FacetSummaryComputationParams(
					this.session, this.schema, this.entities, this.query,
					this.statisticsDepthSupplier, this.parameterGroupMapping,
					this.entityFilter, this.referencePredicate,
					this.facetSorterFactory, this.facetGroupSorterFactory,
					this.allowedReferenceNames,
					this.facetEntityRequirementSupplier, this.groupEntityRequirementSupplier,
					this.selectedFacetProvider, this.selectedEntitiesPredicate
				);
			}
		}
	}

	/**
	 * Computes a facet summary by streaming through entities and grouping their references
	 * into facet group statistics. Supports filtering, sorting, impact computation, and
	 * entity/group enrichment based on the provided parameters.
	 *
	 * @param params the computation parameters encapsulating all inputs
	 * @return a [FacetSummaryWithResultCount] containing the computed facet summary and
	 *         the count of entities matching the facet filter
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull FacetSummaryComputationParams params
	) {
		// this context allows us to create facet filtering predicates in correct way
		final FacetComputationalContext fcc = new FacetComputationalContext(
			params.schema(), params.query(), params.parameterGroupMapping(), params.selectedFacetProvider()
		);

		// filter entities by mandatory predicate
		final List<SealedEntity> filteredEntities = ofNullable(params.entityFilter())
			.map(it -> params.entities().stream().filter(it))
			.orElseGet(params.entities()::stream)
			.toList();

		// collect set of faceted reference types
		final Set<String> facetedEntities = params.schema().getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.map(ReferenceSchemaContract::getReferencedEntityType)
			.collect(Collectors.toSet());

		// group facets by their entity type / group
		final Predicate<ReferenceContract> referencePredicate = params.referencePredicate();
		final Map<GroupReference, Map<ReferenceKey, Integer>> groupedFacets = filteredEntities
			.stream()
			.flatMap(it -> it.getReferences().stream())
			// filter out references by provided predicate
			.filter(it -> referencePredicate == null || referencePredicate.test(it))
			// filter out not faceted entity types
			.filter(it -> facetedEntities.contains(it.getReferenceName()))
			.collect(
				groupingBy(
					// create referenced entity type + referenced entity group id key
					it -> new GroupReference(
						params.schema().getReference(it.getReferenceName()).orElseThrow(),
						it.getGroup().map(EntityReferenceContract::getPrimaryKey).orElse(null)
					),
					TreeMap::new,
					// compute facet count
					groupingBy(
						ReferenceContract::getReferenceKey,
						() -> new TreeMap<>(ReferenceKey.GENERIC_COMPARATOR),
						summingInt(facet -> 1)
					)
				)
			);

		// group facets by their entity type / group and compute sum per group
		final Map<GroupReference, Integer> groupCount = filteredEntities
			.stream()
			.flatMap(entity -> entity.getReferences()
				.stream()
				// filter out references by provided predicate
				.filter(it -> referencePredicate == null || referencePredicate.test(it))
				.map(it ->
					new GroupReferenceWithEntityId(
						it.getReferenceName(),
						it.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(null),
						entity.getPrimaryKeyOrThrowException()
					)
				))
			.distinct()
			.collect(
				groupingBy(
					entry -> new GroupReference(
						params.schema().getReference(entry.referenceName()).orElseThrow(),
						entry.groupId()
					),
					TreeMap::new,
					summingInt(entry -> 1)
				)
			);


		// filter entities by facets in input query (even if part of user filter) - use AND for different entity types, and
		// OR for facet ids
		final List<SealedEntity> filteredEntitiesIncludingUserFilter =
			params.selectedEntitiesPredicate() == null ?
				filteredEntities.stream().toList() :
				filteredEntities.stream().filter(params.selectedEntitiesPredicate()).toList();
		final Set<Integer> facetFilteredEntityIds = filteredEntitiesIncludingUserFilter
			.stream()
			.filter(fcc.createBaseFacetPredicate())
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		// if there facet group negation - invert the facet counts
		if (fcc.isAnyFacetGroupNegated(WITH_DIFFERENT_FACETS_IN_GROUP)) {
			groupedFacets.entrySet()
				.stream()
				.filter(it -> fcc.isFacetGroupNegated(it.getKey(), WITH_DIFFERENT_FACETS_IN_GROUP))
				.forEach(it ->
					// invert the results
					it.getValue()
						.entrySet()
						.forEach(facetCount -> facetCount.setValue(filteredEntitiesIncludingUserFilter.size() - facetCount.getValue()))
				);
		}

		final Map<String, Comparator<FacetStatistics>> cachedComparators = new HashMap<>();
		final Map<String, Comparator<FacetGroupStatistics>> cachedGroupComparators = new HashMap<>();

		return new FacetSummaryWithResultCount(
			facetFilteredEntityIds.size(),
			new FacetSummary(
				groupedFacets
					.entrySet()
					.stream()
					.filter(grouped -> Optional.ofNullable(params.allowedReferenceNames())
						.map(it -> it.get().contains(grouped.getKey().referenceSchema().getName()))
						.orElse(true))
					.map(it -> {
							final ReferenceSchemaContract referenceSchema = it.getKey().referenceSchema();
							return new FacetGroupStatistics(
								referenceSchema,
								ofNullable(it.getKey().groupId())
									.map(gId -> {
										final String entityType = Objects.requireNonNull(referenceSchema.getReferencedGroupType());
										final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(params.groupEntityRequirementSupplier())
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (groupEntityRequirement == null) {
											return new EntityReference(entityType, gId);
										}
										return params.session().getEntity(entityType, gId, groupEntityRequirement.getRequirements()).orElseThrow();
									})
									.orElse(null),
								groupCount.get(it.getKey()),
								it.getValue()
									.entrySet()
									.stream()
									.map(facet -> {
										// compute whether facet was part of input filter by
										final boolean requested = fcc.wasFacetRequested(facet.getKey());

										// fetch facet entity
										final EntityClassifier facetEntity;
										final String facetEntityType = referenceSchema.getReferencedEntityType();
										final int facetPrimaryKey = facet.getKey().primaryKey();
										final EntityFetch facetEntityRequirement = Optional.ofNullable(params.facetEntityRequirementSupplier())
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (facetEntityRequirement == null) {
											facetEntity = new EntityReference(facetEntityType, facetPrimaryKey);
										} else {
											facetEntity = params.session().getEntity(
													facetEntityType, facetPrimaryKey, facetEntityRequirement.getRequirements()
												).orElseThrow();
										}

										final FacetStatisticsDepth statisticsDepth = Optional.ofNullable(
											params.statisticsDepthSupplier().apply(referenceSchema.getName())
										).orElseThrow();

										// create facet statistics
										return new FacetStatistics(
											facetEntity,
											requested,
											facet.getValue(),
											statisticsDepth == FacetStatisticsDepth.IMPACT ?
												computeImpact(filteredEntitiesIncludingUserFilter, facetFilteredEntityIds, facet.getKey(), fcc) : null
										);
									})
									.sorted((o1, o2) -> compareFacet(
										referenceSchema.getName(), params.facetSorterFactory(), cachedComparators, o1, o2
									))
									.collect(toList())
							);
						}
					)
					.sorted((o1, o2) -> compareFacetGroup(params.facetGroupSorterFactory(), cachedGroupComparators, o1, o2))
					.collect(toList())
			)
		);
	}

	private static int compareFacet(
		@Nonnull String referenceName,
		@Nonnull Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nonnull Map<String, Comparator<FacetStatistics>> cachedGroupComparators,
		@Nonnull FacetStatistics o1,
		@Nonnull FacetStatistics o2
	) {
		final Comparator<FacetStatistics> comparator = cachedGroupComparators.computeIfAbsent(
			referenceName,
			theReferenceName -> ofNullable(facetSorterFactory)
				.map(it -> it.apply(theReferenceName))
				.orElseGet(() -> Comparator.comparing((FacetStatistics o12) -> o12.getFacetEntity().getPrimaryKey()))
		);
		return comparator.compare(o1, o2);
	}

	private static int compareFacetGroup(
		@Nonnull Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nonnull Map<String, Comparator<FacetGroupStatistics>> cachedGroupComparators,
		@Nonnull FacetGroupStatistics o1,
		@Nonnull FacetGroupStatistics o2
	) {
		final int referenceCmp = o1.getReferenceName().compareTo(o2.getReferenceName());
		if (referenceCmp == 0 && (o1.getGroupEntity() != null || o2.getGroupEntity() != null)) {
			if (o1.getGroupEntity() == null) {
				return 1;
			} else if (o2.getGroupEntity() == null) {
				return -1;
			} else {
				final Comparator<FacetGroupStatistics> comparator = cachedGroupComparators.computeIfAbsent(
					o1.getReferenceName(),
					theReferenceName -> ofNullable(facetGroupSorterFactory)
						.map(it -> it.apply(theReferenceName))
						.orElseGet(() -> Comparator.comparing((FacetGroupStatistics o12) -> o12.getGroupEntity().getPrimaryKey()))
				);
				return comparator.compare(o1, o2);
			}
		} else {
			return referenceCmp;
		}
	}

	@Nonnull
	private static RequestImpact computeImpact(
		@Nonnull List<SealedEntity> filteredEntities,
		@Nonnull Set<Integer> filteredEntityIds,
		@Nonnull ReferenceKey facet,
		@Nonnull FacetComputationalContext fcc
	) {
		// on already filtered entities
		final Predicate<? super SealedEntity> newPredicate = fcc.createTestFacetPredicate(facet);
		final Set<Integer> newResult = filteredEntities.stream()
			// apply newly created predicate with added current facet query
			.filter(newPredicate)
			// we need only primary keys
			.map(EntityContract::getPrimaryKey)
			// in set
			.collect(Collectors.toSet());

		final int difference = newResult.size() - filteredEntityIds.size();
		return new RequestImpact(
			// compute difference with base result
			difference,
			// pass new result count
			newResult.size(),
			// calculate has sense
			!newResult.isEmpty() && (
				// if there is difference
				(
					difference != 0 ||
						filteredEntities.stream()
							.anyMatch(fcc.createBaseFacetPredicateWithoutGroupOfFacet(facet))
				)
			)
		);
	}

	private static boolean isWithinHierarchy(
			Hierarchy categoryHierarchy, ReferenceContract category, int requestedCategoryId
	) {
		final int categoryId = category.getReferencedPrimaryKey();
		final String categoryIdAsString = String.valueOf(categoryId);
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
		// has parent node or requested category id
		return Objects.equals(requestedCategoryId, categoryId) ||
			parentItems
				.stream()
				.anyMatch(it -> Objects.equals(String.valueOf(requestedCategoryId), it.getCode()));
	}

	/**
	 * Creates a function that returns the given `ids` for the specified `entityType` and
	 * an empty int array for all other reference names. Used as a `selectedFacetProvider`
	 * when computing facet summaries with pre-selected hierarchical facets.
	 *
	 * @param entityType the reference name for which `ids` should be returned
	 * @param ids the facet IDs to return for the matching entity type
	 * @return a function mapping reference names to selected facet ID arrays
	 */
	@Nonnull
	private static Function<String, int[]> selectedFacetProviderFor(
		@Nonnull String entityType,
		@Nonnull int[] ids
	) {
		return referenceName -> entityType.equals(referenceName) ? ids : ArrayUtils.EMPTY_INT_ARRAY;
	}

	/**
	 * Tests whether a given entity's category references are within the specified hierarchy subtree
	 * rooted at `hierarchyRoot`, while excluding the subtree rooted at `excludedNodeId`.
	 *
	 * @param categoryHierarchy the hierarchy structure to navigate
	 * @param hierarchyRoot the root category ID of the subtree to include
	 * @param excludedNodeId the category ID whose subtree should be excluded
	 * @return a predicate that tests whether a sealed entity is within the hierarchy subtree
	 *         while not being under the excluded node
	 */
	@Nonnull
	private static Predicate<SealedEntity> isWithinHierarchyExcluding(
		@Nonnull Hierarchy categoryHierarchy,
		int hierarchyRoot,
		int excludedNodeId
	) {
		return sealedEntity -> sealedEntity.getReferences(Entities.CATEGORY)
			.stream()
			.anyMatch(it -> {
				final Set<Integer> parentItems = categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
					.stream()
					.map(theParent -> Integer.parseInt(theParent.getCode()))
					.collect(Collectors.toSet());
				return (it.getReferencedPrimaryKey() == hierarchyRoot || parentItems.contains(hierarchyRoot)) &&
					!(it.getReferencedPrimaryKey() == excludedNodeId || parentItems.contains(excludedNodeId));
			});
	}

	@Nonnull
	private static Integer[] getParametersWithDifferentGroups(
			List<SealedEntity> originalProductEntities, Set<Integer> groups
	) {
		final SealedEntity exampleProduct = originalProductEntities
			.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER)
				.stream()
				.filter(x -> x.getGroup().isPresent())
				.map(x -> x.getGroup().get().getPrimaryKey())
				.distinct()
				.count() >= 2
			)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
			"There is no product with two references to parameters in different groups!"
		));
		return exampleProduct.getReferences(Entities.PARAMETER)
			.stream()
			.filter(it -> it.getGroup().isPresent())
			.filter(it -> groups.add(it.getGroup().get().getPrimaryKey()))
			.map(ReferenceContract::getReferencedPrimaryKey)
			.toArray(Integer[]::new);
	}

	@Nonnull
	private static Integer[] getParametersWithSameGroup(List<SealedEntity> originalProductEntities, Set<Integer> groups) {
		return originalProductEntities
			.stream()
			.map(it -> {
				final Integer groupWithMultipleItems = it.getReferences(Entities.PARAMETER)
					.stream()
					.filter(x -> x.getGroup().isPresent())
					.collect(groupingBy(x -> x.getGroup().orElseThrow().getPrimaryKey(), Collectors.counting()))
					.entrySet()
					.stream()
					.filter(x -> x.getValue() > 2L)
					.map(Entry::getKey)
					.findFirst()
					.orElse(null);
				if (groupWithMultipleItems == null) {
					return null;
				} else {
					groups.add(groupWithMultipleItems);
					return it.getReferences(Entities.PARAMETER)
						.stream()
						.filter(x -> x.getGroup().map(GroupEntityReference::getPrimaryKey).orElse(-1).equals(groupWithMultipleItems))
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toArray(Integer[]::new);
				}
			})
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
			"There is no product with two references to parameters in same group!"
		));
	}

	private static Set<Integer> getGroupsWithGaps(List<SealedEntity> originalProductEntities) {
		final Set<Integer> allGroupsPresent = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(Entities.PARAMETER).stream())
			.filter(it -> it.getGroup().isPresent())
			.map(it -> it.getGroup().get().getPrimaryKey())
			.collect(Collectors.toSet());
		final Set<Integer> groupsWithGaps = new HashSet<>();
		for (SealedEntity product : originalProductEntities) {
			final Set<Integer> groupsPresentOnProduct = product.getReferences(Entities.PARAMETER).stream()
				.filter(it -> it.getGroup().isPresent())
				.map(it -> it.getGroup().get().getPrimaryKey())
				.collect(Collectors.toSet());
			allGroupsPresent
				.stream()
				.filter(it -> !groupsWithGaps.contains(it) && !groupsPresentOnProduct.contains(it))
				.forEach(groupsWithGaps::add);
		}
		return groupsWithGaps;
	}

	private static Integer[] getParametersInGroups(List<SealedEntity> originalProductEntities, Set<Integer> groups) {
		return originalProductEntities.stream()
			.flatMap(it -> it.getReferences(Entities.PARAMETER).stream())
			.filter(it -> it.getGroup().isPresent())
			.filter(it -> groups.contains(it.getGroup().get().getPrimaryKey()))
			.map(ReferenceContract::getReferencedPrimaryKey)
			.distinct()
			.toArray(Integer[]::new);
	}

	@Nullable
	@DataSet(value = THOUSAND_PRODUCTS_WITH_FACETS, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			final AtomicInteger index = new AtomicInteger();
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.registerValueGenerator(
					Entities.STORE, ATTRIBUTE_ORDER,
					faker -> {
						final int ix = index.incrementAndGet();
						final int position = ArrayUtils.indexOf(ix, STORE_ORDER);
						return position == 0 ? Predecessor.HEAD : new Predecessor(STORE_ORDER[position - 1]);
					}
				).build();

			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(
									ATTRIBUTE_ORDER, Predecessor.class,
									AttributeSchemaEditor::sortable
								).updateVia(session);
							return schemaBuilder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(STORE_COUNT)
				.forEach(session::upsertEntity);

			final List<EntityReferenceContract> storedParameterGroups = dataGenerator.generateEntities(
					dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(15)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReferenceContract> storedParameters = dataGenerator.generateEntities(
					dataGenerator.getSampleParameterSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(200)
				.map(session::upsertEntity)
				.toList();

			session.defineEntitySchema(EMPTY_COLLECTION_ENTITY)
				.withGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
				.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
				.updateVia(session);

			final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withReferenceToEntity(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.STORE, Entities.STORE, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							EMPTY_COLLECTION_ENTITY, EMPTY_COLLECTION_ENTITY, Cardinality.ZERO_OR_MORE,
							whichIs -> makeReferenceIndexed(whichIs).faceted()
						)
						.withReferenceToEntity(
							Entities.PARAMETER, Entities.PARAMETER,
							Cardinality.ZERO_OR_MORE,
							thatIs -> makeReferenceIndexed(thatIs).faceted()
								.withAttribute(ATTRIBUTE_TRANSIENT, Boolean.class, AttributeSchemaEditor::filterable)
								.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
						);
				}
			);
			final List<EntityReferenceContract> storedProducts = dataGenerator.generateEntities(
					productSchema,
					randomEntityPicker,
					SEED
				)
				.limit(1000)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				tuple(
					"originalProductEntities",
					storedProducts.stream()
						.map(it -> session.getEntity(
							it.getType(), it.getPrimaryKey(),
							attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll(), priceContentAll()
						).orElse(null))
						.collect(toList())
				),
				tuple(
					"parameterIndex",
					storedParameters.stream()
						.collect(
							toMap(
								EntityReferenceContract::getPrimaryKey,
								it -> session.getEntity(
								it.getType(), it.getPrimaryKeyOrThrowException(),
								attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
							).orElse(null)
							)
						)
				),
				tuple(
					"parameterGroupIndex",
					storedParameterGroups.stream()
						.collect(
							toMap(
								EntityReferenceContract::getPrimaryKey,
								it -> session.getEntity(
								it.getType(), it.getPrimaryKeyOrThrowException(),
								attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
							).orElse(null)
							)
						)
				),
				tuple(
					"categoryHierarchy",
					dataGenerator.getHierarchy(Entities.CATEGORY)
				),
				tuple(
					"productSchema",
					productSchema
				),
				tuple(
					"parameterGroupMapping",
					dataGenerator.getParameterIndex().get(Entities.PARAMETER)
				)
			);
		});
	}

	/**
	 * Configures the provided ReferenceSchemaBuilder to be indexed.
	 *
	 * @param whichIs the ReferenceSchemaBuilder instance to be configured as indexed
	 * @return the configured ReferenceSchemaBuilder instance
	 */
	@Nonnull
	protected abstract ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs);

	/**
	* @deprecated Use {@link #shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesUsingReferenceSummary}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should throw exception when accessing localized attributes on fetched entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntities(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummary(
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReferenceUsingReferenceSummary}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should throw exception when accessing localized attributes on fetched entities using explicit reference")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummaryOfReference(
									Entities.PARAMETER,
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldNotReturnReferenceSummaryForMissingReferencesOnProduct} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should not return facet summary for missing references on product")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldNotReturnFacetSummaryForMissingReferencesOnProduct(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(referenceHaving(Entities.BRAND))
						),
						require(
							page(1, 1),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(referenceContent(Entities.BRAND)),
							facetSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.COUNTS
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertTrue(result.getRecordData().get(0).getReferences(Entities.BRAND).isEmpty());
				assertNull(result.getExtraResult(FacetSummary.class).getFacetGroupStatistics(Entities.BRAND));
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnEmptyReferenceSummaryForEmptyCollection} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated
	@SuppressWarnings("deprecation")
	@DisplayName("Should return empty facet summary for empty collection")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test()
	void shouldReturnEmptyFacetSummaryForEmptyCollection(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetSummaryOfReference(
								EMPTY_COLLECTION_ENTITY,
								FacetStatisticsDepth.COUNTS,
								filterBy(
									referenceHaving(
										Entities.PARAMETER,
										filterBy(
											entityHaving(entityPrimaryKeyInSet(1))
										)
									)
								)
							)
						)
					),
					EntityReference.class
				);

				final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
				assertNotNull(facetSummary);
				assertTrue(facetSummary.getReferenceStatistics().isEmpty());
				return null;
			}
		);
	}

	@DisplayName("Should return products matching random facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest()
	@MethodSource("returnRandomSeed")
	void shouldReturnProductsWithSpecifiedFacetInEntireSet(
		long seed,
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random rnd = new Random(seed);
				for (String entityType : new String[]{Entities.CATEGORY, Entities.BRAND, Entities.STORE}) {
					final int entityCount = session.getEntityCollectionSize(entityType);
					// for each entity execute 100 pseudo random queries
					final int numberOfSelectedFacets = 1 + rnd.nextInt(5);
					final Integer[] facetIds = new Integer[numberOfSelectedFacets];
					for (int j = 0; j < numberOfSelectedFacets; j++) {
						final int primaryKey = rnd.nextInt(entityCount - 1) + 1;
						facetIds[j] = primaryKey;
					}

					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								userFilter(
									facetHaving(entityType, entityPrimaryKeyInSet(facetIds))
								)
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					final Set<Integer> selectedIdsAsSet = new HashSet<>(Arrays.asList(facetIds));
					assertResultIs(
						"Querying " + entityType + " facets: " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> sealedEntity
							.getReferences(entityType)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.anyMatch(selectedIdsAsSet::contains),
						result.getRecordData()
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Should return products matching group AND combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupAndCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] parameters = getParametersWithDifferentGroups(originalProductEntities, new HashSet<>());
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching group OR combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupOrCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithDifferentGroups(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsDisjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.anyMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return the same products whichever level a negation is declared at")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSameProductsForNegationDeclaredAtEitherLevel(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> groups = getGroupsWithGaps(originalProductEntities);
				final Integer[] parameters = getParametersInGroups(originalProductEntities, groups);
				final Integer[] groupIds = groups.toArray(new Integer[0]);

				// negating each facet and combining with AND is the same set as negating the group's own
				// disjunction - `!a && !b` is `!(a || b)` - so the declared level cannot change the answer
				final EvitaResponse<EntityReference> withinGroup = session.query(
					negationQuery(parameters, groupIds, FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP),
					EntityReference.class
				);
				final EvitaResponse<EntityReference> betweenGroups = session.query(
					negationQuery(parameters, groupIds, FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS),
					EntityReference.class
				);

				assertEquals(betweenGroups.getTotalRecordCount(), withinGroup.getTotalRecordCount());
				assertFalse(withinGroup.getRecordData().isEmpty());
				assertEquals(
					betweenGroups.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					withinGroup.getRecordData().stream().map(EntityReference::getPrimaryKey).toList()
				);
				return null;
			}
		);
	}

	/**
	 * Builds the query used by {@link #shouldReturnSameProductsForNegationDeclaredAtEitherLevel} - the selected
	 * parameter facets with their groups negated at the requested relation level.
	 *
	 * @param parameters facet primary keys to select in the user filter
	 * @param groupIds   primary keys of the groups the negation applies to
	 * @param level      level the negation is declared at
	 * @return the query to execute
	 */
	@Nonnull
	private static Query negationQuery(
		@Nonnull Integer[] parameters,
		@Nonnull Integer[] groupIds,
		@Nonnull FacetGroupRelationLevel level
	) {
		return query(
			collection(Entities.PRODUCT),
			filterBy(
				userFilter(
					facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				facetGroupsNegation(Entities.PARAMETER, level, filterBy(entityPrimaryKeyInSet(groupIds)))
			)
		);
	}

	@DisplayName("Should return products matching group NOT combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupNotCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> groups = getGroupsWithGaps(originalProductEntities);
				final Integer[] parameters = getParametersInGroups(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.noneMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the negated option witness over the {@link #THOUSAND_PRODUCTS_WITH_FACETS} data set. Each row
	 * is a label, the reference, the number of options selected in it, whether the selected options belong to a group,
	 * and the factory of the negation requirement, which receives the group of the first selected option (NULL for an
	 * option without a group).
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> negatedOptionRows() {
		return Stream.of(
			Arguments.of(
				"brand, within group", Entities.BRAND, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.BRAND)
			),
			Arguments.of(
				"brand, between groups", Entities.BRAND, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.BRAND, WITH_DIFFERENT_GROUPS)
			),
			Arguments.of(
				"store, two options", Entities.STORE, 2, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.STORE)
			),
			Arguments.of(
				"store, between groups", Entities.STORE, 1, false,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.STORE, WITH_DIFFERENT_GROUPS)
			),
			Arguments.of(
				"parameter, no group filter", Entities.PARAMETER, 1, true,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(Entities.PARAMETER)
			),
			Arguments.of(
				"parameter, group filter, between groups", Entities.PARAMETER, 1, true,
				(Function<Integer, RequireConstraint>) group -> facetGroupsNegation(
					Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(group))
				)
			)
		);
	}

	/**
	 * Checks that a negated option selected in `facetHaving` excludes the products referencing it from the result
	 * whether the option belongs to a group or not, and at whichever level the negation is declared. The expected
	 * products are the ones referencing none of the selected options; when a single option is selected, the result
	 * must also be as large as the count the reference summary predicts for that option.
	 *
	 * The selected options are those with the lowest primary keys any product references.
	 *
	 * @param label                   the row label, used in the test name only
	 * @param referenceName           the reference the options are selected in
	 * @param optionCount             the number of options to select
	 * @param grouped                 whether the selected options belong to a group
	 * @param relationFactory         creates the negation requirement from the group of the first selected option
	 * @param evita                   the engine instance provided by the test extension
	 * @param originalProductEntities the products of the data set
	 */
	@DisplayName("Should return the products the reference summary counts for a negated option")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest(name = "{0}")
	@MethodSource("negatedOptionRows")
	void shouldReturnProductsReferenceSummaryCountsForNegatedOption(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionCount,
		boolean grouped,
		@Nonnull Function<Integer, RequireConstraint> relationFactory,
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		final int[] optionIds = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(referenceName).stream())
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.distinct()
			.sorted()
			.limit(optionCount)
			.toArray();
		assertEquals(optionCount, optionIds.length, "the data set must reference enough options");
		final Integer groupId = originalProductEntities.stream()
			.flatMap(it -> it.getReferences(referenceName).stream())
			.filter(it -> it.getReferencedPrimaryKey() == optionIds[0])
			.findFirst()
			.flatMap(ReferenceContract::getGroup)
			.map(GroupEntityReference::getPrimaryKey)
			.orElse(null);
		assertEquals(grouped, groupId != null, "the selected option must " + (grouped ? "" : "not ") + "have a group");

		final Set<Integer> selectedIds = Arrays.stream(optionIds).boxed().collect(Collectors.toSet());
		final Predicate<SealedEntity> referencesNoSelectedOption = product -> product.getReferences(referenceName)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.noneMatch(selectedIds::contains);
		final long expectedCount = originalProductEntities.stream().filter(referencesNoSelectedOption).count();
		assertTrue(
			expectedCount > 0 && expectedCount < originalProductEntities.size(),
			"the negation must exclude some products and keep others"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final RequireConstraint relation = relationFactory.apply(groupId);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(referenceName, entityPrimaryKeyInSet(Arrays.stream(optionIds).boxed().toArray(Integer[]::new)))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							relation
						)
					),
					EntityReference.class
				);
				assertResultIs(
					"Querying " + referenceName + " options " + Arrays.toString(optionIds) + " negated",
					originalProductEntities,
					referencesNoSelectedOption,
					result.getRecordData()
				);

				if (optionIds.length == 1) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								userFilter(
									facetHaving(referenceName, entityPrimaryKeyInSet(optionIds[0]))
								)
							),
							require(
								page(1, 1),
								referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS),
								relation
							)
						),
						EntityReference.class
					);
					assertEquals(expectedCount, withSummary.getTotalRecordCount());
					assertEquals(
						expectedCount,
						facetCountOf(withSummary, referenceName, groupId, optionIds[0]),
						"the reference summary must count the products the negated option leaves in the result"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Builds the small hand-made data set described on {@link #FACET_RELATION_SHAPES}.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_RELATION_SHAPES, destroyAfterClass = true)
	void setUpFacetRelationShapes(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withAttribute(ATTRIBUTE_NOTE, String.class)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_TAG_GROUP, TAG_GROUP)
						.setAttribute(ATTRIBUTE_CODE, "tagGroup")
						.setAttribute(ATTRIBUTE_NOTE, "not filterable")
				);
				session.defineEntitySchema(ENTITY_LABEL)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				for (int labelId = 1; labelId <= LABEL_GROUPS.length; labelId++) {
					session.upsertEntity(session.createNewEntity(ENTITY_LABEL, labelId));
				}
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (final int tagId : new int[]{GROUPED_TAG, UNGROUPED_TAG}) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_TAG, tagId).setAttribute(ATTRIBUTE_CODE, "tag" + tagId)
					);
				}
				session.defineEntitySchema(ENTITY_SHAPED_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_LABEL, ENTITY_LABEL, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted().withGroupType(ENTITY_LABEL_GROUP)
					)
					.withReferenceToEntity(
						REF_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted().withGroupTypeRelatedToEntity(ENTITY_TAG_GROUP)
					)
					.withReferenceTo(
						REF_SOURCE, ENTITY_SOURCE, Cardinality.ZERO_OR_MORE,
						whichIs -> makeReferenceIndexed(whichIs).faceted()
							.withAttribute(ATTRIBUTE_CHANNEL, String.class, AttributeSchemaEditor::filterable)
					)
					.updateVia(session);
				for (int pk = 1; pk <= SHAPED_PRODUCT_LABELS.length; pk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_SHAPED_PRODUCT, pk);
					for (final int labelId : SHAPED_PRODUCT_LABELS[pk - 1]) {
						final Integer labelGroup = LABEL_GROUPS[labelId - 1];
						product.setReference(
							REF_LABEL, labelId,
							labelGroup == null ? null : whichIs -> whichIs.setGroup(labelGroup)
						);
					}
					for (final int tagId : SHAPED_PRODUCT_TAGS[pk - 1]) {
						product.setReference(
							REF_TAG, tagId,
							tagId == GROUPED_TAG ? whichIs -> whichIs.setGroup(TAG_GROUP) : null
						);
					}
					for (final int sourceId : SHAPED_PRODUCT_SOURCES[pk - 1]) {
						product.setReference(
							REF_SOURCE, sourceId,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_CHANNEL, SOURCE_CHANNELS[sourceId - 1])
						);
					}
					session.upsertEntity(product);
				}
			}
		);
	}

	/**
	 * Returns the rows of the relation witness over the {@link #FACET_RELATION_SHAPES} data set, whose label groups
	 * are not managed by evitaDB and whose label reference mixes grouped options with options without a group. Each
	 * row is a label, the selected labels, the relation requirement, and the primary keys of the products the query
	 * returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> labelRelationRows() {
		return Stream.of(
			Arguments.of(
				"unmanaged group, negation", new int[]{1},
				facetGroupsNegation(REF_LABEL),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, group filter, between groups", new int[]{3},
				facetGroupsNegation(REF_LABEL, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"ungrouped option of a mixed reference", new int[]{4},
				facetGroupsNegation(REF_LABEL),
				shapedProductsWithLabels(false, 4)
			),
			Arguments.of(
				"disjunction across ungrouped and grouped", new int[]{3, 4},
				facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS),
				shapedProductsWithLabels(true, 3, 4)
			),
			// a group filter cannot match an option without a group, so the option stays a positive selection
			Arguments.of(
				"ungrouped option, group filter", new int[]{4},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 4)
			),
			Arguments.of(
				"unmanaged group, primary key filter", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter in a logical container", new int[]{1},
				facetGroupsNegation(
					REF_LABEL,
					filterBy(or(entityPrimaryKeyInSet(LABEL_GROUP_A), entityPrimaryKeyInSet(MISSING_LABEL_GROUP)))
				),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter in a negation", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(not(entityPrimaryKeyInSet(LABEL_GROUP_B)))),
				shapedProductsWithLabels(false, 1)
			),
			// the primary key ranges need nothing but the primary keys of the groups either, and each of them is run
			// once over the group it matches and once over a group it does not match
			Arguments.of(
				"unmanaged group, primary key between", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyBetween(LABEL_GROUP_A, LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key between, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyBetween(LABEL_GROUP_A, LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThan(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than, other group", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThan(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key greater than or equal", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThanEquals(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 3)
			),
			Arguments.of(
				"unmanaged group, primary key greater than or equal, other group", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyGreaterThanEquals(LABEL_GROUP_B))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThan(LABEL_GROUP_B))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThan(LABEL_GROUP_B))),
				shapedProductsWithLabels(true, 3)
			),
			Arguments.of(
				"unmanaged group, primary key less than or equal", new int[]{1},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThanEquals(LABEL_GROUP_A))),
				shapedProductsWithLabels(false, 1)
			),
			Arguments.of(
				"unmanaged group, primary key less than or equal, other group", new int[]{3},
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyLessThanEquals(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 3)
			)
		);
	}

	/**
	 * Checks that the relation settings of a reference whose groups are not managed by evitaDB, and which mixes grouped
	 * options with options without a group, decide the query result the same way as for any other reference. When a
	 * single label is selected, the result must also be as large as the count the reference summary predicts for it.
	 *
	 * @param label    the row label, used in the test name only
	 * @param labelIds the selected labels
	 * @param relation the relation requirement of the label reference
	 * @param expected the primary keys of the products the query returns, ascending
	 * @param evita    the engine instance provided by the test extension
	 */
	@DisplayName("Should apply the relation settings to unmanaged groups and to options without a group")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("labelRelationRows")
	void shouldApplyRelationToUnmanagedGroupsAndOptionsWithoutGroup(
		@Nonnull String label,
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the relation must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					shapedLabelQuery(labelIds, relation, null),
					EntityReference.class
				);
				assertArrayEquals(
					expected,
					result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
				);

				if (labelIds.length == 1) {
					final EvitaResponse<EntityReference> withSummary = session.query(
						shapedLabelQuery(
							labelIds, relation, referenceSummaryOfReference(REF_LABEL, FacetStatisticsDepth.COUNTS)
						),
						EntityReference.class
					);
					assertEquals(expected.length, withSummary.getTotalRecordCount());
					assertEquals(
						expected.length,
						facetCountOf(withSummary, REF_LABEL, LABEL_GROUPS[labelIds[0] - 1], labelIds[0]),
						"the reference summary must count the products the option leaves in the result"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filters that cannot be evaluated, over the {@link #FACET_RELATION_SHAPES} data set.
	 * Each row is a label, the reference the option is selected in, the selected option, the relation requirement with
	 * the group filter - which may name another reference - the exact type of the exception the query must fail with,
	 * and the fragments its message must contain. Every row is run with and without the reference summary of the
	 * reference the option is selected in.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> unevaluableGroupFilterRows() {
		final FilterBy byLabelGroupCode = filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"));
		final FilterBy byTagGroupNote = filterBy(attributeEquals(ATTRIBUTE_NOTE, "anything"));
		return Stream.of(
			Arguments.of(
				"unmanaged group, attribute filter", REF_LABEL, 1,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter, ungrouped selection", REF_LABEL, 4,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter of disjunction, ungrouped selection", REF_LABEL, 4,
				facetGroupsDisjunction(REF_LABEL, WITH_DIFFERENT_GROUPS, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			Arguments.of(
				"managed group, non-filterable attribute, ungrouped selection", REF_TAG, UNGROUPED_TAG,
				facetGroupsNegation(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			// the query result never asks about exclusivity, only the reference summary does
			Arguments.of(
				"unmanaged group, attribute filter of exclusivity", REF_LABEL, 1,
				facetGroupsExclusivity(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"unmanaged group, attribute filter of exclusivity, ungrouped selection", REF_LABEL, 4,
				facetGroupsExclusivity(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute of exclusivity", REF_TAG, GROUPED_TAG,
				facetGroupsExclusivity(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			),
			// the relation names a reference other than the one selected and summarized, so nothing asks about it
			Arguments.of(
				"unmanaged group, attribute filter of a reference not selected", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_LABEL, byLabelGroupCode),
				EntityNotManagedException.class, new String[]{"`" + ENTITY_LABEL_GROUP + "`", "is not managed"}
			),
			Arguments.of(
				"managed group, non-filterable attribute of a reference not selected", REF_LABEL, 1,
				facetGroupsConjunction(REF_TAG, byTagGroupNote),
				AttributeNotFilterableException.class, new String[]{"`" + ATTRIBUTE_NOTE + "`"}
			)
		)
			.flatMap(row -> Stream.of(false, true).map(withSummary -> {
				final Object[] arguments = row.get();
				final Object[] withArm = Arrays.copyOf(arguments, arguments.length + 1);
				withArm[0] = arguments[0] + (withSummary ? ", with summary" : ", without summary");
				withArm[arguments.length] = withSummary;
				return Arguments.of(withArm);
			}));
	}

	/**
	 * Checks that a group filter which cannot be evaluated - one asking an unmanaged group type about anything but its
	 * primary keys, or using an attribute the managed group type cannot filter by - makes the query fail with a client
	 * error, whether the selected option belongs to a group or not, whether the reference summary is requested or
	 * not, and whether anything in the query asks about the relation of that reference at all.
	 *
	 * @param label            the row label, used in the test name only
	 * @param referenceName    the reference the option is selected in
	 * @param optionId         the selected option
	 * @param relation         the relation requirement with the group filter
	 * @param expectedType     the exact type of the exception the query must fail with
	 * @param messageFragments the fragments the exception message must contain
	 * @param withSummary      whether the query requests the reference summary of the reference
	 * @param evita            the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query whose group filter cannot be evaluated")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("unevaluableGroupFilterRows")
	void shouldFailQueryWhoseGroupFilterCannotBeEvaluated(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionId,
		@Nonnull RequireConstraint relation,
		@Nonnull Class<? extends Throwable> expectedType,
		@Nonnull String[] messageFragments,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Throwable exception = assertThrowsExactly(
					expectedType,
					() -> session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, entityPrimaryKeyInSet(optionId)))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				for (final String fragment : messageFragments) {
					assertTrue(
						exception.getMessage().contains(fragment),
						"the message `" + exception.getMessage() + "` must contain `" + fragment + "`"
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filters that can be evaluated although nothing in the query result asks about them,
	 * over the {@link #FACET_RELATION_SHAPES} data set. Each row is a label, the reference the option is selected in,
	 * the selected option, the relation requirement with the group filter, and the primary keys of the products the
	 * query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> evaluableUnaskedGroupFilterRows() {
		return Stream.of(
			Arguments.of(
				"managed group, primary key filter of exclusivity", REF_TAG, GROUPED_TAG,
				facetGroupsExclusivity(REF_TAG, filterBy(entityPrimaryKeyInSet(TAG_GROUP))),
				shapedProductsWithTag(GROUPED_TAG)
			),
			Arguments.of(
				"unmanaged group, primary key filter of exclusivity", REF_LABEL, 1,
				facetGroupsExclusivity(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_A))),
				shapedProductsWithLabels(true, 1)
			),
			Arguments.of(
				"unmanaged group, primary key filter of a reference not selected", REF_TAG, GROUPED_TAG,
				facetGroupsNegation(REF_LABEL, filterBy(entityPrimaryKeyInSet(LABEL_GROUP_B))),
				shapedProductsWithTag(GROUPED_TAG)
			),
			Arguments.of(
				"attribute filter of a reference the entity does not have", REF_TAG, GROUPED_TAG,
				facetGroupsNegation("missingReference", filterBy(attributeEquals(ATTRIBUTE_CODE, "anything"))),
				shapedProductsWithTag(GROUPED_TAG)
			)
		);
	}

	/**
	 * Checks that a group filter which can be evaluated leaves the query result and the reference summary intact when
	 * the query result itself never asks about the relation it belongs to - for exclusivity, or for a reference other
	 * than the selected one.
	 *
	 * @param label         the row label, used in the test name only
	 * @param referenceName the reference the option is selected in
	 * @param optionId      the selected option
	 * @param relation      the relation requirement with the group filter
	 * @param expected      the primary keys of the products the query returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should accept an evaluable group filter the query result does not ask about")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("evaluableUnaskedGroupFilterRows")
	void shouldAcceptEvaluableGroupFilterTheResultDoesNotAskAbout(
		@Nonnull String label,
		@Nonnull String referenceName,
		int optionId,
		@Nonnull RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		final Integer groupId = REF_LABEL.equals(referenceName) ?
			LABEL_GROUPS[optionId - 1] : (optionId == GROUPED_TAG ? TAG_GROUP : null);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final boolean withSummary : new boolean[]{false, true}) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, entityPrimaryKeyInSet(optionId)))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					);
					assertArrayEquals(
						expected,
						result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
					);
					if (withSummary) {
						assertEquals(
							expected.length,
							facetCountOf(result, referenceName, groupId, optionId),
							"the reference summary must count the products the option leaves in the result"
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the option selections over the {@link #FACET_RELATION_SHAPES} data set, on the reference to
	 * the unmanaged source type and - as a control - on the reference to the managed tag type. Each row is a label,
	 * the reference the option is selected in, the selecting constraint of `facetHaving`, the option the selection
	 * resolves to, its group, the relation requirement of the reference (NULL for the defaults), and the primary keys
	 * of the products the query returns, computed from the fixture table.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> optionSelectionRows() {
		final int[] withGroupedTag = shapedProductsWithTag(GROUPED_TAG);
		return Stream.of(
			Arguments.of(
				"unmanaged option, primary key", REF_SOURCE, entityPrimaryKeyInSet(1), 1, null, null,
				shapedProductsWithSources(true, 1)
			),
			Arguments.of(
				"unmanaged option, primary key, negation", REF_SOURCE, entityPrimaryKeyInSet(1), 1, null,
				facetGroupsNegation(REF_SOURCE),
				shapedProductsWithSources(false, 1)
			),
			Arguments.of(
				"unmanaged option, primary key in a logical container", REF_SOURCE,
				or(entityPrimaryKeyInSet(2), entityPrimaryKeyInSet(MISSING_SOURCE)), 2, null, null,
				shapedProductsWithSources(true, 2)
			),
			Arguments.of(
				"unmanaged option, primary key in a negation", REF_SOURCE, not(entityPrimaryKeyInSet(1)), 2, null, null,
				shapedProductsWithSources(true, 2)
			),
			// a reference attribute is stored by the products themselves, so it needs nothing of the referenced entity
			Arguments.of(
				"unmanaged option, reference attribute", REF_SOURCE, attributeEquals(ATTRIBUTE_CHANNEL, "shop"), 2, null,
				null,
				shapedProductsWithSources(true, 2)
			),
			Arguments.of(
				"managed option, primary key", REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG), GROUPED_TAG, TAG_GROUP,
				null,
				withGroupedTag
			),
			Arguments.of(
				"managed option, primary key, negation", REF_TAG, entityPrimaryKeyInSet(GROUPED_TAG), GROUPED_TAG,
				TAG_GROUP, facetGroupsNegation(REF_TAG),
				IntStream.rangeClosed(1, SHAPED_PRODUCT_TAGS.length)
					.filter(pk -> ArrayUtils.indexOf(pk, withGroupedTag) < 0)
					.toArray()
			),
			Arguments.of(
				"managed option, primary key in a logical container", REF_TAG,
				or(entityPrimaryKeyInSet(GROUPED_TAG), entityPrimaryKeyInSet(MISSING_TAG)), GROUPED_TAG, TAG_GROUP,
				null,
				withGroupedTag
			),
			Arguments.of(
				"managed option, entity attribute", REF_TAG, entityHaving(attributeEquals(ATTRIBUTE_CODE, "tag1")),
				GROUPED_TAG, TAG_GROUP, null,
				withGroupedTag
			)
		);
	}

	/**
	 * Checks that `facetHaving` selects the options of a reference to an entity type evitaDB does not manage by what
	 * the products store about them - the primary keys, also inside logical containers, and the reference attributes -
	 * exactly as it selects the options of a reference to a managed type, and that the reference summary counts the
	 * selected option as many products as the query returns, also when the option is negated.
	 *
	 * @param label         the row label, used in the test name only
	 * @param referenceName the reference the option is selected in
	 * @param selection     the selecting constraint of `facetHaving`
	 * @param optionId      the option the selection resolves to
	 * @param groupId       the group of the option, NULL for an option without a group
	 * @param relation      the relation requirement of the reference, NULL for the defaults
	 * @param expected      the primary keys of the products the query returns, ascending
	 * @param evita         the engine instance provided by the test extension
	 */
	@DisplayName("Should select options whether the referenced entity type is managed or not")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("optionSelectionRows")
	void shouldSelectOptionsWhetherReferencedTypeIsManagedOrNot(
		@Nonnull String label,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint selection,
		int optionId,
		@Nullable Integer groupId,
		@Nullable RequireConstraint relation,
		@Nonnull int[] expected,
		Evita evita
	) {
		assertTrue(
			expected.length > 0 && expected.length < SHAPED_PRODUCT_LABELS.length,
			"the selection must exclude some products and keep others"
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final boolean withSummary : new boolean[]{false, true}) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(referenceName, selection))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								relation,
								withSummary ? referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					);
					assertArrayEquals(
						expected,
						result.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray()
					);
					if (withSummary) {
						assertEquals(
							expected.length,
							facetCountOf(result, referenceName, groupId, optionId),
							"the reference summary must count the products the option leaves in the result"
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the option selections over the {@link #FACET_RELATION_SHAPES} data set that ask the unmanaged
	 * source type about its own data, which evitaDB does not have. Each row is a label and the selecting constraint
	 * of `facetHaving`; every row is run with and without the reference summary of the reference.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> unevaluableUnmanagedOptionSelectionRows() {
		final FilterConstraint byEntityCode = entityHaving(attributeEquals(ATTRIBUTE_CODE, "anything"));
		return Stream.of(
			Arguments.of("entity attribute", new FilterConstraint[]{byEntityCode}),
			Arguments.of(
				"entity attribute in a logical container",
				new FilterConstraint[]{or(entityPrimaryKeyInSet(1), byEntityCode)}
			),
			Arguments.of(
				"entity attribute beside a primary key",
				new FilterConstraint[]{entityPrimaryKeyInSet(1), byEntityCode}
			)
		)
			.flatMap(row -> Stream.of(false, true).map(withSummary -> Arguments.of(
				row.get()[0] + (withSummary ? ", with summary" : ", without summary"), row.get()[1], withSummary
			)));
	}

	/**
	 * Checks that `facetHaving` asking a referenced entity type evitaDB does not manage about the data of the entities
	 * themselves makes the query fail with the client error naming that type, whether the reference summary is
	 * requested or not.
	 *
	 * @param label       the row label, used in the test name only
	 * @param selection   the selecting constraints of `facetHaving`
	 * @param withSummary whether the query requests the reference summary of the reference
	 * @param evita       the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query asking an unmanaged referenced entity type about its own data")
	@UseDataSet(FACET_RELATION_SHAPES)
	@ParameterizedTest(name = "{0}")
	@MethodSource("unevaluableUnmanagedOptionSelectionRows")
	void shouldFailQueryAskingUnmanagedReferencedTypeAboutItsData(
		@Nonnull String label,
		@Nonnull FilterConstraint[] selection,
		boolean withSummary,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityNotManagedException exception = assertThrowsExactly(
					EntityNotManagedException.class,
					() -> session.query(
						query(
							collection(ENTITY_SHAPED_PRODUCT),
							filterBy(userFilter(facetHaving(REF_SOURCE, selection))),
							require(
								page(1, SHAPED_PRODUCT_LABELS.length),
								withSummary ? referenceSummaryOfReference(REF_SOURCE, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				assertTrue(
					exception.getMessage().contains("`" + ENTITY_SOURCE + "`"),
					"the message `" + exception.getMessage() + "` must name the unmanaged type"
				);
				return null;
			}
		);
	}

	/**
	 * Returns the rows of the group filter declared for a reference without any group type: the relation requirement,
	 * the name of its constraint, whether the reference summary is requested and whether an option of the reference is
	 * selected.
	 *
	 * @return the row arguments
	 */
	@Nonnull
	static Stream<Arguments> groupFilterOfReferenceWithoutGroupTypeRows() {
		return Stream.concat(
			Stream.of(false, true)
				.flatMap(withSummary -> Stream.of(
					Arguments.of(
						"facetGroupsNegation" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsNegation(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))), "facetGroupsNegation",
						withSummary, true
					),
					Arguments.of(
						"facetGroupsDisjunction" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsDisjunction(Entities.BRAND, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1))),
						"facetGroupsDisjunction", withSummary, true
					),
					// the query result never asks about exclusivity, only the reference summary does
					Arguments.of(
						"facetGroupsExclusivity" + (withSummary ? ", with summary" : ", without summary"),
						facetGroupsExclusivity(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))),
						"facetGroupsExclusivity", withSummary, true
					)
				)),
			// nothing in the query asks about the relations of the reference
			Stream.of(
				Arguments.of(
					"facetGroupsConjunction, no option selected, without summary",
					facetGroupsConjunction(Entities.BRAND, filterBy(entityPrimaryKeyInSet(1))),
					"facetGroupsConjunction", false, false
				)
			)
		);
	}

	/**
	 * Checks that a group filter declared for a reference that has no group type at all - its options never belong to
	 * any group - makes the query fail with a client error naming the reference and the relation constraint, whether
	 * the reference summary is requested or not, and whether an option of the reference is selected or not.
	 *
	 * @param label          the row label, used in the test name only
	 * @param relation       the relation requirement with the group filter
	 * @param constraintName the name of the relation constraint
	 * @param withSummary    whether the query requests the reference summary of the reference
	 * @param selected       whether the query selects an option of the reference in its user filter
	 * @param evita          the engine instance provided by the test extension
	 */
	@DisplayName("Should fail the query with a group filter of a reference without group type")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest(name = "{0}")
	@MethodSource("groupFilterOfReferenceWithoutGroupTypeRows")
	void shouldFailQueryWithGroupFilterOfReferenceWithoutGroupType(
		@Nonnull String label,
		@Nonnull RequireConstraint relation,
		@Nonnull String constraintName,
		boolean withSummary,
		boolean selected,
		Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInvalidUsageException exception = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							selected ? filterBy(userFilter(facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)))) : null,
							require(
								page(1, 20),
								relation,
								withSummary ? referenceSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS) : null
							)
						),
						EntityReference.class
					)
				);
				final String message = exception.getMessage();
				assertTrue(message.contains("`" + Entities.BRAND + "`"), message);
				assertTrue(message.contains("`" + constraintName + "`"), message);
				assertTrue(message.contains("has no group type"), message);
				return null;
			}
		);
	}

	/**
	 * Builds the query selecting the passed labels of the {@link #FACET_RELATION_SHAPES} data set in the user filter.
	 *
	 * @param labelIds the selected labels
	 * @param relation the relation requirement of the label reference
	 * @param summary  the reference summary requirement, NULL when no summary is requested
	 * @return the query
	 */
	@Nonnull
	private static Query shapedLabelQuery(
		@Nonnull int[] labelIds,
		@Nonnull RequireConstraint relation,
		@Nullable RequireConstraint summary
	) {
		return query(
			collection(ENTITY_SHAPED_PRODUCT),
			filterBy(userFilter(facetHaving(REF_LABEL, entityPrimaryKeyInSet(Arrays.stream(labelIds).boxed().toArray(Integer[]::new))))),
			require(
				page(1, SHAPED_PRODUCT_LABELS.length),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				relation,
				summary
			)
		);
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference any of the
	 * passed labels, or none of them, as the fixture table states.
	 *
	 * @param referencingAny `true` for the products referencing any of the labels, `false` for those referencing none
	 * @param labelIds       the labels
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithLabels(boolean referencingAny, int... labelIds) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_LABELS.length)
			.filter(pk -> Arrays.stream(SHAPED_PRODUCT_LABELS[pk - 1])
				.anyMatch(labelId -> ArrayUtils.indexOf(labelId, labelIds) >= 0) == referencingAny)
			.toArray();
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference the passed
	 * tag, as the fixture table states.
	 *
	 * @param tagId the tag
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithTag(int tagId) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_TAGS.length)
			.filter(pk -> ArrayUtils.indexOf(tagId, SHAPED_PRODUCT_TAGS[pk - 1]) >= 0)
			.toArray();
	}

	/**
	 * Returns the primary keys of the products of the {@link #FACET_RELATION_SHAPES} data set that reference any of the
	 * passed sources, or none of them, as the fixture table states.
	 *
	 * @param referencingAny `true` for the products referencing any of the sources, `false` for those referencing none
	 * @param sourceIds      the sources
	 * @return the ascending primary keys
	 */
	@Nonnull
	private static int[] shapedProductsWithSources(boolean referencingAny, int... sourceIds) {
		return IntStream.rangeClosed(1, SHAPED_PRODUCT_SOURCES.length)
			.filter(pk -> Arrays.stream(SHAPED_PRODUCT_SOURCES[pk - 1])
				.anyMatch(sourceId -> ArrayUtils.indexOf(sourceId, sourceIds) >= 0) == referencingAny)
			.toArray();
	}

	/**
	 * Returns the count the reference summary of the passed response computes for the passed option.
	 *
	 * @param response      the response carrying the reference summary
	 * @param referenceName the reference the option belongs to
	 * @param groupId       the group of the option, NULL for an option without a group
	 * @param optionId      the option
	 * @return the count of the option
	 */
	private static int facetCountOf(
		@Nonnull EvitaResponse<EntityReference> response,
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int optionId
	) {
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "the reference summary must be computed");
		final ReferenceGroupStatistics groupStatistics = groupId == null ?
			summary.getReferenceGroupStatistics(referenceName) :
			summary.getReferenceGroupStatistics(referenceName, groupId);
		assertNotNull(groupStatistics, "the group " + groupId + " of `" + referenceName + "` must have statistics");
		final FacetStatistics facetStatistics = groupStatistics.getFacetStatistics(optionId);
		assertNotNull(facetStatistics, "the option " + optionId + " of `" + referenceName + "` must have statistics");
		return facetStatistics.getCount();
	}

	@DisplayName("Should return products matching AND combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetAndCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, groups);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groups.toArray(new Integer[0]))))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching AND combination of facet using the attribute filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingSpecifiedFacetAndCombinationDefinedByGroupAttributeFilterInEntireSetBy(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, groups);
				final String[] groupCodes = groups.stream()
					.map(parameterGroupIndex::get)
					.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
					.toArray(String[]::new);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetGroupsConjunction(Entities.PARAMETER, filterBy(attributeInSet(ATTRIBUTE_CODE, groupCodes)))
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.allMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching OR combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetOrCombinationInEntireSet(
		Evita evita,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] parameters = getParametersWithSameGroup(originalProductEntities, new HashSet<>());
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> selectedIdsAsSet = Arrays.stream(parameters).collect(Collectors.toSet());
				assertResultIs(
					"Querying " + Entities.PARAMETER + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					sealedEntity -> selectedIdsAsSet
						.stream()
						.anyMatch(parameterId -> sealedEntity.getReference(Entities.PARAMETER, parameterId).isPresent()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products matching random facet within hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@ParameterizedTest()
	@MethodSource("returnRandomSeed")
	void shouldReturnProductsWithSpecifiedFacetInHierarchyTree(
		long seed,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random rnd = new Random(seed);
				final int categoryCount = session.getEntityCollectionSize(Entities.CATEGORY);
				for (String entityType : new String[]{Entities.CATEGORY, Entities.BRAND, Entities.STORE}) {
					final int entityCount = session.getEntityCollectionSize(entityType);
					final int numberOfSelectedFacets = rnd.nextInt(entityCount - 1) + 1;
					final Integer[] facetIds = new Integer[numberOfSelectedFacets];
					for (int j = 0; j < numberOfSelectedFacets; j++) {
						int primaryKey;
						do {
							primaryKey = rnd.nextInt(entityCount - 1) + 1;
						} while (ArrayUtils.contains(facetIds, primaryKey));
						facetIds[j] = primaryKey;
					}

					final int hierarchyRoot = rnd.nextInt(categoryCount - 1) + 1;
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(hierarchyRoot)),
									userFilter(
										facetHaving(entityType, entityPrimaryKeyInSet(facetIds))
									)
								)
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					final Set<Integer> selectedIdsAsSet = new HashSet<>(Arrays.asList(facetIds));
					assertResultIs(
						"Querying " + entityType + " facets in hierarchy root " + hierarchyRoot + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							final boolean isWithinHierarchy = sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
							// has the facet
							final boolean hasFacet = sealedEntity.getReferences(entityType)
								.stream()
								.map(ReferenceContract::getReferencedPrimaryKey)
								.anyMatch(selectedIdsAsSet::contains);
							return isWithinHierarchy && hasFacet;
						},
						result.getRecordData()
					);
				}
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithHierarchicalFacetSubTreeUsingReferenceSummary} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet including all its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
					final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
					final Integer[] facetIds = new Integer[]{hierarchyRoot};

					final Query query = query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								userFilter(
									facetHaving(
										Entities.CATEGORY,
										entityPrimaryKeyInSet(facetIds),
										includingChildren()
									)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetSummary(FacetStatisticsDepth.IMPACT)
						)
					);

					final EvitaResponse<EntityReference> result = session.query(
						query,
						EntityReference.class
					);

					assertResultIs(
						"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							return sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
						},
						result.getRecordData()
					);

					final int[] selectedIds = Stream.concat(
							Arrays.stream(facetIds),
							categoryHierarchy.getAllChildItems(rootItem.getCode())
								.stream()
								.map(it -> Integer.valueOf(it.getCode()))
						)
						.sorted()
						.mapToInt(Integer::intValue)
						.toArray();

					final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

					final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
						new FacetSummaryComputationParams.Builder(
							session, productSchema, originalProductEntities,
							query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
						)
							.selectedFacetProvider(referenceName -> {
								if (Entities.CATEGORY.equals(referenceName)) {
									return selectedIds;
								} else {
									return ArrayUtils.EMPTY_INT_ARRAY;
								}
							})
							.build()
					);

					assertFacetSummary(expectedSummary, actualFacetSummary);
				}
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithPartialHierarchicalFacetSubTreeUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet including some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithPartialHierarchicalFacetSubTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenHaving(
										entityPrimaryKeyInSet(7)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSomeUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching hierarchical facet excluding some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSome(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenExcept(
										entityPrimaryKeyInSet(9)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSet} instead. Remove this method once FacetSummary is
	* removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					).build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithPriceFilter} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set when price filter is set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithPriceFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForPriceAndFacetInUserFilter} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when both price filter and facet selection are in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForPriceAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForAttributeInUserFilter} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when attribute filter is in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForAttributeInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedEntitiesPredicate(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryWithImpactForPriceAttributeAndFacetInUserFilter} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with impact when price, attribute and facet filters coexist in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryWithImpactForPriceAttributeAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
							facetHaving(Entities.STORE, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							)
							&& ofNullable((BigDecimal) product.getAttribute(ATTRIBUTE_QUANTITY))
								.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
								.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnSortedReferenceStatisticsByPredecessorAttribute} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return sorted facet statistics by predecessor attribute")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSortedFacetStatisticsByPredecessorAttribute(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(Locale.ENGLISH)
					),
					require(
						page(1, Integer.MAX_VALUE),
						facetSummaryOfReference(
							Entities.STORE,
							FacetStatisticsDepth.COUNTS,
							orderBy(
								attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
							),
							entityFetch(
								attributeContentAll()
							)
						),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, Collections.emptyMap()
					)
						.entityFilter(sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH))
						.facetSorterFactory(refName -> {
							if (Entities.STORE.equals(refName)) {
								return Comparator.comparingInt(
								o -> ArrayUtils.indexOf(o.getFacetEntity().getPrimaryKeyOrThrowException(), STORE_ORDER)
							);
							} else {
								return null;
							}
						})
						.allowedReferenceNames(() -> Set.of(Entities.STORE))
						.facetEntityRequirementSupplier(refName -> entityFetch(attributeContentAll()))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsWithFacetMatchingConditionInEntireSetUsingReferenceSummary} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching facets identified by filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithFacetMatchingConditionInEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		Map<Integer, SealedEntity> parameterIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(
								Entities.PARAMETER,
								attributeEqualsFalse(ATTRIBUTE_TRANSIENT),
								entityHaving(attributeLessThanEquals(ATTRIBUTE_CODE, "C"))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final int[] selectedFacets = parameterIndex.values()
					.stream()
					.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("C") < 0)
					.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
					.filter(
						facetId -> originalProductEntities.stream()
							.anyMatch(
								it -> it.getReference(Entities.PARAMETER, facetId)
									.map(ref -> Boolean.FALSE.equals(ref.getAttribute(ATTRIBUTE_TRANSIENT, Boolean.class)))
									.orElse(false)
							)
					)
					.toArray();

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.selectedFacetProvider(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return selectedFacets;
							} else {
								return new int[0];
							}
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForFilteredSet} instead. Remove this method once FacetSummary is
	* removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForFacetFilteredSet} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for facet filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFacetFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
							.orElse(false))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTree} instead. Remove this method once FacetSummary
	* is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] excludedSubTrees = {2, 10};
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(entityPrimaryKeyInSet(excludedSubTrees))
							),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5, 6, 7, 8)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8, 9))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);
				final Set<Integer> excluded = new HashSet<>(Arrays.asList(excludedSubTrees));

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> {
								final int categoryId = category.getReferencedPrimaryKey();
								final String categoryIdAsString = String.valueOf(categoryId);
								final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
								return
									// is not directly excluded node
									!excluded.contains(categoryId) &&
										// has no excluded parent node
										parentItems
											.stream()
											.map(it -> Integer.parseInt(it.getCode()))
											.noneMatch(excluded::contains) &&
										// has parent node 1
										(
											Objects.equals(1, categoryId) ||
												parentItems
													.stream()
													.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
										);
							}))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeWithStatistics} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						facetSummary(FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatistics} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int allParametersWithinOneGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 3, 11
				);
				final int parametersInDifferentGroupsResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 2, 3, 11
				);
				assertTrue(
					parametersInDifferentGroupsResult < allParametersWithinOneGroupResult,
					"When parameter from different group is selected - result count must decrease."
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation}
	* instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for hierarchy with statistics and inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.IMPACT),
						facetGroupsConjunction(Entities.STORE)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] facets = getParametersWithSameGroup(originalProductEntities, groups);

				assertEquals(1, groups.size(), "There should be only one group.");
				assertTrue(facets.length > 1, "There should be at least two facets.");

				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromSameGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When selecting multiple parameters from same group it should increase the result"
				);
				final Integer groupId = groups.iterator().next();
				final int singleParameterSelectedResultInverted = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0]
				);
				final int twoParametersFromSameGroupResultInverted = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResultInverted < singleParameterSelectedResultInverted,
					"When certain parameter group relation is inverted to AND, " +
					"selecting multiple parameters from it should decrease the result"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation} instead.
	* Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with inverted facet group relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] facets = Arrays.stream(getParametersWithDifferentGroups(originalProductEntities, new HashSet<>()))
					.limit(2)
					.toArray(Integer[]::new);
				final Integer[] groups = Arrays.stream(facets)
					.map(parameterGroupMapping::get)
					.distinct()
					.toArray(Integer[]::new);

				assertEquals(2, facets.length, "Number of facets must be exactly two.");
				assertEquals(facets.length, groups.length, "Number of facets and groups must be equal.");

				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromDifferentGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResult < singleParameterSelectedResult,
					"When selecting multiple facets from their groups should decrease the result"
				);
				final int singleParameterSelectedResultWithOr = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets[0]
				);
				final int twoParametersFromDifferentGroupResultWithOr = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResultWithOr > singleParameterSelectedResultWithOr,
					"When certain parameter group relation is inverted to OR, " +
					"selecting multiple facets from their groups should increase the result"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics " +
		"with negated meaning of group")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int facetId = 3;
				final int singleParameterSelectedResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facetId
				);
				final int twoParametersFromSameGroupResult = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(parameterGroupMapping.get(facetId)))),
					facetId
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When same parameter query is inverted to negative fashion, it must return more results"
				);
				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithGroupEntities} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with group entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithGroupEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.groupEntityRequirementSupplier(__ -> groupEntityRequirement)
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> true,
					groupEntity -> !groupEntity.getAttributeNames().isEmpty() &&
						(groupEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							groupEntity.getAttribute(ATTRIBUTE_CODE) != null)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFacetEntities} instead. Remove this method once
	* FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with facet entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFacetEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(__ -> facetEntityRequirement)
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty() &&
						(facetEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null),
					groupEntity -> true
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithParameterEntities} instead. Remove this method
	* once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with parameter entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithParameterEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntitySetWithFacetEntitiesForParameters} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with facet entities for parameters")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntitySetWithFacetEntitiesForParameters(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.COUNTS),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacets} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with filtered and ordered facets")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacets(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (Entities.PARAMETER.equals(reference.getReferenceName())) {
								final SealedEntity parameter = parameterIndex.get(reference.getReferencedPrimaryKey());
								return parameter.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
									parameter.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null;
							} else {
								return true;
							}
						})
						.facetSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterIndex.get(o1.getFacetEntity().getPrimaryKey());
									final SealedEntity parameter2 = parameterIndex.get(o2.getFacetEntity().getPrimaryKey());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getFacetEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacetGroups} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with filtered and ordered facet groups")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacetGroups(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterGroupBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (reference.getReferenceKey().referenceName().equals(Entities.PARAMETER)) {
								return reference.getGroup()
									.map(groupRef -> parameterGroupIndex.get(groupRef.getPrimaryKey()))
									.map(group -> group.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
										group.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
									.orElse(false);
							} else {
								return true;
							}
						})
						.facetGroupSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterGroupIndex.get(o1.getGroupEntity().getPrimaryKeyOrThrowException());
									final SealedEntity parameter2 = parameterGroupIndex.get(o2.getGroupEntity().getPrimaryKeyOrThrowException());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getGroupEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with complex entity requirements with only default " +
		"requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						facetSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint carries no entity fetch of its own and inherits none
						// from the generic summary, so its reference comes back as a bare entity reference
						.facetEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_CODE) != null,
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntitiesAreBareReferences(actualFacetSummary, Entities.CATEGORY);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirements} instead. Remove this
	* method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return facet summary for entire set with complex entity requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						facetSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)),
							entityGroupFetch()
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint governs its reference entirely - only the attributes
						// it asks for itself reach it, the generic summary's `code` is not united into them
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
							}
							return entityFetch(attributeContent(ATTRIBUTE_CODE));
						})
						.groupEntityRequirementSupplier(referenceName -> referenceName.equals(Entities.CATEGORY)
							? entityGroupFetch()
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> {
						if (facetEntity.getType().equals(Entities.CATEGORY)) {
							return facetEntity.getAttributeNames().size() == 1 &&
								facetEntity.getAttribute(ATTRIBUTE_NAME) != null;
						}
						return facetEntity.getAttributeNames().size() == 1 &&
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null;
					},
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntityAttributeNames(actualFacetSummary, Entities.CATEGORY, Set.of(ATTRIBUTE_NAME));

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link
	* #shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSetUsingReferenceSummary} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products matching group EXCLUSIVE combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetGroupsExclusivity(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(1))),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final Predicate<SealedEntity> entityPredicate = sealedEntity -> sealedEntity
					.getReferences(Entities.PARAMETER)
					.stream()
					.filter(ref -> parameterIndex.contains(ref.getReferencedPrimaryKey()))
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.distinct()
					.count() == 2;

				assertResultIs(
					"Querying " + Entities.PRODUCT + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					entityPredicate,
					result.getRecordData()
				);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	* @deprecated Use {@link #shouldReturnProductsUsingDifferentDefaultFacetRulesUsingReferenceSummary} instead. Remove
	* this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	@SuppressWarnings("deprecation")
	@DisplayName("Should return products using different default facet rules")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingDifferentDefaultFacetRules(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetCalculationRules(
							FacetRelationType.CONJUNCTION,
							FacetRelationType.EXCLUSIVITY
						),
						facetSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	/**
	 * Asserts facet summary against expected without full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(@Nonnull FacetSummaryWithResultCount expectedSummary,
	                                @Nullable FacetSummary actualFacetSummary) {
		assertFacetSummary(
			expectedSummary,
			actualFacetSummary,
			__ -> true,
			__ -> true
		);
	}

	/**
	 * Asserts facet summary against expected with full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(
		@Nonnull FacetSummaryWithResultCount expectedSummary,
		@Nullable FacetSummary actualFacetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		assertFacetSummary(
			expectedSummary, actualFacetSummary, facetEntitiesAssertFunction, groupEntitiesAssertFunction,
			__ -> "", __ -> ""
		);
	}

	/**
	 * Asserts facet summary against expected with full entity data asserts.
	 *
	 * @deprecated Use {@link #assertReferenceSummary} instead. Remove this method once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummary(
		@Nonnull FacetSummaryWithResultCount expectedSummary,
		@Nullable FacetSummary actualFacetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction,
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		assertNotNull(actualFacetSummary);
		assertFalse(actualFacetSummary.getReferenceStatistics().isEmpty());
		assertEquals(
			new FacetSummaryToStringWrapper(expectedSummary.facetSummary(), groupRenderer, facetRenderer),
			new FacetSummaryToStringWrapper(actualFacetSummary, groupRenderer, facetRenderer),
			"Filtered entity count: " + expectedSummary.entityCount()
		);
		assertFacetSummaryEntities(
			actualFacetSummary,
			facetEntitiesAssertFunction,
			groupEntitiesAssertFunction
		);
	}

	/**
	 * Checks all group and facet entities and verifies them. This method expects that both actual facet summary and
	 * expected facet summary are equal.
	 * @deprecated Use {@link #assertReferenceSummaryEntities} instead. Remove this method once FacetSummary is removed.
	 *
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private void assertFacetSummaryEntities(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		facetSummary.getReferenceStatistics().forEach(actualFacetGroupStatistics -> {
			if (actualFacetGroupStatistics.getGroupEntity() != null &&
				actualFacetGroupStatistics.getGroupEntity() instanceof final SealedEntity groupEntity) {
				assertTrue(groupEntitiesAssertFunction.apply(groupEntity));
			}
			actualFacetGroupStatistics.getFacetStatistics().forEach(actualFacetStatistics -> {
				if (actualFacetStatistics.getFacetEntity() instanceof final SealedEntity facetEntity) {
					assertTrue(facetEntitiesAssertFunction.apply(facetEntity));
				}
			});
		});
	}

	/**
	 * Asserts that every facet entity the summary holds for the passed reference came back as a **bare entity
	 * reference**. A reference-specific summary constraint governs the reference it names entirely, so a reference
	 * whose constraint carries no `entityFetch` of its own fetches no body - not even the one the generic summary
	 * written beside it asks for.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the reference whose facet entities are examined
	 */
	private static void assertFacetEntitiesAreBareReferences(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		int examinedFacets = 0;
		for (final ReferenceGroupStatistics groupStatistics : summary.getReferenceStatistics()) {
			if (!referenceName.equals(groupStatistics.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facetStatistics : groupStatistics.getFacetStatistics()) {
				assertInstanceOf(
					EntityReference.class,
					facetStatistics.getFacetEntity(),
					"Facet entity of reference `" + referenceName + "` inherited the entity fetch of the generic summary!"
				);
				examinedFacets++;
			}
		}
		assertTrue(examinedFacets > 0, "Summary holds no facet for reference `" + referenceName + "`!");
	}

	/**
	 * Asserts that every facet entity the summary holds for the passed reference carries exactly the passed
	 * attributes - neither more (the generic summary's attributes are not united into the reference-specific fetch)
	 * nor fewer (the reference-specific fetch reaches the facet entity untouched).
	 *
	 * @param summary                 summary returned by the query
	 * @param referenceName           name of the reference whose facet entities are examined
	 * @param expectedAttributeNames  the attribute names the facet entities are expected to carry
	 */
	private static void assertFacetEntityAttributeNames(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		@Nonnull Set<String> expectedAttributeNames
	) {
		int examinedFacets = 0;
		for (final ReferenceGroupStatistics groupStatistics : summary.getReferenceStatistics()) {
			if (!referenceName.equals(groupStatistics.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facetStatistics : groupStatistics.getFacetStatistics()) {
				final SealedEntity facetEntity = assertInstanceOf(
					SealedEntity.class,
					facetStatistics.getFacetEntity(),
					"The body of the `" + referenceName + "` facet entity was not fetched!"
				);
				assertEquals(
					expectedAttributeNames,
					facetEntity.getAttributeNames(),
					"Facet entity of reference `" + referenceName + "` does not carry exactly the attributes its own " +
						"summary constraint asked for!"
				);
				examinedFacets++;
			}
		}
		assertTrue(examinedFacets > 0, "Summary holds no facet for reference `" + referenceName + "`!");
	}

	/**
	 * Simplification method that executes query with facet computation and returns how many record matches the query
	 * that filters over input parameter facet ids.
	 * @deprecated Use {@link #queryParameterReferences} instead. Remove this method once FacetSummary is removed.
	 *
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private int queryParameterFacets(
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		EvitaSessionContract session,
		RequireConstraint additionalRequirement,
		Integer... facetIds
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				and(
					userFilter(
						facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(facetIds))
					)
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				facetSummary(FacetStatisticsDepth.IMPACT),
				additionalRequirement
			)
		);
		final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
		final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

		final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
			new FacetSummaryComputationParams.Builder(
				session, productSchema, originalProductEntities,
				query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
			).build()
		);

		assertEquals(expectedSummary.entityCount(), result.getTotalRecordCount());
		assertFacetSummary(expectedSummary, actualFacetSummary);

		return result.getTotalRecordCount();
	}


	private record GroupReferenceWithEntityId(
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int entityId
	) {
	}

	/**
	 * @deprecated Use {@link #ReferenceSummaryWithResultCount} instead. Remove this record once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private record FacetSummaryWithResultCount(int entityCount, FacetSummary facetSummary) {
	}

	/**
	 * @deprecated Use {@link #ReferenceSummaryToStringWrapper} instead. Remove this record once FacetSummary is removed.
	 */
	@Deprecated(since = "2026.2", forRemoval = true)
	private record FacetSummaryToStringWrapper(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {

		@Nonnull
		@Override
		public String toString() {
			//noinspection unchecked
			return this.facetSummary.prettyPrint(
				(Function<ReferenceGroupStatistics, String>) (Function<?, ?>) this.groupRenderer, this.facetRenderer
			);
		}

	}


	// region Reference summary tests (using referenceSummary constraint)

	/**
	 * Wrapper around {@link #computeFacetSummary(FacetSummaryComputationParams)} that returns
	 * {@link ReferenceSummaryWithResultCount} instead of {@link FacetSummaryWithResultCount}.
	 * The intermediate {@link FacetSummary} is repackaged into a strict {@link ReferenceSummary}
	 * instance and every {@link FacetGroupStatistics} element is rebuilt as a plain
	 * {@link ReferenceGroupStatistics} — {@link ReferenceGroupStatistics#equals} is
	 * {@code getClass()}-strict, and the canonical {@code referenceSummary()} constraint emits
	 * plain {@link ReferenceGroupStatistics}, so the expected side must match that runtime type.
	 */
	private static ReferenceSummaryWithResultCount computeReferenceSummary(
		@Nonnull FacetSummaryComputationParams params
	) {
		final FacetSummaryWithResultCount result = computeFacetSummary(params);
		final Collection<ReferenceGroupStatistics> rebuilt = result.facetSummary()
			.getReferenceStatistics()
			.stream()
			.map(src -> new ReferenceGroupStatistics(
				src.getReferenceName(),
				src.getGroupEntity(),
				src.getCount(),
				src.getFacetStatistics()
					.stream()
					.collect(
						toMap(
							fs -> fs.getFacetEntity().getPrimaryKey(),
							Function.identity(),
							(a, b) -> {
								throw new IllegalStateException(
									"Duplicate facet statistics for primary key " +
										a.getFacetEntity().getPrimaryKey()
								);
							},
							LinkedHashMap::new
						)
					)
			))
			.toList();
		return new ReferenceSummaryWithResultCount(result.entityCount(), new ReferenceSummary(rebuilt));
	}

	/**
	 * Asserts reference summary against expected without full entity data asserts.
	 */
	private void assertReferenceSummary(@Nonnull ReferenceSummaryWithResultCount expectedSummary,
	                                    @Nullable ReferenceSummary actualReferenceSummary) {
		assertReferenceSummary(
			expectedSummary,
			actualReferenceSummary,
			__ -> true,
			__ -> true
		);
	}

	/**
	 * Asserts reference summary against expected with full entity data asserts.
	 */
	private void assertReferenceSummary(
		@Nonnull ReferenceSummaryWithResultCount expectedSummary,
		@Nullable ReferenceSummary actualReferenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		assertReferenceSummary(
			expectedSummary, actualReferenceSummary, facetEntitiesAssertFunction, groupEntitiesAssertFunction,
			__ -> "", __ -> ""
		);
	}

	/**
	 * Asserts reference summary against expected with full entity data asserts.
	 */
	private void assertReferenceSummary(
		@Nonnull ReferenceSummaryWithResultCount expectedSummary,
		@Nullable ReferenceSummary actualReferenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction,
		@Nonnull Function<ReferenceGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {
		assertNotNull(actualReferenceSummary);
		assertFalse(actualReferenceSummary.getReferenceStatistics().isEmpty());
		assertEquals(
			new ReferenceSummaryToStringWrapper(expectedSummary.referenceSummary(), groupRenderer, facetRenderer),
			new ReferenceSummaryToStringWrapper(actualReferenceSummary, groupRenderer, facetRenderer),
			"Filtered entity count: " + expectedSummary.entityCount()
		);
		assertReferenceSummaryEntities(
			actualReferenceSummary,
			facetEntitiesAssertFunction,
			groupEntitiesAssertFunction
		);
	}

	/**
	 * Checks all group and facet entities and verifies them. This method expects that both actual reference summary and
	 * expected reference summary are equal.
	 */
	private void assertReferenceSummaryEntities(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		referenceSummary.getReferenceStatistics().forEach(actualReferenceGroupStatistics -> {
			if (actualReferenceGroupStatistics.getGroupEntity() != null &&
				actualReferenceGroupStatistics.getGroupEntity() instanceof final SealedEntity groupEntity) {
				assertTrue(groupEntitiesAssertFunction.apply(groupEntity));
			}
			actualReferenceGroupStatistics.getFacetStatistics().forEach(actualFacetStatistics -> {
				if (actualFacetStatistics.getFacetEntity() instanceof final SealedEntity facetEntity) {
					assertTrue(facetEntitiesAssertFunction.apply(facetEntity));
				}
			});
		});
	}

	/**
	 * Simplification method that executes query with reference summary computation and returns how many record matches
	 * the query that filters over input parameter facet ids.
	 */
	private int queryParameterReferences(
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		EvitaSessionContract session,
		RequireConstraint additionalRequirement,
		Integer... facetIds
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				and(
					userFilter(
						facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(facetIds))
					)
				)
			),
			require(
				page(1, Integer.MAX_VALUE),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
				referenceSummary(FacetStatisticsDepth.IMPACT),
				additionalRequirement
			)
		);
		final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
		final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

		final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
			new FacetSummaryComputationParams.Builder(
				session, productSchema, originalProductEntities,
				query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
			).build()
		);

		assertEquals(expectedSummary.entityCount(), result.getTotalRecordCount());
		assertReferenceSummary(expectedSummary, actualReferenceSummary);

		return result.getTotalRecordCount();
	}

	@DisplayName("Should throw exception when accessing localized attributes on fetched entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesUsingReferenceSummary(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								referenceSummary(
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing localized attributes on fetched entities using explicit reference")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldThrowExceptionWhenAccessingLocalizedAttributesOnFetchedEntitiesOnExplicitReferenceUsingReferenceSummary(
			Evita evita
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								referenceSummaryOfReference(
									Entities.PARAMETER,
									FacetStatisticsDepth.COUNTS,
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
									)
								),
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should not return reference summary for missing references on product")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldNotReturnReferenceSummaryForMissingReferencesOnProduct(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(referenceHaving(Entities.BRAND))
						),
						require(
							page(1, 1),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(referenceContent(Entities.BRAND)),
							referenceSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.COUNTS
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertTrue(result.getRecordData().get(0).getReferences(Entities.BRAND).isEmpty());
				assertNull(result.getExtraResult(ReferenceSummary.class).getReferenceGroupStatistics(Entities.BRAND));
				return null;
			}
		);
	}

	@DisplayName("Should return empty reference summary for empty collection")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test()
	void shouldReturnEmptyReferenceSummaryForEmptyCollection(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummaryOfReference(
								EMPTY_COLLECTION_ENTITY,
								FacetStatisticsDepth.COUNTS,
								filterBy(
									referenceHaving(
										Entities.PARAMETER,
										filterBy(
											entityHaving(entityPrimaryKeyInSet(1))
										)
									)
								)
							)
						)
					),
					EntityReference.class
				);

				final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(referenceSummary);
				assertTrue(referenceSummary.getReferenceStatistics().isEmpty());
				return null;
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet including all its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
					final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
					final Integer[] facetIds = new Integer[]{hierarchyRoot};

					final Query query = query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								userFilter(
									facetHaving(
										Entities.CATEGORY,
										entityPrimaryKeyInSet(facetIds),
										includingChildren()
									)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummary(FacetStatisticsDepth.IMPACT)
						)
					);

					final EvitaResponse<EntityReference> result = session.query(
						query,
						EntityReference.class
					);

					assertResultIs(
						"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
						originalProductEntities,
						sealedEntity -> {
							// is within requested hierarchy
							return sealedEntity.getReferences(Entities.CATEGORY)
								.stream()
								.anyMatch(it -> it.getReferencedPrimaryKey() == hierarchyRoot ||
									categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.anyMatch(catId -> hierarchyRoot == Integer.parseInt(catId.getCode()))
								);
						},
						result.getRecordData()
					);

					final int[] selectedIds = Stream.concat(
							Arrays.stream(facetIds),
							categoryHierarchy.getAllChildItems(rootItem.getCode())
								.stream()
								.map(it -> Integer.valueOf(it.getCode()))
						)
						.sorted()
						.mapToInt(Integer::intValue)
						.toArray();

					final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

					final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
						new FacetSummaryComputationParams.Builder(
							session, productSchema, originalProductEntities,
							query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
						)
							.selectedFacetProvider(referenceName -> {
								if (Entities.CATEGORY.equals(referenceName)) {
									return selectedIds;
								} else {
									return ArrayUtils.EMPTY_INT_ARRAY;
								}
							})
							.build()
					);

					assertReferenceSummary(expectedSummary, actualReferenceSummary);
				}
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet including some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithPartialHierarchicalFacetSubTreeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenHaving(
										entityPrimaryKeyInSet(7)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet excluding some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSomeUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem rootItem = categoryHierarchy.getItem("3");
				final int hierarchyRoot = Integer.parseInt(rootItem.getCode());
				final Integer[] facetIds = new Integer[]{hierarchyRoot};

				final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
				assertEquals(2, childItems.size());
				assertArrayEquals(new int[]{7, 9}, childItems.stream().mapToInt(it -> Integer.parseInt(it.getCode())).toArray());

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(facetIds),
									includingChildrenExcept(
										entityPrimaryKeyInSet(9)
									)
								)
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				assertResultIs(
					"Querying products with selected root category " + rootItem.getCode() + ": " + Arrays.toString(facetIds),
					originalProductEntities,
					isWithinHierarchyExcluding(categoryHierarchy, hierarchyRoot, 9),
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedFacetProvider(selectedFacetProviderFor(Entities.CATEGORY, selectedIds))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
			}
		);
	}

	@DisplayName("Should return reference summary for entire set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					).build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set when price filter is set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithPriceFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when both price filter and facet selection are in user " +
		"filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForPriceAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when attribute filter is in user filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForAttributeInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.selectedEntitiesPredicate(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with impact when price, attribute and facet filters coexist in user " +
		"filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryWithImpactForPriceAttributeAndFacetInUserFilter(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						userFilter(
							priceBetween(from, to),
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
							facetHaving(Entities.STORE, entityPrimaryKeyInSet(2))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(product -> product.getPriceForSale(
								CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							).isPresent())
						.selectedEntitiesPredicate(product -> product.hasPriceInInterval(
								from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC
							)
							&& ofNullable((BigDecimal) product.getAttribute(ATTRIBUTE_QUANTITY))
								.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
								.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return sorted reference statistics by predecessor attribute")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSortedReferenceStatisticsByPredecessorAttribute(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(Locale.ENGLISH)
					),
					require(
						page(1, Integer.MAX_VALUE),
						referenceSummaryOfReference(
							Entities.STORE,
							FacetStatisticsDepth.COUNTS,
							orderBy(
								attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
							),
							entityFetch(
								attributeContentAll()
							)
						),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(
					query,
					EntityReference.class
				);

				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, Collections.emptyMap()
					)
						.entityFilter(sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH))
						.facetSorterFactory(refName -> {
							if (Entities.STORE.equals(refName)) {
								return Comparator.comparingInt(
								o -> ArrayUtils.indexOf(o.getFacetEntity().getPrimaryKeyOrThrowException(), STORE_ORDER)
							);
							} else {
								return null;
							}
						})
						.allowedReferenceNames(() -> Set.of(Entities.STORE))
						.facetEntityRequirementSupplier(refName -> entityFetch(attributeContentAll()))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products matching facets identified by filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithFacetMatchingConditionInEntireSetUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping,
		Map<Integer, SealedEntity> parameterIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(
								Entities.PARAMETER,
								attributeEqualsFalse(ATTRIBUTE_TRANSIENT),
								entityHaving(attributeLessThanEquals(ATTRIBUTE_CODE, "C"))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final int[] selectedFacets = parameterIndex.values()
					.stream()
					.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("C") < 0)
					.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
					.filter(
						facetId -> originalProductEntities.stream()
							.anyMatch(
								it -> it.getReference(Entities.PARAMETER, facetId)
									.map(ref -> Boolean.FALSE.equals(ref.getAttribute(ATTRIBUTE_TRANSIENT, Boolean.class)))
									.orElse(false)
							)
					)
					.toArray();

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.selectedFacetProvider(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return selectedFacets;
							} else {
								return new int[0];
							}
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						attributeGreaterThan(ATTRIBUTE_QUANTITY, 970)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for facet filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForFacetFilteredSet(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 950),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(2)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
							.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
							.orElse(false))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTree(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] excludedSubTrees = {2, 10};
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(entityPrimaryKeyInSet(excludedSubTrees))
							),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5, 6, 7, 8)),
								facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8, 9))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary()
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);
				final Set<Integer> excluded = new HashSet<>(Arrays.asList(excludedSubTrees));

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> {
								final int categoryId = category.getReferencedPrimaryKey();
								final String categoryIdAsString = String.valueOf(categoryId);
								final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
								return
									// is not directly excluded node
									!excluded.contains(categoryId) &&
										// has no excluded parent node
										parentItems
											.stream()
											.map(it -> Integer.parseInt(it.getCode()))
											.noneMatch(excluded::contains) &&
										// has parent node 1
										(
											Objects.equals(1, categoryId) ||
												parentItems
													.stream()
													.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
										);
							}))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.BRAND, entityPrimaryKeyInSet(1)),
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						referenceSummary(FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatistics(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int allParametersWithinOneGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 3, 11
				);
				final int parametersInDifferentGroupsResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, 2, 3, 11
				);
				assertTrue(
					parametersInDifferentGroupsResult < allParametersWithinOneGroupResult,
					"When parameter from different group is selected - result count must decrease."
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for hierarchy with statistics and inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Hierarchy categoryHierarchy,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)),
							userFilter(
								facetHaving(Entities.STORE, entityPrimaryKeyInSet(5))
							)
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.IMPACT),
						facetGroupsConjunction(Entities.STORE)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.entityFilter(sealedEntity -> sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)))
						.build()
				);

				assertReferenceSummary(expectedSummary, actualReferenceSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> groups = new HashSet<>();
				final Integer[] facets = getParametersWithSameGroup(originalProductEntities, groups);

				assertEquals(1, groups.size(), "There should be only one group.");
				assertTrue(facets.length > 1, "There should be at least two facets.");

				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromSameGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When selecting multiple parameters from same group it should increase the result"
				);
				final Integer groupId = groups.iterator().next();
				final int singleParameterSelectedResultInverted = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0]
				);
				final int twoParametersFromSameGroupResultInverted = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResultInverted < singleParameterSelectedResultInverted,
					"When certain parameter group relation is inverted to AND, " +
					"selecting multiple parameters from it should decrease the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with inverted facet group relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] facets = Arrays.stream(getParametersWithDifferentGroups(originalProductEntities, new HashSet<>()))
					.limit(2)
					.toArray(Integer[]::new);
				final Integer[] groups = Arrays.stream(facets)
					.map(parameterGroupMapping::get)
					.distinct()
					.toArray(Integer[]::new);

				assertEquals(2, facets.length, "Number of facets must be exactly two.");
				assertEquals(facets.length, groups.length, "Number of facets and groups must be equal.");

				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets[0]
				);
				final int twoParametersFromDifferentGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResult < singleParameterSelectedResult,
					"When selecting multiple facets from their groups should decrease the result"
				);
				final int singleParameterSelectedResultWithOr = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets[0]
				);
				final int twoParametersFromDifferentGroupResultWithOr = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsDisjunction(
						Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))
					), facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResultWithOr > singleParameterSelectedResultWithOr,
					"When certain parameter group relation is inverted to OR, " +
					"selecting multiple facets from their groups should increase the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary with parameter selection for hierarchy with statistics " +
		"with negated meaning of group")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int facetId = 3;
				final int singleParameterSelectedResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session, null, facetId
				);
				final int twoParametersFromSameGroupResult = queryParameterReferences(
					productSchema, originalProductEntities, parameterGroupMapping, session,
					facetGroupsNegation(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(parameterGroupMapping.get(facetId)))),
					facetId
				);
				assertTrue(
					twoParametersFromSameGroupResult > singleParameterSelectedResult,
					"When same parameter query is inverted to negative fashion, it must return more results"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with group entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithGroupEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.groupEntityRequirementSupplier(__ -> groupEntityRequirement)
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> true,
					groupEntity -> !groupEntity.getAttributeNames().isEmpty() &&
						(groupEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							groupEntity.getAttribute(ATTRIBUTE_CODE) != null)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with facet entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFacetEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE)
				);

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(__ -> facetEntityRequirement)
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty() &&
						(facetEntity.getAttribute(ATTRIBUTE_NAME) != null ||
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null),
					groupEntity -> true
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with parameter entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithParameterEntities(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with facet entities for parameters")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntitySetWithFacetEntitiesForParameters(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(
					attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)
				);
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch();

				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.COUNTS),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							facetEntityRequirement,
							groupEntityRequirement
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, __ -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return facetEntityRequirement;
							}
							return null;
						})
						.groupEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.PARAMETER)) {
								return groupEntityRequirement;
							}
							return null;
						})
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_NAME) != null,
					groupEntity -> !groupEntity.attributesAvailable()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with filtered and ordered facets")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacets(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (Entities.PARAMETER.equals(reference.getReferenceName())) {
								final SealedEntity parameter = parameterIndex.get(reference.getReferencedPrimaryKey());
								return parameter.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
									parameter.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null;
							} else {
								return true;
							}
						})
						.facetSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterIndex.get(o1.getFacetEntity().getPrimaryKey());
									final SealedEntity parameter2 = parameterIndex.get(o2.getFacetEntity().getPrimaryKey());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getFacetEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with filtered and ordered facet groups")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithFilteredAndOrderedFacetGroups(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> parameterGroupIndex,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						entityLocaleEquals(CZECH_LOCALE)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.COUNTS,
							filterGroupBy(attributeLessThanEquals(ATTRIBUTE_CODE, "K")),
							orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
							// the reference-specific constraint governs `parameter` entirely, so it has to repeat the
							// bodies the generic summary asks for - nothing is inherited from it
							entityFetch(entityFetchAllContent()),
							entityGroupFetch(entityFetchAllContent())
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.COUNTS, parameterGroupMapping
					)
						.entityFilter(entity -> entity.getLocales().contains(CZECH_LOCALE))
						.referencePredicate(reference -> {
							if (reference.getReferenceKey().referenceName().equals(Entities.PARAMETER)) {
								return reference.getGroup()
									.map(groupRef -> parameterGroupIndex.get(groupRef.getPrimaryKey()))
									.map(group -> group.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
										group.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
									.orElse(false);
							} else {
								return true;
							}
						})
						.facetGroupSorterFactory(referenceName -> {
							if (Entities.PARAMETER.equals(referenceName)) {
								return (o1, o2) -> {
									final SealedEntity parameter1 = parameterGroupIndex.get(o1.getGroupEntity().getPrimaryKeyOrThrowException());
									final SealedEntity parameter2 = parameterGroupIndex.get(o2.getGroupEntity().getPrimaryKeyOrThrowException());
									// reversed order
									return parameter2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
										.compareTo(parameter1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
								};
							} else {
								return Comparator.comparingInt(o -> o.getGroupEntity().getPrimaryKeyOrThrowException());
							}
						})
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> !facetEntity.getAttributeNames().isEmpty(),
					groupEntity -> !groupEntity.getAttributeNames().isEmpty(),
					groupStatistics -> ofNullable(groupStatistics.getGroupEntity())
						.map(it -> ((SealedEntity) it).getAttribute(ATTRIBUTE_CODE, String.class))
						.orElse(""),
					facetStatistics -> ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with complex entity requirements with only default " +
		"requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						referenceSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint carries no entity fetch of its own and inherits none
						// from the generic summary, so its reference comes back as a bare entity reference
						.facetEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> Entities.CATEGORY.equals(referenceName)
							? null
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_CODE) != null,
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntitiesAreBareReferences(actualReferenceSummary, Entities.CATEGORY);

				return null;
			}
		);
	}

	@DisplayName("Should return reference summary for entire set with complex entity requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnReferenceSummaryForEntireSetWithComplexEntityRequirements(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.CATEGORY, entityPrimaryKeyInSet(8))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						),
						referenceSummaryOfReference(
							Entities.CATEGORY,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE)),
							entityGroupFetch()
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return FacetStatisticsDepth.IMPACT;
							}
							return FacetStatisticsDepth.COUNTS;
						}, parameterGroupMapping
					)
						// the reference-specific constraint governs its reference entirely - only the attributes
						// it asks for itself reach it, the generic summary's `code` is not united into them
						.facetEntityRequirementSupplier(referenceName -> {
							if (referenceName.equals(Entities.CATEGORY)) {
								return entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
							}
							return entityFetch(attributeContent(ATTRIBUTE_CODE));
						})
						.groupEntityRequirementSupplier(referenceName -> referenceName.equals(Entities.CATEGORY)
							? entityGroupFetch()
							: entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary,
					facetEntity -> {
						if (facetEntity.getType().equals(Entities.CATEGORY)) {
							return facetEntity.getAttributeNames().size() == 1 &&
								facetEntity.getAttribute(ATTRIBUTE_NAME) != null;
						}
						return facetEntity.getAttributeNames().size() == 1 &&
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null;
					},
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);
				assertFacetEntityAttributeNames(actualReferenceSummary, Entities.CATEGORY, Set.of(ATTRIBUTE_NAME));

				return null;
			}
		);
	}

	@DisplayName("Should return products matching group EXCLUSIVE combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSetUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);
				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetGroupsExclusivity(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(1))),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final Predicate<SealedEntity> entityPredicate = sealedEntity -> sealedEntity
					.getReferences(Entities.PARAMETER)
					.stream()
					.filter(ref -> parameterIndex.contains(ref.getReferencedPrimaryKey()))
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.distinct()
					.count() == 2;

				assertResultIs(
					"Querying " + Entities.PRODUCT + " facets: " + Arrays.toString(parameters),
					originalProductEntities,
					entityPredicate,
					result.getRecordData()
				);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products using different default facet rules")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingDifferentDefaultFacetRulesUsingReferenceSummary(
		Evita evita,
		EntitySchemaContract productSchema,
		List<SealedEntity> originalProductEntities,
		Map<Integer, Integer> parameterGroupMapping
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AtomicInteger eachOther = new AtomicInteger(0);
				final Integer[] parameters = Arrays.stream(getParametersInGroups(originalProductEntities, Set.of(1, 2)))
					.filter(it -> eachOther.getAndIncrement() % 2 == 0)
					.toArray(Integer[]::new);
				final Set<Integer> parameterIndex = Set.of(parameters);

				final Query query = query(
					collection(Entities.PRODUCT),
					filterBy(
						userFilter(
							facetHaving(Entities.PARAMETER, entityPrimaryKeyInSet(parameters))
						)
					),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetCalculationRules(
							FacetRelationType.CONJUNCTION,
							FacetRelationType.EXCLUSIVITY
						),
						referenceSummaryOfReference(
							Entities.PARAMETER,
							FacetStatisticsDepth.IMPACT,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
						)
					)
				);

				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final ReferenceSummary actualReferenceSummary = result.getExtraResult(ReferenceSummary.class);

				final ReferenceSummaryWithResultCount expectedSummary = computeReferenceSummary(
					new FacetSummaryComputationParams.Builder(
						session, productSchema, originalProductEntities,
						query, referenceName -> FacetStatisticsDepth.IMPACT, parameterGroupMapping
					)
						.allowedReferenceNames(() -> Set.of(Entities.PARAMETER))
						.facetEntityRequirementSupplier(referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)))
						.groupEntityRequirementSupplier(referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)))
						.build()
				);

				assertReferenceSummary(
					expectedSummary,
					actualReferenceSummary
				);

				return null;
			}
		);
	}

	// endregion

	/**
	 * Wraps a computed reference summary together with the count of entities matching the filter.
	 */
	private record ReferenceSummaryWithResultCount(int entityCount, ReferenceSummary referenceSummary) {
	}

	/**
	 * Helper wrapper that enables comparing reference summaries via their pretty-printed string representation.
	 */
	private record ReferenceSummaryToStringWrapper(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull Function<ReferenceGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.referenceSummary.prettyPrint(this.groupRenderer, this.facetRenderer);
		}

	}
}
