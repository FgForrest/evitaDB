/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.PrefetchOrder;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.function.TriFunction;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.spi.chunk.ServerChunkTransformerAccessor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.RandomUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This is a context object that builds on top of {@link QueryPlanningContext} and captures the data related to the
 * query execution. Planning phase might create multiple plans from which only one us usually selected and executed,
 * but in case of tests of debugging we might want to evaluate the query with multiple plans and verify the results.
 * The execution phase needs to be isolated one from another, so that different executions don't interfere with each
 * other.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class QueryExecutionContext implements Closeable {
	/**
	 * The original context that was used for planning the query.
	 */
	@Nonnull @Getter
	private final QueryPlanningContext queryContext;
	/**
	 * Contains true if the execution is based on prefetched entities. I.e. it was "worthwhile" to optimistically
	 * prefetch entities with contents from the disk and perform filtration analysis on their contents instead of
	 * indexes.
	 */
	@Getter private final boolean prefetchExecution;
	/**
	 * This field is used only for debugging purposes when we need to compute results for different variants of
	 * query plan. In case random function is used in the evaluation process, the variants would ultimately produce
	 * different results. Therefore, we "freeze" the {@link Random} using Java serialization process and restore it
	 * along with its internal state for each query plan so that the random row, stays the same for all evaluations.
	 */
	private final byte[] frozenRandom;
	/**
	 * A function that converts a given {@link SealedEntity} into a specified type.
	 * This BiFunction takes a {@link Class} object representing the desired type
	 * and a {@link SealedEntity} object, and returns an object of the specified type.
	 *
	 * @see QueryExecutionContext#convertToRequestedType(Class, SealedEntity)
	 */
	private final BiFunction<Class<?>, SealedEntity, Object> converter;
	/**
	 * Contains list of prefetched entities if they were considered worthwhile to prefetch -
	 * see {@link SelectionFormula} for more information.
	 */
	@Getter
	private List<ServerEntityDecorator> prefetchedEntities;
	/**
	 * Contains index of primary keys to their respective prefetched {@link SealedEntity} objects.
	 */
	private IntObjectMap<EntityDecorator> entityPkIndex;
	/**
	 * Contains index of {@link EntityReference} identifiers to prefetched {@link SealedEntity} objects.
	 */
	private Map<EntityReferenceContract<EntityReference>, EntityDecorator> entityReferenceIndex;
	/**
	 * Contains lazy initialized local buffer pool.
	 */
	private Deque<int[]> buffers;

	/**
	 * Returns true if the context is inside {@link QueryPlanner#verifyConsistentResultsInAllPlans(QueryPlanningContext, List, List, QueryPlanBuilder)}  method.
	 */
	public boolean isDryRun() {
		return this.frozenRandom != null;
	}

	/**
	 * Returns random object to generate sequences from.
	 *
	 * @see #frozenRandom for more information
	 */
	@Nonnull
	public Random getRandom() {
		return RandomUtils.getRandom(this.frozenRandom);
	}

	/**
	 * Method loads entity contents by specifying its type and primary key. Fetching logic respect language from
	 * the original {@link EvitaRequest}
	 */
	@Nonnull
	public Optional<SealedEntity> fetchEntity(@Nullable String entityType, int entityPrimaryKey, @Nonnull EntityFetchRequire requirements) {
		final EntityCollection targetCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
		final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
		return targetCollection.fetchEntity(entityPrimaryKey, fetchRequest, this.queryContext.getEvitaSession());
	}

	/**
	 * Method loads requested entity contents by specifying its primary key.
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(@Nullable String entityType, @Nonnull int[] entityPrimaryKeys, @Nonnull EntityFetchRequire requirements) {
		final EntityCollection entityCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entities");
		final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
		return entityCollection.fetchEntities(entityPrimaryKeys, fetchRequest, this.queryContext.getEvitaSession());
	}

	/**
	 * Method will return full entity object for passed `entityPrimaryKey`. The input primary key may represent the
	 * real {@link EntityContract#getPrimaryKey()} or it may represent key masked by {@link QueryPlanningContext#translateEntityReference(EntityReferenceContract[])}
	 * method.
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(int... entityPrimaryKey) {
		if (ArrayUtils.isEmpty(entityPrimaryKey)) {
			return Collections.emptyList();
		}

		// are the reference bodies required?
		final String entityType = this.queryContext.getEntityType();
		final EvitaRequest evitaRequest = this.queryContext.getEvitaRequest();
		final EvitaSessionContract evitaSession = this.queryContext.getEvitaSession();
		final Map<String, RequirementContext> requirementTuples = evitaRequest.getReferenceEntityFetch();

		// new predicates are richer that previous ones - we need to fetch additional data and create new entity
		final ReferenceFetcher entityFetcher = requirementTuples.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				requirementTuples,
				evitaRequest.getDefaultReferenceRequirement(),
				this,
				new ServerChunkTransformerAccessor(evitaRequest)
			);

		if (this.prefetchedEntities == null) {
			final EntityCollection entityCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
			return entityCollection.fetchEntities(entityPrimaryKey, evitaRequest, evitaSession, entityFetcher);
		} else {
			return takeAdvantageOfPrefetchedEntities(
				entityPrimaryKey,
				entityType,
				(entityCollection, entityPrimaryKeys, requestToUse) ->
					entityCollection.fetchEntities(entityPrimaryKeys, evitaRequest, evitaSession),
				(entityCollection, prefetchedEntities, requestToUse) ->
					entityCollection.limitAndFetchExistingEntities(prefetchedEntities, requestToUse, entityFetcher)
			);
		}
	}

	/**
	 * Method will return full entity object for passed `entityPrimaryKey`. The input primary key may represent the
	 * real {@link EntityContract#getPrimaryKey()} or it may represent key masked by {@link QueryPlanningContext#translateEntityReference(EntityReferenceContract[])}
	 * method.
	 */
	@Nonnull
	public List<BinaryEntity> fetchBinaryEntities(int... entityPrimaryKey) {
		final String entityType = this.queryContext.getEntityType();
		final EvitaRequest evitaRequest = this.queryContext.getEvitaRequest();
		final EvitaSessionContract evitaSession = this.queryContext.getEvitaSession();
		if (this.prefetchedEntities == null) {
			final EntityCollection entityCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
			return entityCollection.fetchBinaryEntities(entityPrimaryKey, evitaRequest, evitaSession);
		} else {
			// we need to reread the contents of the prefetched entity in binary form
			return takeAdvantageOfPrefetchedEntities(
				entityPrimaryKey,
				entityType,
				(entityCollection, entityPrimaryKeys, requestToUse) ->
					entityCollection.fetchBinaryEntities(entityPrimaryKeys, evitaRequest, evitaSession),
				(entityCollection, prefetchedEntities, requestToUse) -> entityCollection.fetchBinaryEntities(
					prefetchedEntities.stream()
						.mapToInt(EntityDecorator::getPrimaryKeyOrThrowException)
						.toArray(),
					evitaRequest, evitaSession
				)
			);
		}
	}

	/**
	 * Method loads requested entity contents by specifying its primary key (either virtual or real).
	 */
	@Nullable
	public SealedEntity translateToEntity(int primaryKey) {
		if (this.queryContext.isAtLeastOneMaskedPrimaryAssigned()) {
			return getPrefetchedEntityByMaskedPrimaryKey(primaryKey);
		} else {
			return getPrefetchedEntityByPrimaryKey(primaryKey);
		}
	}

	/**
	 * Method will prefetch all entities mentioned in `entitiesToPrefetch` and loads them with the scope of `requirements`.
	 * The entities will reveal only the scope to the `requirements` - no less, no more data.
	 */
	public void prefetchEntities(@Nonnull PrefetchOrder prefetcher) {
		final Bitmap entitiesToPrefetch = prefetcher.getEntitiesToPrefetch();
		final EntityFetchRequire requirements = prefetcher.getEntityRequirements();
		if (this.queryContext.isAtLeastOneMaskedPrimaryAssigned()) {
			prefetchEntities(
				Arrays.stream(entitiesToPrefetch.getArray())
					.mapToObj(this.queryContext::translateToEntityReference)
					.toArray(EntityReferenceContract[]::new),
				requirements
			);
		} else {
			final String entityType = this.queryContext.getEntityType();
			final EvitaSessionContract evitaSession = this.queryContext.getEvitaSession();
			final EntityCollection entityCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entities");
			final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
			this.prefetchedEntities = Arrays.stream(entitiesToPrefetch.getArray())
				.mapToObj(it -> entityCollection.fetchEntityDecorator(it, fetchRequest, evitaSession))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
		}
	}

	/**
	 * Method will prefetch all entities mentioned in `entitiesToPrefetch` and loads them with the scope of `requirements`.
	 * The entities will reveal only the scope to the `requirements` - no less, no more data.
	 */
	public void prefetchEntities(@Nonnull EntityReferenceContract<?>[] entitiesToPrefetch, @Nonnull EntityFetchRequire requirements) {
		if (entitiesToPrefetch.length != 0) {
			if (this.prefetchedEntities == null) {
				this.prefetchedEntities = new ArrayList<>(entitiesToPrefetch.length);
			}
			final EvitaSessionContract evitaSession = this.queryContext.getEvitaSession();
			if (entitiesToPrefetch.length == 1) {
				final String entityType = entitiesToPrefetch[0].getType();
				final EntityCollection targetCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
				final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
				final int pk = entitiesToPrefetch[0].getPrimaryKey();
				targetCollection.fetchEntityDecorator(pk, fetchRequest, evitaSession)
					.ifPresent(it -> this.prefetchedEntities.add(it));
			} else {
				final Map<String, CompositeIntArray> entitiesByType = CollectionUtils.createHashMap(16);
				for (EntityReferenceContract<?> ref : entitiesToPrefetch) {
					final CompositeIntArray pks = entitiesByType.computeIfAbsent(ref.getType(), eType -> new CompositeIntArray());
					pks.add(ref.getPrimaryKey());
				}
				entitiesByType
					.entrySet()
					.stream()
					.flatMap(it -> {
						final String entityType = it.getKey();
						final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
						final EntityCollection targetCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
						return Arrays.stream(it.getValue().toArray())
							.mapToObj(pk -> targetCollection.fetchEntityDecorator(pk, fetchRequest, evitaSession))
							.filter(Optional::isPresent)
							.map(Optional::get);
					})
					.forEach(it -> this.prefetchedEntities.add(it));
			}
		}
	}

	/**
	 * Enriches the provided {@link EntityFetch} instance by updating its requirements
	 * based on specified rules. If no {@link EntityFetch} is provided or no updates are needed,
	 * the method returns the original or null input. When updates are applied, a new instance
	 * of {@link EntityFetch} is created and returned.
	 *
	 * @param entityFetchRequire the original {@link EntityFetch} instance to be enriched; may be null
	 * @return the enriched {@link EntityFetch} instance if updates are applied, or the original instance if no updates are needed, or null if the input is null
	 */
	@Nonnull
	public <T extends EntityFetchRequire> T enrichEntityFetch(@Nonnull T entityFetchRequire) {
		//noinspection unchecked
		return (T) Objects.requireNonNull(
			ConstraintCloneVisitor.clone(
				entityFetchRequire,
				(visitor, constraint) -> {
					if (constraint instanceof EntityFetch ef) {
						final EntityContentRequire[] originalRequirements = ef.getRequirements();
						final EntityContentRequire[] updatedRequirements = updateRequirements(originalRequirements);
						//noinspection ArrayEquality
						return updatedRequirements == originalRequirements ? ef : new EntityFetch(updatedRequirements);
					} else if (constraint instanceof EntityGroupFetch egf) {
						final EntityContentRequire[] originalRequirements = egf.getRequirements();
						final EntityContentRequire[] updatedRequirements = updateRequirements(originalRequirements);
						//noinspection ArrayEquality
						return updatedRequirements == originalRequirements ? egf : new EntityGroupFetch(updatedRequirements);
					} else {
						return constraint;
					}
				}
			)
		);
	}

	/**
	 * Updates the given array of {@link EntityContentRequire} objects based on specific rules for modifying
	 * {@link AccompanyingPriceContent} elements. If updates are applied to the elements of the array, a
	 * new array instance is returned containing the modified elements. If no updates are needed, the original
	 * array is returned unchanged.
	 *
	 * @param requirements an array of {@link EntityContentRequire} objects to be checked and potentially modified;
	 *                     must not be null.
	 * @return a new array of {@link EntityContentRequire} objects with modified elements if applicable, or
	 *         the original array if no changes were made.
	 */
	@Nonnull
	private EntityContentRequire[] updateRequirements(@Nonnull EntityContentRequire[] requirements) {
		EntityContentRequire[] result = requirements;
		for (int i = 0; i < requirements.length; i++) {
			final EntityContentRequire requirement = requirements[i];
			if (requirement instanceof AccompanyingPriceContent apc) {
				final boolean emptyPriceLists = ArrayUtils.isEmpty(apc.getPriceLists());
				if (apc.getAccompanyingPriceName().isEmpty() || emptyPriceLists) {
					// copy on first write
					//noinspection ArrayEquality
					if (requirements == result) {
						result = Arrays.copyOf(requirements, requirements.length);
					}
					result[i] = new AccompanyingPriceContent(
						apc.getAccompanyingPriceName().orElse(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE),
						emptyPriceLists ?
							this.queryContext.getEvitaRequest().getDefaultAccompanyingPricePriceLists() :
							apc.getPriceLists()
					);
				}
			}
		}
		return result;
	}

	/**
	 * Method returns an array for buffering purposes. The buffer is obtained from shared resource, but kept locally
	 * for multiple reuse within single query context.
	 */
	@Nonnull
	public int[] borrowBuffer() {
		if (this.buffers == null) {
			this.buffers = new ArrayDeque<>(16);
		}
		// return locally cached buffer or obtain new one from shared pool
		return ofNullable(this.buffers.poll())
			.orElseGet(SharedBufferPool.INSTANCE::obtain);
	}

	/**
	 * Borrowed buffer is returned to local queue for reuse.
	 */
	public void returnBuffer(@Nonnull int[] borrowedBuffer) {
		this.buffers.push(borrowedBuffer);
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase) {
		if (!isDryRun()) {
			this.queryContext.pushStep(phase);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull String message) {
		if (!isDryRun()) {
			this.queryContext.pushStep(phase, message);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull Supplier<String> messageSupplier) {
		if (!isDryRun()) {
			this.queryContext.pushStep(phase, messageSupplier);
		}
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep() {
		if (!isDryRun()) {
			this.queryContext.popStep();
		}
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep(@Nonnull String message) {
		if (!isDryRun()) {
			this.queryContext.popStep(message);
		}
	}

	/**
	 * Returns finalized {@link QueryTelemetry} or throws an exception.
	 */
	@Nonnull
	public QueryTelemetry finalizeAndGetTelemetry() {
		if (isDryRun()) {
			return new QueryTelemetry(QueryPhase.OVERALL);
		} else {
			return this.queryContext.finalizeAndGetTelemetry();
		}
	}

	@Override
	public void close() {
		if (this.buffers != null) {
			this.buffers.forEach(SharedBufferPool.INSTANCE::free);
		}
	}

	/*
		DELEGATED METHODS TO QUERY PLANNING CONTEXT
	 */

	@Nonnull
	public EvitaRequest getEvitaRequest() {
		return this.queryContext.getEvitaRequest();
	}

	public boolean isRequiresBinaryForm() {
		return this.queryContext.isRequiresBinaryForm();
	}

	@Nonnull
	public QueryPriceMode getQueryPriceMode() {
		return this.queryContext.getQueryPriceMode();
	}

	@Nonnull
	public EntitySchemaContract getSchema() {
		return this.queryContext.getSchema();
	}

	public int translateEntity(@Nonnull EntityContract entityContract) {
		return this.queryContext.translateEntity(entityContract);
	}

	public int translateToEntityPrimaryKey(int primaryKey) {
		return this.queryContext.translateToEntityPrimaryKey(primaryKey);
	}

	public EntityReference translateToEntityReference(int primaryKey) {
		return this.queryContext.translateToEntityReference(primaryKey);
	}

	public boolean isFacetGroupConjunction(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return this.queryContext.isFacetGroupConjunction(referenceSchema, groupId, level);
	}

	public boolean isFacetGroupDisjunction(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return this.queryContext.isFacetGroupDisjunction(referenceSchema, groupId, level);
	}

	public boolean isFacetGroupNegation(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return this.queryContext.isFacetGroupNegation(referenceSchema, groupId, level);
	}

	public boolean isFacetGroupExclusive(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return this.queryContext.isFacetGroupExclusivity(referenceSchema, groupId, level);
	}

	@Nonnull
	public Formula analyse(@Nonnull Formula formula) {
		return this.queryContext.analyse(formula);
	}

	@Nonnull
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull T computer) {
		return this.queryContext.analyse(computer);
	}

	@Nonnull
	public EntityCollection getEntityCollectionOrThrowException(@Nonnull String entityType, @Nonnull String fetchReferences) {
		return this.queryContext.getEntityCollectionOrThrowException(entityType, fetchReferences);
	}

	@Nonnull
	public <T> T convertToRequestedType(@Nonnull Class<T> expectedType, @Nonnull SealedEntity sealedEntity) {
		//noinspection unchecked
		return (T) this.converter.apply(expectedType, sealedEntity);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method retrieves already prefetched entities and uses them for response output by enriching them of additional
	 * data that has been requested but not required for filtering or sorting operations.
	 */
	@Nonnull
	private <T extends EntityClassifier> List<T> takeAdvantageOfPrefetchedEntities(
		@Nonnull int[] inputPrimaryKeys,
		@Nullable String entityType,
		@Nonnull TriFunction<EntityCollection, int[], EvitaRequest, List<T>> fetcher,
		@Nonnull TriFunction<EntityCollection, List<EntityDecorator>, EvitaRequest, List<T>> collector
	) {
		// initialize variables that allow caching of last resolved objects
		// there is high probability that the locale will stay the same for entire result set
		Locale lastImplicitLocale = null;
		String lastEntityType = null;

		final AtomicReference<EntityCollection> entityCollection = new AtomicReference<>();
		final AtomicReference<EvitaRequest> requestToUse = new AtomicReference<>();
		final AtomicInteger primaryKeyPeek = new AtomicInteger();
		final int[] primaryKeysToFetch = new int[inputPrimaryKeys.length];
		final AtomicInteger prefetchedEntitiesPeek = new AtomicInteger();
		final EntityDecorator[] prefetchedEntities = new EntityDecorator[inputPrimaryKeys.length];
		final Map<Integer, T> index = CollectionUtils.createHashMap(inputPrimaryKeys.length);
		final AtomicReference<Map<EntityReference, Integer>> remappingIndex = new AtomicReference<>();

		final Runnable dataCollector = () -> {
			// convert collected data so far
			if (primaryKeyPeek.get() > 0) {
				fetcher.apply(entityCollection.get(), primaryKeyPeek.get() < inputPrimaryKeys.length ? Arrays.copyOfRange(primaryKeysToFetch, 0, primaryKeyPeek.get()) : primaryKeysToFetch, requestToUse.get())
					.forEach(it -> index.put(it.getPrimaryKey(), it));
				primaryKeyPeek.set(0);
			}
			if (prefetchedEntitiesPeek.get() > 0) {
				final List<EntityDecorator> collectedDecorators = prefetchedEntitiesPeek.get() < inputPrimaryKeys.length ?
					ArrayUtils.asList(prefetchedEntities, 0, prefetchedEntitiesPeek.get()) : Arrays.asList(prefetchedEntities);
				collector.apply(entityCollection.get(), collectedDecorators, requestToUse.get())
					.forEach(
						it -> index.put(
							ofNullable(remappingIndex.get())
								.map(ix -> ix.get(new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException())))
								.orElse(it.getPrimaryKey()),
							it
						)
					);
				prefetchedEntitiesPeek.set(0);
			}
		};

		for (final int epk : inputPrimaryKeys) {
			final EntityDecorator prefetchedEntity;
			final Locale implicitLocale;

			// if at least one masked primary key was assigned
			if (this.queryContext.isAtLeastOneMaskedPrimaryAssigned()) {
				// retrieve the prefetched entity by the masked key
				prefetchedEntity = getPrefetchedEntityByMaskedPrimaryKey(epk);
				// attempt to retrieve implicit locale from this prefetched entity
				// implicit locale = locale derived from the global unique attr that might have been resolved in filter
				implicitLocale = getPrefetchedEntityImplicitLocale(epk);
			} else {
				// retrieve the prefetched entity by its primary key
				prefetchedEntity = getPrefetchedEntityByPrimaryKey(epk);
				implicitLocale = getPrefetchedEntityImplicitLocale(epk);
			}

			// init collection
			final String entityTypeChangedTo;
			if (prefetchedEntity == null && !Objects.equals(lastEntityType, entityType)) {
				Assert.isTrue(entityType != null, () -> new EntityCollectionRequiredException("fetch entity"));
				entityTypeChangedTo = entityType;
			} else if (prefetchedEntity != null && !Objects.equals(lastEntityType, prefetchedEntity.getType())) {
				entityTypeChangedTo = prefetchedEntity.getType();
			} else {
				entityTypeChangedTo = null;
			}

			// resolve the request that should be used for fetching
			final EvitaRequest evitaRequest = this.queryContext.getEvitaRequest();
			if ((implicitLocale == null || evitaRequest.getLocale() != null) && evitaRequest != requestToUse.get()) {
				dataCollector.run();
				requestToUse.set(evitaRequest);
				lastImplicitLocale = null;
			} else if (implicitLocale != null && !Objects.equals(lastImplicitLocale, implicitLocale)) {
				dataCollector.run();
				// when implicit locale is found we need to fabricate new request for that particular entity
				// that will use such implicit locale as if it would have been part of the original request
				lastImplicitLocale = implicitLocale;
				requestToUse.set(new EvitaRequest(evitaRequest, implicitLocale));
			} else if (entityTypeChangedTo != null) {
				dataCollector.run();
			}

			// now change the collection if necessary
			if (entityTypeChangedTo != null) {
				entityCollection.set(this.queryContext.getEntityCollectionOrThrowException(entityTypeChangedTo, "fetch entity"));
				lastEntityType = entityTypeChangedTo;
			}

			// now apply collector to fetch the entity in requested form using potentially enriched request
			if (prefetchedEntity == null) {
				primaryKeysToFetch[primaryKeyPeek.getAndIncrement()] = epk;
			} else {
				prefetchedEntities[prefetchedEntitiesPeek.getAndIncrement()] = prefetchedEntity;
				if (epk != prefetchedEntity.getPrimaryKeyOrThrowException()) {
					if (remappingIndex.get() == null) {
						remappingIndex.set(CollectionUtils.createHashMap(inputPrimaryKeys.length));
					}
					remappingIndex.get().put(
						new EntityReference(prefetchedEntity.getType(), prefetchedEntity.getPrimaryKeyOrThrowException()),
						epk
					);
				}
			}
		}

		dataCollector.run();

		return Arrays.stream(inputPrimaryKeys)
			.mapToObj(index::get)
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * Method extracts implicit locale that might be derived from the globally unique attribute if the entity is matched
	 * particularly by it.
	 */
	@Nullable
	private Locale getPrefetchedEntityImplicitLocale(int entityPrimaryKey) {
		final EntityReferenceContract<EntityReference> entityReference = this.queryContext.getEntityReferenceIfExist(entityPrimaryKey)
			.orElse(null);
		return entityReference instanceof EntityReferenceWithLocale entityReferenceWithLocale ? entityReferenceWithLocale.locale() : null;
	}

	/**
	 * Returns appropriate prefetched {@link SealedEntity} by real primary key from {@link EntityContract#getPrimaryKey()}.
	 */
	@Nullable
	private EntityDecorator getPrefetchedEntityByPrimaryKey(int entityPrimaryKey) {
		this.entityPkIndex = ofNullable(this.entityPkIndex)
			.orElseGet(() -> {
				final IntObjectMap<EntityDecorator> result = new IntObjectHashMap<>(this.prefetchedEntities.size());
				for (EntityDecorator prefetchedEntity : this.prefetchedEntities) {
					result.put(Objects.requireNonNull(prefetchedEntity.getPrimaryKey()), prefetchedEntity);
				}
				return result;
			});
		return this.entityPkIndex.get(entityPrimaryKey);
	}

	/**
	 * Returns appropriate prefetched {@link SealedEntity} by virtual primary key assigned by
	 * {@link QueryPlanningContext#getOrRegisterEntityReferenceMaskId(EntityReferenceContract)} method.
	 */
	@Nullable
	private EntityDecorator getPrefetchedEntityByMaskedPrimaryKey(int entityPrimaryKey) {
		this.entityReferenceIndex = ofNullable(this.entityReferenceIndex)
			.orElseGet(() ->
				this.prefetchedEntities
					.stream()
					.collect(
						Collectors.toMap(
							it -> new EntityReference(it.getType(), Objects.requireNonNull(it.getPrimaryKey())),
							Function.identity()
						)
					)
			);
		return this.queryContext.getEntityReferenceIfExist(entityPrimaryKey)
			.map(this.entityReferenceIndex::get)
			.orElse(null);
	}

}
