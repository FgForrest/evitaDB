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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.core.cache.model.CacheRecordAdept;
import io.evitadb.core.cache.model.CacheRecordType;
import io.evitadb.core.cache.payload.BinaryEntityComputationalObjectAdapter;
import io.evitadb.core.cache.payload.EntityComputationalObjectAdapter;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.cache.AnteroomRecordStatisticsUpdatedEvent;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormulaScope;
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.response.ServerBinaryEntityDecorator;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Cache anteroom represents a stage before the formulas enter the cache. It collects information about formula usage
 * and their costs and computes the "ROI" value of them. Only the most valuable formulas will make it to the
 * {@link CacheEden} and consume the precious memory for the sake of better system performance. If you see any analogies
 * with the Bible, you're right :).
 *
 * The key entry-points of this class are:
 *
 * - {@link #register(EvitaSessionContract, String, Formula, FormulaCacheVisitor)}
 * - {@link #register(EvitaSessionContract, String, CacheableSorter)}
 * - {@link #register(EvitaSessionContract, String, CacheableEvitaResponseExtraResultComputer)}
 * - {@link #register(EvitaSessionContract, int, Serializable, EntityFetch, Supplier)}
 * - {@link #evaluateAssociates(boolean)}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@ThreadSafe
public class CacheAnteroom {
	/**
	 * Contains limit of maximal entries held in CacheAnteroom. When this limit is exceeded the anteroom needs to be
	 * re-evaluated and the adepts either cleared or moved to {@link CacheEden}. In other words it's the size of the
	 * CacheAnteroom buffer.
	 */
	private final int maxRecordCount;
	/**
	 * Contains minimal threshold of the {@link Formula#getEstimatedCost()} that formula needs to exceed in order to
	 * become a cache adept, that may be potentially moved to {@link CacheEden}.
	 */
	private final long minimalComplexityThreshold;
	/**
	 * Contains reference to the real cache.
	 */
	private final CacheEden cacheEden;
	/**
	 * Task that evaluates adepts for the eden.
	 */
	private final DelayedAsyncTask edenGateKeeper;
	/**
	 * Contains a hash map that collects adepts for the caching. In other terms the expensive data structures that were
	 * recently computed and might be worth caching. The map is cleared each time {@link #evaluateAssociates(boolean)}
	 * is executed.
	 */
	private final AtomicReference<ConcurrentHashMap<Long, CacheRecordAdept>> cacheAdepts;

	/**
	 * Method computes long hash for passed `dataStructure`.
	 */
	static long computeDataStructureHash(
		@Nonnull String catalogName,
		@Nonnull Serializable entityType,
		@Nonnull TransactionalDataRelatedStructure dataStructure
	) {
		final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();
		return hashFunction.hashLongs(
			new long[]{
				hashFunction.hashChars(catalogName),
				hashFunction.hashChars(entityType.toString()),
				dataStructure.getHash()
			}
		);
	}

	public CacheAnteroom(int maxRecordCount, long minimalComplexityThreshold, @Nonnull CacheEden cacheEden, @Nonnull Scheduler scheduler) {
		this.cacheAdepts = new AtomicReference<>(CollectionUtils.createConcurrentHashMap((int) (maxRecordCount * 1.1)));
		this.cacheEden = cacheEden;
		this.maxRecordCount = maxRecordCount;
		this.minimalComplexityThreshold = minimalComplexityThreshold;
		this.edenGateKeeper = new DelayedAsyncTask(
			null,
			"Eden cache adepts inbound reevaluation",
			scheduler,
			this.cacheEden::evaluateAdepts,
			0, TimeUnit.MILLISECONDS, 0
		);
	}

	/**
	 * Hands off {@link #cacheAdepts} via. {@link CacheEden#setNextAdeptsToEvaluate(Map)} for evaluation. It also
	 * triggers immediate evaluation of those adepts in this thread context.
	 *
	 * Beware the evaluation might take a while - it's better considering asynchronous variant of this method
	 * ({@link #evaluateAssociatesAsynchronously()}) that is fast.
	 */
	public void evaluateAssociatesSynchronously() {
		evaluateAssociates(true);
	}

	/**
	 * Hands off {@link #cacheAdepts} via. {@link CacheEden#setNextAdeptsToEvaluate(Map)} for evaluation. It also
	 * triggers evaluation of those adepts in different thread using {@link Scheduler}. The evaluation will start almost
	 * immediately if there is any thread available in the executor pool.
	 */
	public void evaluateAssociatesAsynchronously() {
		evaluateAssociates(false);
	}

	/**
	 * Method examines `formula`, whether the formula computation seems expensive and whether formula claims that it
	 * supports caching of its results. If so, it's checked for existing cached result in {@link CacheEden} and return
	 * it immediately. If no cached result is found it registers the formula to {@link #cacheAdepts} and returns a clone
	 * that records the detailed information on first formula computation.
	 *
	 * Special treatment is provided for formulas within {@link PriceTerminationFormula} - these formulas even if
	 * cacheable and expensive may hold so large data that are excluded from caching.
	 */
	@Nonnull
	public Formula register(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull Formula formula,
		@Nonnull FormulaCacheVisitor formulaVisitor
	) {
		if (formula instanceof final CacheableFormula inputFormula &&
			(formula instanceof CacheablePriceFormula || !formulaVisitor.isWithin(NonCacheableFormulaScope.class))) {
			if (formula.getEstimatedCost() >= this.minimalComplexityThreshold) {
				final String catalogName = evitaSession.getCatalogName();
				final long formulaHash = computeDataStructureHash(catalogName, entityType, inputFormula);
				final Formula cachedFormula = this.cacheEden.getCachedRecord(evitaSession, catalogName, entityType, inputFormula, Formula.class, formulaHash);
				return cachedFormula != null ?
					cachedFormula :
					recordUsageAndReturnInstrumentedCopyIfNotYetSeen(formulaVisitor, inputFormula, formulaHash);
			} else {
				return formula;
			}
		} else {
			return formula;
		}
	}

	/**
	 * Method examines `computer`, whether the computation seems expensive and whether computer claims that it
	 * supports caching of its results. If so, it's checked for existing cached result in {@link CacheEden} and return
	 * it immediately. If no cached result is found it registers the formula to {@link #cacheAdepts} and returns a clone
	 * that records the detailed information on first computation.
	 */
	@SuppressWarnings("ClassEscapesDefinedScope")
	@Nonnull
	public <U> EvitaResponseExtraResultComputer<?> register(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull CacheableEvitaResponseExtraResultComputer<U> computer
	) {
		if (computer.getEstimatedCost() > this.minimalComplexityThreshold) {
			final String catalogName = evitaSession.getCatalogName();
			final long recordHash = computeDataStructureHash(catalogName, entityType, computer);
			final EvitaResponseExtraResultComputer<?> cachedResult = this.cacheEden.getCachedRecord(
				evitaSession, catalogName, entityType, computer, EvitaResponseExtraResultComputer.class, recordHash
			);
			return cachedResult == null ?
				recordUsageAndReturnInstrumentedCopyIfNotYetSeen(computer, recordHash) : cachedResult;
		} else {
			return computer;
		}
	}

	/**
	 * Method examines `cacheableSorter`, whether the computation seems expensive and whether cacheableSorter claims that it
	 * supports caching of its results. If so, it's checked for existing cached result in {@link CacheEden} and return
	 * it immediately. If no cached result is found it registers the formula to {@link #cacheAdepts} and returns a clone
	 * that records the detailed information on first computation.
	 */
	@SuppressWarnings("ClassEscapesDefinedScope")
	@Nonnull
	public Sorter register(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull CacheableSorter cacheableSorter
	) {
		if (cacheableSorter.getEstimatedCost() > this.minimalComplexityThreshold) {
			final String catalogName = evitaSession.getCatalogName();
			final long recordHash = computeDataStructureHash(catalogName, entityType, cacheableSorter);
			final Sorter cachedResult = this.cacheEden.getCachedRecord(
				evitaSession, catalogName, entityType, cacheableSorter, Sorter.class, recordHash
			);
			return cachedResult == null ?
				recordUsageAndReturnInstrumentedCopyIfNotYetSeen(cacheableSorter, recordHash) : cachedResult;
		} else {
			return cacheableSorter;
		}
	}

	/**
	 * Method checks whether the entity with `entityPrimaryKey` is present in {@link CacheEden} and if so it returns
	 * it immediately applying the `sealer` function that might hide the data that were not requested but are loaded
	 * for the entity object in cache. If no cached result is found it fetches the entity from the persistent data store
	 * and registers the entity request to {@link #cacheAdepts} along with the information about its size. The cache
	 * adept information is then used to evaluate the worthiness of keeping this entity in cache.
	 */
	@Nullable
	public ServerEntityDecorator register(
		@Nonnull EvitaSessionContract evitaSession,
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull OffsetDateTime alignedNow,
		@Nullable EntityFetch entityRequirement,
		@Nonnull Supplier<ServerEntityDecorator> entityFetcher,
		@Nonnull UnaryOperator<ServerEntityDecorator> enricher
	) {
		final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();
		final String catalogName = evitaSession.getCatalogName();
		final long recordHash = hashFunction.hashLongs(
			new long[]{
				hashFunction.hashChars(catalogName),
				entityPrimaryKey,
				hashFunction.hashChars(entityType)
			}
		);
		final EntityComputationalObjectAdapter entityWrapper = new EntityComputationalObjectAdapter(
			entityPrimaryKey,
			() -> evitaSession.getEntitySchemaOrThrow(entityType),
			entityFetcher,
			enricher,
			alignedNow,
			Optional.ofNullable(entityRequirement)
				.map(it -> it.getRequirements().length + 1)
				.orElse(0),
			this.minimalComplexityThreshold
		);
		final ServerEntityDecorator cachedResult = this.cacheEden.getCachedRecord(
			evitaSession, catalogName, entityType, entityWrapper, ServerEntityDecorator.class, recordHash
		);
		if (cachedResult == null) {
			final ServerEntityDecorator entity = entityFetcher.get();
			if (entity == null) {
				return null;
			} else {
				final AtomicBoolean enlarged = new AtomicBoolean(false);
				final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
				final CacheRecordAdept cacheRecordAdept = currentCacheAdepts.computeIfAbsent(
					recordHash, fHash -> {
						enlarged.set(true);
						return new CacheRecordAdept(
							CacheRecordType.ENTITY,
							fHash,
							entityWrapper.getCostToPerformanceRatio(),
							1,
							entity.estimateSize()
						);
					}
				);
				if (enlarged.get() && currentCacheAdepts.size() > this.maxRecordCount) {
					CacheAnteroom.this.evaluateAssociatesAsynchronously();
				}
				cacheRecordAdept.used();
				return entity;
			}
		} else {
			return cachedResult;
		}
	}

	/**
	 * Method checks whether the entity wit `entityPrimaryKey` is present in {@link CacheEden} and if so it returns
	 * it immediately. If no cached result is found it fetches the entity from the persistent data store
	 * and registers the entity request to {@link #cacheAdepts} along with the information about its size. The cache
	 * adept information is then used to evaluate the worthiness of keeping this entity in cache.
	 *
	 * The method is analogous to {@link #register(EvitaSessionContract, int, String, OffsetDateTime, EntityFetch, Supplier, UnaryOperator)}
	 * but it returns {@link BinaryEntity} instead.
	 *
	 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
	 */
	@Nullable
	public ServerBinaryEntityDecorator register(
		@Nonnull EvitaSessionContract evitaSession,
		int entityPrimaryKey,
		@Nonnull Serializable entityType,
		@Nullable EntityFetch entityRequirement,
		@Nonnull Supplier<ServerBinaryEntityDecorator> entityFetcher
	) {
		final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();
		final String catalogName = evitaSession.getCatalogName();
		final long recordHash = hashFunction.hashLongs(
			new long[]{
				hashFunction.hashChars(catalogName),
				entityPrimaryKey,
				hashFunction.hashChars(entityType.toString())
			}
		);
		final BinaryEntityComputationalObjectAdapter entityWrapper = new BinaryEntityComputationalObjectAdapter(
			entityPrimaryKey,
			entityFetcher,
			Optional.ofNullable(entityRequirement)
				.map(it -> it.getRequirements().length + 1)
				.orElse(0),
			this.minimalComplexityThreshold
		);
		final ServerBinaryEntityDecorator cachedResult = this.cacheEden.getCachedRecord(
			evitaSession, catalogName, entityType, entityWrapper, ServerBinaryEntityDecorator.class, recordHash
		);
		if (cachedResult == null) {
			final ServerBinaryEntityDecorator entity = entityFetcher.get();
			final AtomicBoolean enlarged = new AtomicBoolean(false);
			final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
			final CacheRecordAdept cacheRecordAdept = currentCacheAdepts.computeIfAbsent(
				recordHash, fHash -> {
					enlarged.set(true);
					return new CacheRecordAdept(
						CacheRecordType.ENTITY,
						fHash,
						entityWrapper.getCostToPerformanceRatio(),
						1,
						entity.estimateSize()
					);
				}
			);
			if (enlarged.get() && currentCacheAdepts.size() > this.maxRecordCount) {
				CacheAnteroom.this.evaluateAssociatesAsynchronously();
			}
			cacheRecordAdept.used();
			return entity;
		} else {
			return cachedResult;
		}
	}

	/**
	 * Hands off {@link #cacheAdepts} via. {@link CacheEden#setNextAdeptsToEvaluate(Map)} for evaluation. It also
	 * triggers immediate evaluation of those adepts in this thread context, but only if there is no previous map
	 * of adepts waiting for evaluation.
	 *
	 * This method is expected to be used only from {@link HeapMemoryCacheSupervisor} so that we ensure that the adepts
	 * are processed even if the map is not getting filled fast enough, but also avoid overheating the evaluation
	 * process.
	 */
	void evaluateAssociatesSynchronouslyIfNoAdeptsWait() {
		if (!this.cacheEden.isAdeptsWaitingForEvaluation()) {
			evaluateAssociates(true);
		}
	}

	/**
	 * Method returns {@link CacheRecordAdept} for passed `dataStructure`.
	 * The key is the {@link TransactionalDataRelatedStructure#getHash()}.
	 */
	@Nullable
	CacheRecordAdept getCacheAdept(
		@Nonnull String catalogName,
		@Nonnull Serializable entityType,
		@Nonnull TransactionalDataRelatedStructure dataStructure
	) {
		return this.cacheAdepts.get().get(computeDataStructureHash(catalogName, entityType, dataStructure));
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method reports statistics about the number of adepts waiting in the anteroom.
	 */
	private void reportStatistics() {
		final ConcurrentHashMap<Long, CacheRecordAdept> anteroom = this.cacheAdepts.get();
		new AnteroomRecordStatisticsUpdatedEvent(anteroom == null ? 0 : anteroom.size()).commit();
	}

	/**
	 * Hands off {@link #cacheAdepts} via. {@link CacheEden#setNextAdeptsToEvaluate(Map)} for evaluation. It also
	 * triggers evaluation of those adepts.
	 */
	private void evaluateAssociates(boolean synchronously) {
		try {
			final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
			if (currentCacheAdepts.isEmpty()) {
				// we need to trigger evaluation even if current adepts are empty
				// in order to trigger cooling of currently cached records
				this.cacheEden.setNextAdeptsToEvaluate(Collections.emptyMap());
			} else {
				// create new cache adepts map and move the old one to the cache eden for evaluation
				final ConcurrentHashMap<Long, CacheRecordAdept> adeptsToEvaluate = this.cacheAdepts.getAndSet(
					CollectionUtils.createConcurrentHashMap(currentCacheAdepts.size())
				);
				this.cacheEden.setNextAdeptsToEvaluate(adeptsToEvaluate);
			}
			// evaluate either in this thread or via thread executor
			if (synchronously) {
				this.cacheEden.evaluateAdepts();
			} else {
				this.edenGateKeeper.schedule();
			}
		} catch (RuntimeException e) {
			// we don't rethrow - it would stop engine, just log error
			log.error("Failed to evaluate cache associates: " + e.getMessage(), e);
		}
	}

	/**
	 * Method will check whether the `inputFormula` is already registered in {@link #cacheAdepts} and if so, it's immediately
	 * returned. If not - new clone is created and {@link #recordDataOnComputationCompletion(CacheRecordType, long, int, long)}
	 * is introduced to it so that critical information are filled in on first result computation.
	 *
	 * If new cache adept is created it's checked whether the number of adepts exceeds {@link #maxRecordCount} and if
	 * so, the {@link #evaluateAssociatesAsynchronously()} process is executed.
	 */
	@Nonnull
	private Formula recordUsageAndReturnInstrumentedCopyIfNotYetSeen(
		@Nonnull FormulaCacheVisitor formulaVisitor,
		@Nonnull CacheableFormula inputFormula,
		long formulaHash
	) {
		final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
		final CacheRecordAdept cacheFormulaAdept = currentCacheAdepts.get(formulaHash);
		if (cacheFormulaAdept == null) {
			final Formula[] childrenFormulas = formulaVisitor.analyseChildren(inputFormula);
			// if the children formulas are null - it means they contain UserFilter and we can't allow caching this formula
			if (childrenFormulas == null) {
				return inputFormula;
			} else {
				return inputFormula.getCloneWithComputationCallback(
					self -> recordDataOnComputationCompletion(
						CacheRecordType.FORMULA,
						formulaHash,
						self.getSerializableFormulaSizeEstimate(),
						self.getCostToPerformanceRatio()
					),
					childrenFormulas
				);
			}
		} else {
			cacheFormulaAdept.used();
			return inputFormula;
		}
	}

	/**
	 * Method will check whether the `extraResult` is already registered in {@link #cacheAdepts} and if so, it's immediately
	 * returned. If not - new clone is created and {@link #recordDataOnComputationCompletion(CacheRecordType, long, int, long)}
	 * is introduced to it so that critical information are filled in on first result computation.
	 *
	 * If new cache adept is created it's checked whether the number of adepts exceeds {@link #maxRecordCount} and if
	 * so, the {@link #evaluateAssociatesAsynchronously()} process is executed.
	 */
	@Nonnull
	private CacheableEvitaResponseExtraResultComputer<?> recordUsageAndReturnInstrumentedCopyIfNotYetSeen(
		@Nonnull CacheableEvitaResponseExtraResultComputer<?> extraResult,
		long extraResultHash
	) {
		final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
		final CacheRecordAdept cacheRecordAdept = currentCacheAdepts.get(extraResultHash);
		if (cacheRecordAdept == null) {
			return extraResult.getCloneWithComputationCallback(
				self -> recordDataOnComputationCompletion(
					CacheRecordType.EXTRA_RESULT,
					extraResultHash,
					self.getSerializableResultSizeEstimate(),
					self.getCostToPerformanceRatio()
				)
			);
		} else {
			cacheRecordAdept.used();
			return extraResult;
		}
	}

	/**
	 * Method will check whether the `extraResult` is already registered in {@link #cacheAdepts} and if so, it's immediately
	 * returned. If not - new clone is created and {@link #recordDataOnComputationCompletion(CacheRecordType, long, int, long)}
	 * is introduced to it so that critical information are filled in on first result computation.
	 *
	 * If new cache adept is created it's checked whether the number of adepts exceeds {@link #maxRecordCount} and if
	 * so, the {@link #evaluateAssociatesAsynchronously()} process is executed.
	 */
	@Nonnull
	private Sorter recordUsageAndReturnInstrumentedCopyIfNotYetSeen(
		@Nonnull CacheableSorter cacheableSorter,
		long extraResultHash
	) {
		final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
		final CacheRecordAdept cacheRecordAdept = currentCacheAdepts.get(extraResultHash);
		if (cacheRecordAdept == null) {
			return cacheableSorter.getCloneWithComputationCallback(
				self -> recordDataOnComputationCompletion(
					CacheRecordType.SORTED_RESULT,
					extraResultHash,
					self.getSerializableResultSizeEstimate(),
					self.getCostToPerformanceRatio()
				)
			);
		} else {
			cacheRecordAdept.used();
			return cacheableSorter;
		}
	}

	/**
	 * This method is used as computational callback for formulas and extra result computers. When the computation result
	 * is available it computes the expected size of the cached record (without really caching it) and computes
	 * the cost to performance ration - i.e. how much memory we'll pay for how big performance gain. This number is
	 * the key aspect for deciding whether this data structure is worth caching.
	 */
	private void recordDataOnComputationCompletion(
		@Nonnull CacheRecordType recordType,
		long formulaHash,
		int estimatedMemorySize,
		long costToPerformanceRatio
	) {
		final ConcurrentHashMap<Long, CacheRecordAdept> currentCacheAdepts = this.cacheAdepts.get();
		currentCacheAdepts.compute(
			formulaHash,
			(fHash, existingCacheRecordAdept) -> {
				if (existingCacheRecordAdept == null) {
					return new CacheRecordAdept(
						recordType,
						formulaHash,
						costToPerformanceRatio,
						1,
						CacheRecordAdept.estimateSize(estimatedMemorySize)
					);
				} else {
					existingCacheRecordAdept.used();
					return existingCacheRecordAdept;
				}
			}
		);
		if (currentCacheAdepts.size() > this.maxRecordCount) {
			CacheAnteroom.this.evaluateAssociatesAsynchronously();
		}
	}

}
