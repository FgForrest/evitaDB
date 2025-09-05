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

package io.evitadb.api.functional.facet;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryUtils.findConstraints;
import static io.evitadb.api.query.QueryUtils.findRequire;
import static io.evitadb.api.query.QueryUtils.findRequires;
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

/**
 * This test verifies whether entities can be filtered by facets.
 *
 * TOBEDONE JNO - add tests that contains also priceBetween / other attribute filter inside user filter and check that
 * they're affecting impact counts
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public abstract class AbstractEntityByFacetFilteringFunctionalTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_ORDER = "order";
	private static final String THOUSAND_PRODUCTS_WITH_FACETS = "ThousandsProductsWithFacets";
	private static final String ATTRIBUTE_TRANSIENT = "transient";
	private static final int SEED = 40;
	private static final String EMPTY_COLLECTION_ENTITY = "someCollectionWithoutEntities";
	private final static int[] STORE_ORDER;
	private static final int STORE_COUNT = 12;

	static {
		STORE_ORDER = new int[STORE_COUNT];
		for (int i = 1; i <= STORE_COUNT; i++) {
			STORE_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), STORE_ORDER, STORE_COUNT);
	}

	/**
	 * Computes facet summary by streamed fashion.
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nonnull Query query,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping
	) {
		return computeFacetSummary(
			session, schema, entities, entityFilter, null, null, null,
			query, allowedReferenceNames,
			statisticsDepthSupplier, facetEntityRequirementSupplier,
			groupEntityRequirementSupplier,
			parameterGroupMapping
		);
	}

	/**
	 * Computes facet summary by streamed fashion.
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nullable Predicate<ReferenceContract> referencePredicate,
		@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nonnull Query query,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping
	) {
		return computeFacetSummary(
			session, schema, entities, entityFilter, referencePredicate, facetSorterFactory, facetGroupSorterFactory,
			query, allowedReferenceNames, statisticsDepthSupplier,
			facetEntityRequirementSupplier, groupEntityRequirementSupplier,
			parameterGroupMapping, null, null
		);
	}

	/**
	 * Computes facet summary by streamed fashion.
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nullable Predicate<ReferenceContract> referencePredicate,
		@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nonnull Query query,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping,
		@Nullable Function<String, int[]> selectedFacetProvider
	) {
		return computeFacetSummary(
			session, schema, entities, entityFilter, referencePredicate, facetSorterFactory, facetGroupSorterFactory,
			query, allowedReferenceNames, statisticsDepthSupplier,
			facetEntityRequirementSupplier, groupEntityRequirementSupplier,
			parameterGroupMapping, selectedFacetProvider, null
		);
	}

	/**
	 * Computes facet summary by streamed fashion.
	 */
	private static FacetSummaryWithResultCount computeFacetSummary(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<SealedEntity> entities,
		@Nullable Predicate<SealedEntity> entityFilter,
		@Nullable Predicate<ReferenceContract> referencePredicate,
		@Nullable Function<String, Comparator<FacetStatistics>> facetSorterFactory,
		@Nullable Function<String, Comparator<FacetGroupStatistics>> facetGroupSorterFactory,
		@Nonnull Query query,
		@Nullable Supplier<Set<String>> allowedReferenceNames,
		@Nonnull Function<String, FacetStatisticsDepth> statisticsDepthSupplier,
		@Nullable Function<String, EntityFetch> facetEntityRequirementSupplier,
		@Nullable Function<String, EntityGroupFetch> groupEntityRequirementSupplier,
		@Nonnull Map<Integer, Integer> parameterGroupMapping,
		@Nullable Function<String, int[]> selectedFacetProvider,
		@Nullable Predicate<SealedEntity> selectedEntitiesPredicate
	) {
		// this context allows us to create facet filtering predicates in correct way
		final FacetComputationalContext fcc = new FacetComputationalContext(
			schema, query, parameterGroupMapping, selectedFacetProvider
		);

		// filter entities by mandatory predicate
		final List<SealedEntity> filteredEntities = ofNullable(entityFilter)
			.map(it -> entities.stream().filter(it))
			.orElseGet(entities::stream)
			.toList();

		// collect set of faceted reference types
		final Set<String> facetedEntities = schema.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.map(ReferenceSchemaContract::getReferencedEntityType)
			.collect(Collectors.toSet());

		// group facets by their entity type / group
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
						schema.getReference(it.getReferenceName()).orElseThrow(),
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
						schema.getReference(entry.referenceName()).orElseThrow(),
						entry.groupId()
					),
					TreeMap::new,
					summingInt(entry -> 1)
				)
			);


		// filter entities by facets in input query (even if part of user filter) - use AND for different entity types, and OR for facet ids
		final List<SealedEntity> filteredEntitiesIncludingUserFilter =
			selectedEntitiesPredicate == null ?
				filteredEntities.stream().toList() :
				filteredEntities.stream().filter(selectedEntitiesPredicate).toList();
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
					.filter(grouped -> Optional.ofNullable(allowedReferenceNames)
						.map(it -> it.get().contains(grouped.getKey().referenceSchema().getName()))
						.orElse(true))
					.map(it -> {
							final ReferenceSchemaContract referenceSchema = it.getKey().referenceSchema();
							return new FacetGroupStatistics(
								referenceSchema,
								ofNullable(it.getKey().groupId())
									.map(gId -> {
										final String entityType = Objects.requireNonNull(referenceSchema.getReferencedGroupType());
										final EntityGroupFetch groupEntityRequirement = Optional.ofNullable(groupEntityRequirementSupplier)
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (groupEntityRequirement == null) {
											return new EntityReference(entityType, gId);
										}
										return session.getEntity(entityType, gId, groupEntityRequirement.getRequirements()).orElseThrow();
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
										final EntityFetch facetEntityRequirement = Optional.ofNullable(facetEntityRequirementSupplier)
											.map(supplier -> supplier.apply(referenceSchema.getName()))
											.orElse(null);
										if (facetEntityRequirement == null) {
											facetEntity = new EntityReference(facetEntityType, facetPrimaryKey);
										} else {
											facetEntity = session.getEntity(facetEntityType, facetPrimaryKey, facetEntityRequirement.getRequirements())
												.orElseThrow();
										}

										final FacetStatisticsDepth statisticsDepth = Optional.ofNullable(statisticsDepthSupplier.apply(referenceSchema.getName()))
											.orElseThrow();

										// create facet statistics
										return new FacetStatistics(
											facetEntity,
											requested,
											facet.getValue(),
											statisticsDepth == FacetStatisticsDepth.IMPACT ?
												computeImpact(filteredEntitiesIncludingUserFilter, facetFilteredEntityIds, facet.getKey(), fcc) : null
										);
									})
									.sorted((o1, o2) -> compareFacet(referenceSchema.getName(), facetSorterFactory, cachedComparators, o1, o2))
									.collect(toList())
							);
						}
					)
					.sorted((o1, o2) -> compareFacetGroup(facetGroupSorterFactory, cachedGroupComparators, o1, o2))
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

	@Nonnull
	private static int[] extractFacetIds(@Nonnull FacetHaving facetHavingFilter) {
		for (FilterConstraint child : facetHavingFilter.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet epkis) {
				return epkis.getPrimaryKeys();
			} else {
				throw new IllegalArgumentException("Unsupported constraint in facet filter: " + child);
			}
		}
		return new int[0];
	}

	@Nonnull
	private static int[] extractFacetIds(@Nonnull FilterBy filterBy) {
		for (FilterConstraint child : filterBy.getChildren()) {
			if (child instanceof EntityPrimaryKeyInSet epkis) {
				return epkis.getPrimaryKeys();
			} else {
				throw new IllegalArgumentException("Unsupported constraint in facet filter: " + child);
			}
		}
		return new int[0];
	}

	private static boolean isWithinHierarchy(Hierarchy categoryHierarchy, ReferenceContract category, int requestedCategoryId) {
		final int categoryId = category.getReferencedPrimaryKey();
		final String categoryIdAsString = String.valueOf(categoryId);
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
		// has parent node or requested category id
		return Objects.equals(requestedCategoryId, categoryId) ||
			parentItems
				.stream()
				.anyMatch(it -> Objects.equals(String.valueOf(requestedCategoryId), it.getCode()));
	}

	@Nonnull
	private static Integer[] getParametersWithDifferentGroups(List<SealedEntity> originalProductEntities, Set<Integer> groups) {
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
			.orElseThrow(() -> new IllegalStateException("There is no product with two references to parameters in different groups!"));
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
			.orElseThrow(() -> new IllegalStateException("There is no product with two references to parameters in same group!"));
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

			final List<EntityReference> storedParameterGroups = dataGenerator.generateEntities(
					dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(15)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReference> storedParameters = dataGenerator.generateEntities(
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
						.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, whichIs -> makeReferenceIndexed(whichIs).faceted())
						.withReferenceToEntity(Entities.STORE, Entities.STORE, Cardinality.ZERO_OR_MORE, whichIs -> makeReferenceIndexed(whichIs).faceted())
						.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, whichIs -> makeReferenceIndexed(whichIs).faceted())
						.withReferenceToEntity(EMPTY_COLLECTION_ENTITY, EMPTY_COLLECTION_ENTITY, Cardinality.ZERO_OR_MORE, whichIs -> makeReferenceIndexed(whichIs).faceted())
						.withReferenceToEntity(
							Entities.PARAMETER, Entities.PARAMETER,
							Cardinality.ZERO_OR_MORE,
							thatIs -> makeReferenceIndexed(thatIs).faceted()
								.withAttribute(ATTRIBUTE_TRANSIENT, Boolean.class, AttributeSchemaEditor::filterable)
								.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
						);
				}
			);
			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
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
						.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll(), priceContentAll()).orElse(null))
						.collect(toList())
				),
				tuple(
					"parameterIndex",
					storedParameters.stream()
						.collect(
							toMap(
								EntityReference::getPrimaryKey,
								it -> session.getEntity(it.getType(), it.getPrimaryKeyOrThrowException(), attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()).orElse(null)
							)
						)
				),
				tuple(
					"parameterGroupIndex",
					storedParameterGroups.stream()
						.collect(
							toMap(
								EntityReference::getPrimaryKey,
								it -> session.getEntity(it.getType(), it.getPrimaryKeyOrThrowException(), attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()).orElse(null)
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

	@DisplayName("Should throw exception when accessing localized attributes on fetched entities")
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
	void shouldReturnProductsWithSpecifiedFacetInEntireSet(long seed, Evita evita, List<SealedEntity> originalProductEntities) {
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
	void shouldReturnProductsWithSpecifiedFacetGroupAndCombinationInEntireSet(Evita evita, List<SealedEntity> originalProductEntities) {
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
	void shouldReturnProductsWithSpecifiedFacetGroupOrCombinationInEntireSet(Evita evita, List<SealedEntity> originalProductEntities) {
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

	@DisplayName("Should return products matching group NOT combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupNotCombinationInEntireSet(Evita evita, List<SealedEntity> originalProductEntities) {
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

	@DisplayName("Should return products matching AND combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetAndCombinationInEntireSet(Evita evita, List<SealedEntity> originalProductEntities) {
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
	void shouldReturnProductsUsingSpecifiedFacetAndCombinationDefinedByGroupAttributeFilterInEntireSetBy(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> parameterGroupIndex) {
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
	void shouldReturnProductsWithSpecifiedFacetOrCombinationInEntireSet(Evita evita, List<SealedEntity> originalProductEntities) {
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
	void shouldReturnProductsWithSpecifiedFacetInHierarchyTree(long seed, Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
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

	@DisplayName("Should return products matching hierarchical facet including all its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTree(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
						session,
						productSchema,
						originalProductEntities,
						null,
						null,
						null,
						null,
						query,
						null,
						__ -> FacetStatisticsDepth.IMPACT,
						null,
						null,
						parameterGroupMapping,
						referenceName -> {
							if (Entities.CATEGORY.equals(referenceName)) {
								return selectedIds;
							} else {
								return ArrayUtils.EMPTY_INT_ARRAY;
							}
						}
					);

					assertFacetSummary(expectedSummary, actualFacetSummary);
				}
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet including some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithPartialHierarchicalFacetSubTree(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
					sealedEntity -> {
						// is within requested hierarchy
						return sealedEntity.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(it -> {
									final Set<Integer> parentItems = categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.map(theParent -> Integer.parseInt(theParent.getCode()))
										.collect(Collectors.toSet());
									return (it.getReferencedPrimaryKey() == hierarchyRoot || parentItems.contains(hierarchyRoot)) &&
										!(it.getReferencedPrimaryKey() == 9 || parentItems.contains(9));
								}
							);
					},
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					session,
					productSchema,
					originalProductEntities,
					null,
					null,
					null,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.IMPACT,
					null,
					null,
					parameterGroupMapping,
					referenceName -> {
						if (Entities.CATEGORY.equals(referenceName)) {
							return selectedIds;
						} else {
							return ArrayUtils.EMPTY_INT_ARRAY;
						}
					}
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	@DisplayName("Should return products matching hierarchical facet excluding some of its children")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithHierarchicalFacetSubTreeExcludingSome(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
					sealedEntity -> {
						// is within requested hierarchy
						return sealedEntity.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(it -> {
									final Set<Integer> parentItems = categoryHierarchy.getParentItems(String.valueOf(it.getReferencedPrimaryKey()))
										.stream()
										.map(theParent -> Integer.parseInt(theParent.getCode()))
										.collect(Collectors.toSet());
									return (it.getReferencedPrimaryKey() == hierarchyRoot || parentItems.contains(hierarchyRoot)) &&
										!(it.getReferencedPrimaryKey() == 9 || parentItems.contains(9));
								}
							);
					},
					result.getRecordData()
				);

				final int[] selectedIds = new int[]{3, 7};
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					session,
					productSchema,
					originalProductEntities,
					null,
					null,
					null,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.IMPACT,
					null,
					null,
					parameterGroupMapping,
					referenceName -> {
						if (Entities.CATEGORY.equals(referenceName)) {
							return selectedIds;
						} else {
							return ArrayUtils.EMPTY_INT_ARRAY;
						}
					}
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
			}
		);
	}

	@DisplayName("Should return facet summary for entire set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSet(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for entire set when price filter is set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithPriceFilter(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					product -> product.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC).isPresent(),
					null,
					null,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.IMPACT,
					null,
					null,
					parameterGroupMapping,
					null,
					product -> product.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC)
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return sorted facet statistics by predecessor attribute")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnSortedFacetStatisticsByPredecessorAttribute(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities) {
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
					session,
					productSchema,
					originalProductEntities,
					sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH),
					null,
					refName -> {
						if (Entities.STORE.equals(refName)) {
							return Comparator.comparingInt(o -> ArrayUtils.indexOf(o.getFacetEntity().getPrimaryKeyOrThrowException(), STORE_ORDER));
						} else {
							return null;
						}
					},
					null,
					query,
					() -> Set.of(Entities.STORE),
					__ -> FacetStatisticsDepth.COUNTS,
					refName -> entityFetch(attributeContentAll()),
					null,
					Collections.emptyMap(),
					null
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products matching facets identified by filter")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithFacetMatchingConditionInEntireSet(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping, Map<Integer, SealedEntity> parameterIndex) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					null,
					null,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					null,
					parameterGroupMapping,
					referenceName -> {
						if (Entities.PARAMETER.equals(referenceName)) {
							return selectedFacets;
						} else {
							return new int[0];
						}
					}
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFilteredSet(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
						.map(attr -> attr.compareTo(new BigDecimal("970")) > 0)
						.orElse(false),
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for facet filtered set")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForFacetFilteredSet(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
						.map(attr -> attr.compareTo(new BigDecimal("950")) > 0)
						.orElse(false),
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for hierarchy tree")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTree(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					sealedEntity -> sealedEntity
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
						}),
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatistics(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)),
					query,
					null,
					__ -> FacetStatisticsDepth.IMPACT,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatistics(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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

	@DisplayName("Should return facet summary for hierarchy with statistics and inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeWithStatisticsAndInvertedInterFacetRelation(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy, Map<Integer, Integer> parameterGroupMapping) {
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
						facetGroupsConjunction(Entities.STORE, filterBy(entityPrimaryKeyInSet(5)))
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					session,
					productSchema,
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> isWithinHierarchy(categoryHierarchy, category, 2)),
					query,
					null,
					__ -> FacetStatisticsDepth.IMPACT,
					null,
					null,
					parameterGroupMapping
				);

				assertFacetSummary(expectedSummary, actualFacetSummary);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics with inverted inter facet relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedInterFacetRelation(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					productSchema, originalProductEntities, parameterGroupMapping, session, facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0]
				);
				final int twoParametersFromSameGroupResultInverted = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, facetGroupsConjunction(Entities.PARAMETER, filterBy(entityPrimaryKeyInSet(groupId))), facets[0], facets[1]
				);
				assertTrue(
					twoParametersFromSameGroupResultInverted < singleParameterSelectedResultInverted,
					"When certain parameter group relation is inverted to AND, selecting multiple parameters from it should decrease the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics with inverted facet group relation")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndInvertedFacetGroupRelation(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					productSchema, originalProductEntities, parameterGroupMapping, session, facetGroupsDisjunction(Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))), facets[0]
				);
				final int twoParametersFromDifferentGroupResultWithOr = queryParameterFacets(
					productSchema, originalProductEntities, parameterGroupMapping, session, facetGroupsDisjunction(Entities.PARAMETER, WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(groups[1]))), facets
				);
				assertTrue(
					twoParametersFromDifferentGroupResultWithOr > singleParameterSelectedResultWithOr,
					"When certain parameter group relation is inverted to OR, selecting multiple facets from their groups should increase the result"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return facet summary with parameter selection for hierarchy with statistics with negated meaning of group")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForHierarchyTreeAndParameterFacetWithStatisticsAndNegatedGroupImpact(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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

	@DisplayName("Should return facet summary for entire set with group entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithGroupEntities(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityGroupFetch groupEntityRequirement = entityGroupFetch(attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE));

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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					null,
					__ -> groupEntityRequirement,
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with facet entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFacetEntities(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE));

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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					__ -> facetEntityRequirement,
					null,
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with parameter entities")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithParameterEntities(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					() -> Set.of(Entities.PARAMETER),
					__ -> FacetStatisticsDepth.COUNTS,
					referenceName -> {
						if (referenceName.equals(Entities.PARAMETER)) {
							return facetEntityRequirement;
						}
						return null;
					},
					referenceName -> {
						if (referenceName.equals(Entities.PARAMETER)) {
							return groupEntityRequirement;
						}
						return null;
					},
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with facet entities for parameters")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntitySetWithFacetEntitiesForParameters(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetch facetEntityRequirement = entityFetch(attributeContent(ATTRIBUTE_NAME), dataInLocales(CZECH_LOCALE));
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					__ -> FacetStatisticsDepth.COUNTS,
					referenceName -> {
						if (referenceName.equals(Entities.PARAMETER)) {
							return facetEntityRequirement;
						}
						return null;
					},
					referenceName -> {
						if (referenceName.equals(Entities.PARAMETER)) {
							return groupEntityRequirement;
						}
						return null;
					},
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with filtered and ordered facets")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacets(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> parameterIndex, Map<Integer, Integer> parameterGroupMapping) {
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
							orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					session,
					productSchema,
					originalProductEntities,
					entity -> entity.getLocales().contains(CZECH_LOCALE),
					reference -> {
						if (Entities.PARAMETER.equals(reference.getReferenceName())) {
							final SealedEntity parameter = parameterIndex.get(reference.getReferencedPrimaryKey());
							return parameter.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
								parameter.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null;
						} else {
							return true;
						}
					},
					referenceName -> {
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
					},
					null,
					query,
					null,
					referenceName -> FacetStatisticsDepth.COUNTS,
					referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)),
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with filtered and ordered facet groups")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithFilteredAndOrderedFacetGroups(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> parameterGroupIndex, Map<Integer, Integer> parameterGroupMapping) {
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
							orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
						)
					)
				);
				final EvitaResponse<EntityReference> result = session.query(query, EntityReference.class);
				final FacetSummary actualFacetSummary = result.getExtraResult(FacetSummary.class);

				final FacetSummaryWithResultCount expectedSummary = computeFacetSummary(
					session,
					productSchema,
					originalProductEntities,
					entity -> entity.getLocales().contains(CZECH_LOCALE),
					reference -> {
						if (reference.getReferenceKey().referenceName().equals(Entities.PARAMETER)) {
							return reference.getGroup()
								.map(groupRef -> parameterGroupIndex.get(groupRef.getPrimaryKey()))
								.map(group -> group.getAttribute(ATTRIBUTE_CODE, String.class).compareTo("K") < 0 &&
									group.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
								.orElse(false);
						} else {
							return true;
						}
					},
					null,
					referenceName -> {
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
					},
					query,
					null,
					referenceName -> FacetStatisticsDepth.COUNTS,
					referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)),
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
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

	@DisplayName("Should return facet summary for entire set with complex entity requirements with only default requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirementsWithOnlyDefaultRequirements(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					referenceName -> {
						if (referenceName.equals(Entities.CATEGORY)) {
							return FacetStatisticsDepth.IMPACT;
						}
						return FacetStatisticsDepth.COUNTS;
					},
					referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)),
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> facetEntity.getAttributeNames().size() == 1 &&
						facetEntity.getAttribute(ATTRIBUTE_CODE) != null,
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return facet summary for entire set with complex entity requirements")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnFacetSummaryForEntireSetWithComplexEntityRequirements(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					null,
					referenceName -> {
						if (referenceName.equals(Entities.CATEGORY)) {
							return FacetStatisticsDepth.IMPACT;
						}
						return FacetStatisticsDepth.COUNTS;
					},
					referenceName -> {
						if (referenceName.equals(Entities.CATEGORY)) {
							return entityFetch(attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_CODE), dataInLocales(CZECH_LOCALE));
						}
						return entityFetch(attributeContent(ATTRIBUTE_CODE));
					},
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary,
					facetEntity -> {
						if (facetEntity.getType().equals(Entities.CATEGORY)) {
							return facetEntity.getAttributeNames().size() == 2 &&
								facetEntity.getAttribute(ATTRIBUTE_CODE) != null &&
								facetEntity.getAttribute(ATTRIBUTE_NAME) != null;
						}
						return facetEntity.getAttributeNames().size() == 1 &&
							facetEntity.getAttribute(ATTRIBUTE_CODE) != null;
					},
					groupEntity -> groupEntity.getAttributeNames().size() == 1 &&
						groupEntity.getAttribute(ATTRIBUTE_CODE) != null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products matching group EXCLUSIVE combination of facet")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsWithSpecifiedFacetGroupExclusiveCombinationInEntireSet(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					() -> Set.of(Entities.PARAMETER),
					referenceName -> FacetStatisticsDepth.IMPACT,
					referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)),
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
				);

				assertFacetSummary(
					expectedSummary,
					actualFacetSummary
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products using different default facet rules")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void shouldReturnProductsUsingDifferentDefaultFacetRules(Evita evita, EntitySchemaContract productSchema, List<SealedEntity> originalProductEntities, Map<Integer, Integer> parameterGroupMapping) {
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
					session,
					productSchema,
					originalProductEntities,
					null,
					query,
					() -> Set.of(Entities.PARAMETER),
					referenceName -> FacetStatisticsDepth.IMPACT,
					referenceName -> entityFetch(attributeContent(ATTRIBUTE_CODE)),
					referenceName -> entityGroupFetch(attributeContent(ATTRIBUTE_CODE)),
					parameterGroupMapping
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
	 * Asserts facet summary against expected without assert full entity data.
	 */
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
	 */
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
	 */
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
	 */
	private void assertFacetSummaryEntities(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<SealedEntity, Boolean> facetEntitiesAssertFunction,
		@Nonnull Function<SealedEntity, Boolean> groupEntitiesAssertFunction
	) {
		facetSummary.getReferenceStatistics().forEach(actualFacetGroupStatistics -> {
			if (actualFacetGroupStatistics.getGroupEntity() != null && actualFacetGroupStatistics.getGroupEntity() instanceof final SealedEntity groupEntity) {
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
	 * Simplification method that executes query with facet computation and returns how many record matches the query
	 * that filters over input parameter facet ids.
	 */
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
			session,
			productSchema,
			originalProductEntities,
			null,
			query,
			null,
			__ -> FacetStatisticsDepth.IMPACT,
			null,
			null,
			parameterGroupMapping
		);

		assertEquals(expectedSummary.entityCount(), result.getTotalRecordCount());
		assertFacetSummary(expectedSummary, actualFacetSummary);

		return result.getTotalRecordCount();
	}

	private interface FacetPredicate extends Predicate<SealedEntity> {

		ReferenceSchemaContract referenceSchema();

		Integer facetGroupId();

		int[] facetIds();

		FacetPredicate combine(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull int... facetIds);

	}

	private record AndFacetPredicate(
		@Getter ReferenceSchemaContract referenceSchema,
		@Nullable @Getter Integer facetGroupId,
		@Getter int[] facetIds
	) implements FacetPredicate {

		@Override
		public boolean test(SealedEntity entity) {
			final Set<Integer> referenceSet = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
			// has the facet
			return Arrays.stream(this.facetIds).allMatch(referenceSet::contains);
		}

		@Override
		public FacetPredicate combine(@Nonnull ReferenceSchemaContract referenceSchema, Integer facetGroupId, @Nonnull int... facetIds) {
			Assert.isTrue(this.referenceSchema.equals(referenceSchema), "Sanity check!");
			Assert.isTrue(Objects.equals(this.facetGroupId, facetGroupId), "Sanity check!");
			return new AndFacetPredicate(
				referenceSchema,
				facetGroupId(),
				ArrayUtils.mergeArrays(facetIds(), facetIds)
			);
		}

		@Override
		public String toString() {
			return referenceSchema() + ofNullable(this.facetGroupId).map(it -> " " + it).orElse("") + " (AND):" + Arrays.toString(facetIds());
		}

	}

	private record OrFacetPredicate(
		@Getter ReferenceSchemaContract referenceSchema,
		@Nullable @Getter Integer facetGroupId,
		@Getter int[] facetIds
	) implements FacetPredicate {

		@Override
		public boolean test(SealedEntity entity) {
			final Set<Integer> referenceSet = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
			// has the facet
			return Arrays.stream(this.facetIds).anyMatch(referenceSet::contains);
		}

		@Override
		public FacetPredicate combine(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull int... facetIds) {
			Assert.isTrue(this.referenceSchema.equals(referenceSchema), "Sanity check!");
			Assert.isTrue(Objects.equals(this.facetGroupId, facetGroupId), "Sanity check!");
			return new OrFacetPredicate(
				referenceSchema,
				facetGroupId(),
				ArrayUtils.mergeArrays(facetIds(), facetIds)
			);
		}

		@Override
		public String toString() {
			return referenceSchema() + ofNullable(this.facetGroupId).map(it -> " " + it).orElse("") + " (OR):" + Arrays.toString(facetIds());
		}

	}

	private record NotFacetPredicate(
		@Getter ReferenceSchemaContract referenceSchema,
		@Nullable @Getter Integer facetGroupId,
		@Getter int[] facetIds
	) implements FacetPredicate {

		@Override
		public boolean test(SealedEntity entity) {
			final Set<Integer> referenceSet = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
			// has the facet
			return Arrays.stream(this.facetIds).noneMatch(referenceSet::contains);
		}

		@Override
		public FacetPredicate combine(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull int... facetIds) {
			Assert.isTrue(this.referenceSchema.equals(referenceSchema), "Sanity check!");
			Assert.isTrue(Objects.equals(this.facetGroupId, facetGroupId), "Sanity check!");
			return new NotFacetPredicate(
				referenceSchema,
				facetGroupId(),
				ArrayUtils.mergeArrays(facetIds(), facetIds)
			);
		}

		@Override
		public String toString() {
			return referenceSchema() + ofNullable(this.facetGroupId).map(it -> " " + it).orElse("") + " (NOT):" + Arrays.toString(facetIds());
		}

	}

	private record ExclusiveFacetPredicate(
		@Getter ReferenceSchemaContract referenceSchema,
		@Nullable @Getter Integer facetGroupId,
		@Getter int[] facetIds
	) implements FacetPredicate {

		@Override
		public boolean test(SealedEntity entity) {
			final Set<Integer> referenceSet = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
			// has the facet
			return Arrays.stream(this.facetIds).anyMatch(referenceSet::contains);
		}

		@Override
		public FacetPredicate combine(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull int... facetIds) {
			Assert.isTrue(this.referenceSchema.equals(referenceSchema), "Sanity check!");
			Assert.isTrue(Objects.equals(this.facetGroupId, facetGroupId), "Sanity check!");
			return new OrFacetPredicate(
				referenceSchema,
				facetGroupId(),
				facetIds
			);
		}

		@Override
		public String toString() {
			return referenceSchema() + ofNullable(this.facetGroupId).map(it -> " " + it).orElse("") + " (EXCLUSIVE):" + Arrays.toString(facetIds());
		}

	}

	/**
	 * Internal data structure for referencing nullable groups.
	 */
	private record GroupReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId
	) implements Comparable<GroupReference> {

		private GroupReference {
			Assert.notNull(referenceSchema, "Reference schema must not be null!");
		}

		@Override
		public int compareTo(GroupReference o) {
			final int first = this.referenceSchema.getName().compareTo(o.referenceSchema.getName());
			return first == 0 ? ofNullable(this.groupId).map(it -> ofNullable(o.groupId).map(it::compareTo).orElse(-1)).orElseGet(() -> o.groupId != null ? 1 : 0) : first;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof GroupReference that)) return false;

			return compareTo(that) == 0;
		}

		@Override
		public int hashCode() {
			int result = this.referenceSchema.hashCode();
			result = 31 * result + Objects.hashCode(this.groupId);
			return result;
		}
	}

	private record GroupReferenceWithEntityId(
		@Nonnull String referenceName,
		@Nullable Integer groupId,
		int entityId
	) {
	}

	private record FacetSummaryWithResultCount(int entityCount, FacetSummary facetSummary) {
	}

	private record FacetSummaryToStringWrapper(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Function<FacetGroupStatistics, String> groupRenderer,
		@Nonnull Function<FacetStatistics, String> facetRenderer
	) {

		@Override
		public String toString() {
			return this.facetSummary.prettyPrint(this.groupRenderer, this.facetRenderer);
		}

	}

	private static class FacetComputationalContext {
		private final EntitySchemaContract entitySchema;
		private final Query query;
		private final BiFunction<String, Integer, Boolean> facetSelectionPredicate;
		private final Map<Integer, Integer> parameterGroupMapping;
		private final List<FacetPredicate> existingFacetPredicates;
		private final Map<FacetGroupRelationLevel, Set<GroupReference>> conjugatedGroups;
		private final Map<FacetGroupRelationLevel, Set<GroupReference>> disjugatedGroups;
		private final Map<FacetGroupRelationLevel, Set<GroupReference>> negatedGroups;
		private final Map<FacetGroupRelationLevel, Set<GroupReference>> exclusiveGroups;
		private final FacetRelationType defaultFacetRelationType;
		private final FacetRelationType defaultGroupRelationType;

		@Nonnull
		private static BiFunction<String, Integer, Boolean> createDefaultFacetExtractPredicate(@Nonnull Query query) {
			final List<FacetHaving> facetHavingConstraints = ofNullable(query.getFilterBy())
				.map(it -> findConstraints(it, FacetHaving.class))
				.orElse(Collections.emptyList());
			return (referenceName, facetId) -> facetHavingConstraints
				.stream()
				.anyMatch(facetHaving -> {
					if (!referenceName.equals(facetHaving.getReferenceName())) {
						return false;
					} else {
						return Arrays.stream(extractFacetIds(facetHaving)).anyMatch(theFacetId -> facetId == theFacetId);
					}
				});
		}

		@Nonnull
		private static <T extends FacetGroupsConstraint> Map<FacetGroupRelationLevel, Set<GroupReference>> extractSettingsFor(
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull Query query,
			@Nonnull Class<T> constraintType
		) {
			return findRequires(query, constraintType)
				.stream()
				.collect(
					Collectors.groupingBy(
						FacetGroupsConstraint::getFacetGroupRelationLevel,
						Collectors.flatMapping(
							it -> {
								if (ArrayUtils.isEmpty(extractFacetIds(it.getFacetGroups().orElseThrow()))) {
									return Stream.of(new GroupReference(entitySchema.getReferenceOrThrowException(it.getReferenceName()), null));
								} else {
									return Arrays.stream(extractFacetIds(it.getFacetGroups().orElseThrow()))
										.mapToObj(x -> new GroupReference(entitySchema.getReferenceOrThrowException(it.getReferenceName()), x));
								}
							},
							Collectors.toSet()
						)
					)
				);
		}

		public FacetComputationalContext(
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull Query query,
			@Nonnull Map<Integer, Integer> parameterGroupMapping,
			@Nullable Function<String, int[]> selectedFacetProvider
		) {
			this.entitySchema = entitySchema;
			this.query = query;
			this.parameterGroupMapping = parameterGroupMapping;
			final Optional<FacetCalculationRules> calculationRules = ofNullable(findRequire(query, FacetCalculationRules.class));
			this.defaultFacetRelationType = calculationRules.map(FacetCalculationRules::getFacetsWithSameGroupRelationType).orElse(FacetRelationType.DISJUNCTION);
			this.defaultGroupRelationType = calculationRules.map(FacetCalculationRules::getFacetsWithDifferentGroupsRelationType).orElse(FacetRelationType.CONJUNCTION);
			this.conjugatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsConjunction.class);
			this.disjugatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsDisjunction.class);
			this.negatedGroups = extractSettingsFor(entitySchema, query, FacetGroupsNegation.class);
			this.exclusiveGroups = extractSettingsFor(entitySchema, query, FacetGroupsExclusivity.class);
			// create function that allows to create predicate that returns true if specified facet was part of input query filter
			this.facetSelectionPredicate = selectedFacetProvider == null ?
				createDefaultFacetExtractPredicate(query) :
				(referenceName, facetId) -> ArrayUtils.contains(selectedFacetProvider.apply(referenceName), facetId);

			// create predicates that can filter along facet constraints in current query
			this.existingFacetPredicates = computeExistingFacetPredicates(query.getFilterBy(), entitySchema, selectedFacetProvider);
		}

		@Nonnull
		public Predicate<? super SealedEntity> createBaseFacetPredicate() {
			return combineFacetsIntoPredicate(this.existingFacetPredicates);
		}

		public Predicate<? super SealedEntity> createBaseFacetPredicateWithoutGroupOfFacet(ReferenceKey facet) {
			final Predicate<FacetPredicate> matchTypeAndGroup = it -> Objects.equals(facet.referenceName(), it.referenceSchema().getName()) &&
				Objects.equals(getGroup(facet), it.facetGroupId());

			return combineFacetsIntoPredicate(
				Stream.concat(
					Stream.of(
						// create brand new predicate
						createFacetGroupPredicate(
							this.entitySchema.getReferenceOrThrowException(facet.referenceName()),
							getGroup(facet),
							facet.primaryKey()
						)
					),
					// use all previous facet predicates that doesn't match this facet type and group
					this.existingFacetPredicates
						.stream()
						.filter(matchTypeAndGroup.negate())
				).toList()
			);
		}

		@Nonnull
		public Predicate<? super SealedEntity> createTestFacetPredicate(@Nonnull ReferenceKey facet) {
			final Predicate<FacetPredicate> matchTypeAndGroup = it -> Objects.equals(facet.referenceName(), it.referenceSchema().getName()) &&
				Objects.equals(getGroup(facet), it.facetGroupId());

			// alter existing facet predicate by adding new OR facet id or create new facet predicate for current facet
			final FacetPredicate currentFacetGroupPredicate = this.existingFacetPredicates
				.stream()
				.filter(matchTypeAndGroup)
				.findFirst()
				.map(it -> it.combine(this.entitySchema.getReferenceOrThrowException(facet.referenceName()), getGroup(facet), facet.primaryKey()))
				.orElseGet(() ->
					createFacetGroupPredicate(
						this.entitySchema.getReferenceOrThrowException(facet.referenceName()),
						getGroup(facet),
						facet.primaryKey()
					)
				);
			// use all previous facet predicates that don't match this facet type and group
			final Stream<FacetPredicate> otherFacetGroupPredicates = this.existingFacetPredicates
				.stream()
				.filter(matchTypeAndGroup.negate());

			if (isExclusiveAmongOtherGroups(currentFacetGroupPredicate)) {
				// use only this facet group predicate - the group is exclusive on group level
				return currentFacetGroupPredicate;
			} else {
				// now create combined predicate upon it
				return combineFacetsIntoPredicate(
					Stream.concat(
						otherFacetGroupPredicates,
						Stream.of(currentFacetGroupPredicate)
					).collect(toList())
				);
			}
		}

		public boolean wasFacetRequested(@Nonnull ReferenceKey facet) {
			return ofNullable(this.query.getFilterBy())
				.map(fb -> this.facetSelectionPredicate.apply(facet.referenceName(), facet.primaryKey()))
				.orElse(false);
		}

		public boolean isAnyFacetGroupNegated(FacetGroupRelationLevel level) {
			return !this.negatedGroups
				.getOrDefault(level, Collections.emptySet())
				.isEmpty();
		}

		public boolean isFacetGroupNegated(GroupReference groupReference, FacetGroupRelationLevel level) {
			return this.negatedGroups
				.getOrDefault(level, Collections.emptySet())
				.contains(groupReference);
		}

		public boolean isFacetGroupExclusive(GroupReference groupReference, FacetGroupRelationLevel level) {
			return this.exclusiveGroups
				.getOrDefault(level, Collections.emptySet())
				.contains(groupReference);
		}

		public boolean isFacetGroupConjugated(GroupReference groupReference, FacetGroupRelationLevel level) {
			return this.conjugatedGroups
				.getOrDefault(level, Collections.emptySet())
				.contains(groupReference);
		}

		public boolean isFacetGroupDisjugated(GroupReference groupReference, FacetGroupRelationLevel level) {
			return this.disjugatedGroups
				.getOrDefault(level, Collections.emptySet())
				.contains(groupReference);
		}

		private boolean isExclusiveAmongOtherGroups(FacetPredicate currentFacetGroupPredicate) {
			final GroupReference groupReference = new GroupReference(
				currentFacetGroupPredicate.referenceSchema(),
				currentFacetGroupPredicate.facetGroupId()
			);
			if (this.exclusiveGroups.getOrDefault(WITH_DIFFERENT_GROUPS, Collections.emptySet())
				.contains(groupReference)) {
				return true;
			} else if (
				// check defaults if there is no specific mapping
				Stream.of(
					this.conjugatedGroups,
					this.disjugatedGroups,
					this.negatedGroups
				).noneMatch(
					it -> it
						.getOrDefault(WITH_DIFFERENT_GROUPS, Collections.emptySet())
						.contains(groupReference)
				)
			) {
				return this.defaultGroupRelationType == FacetRelationType.EXCLUSIVITY;
			} else {
				return false;
			}
		}

		@Nullable
		private Integer getGroup(ReferenceKey facet) {
			return Entities.PARAMETER.equals(facet.referenceName()) ?
				this.parameterGroupMapping.get(facet.primaryKey()) : null;
		}

		@Nonnull
		private List<FacetPredicate> computeExistingFacetPredicates(
			@Nullable FilterBy filterBy,
			@Nonnull EntitySchemaContract entitySchema,
			@Nullable Function<String, int[]> selectedFacetProvider
		) {
			final List<FacetPredicate> userFilterPredicates = new LinkedList<>();
			if (filterBy != null) {
				for (FacetHaving facetHavingFilter : findConstraints(filterBy, FacetHaving.class)) {
					final int[] selectedFacets = selectedFacetProvider == null ?
						extractFacetIds(facetHavingFilter) :
						selectedFacetProvider.apply(facetHavingFilter.getReferenceName());

					if (Entities.PARAMETER.equals(facetHavingFilter.getReferenceName())) {
						final Map<Integer, List<Integer>> groupedFacets = Arrays.stream(selectedFacets)
							.boxed()
							.collect(
								groupingBy(this.parameterGroupMapping::get)
							);
						groupedFacets
							.forEach((facetGroupId, facetIdList) -> {
								final int[] facetIds = facetIdList.stream().mapToInt(it -> it).toArray();
								userFilterPredicates.add(
									createFacetGroupPredicate(
										entitySchema.getReferenceOrThrowException(facetHavingFilter.getReferenceName()),
										facetGroupId,
										facetIds
									)
								);
							});
					} else {
						userFilterPredicates.add(
							createFacetGroupPredicate(
								entitySchema.getReferenceOrThrowException(facetHavingFilter.getReferenceName()),
								null,
								selectedFacets
							)
						);
					}
				}
			}
			return userFilterPredicates;
		}

		@Nonnull
		private FacetPredicate createFacetGroupPredicate(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nullable Integer facetGroupId,
			int... facetIds
		) {
			final GroupReference groupReference = new GroupReference(referenceSchema, facetGroupId);
			if (isFacetGroupConjugated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return new AndFacetPredicate(referenceSchema, facetGroupId, facetIds);
			} else if (isFacetGroupNegated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return new NotFacetPredicate(referenceSchema, facetGroupId, facetIds);
			} else if (isFacetGroupExclusive(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return new ExclusiveFacetPredicate(referenceSchema, facetGroupId, facetIds);
			} else if (isFacetGroupDisjugated(groupReference, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return new OrFacetPredicate(referenceSchema, facetGroupId, facetIds);
			} else {
				return switch (this.defaultFacetRelationType) {
					case CONJUNCTION -> new AndFacetPredicate(referenceSchema, facetGroupId, facetIds);
					case DISJUNCTION -> new OrFacetPredicate(referenceSchema, facetGroupId, facetIds);
					case NEGATION -> new NotFacetPredicate(referenceSchema, facetGroupId, facetIds);
					case EXCLUSIVITY -> new ExclusiveFacetPredicate(referenceSchema, facetGroupId, facetIds);
				};
			}
		}

		@Nonnull
		private Predicate<SealedEntity> combineFacetsIntoPredicate(@Nonnull List<FacetPredicate> predicates) {
			final List<Predicate<SealedEntity>> disjugatedPredicates = new ArrayList<>();
			final List<Predicate<SealedEntity>> conjugatedPredicates = new ArrayList<>();
			final List<Predicate<SealedEntity>> negatedPredicates = new ArrayList<>();
			final List<Predicate<SealedEntity>> exclusivePredicates = new ArrayList<>();
			for (FacetPredicate predicate : predicates) {
				final GroupReference groupReference = new GroupReference(predicate.referenceSchema(), predicate.facetGroupId());
				if (isFacetGroupConjugated(groupReference, WITH_DIFFERENT_GROUPS)) {
					conjugatedPredicates.add(predicate);
				} else if (isFacetGroupNegated(groupReference, WITH_DIFFERENT_GROUPS)) {
					negatedPredicates.add(predicate);
				} else if (isFacetGroupExclusive(groupReference, WITH_DIFFERENT_GROUPS)) {
					exclusivePredicates.add(predicate);
				} else if (isFacetGroupDisjugated(groupReference, WITH_DIFFERENT_GROUPS)) {
					disjugatedPredicates.add(predicate);
				} else {
					switch (this.defaultGroupRelationType) {
						case CONJUNCTION -> conjugatedPredicates.add(predicate);
						case DISJUNCTION -> disjugatedPredicates.add(predicate);
						case NEGATION -> negatedPredicates.add(predicate);
						case EXCLUSIVITY -> exclusivePredicates.add(predicate);
					};
				}
			}

			final Optional<Predicate<SealedEntity>> disjugatedPredicate = disjugatedPredicates.stream().reduce(Predicate::or);
			final Optional<Predicate<SealedEntity>> conjugatedPredicate = conjugatedPredicates.stream().reduce(Predicate::and);
			final Optional<Predicate<SealedEntity>> negatedPredicate = negatedPredicates.stream().reduce(Predicate::and);
			final Optional<Predicate<SealedEntity>> exclusivePredicate = exclusivePredicates.stream().reduce(Predicate::or);

			Predicate<SealedEntity> resultPredicate = entity -> true;
			if (conjugatedPredicate.isPresent()) {
				resultPredicate = resultPredicate.and(conjugatedPredicate.get());
			}
			if (negatedPredicate.isPresent()) {
				resultPredicate = resultPredicate.and(negatedPredicate.get());
			}
			if (exclusivePredicate.isPresent()) {
				// exclusivity must be enforced on client level - it's too late here, so we fall back to system default - and
				resultPredicate = resultPredicate.and(exclusivePredicate.get());
			}
			if (disjugatedPredicate.isPresent()) {
				resultPredicate = resultPredicate.or(disjugatedPredicate.get());
			}

			return resultPredicate;
		}

	}

}
