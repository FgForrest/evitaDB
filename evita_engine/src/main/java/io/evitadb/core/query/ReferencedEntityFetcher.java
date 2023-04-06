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

package io.evitadb.core.query;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor.OrderingDescriptor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * The single implementation of {@link ReferenceFetcher} interface that needs to be declared in public API module
 * because it's required by {@link Entity} and {@link EntityDecorator} that are preset there.
 *
 * The implementation fetches all required referenced entities in bulk in its container for all entity primary keys
 * that are required to be fetched from the persistent storage. The implementation is efficient in the way that it
 * eliminates multiple round-trips and fetches only entities of the filtered references accordingly to the requested
 * specs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedEntityFetcher implements ReferenceFetcher {
	private static final int[] EMPTY_INTS = new int[0];
	/**
	 * Requirement aggregation exported from {@link EvitaRequest#getReferenceEntityFetch()}.
	 */
	@Nonnull private final Map<String, RequirementContext> requirementContext;
	/**
	 * The query context used for querying the entities.
	 */
	@Nonnull private final QueryContext queryContext;
	/**
	 * The function that provides access to already fetched entities present in the input data. This allows us to avoid
	 * duplicate fetches for the data that have been fetched in previous top entity fetch. This function takes its part
	 * when the same entity is gradually enriched.
	 */
	@Nonnull private final ExistingEntityProvider existingEntityRetriever;
	/**
	 * Index of prefetched entities assembled in constructor and quickly available when the entity is requested
	 * by the {@link EntityDecorator} constructor. The key is {@link ReferenceSchemaContract#getName()}, the value
	 * is the information containing the indexes of fetched entities and their groups, information about their ordering
	 * and validity index.
	 *
	 * @see PrefetchedEntities
	 */
	@Nullable private Map<String, PrefetchedEntities> fetchedEntities;

	/**
	 * Utility function that fetches and returns filtered map of {@link SealedEntity} indexed by their primary key
	 * according to `entityPrimaryKeys` argument. The method is reused both for fetching the referenced entities and
	 * their groups.
	 *
	 * @param entityPrimaryKeys           array of primary keys to be fetched
	 * @param queryContext                query context that will be used for fetching
	 * @param filterByVisitor             filter visitor to be (re)used for filtering the entities to fetch
	 * @param referenceSchema             the schema of the reference ({@link ReferenceSchemaContract#getName()})
	 * @param entityType                  represents the entity type ({@link EntitySchemaContract#getName()}) that should be loaded
	 * @param existingEntityRetriever     lambda allowing to reuse already fetched entities from previous decorator instance
	 * @param referencedEntityResolver    lambda allowing to get primary keys of all entities referenced by entity with
	 *                                    certain primary key (we need this to distinguish retrieving data for entities and groups)
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param entityFetch                 contains the "richness" requirements for the fetched entities
	 * @param filterBy                    contains the filter that filters out the entities client doesn't want to fetch
	 * @param validityMapping             contains the DTO tracking the reachability of the referenced entities by owner
	 *                                    entities (see {@link ValidEntityToReferenceMapping} for more details)
	 * @return filtered map of {@link SealedEntity} indexed by their primary key according to `entityPrimaryKeys` argument
	 */
	@Nonnull
	private static Map<Integer, SealedEntity> fetchReferencedEntities(
		@Nonnull QueryContext queryContext,
		@Nonnull int[] entityPrimaryKeys,
		@Nonnull AtomicReference<FilterByVisitor> filterByVisitor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityType,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityResolver,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull EntityFetch entityFetch,
		@Nullable FilterBy filterBy,
		@Nullable ValidEntityToReferenceMapping validityMapping
	) {

		// compute set of filtered referenced entity ids
		final int[] referencedRecordIds = getFilteredReferencedEntityIds(
			entityPrimaryKeys, queryContext, referenceSchema, filterByVisitor, filterBy, validityMapping,
			entityNestedQueryComparator, referencedEntityResolver
		);

		if (ArrayUtils.isEmpty(referencedRecordIds)) {
			return Collections.emptyMap();
		} else {
			// set them to the comparator instance, if such is provided
			// this prepares the "pre-sorted" arrays in this comparator for faster sorting
			ofNullable(entityNestedQueryComparator)
				.ifPresent(it -> it.setFilteredEntities(referencedRecordIds));

			// finally, create the fetch request, get the collection and fetch the referenced entity bodies
			final EvitaRequest fetchRequest = queryContext.getEvitaRequest().deriveCopyWith(entityType, entityFetch);
			final EntityCollection referencedCollection = queryContext.getEntityCollectionOrThrowException(
				entityType, "fetch references"
			);
			return fetchReferenceBodies(
				referenceSchema.getName(), referencedRecordIds, queryContext,
				existingEntityRetriever, referencedCollection, fetchRequest
			);
		}
	}

	/**
	 * Method fetches all `referencedRecordIds` from the `referencedCollection`.
	 *
	 * @param referenceName           just for logging purposes
	 * @param referencedRecordIds     the ids of referenced entities to fetch
	 * @param queryContext            current query context
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities (when only enrichment occurs)
	 * @param referencedCollection    the reference collection that will be used for fetching the entities
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, SealedEntity> fetchReferenceBodies(
		@Nonnull String referenceName,
		@Nonnull int[] referencedRecordIds,
		@Nonnull QueryContext queryContext,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever,
		@Nonnull EntityCollection referencedCollection,
		@Nonnull EvitaRequest fetchRequest
	) {
		final Map<String, RequirementContext> referenceEntityFetch = fetchRequest.getReferenceEntityFetch();
		final QueryContext nestedQueryContext = referencedCollection.createQueryContext(queryContext, fetchRequest, queryContext.getEvitaSession());
		final ReferenceFetcher subReferenceFetcher = createSubReferenceFetcher(referenceEntityFetch, nestedQueryContext);

		final Map<Integer, SealedEntity> entityIndex;
		try {
			queryContext.pushStep(QueryPhase.FETCHING_REFERENCES, "Reference name: `" + referenceName + "`");

			entityIndex = CollectionUtils.createHashMap(referencedRecordIds.length);
			final List<EntityDecorator> alreadyExistingEntities = Arrays.stream(referencedRecordIds)
				.mapToObj(referencedRecordId -> {
					// first look up whether we already have the entity prefetched somewhere (in case enrichment occurs)
					final Optional<SealedEntity> existingEntity = existingEntityRetriever.apply(referencedRecordId);
					if (existingEntity.isPresent()) {
						return (EntityDecorator)existingEntity.get();
					} else {
						// if not, fetch the fresh entity from the collection
						referencedCollection.getEntity(
							referencedRecordId, fetchRequest, queryContext.getEvitaSession(), subReferenceFetcher
						).ifPresent(refEntity -> entityIndex.put(refEntity.getPrimaryKey(), refEntity));
						return null;
					}
				})
				.filter(Objects::nonNull)
				.toList();

			// if there were any already existing entities found, enrich and limit them appropriately
			if (!alreadyExistingEntities.isEmpty()) {
				subReferenceFetcher.initReferenceIndex(alreadyExistingEntities, referencedCollection);
				alreadyExistingEntities.forEach(
					// if so, enrich or limit the existing entity for desired scope
					previouslyFetchedEntity -> {
						final SealedEntity refEntity = queryContext.enrichOrLimitReferencedEntity(
							previouslyFetchedEntity, fetchRequest, subReferenceFetcher
						);
						entityIndex.put(refEntity.getPrimaryKey(), refEntity);
					}
				);
			}
		} finally {
			nestedQueryContext.popStep();
		}
		return entityIndex;
	}

	/**
	 * Creates new instance of this class, that is loads the sub-references targeting different entity type
	 * (collection) and having different scope (request). The sub-fetcher will limit loading references to the set
	 * of `referencedRecordIds` in the input parameter.
	 *
	 * @param referenceEntityFetch the requirements for fetching the references
	 * @param nestedQueryContext   the query context to use
	 * @return the new instance of reference loader prepared to provide the {@link SealedEntity} instances
	 */
	@Nonnull
	private static ReferenceFetcher createSubReferenceFetcher(
		@Nonnull Map<String, RequirementContext> referenceEntityFetch,
		@Nonnull QueryContext nestedQueryContext
	) {
		return referenceEntityFetch.isEmpty() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				referenceEntityFetch,
				nestedQueryContext
			);
	}

	/**
	 * Returns array of all referenced entity ids that are referenced by any of passed `entityPrimaryKeys`. Initializes
	 * starting validity relations in `validityMapping` where each entity sees all its referenced entities. This initial
	 * visibility setup will be refined during fetch process.
	 *
	 * @param entityPrimaryKeys        the set of entity ids whose references should be looked up
	 * @param referencedEntityResolver the lambda that will retrieve the data from the index (we need either referenced
	 *                                 entities or their groups)
	 * @param validityMapping          contains the DTO tracking the reachability of the referenced entities by owner
	 *                                 entities (see {@link ValidEntityToReferenceMapping} for more details)
	 * @return non-filtered complete array of referenced entity ids
	 */
	private static int[] getAllReferencedEntityIds(
		@Nonnull int[] entityPrimaryKeys,
		@Nonnull Function<Integer, Formula> referencedEntityResolver,
		@Nullable ValidEntityToReferenceMapping validityMapping
	) {
		// aggregate all referenced primary keys into one sorted distinct array
		return FormulaFactory.or(
				Arrays.stream(entityPrimaryKeys)
					.mapToObj(it -> {
						final Formula referencedEntityIds = referencedEntityResolver.apply(it);
						// Initializes starting validity relations in `validityMapping` where each entity sees
						// all its referenced entities. This initial visibility setup will be refined during fetch process.
						ofNullable(validityMapping)
							.ifPresent(vm -> vm.setInitialVisibilityForEntity(it, referencedEntityIds.compute()));
						// return the referenced entity primary keys
						return referencedEntityIds;
					})
					.toArray(Formula[]::new)
			)
			.compute()
			.getArray();
	}

	/**
	 * Returns array of referenced entity ids that are referenced by any of passed `entityPrimaryKeys` that are filtered
	 * to match the passed `filterBy`.
	 *
	 * @param entityPrimaryKeys           the set of entity ids whose references should be looked up
	 * @param queryContext                the query context user for querying the entities
	 * @param referenceSchema             the schema of the reference
	 * @param filterByVisitor             the visitor that will be used for traversing the constraint
	 * @param filterBy                    the filtering constraint itself
	 * @param validityMapping             see detailed description in {@link ValidEntityToReferenceMapping}
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param referencedEntityResolver    lambda allowing to get primary keys of all entities referenced by entity with
	 *                                    certain primary key (we need this to distinguish retrieving data for entities and groups)
	 * @return filtered array of referenced entity ids
	 */
	@Nullable
	private static int[] getFilteredReferencedEntityIds(
		@Nonnull int[] entityPrimaryKeys,
		@Nonnull QueryContext queryContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AtomicReference<FilterByVisitor> filterByVisitor,
		@Nullable FilterBy filterBy,
		@Nullable ValidEntityToReferenceMapping validityMapping,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityResolver
	) {
		// initialize the main data-structures we'll be working later in this method
		final Optional<ValidEntityToReferenceMapping> validityMappingOptional = ofNullable(validityMapping);

		// we need to gather all referenced entity ids and initialize validity mapping for all the passed entity PKs
		final int[] allReferencedEntityIds = getAllReferencedEntityIds(
			entityPrimaryKeys,
			entityPk -> referencedEntityResolver.apply(referenceSchema.getName(), entityPk),
			validityMapping
		);

		if (ArrayUtils.isEmpty(allReferencedEntityIds)) {
			// if nothing was found, quickly finish
			return EMPTY_INTS;
		} else if (filterBy == null) {
			// we may allow all referenced entity ids
			validityMappingOptional.ifPresent(it -> it.restrictTo(new BaseBitmap(allReferencedEntityIds)));
			return allReferencedEntityIds;
		} else if (isAttributeConstraintPresent(filterBy)) {
			/*
			   if the filter contains only constraints on attributes on references only,
			   we work with referencedEntityTypeIndex here - this index contains referenced entity primary keys as records
			   this means we can directly filter against `allReferencedEntityIds` and apply the filter constraint
			 */
			final FilterBy filterByToUse = new FilterBy(
				ArrayUtils.mergeArrays(
					filterBy.getChildren(),
					new FilterConstraint[] {
						new EntityPrimaryKeyInSet(
							Arrays.stream(allReferencedEntityIds).boxed().toArray(Integer[]::new)
						)
					}
				)
			);

			final FilterByVisitor theFilterByVisitor = getFilterByVisitor(queryContext, filterByVisitor);
			final EntitySchemaContract entitySchema = theFilterByVisitor.getSchema();
			final ReferencedTypeEntityIndex referencedEntityTypeIndex = theFilterByVisitor.getReferencedEntityTypeIndex(entitySchema, referenceSchema);
			final Formula resultFormula = computeResultWithPassedIndex(
				referencedEntityTypeIndex, referenceSchema, theFilterByVisitor, filterByToUse,
				null, entityNestedQueryComparator
			);

			// we've resolved directly the referenced entity ids
			final Bitmap result = resultFormula.compute();
			validityMappingOptional.ifPresent(it -> it.restrictTo(result));
			return result.getArray();
		} else {
			final FilterByVisitor theFilterByVisitor = getFilterByVisitor(queryContext, filterByVisitor);
			final List<EntityIndex> referencedEntityIndexes = theFilterByVisitor.getReferencedRecordEntityIndexes(
				new ReferenceHaving(referenceSchema.getName(), filterBy.getChildren())
			);

			if (referencedEntityIndexes.isEmpty()) {
				validityMappingOptional.ifPresent(ValidEntityToReferenceMapping::forbidAll);
				return EMPTY_INTS;
			} else {
				final IntSet referencedPrimaryKeys = new IntHashSet(referencedEntityIndexes.size());
				final Formula entityPrimaryKeyFormula = new ConstantFormula(new BaseBitmap(entityPrimaryKeys));
				final IntSet foundReferencedIds = new IntHashSet(referencedEntityIndexes.size());
				for (EntityIndex referencedEntityIndex : referencedEntityIndexes) {
					final ReferenceKey indexDiscriminator = (ReferenceKey) referencedEntityIndex.getIndexKey().getDiscriminator();
					final int referencedPrimaryKey = indexDiscriminator.primaryKey();
					foundReferencedIds.add(referencedPrimaryKey);

					final Formula resultFormula = computeResultWithPassedIndex(
						referencedEntityIndex,
						referenceSchema,
						theFilterByVisitor,
						filterBy,
						filterConstraint -> new And(new EntityPrimaryKeyInSet(referencedPrimaryKey), filterConstraint),
						entityNestedQueryComparator,
						EntityPrimaryKeyInSet.class
					);

					final Bitmap matchingPrimaryKeys = FormulaFactory.and(
						resultFormula,
						entityPrimaryKeyFormula
					).compute();

					if (matchingPrimaryKeys.isEmpty()) {
						validityMappingOptional.ifPresent(it -> it.forbid(referencedPrimaryKey));
					} else {
						validityMappingOptional.ifPresent(it -> it.restrictTo(matchingPrimaryKeys, referencedPrimaryKey));
						referencedPrimaryKeys.add(referencedPrimaryKey);
					}
				}
				validityMappingOptional.ifPresent(it -> it.forbidAllExcept(foundReferencedIds));
				return referencedPrimaryKeys.toArray();
			}
		}
	}

	/**
	 * Returns true if there is at least one attribute related constraint present in the passed `filterBy` constraint
	 * container excluding the constraint placed inside {@link EntityHaving} parent containers (which relate to
	 * attributes of the related entity and not the attributes on relation itself).
	 */
	private static boolean isAttributeConstraintPresent(@Nonnull FilterBy filterBy) {
		return FinderVisitor.findConstraints(
			filterBy,
			it -> it instanceof AttributeConstraint<?>,
			it -> it instanceof EntityHaving
		).isEmpty();
	}

	/**
	 * Method creates {@link Formula} that calculates the set of matching primary keys from input `index` in the context
	 * of the `referenceSchema` analyzing `filterBy` constraint using `filterByVisitor`. The method unifies the logic
	 * around context preparation.
	 *
	 * @param index                       to be used for filter by transformation
	 * @param referenceSchema             related {@link ReferenceSchemaContract}
	 * @param filterByVisitor             visitor to be used for filter by analysis
	 * @param filterBy                    filtering constraint to be analyzed
	 * @param nestedQueryFormulaEnricher  lambda allowing to enrich the filtering formula for nested entity query
	 *                                    (if any such is found)
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param suppressedConstraints       set of constraints that should be ignored in transformation process
	 * @return formula that calculates the result
	 */
	@SafeVarargs
	@Nullable
	private static Formula computeResultWithPassedIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull FilterBy filterBy,
		@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		// prepare the lambda allowing to reach attribute schema of particular name
		final BiFunction<EntitySchemaContract, String, AttributeSchemaContract> referenceAttributeSchemaFetcher =
			(theEntitySchema, attributeName) -> FilterByVisitor.getReferenceAttributeSchema(
				attributeName, ofNullable(theEntitySchema).orElseGet(filterByVisitor::getSchema), referenceSchema
			);

		// compute the result formula in the initialized context
		final Formula filterFormula = filterByVisitor.executeInContext(
			Collections.singletonList(index),
			ReferenceContent.ALL_REFERENCES,
			referenceSchema,
			nestedQueryFormulaEnricher,
			entityNestedQueryComparator,
			referenceAttributeSchemaFetcher,
			(entityContract, attributeName, locale) -> entityContract.getReferences(referenceSchema.getName())
				.stream()
				.map(it -> it.getAttributeValue(attributeName, locale)),
			() -> {
				filterBy.accept(filterByVisitor);
				// get the result and clear the visitor internal structures
				return filterByVisitor.getFormulaAndClear();
			},
			suppressedConstraints
		);

		// in case there is ordering specified and no nested query filter constraint, we need to handle ordering here
		if (entityNestedQueryComparator != null && !entityNestedQueryComparator.isInitialized()) {
			initializeComparatorFromGlobalIndex(
				filterByVisitor.getEntityCollectionOrThrowException(
					referenceSchema.getReferencedEntityType(), "order references"
				),
				entityNestedQueryComparator,
				filterByVisitor.getEvitaRequest(),
				filterByVisitor.getEvitaSession()
			);
		}

		return filterFormula;
	}

	/**
	 * Initializes the {@link Sorter} implementation in the passed `entityNestedQueryComparator` from the global index
	 * of the passed `targetEntityCollection`.
	 *
	 * @param targetEntityCollection      collection of the referenced entity type
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param evitaRequest                source evita request
	 * @param evitaSession                current session
	 */
	private static void initializeComparatorFromGlobalIndex(
		@Nonnull EntityCollection targetEntityCollection,
		@Nonnull EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract evitaSession
	) {
		final OrderBy orderBy = new OrderBy(entityNestedQueryComparator.getOrderBy().getChildren());
		final QueryContext nestedQueryContext = targetEntityCollection.createQueryContext(
			evitaRequest.deriveCopyWith(targetEntityCollection.getEntityType(), null, orderBy),
			evitaSession
		);

		final QueryPlan queryPlan = QueryPlanner.planNestedQuery(nestedQueryContext);
		final Sorter sorter = queryPlan.getSorter();
		entityNestedQueryComparator.initSorter(nestedQueryContext, sorter);
	}

	/**
	 * Constructor that is used to further enrich already rich entity.
	 */
	public ReferencedEntityFetcher(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nonnull QueryContext queryContext,
		@Nonnull SealedEntity sealedEntity
	) {
		this(requirementContext, queryContext, new ExistingSealedEntityProvider(sealedEntity));
	}

	/**
	 * Constructor that is used for initial entity construction.
	 */
	public ReferencedEntityFetcher(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nonnull QueryContext queryContext
	) {
		this(requirementContext, queryContext, EmptyEntityProvider.INSTANCE);
	}

	/**
	 * Internal constructor.
	 */
	private ReferencedEntityFetcher(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nonnull QueryContext queryContext,
		@Nonnull ExistingEntityProvider existingEntityRetriever
	) {
		this.requirementContext = requirementContext;
		this.queryContext = queryContext;
		this.existingEntityRetriever = existingEntityRetriever;
	}

	/**
	 * Method executes all the necessary referenced entities fetch. It loads only those referenced entities that are
	 * mentioned in `requirementContext`. Execution reuses potentially existing fetched referenced entities from
	 * the last enrichment of the same entity.
	 *
	 * @param queryContext query context used for querying the entity
	 * @param existingEntityRetriever function that provides access to already fetched referenced entities (relict of last enrichment)
	 * @param referencedEntityIdsFormula the formula containing superset of all possible referenced entities
	 * @param referencedEntityGroupIdsFormula the formula containing superset of all possible referenced entity groups
	 * @param entityPrimaryKey the array of top entity primary keys for which the references are being fetched
	 */
	private void prefetchEntities(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nonnull QueryContext queryContext,
		@Nonnull ExistingEntityProvider existingEntityRetriever,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityGroupIdsFormula,
		@Nonnull int[] entityPrimaryKey
	) {
		final AtomicReference<FilterByVisitor> filterByVisitor = new AtomicReference<>();
		this.fetchedEntities = requirementContext
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					it -> {
						final String referenceName = it.getKey();
						final RequirementContext requirements = it.getValue();
						final ReferenceSchemaContract referenceSchema = queryContext.getSchema().getReferenceOrThrowException(referenceName);

						final Optional<OrderingDescriptor> orderingDescriptor = ofNullable(requirements.orderBy())
							.map(OrderBy::getChild)
							.map(ob -> ReferenceOrderByVisitor.getComparator(queryContext, ob));

						final ValidEntityToReferenceMapping validityMapping = new ValidEntityToReferenceMapping(entityPrimaryKey.length);
						final Map<Integer, SealedEntity> entityIndex;
						// are we requested to (are we able to) fetch the entity bodies?
						if (referenceSchema.isReferencedEntityTypeManaged() && requirements.entityFetch() != null) {
							// if so, fetch them
							entityIndex = fetchReferencedEntities(
								queryContext, entityPrimaryKey, filterByVisitor,
								referenceSchema,
								referenceSchema.getReferencedEntityType(),
								pk -> existingEntityRetriever.getExistingEntity(referenceName, pk),
								referencedEntityIdsFormula,
								orderingDescriptor
									.map(OrderingDescriptor::nestedQueryComparator)
									.orElse(null),
								requirements.entityFetch(),
								requirements.filterBy(),
								validityMapping
							);
						} else {
							// if not, leave the index empty
							entityIndex = Collections.emptyMap();
						}

						final Map<Integer, SealedEntity> entityGroupIndex;
						// are we requested to (are we able to) fetch the entity group bodies?
						if (referenceSchema.isReferencedGroupTypeManaged() && requirements.entityGroupFetch() != null && referenceSchema.getReferencedGroupType() != null) {
							// if so, fetch them
							entityGroupIndex = fetchReferencedEntities(
								queryContext, entityPrimaryKey, filterByVisitor,
								referenceSchema,
								referenceSchema.getReferencedGroupType(),
								pk -> existingEntityRetriever.getExistingGroupEntity(referenceName, pk),
								referencedEntityGroupIdsFormula,
								null,
								new EntityFetch(requirements.entityGroupFetch().getRequirements()),
								null,
								null
							);
						} else {
							// if not, leave the index empty
							entityGroupIndex = Collections.emptyMap();
						}

						return new PrefetchedEntities(
							entityIndex,
							validityMapping,
							entityGroupIndex,
							orderingDescriptor
								.map(OrderingDescriptor::comparator)
								.orElse(null)
						);

					}
				)
			);
	}

	@Nonnull
	@Override
	public <T extends SealedEntity> T initReferenceIndex(@Nonnull T entity, @Nonnull EntityCollectionContract entityCollection) {
		// we need to ensure that references are fetched in order to be able to provide information about them
		final T richEnoughEntity = ((EntityCollection)entityCollection).ensureReferencesFetched(entity);
		// prefetch the entities
		prefetchEntities(
			requirementContext,
			queryContext,
			existingEntityRetriever,
			(referenceName, entityPk) -> toFormula(
				(richEnoughEntity instanceof  EntityDecorator entityDecorator ? entityDecorator.getDelegate() : richEnoughEntity)
					.getReferences(referenceName)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray()
			),
			(referenceName, entityPk) -> toFormula(
				(richEnoughEntity instanceof  EntityDecorator entityDecorator ? entityDecorator.getDelegate() : richEnoughEntity)
					.getReferences(referenceName)
					.stream()
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.mapToInt(GroupEntityReference::getPrimaryKey)
					.toArray()
			),
			new int[] {richEnoughEntity.getPrimaryKey()}
		);

		return richEnoughEntity;
	}

	@Nonnull
	@Override
	public <T extends SealedEntity> List<T> initReferenceIndex(@Nonnull List<T> entities, @Nonnull EntityCollectionContract entityCollection) {
		// we need to ensure that references are fetched in order to be able to provide information about them
		final List<T> richEnoughEntities = new ArrayList<>(entities.size());
		for (T entity : entities) {
			richEnoughEntities.add(((EntityCollection) entityCollection).ensureReferencesFetched(entity));
		}
		// prefetch the entities
		prefetchEntities(
			requirementContext,
			queryContext,
			existingEntityRetriever,
			(referenceName, entityPk) -> toFormula(
				richEnoughEntities.stream()
					.map(it -> (it instanceof  EntityDecorator entityDecorator ? entityDecorator.getDelegate() : it))
					.flatMap(it -> it.getReferences(referenceName).stream())
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray()
			),
			(referenceName, entityPk) -> toFormula(
				richEnoughEntities.stream()
					.map(it -> (it instanceof  EntityDecorator entityDecorator ? entityDecorator.getDelegate() : it))
					.flatMap(it -> it.getReferences(referenceName).stream())
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.mapToInt(GroupEntityReference::getPrimaryKey)
					.toArray()
			),
			entities.stream()
				.mapToInt(SealedEntity::getPrimaryKey)
				.toArray()
		);

		return richEnoughEntities;
	}

	@Nonnull
	private static FilterByVisitor getFilterByVisitor(@Nonnull QueryContext queryContext, @Nonnull AtomicReference<FilterByVisitor> filterByVisitor) {
		return ofNullable(filterByVisitor.get()).orElseGet(() -> {
			final FilterByVisitor newVisitor = createFilterVisitor(queryContext);
			filterByVisitor.set(newVisitor);
			return newVisitor;
		});
	}

	@Nonnull
	private static FilterByVisitor createFilterVisitor(@Nonnull QueryContext queryContext) {
		return new FilterByVisitor(
			queryContext,
			Collections.emptyList(),
			TargetIndexes.EMPTY,
			false
		);
	}

	@Nonnull
	private static Formula toFormula(@Nullable int[] recordIds) {
		return ArrayUtils.isEmpty(recordIds) ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(
				new BaseBitmap(
					recordIds
				)
			);
	}

	@Nullable
	@Override
	public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return entityPrimaryKey -> ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(it -> it.getEntity(entityPrimaryKey))
			.orElse(null);
	}

	@Nullable
	@Override
	public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return entityPrimaryKey -> ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(it -> it.getGroupEntity(entityPrimaryKey))
			.orElse(null);
	}

	@Nonnull
	@Override
	public Comparator<ReferenceContract> getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(PrefetchedEntities::referenceComparator)
			.orElse(ReferenceComparator.DEFAULT);
	}

	@Nullable
	@Override
	public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(requirementContext.get(referenceSchema.getName()))
			.map(it -> {
				if (it.filterBy() == null) {
					return null;
				} else {
					final PrefetchedEntities prefetchedEntities = fetchedEntities.get(referenceSchema.getName());
					if (prefetchedEntities != null) {
						final ValidEntityToReferenceMapping validityMapping = prefetchedEntities.validityMapping();
						if (validityMapping != null) {
							return (BiPredicate<Integer, ReferenceDecorator>) (entityPrimaryKey, referenceDecorator) ->
								validityMapping.isReferenceSelected(entityPrimaryKey, referenceDecorator.getReferencedPrimaryKey());
						}
					}
					return null;
				}
			})
			.orElse(null);
	}

	/**
	 * Interface allows accessing the already fetched bodies of entities in existing data structure. It allows
	 * accessing existing entities in {@link SealedEntity} in case the reference entity loader is used for enrichment
	 * only.
	 */
	private interface ExistingEntityProvider {

		/**
		 * Return entity from the existing {@link SealedEntity} or empty result.
		 */
		@Nonnull
		Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey);

		/**
		 * Return entity group from the existing {@link SealedEntity} or empty result.
		 */
		@Nonnull
		Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey);

	}

	/**
	 * This DTO contains the validity mappings between main entity and referenced one. Because we fetch references for
	 * all main entities at once the following situation might occur:
	 *
	 * We look up for PARAMETER (referenced) entity from PRODUCT ENTITY (main), we look up for those parameters that
	 * are mapped with reference having attribute X greater than 5 and there is following data layout:
	 *
	 * | PRODUCT PK  | ATTRIBUTE X    | PARAMETER PK |
	 * |*************|****************|**************|
	 * | 1           | 1              | 10           |
	 * | 1           | 7              | 11           |
	 * | 2           | 7              | 10           |
	 *
	 * If the validity mapping wasn't involved we'd initialize reference to PARAMETER 10 for PRODUCT with pk = 1 because
	 * the PARAMETER 10 passes the filtering constraint for PRODUCT with pk = 2. This validity mapping allows us to
	 * state that the PARAMETER with pk = 10 should be initialized only in PRODUCT with pk = 2.
	 */
	private static class ValidEntityToReferenceMapping {
		/**
		 * Contains the source entity PK to allowed referenced entity PKs index.
		 * Key: source entity primary key
		 * Value: allowed referenced primary keys
		 */
		private final IntObjectMap<IntSet> mapping;
		/**
		 * Internal, helper variable that contains initialized {@link RoaringBitmap} with all source entity primary keys.
		 */
		private RoaringBitmap knownEntityPrimaryKeys;

		public ValidEntityToReferenceMapping(int expectedEntityCount) {
			this.mapping = new IntObjectHashMap<>(expectedEntityCount);
		}

		/**
		 * Initializes the validity mapping - for `entityPrimaryKey` the `referencedPrimaryKeys` will
		 * become "allowed".
		 */
		public void setInitialVisibilityForEntity(int entityPrimaryKey, @Nonnull Bitmap referencedPrimaryKeys) {
			final IntSet matchingReferencedPrimaryKeys = ofNullable(mapping.get(entityPrimaryKey))
				.orElseGet(() -> {
					final IntHashSet theSet = new IntHashSet();
					mapping.put(entityPrimaryKey, theSet);
					return theSet;
				});
			final OfInt it = referencedPrimaryKeys.iterator();
			while (it.hasNext()) {
				matchingReferencedPrimaryKeys.add(it.nextInt());
			}
		}

		/**
		 * Clears all validity mappings - no referenced entity will be allowed for any of known source entities.
		 */
		public void forbidAll() {
			for (IntObjectCursor<IntSet> entry : mapping) {
				entry.value.clear();
			}
		}

		/**
		 * Clears validity mappings for all source entities except those that are present in the input
		 * `referencedPrimaryKeys` argument. Each source entity not present in the input set will be left with
		 * no referenced entities allowed.
		 */
		public void forbidAllExcept(@Nonnull IntSet referencedPrimaryKeys) {
			for (IntObjectCursor<IntSet> entry : mapping) {
				entry.value.removeAll(it -> !referencedPrimaryKeys.contains(it));
			}
		}

		/**
		 * Removes `referencedPrimaryKey` in the input from allowed referenced entities for all known source entities.
		 * The referenced entity with this primary key will not be visible in any of the known mappings.
		 */
		public void forbid(int referencedPrimaryKey) {
			for (IntObjectCursor<IntSet> entry : mapping) {
				entry.value.removeAll(referencedPrimaryKey);
			}
		}

		/**
		 * Restricts the existing validity mapping - for each known mapping only the set of `referencedPrimaryKeys`
		 * will remain "allowed".
		 */
		public void restrictTo(@Nonnull Bitmap referencedPrimaryKeys) {
			for (IntObjectCursor<IntSet> entry : mapping) {
				entry.value.retainAll(referencedPrimaryKeys::contains);
			}
		}

		/**
		 * Restricts the existing validity mapping - for each record in `entityPrimaryKeys` only the `referencedPrimaryKey`
		 * will remain "allowed".
		 */
		public void restrictTo(@Nonnull Bitmap entityPrimaryKeys, int referencedPrimaryKey) {
			if (knownEntityPrimaryKeys == null) {
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (IntObjectCursor<IntSet> entry : mapping) {
					writer.add(entry.key);
				}
				knownEntityPrimaryKeys = writer.get();
			}
			final RoaringBitmap invalidRecords = RoaringBitmap.andNot(knownEntityPrimaryKeys, RoaringBitmapBackedBitmap.getRoaringBitmap(entityPrimaryKeys));
			for (Integer invalidRecord : invalidRecords) {
				mapping.get(invalidRecord).removeAll(referencedPrimaryKey);
			}
		}

		/**
		 * Returns true if `referencedPrimaryKey` is allowed to be fetched for passed `entityPrimaryKey`.
		 */
		public boolean isReferenceSelected(int entityPrimaryKey, int referencedPrimaryKey) {
			return ofNullable(mapping.get(entityPrimaryKey))
				.map(it -> it.contains(referencedPrimaryKey))
				.orElse(false);
		}

	}

	/**
	 * The carrier DTO for carrying all prefetched entities and groups for specific reference.
	 *
	 * @param entityIndex      prefetched entity bodies indexed by {@link EntityContract#getPrimaryKey()}
	 * @param validityMapping  see detailed description in {@link ValidEntityToReferenceMapping}
	 * @param entityGroupIndex prefetched entity group bodies indexed by {@link EntityContract#getPrimaryKey()}
	 */
	private record PrefetchedEntities(
		@Nonnull Map<Integer, SealedEntity> entityIndex,
		@Nullable ValidEntityToReferenceMapping validityMapping,
		@Nonnull Map<Integer, SealedEntity> entityGroupIndex,

		@Nullable Comparator<ReferenceContract> referenceComparator

	) {

		/**
		 * Looks up the prefetched body by primary key in the index.
		 */
		@Nullable
		public SealedEntity getEntity(int entityPrimaryKey) {
			return entityIndex.get(entityPrimaryKey);
		}

		/**
		 * Looks up the prefetched body by primary key in the index.
		 */
		@Nullable
		public SealedEntity getGroupEntity(int entityPrimaryKey) {
			return entityGroupIndex.get(entityPrimaryKey);
		}

	}

	/**
	 * This implementation will always return empty results for each call.
	 */
	private static class EmptyEntityProvider implements ExistingEntityProvider {
		public static final EmptyEntityProvider INSTANCE = new EmptyEntityProvider();

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey) {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey) {
			return Optional.empty();
		}
	}

	/**
	 * This implementation looks up to the passed `sealedEntity` for existing referenced entity bodies.
	 */
	@RequiredArgsConstructor
	private static class ExistingSealedEntityProvider implements ExistingEntityProvider {
		private final SealedEntity sealedEntity;

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey) {
			return sealedEntity.getReference(referenceName, primaryKey).flatMap(ReferenceContract::getReferencedEntity);
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey) {
			return sealedEntity.getReference(referenceName, primaryKey).flatMap(ReferenceContract::getGroupEntity);
		}
	}
}
