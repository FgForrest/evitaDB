/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.AbstractHierarchyTranslator.TraversalDirection;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor.OrderingDescriptor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.collection.IntegerIntoBitmapCollector;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.core.query.extraResult.translator.hierarchyStatistics.AbstractHierarchyTranslator.stopAtConstraintToPredicate;
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
	 * Requirement for fetching the parents from hierarchical structure.
	 */
	@Nullable private final HierarchyContent hierarchyContent;
	/**
	 * Requirement aggregation exported from {@link EvitaRequest#getReferenceEntityFetch()}.
	 */
	@Nonnull private final Map<String, RequirementContext> requirementContext;
	/**
	 * Default requirement context for the case when the requirement context is not specified for the reference.
	 */
	@Nullable private final RequirementContext defaultRequirementContext;
	/**
	 * The query context used for querying the entities.
	 */
	@Nonnull @Getter private final QueryExecutionContext executionContext;
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
	 * Index of prefetched parents assembled in constructor and quickly available when the entity is requested
	 * by the {@link EntityDecorator} constructor. The key is parent {@link EntityContract#getPrimaryKey()}, the value
	 * is either {@link EntityReferenceWithParent} if bodies were not requested, or full {@link SealedEntity} otherwise.
	 */
	@Nullable private IntObjectMap<EntityClassifierWithParent> parentEntities;
	/**
	 * This request is used to extend the original request on top-level entity. It solves the scenario, when the nested
	 * references are ordered by reference attribute. In that case we need to extend the original request with additional
	 * requirements to fetch the reference attribute for ordering comparator.
	 */
	private EvitaRequest envelopingEntityRequest;

	/**
	 * Utility function that fetches and returns filtered map of {@link SealedEntity} indexed by their primary key
	 * according to `entityPrimaryKeys` argument. The method is reused both for fetching the referenced entities and
	 * their groups.
	 *
	 * @param executionContext        query context that will be used for fetching
	 * @param referenceSchema         the schema of the reference ({@link ReferenceSchemaContract#getName()})
	 * @param entityType              represents the entity type ({@link EntitySchemaContract#getName()}) that should be loaded
	 * @param existingEntityRetriever lambda allowing to reuse already fetched entities from previous decorator instance
	 * @param entityFetch             contains the "richness" requirements for the fetched entities
	 * @param referencedRecordIds     contains array of filtered referenced record ids to fetch
	 * @return filtered map of {@link SealedEntity} indexed by their primary key according to `entityPrimaryKeys` argument
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchReferencedEntities(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityType,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever,
		@Nonnull EntityFetch entityFetch,
		@Nonnull int[] referencedRecordIds
	) {
		// compute set of filtered referenced entity ids
		if (ArrayUtils.isEmpty(referencedRecordIds)) {
			return Collections.emptyMap();
		} else {
			// finally, create the fetch request, get the collection and fetch the referenced entity bodies
			final EvitaRequest fetchRequest = executionContext.getEvitaRequest().deriveCopyWith(entityType, entityFetch);
			final EntityCollection referencedCollection = executionContext.getEntityCollectionOrThrowException(
				entityType, "fetch references"
			);
			return fetchReferenceBodies(
				referenceSchema.getName(), referencedRecordIds, fetchRequest, executionContext,
				referencedCollection, existingEntityRetriever
			);
		}
	}

	/**
	 * Method fetches all `referencedRecordIds` from the `referencedCollection`.
	 *
	 * @param referenceName           just for logging purposes
	 * @param referencedRecordIds     the ids of referenced entities to fetch
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @param executionContext        current query context
	 * @param referencedCollection    the reference collection that will be used for fetching the entities
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities (when only enrichment occurs)
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchReferenceBodies(
		@Nonnull String referenceName,
		@Nonnull int[] referencedRecordIds,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollection referencedCollection,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		final Map<Integer, ServerEntityDecorator> entityIndex;
		final QueryPlanningContext queryContext = executionContext.getQueryContext();
		final QueryPlanningContext nestedQueryContext = referencedCollection.createQueryContext(queryContext, fetchRequest, queryContext.getEvitaSession());
		final Map<String, RequirementContext> referenceEntityFetch = fetchRequest.getReferenceEntityFetch();
		final ReferenceFetcher subReferenceFetcher = createSubReferenceFetcher(
			fetchRequest.getHierarchyContent(),
			referenceEntityFetch,
			fetchRequest.getDefaultReferenceRequirement(),
			nestedQueryContext.createExecutionContext()
		);

		try {
			executionContext.pushStep(QueryPhase.FETCHING_REFERENCES, "Reference name: `" + referenceName + "`");

			entityIndex = fetchEntitiesByIdsIntoIndex(
				referencedRecordIds, fetchRequest, nestedQueryContext, referencedCollection, subReferenceFetcher, existingEntityRetriever
			);
		} finally {
			nestedQueryContext.popStep();
		}
		return entityIndex;
	}

	/**
	 * Method fetches all `parentIds` from the `hierarchyCollection`.
	 *
	 * @param parentIds               the ids of parent entities to fetch
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @param executionContext        current query context
	 * @param hierarchyCollection     the hierarchy collection that will be used for fetching the entities
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities (when only enrichment occurs)
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchParentBodies(
		@Nonnull int[] parentIds,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollection hierarchyCollection,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		final Map<Integer, ServerEntityDecorator> entityIndex;
		final QueryPlanningContext queryContext = executionContext.getQueryContext();
		final QueryPlanningContext nestedQueryContext = hierarchyCollection.createQueryContext(queryContext, fetchRequest, queryContext.getEvitaSession());
		final Map<String, RequirementContext> referenceEntityFetch = fetchRequest.getReferenceEntityFetch();
		final ReferenceFetcher subReferenceFetcher = createSubReferenceFetcher(
			null,
			referenceEntityFetch,
			fetchRequest.getDefaultReferenceRequirement(),
			nestedQueryContext.createExecutionContext()
		);

		try {
			executionContext.pushStep(QueryPhase.FETCHING_PARENTS);
			entityIndex = fetchEntitiesByIdsIntoIndex(
				parentIds, fetchRequest, nestedQueryContext, hierarchyCollection, subReferenceFetcher, existingEntityRetriever
			);
		} finally {
			nestedQueryContext.popStep();
		}
		return entityIndex;
	}

	/**
	 * Method unifies the procedure that allows to fetch entity bodies for all passed `entityPks` in the scope requested
	 * by `fetchRequest`. Entities are fetched from `entityCollection` and limited / enriched using `referenceFetcher`
	 * and `existingEntityRetriever`.
	 *
	 * @return rich entity forms indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchEntitiesByIdsIntoIndex(
		@Nonnull int[] entityPks,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntityCollection entityCollection,
		@Nonnull ReferenceFetcher referenceFetcher,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		final Map<Integer, ServerEntityDecorator> entityIndex;
		entityIndex = CollectionUtils.createHashMap(entityPks.length);
		final List<ServerEntityDecorator> entities = new ArrayList<>(entityPks.length);

		for (int referencedRecordId : entityPks) {
			final Optional<SealedEntity> existingEntity = existingEntityRetriever.apply(referencedRecordId);
			if (existingEntity.isPresent()) {
				// first look up whether we already have the entity prefetched somewhere (in case enrichment occurs)
				entities.add((ServerEntityDecorator) existingEntity.get());
			} else {
				// if not, fetch the fresh entity from the collection
				entityCollection.getEntityDecorator(
						referencedRecordId, fetchRequest, queryContext.getEvitaSession()
					)
					.ifPresent(entities::add);
			}
		}

		// enrich and limit entities them appropriately
		if (!entities.isEmpty()) {
			entityCollection.applyReferenceFetcher(
				entities.stream()
					.map(it -> entityCollection.enrichEntity(it, fetchRequest))
					.map(it -> entityCollection.limitEntity(it, fetchRequest, queryContext.getEvitaSession()))
					.toList(),
				referenceFetcher
			).forEach(refEntity -> entityIndex.put(refEntity.getPrimaryKey(), (ServerEntityDecorator) refEntity));
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
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> referenceEntityFetch,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext nestedQueryContext
	) {
		return referenceEntityFetch.isEmpty() && hierarchyContent == null ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				hierarchyContent,
				referenceEntityFetch,
				defaultRequirementContext,
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
	 * @param executionContext            the query context user for querying the entities
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
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean targetEntityManaged,
		@Nonnull String targetEntityType,
		@Nonnull AtomicReference<FilterByVisitor> filterByVisitor,
		@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour,
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
			final int[] result;
			if (managedReferencesBehaviour == ManagedReferencesBehaviour.EXISTING) {
				if (targetEntityManaged) {
					// we need to filter the referenced entity ids to only those that really exist
					final Optional<EntityCollection> targetEntityCollection = executionContext
						.getQueryContext()
						.getEntityCollection(targetEntityType);
					if (targetEntityCollection.isEmpty()) {
						validityMappingOptional.ifPresent(ValidEntityToReferenceMapping::forbidAll);
						result = EMPTY_INTS;
					} else {
						final Formula existingOnlyReferencedIds = FormulaFactory.and(
							new ConstantFormula(new ArrayBitmap(allReferencedEntityIds)),
							targetEntityCollection.get().getGlobalIndex().getAllPrimaryKeysFormula()
						);
						final Bitmap existingReferences = existingOnlyReferencedIds.compute();
						result = existingReferences.getArray();
						validityMappingOptional.ifPresent(it -> it.restrictTo(existingReferences));
					}
				} else {
					// target entity is not managed - we may allow all referenced entity ids
					validityMappingOptional.ifPresent(it -> it.restrictTo(new BaseBitmap(allReferencedEntityIds)));
					result = allReferencedEntityIds;
				}
			} else {
				// we may allow all referenced entity ids
				validityMappingOptional.ifPresent(it -> it.restrictTo(new BaseBitmap(allReferencedEntityIds)));
				result = allReferencedEntityIds;
			}
			initNestedQueryComparator(
				entityNestedQueryComparator,
				referenceSchema,
				executionContext.getQueryContext()
			);
			return result;
		} else {
			final FilterByVisitor theFilterByVisitor = getFilterByVisitor(executionContext.getQueryContext(), filterByVisitor);
			final List<ReducedEntityIndex> referencedEntityIndexes = theFilterByVisitor.getReferencedRecordEntityIndexes(
				new ReferenceHaving(
					referenceSchema.getName(),
					and(
						ArrayUtils.mergeArrays(
							new FilterConstraint[]{entityPrimaryKeyInSet(allReferencedEntityIds)},
							filterBy.getChildren()
						)
					)
				)
			);

			if (referencedEntityIndexes.isEmpty()) {
				validityMappingOptional.ifPresent(ValidEntityToReferenceMapping::forbidAll);
				return EMPTY_INTS;
			} else {
				final CompositeIntArray referencedPrimaryKeys = new CompositeIntArray();
				final Formula entityPrimaryKeyFormula = new ConstantFormula(new BaseBitmap(entityPrimaryKeys));
				final IntSet foundReferencedIds = new IntHashSet(referencedEntityIndexes.size());
				Formula lastIndexFormula = null;
				Bitmap lastResult = null;
				for (ReducedEntityIndex referencedEntityIndex : referencedEntityIndexes) {
					final ReferenceKey indexDiscriminator = (ReferenceKey) referencedEntityIndex.getIndexKey().getDiscriminator();
					final int referencedPrimaryKey = indexDiscriminator.primaryKey();
					foundReferencedIds.add(referencedPrimaryKey);

					final Formula resultFormula = computeResultWithPassedIndex(
						referencedEntityIndex,
						referenceSchema,
						theFilterByVisitor,
						filterBy,
						entityNestedQueryComparator,
						EntityPrimaryKeyInSet.class
					);

					final Bitmap matchingPrimaryKeys;
					if (lastIndexFormula == resultFormula) {
						matchingPrimaryKeys = lastResult;
					} else {
						final Formula combinedFormula = FormulaFactory.and(
							resultFormula,
							entityPrimaryKeyFormula
						);
						combinedFormula.initialize(executionContext);
						matchingPrimaryKeys = combinedFormula.compute();
						lastIndexFormula = resultFormula;
						lastResult = matchingPrimaryKeys;
					}

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
	 * Method creates {@link Formula} that calculates the set of matching primary keys from input `index` in the context
	 * of the `referenceSchema` analyzing `filterBy` constraint using `filterByVisitor`. The method unifies the logic
	 * around context preparation.
	 *
	 * @param index                       to be used for filter by transformation
	 * @param referenceSchema             related {@link ReferenceSchemaContract}
	 * @param filterByVisitor             visitor to be used for filter by analysis
	 * @param filterBy                    filtering constraint to be analyzed
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param suppressedConstraints       set of constraints that should be ignored in transformation process
	 * @return formula that calculates the result
	 */
	@SafeVarargs
	@Nullable
	private static Formula computeResultWithPassedIndex(
		@Nonnull ReducedEntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull FilterBy filterBy,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		// compute the result formula in the initialized context
		final String referenceName = referenceSchema.getName();
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		final Formula filterFormula = filterByVisitor.executeInContext(
			ReducedEntityIndex.class,
			Collections.singletonList(index),
			ReferenceContent.ALL_REFERENCES,
			processingScope.getEntitySchema(),
			referenceSchema,
			null,
			entityNestedQueryComparator,
			processingScope.withReferenceSchemaAccessor(referenceName),
			(entityContract, attributeName, locale) -> entityContract.getReferences(referenceName)
				.stream()
				.map(it -> it.getAttributeValue(attributeName, locale)),
			() -> {
				filterBy.accept(filterByVisitor);
				// get the result and clear the visitor internal structures
				return filterByVisitor.getFormulaAndClear();
			},
			suppressedConstraints
		);

		initNestedQueryComparator(
			entityNestedQueryComparator,
			referenceSchema,
			filterByVisitor.getQueryContext()
		);

		return filterFormula;
	}

	private static void initNestedQueryComparator(
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull QueryPlanningContext queryContext
	) {
		// in case there is ordering specified and no nested query filter constraint, we need to handle ordering here
		if (entityNestedQueryComparator != null && !entityNestedQueryComparator.isInitialized()) {
			initializeComparatorFromGlobalIndex(
				referenceSchema,
				queryContext.getEntityCollectionOrThrowException(
					referenceSchema.getReferencedEntityType(),
					"order references"
				),
				ofNullable(referenceSchema.getReferencedGroupType())
					.filter(it -> referenceSchema.isReferencedGroupTypeManaged())
					.map(it -> queryContext.getEntityCollectionOrThrowException(it, "order references by group"))
					.orElse(null),
				entityNestedQueryComparator,
				queryContext.getEvitaRequest(),
				queryContext.getEvitaSession()
			);
		}
	}

	/**
	 * Initializes the {@link Sorter} implementation in the passed `entityNestedQueryComparator` from the global index
	 * of the passed `targetEntityCollection`.
	 *
	 * @param referenceSchemaContract     the schema of the reference
	 * @param targetEntityCollection      collection of the referenced entity type
	 * @param targetEntityGroupCollection collection of the referenced entity type group
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param evitaRequest                source evita request
	 * @param evitaSession                current session
	 */
	private static void initializeComparatorFromGlobalIndex(
		@Nonnull ReferenceSchemaContract referenceSchemaContract,
		@Nonnull EntityCollection targetEntityCollection,
		@Nullable EntityCollection targetEntityGroupCollection,
		@Nonnull EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract evitaSession
	) {
		final EntityProperty entityOrderBy = entityNestedQueryComparator.getOrderBy();
		if (entityOrderBy != null) {
			final OrderBy orderBy = new OrderBy(entityOrderBy.getChildren());
			final QueryPlanningContext nestedQueryContext = targetEntityCollection.createQueryContext(
				evitaRequest.deriveCopyWith(
					targetEntityCollection.getEntityType(), null, orderBy,
					entityNestedQueryComparator.getLocale()
				),
				evitaSession
			);
			final QueryPlan queryPlan = QueryPlanner.planNestedQuery(nestedQueryContext);
			final Sorter sorter = queryPlan.getSorter();
			entityNestedQueryComparator.setSorter(nestedQueryContext.createExecutionContext(), sorter);
		}
		final EntityGroupProperty entityGroupOrderBy = entityNestedQueryComparator.getGroupOrderBy();
		if (entityGroupOrderBy != null) {
			Assert.isTrue(
				targetEntityGroupCollection != null,
				"The `entityGroupProperty` ordering is specified in the query but the reference `" + referenceSchemaContract.getName() + "` does not have managed entity group collection!"
			);

			final OrderBy orderBy = new OrderBy(entityGroupOrderBy.getChildren());
			final QueryPlanningContext nestedQueryContext = targetEntityGroupCollection.createQueryContext(
				evitaRequest.deriveCopyWith(targetEntityCollection.getEntityType(), null, orderBy, entityNestedQueryComparator.getLocale()),
				evitaSession
			);

			final QueryPlan queryPlan = QueryPlanner.planNestedQuery(nestedQueryContext);
			final Sorter sorter = queryPlan.getSorter();
			entityNestedQueryComparator.setGroupSorter(nestedQueryContext.createExecutionContext(), sorter);
		}
	}

	@Nonnull
	private static FilterByVisitor getFilterByVisitor(@Nonnull QueryPlanningContext queryContext, @Nonnull AtomicReference<FilterByVisitor> filterByVisitor) {
		return ofNullable(filterByVisitor.get()).orElseGet(() -> {
			final FilterByVisitor newVisitor = createFilterVisitor(queryContext);
			filterByVisitor.set(newVisitor);
			return newVisitor;
		});
	}

	@Nonnull
	private static FilterByVisitor createFilterVisitor(@Nonnull QueryPlanningContext queryContext) {
		return new FilterByVisitor(
			queryContext,
			Collections.emptyList(),
			TargetIndexes.EMPTY
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

	/**
	 * Method allows to replace plain EntityReferenceWithParent with EntityDecorator chain respecting the parent-child
	 * relationship. The method is invoked recursively on each parent.
	 */
	@Nonnull
	private static Optional<SealedEntity> replaceWithSealedEntities(
		@Nonnull EntityReferenceWithParent entityReference,
		@Nonnull Map<Integer, ServerEntityDecorator> parentBodies
	) {
		final ServerEntityDecorator entityDecorator = parentBodies.get(entityReference.getPrimaryKey());
		if (entityDecorator == null) {
			return Optional.empty();
		}

		final EntityClassifierWithParent enrichedParentEntity = entityReference.getParentEntity()
			.flatMap(parentEntity -> replaceWithSealedEntities((EntityReferenceWithParent) parentEntity, parentBodies))
			.map(EntityClassifierWithParent.class::cast)
			.orElse(EntityClassifierWithParent.CONCEALED_ENTITY);

		return Optional.of(
			ServerEntityDecorator.decorate(
				entityDecorator,
				enrichedParentEntity,
				entityDecorator.getLocalePredicate(),
				new HierarchySerializablePredicate(true),
				entityDecorator.getAttributePredicate(),
				entityDecorator.getAssociatedDataPredicate(),
				entityDecorator.getReferencePredicate(),
				entityDecorator.getPricePredicate(),
				entityDecorator.getAlignedNow(),
				entityDecorator.getIoFetchCount() +
					(enrichedParentEntity instanceof ServerEntityDecorator parentDecorator ?
						parentDecorator.getIoFetchCount() :
						0),
				entityDecorator.getIoFetchedBytes() +
					(enrichedParentEntity instanceof ServerEntityDecorator parentDecorator ?
						parentDecorator.getIoFetchedBytes() :
						0)
			)
		);
	}

	/**
	 * Constructor that is used to further enrich already rich entity.
	 */
	public ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityContract entity
	) {
		this(
			hierarchyContent, requirementContext, defaultRequirementContext, executionContext,
			new ExistingEntityDecoratorProvider((EntityDecorator) entity)
		);
	}

	/**
	 * Constructor that is used for initial entity construction.
	 */
	public ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext
	) {
		this(hierarchyContent, requirementContext, defaultRequirementContext, executionContext, EmptyEntityProvider.INSTANCE);
	}

	/**
	 * Internal constructor.
	 */
	private ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull ExistingEntityProvider existingEntityRetriever
	) {
		this.hierarchyContent = hierarchyContent;
		this.requirementContext = requirementContext;
		this.defaultRequirementContext = defaultRequirementContext;
		this.executionContext = executionContext;
		this.existingEntityRetriever = existingEntityRetriever;
	}

	@Nonnull
	@Override
	public <T extends SealedEntity> T initReferenceIndex(@Nonnull T entity, @Nonnull EntityCollectionContract entityCollection) {
		// we need to ensure that references are fetched in order to be able to provide information about them
		final T richEnoughEntity = ((EntityCollection) entityCollection).ensureReferencesFetched(entity);
		final Entity theEntity = richEnoughEntity instanceof EntityDecorator entityDecorator ?
			entityDecorator.getDelegate() : (Entity) richEnoughEntity;

		// prefetch the parents
		if (entityCollection.getSchema().isWithHierarchy()) {
			prefetchParents(
				this.hierarchyContent,
				this.executionContext,
				entityCollection,
				() -> theEntity.getParent().stream().toArray()
			);
		}
		// prefetch the entities
		this.envelopingEntityRequest = prefetchEntities(
			this.requirementContext,
			this.defaultRequirementContext,
			this.executionContext,
			entityCollection.getSchema(),
			this.existingEntityRetriever,
			(referenceName, entityPk) ->
				// we can ignore the entityPk, because this method processes only single entity,
				// and it can't be anything else than the pk of this entity
				toFormula(
					theEntity
						.getReferences(referenceName)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				),
			(referenceName, entityPk) ->
				// we can ignore the entityPk, because this method processes only single entity,
				// and it can't be anything else than the pk of this entity
				toFormula(
					theEntity
						.getReferences(referenceName)
						.stream()
						.map(ReferenceContract::getGroup)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.mapToInt(GroupEntityReference::getPrimaryKey)
						.toArray()
				),
			(referenceName, groupId) ->
				theEntity
					.getReferences(referenceName)
					.stream()
					.filter(it -> it.getGroup().map(GroupEntityReference::primaryKey).map(groupId::equals).orElse(false))
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray(),
			new int[]{theEntity.getPrimaryKey()}
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
		// prefetch the parents
		if (entityCollection.getSchema().isWithHierarchy()) {
			prefetchParents(
				this.hierarchyContent,
				this.executionContext,
				entityCollection,
				() -> richEnoughEntities.stream()
					.map(
						it -> it instanceof EntityDecorator entityDecorator ?
							entityDecorator.getDelegate().getParent() :
							((Entity) it).getParent()
					)
					.filter(OptionalInt::isPresent)
					.mapToInt(OptionalInt::getAsInt)
					.toArray()
			);
		}

		final Supplier<Map<Integer, Entity>> entityIndexSupplier = new EntityIndexSupplier<>(richEnoughEntities);
		final ReferenceMapping groupToEntityIdMapping = new ReferenceMapping(
			entityCollection.getSchema().getReferences().size(),
			referenceName -> richEnoughEntities
				.stream()
				.flatMap(it -> it.getReferences(referenceName).stream())
				.filter(it -> it.getGroup().isPresent())
				.collect(
					Collectors.groupingBy(
						it -> it.getGroup().get().getPrimaryKey(),
						Collectors.mapping(
							ReferenceContract::getReferencedPrimaryKey,
							Collectors.collectingAndThen(
								IntegerIntoBitmapCollector.INSTANCE,
								Bitmap::getArray
							)
						)
					)
				)
		);

		// prefetch the entities
		this.envelopingEntityRequest = prefetchEntities(
			this.requirementContext,
			this.defaultRequirementContext,
			this.executionContext,
			entityCollection.getSchema(),
			existingEntityRetriever,
			(referenceName, entityPk) -> toFormula(
				entityIndexSupplier.get().get(entityPk).getReferences(referenceName)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray()
			),
			(referenceName, entityPk) -> toFormula(
				entityIndexSupplier.get().get(entityPk).getReferences(referenceName)
					.stream()
					.map(ReferenceContract::getGroup)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.mapToInt(GroupEntityReference::getPrimaryKey)
					.toArray()
			),
			groupToEntityIdMapping::get,
			entities.stream()
				.mapToInt(SealedEntity::getPrimaryKey)
				.toArray()
		);

		return richEnoughEntities;
	}

	@Nonnull
	@Override
	public EvitaRequest getEnvelopingEntityRequest() {
		Assert.isPremiseValid(
			this.envelopingEntityRequest != null,
			() -> new GenericEvitaInternalError("Enveloping entity request must be initialized before it's accessed.")
		);
		return this.envelopingEntityRequest;
	}

	@Nullable
	@Override
	public Function<Integer, EntityClassifierWithParent> getParentEntityFetcher() {
		return ofNullable(parentEntities)
			.map(prefetchedEntities -> (Function<Integer, EntityClassifierWithParent>) prefetchedEntities::get)
			.orElse(null);
	}

	@Nullable
	@Override
	public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(prefetchedEntities -> (Function<Integer, SealedEntity>) prefetchedEntities::getEntity)
			.orElse(null);
	}

	@Nullable
	@Override
	public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(prefetchedEntities -> (Function<Integer, SealedEntity>) prefetchedEntities::getGroupEntity)
			.orElse(null);
	}

	@Nonnull
	@Override
	public ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(fetchedEntities.get(referenceSchema.getName()))
			.map(PrefetchedEntities::referenceComparator)
			.orElse(ReferenceComparator.DEFAULT);
	}

	@Nullable
	@Override
	public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema) {
		return ofNullable(
			ofNullable(this.requirementContext.get(referenceSchema.getName())).orElse(this.defaultRequirementContext)
		)
			.map(it -> {
				if (it.filterBy() == null && (it.managedReferencesBehaviour() == ManagedReferencesBehaviour.ANY || !referenceSchema.isReferencedEntityTypeManaged())) {
					return null;
				} else {
					final PrefetchedEntities prefetchedEntities = fetchedEntities.get(referenceSchema.getName());
					if (prefetchedEntities != null) {
						final ValidEntityToReferenceMapping validityMapping = prefetchedEntities.validityMapping();
						if (validityMapping != null) {
							return (BiPredicate<Integer, ReferenceDecorator>) (entityPrimaryKey, referenceDecorator) ->
								ofNullable(referenceDecorator)
									.map(refDec -> validityMapping.isReferenceSelected(entityPrimaryKey, refDec.getReferencedPrimaryKey()))
									.orElse(false);
						}
					}
					return null;
				}
			})
			.orElse(null);
	}

	/**
	 * Method executes all the necessary referenced entities fetch. It loads only those referenced entities that are
	 * mentioned in `requirementContext`. Execution reuses potentially existing fetched referenced entities from
	 * the last enrichment of the same entity.
	 *
	 * @param executionContext                query context used for querying the entity
	 * @param existingEntityRetriever         function that provides access to already fetched referenced entities (relict of last enrichment)
	 * @param referencedEntityIdsFormula      the formula containing superset of all possible referenced entities
	 * @param referencedEntityGroupIdsFormula the formula containing superset of all possible referenced entity groups
	 * @param entityPrimaryKey                the array of top entity primary keys for which the references are being fetched
	 */
	@Nonnull
	private EvitaRequest prefetchEntities(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingEntityProvider existingEntityRetriever,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityGroupIdsFormula,
		@Nonnull BiFunction<String, Integer, int[]> groupToReferencedEntityIdTranslator,
		@Nonnull int[] entityPrimaryKey
	) {
		final AtomicReference<FilterByVisitor> filterByVisitor = new AtomicReference<>();
		final Stream<Entry<String, RequirementContext>> collectedRequirements = defaultRequirementContext == null ?
			requirementContext.entrySet().stream() :
			entitySchema.getReferences()
				.keySet()
				.stream()
				.map(
					referenceSchemaContract -> new SimpleEntry<>(
						referenceSchemaContract,
						requirementContext.getOrDefault(referenceSchemaContract, defaultRequirementContext)
					)
				);

		final DefaultPrefetchRequirementCollector globalPrefetchCollector = new DefaultPrefetchRequirementCollector();
		this.fetchedEntities = collectedRequirements
			.filter(it -> it.getValue().requiresInit())
			.collect(
				Collectors.toMap(
					Entry::getKey,
					it -> {
						final String referenceName = it.getKey();
						final RequirementContext requirements = it.getValue();
						final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
						// initialize requirements with requested attributes
						if (requirements.attributeContent() != null) {
							globalPrefetchCollector.addRequirementsToPrefetch(requirements.attributeContent());
						}

						final Optional<OrderingDescriptor> orderingDescriptor = ofNullable(requirements.orderBy())
							.map(ob -> ReferenceOrderByVisitor.getComparator(
								executionContext.getQueryContext(),
								globalPrefetchCollector,
								ob,
								entitySchema,
								referenceSchema
							)
						);

						final ValidEntityToReferenceMapping validityMapping = new ValidEntityToReferenceMapping(entityPrimaryKey.length);

						final int[] filteredReferencedGroupEntityIds;
						final Map<Integer, ServerEntityDecorator> entityGroupIndex;
						// are we requested to (are we able to) fetch the entity group bodies?
						if (referenceSchema.isReferencedGroupTypeManaged() && referenceSchema.getReferencedGroupType() != null) {
							// if so, fetch them
							filteredReferencedGroupEntityIds = getFilteredReferencedEntityIds(
								entityPrimaryKey, executionContext, referenceSchema,
								referenceSchema.isReferencedGroupTypeManaged(),
								referenceSchema.getReferencedGroupType(),
								filterByVisitor,
								requirements.managedReferencesBehaviour(),
								null, null,
								null, referencedEntityGroupIdsFormula
							);

							if (requirements.entityGroupFetch() != null && !ArrayUtils.isEmpty(filteredReferencedGroupEntityIds)) {
								entityGroupIndex = fetchReferencedEntities(
									executionContext,
									referenceSchema,
									referenceSchema.getReferencedGroupType(),
									pk -> existingEntityRetriever.getExistingGroupEntity(referenceName, pk),
									new EntityFetch(requirements.entityGroupFetch().getRequirements()),
									filteredReferencedGroupEntityIds
								);
							} else {
								entityGroupIndex = Collections.emptyMap();
							}
						} else {
							// if not, leave the index empty
							filteredReferencedGroupEntityIds = null;
							entityGroupIndex = Collections.emptyMap();
						}

						final Map<Integer, ServerEntityDecorator> entityIndex;
						// are we requested to (are we able to) fetch the entity bodies?
						if (referenceSchema.isReferencedEntityTypeManaged()) {
							final int[] filteredReferencedEntityIds = getFilteredReferencedEntityIds(
								entityPrimaryKey, executionContext, referenceSchema,
								referenceSchema.isReferencedEntityTypeManaged(),
								referenceSchema.getReferencedEntityType(),
								filterByVisitor,
								requirements.managedReferencesBehaviour(),
								requirements.filterBy(),
								validityMapping,
								orderingDescriptor
									.map(OrderingDescriptor::nestedQueryComparator)
									.orElse(null), referencedEntityIdsFormula
							);
							// set them to the comparator instance, if such is provided
							// this prepares the "pre-sorted" arrays in this comparator for faster sorting
							orderingDescriptor
								.map(OrderingDescriptor::nestedQueryComparator)
								.ifPresent(
									comparator -> comparator.setFilteredEntities(
										filteredReferencedEntityIds, filteredReferencedGroupEntityIds,
										entityPk -> groupToReferencedEntityIdTranslator.apply(referenceName, entityPk)
									)
								);

							if (requirements.entityFetch() != null && !ArrayUtils.isEmpty(filteredReferencedEntityIds)) {
								// if so, fetch them
								entityIndex = fetchReferencedEntities(
									executionContext,
									referenceSchema,
									referenceSchema.getReferencedEntityType(),
									pk -> existingEntityRetriever.getExistingEntity(referenceName, pk),
									requirements.entityFetch(),
									filteredReferencedEntityIds
								);
							} else {
								entityIndex = Collections.emptyMap();
							}
						} else {
							// if not, leave the index empty
							entityIndex = Collections.emptyMap();
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

		return ofNullable(globalPrefetchCollector.getEntityFetch())
			.map(it -> executionContext.getEvitaRequest().deriveCopyWith(executionContext.getSchema().getName(), it))
			.orElse(executionContext.getEvitaRequest());
	}

	/**
	 * Method executes all the necessary hierarchical entities fetch.  Execution reuses potentially existing fetched
	 * hierarchical entities from the last enrichment of the same entity.
	 *
	 * @param hierarchyContent  the requirement specification for the hierarchical entities
	 * @param executionContext  query context used for querying the entity
	 * @param parentIdsSupplier the function returning the array of parent entity primary keys to prefetch
	 */
	private void prefetchParents(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollectionContract entityCollection,
		@Nonnull Supplier<int[]> parentIdsSupplier
	) {
		// prefetch only if there is any requirement
		if (hierarchyContent != null) {
			final int[] parentIds = parentIdsSupplier.get();
			final IntObjectHashMap<EntityClassifierWithParent> parentEntityReferences = new IntObjectHashMap<>(parentIds.length);
			final boolean bodyRequired = hierarchyContent.getEntityFetch().isPresent();
			final IntHashSet allReferencedParents = bodyRequired ?
				new IntHashSet(parentIds.length * 3) : null;

			// initialize used data structures
			final EntitySchemaContract entitySchema = entityCollection.getSchema();
			final String entityType = entitySchema.getName();
			final QueryPlanningContext queryContext = executionContext.getQueryContext();
			final GlobalEntityIndex globalIndex = entityCollection instanceof EntityCollection ec ?
				ec.getGlobalIndex() :
				queryContext.getGlobalEntityIndexIfExists(entityType)
					.orElseThrow(() -> new CollectionNotFoundException(entityType));
			// scope predicate limits the parent traversal
			final HierarchyTraversalPredicate scopePredicate = hierarchyContent.getStopAt()
				.map(
					stopAt -> {
						final HierarchyTraversalPredicate predicate = stopAtConstraintToPredicate(
							TraversalDirection.BOTTOM_UP,
							stopAt,
							queryContext,
							globalIndex,
							entitySchema,
							null
						);
						predicate.initializeIfNotAlreadyInitialized(executionContext);
						return predicate;
					}
				)
				.orElse(HierarchyTraversalPredicate.NEVER_STOP_PREDICATE);

			// first, construct EntityReferenceWithParent for each requested parent id
			for (int parentId : parentIds) {
				final AtomicReference<EntityReferenceWithParent> theParent = new AtomicReference<>();
				if (bodyRequired) {
					allReferencedParents.add(parentId);
				}
				globalIndex.traverseHierarchyToRoot(
					(node, level, distance, traverser) -> {
						if (scopePredicate.test(node.entityPrimaryKey(), level, distance + 1)) {
							if (scopePredicate instanceof SelfTraversingPredicate selfTraversingPredicate) {
								selfTraversingPredicate.traverse(node.entityPrimaryKey(), level, distance + 1, traverser);
							} else {
								traverser.run();
							}
							theParent.set(new EntityReferenceWithParent(entityType, node.entityPrimaryKey(), theParent.get()));
							if (bodyRequired) {
								allReferencedParents.add(node.entityPrimaryKey());
							}
						}
					},
					parentId
				);
				parentEntityReferences.put(parentId, theParent.get());
			}

			// second, replace the EntityReferenceWithParent with EntityDecorator if the bodies are requested
			if (bodyRequired) {
				final EvitaRequest fetchRequest = executionContext.getEvitaRequest().deriveCopyWith(
					entityType,
					hierarchyContent.getEntityFetch().get()
				);
				final EntityCollection referencedCollection = queryContext.getEntityCollectionOrThrowException(
					entityType, "fetch parents"
				);
				final Map<Integer, ServerEntityDecorator> parentBodies = fetchParentBodies(
					allReferencedParents.toArray(), fetchRequest, executionContext,
					referencedCollection,
					existingEntityRetriever::getExistingParentEntity
				);

				// replace the previous EntityReferenceWithParent with EntityDecorator with filled parent
				final IntObjectHashMap<EntityClassifierWithParent> parentSealedEntities = new IntObjectHashMap<>(parentIds.length);
				for (IntObjectCursor<EntityClassifierWithParent> parentRef : parentEntityReferences) {
					parentSealedEntities.put(
						parentRef.key,
						ofNullable(parentRef.value)
							.map(EntityReferenceWithParent.class::cast)
							.flatMap(it -> replaceWithSealedEntities(it, parentBodies))
							.orElse(null)
					);
				}
				// initialize rich SealedEntities index
				this.parentEntities = parentSealedEntities;
			} else {
				// initialize plain EntityReferenceWithParent - no body was requested
				this.parentEntities = parentEntityReferences;
			}
		}
	}

	/**
	 * Interface allows accessing the already fetched bodies of entities in existing data structure. It allows
	 * accessing existing entities in {@link SealedEntity} in case the reference entity loader is used for enrichment
	 * only.
	 */
	private interface ExistingEntityProvider {

		/**
		 * Return parent entity from the existing {@link SealedEntity} or empty result.
		 */
		@Nonnull
		Optional<SealedEntity> getExistingParentEntity(int primaryKey);

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
		@Nonnull Map<Integer, ServerEntityDecorator> entityIndex,
		@Nullable ValidEntityToReferenceMapping validityMapping,
		@Nonnull Map<Integer, ServerEntityDecorator> entityGroupIndex,

		@Nullable ReferenceComparator referenceComparator

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
		public Optional<SealedEntity> getExistingParentEntity(int primaryKey) {
			return Optional.empty();
		}

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
	private static class ExistingEntityDecoratorProvider implements ExistingEntityProvider {
		private final EntityDecorator entityDecorator;

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingParentEntity(int primaryKey) {
			return entityDecorator.getParentEntityWithoutCheckingPredicate()
				.filter(SealedEntity.class::isInstance)
				.map(SealedEntity.class::cast);
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey) {
			return entityDecorator.getReferenceWithoutCheckingPredicate(referenceName, primaryKey).flatMap(ReferenceContract::getReferencedEntity);
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey) {
			return entityDecorator.getReferenceWithoutCheckingPredicate(referenceName, primaryKey).flatMap(ReferenceContract::getGroupEntity);
		}
	}

	/**
	 * This DTO envelopes cached access to referenced entity group primary key to referenced entity primary keys mapping.
	 * This mapping is used in {@link EntityNestedQueryComparator#setFilteredEntities(int[], int[], Function)} method
	 * when the references are sorted first by group entity and then by referenced entity.
	 */
	private static class ReferenceMapping {
		private final Map<String, Map<Integer, int[]>> mapping;
		private final Function<String, Map<Integer, int[]>> computeFct;

		public ReferenceMapping(int expectedSize, @Nonnull Function<String, Map<Integer, int[]>> computeFct) {
			this.mapping = CollectionUtils.createHashMap(expectedSize);
			this.computeFct = computeFct;
		}

		/**
		 * Returns (and lazily computes) array of referenced entity primary keys for passed `groupEntityPrimaryKey` of
		 * group entity.
		 *
		 * @param referenceName         name of the reference
		 * @param groupEntityPrimaryKey primary key of the group entity
		 * @return array of referenced entity primary keys
		 */
		@Nonnull
		public int[] get(@Nonnull String referenceName, int groupEntityPrimaryKey) {
			return mapping.computeIfAbsent(referenceName, computeFct).get(groupEntityPrimaryKey);
		}

	}

	/**
	 * This class lazily provides (and caches) index of entities by their primary key. The entities always
	 * represent the original entity in the evitaDB and not the {@link EntityDecorator} wrapper.
	 */
	@RequiredArgsConstructor
	private static class EntityIndexSupplier<T extends SealedEntity> implements Supplier<Map<Integer, Entity>> {
		private final List<T> richEnoughEntities;
		private Map<Integer, Entity> memoizedResult;

		@Override
		public Map<Integer, Entity> get() {
			if (memoizedResult == null) {
				memoizedResult = richEnoughEntities.stream()
					.collect(
						Collectors.toMap(
							EntityContract::getPrimaryKey,
							it -> it instanceof EntityDecorator entityDecorator ?
								entityDecorator.getDelegate() : (Entity) it
						)
					);
			}
			return memoizedResult;
		}
	}

}
