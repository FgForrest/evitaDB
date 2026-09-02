/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
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
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.PrefetchOrder;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.fetch.ReferencedEntityFetcher;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.function.TriFunction;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.chunk.ServerChunkTransformerAccessor;
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

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This is a context object that builds on top of {@link QueryPlanningContext} and captures the data related to the
 * query execution. Planning phase might create multiple plans from which only one is usually selected and executed,
 * but in case of tests or debugging we might want to evaluate the query with multiple plans and verify the results.
 * The execution phase needs to be isolated one from another, so that different executions don't interfere with each
 * other.
 *
 * The planning context is shared by all such executions and therefore holds everything that is *not* per-execution
 * state; this object holds what is - prefetched entities, the buffer pool lease and the dry-run flag. It is
 * {@link Closeable} and must be used in a try-with-resources block, because {@link #close()} is what returns the
 * borrowed buffers to the shared pool.
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
	private Map<EntityReferenceContract, EntityDecorator> entityReferenceIndex;
	/**
	 * Contains lazy initialized local buffer pool. Buffers are drawn from {@link SharedBufferPool} on first demand and
	 * kept here for reuse within this single execution; {@link #close()} hands them all back.
	 */
	private Deque<int[]> buffers;

	/**
	 * Returns true if the context is inside {@link QueryPlanner#verifyConsistentResultsInAllPlans(QueryPlanningContext, List, List, QueryPlanBuilder)}  method.
	 *
	 * In that mode the same query is evaluated by several alternative plans purely to check they agree, so the run is
	 * not the one the client observes. Two things follow, and both are relied upon across this class: the random
	 * source is frozen so the plans cannot diverge on it (see {@link #frozenRandom}), and every telemetry step
	 * pushed or popped *through this class* is suppressed - the throw-away runs must not pollute the tree that the
	 * real execution reports.
	 *
	 * The suppression covers steps, not annotations, and it only covers callers that go through this class. Code
	 * that reaches the planning context directly - `SortResolutionStrategies` is the one such caller today - can
	 * still annotate the open step during a dry run, so a query run under
	 * {@link io.evitadb.api.query.require.DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} may carry annotations
	 * contributed by a verification pass rather than by the run the client observes.
	 *
	 * The flag is derived from {@link #frozenRandom} rather than stored separately: a frozen random source is only
	 * ever supplied for plan verification, so its presence *is* the dry-run signal.
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
	 *
	 * This is the direct-read path - it ignores {@link #prefetchedEntities} entirely and always goes to the
	 * collection.
	 *
	 * @param entityType       type of the entity to fetch; `null` falls back to the collection targeted by the query
	 *                         and fails when the query targets none
	 * @param entityPrimaryKey real primary key of the entity to fetch
	 * @param requirements     the scope of data to load - the returned entity reveals no more than this
	 * @return the entity, or empty when no entity of that primary key exists
	 */
	@Nonnull
	public Optional<SealedEntity> fetchEntity(@Nullable String entityType, int entityPrimaryKey, @Nonnull EntityFetchRequire requirements) {
		final EntityCollection targetCollection = this.queryContext.getEntityCollectionOrThrowException(entityType, "fetch entity");
		final EvitaRequest fetchRequest = this.queryContext.fabricateFetchRequest(entityType, requirements);
		return targetCollection.fetchEntity(entityPrimaryKey, fetchRequest, this.queryContext.getEvitaSession());
	}

	/**
	 * Method loads requested entity contents by specifying its primary key.
	 *
	 * Bulk counterpart of {@link #fetchEntity(String, int, EntityFetchRequire)} and likewise a direct read that does
	 * not consult {@link #prefetchedEntities}.
	 *
	 * @param entityType        type of the entities to fetch; `null` falls back to the collection targeted by the
	 *                          query and fails when the query targets none
	 * @param entityPrimaryKeys real primary keys of the entities to fetch
	 * @param requirements      the scope of data to load - the returned entities reveal no more than this
	 * @return the entities that exist; keys with no matching entity are simply absent from the result
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
	 *
	 * Unlike {@link #fetchEntities(String, int[], EntityFetchRequire)} this is the response-building path: it loads
	 * the scope the original {@link EvitaRequest} asked for, reuses anything already prefetched, and wires up a
	 * {@link ReferencedEntityFetcher} when references, parents or hierarchies also have to be resolved.
	 *
	 * @param entityPrimaryKey primary keys - real or masked - of the entities to fetch, in the order they should be
	 *                         returned in
	 * @return the entities that exist, in the order of `entityPrimaryKey`; missing ones are dropped, so the result
	 *         may be shorter than the input
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
		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = evitaRequest.getNamedReferenceEntityFetch();

		// new predicates are richer that previous ones - we need to fetch additional data and create new entity
		final ReferenceFetcher entityFetcher = referenceEntityFetch.isEmpty() &&
			namedReferenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
				namedReferenceEntityFetch,
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
	 *
	 * Prefetched entities are of no help here beyond identifying which records to read: the binary form is a distinct
	 * representation, so the contents have to be re-read from the collection either way.
	 *
	 * @param entityPrimaryKey primary keys - real or masked - of the entities to fetch
	 * @return the entities that exist, in binary form
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
	 *
	 * Reads exclusively from what was already prefetched - it never falls back to the collection - so it returns
	 * `null` both for an unknown key and for one that simply was not prefetched.
	 *
	 * @param primaryKey primary key of the entity - masked when the query assigned masked keys, real otherwise
	 * @return the prefetched entity, or `null` when it is not among the prefetched ones
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
	 *
	 * This overload **replaces** whatever was prefetched so far, and it is the one the query plan drives: the plan
	 * decided during planning that reading these entities up front is cheaper than answering the filter from the
	 * indexes. Entities that turn out not to exist are silently skipped, so the prefetched list may be shorter than
	 * the order asked for.
	 *
	 * @param prefetcher carries both the entities to prefetch and the scope of data to load for them
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
	 *
	 * Unlike {@link #prefetchEntities(PrefetchOrder)} this overload **appends** to the already prefetched entities,
	 * and it accepts references that may span several entity types - those are grouped per type so each collection is
	 * visited once. Entities that do not exist are silently skipped.
	 *
	 * @param entitiesToPrefetch references of the entities to load; an empty array is a no-op
	 * @param requirements       the scope of data to load - the prefetched entities reveal no more than this
	 */
	public void prefetchEntities(@Nonnull EntityReferenceContract[] entitiesToPrefetch, @Nonnull EntityFetchRequire requirements) {
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
				for (EntityReferenceContract ref : entitiesToPrefetch) {
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
	 * Fills in the request-level defaults that an {@link EntityFetch} or {@link EntityGroupFetch} left unspecified,
	 * by rewriting the {@link AccompanyingPriceContent} requirements nested anywhere inside the given requirement
	 * tree - see {@link #updateRequirements(EntityContentRequire[])} for what exactly is substituted.
	 *
	 * Requirement trees are immutable, so this is a clone rather than an in-place edit - but a copy-on-write one:
	 * any subtree that needs no substitution is reused by reference instead of being rebuilt, which is why an
	 * untouched tree costs a traversal and nothing more.
	 *
	 * @param entityFetchRequire the requirement tree to enrich
	 * @return the enriched tree, sharing every subtree that needed no enriching with the input
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
	 *
	 * The buffer's contents are whatever the previous borrower left in it - callers must treat it as uninitialized
	 * and never read past what they wrote themselves.
	 *
	 * @return a buffer to be handed back through {@link #returnBuffer(int[])}
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
	 * Borrowed buffer is returned to local queue for reuse. The buffer stays with this context until {@link #close()}
	 * releases it to the shared pool, so returning it merely makes it available to the next
	 * {@link #borrowBuffer()} within this same execution.
	 *
	 * May only be called with a buffer this context handed out: the local queue is created lazily by
	 * {@link #borrowBuffer()}, so returning a buffer before ever borrowing one throws a {@link NullPointerException}.
	 *
	 * @param borrowedBuffer the buffer previously obtained from {@link #borrowBuffer()}
	 */
	public void returnBuffer(@Nonnull int[] borrowedBuffer) {
		this.buffers.push(borrowedBuffer);
	}

	/**
	 * Adds new step of query evaluation.
	 *
	 * This wrapper exists solely to add the {@link #isDryRun()} guard to the identically named method on the planning
	 * context: the telemetry tree belongs to the planning context and is shared by every execution of the query, so a
	 * plan-verification run must not push its steps into it. Execution-time code must therefore always go through
	 * this class and never reach for the {@link #queryContext} directly to push or pop a step.
	 *
	 * @param phase the phase the pushed step measures
	 */
	public void pushStep(@Nonnull QueryPhase phase) {
		if (!isDryRun()) {
			this.queryContext.pushStep(phase);
		}
	}

	/**
	 * Adds new step of query evaluation, describing what it is about to do.
	 *
	 * The message is resolved only when telemetry is actually being collected. There is deliberately no overload
	 * taking a plain `String` - it would let the caller build the message before this guard is reached, which is
	 * exactly the cost telemetry must not impose on a query that did not ask for it.
	 *
	 * Guarded by {@link #isDryRun()} for the same reason as {@link #pushStep(QueryPhase)}.
	 *
	 * @param phase           the phase the pushed step measures
	 * @param messageSupplier supplies the step description; invoked only when the step is actually recorded
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull Supplier<String> messageSupplier) {
		if (!isDryRun()) {
			this.queryContext.pushStep(phase, messageSupplier);
		}
	}

	/**
	 * Finishes current query evaluation step.
	 *
	 * Guarded by {@link #isDryRun()} for the same reason as {@link #pushStep(QueryPhase)}. Every push made through
	 * this class must be balanced by a pop made through this class - mixing the two contexts across a push/pop pair
	 * unbalances the stack in dry-run mode, where only one half of the pair is suppressed.
	 */
	public void popStep() {
		if (!isDryRun()) {
			this.queryContext.popStep();
		}
	}

	/**
	 * Finishes current query evaluation step, describing the outcome it arrived at.
	 *
	 * The message is resolved only when telemetry is actually being collected - see {@link #pushStep(QueryPhase,
	 * Supplier)} for why no plain `String` overload exists.
	 *
	 * @param messageSupplier supplies the outcome description; invoked only when the step is actually recorded
	 */
	public void popStep(@Nonnull Supplier<String> messageSupplier) {
		if (!isDryRun()) {
			this.queryContext.popStep(messageSupplier);
		}
	}

	/**
	 * Returns the root node of the {@link QueryTelemetry} tree to be attached to the response, if there is one to
	 * attach.
	 *
	 * Despite the shared name this does *not* behave like {@link QueryPlanningContext#getTelemetryRoot()}, which
	 * asserts that telemetry is initialized. Here the three outcomes are:
	 *
	 * - **telemetry not requested** - empty, and nothing is attached to the response
	 * - **telemetry requested** - the planning context's real root, still open at this point
	 * - **dry run** - a freshly created throw-away root that is not this context's tree at all, so that the
	 *   plan-verification runs produce a structurally valid response without touching the real telemetry
	 *
	 * @return the telemetry root to attach to the response, or empty when telemetry was not requested
	 */
	@Nonnull
	public Optional<QueryTelemetry> getTelemetryRoot() {
		if (isDryRun()) {
			return of(QueryTelemetry.root(QueryPhase.OVERALL));
		} else {
			return isTelemetryCollected() ? of(this.queryContext.getTelemetryRoot()) : empty();
		}
	}

	/**
	 * Returns true when this execution is actually building a telemetry tree that will reach the client.
	 *
	 * This is the guard a caller must test before doing work that exists **only** to feed telemetry - computing a
	 * number, building a description, reading a clock - because none of it may be charged to a query that did not
	 * ask for a profile. The `pushStep` / `popStep` family already guards itself and needs nothing from callers; a
	 * caller that has to *produce* the values does.
	 *
	 * Note a dry run answers `false` even for a query that did request telemetry: its measurements describe a
	 * throw-away plan-comparison run rather than the query, which is also why {@link #getTelemetryRoot()} hands a
	 * dry run a fresh root instead of the real one.
	 *
	 * @return true when telemetry recorded through this context ends up in the response
	 */
	public boolean isTelemetryCollected() {
		return !isDryRun() && this.queryContext.getEvitaRequest().isQueryTelemetryRequested();
	}

	/**
	 * Returns true when telemetry recorded through this context is kept **and** the query asked for the formula
	 * plan - the guard every caller that would otherwise walk a formula tree has to consult first.
	 *
	 * It is deliberately stricter than {@link #isTelemetryCollected()}: rendering a plan is O(formula nodes) of
	 * structure building, so plain `queryTelemetry()` must not pay for it. Telemetry being collected is necessary
	 * but not sufficient.
	 *
	 * @return true when a formula plan recorded through this context ends up in the response
	 */
	public boolean isTelemetryPlanCollected() {
		return isTelemetryCollected() && this.queryContext.getEvitaRequest().isQueryTelemetryPlanRequested();
	}

	/**
	 * Finalizes telemetry data by stopping the timer - closing every step still open, root included, so the tree
	 * already handed to the response carries complete timings.
	 *
	 * May be called at most once per query: it drains the planning context's telemetry stack and the underlying
	 * {@link QueryPlanningContext#finalizeTelemetry()} asserts the stack is not already empty. Skipped entirely in a
	 * dry run and when telemetry was not requested, in which case there is no stack to drain.
	 */
	public void finalizeTelemetry() {
		if (isTelemetryCollected()) {
			this.queryContext.finalizeTelemetry();
		}
	}

	/**
	 * Releases the buffers this execution borrowed back to the {@link SharedBufferPool}. This is the whole reason the
	 * class is {@link Closeable}, so an execution context must always be created in a try-with-resources block.
	 * Nothing breaks when it is not - the pool simply allocates a fresh array next time - but the reuse the pool
	 * exists for is lost, which is precisely the GC pressure it was introduced to avoid.
	 *
	 * Note that only the buffers currently held in the local queue are released; a buffer that a caller borrowed and
	 * never handed back through {@link #returnBuffer(int[])} is not tracked here and never reaches the pool either.
	 */
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
	 *
	 * The input keys are walked in order and split into two runs - those already prefetched, which only need
	 * enriching through `collector`, and those that must still be read, which go to `fetcher`. Both runs are
	 * accumulated and flushed in batches rather than per record; a batch is closed whenever the entity type or the
	 * request to use changes, which is why keeping keys of the same type adjacent keeps the batches large. The
	 * request changes when an entity was matched through a globally unique attribute that implies a locale the
	 * original request did not name - such an entity is fetched with a request fabricated for that locale.
	 *
	 * Results are re-indexed back onto the *input* keys, so masked keys are mapped back from the real primary keys
	 * the collections return, and the original order is restored at the end.
	 *
	 * @param inputPrimaryKeys primary keys - real or masked - to resolve, in the order the result should follow
	 * @param entityType       the entity type to use for keys with no prefetched entity; may only be `null` when
	 *                         every key resolves to a prefetched entity, otherwise an exception is raised
	 * @param fetcher          reads entities that were not prefetched, straight from a collection
	 * @param collector        enriches already prefetched entities up to the requested scope
	 * @return the resolved entities in the order of `inputPrimaryKeys`, with unresolvable keys dropped
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
		final EntityReferenceContract entityReference = this.queryContext.getEntityReferenceIfExist(entityPrimaryKey)
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
