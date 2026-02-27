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

package io.evitadb.core.query.fetch;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.query.order.EntityPrimaryKeyExact;
import io.evitadb.api.query.order.EntityPrimaryKeyInFilter;
import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.ReferenceSetFetcher;
import io.evitadb.api.requestResponse.data.structure.References.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
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
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator.EntityGroupPropertyWithScopes;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator.EntityPropertyWithScopes;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.ReferenceKeeper;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import io.evitadb.spi.store.catalog.chunk.ServerChunkTransformerAccessor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
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
	/**
	 * Comparator that allows to sort reduced entity indexes by their discriminator.
	 */
	private static final Comparator<AbstractReducedEntityIndex> BY_DISCRIMINATOR = Comparator.comparing(
		rede -> {
			final EntityIndexKey indexKey = rede.getIndexKey();
			return Objects.requireNonNull(
				(RepresentativeReferenceKey) indexKey.discriminator()
			);
		}
	);
	/**
	 * Requirement for fetching the parents from hierarchical structure.
	 */
	@Nullable private final HierarchyContent hierarchyContent;
	/**
	 * Requirement aggregation exported from {@link EvitaRequest#getReferenceEntityFetch()}.
	 */
	@Nonnull private final Map<String, RequirementContext> referenceFetch;
	/**
	 * Requirement aggregation exported from {@link EvitaRequest#getNamedReferenceEntityFetch()}.
	 */
	@Nonnull private final Map<ReferenceContentKey, RequirementContext> namedReferenceFetch;
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
	 * Function providing access to {@link ChunkTransformer} implementations for particular references. Accessor is
	 * simple wrapper over {@link EvitaRequest} method references.
	 */
	@Nonnull private final ChunkTransformerAccessor chunkTransformerAccessor;
	/**
	 * Index of prefetched entities assembled by {@link #initReferenceIndex} via {@link #prefetchEntities} and quickly
	 * available when the entity is requested by the {@link EntityDecorator} constructor. The key is
	 * {@link ReferenceSchemaContract#getName()}, the value is the information containing the indexes of fetched
	 * entities and their groups, information about their ordering and validity index.
	 *
	 * @see PrefetchedEntities
	 */
	@Nullable private ReferenceSetFetcher fetchedEntities;
	/**
	 * Similar to {@link #fetchedEntities} but indexed by {@link ReferenceContentKey#instanceName()} for named references.
	 * The instance name must be unique among other named references within the same entity type.
	 *
	 * @see PrefetchedEntities
	 */
	@Nullable private Map<String, ReferenceSetFetcher> namedFetchedEntities;
	/**
	 * Index of prefetched parents assembled by {@link #initReferenceIndex} via {@link #prefetchParents} and quickly
	 * available when the entity is requested by the {@link EntityDecorator} constructor. The key is parent
	 * {@link EntityContract#getPrimaryKey()}, the value is either {@link EntityReferenceWithParent} if bodies were
	 * not requested, or full {@link SealedEntity} otherwise.
	 */
	@Nullable private IntObjectMap<EntityClassifierWithParent> parentEntities;
	/**
	 * This request is used to extend the original request on top-level entity. It solves the scenario, when the nested
	 * references are ordered by reference attribute. In that case we need to extend the original request with additional
	 * requirements to fetch the reference attribute for ordering comparator.
	 */
	private EvitaRequest envelopingEntityRequest;

	/**
	 * Converts an array of record IDs into a Formula object.
	 *
	 * @param recordIds an array of integers representing record IDs; may be null or empty.
	 * @return a Formula object representing the record IDs; returns an empty formula if the input array is null or empty.
	 */
	@Nonnull
	static Formula toFormula(@Nullable int[] recordIds) {
		return ArrayUtils.isEmpty(recordIds) ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(
				new ArrayBitmap(recordIds)
			);
	}

	/**
	 * Converts a bitmap of record IDs into a Formula object.
	 *
	 * @param recordIds a bitmap of record IDs; may be null or empty
	 * @return a Formula object representing the record IDs; returns an empty formula if the input bitmap is null
	 * or empty
	 */
	@Nonnull
	static Formula toFormula(@Nullable Bitmap recordIds) {
		return recordIds == null || recordIds.isEmpty() || recordIds instanceof EmptyBitmap ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(recordIds);
	}

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
		@Nullable FilterBy entityFilterBy,
		@Nullable OrderBy entityOrderBy,
		@Nonnull EntityFetch entityFetch,
		@Nonnull Bitmap referencedRecordIds
	) {
		// compute set of filtered referenced entity ids
		if (referencedRecordIds.isEmpty()) {
			return Collections.emptyMap();
		} else {
			// finally, create the fetch request, get the collection and fetch the referenced entity bodies
			final EvitaRequest fetchRequest = executionContext.getEvitaRequest().deriveCopyWith(
				entityType,
				unwrapFilterBy(entityFilterBy),
				unwrapOrderBy(entityOrderBy),
				entityFetch
			);
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
	 * Unwraps the provided {@code entityFilterBy} object to extract entity-level filter criteria.
	 * Extracts children of {@link EntityHaving} as well as standalone {@link EntityPrimaryKeyInSet}
	 * and {@link EntityLocaleEquals} constraints. {@link GroupHaving} constraints are intentionally
	 * excluded because they are handled separately via group-level indexes.
	 *
	 * @param entityFilterBy the {@code FilterBy} object potentially containing entity-level constraints
	 * @return a new {@code FilterBy} instance with the extracted entity-level constraints,
	 * or null if the {@code entityFilterBy} is null or contains no matching constraints
	 */
	@Nullable
	private static FilterBy unwrapFilterBy(@Nullable FilterBy entityFilterBy) {
		final FilterBy unwrappedEntityFilterBy;
		if (entityFilterBy != null) {
			final List<FilterConstraint> entityConstraints = FinderVisitor.findConstraints(
				entityFilterBy,
				it -> it instanceof EntityHaving ||
					it instanceof EntityPrimaryKeyInSet ||
					it instanceof EntityLocaleEquals,
				SeparateEntityScopeContainer.class::isInstance
			);
			unwrappedEntityFilterBy = filterBy(
				entityConstraints
					.stream()
					.flatMap(
						it -> it instanceof ConstraintContainer<?> cc
							? Arrays.stream(cc.getChildren())
							: Stream.of(it)
					)
					.map(FilterConstraint.class::cast)
					.toArray(FilterConstraint[]::new)
			);
		} else {
			unwrappedEntityFilterBy = null;
		}
		return unwrappedEntityFilterBy;
	}

	/**
	 * Unwraps the provided {@code OrderBy} object by extracting its relevant constraints and
	 * constructing a new {@code OrderBy} instance based on its children.
	 *
	 * @param entityOrderBy the {@code OrderBy} object to be unwrapped; may be null
	 * @return a new {@code OrderBy} instance derived from the provided {@code OrderBy},
	 * or null if the input is null or its constraints do not meet the expected criteria
	 */
	@Nullable
	private static OrderBy unwrapOrderBy(@Nullable OrderBy entityOrderBy) {
		final OrderBy unwrappedEntityOrderBy;
		if (entityOrderBy != null) {
			final List<OrderConstraint> entityConstraints = FinderVisitor.findConstraints(
				entityOrderBy,
				it -> it instanceof EntityProperty ||
					it instanceof EntityPrimaryKeyExact ||
					it instanceof EntityPrimaryKeyNatural ||
					it instanceof EntityPrimaryKeyInFilter,
				EntityProperty.class::isInstance
			);
			unwrappedEntityOrderBy = orderBy(
				entityConstraints
					.stream()
					.flatMap(it -> it instanceof EntityProperty eh ? Arrays.stream(eh.getChildren()) : Stream.of(it))
					.toArray(OrderConstraint[]::new)
			);
		} else {
			unwrappedEntityOrderBy = null;
		}
		return unwrappedEntityOrderBy;
	}

	/**
	 * Method fetches all `referencedRecordIds` from the `referencedCollection`.
	 *
	 * @param referenceName           just for logging purposes
	 * @param referencedRecordIds     the ids of referenced entities to fetch
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @param executionContext        current query context
	 * @param referencedCollection    the reference collection that will be used for fetching the entities
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities (when only
	 *                                enrichment occurs)
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchReferenceBodies(
		@Nonnull String referenceName,
		@Nonnull Bitmap referencedRecordIds,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollection referencedCollection,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		return fetchEntityBodies(
			referenceName, referencedRecordIds, fetchRequest, executionContext,
			referencedCollection, existingEntityRetriever,
			fetchRequest.getHierarchyContent(), QueryPhase.FETCHING_REFERENCES
		);
	}

	/**
	 * Method fetches all `parentIds` from the `hierarchyCollection`.
	 *
	 * @param parentIds               the ids of parent entities to fetch
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @param executionContext        current query context
	 * @param hierarchyCollection     the hierarchy collection that will be used for fetching the entities
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities (when only
	 *                                enrichment occurs)
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchParentBodies(
		@Nonnull Bitmap parentIds,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollection hierarchyCollection,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		return fetchEntityBodies(
			null, parentIds, fetchRequest, executionContext,
			hierarchyCollection, existingEntityRetriever,
			null, QueryPhase.FETCHING_PARENTS
		);
	}

	/**
	 * Shared implementation for fetching entity bodies by their IDs from a given collection. Used by both
	 * {@link #fetchReferenceBodies} and {@link #fetchParentBodies} which differ only in hierarchy content,
	 * query phase, and telemetry message.
	 *
	 * @param referenceName           the reference name for telemetry logging, or null for parent fetching
	 * @param entityIds               the ids of entities to fetch
	 * @param fetchRequest            request that describes the requested richness of the fetched entities
	 * @param executionContext        current query context
	 * @param entityCollection        the collection from which entities will be fetched
	 * @param existingEntityRetriever lambda providing access to potentially already prefetched entities
	 * @param hierarchyContent        the hierarchy content for the sub-reference fetcher, or null for parents
	 * @param queryPhase              the telemetry query phase to record
	 * @return fetched entities indexed by their {@link EntityContract#getPrimaryKey()}
	 */
	@Nonnull
	private static Map<Integer, ServerEntityDecorator> fetchEntityBodies(
		@Nullable String referenceName,
		@Nonnull Bitmap entityIds,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollection entityCollection,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever,
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull QueryPhase queryPhase
	) {
		final Map<Integer, ServerEntityDecorator> entityIndex;
		final QueryPlanningContext queryContext = executionContext.getQueryContext();
		final QueryPlanningContext nestedQueryContext = entityCollection.createQueryContext(
			queryContext, fetchRequest, queryContext.getEvitaSession()
		);
		final Map<String, RequirementContext> referenceEntityFetch = fetchRequest.getReferenceEntityFetch();
		final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = fetchRequest.getNamedReferenceEntityFetch();
		final ReferenceFetcher subReferenceFetcher = createSubReferenceFetcher(
			hierarchyContent,
			referenceEntityFetch,
			namedReferenceEntityFetch,
			fetchRequest.getDefaultReferenceRequirement(),
			nestedQueryContext.createExecutionContext(),
			new ServerChunkTransformerAccessor(fetchRequest)
		);

		try {
			if (referenceName != null) {
				executionContext.pushStep(queryPhase, "Reference name: `" + referenceName + "`");
			} else {
				executionContext.pushStep(queryPhase);
			}
			entityIndex = fetchEntitiesByIdsIntoIndex(
				entityIds, fetchRequest, nestedQueryContext, entityCollection,
				subReferenceFetcher, existingEntityRetriever
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
		@Nonnull Bitmap entityPks,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntityCollection entityCollection,
		@Nonnull ReferenceFetcher referenceFetcher,
		@Nonnull IntFunction<Optional<SealedEntity>> existingEntityRetriever
	) {
		final Map<Integer, ServerEntityDecorator> entityIndex;
		entityIndex = CollectionUtils.createHashMap(entityPks.size());

		for (int referencedRecordId : entityPks) {
			if (!entityIndex.containsKey(referencedRecordId)) {
				final Optional<SealedEntity> existingEntity = existingEntityRetriever.apply(referencedRecordId);
				if (existingEntity.isPresent()) {
					// first look up whether we already have the entity prefetched somewhere (in case enrichment occurs)
					final ServerEntityDecorator entity = (ServerEntityDecorator) existingEntity.get();
					entityIndex.put(entity.getPrimaryKey(), entity);
				} else {
					// if not, fetch the fresh entity from the collection
					entityCollection.fetchEntityDecorator(
							referencedRecordId, fetchRequest, queryContext.getEvitaSession()
						)
						.ifPresent(entity -> entityIndex.put(entity.getPrimaryKey(), entity));
				}
			}
		}

		// enrich and limit entities appropriately
		if (!entityIndex.isEmpty()) {
			// we will replace all the entities with enriched and limited versions
			entityCollection.limitAndFetchExistingEntities(
				entityIndex.values(), fetchRequest, referenceFetcher
			).forEach(refEntity -> entityIndex.put(refEntity.getPrimaryKey(), (ServerEntityDecorator) refEntity));
		}
		return entityIndex;
	}

	/**
	 * Creates new instance of this class, that is loads the sub-references targeting different entity type
	 * (collection) and having different scope (request). The sub-fetcher will limit loading references to the set
	 * of `referencedRecordIds` in the input parameter.
	 *
	 * @param hierarchyContent          the hierarchy content requirement for the sub-fetcher, may be null if no
	 *                                  hierarchy is needed
	 * @param requirementContext        the requirements for fetching the references keyed by reference name
	 * @param namedRequirementContext   the requirements for fetching named references keyed by
	 *                                  {@link ReferenceContentKey}
	 * @param defaultRequirementContext the default requirement context applied to references without explicit
	 *                                  requirements, may be null
	 * @param nestedQueryContext        the query execution context to use for the sub-fetcher
	 * @param chunkTransformerAccessor  the accessor providing {@link ChunkTransformer} implementations for
	 *                                  particular references
	 * @return the new instance of reference loader prepared to provide the {@link SealedEntity} instances, or
	 * {@link ReferenceFetcher#NO_IMPLEMENTATION} if no references need to be fetched
	 */
	@Nonnull
	private static ReferenceFetcher createSubReferenceFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nonnull Map<ReferenceContentKey, RequirementContext> namedRequirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext nestedQueryContext,
		@Nonnull ChunkTransformerAccessor chunkTransformerAccessor
	) {
		return requirementContext.isEmpty() && namedRequirementContext.isEmpty() && hierarchyContent == null ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				hierarchyContent,
				requirementContext,
				namedRequirementContext,
				defaultRequirementContext,
				nestedQueryContext,
				chunkTransformerAccessor
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
	@Nonnull
	private static Map<Scope, Bitmap> getAllReferencedEntityIds(
		@Nonnull Map<Scope, int[]> entityPrimaryKeys,
		@Nonnull Function<Integer, Formula> referencedEntityResolver,
		@Nullable ValidEntityToReferenceMapping validityMapping
	) {
		// aggregate all referenced primary keys into one sorted distinct array
		final Map<Scope, Formula[]> formulas = CollectionUtils.createHashMap(entityPrimaryKeys.size());
		for (Entry<Scope, int[]> entry : entityPrimaryKeys.entrySet()) {
			final int[] epks = entry.getValue();
			final Formula[] scopeFormulas = new Formula[epks.length];
			formulas.put(entry.getKey(), scopeFormulas);
			for (int i = 0; i < epks.length; i++) {
				int epk = epks[i];
				final Formula referencedEntityIds = referencedEntityResolver.apply(epk);
				// Initializes starting validity relations in `validityMapping` where each entity sees
				// all its referenced entities. This initial visibility setup will be refined during fetch process.
				if (validityMapping != null) {
					validityMapping.setInitialVisibilityForEntity(
						epk,
						referencedEntityIds.compute()
					);
				}
				// return the referenced entity primary keys
				scopeFormulas[i] = referencedEntityIds;
			}
		}

		final Map<Scope, Bitmap> result = CollectionUtils.createHashMap(formulas.size());
		for (Entry<Scope, Formula[]> entry : formulas.entrySet()) {
			result.put(
				entry.getKey(),
				FormulaFactory.or(entry.getValue()).compute()
			);
		}
		return result;
	}

	/**
	 * Returns array of referenced entity ids that are referenced by any of passed `entityPrimaryKeys` that are filtered
	 * to match the passed `filterBy`.
	 *
	 * @param entityPrimaryKeys                   the set of entity ids whose references should be looked up, indexed by scope
	 * @param executionContext                    the query context used for querying the entities
	 * @param entitySchema                        the schema of the entity owning the references
	 * @param referenceSchema                     the schema of the reference
	 * @param targetEntityType                    the entity type of the referenced entities (or group type when resolving groups)
	 * @param filterByVisitor                     the visitor that will be used for traversing the constraint
	 * @param managedReferencesBehaviour          defines whether only existing (managed) references should be returned or all
	 * @param filterBy                            the filtering constraint itself
	 * @param validityMapping                     see detailed description in {@link ValidEntityToReferenceMapping}
	 * @param entityNestedQueryComparator         comparator that holds information about requested ordering so that we can
	 *                                            apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                            in an optimal way
	 * @param referencedEntityResolver            lambda allowing to get primary keys of all entities referenced by
	 *                                            entity with certain primary key (we need this to distinguish
	 *                                            retrieving data for entities and groups)
	 * @param groupToReferencedEntityIdTranslator function that maps group entity PKs to referenced entity PKs for
	 *                                            a given reference name; used to evaluate {@link GroupHaving}
	 *                                            constraints; may be {@code null} when group filtering is
	 *                                            not applicable
	 * @return filtered bitmap of referenced entity ids
	 */
	@Nonnull
	private static Bitmap getFilteredReferencedEntityIds(
		@Nonnull Map<Scope, int[]> entityPrimaryKeys,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String targetEntityType,
		@Nonnull ReferenceKeeper<FilterByVisitor> filterByVisitor,
		@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable FilterBy filterBy,
		@Nullable ValidEntityToReferenceMapping validityMapping,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityResolver,
		@Nullable BiFunction<String, Integer, int[]> groupToReferencedEntityIdTranslator
	) {
		// initialize the main data-structures we'll be working later in this method
		final Optional<ValidEntityToReferenceMapping> validityMappingOptional = ofNullable(validityMapping);
		final Bitmap result;

		// we need to gather all referenced entity ids and initialize validity mapping for all the passed entity PKs
		final Map<Scope, Bitmap> allReferencedEntityPksFromEntitiesInScope = getAllReferencedEntityIds(
			entityPrimaryKeys,
			entityPk -> referencedEntityResolver.apply(referenceSchema.getName(), entityPk),
			validityMapping
		);

		// extract working context
		final QueryPlanningContext queryContext = executionContext.getQueryContext();
		final boolean referencedEntityTypeManaged = referenceSchema.isReferencedEntityTypeManaged();
		// if referenced entity type is not managed, we cannot filter by its properties
		if (!referencedEntityTypeManaged && filterBy != null && !QueryUtils.findConstraints(
			filterBy, EntityHaving.class, SeparateEntityScopeContainer.class).isEmpty()) {
			throw new EntityNotManagedException(referenceSchema.getReferencedEntityType());
		}
		// identify scopes that need to be searched for referenced entities
		final Set<Scope> examinedScopes = gatherSearchedScopes(
			filterBy,
			executionContext.getQueryContext().getScopes()
		);

		// collect the referenced entity ids
		final Map<Scope, Bitmap> allReferencedEntityPksInScope;
		final Bitmap allReferencedEntityPks;
		// if query required filtering or only existing references and the referenced entity type is managed
		if ((managedReferencesBehaviour == ManagedReferencesBehaviour.EXISTING || filterBy != null) && referencedEntityTypeManaged) {
			// we need to filter the referenced entity ids to only those that really exist
			allReferencedEntityPksInScope = limitToExistingEntities(
				combineWithOr(allReferencedEntityPksFromEntitiesInScope.values()),
				queryContext.getEntityCollection(targetEntityType).orElse(null),
				examinedScopes
			);
			// and also create unified lookup over all referenced entity ids in all requested scopes
			allReferencedEntityPks = combineWithOr(allReferencedEntityPksInScope.values());
		} else {
			// otherwise we just use all referenced entity pks as they are
			allReferencedEntityPksInScope = allReferencedEntityPksFromEntitiesInScope;
			// and also create unified lookup over all referenced entity ids in all requested scopes
			allReferencedEntityPks = combineWithOr(allReferencedEntityPksFromEntitiesInScope.values());
		}

		if (allReferencedEntityPks.isEmpty()) {
			// if nothing was found, quickly finish
			validityMappingOptional.ifPresent(ValidEntityToReferenceMapping::forbidAll);
			result = EmptyBitmap.INSTANCE;
		} else {
			final RoaringBitmap referencedPrimaryKeys;
			if (filterBy == null) {
				// if there is no filtering, we can quickly return all referenced pks
				final RoaringBitmapWriter<RoaringBitmap> referencedPrimaryKeysWriter = RoaringBitmapBackedBitmap.buildWriter();
				writeAllReferencedPrimaryKeys(allReferencedEntityPks, referencedPrimaryKeysWriter);

				initNestedQueryComparator(
					entityNestedQueryComparator,
					referenceSchema,
					queryContext
				);

				referencedPrimaryKeys = referencedPrimaryKeysWriter.get();
				result = referencedPrimaryKeys.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(referencedPrimaryKeys);

				validityMappingOptional.ifPresent(it -> it.restrictTo(result));
			} else {
				// otherwise we need to filter the referenced pks according to the filterBy constraint
				final FilterByVisitor theFilterByVisitor = getFilterByVisitor(queryContext, filterByVisitor);

				// separate GroupHaving constraints from entity-level constraints and evaluate group filter
				final GroupFilterSeparation groupFilter = separateAndEvaluateGroupFilter(
					filterBy, referenceSchema, theFilterByVisitor,
					examinedScopes, groupToReferencedEntityIdTranslator
				);
				final FilterConstraint[] entityLevelChildren = groupFilter.entityLevelChildren();
				final FilterBy entityLevelFilterBy = groupFilter.entityLevelFilterBy();
				final Bitmap allowedByGroupFilter = groupFilter.allowedByGroupFilter();

				referencedPrimaryKeys = theFilterByVisitor
					.getProcessingScope()
					.doWithScope(
						examinedScopes,
						() -> {
							// build the ReferenceHaving constraint, avoiding mergeArrays when no entity-level children
							final FilterConstraint pkConstraint = entityPrimaryKeyInSet(allReferencedEntityPks.getArray());
							final FilterConstraint[] havingChildren = entityLevelChildren.length == 0
								? new FilterConstraint[]{pkConstraint}
								: ArrayUtils.mergeArrays(new FilterConstraint[]{pkConstraint}, entityLevelChildren);

							final List<ReducedEntityIndex> referencedEntityIndexes = allReferencedEntityPks.isEmpty() ?
								Collections.emptyList() :
								theFilterByVisitor.getReferencedRecordEntityIndexes(
									new ReferenceHaving(
										referenceSchema.getName(),
										and(havingChildren)
									),
									examinedScopes,
									(es, eik) -> null
								);
							// pre-filter by group constraint before sorting to reduce sort size
							if (allowedByGroupFilter != null && !referencedEntityIndexes.isEmpty()) {
								referencedEntityIndexes.removeIf(idx -> {
									final RepresentativeReferenceKey key = idx.getRepresentativeReferenceKey();
									return !allowedByGroupFilter.contains(key.primaryKey());
								});
							}
							if (!referencedEntityIndexes.isEmpty()) {
								referencedEntityIndexes.sort(BY_DISCRIMINATOR);
							}
							// we need to identify which scopes are not indexed for this reference
							final EnumSet<Scope> nonIndexedScopes = EnumSet.noneOf(Scope.class);
							for (Scope scope : examinedScopes) {
								if (!referenceSchema.isIndexedInScope(scope)) {
									nonIndexedScopes.add(scope);
								}
							}

							final RoaringBitmapWriter<RoaringBitmap> referencedPrimaryKeysWriter = RoaringBitmapBackedBitmap.buildWriter();
							final IntSet foundReferencedIds = new IntHashSet(allReferencedEntityPks.size());
							// if there is at least one indexed scope, we need to process the indexes
							if (nonIndexedScopes.size() < examinedScopes.size()) {
								Formula lastIndexFormula = null;
								RepresentativeReferenceKey lastDiscriminator = null;

								for (ReducedEntityIndex referencedEntityIndex : referencedEntityIndexes) {
									final EntityIndexKey indexKey = referencedEntityIndex.getIndexKey();
									final RepresentativeReferenceKey discriminator = Objects.requireNonNull(
										(RepresentativeReferenceKey) indexKey.discriminator()
									);

									foundReferencedIds.add(discriminator.primaryKey());

									final Formula resultFormula = computeResultWithPassedIndex(
										referencedEntityIndex,
										entitySchema,
										referenceSchema,
										theFilterByVisitor,
										entityLevelFilterBy != null ? entityLevelFilterBy : filterBy,
										entityNestedQueryComparator
									);

									if (lastDiscriminator == null) {
										lastDiscriminator = discriminator;
										lastIndexFormula = resultFormula;
									} else if (Objects.equals(lastDiscriminator, discriminator)) {
										if (lastIndexFormula != resultFormula) {
											lastIndexFormula = FormulaFactory.or(
												lastIndexFormula,
												resultFormula
											);
										}
									} else {
										Assert.isPremiseValid(
											lastIndexFormula != null,
											"Last index formula must be initialized!"
										);
										lastIndexFormula.initialize(executionContext);
										final Bitmap matchingPks = lastIndexFormula.compute();
										final RepresentativeReferenceKey finalLastDiscriminator = lastDiscriminator;
										if (matchingPks.isEmpty()) {
											validityMappingOptional.ifPresent(
												it -> it.restrictTo(finalLastDiscriminator, EmptyBitmap.INSTANCE));
										} else {
											validityMappingOptional.ifPresent(
												it -> it.restrictTo(finalLastDiscriminator, matchingPks));
											referencedPrimaryKeysWriter.add(lastDiscriminator.primaryKey());
										}

										lastIndexFormula = resultFormula;
										lastDiscriminator = discriminator;
									}
								}
								if (lastDiscriminator != null && lastIndexFormula != null) {
									final RepresentativeReferenceKey finalLastDiscriminator = lastDiscriminator;
									lastIndexFormula.initialize(executionContext);
									final Bitmap matchingPks = lastIndexFormula.compute();
									if (matchingPks.isEmpty()) {
										validityMappingOptional.ifPresent(
											it -> it.restrictTo(finalLastDiscriminator, EmptyBitmap.INSTANCE));
									} else {
										validityMappingOptional.ifPresent(
											it -> it.restrictTo(finalLastDiscriminator, matchingPks));
										referencedPrimaryKeysWriter.add(lastDiscriminator.primaryKey());
									}
								}

								// finally, add all referenced pks from non-indexed scopes
								for (Scope nonIndexedScope : nonIndexedScopes) {
									// we need to add all referenced entities from source entities in scope that is not indexed
									// i.e. archived product relates to live product via non-indexed reference
									final Bitmap refPksFromEntitiesInScope = allReferencedEntityPksFromEntitiesInScope.get(
										nonIndexedScope);
									if (refPksFromEntitiesInScope != null) {
										addBitmapToSetAndWriter(
											refPksFromEntitiesInScope, foundReferencedIds, referencedPrimaryKeysWriter);
									}
									// but we also need to add referenced entities that exist in the non-indexed scope
									// i.e. live product relates to archived product via indexed reference
									// references are indexed only within the same scope and here it goes across scopes
									final Bitmap refPks = allReferencedEntityPksInScope.get(nonIndexedScope);
									if (refPks != null) {
										addBitmapToSetAndWriter(
											refPks, foundReferencedIds, referencedPrimaryKeysWriter);
									}
								}
							} else {
								// if there are only non-indexed scopes, we need to add all referenced pks
								// from all source entities in scope
								addBitmapToSetAndWriter(
									allReferencedEntityPks, foundReferencedIds, referencedPrimaryKeysWriter);
							}

							validityMappingOptional.ifPresent(
								it -> {
									if (referenceSchema.getCardinality().allowsDuplicates()) {
										it.forbidAllExceptIncludingDiscriminators(foundReferencedIds);
									} else {
										it.forbidAllExcept(foundReferencedIds);
									}
								}
							);

							initNestedQueryComparator(
								entityNestedQueryComparator,
								referenceSchema,
								theFilterByVisitor.getQueryContext()
							);

							return referencedPrimaryKeysWriter.get();
						}
					);
				result = referencedPrimaryKeys.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(referencedPrimaryKeys);
			}
		}

		return result;
	}

	/**
	 * Separates {@link GroupHaving} constraints from entity-level constraints in the provided filter,
	 * evaluates group constraints against group-level indexes, and maps matching group PKs to a bitmap
	 * of allowed referenced entity PKs.
	 *
	 * When no {@link GroupHaving} is present or group filtering is not applicable, the original filter
	 * is returned unchanged and {@code allowedByGroupFilter} is {@code null}.
	 *
	 * The method performs a single pass over filter children to both detect and separate
	 * {@link GroupHaving} constraints, avoiding a redundant scan. In the common single-{@link GroupHaving}
	 * case, the constraint's children array is reused directly without intermediate collection allocation.
	 *
	 * @param filterBy                            the filter containing both group-level and entity-level constraints
	 * @param referenceSchema                     the schema of the reference being filtered
	 * @param filterByVisitor                     the visitor used to evaluate group constraints
	 * @param examinedScopes                      the scopes to evaluate
	 * @param groupToReferencedEntityIdTranslator function that maps group PKs to referenced entity PKs;
	 *                                            may be {@code null} when group filtering is not applicable
	 * @return a {@link GroupFilterSeparation} containing the entity-level filter and the
	 * allowed referenced entity PKs bitmap (or {@code null} if no group filtering applies)
	 */
	@Nonnull
	private static GroupFilterSeparation separateAndEvaluateGroupFilter(
		@Nonnull FilterBy filterBy,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Set<Scope> examinedScopes,
		@Nullable BiFunction<String, Integer, int[]> groupToReferencedEntityIdTranslator
	) {
		// fast path: group filtering not applicable
		if (groupToReferencedEntityIdTranslator == null) {
			return new GroupFilterSeparation(filterBy, null);
		}

		final FilterConstraint[] allFilterChildren = filterBy.getChildren();

		// single pass: separate entity-level from group-level constraints and detect GroupHaving presence
		final List<FilterConstraint> entityLevel = new ArrayList<>(allFilterChildren.length);
		FilterConstraint[] groupChildren = null;
		List<FilterConstraint> groupAccumulator = null;
		for (FilterConstraint child : allFilterChildren) {
			if (child instanceof GroupHaving gh) {
				if (groupChildren == null) {
					// first GroupHaving: reuse its children array directly
					groupChildren = gh.getChildren();
				} else {
					// rare: multiple GroupHaving constraints — accumulate
					if (groupAccumulator == null) {
						groupAccumulator = new ArrayList<>(8);
						Collections.addAll(groupAccumulator, groupChildren);
					}
					Collections.addAll(groupAccumulator, gh.getChildren());
				}
			} else {
				entityLevel.add(child);
			}
		}

		// no GroupHaving found — return original filter unchanged
		if (groupChildren == null) {
			return new GroupFilterSeparation(filterBy, null);
		}

		// resolve final group children array
		final FilterConstraint[] finalGroupChildren = groupAccumulator != null
			? groupAccumulator.toArray(FilterConstraint[]::new)
			: groupChildren;

		// build entity-level FilterBy (null if all constraints were group-level)
		final FilterBy entityLevelFilterBy = entityLevel.isEmpty()
			? null
			: new FilterBy(entityLevel.toArray(FilterConstraint[]::new));

		// evaluate group constraints against group-level indexes
		final Bitmap matchingGroupPks = filterByVisitor.getMatchingGroupEntityPrimaryKeys(
			new ReferenceHaving(
				referenceSchema.getName(),
				and(finalGroupChildren)
			),
			examinedScopes,
			(es, eik) -> null
		);

		if (matchingGroupPks.isEmpty()) {
			return new GroupFilterSeparation(entityLevelFilterBy, EmptyBitmap.INSTANCE);
		}

		// map matching group PKs to allowed referenced entity PKs
		final RoaringBitmapWriter<RoaringBitmap> allowedWriter = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt groupIt = matchingGroupPks.iterator();
		while (groupIt.hasNext()) {
			final int[] referencedByGroup = groupToReferencedEntityIdTranslator.apply(
				referenceSchema.getName(), groupIt.nextInt()
			);
			for (int refPk : referencedByGroup) {
				allowedWriter.add(refPk);
			}
		}
		final RoaringBitmap allowedBitmap = allowedWriter.get();
		final Bitmap allowedByGroupFilter = allowedBitmap.isEmpty()
			? EmptyBitmap.INSTANCE
			: new BaseBitmap(allowedBitmap);

		return new GroupFilterSeparation(entityLevelFilterBy, allowedByGroupFilter);
	}

	/**
	 * Combines multiple bitmaps using a logical OR operation into a single bitmap containing all primary keys
	 * present in any of the input bitmaps.
	 *
	 * @param values collection of bitmaps to combine
	 * @return a single bitmap representing the OR combination of all input bitmaps, or an empty bitmap if the result
	 * is empty
	 */
	@Nonnull
	private static Bitmap combineWithOr(@Nonnull Collection<Bitmap> values) {
		final RoaringBitmap[] bitmaps = new RoaringBitmap[values.size()];
		int i = 0;
		for (Bitmap value : values) {
			bitmaps[i++] = RoaringBitmapBackedBitmap.getRoaringBitmap(value);
		}
		final RoaringBitmap orResult = RoaringBitmap.or(bitmaps);
		return orResult.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(orResult);
	}

	/**
	 * Limits the referenced entity IDs to include only those that exist in the provided entity collection
	 * and scopes, optionally updating the validity mapping as necessary.
	 *
	 * @param allReferencedEntityIdsIncludingNonExisting A {@link Bitmap} containing IDs of all referenced
	 *                                                   entities, including ones that may not exist.
	 * @param entityCollection                           The {@link EntityCollection} containing indexes used to determine which
	 *                                                   entities exist. If null, no filtering is applied and an empty bitmap is returned.
	 * @param examinedScopes                             A set of {@link Scope} objects defining the scopes within which to check for
	 *                                                   existing entities.
	 * @return A {@link Bitmap} containing the IDs of entities that exist in the specified scopes of
	 * the entity collection. If no entities exist for the given parameters, an empty bitmap is returned.
	 */
	@Nonnull
	private static Map<Scope, Bitmap> limitToExistingEntities(
		@Nonnull Bitmap allReferencedEntityIdsIncludingNonExisting,
		@Nullable EntityCollection entityCollection,
		@Nonnull Set<Scope> examinedScopes
	) {
		if (entityCollection != null) {
			final RoaringBitmap allPks = RoaringBitmapBackedBitmap.getRoaringBitmap(
				allReferencedEntityIdsIncludingNonExisting);
			final Map<Scope, Bitmap> existingEntityIdsByScope = CollectionUtils.createHashMap(examinedScopes.size());
			for (Scope scope : examinedScopes) {
				final EntityIndex indexByKeyIfExists = entityCollection
					.getIndexByKeyIfExists(
						new EntityIndexKey(EntityIndexType.GLOBAL, scope)
					);
				if (indexByKeyIfExists != null) {
					final RoaringBitmap andResult = RoaringBitmap.and(
						RoaringBitmapBackedBitmap.getRoaringBitmap(indexByKeyIfExists.getAllPrimaryKeys()),
						allPks
					);
					existingEntityIdsByScope.put(
						scope,
						andResult.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(andResult)
					);
				}
			}
			return existingEntityIdsByScope;
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * Gathers and returns the set of scopes that are to be examined based on the provided filter and initial scopes.
	 * If the filter contains a local `EntityScope` constraint, its scopes are added to the provided scopes.
	 *
	 * In other words it combines "current" scopes with the scopes that are explicitly requested in the filter or
	 * nearest `entityHaving` constraint. Currently it doesn't support more complex scenarios with multiple
	 * `EntityScope` constraints - in that case the error is thrown (but it could be handled if some time is invested).
	 *
	 * @param filterBy the filter criteria that may contain constraints influencing the scopes to search
	 * @param scopes   the initial set of scopes to be considered for examination
	 * @return a set of scopes that includes the initial scopes and any additional scopes derived from the filter criteria
	 */
	@Nonnull
	private static Set<Scope> gatherSearchedScopes(@Nullable FilterBy filterBy, @Nonnull Set<Scope> scopes) {
		if (filterBy == null) {
			return scopes;
		}
		final EntityScope localScope = QueryUtils.findConstraint(filterBy, EntityScope.class);
		final Set<Scope> examinedScopes;
		if (localScope == null) {
			examinedScopes = scopes;
		} else {
			examinedScopes = EnumSet.noneOf(Scope.class);
			examinedScopes.addAll(scopes);
			examinedScopes.addAll(localScope.getScope());
		}
		return examinedScopes;
	}

	/**
	 * Writes all referenced primary keys from the provided bitmap of entity IDs
	 * into the specified bitmap writer.
	 *
	 * @param allReferencedEntityIds      the bitmap containing all referenced entity IDs to be written
	 * @param referencedPrimaryKeysWriter the bitmap writer where the primary keys will be added
	 */
	private static void writeAllReferencedPrimaryKeys(
		@Nonnull Bitmap allReferencedEntityIds,
		@Nonnull RoaringBitmapWriter<RoaringBitmap> referencedPrimaryKeysWriter
	) {
		final OfInt it = allReferencedEntityIds.iterator();
		while (it.hasNext()) {
			referencedPrimaryKeysWriter.add(it.nextInt());
		}
	}

	/**
	 * Iterates over all primary keys in the given bitmap and adds each one to both the target set and the bitmap
	 * writer. This avoids duplicating the iteration pattern that appears multiple times in
	 * {@link #getFilteredReferencedEntityIds}.
	 *
	 * @param bitmap       the source bitmap whose elements should be added
	 * @param targetSet    the set to which each primary key is added (used for later exclusion checks)
	 * @param targetWriter the bitmap writer to which each primary key is appended
	 */
	private static void addBitmapToSetAndWriter(
		@Nonnull Bitmap bitmap,
		@Nonnull IntSet targetSet,
		@Nonnull RoaringBitmapWriter<RoaringBitmap> targetWriter
	) {
		final OfInt it = bitmap.iterator();
		while (it.hasNext()) {
			final int pk = it.nextInt();
			targetSet.add(pk);
			targetWriter.add(pk);
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
	 * @return formula that calculates the result
	 */
	@Nullable
	private static Formula computeResultWithPassedIndex(
		@Nonnull AbstractReducedEntityIndex index,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull FilterBy filterBy,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator
	) {
		// compute the result formula in the initialized context
		final String referenceName = referenceSchema.getName();
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return filterByVisitor.executeInContextAndIsolatedFormulaStack(
			AbstractReducedEntityIndex.class,
			() -> Collections.singletonList(index),
			ReferenceContent.ALL_REFERENCES,
			entitySchema,
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
			EntityPrimaryKeyInSet.class
		);
	}

	/**
	 * Conditionally initializes the nested query comparator from the global index of the referenced entity collection.
	 * The initialization is performed only when the comparator is present and has not been initialized yet (i.e. no
	 * nested query filter was applied that would have already initialized it).
	 *
	 * @param entityNestedQueryComparator comparator to initialize, may be null if no ordering is requested
	 * @param referenceSchema             the schema of the reference whose entities are being ordered
	 * @param queryContext                the query planning context providing access to entity collections and session
	 */
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
	 * @param referenceSchema             the schema of the reference
	 * @param targetEntityCollection      collection of the referenced entity type
	 * @param targetEntityGroupCollection collection of the referenced entity type group
	 * @param entityNestedQueryComparator comparator that holds information about requested ordering so that we can
	 *                                    apply it during entity filtering (if it's performed) and pre-initialize it
	 *                                    in an optimal way
	 * @param evitaRequest                source evita request
	 * @param evitaSession                current session
	 */
	private static void initializeComparatorFromGlobalIndex(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityCollection targetEntityCollection,
		@Nullable EntityCollection targetEntityGroupCollection,
		@Nonnull EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract evitaSession
	) {
		final EntityPropertyWithScopes entityOrderBy = entityNestedQueryComparator.getOrderBy();
		if (entityOrderBy != null) {
			final OrderBy orderBy = entityOrderBy.createStandaloneOrderBy();
			final QueryPlanningContext nestedQueryContext = targetEntityCollection.createQueryContext(
				evitaRequest.deriveCopyWith(
					targetEntityCollection.getEntityType(),
					null,
					orderBy,
					entityNestedQueryComparator.getLocale(),
					entityOrderBy.scopes()
				),
				evitaSession
			);
			final QueryPlan queryPlan = QueryPlanner.planNestedQuery(
				nestedQueryContext,
				() -> "ordering reference `" + referenceSchema.getName() +
					"` by entity `" + targetEntityCollection.getEntityType() + "`: " + entityOrderBy
			);
			entityNestedQueryComparator.setSorters(nestedQueryContext.createExecutionContext(), queryPlan.getSorters());
		}
		final EntityGroupPropertyWithScopes entityGroupOrderBy = entityNestedQueryComparator.getGroupOrderBy();
		if (entityGroupOrderBy != null) {
			Assert.isTrue(
				targetEntityGroupCollection != null,
				"The `entityGroupProperty` ordering is specified in the query but the reference `" + referenceSchema.getName() + "` does not have managed entity group collection!"
			);

			final OrderBy orderBy = entityGroupOrderBy.createStandaloneOrderBy();
			final QueryPlanningContext nestedQueryContext = targetEntityGroupCollection.createQueryContext(
				evitaRequest.deriveCopyWith(
					targetEntityGroupCollection.getEntityType(),
					null,
					orderBy,
					entityNestedQueryComparator.getLocale(),
					entityGroupOrderBy.scopes()
				),
				evitaSession
			);

			final QueryPlan queryPlan = QueryPlanner.planNestedQuery(
				nestedQueryContext,
				() -> "ordering reference groups `" + referenceSchema.getName() +
					"` by entity group `" + targetEntityGroupCollection.getEntityType() + "`: " + entityGroupOrderBy
			);
			entityNestedQueryComparator.setGroupSorters(
				nestedQueryContext.createExecutionContext(), queryPlan.getSorters());
		}
	}

	/**
	 * Retrieves or creates a new instance of {@code FilterByVisitor}.
	 * If the provided {@link ReferenceKeeper} already contains a {@code FilterByVisitor}, returns it.
	 * Otherwise, creates a new {@code FilterByVisitor} using the {@code QueryPlanningContext},
	 * stores it in the keeper, and returns the new instance.
	 *
	 * @param queryContext    the context used to create a new {@code FilterByVisitor} if necessary
	 * @param filterByVisitor a keeper holding the {@code FilterByVisitor} instance
	 * @return the existing or newly created {@code FilterByVisitor} instance
	 */
	@Nonnull
	private static FilterByVisitor getFilterByVisitor(
		@Nonnull QueryPlanningContext queryContext, @Nonnull ReferenceKeeper<FilterByVisitor> filterByVisitor) {
		FilterByVisitor visitor = filterByVisitor.getReference();
		if (visitor == null) {
			visitor = createFilterVisitor(queryContext);
			filterByVisitor.setReference(visitor);
		}
		return visitor;
	}

	/**
	 * Creates and returns a new instance of FilterByVisitor configured with the provided query context.
	 *
	 * @param queryContext the query planning context used to configure the FilterByVisitor; must not be null
	 * @return a new instance of FilterByVisitor
	 */
	@Nonnull
	private static FilterByVisitor createFilterVisitor(@Nonnull QueryPlanningContext queryContext) {
		return new FilterByVisitor(
			queryContext,
			Collections.emptyList(),
			TargetIndexes.EMPTY
		);
	}

	/**
	 * Replaces a plain {@link EntityReferenceWithParent} with a fully decorated {@link ServerEntityDecorator} chain
	 * respecting the parent-child relationship. The method is invoked recursively on each parent in the chain,
	 * building the hierarchy from the root down.
	 *
	 * @param entityReference the entity reference with parent chain to replace with sealed entities
	 * @param parentBodies    the map of already fetched parent entity bodies indexed by primary key
	 * @return an optional containing the sealed entity with its parent chain, or empty if the entity body
	 * was not found in {@code parentBodies}
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
	 * Groups a list of entities by their scope and returns a map where each scope
	 * is associated with an array of primary keys of the entities belonging to that scope.
	 *
	 * @param entities the list of entities to be processed, where each entity must extend SealedEntity. Must not be null.
	 * @return a map where the keys are scopes and the values are arrays of primary keys associated with each scope.
	 * Each scope only includes entities from the input list.
	 */
	@Nonnull
	private static <T extends SealedEntity> Map<Scope, int[]> getPrimaryKeysIndexedByScope(@Nonnull List<T> entities) {
		final EnumMap<Scope, CompositeIntArray> primaryKeysByScope = new EnumMap<>(Scope.class);
		for (T entity : entities) {
			final CompositeIntArray intArray = primaryKeysByScope.computeIfAbsent(
				entity.getScope(),
				scope -> new CompositeIntArray()
			);
			intArray.add(entity.getPrimaryKeyOrThrowException());
		}
		final EnumMap<Scope, int[]> result = new EnumMap<>(Scope.class);
		for (Entry<Scope, CompositeIntArray> entry : primaryKeysByScope.entrySet()) {
			result.put(entry.getKey(), entry.getValue().toArray());
		}
		return result;
	}

	/**
	 * Traverses the hierarchy structure for the given parent IDs and builds the parent chain
	 * ({@link EntityReferenceWithParent}) for each of them. The method walks from each parent towards the root,
	 * respecting the stop predicate defined in the {@code hierarchyContent}, and registers the full parent chain
	 * in {@code parentEntityReferences} so that shared ancestors are reused across entities.
	 *
	 * Duplicate parent IDs (after sorting) are skipped. When {@code allReferencedParents} is provided (i.e. bodies
	 * are requested), every discovered parent primary key is added to this writer for later bulk body fetching.
	 *
	 * @param entityType             the entity type name used to construct {@link EntityReferenceWithParent} instances
	 * @param scope                  the scope within which the hierarchy index is looked up
	 * @param hierarchyContent       the hierarchy content requirement containing the stop-at predicate specification
	 * @param queryPlanningContext   the query planning context providing access to global entity indexes
	 * @param queryExecutionContext  the query execution context used to initialize the stop predicate if needed
	 * @param entitySchema           the schema of the entity whose hierarchy is being traversed
	 * @param allReferencedParents   optional bitmap writer collecting all discovered parent primary keys for bulk
	 *                               body fetching; null when bodies are not requested
	 * @param parentEntityReferences output map where each parent primary key is associated with its
	 *                               {@link EntityReferenceWithParent} chain (including ancestors)
	 * @param parentIds              the array of parent primary keys to identify and traverse
	 */
	private static void identifyParents(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull HierarchyContent hierarchyContent,
		@Nonnull QueryPlanningContext queryPlanningContext,
		@Nonnull QueryExecutionContext queryExecutionContext,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable RoaringBitmapWriter<RoaringBitmap> allReferencedParents,
		@Nonnull IntObjectHashMap<EntityClassifierWithParent> parentEntityReferences,
		int[] parentIds
	) {
		final Optional<GlobalEntityIndex> globalIndexRef = queryPlanningContext.getGlobalEntityIndexIfExists(
			entityType, scope);
		if (globalIndexRef.isPresent()) {
			final GlobalEntityIndex globalIndex = globalIndexRef.get();
			// scope predicate limits the parent traversal
			final HierarchyTraversalPredicate stopPredicate = hierarchyContent.getStopAt()
				.map(
					stopAt -> {
						final HierarchyTraversalPredicate predicate = stopAtConstraintToPredicate(
							TraversalDirection.BOTTOM_UP,
							stopAt,
							queryPlanningContext,
							globalIndex,
							entitySchema,
							null
						);
						if (predicate != null) {
							predicate.initializeIfNotAlreadyInitialized(queryExecutionContext);
						}
						return predicate;
					}
				)
				.orElse(HierarchyTraversalPredicate.NEVER_STOP_PREDICATE);

			// sort parents first
			Arrays.sort(parentIds);
			final ReferenceKeeper<EntityReferenceWithParent> theParent = new ReferenceKeeper<>(null);
			boolean hasPreviousParent = false;
			int previousParent = 0;
			// first, construct EntityReferenceWithParent for each requested parent id
			for (int parentId : parentIds) {
				if (hasPreviousParent && previousParent == parentId) {
					// skip duplicates
					continue;
				}
				hasPreviousParent = true;
				previousParent = parentId;
				theParent.setReference(null);
				if (allReferencedParents != null) {
					allReferencedParents.add(parentId);
				}
				globalIndex.traverseHierarchyToRoot(
					(node, level, distance, traverser) -> {
						if (stopPredicate.test(node.entityPrimaryKey(), level, distance + 1)) {
							if (stopPredicate instanceof SelfTraversingPredicate selfTraversingPredicate) {
								selfTraversingPredicate.traverse(
									node.entityPrimaryKey(), level, distance + 1, traverser);
							} else {
								traverser.run();
							}
							theParent.setReference(new EntityReferenceWithParent(
								entityType, node.entityPrimaryKey(),
								theParent.getReference()
							));
							if (allReferencedParents != null) {
								allReferencedParents.add(node.entityPrimaryKey());
							}
						}
					},
					parentId
				);
				// register the parent and also all its parents recursively, there is high chance other entities
				// will share the same parents
				EntityReferenceWithParent parent = theParent.getReference();
				parentEntityReferences.put(parentId, parent);
				while (parent != null) {
					final EntityClassifierWithParent parentEntity = parent.getParentEntity().orElse(null);
					if (parentEntity == null) {
						parent = null;
					} else {
						parent = new EntityReferenceWithParent(
							parentEntity.getType(),
							parentEntity.getPrimaryKeyOrThrowException(),
							parentEntity.getParentEntity().orElse(null)
						);
						parentEntityReferences.put(parent.primaryKey(), parent);
					}
				}
			}
		}
	}

	/**
	 * Creates an instance of {@link PrefetchedEntities} by handling the retrieval and processing of referenced entities,
	 * groups, and their required metadata based on the provided requirements and context.
	 *
	 * @param executionContext                    the context within which a query is executed
	 * @param entitySchema                        the schema defining the structure and relationships of the entity
	 * @param existingEntityRetriever             a provider for retrieving existing entities by their primary keys
	 * @param referencedEntityIdsFormula          a function to compute formulas for referenced entity IDs based on a given
	 *                                            reference name and an integer parameter
	 * @param groupToReferencedEntityIdTranslator a function for translating group IDs to the referenced entity IDs for
	 *                                            a specific reference
	 * @param referencedEntityToGroupIdTranslator a function for translating referenced entity IDs to group IDs based on
	 *                                            entity type, reference name, and group entity primary key
	 * @param entityPrimaryKey                    a map containing the primary keys of the entities involved in the operation, keyed by
	 *                                            the relevant scope
	 * @param referenceName                       the name of the reference for which entities are being prefetched
	 * @param requirements                        the requirements specifying the attributes, filters, and behaviors for fetching entities
	 * @param globalPrefetchCollector             a collector responsible for gathering global prefetch requirements across multiple
	 *                                            operations
	 * @param filterByVisitor                     a reference to a visitor instance used to apply filters based on criteria
	 * @return a {@link PrefetchedEntities} instance containing the prefetched entities, validity mappings, grouped
	 * entities, and any applicable comparators for sorting
	 */
	@Nonnull
	private static PrefetchedEntities createPrefetchedEntities(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ExistingEntityProvider existingEntityRetriever,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull BiFunction<String, Integer, int[]> groupToReferencedEntityIdTranslator,
		@Nonnull TriFunction<Integer, String, Integer, IntStream> referencedEntityToGroupIdTranslator,
		@Nonnull Map<Scope, int[]> entityPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull RequirementContext requirements,
		@Nonnull DefaultPrefetchRequirementCollector globalPrefetchCollector,
		@Nonnull ReferenceKeeper<FilterByVisitor> filterByVisitor
	) {
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
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

		final ValidEntityToReferenceMapping validityMapping = new ValidEntityToReferenceMapping(
			entityPrimaryKey.values().stream().mapToInt(array -> array.length).sum(),
			referenceSchema
		);
		final int[] filteredReferencedEntityIdsArray;
		final Map<Integer, ServerEntityDecorator> entityIndex;
		final BitmapSlicer slicer = new BitmapSlicer(
			entityPrimaryKey,
			referenceName,
			referencedEntityIdsFormula,
			referencedEntityToGroupIdTranslator,
			ServerChunkTransformerAccessor.convertIfNecessary(requirements.referenceChunkTransformer())
		);

		final Bitmap filteredReferencedEntityIds = getFilteredReferencedEntityIds(
			entityPrimaryKey,
			executionContext,
			entitySchema,
			referenceSchema,
			referenceSchema.getReferencedEntityType(),
			filterByVisitor,
			requirements.managedReferencesBehaviour(),
			requirements.filterBy(),
			validityMapping,
			orderingDescriptor
				.map(OrderingDescriptor::nestedQueryComparator)
				.orElse(null),
			referencedEntityIdsFormula,
			groupToReferencedEntityIdTranslator
		);
		// apply chunking if necessary
		if (requirements.entityFetch() != null) {
			if (!referenceSchema.isReferencedEntityTypeManaged()) {
				throw new EntityNotManagedException(referenceSchema.getReferencedEntityType());
			}
			final Bitmap filteredAndSlicedReferencedIds = slicer.sliceEntityIds(
				toFormula(filteredReferencedEntityIds),
				validityMapping
			);
			if (!filteredAndSlicedReferencedIds.isEmpty()) {
				// if so, fetch them
				entityIndex = fetchReferencedEntities(
					executionContext,
					referenceSchema,
					referenceSchema.getReferencedEntityType(),
					pk -> existingEntityRetriever.getExistingEntity(referenceName, pk),
					requirements.filterBy(),
					requirements.orderBy(),
					requirements.entityFetch(),
					filteredAndSlicedReferencedIds
				);
			} else {
				entityIndex = Collections.emptyMap();
			}
		} else {
			entityIndex = Collections.emptyMap();
		}
		filteredReferencedEntityIdsArray = filteredReferencedEntityIds.getArray();

		final int[] filteredReferencedGroupEntityIdsArray;
		final Map<Integer, ServerEntityDecorator> entityGroupIndex;
		// are we requested to (are we able to) fetch the entity group bodies?
		if (referenceSchema.isReferencedGroupTypeManaged() && referenceSchema.getReferencedGroupType() != null) {
			// if so, fetch them
			final Bitmap filteredReferencedGroupEntityIds = getFilteredReferencedEntityIds(
				entityPrimaryKey, executionContext, entitySchema, referenceSchema,
				referenceSchema.getReferencedGroupType(),
				filterByVisitor,
				requirements.managedReferencesBehaviour(),
				null,
				null,
				null,
				slicer::getGroupIds,
				null
			);

			if (requirements.entityGroupFetch() != null && !filteredReferencedGroupEntityIds.isEmpty()) {
				entityGroupIndex = fetchReferencedEntities(
					executionContext,
					referenceSchema,
					referenceSchema.getReferencedGroupType(),
					pk -> existingEntityRetriever.getExistingGroupEntity(referenceName, pk),
					requirements.filterBy(),
					requirements.orderBy(),
					new EntityFetch(requirements.entityGroupFetch().getRequirements()),
					filteredReferencedGroupEntityIds
				);
			} else {
				entityGroupIndex = Collections.emptyMap();
			}

			filteredReferencedGroupEntityIdsArray = filteredReferencedGroupEntityIds.getArray();
		} else {
			// if not, leave the index empty
			filteredReferencedGroupEntityIdsArray = ArrayUtils.EMPTY_INT_ARRAY;
			entityGroupIndex = Collections.emptyMap();
		}

		// set them to the comparator instance, if such is provided
		// this prepares the "pre-sorted" arrays in this comparator for faster sorting
		orderingDescriptor
			.map(OrderingDescriptor::nestedQueryComparator)
			.ifPresent(
				comparator -> comparator.setFilteredEntities(
					filteredReferencedEntityIdsArray, filteredReferencedGroupEntityIdsArray,
					entityPk -> groupToReferencedEntityIdTranslator.apply(referenceName, entityPk)
				)
			);

		return new PrefetchedEntities(
			entityIndex,
			requirements.filterBy() == null &&
				requirements.managedReferencesBehaviour() == ManagedReferencesBehaviour.ANY ?
				null : validityMapping,
			entityGroupIndex,
			orderingDescriptor
				.map(OrderingDescriptor::comparator)
				.orElse(null)
		);
	}

	/**
	 * Provides a stream of collected requirement entries, combining provided requirement context entries
	 * with additional default context entries derived from the schema.
	 *
	 * If a default requirement context is provided, entries for references that are not already in the
	 * provided requirement context are generated using this default context. These additional entries
	 * are combined with the existing entries in the requirement context.
	 *
	 * @param requirementContext        a map containing the specific requirement contexts keyed by reference name
	 * @param defaultRequirementContext the default requirement context applied to references not explicitly listed
	 *                                  in {@code requirementContext}, may be null when no defaults are needed
	 * @param entitySchema              the entity schema used to discover all available reference names and check
	 *                                  whether referenced entity/group types are managed
	 * @return a stream of entries mapping reference names to their requirement contexts
	 */
	@Nonnull
	private static Stream<Entry<String, RequirementContext>> getCollectedRequirementStream(
		@Nonnull Map<String, RequirementContext> requirementContext,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull EntitySchema entitySchema
	) {
		final Stream<Entry<String, RequirementContext>> collectedRequirements;
		if (defaultRequirementContext == null) {
			collectedRequirements = requirementContext.entrySet().stream();
		} else {
			final Set<String> existingReferenceNames = requirementContext.keySet();
			collectedRequirements = Stream.concat(
				requirementContext.entrySet().stream(),
				entitySchema.getReferences()
					.keySet()
					.stream()
					.filter(referenceName -> !existingReferenceNames.contains(referenceName))
					.map(
						referenceName -> {
							final boolean skipEntityFetch = defaultRequirementContext.entityFetch() != null &&
								!entitySchema.getReferenceOrThrowException(referenceName)
									.isReferencedEntityTypeManaged();
							final boolean skipEntityGroupFetch = defaultRequirementContext.entityGroupFetch() != null &&
								!entitySchema.getReferenceOrThrowException(referenceName)
									.isReferencedGroupTypeManaged();
							return new SimpleEntry<>(
								referenceName,
								skipEntityFetch || skipEntityGroupFetch ?
									new RequirementContext(
										defaultRequirementContext.managedReferencesBehaviour(),
										defaultRequirementContext.attributeContent(),
										skipEntityFetch ? null : defaultRequirementContext.entityFetch(),
										skipEntityGroupFetch ? null : defaultRequirementContext.entityGroupFetch(),
										defaultRequirementContext.filterBy(),
										defaultRequirementContext.orderBy(),
										defaultRequirementContext.referenceChunkTransformer()
									) :
									defaultRequirementContext
							);
						}
					)
			);
		}
		return collectedRequirements;
	}

	/**
	 * Constructor that is used to further enrich already rich entity.
	 */
	public ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> referenceFetch,
		@Nonnull Map<ReferenceContentKey, RequirementContext> namedReferenceFetch,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityContract entity,
		@Nonnull ChunkTransformerAccessor chunkTransformerAccessor
	) {
		this(
			hierarchyContent,
			referenceFetch, namedReferenceFetch, defaultRequirementContext,
			executionContext,
			new ExistingEntityDecoratorProvider((EntityDecorator) entity),
			chunkTransformerAccessor
		);
	}

	/**
	 * Constructor that is used for initial entity construction.
	 */
	public ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> referenceFetch,
		@Nonnull Map<ReferenceContentKey, RequirementContext> namedReferenceFetch,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull ChunkTransformerAccessor chunkTransformerAccessor
	) {
		this(
			hierarchyContent,
			referenceFetch, namedReferenceFetch, defaultRequirementContext,
			executionContext,
			EmptyEntityProvider.INSTANCE, chunkTransformerAccessor
		);
	}

	/**
	 * Internal constructor.
	 */
	private ReferencedEntityFetcher(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull Map<String, RequirementContext> referenceFetch,
		@Nonnull Map<ReferenceContentKey, RequirementContext> namedReferenceFetch,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull ExistingEntityProvider existingEntityRetriever,
		@Nonnull ChunkTransformerAccessor chunkTransformerAccessor
	) {
		this.hierarchyContent = hierarchyContent;
		this.referenceFetch = referenceFetch;
		this.namedReferenceFetch = namedReferenceFetch;
		this.defaultRequirementContext = defaultRequirementContext;
		this.executionContext = executionContext;
		this.existingEntityRetriever = existingEntityRetriever;
		this.chunkTransformerAccessor = chunkTransformerAccessor;
	}

	@Nonnull
	@Override
	public <T extends SealedEntity> T initReferenceIndex(
		@Nonnull T entity, @Nonnull EntityCollectionContract entityCollection) {
		// we need to ensure that references are fetched in order to be able to provide information about them
		final EntityCollection internalEntityCollection = (EntityCollection) entityCollection;
		final T richEnoughEntity = internalEntityCollection.ensureReferencesFetched(entity);
		final Entity theEntity = richEnoughEntity instanceof EntityDecorator entityDecorator ?
			entityDecorator.getDelegate() : (Entity) richEnoughEntity;

		// prefetch the parents
		if (entityCollection.getSchema().isWithHierarchy()) {
			prefetchParents(
				this.hierarchyContent,
				this.executionContext,
				entityCollection,
				List.of(theEntity.getScope()),
				scope -> theEntity.getParent().stream().toArray(),
				1
			);
		}
		// prefetch the entities
		this.envelopingEntityRequest = prefetchEntities(
			this.referenceFetch,
			this.namedReferenceFetch,
			this.defaultRequirementContext,
			this.executionContext,
			internalEntityCollection.getInternalSchema(),
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
			(referenceName, groupId) -> {
				final Collection<ReferenceContract> references = theEntity.getReferences(referenceName);
				final int[] buffer = new int[references.size()];
				int count = 0;
				for (ReferenceContract ref : references) {
					final Optional<GroupEntityReference> group = ref.getGroup();
					if (group.isPresent() && group.get().getPrimaryKey() == groupId.intValue()) {
						buffer[count++] = ref.getReferencedPrimaryKey();
					}
				}
				return count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
			},
			(entityPrimaryKey, referenceName, referencedEntityId) ->
				theEntity.getReferences(referenceName, referencedEntityId)
					.stream()
					.map(ReferenceContract::getGroup)
					.flatMap(Optional::stream)
					.mapToInt(GroupEntityReference::getPrimaryKeyOrThrowException),
			Map.of(
				theEntity.getScope(),
				new int[]{theEntity.getPrimaryKeyOrThrowException()}
			)
		);

		return richEnoughEntity;
	}

	@Nonnull
	@Override
	public <T extends SealedEntity> List<T> initReferenceIndex(
		@Nonnull List<T> entities, @Nonnull EntityCollectionContract entityCollection) {
		// we need to ensure that references are fetched in order to be able to provide information about them
		final EntityCollection internalCollection = (EntityCollection) entityCollection;
		final List<T> richEnoughEntities = new ArrayList<>(entities.size());
		for (T entity : entities) {
			richEnoughEntities.add(internalCollection.ensureReferencesFetched(entity));
		}

		// prefetch the parents
		if (entityCollection.getSchema().isWithHierarchy()) {
			// collect parent ids by their scope
			final EnumMap<Scope, CompositeIntArray> parentIdsByScopeBuilder = new EnumMap<>(Scope.class);
			for (T entity : richEnoughEntities) {
				final Entity delegate = entity instanceof EntityDecorator entityDecorator ?
					entityDecorator.getDelegate() : (Entity) entity;
				if (delegate.getParent().isPresent()) {
					parentIdsByScopeBuilder
						.computeIfAbsent(entity.getScope(), scope -> new CompositeIntArray())
						.add(delegate.getParent().orElseThrow());
				}
			}
			final EnumMap<Scope, int[]> parentIdsByScope = new EnumMap<>(Scope.class);
			for (Entry<Scope, CompositeIntArray> entry : parentIdsByScopeBuilder.entrySet()) {
				parentIdsByScope.put(entry.getKey(), entry.getValue().toArray());
			}

			prefetchParents(
				this.hierarchyContent,
				this.executionContext,
				entityCollection,
				parentIdsByScope.keySet(),
				scope -> parentIdsByScope.getOrDefault(scope, ArrayUtils.EMPTY_INT_ARRAY),
				richEnoughEntities.size()
			);
		}

		final Supplier<Map<Integer, Entity>> entityIndexSupplier = new EntityIndexSupplier<>(richEnoughEntities);
		final ReferenceMapping groupToEntityIdMapping = new ReferenceMapping(
			entityCollection.getSchema().getReferences().size(),
			richEnoughEntities
		);

		// prefetch the entities
		this.envelopingEntityRequest = prefetchEntities(
			this.referenceFetch,
			this.namedReferenceFetch,
			this.defaultRequirementContext,
			this.executionContext,
			internalCollection.getInternalSchema(),
			this.existingEntityRetriever,
			(referenceName, entityPk) -> toFormula(
				entityIndexSupplier.get().get(entityPk).getReferences(referenceName)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray()
			),
			groupToEntityIdMapping::getReferencedEntityPrimaryKeys,
			groupToEntityIdMapping::getGroup,
			getPrimaryKeysIndexedByScope(entities)
		);

		return entities;
	}

	@Nullable
	@Override
	public Function<Integer, EntityClassifierWithParent> getParentEntityFetcher() {
		return ofNullable(this.parentEntities)
			.map(prefetchedEntities -> (Function<Integer, EntityClassifierWithParent>) prefetchedEntities::get)
			.orElse(null);
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

	@Nonnull
	@Override
	public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return requireFetchedEntities().getEntityFetcher(referenceSchema);
	}

	@Nonnull
	@Override
	public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
		return requireFetchedEntities().getEntityGroupFetcher(referenceSchema);
	}

	@Nullable
	@Override
	public ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
		return requireFetchedEntities().getEntityComparator(referenceSchema);
	}

	@Nullable
	@Override
	public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		return requireFetchedEntities().getEntityFilter(referenceSchema);
	}

	@Nullable
	@Override
	public AttributeContent getAttributeContentToPrefetch(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		return requireFetchedEntities().getAttributeContentToPrefetch(referenceSchema);
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> createChunk(
		@Nonnull Entity entity, @Nonnull String referenceName, @Nonnull List<ReferenceContract> references) {
		requireFetchedEntities();
		return this.chunkTransformerAccessor.apply(referenceName)
			.createChunk(references);
	}

	/**
	 * Creates and returns a ReferenceSetFetcher based on the specified reference content instance name.
	 *
	 * @param instanceName the name of the instance to fetch the ReferenceSetFetcher for, must not be null
	 * @return the ReferenceSetFetcher associated with the given instance name, never null
	 * @throws GenericEvitaInternalError if {@link #prefetchEntities} has not been called prior to this method
	 * @throws NullPointerException      if no ReferenceSetFetcher is found for the given instance name
	 */
	@Nonnull
	public ReferenceSetFetcher getMinimalReferenceFetcher(@Nonnull String instanceName) {
		Assert.isPremiseValid(
			this.namedFetchedEntities != null,
			() -> new GenericEvitaInternalError(
				"Method `prefetchEntities` must be called prior creating named fetchers!")
		);
		return Objects.requireNonNull(this.namedFetchedEntities.get(instanceName));
	}

	/**
	 * Asserts that {@link #fetchedEntities} has been initialized by a prior call to {@link #initReferenceIndex}
	 * and returns the initialized instance.
	 *
	 * @return the initialized {@link ReferenceSetFetcher} instance
	 * @throws GenericEvitaInternalError if {@link #initReferenceIndex} has not been called yet
	 */
	@Nonnull
	private ReferenceSetFetcher requireFetchedEntities() {
		Assert.isPremiseValid(
			this.fetchedEntities != null,
			() -> new GenericEvitaInternalError(
				"Method `initReferenceIndex` must be called prior to accessing reference data!"
			)
		);
		return this.fetchedEntities;
	}

	/**
	 * Method executes all the necessary referenced entities fetch. It loads only those referenced entities that are
	 * mentioned in `referenceFetch`. Execution reuses potentially existing fetched referenced entities from
	 * the last enrichment of the same entity.
	 *
	 * @param referenceFetch                      the map of reference names to their requirements
	 * @param namedReferenceFetch                 the map of reference content instance and reference names to their requirements
	 * @param defaultRequirementContext           the default requirements for all references
	 * @param executionContext                    query context used for querying the entity
	 * @param entitySchema                        the schema of the entity
	 * @param existingEntityRetriever             function that provides access to already fetched referenced entities (relict of last enrichment)
	 * @param referencedEntityIdsFormula          the formula containing superset of all possible referenced entities
	 * @param groupToReferencedEntityIdTranslator the function that translates group ids to referenced entity ids
	 * @param referencedEntityToGroupIdTranslator the function that translates referenced entity ids to group ids
	 * @param entityPrimaryKey                    the array of top entity primary keys for which the references are being fetched
	 */
	@Nonnull
	private EvitaRequest prefetchEntities(
		@Nonnull Map<String, RequirementContext> referenceFetch,
		@Nonnull Map<ReferenceContentKey, RequirementContext> namedReferenceFetch,
		@Nullable RequirementContext defaultRequirementContext,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ExistingEntityProvider existingEntityRetriever,
		@Nonnull BiFunction<String, Integer, Formula> referencedEntityIdsFormula,
		@Nonnull BiFunction<String, Integer, int[]> groupToReferencedEntityIdTranslator,
		@Nonnull TriFunction<Integer, String, Integer, IntStream> referencedEntityToGroupIdTranslator,
		@Nonnull Map<Scope, int[]> entityPrimaryKey
	) {
		final ReferenceKeeper<FilterByVisitor> filterByVisitor = new ReferenceKeeper<>(null);
		final Stream<Entry<String, RequirementContext>> collectedRequirements = getCollectedRequirementStream(
			referenceFetch, defaultRequirementContext, entitySchema
		);

		final DefaultPrefetchRequirementCollector globalPrefetchCollector = new DefaultPrefetchRequirementCollector();
		this.fetchedEntities = new ReferencedSetEntityFetcher(
			collectedRequirements
				.filter(it -> it.getValue().requiresInit())
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> createPrefetchedEntities(
							executionContext,
							entitySchema,
							existingEntityRetriever,
							referencedEntityIdsFormula,
							groupToReferencedEntityIdTranslator,
							referencedEntityToGroupIdTranslator,
							entityPrimaryKey,
							it.getKey(),
							it.getValue(),
							globalPrefetchCollector,
							filterByVisitor
						)
					)
				),
			this.chunkTransformerAccessor,
			globalPrefetchCollector
		);
		if (namedReferenceFetch.isEmpty()) {
			this.namedFetchedEntities = Collections.emptyMap();
		} else {
			this.namedFetchedEntities = CollectionUtils.createHashMap(namedReferenceFetch.size());
			for (Entry<ReferenceContentKey, RequirementContext> namedEntry : namedReferenceFetch.entrySet()) {
				final ReferenceContentKey rck = namedEntry.getKey();
				final RequirementContext namedRequirementContext = namedEntry.getValue();
				if (namedRequirementContext.requiresInit()) {
					final ChunkTransformer namedChunkTransformer = namedEntry.getValue().referenceChunkTransformer();
					final ChunkTransformerAccessor namedChunkTransformerAccessor = referenceName -> namedChunkTransformer;
					final DefaultPrefetchRequirementCollector namedCollector = new DefaultPrefetchRequirementCollector();
					this.namedFetchedEntities.put(
						rck.instanceName(),
						new ReferencedSetEntityFetcher(
							Map.of(
								rck.referenceName(),
								createPrefetchedEntities(
									executionContext,
									entitySchema,
									existingEntityRetriever,
									referencedEntityIdsFormula,
									groupToReferencedEntityIdTranslator,
									referencedEntityToGroupIdTranslator,
									entityPrimaryKey,
									rck.referenceName(),
									namedRequirementContext,
									namedCollector,
									filterByVisitor
								)
							),
							namedChunkTransformerAccessor,
							namedCollector
						)
					);
				}
			}
		}

		return ofNullable(globalPrefetchCollector.getEntityFetch())
			.map(it -> executionContext.getEvitaRequest().deriveCopyWith(executionContext.getSchema().getName(), it))
			.orElse(executionContext.getEvitaRequest());
	}

	/**
	 * Method executes all the necessary hierarchical entities fetch. Execution reuses potentially existing fetched
	 * hierarchical entities from the last enrichment of the same entity.
	 *
	 * @param hierarchyContent  the requirement specification for the hierarchical entities
	 * @param executionContext  query context used for querying the entity
	 * @param entityCollection  the entity collection whose hierarchy is being traversed
	 * @param scopes            the set of scopes within which to look up hierarchy indexes
	 * @param parentIdsSupplier the function returning the array of parent entity primary keys to prefetch for a given
	 *                          scope
	 * @param parentCount       the expected number of parent entities (used for initial capacity of internal maps)
	 */
	private void prefetchParents(
		@Nullable HierarchyContent hierarchyContent,
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EntityCollectionContract entityCollection,
		@Nonnull Collection<Scope> scopes,
		@Nonnull Function<Scope, int[]> parentIdsSupplier,
		int parentCount
	) {
		// prefetch only if there is any requirement
		if (hierarchyContent != null) {
			final IntObjectHashMap<EntityClassifierWithParent> parentEntityReferences = new IntObjectHashMap<>(
				parentCount);
			final boolean bodyRequired = hierarchyContent.getEntityFetch().isPresent();
			final RoaringBitmapWriter<RoaringBitmap> allReferencedParents = bodyRequired ?
				RoaringBitmapBackedBitmap.buildWriter() : null;

			// initialize used data structures
			final EntitySchemaContract entitySchema = entityCollection.getSchema();
			final String entityType = entitySchema.getName();
			final QueryPlanningContext queryContext = executionContext.getQueryContext();

			for (Scope scope : scopes) {
				identifyParents(
					entityType, scope, hierarchyContent,
					queryContext, executionContext,
					entitySchema,
					allReferencedParents,
					parentEntityReferences,
					parentIdsSupplier.apply(scope)
				);
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
					new BaseBitmap(allReferencedParents.get()),
					fetchRequest, executionContext,
					referencedCollection,
					this.existingEntityRetriever::getExistingParentEntity
				);

				// replace the previous EntityReferenceWithParent with EntityDecorator with filled parent
				final IntObjectHashMap<EntityClassifierWithParent> parentSealedEntities = new IntObjectHashMap<>(
					parentEntityReferences.size());
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
	 * Holds the result of separating {@link GroupHaving} constraints from entity-level constraints.
	 *
	 * @param entityLevelFilterBy  the {@link FilterBy} containing only entity-level constraints, or {@code null}
	 *                             if all constraints were group-level (the original filterBy is used when no
	 *                             GroupHaving was found)
	 * @param allowedByGroupFilter bitmap of referenced entity PKs allowed by the group filter, or {@code null}
	 *                             if no group filtering applies
	 */
	private record GroupFilterSeparation(
		@Nullable FilterBy entityLevelFilterBy,
		@Nullable Bitmap allowedByGroupFilter
	) {

		/**
		 * Returns the entity-level filter constraint children array. Derived from
		 * {@link #entityLevelFilterBy} to avoid storing redundant data.
		 *
		 * @return the entity-level filter children, or empty array if no entity-level constraints exist
		 */
		@Nonnull
		FilterConstraint[] entityLevelChildren() {
			return this.entityLevelFilterBy == null
				? FilterConstraint.EMPTY_ARRAY
				: this.entityLevelFilterBy.getChildren();
		}
	}

}
