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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.core.cache.model.CacheRecordAdept;
import io.evitadb.core.cache.model.CachedRecord;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.cache.payload.EntityComputationalObjectAdapter;
import io.evitadb.core.cache.payload.EntityPayload;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.PrimitiveIterator.OfLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link CacheEden} represents the Evita cache core. It contains {@link #theCache} holding all cached formulas, tracks
 * formula usage and tracks their changes resulting in cache invalidation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@ThreadSafe
public class CacheEden {
	/**
	 * Threshold that controls how many iterations {@link #evaluateAdepts()} may remain any of {@link CachedRecord}
	 * unused until it is evicted from the cache.
	 */
	public static final int COOL_ENOUGH = 3;
	/**
	 * Contains maximal size of payload that will be acceptable by this cache. Too large records will never make it
	 * to the cache.
	 */
	static final int MAX_BUFFER_SIZE = 1_048_576;
	/**
	 * Represents the core of this class - the cache.
	 */
	private final ConcurrentHashMap<Long, CachedRecord> theCache;
	/**
	 * Contains {@link CacheOptions#minimalUsageThreshold()} limit for this cache.
	 */
	private final int minimalUsageThreshold;
	/**
	 * Contains {@link CacheOptions#minimalComplexityThreshold()} limit for this cache.
	 */
	private final long minimalSpaceToPerformanceRatio;
	/**
	 * Contains {@link CacheOptions#cacheSizeInBytes()} limit for this cache.
	 */
	private final long maximalByteSize;
	/**
	 * Represents counter of hits of this cache (i.e. how many times requested formula was found in cache).
	 */
	private final AtomicLong hits = new AtomicLong();
	/**
	 * Represents counter of misses of this cache (i.e. how many times requested formula was NOT found in cache).
	 */
	private final AtomicLong misses = new AtomicLong();
	/**
	 * Represents counter of entity enrichments of this cache.
	 */
	private final AtomicLong enrichments = new AtomicLong();
	/**
	 * Lock used to synchronize {@link #evaluateAdepts()} that is expected to be called in synchronized block.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * Contains precise count of records stored in the cache.
	 */
	private final AtomicInteger cacheSize = new AtomicInteger();
	/**
	 * Contains estimate of current cache size in Bytes.
	 */
	private final AtomicLong usedByteSize = new AtomicLong();
	/**
	 * Contains reference to collection of adepts that were collected by {@link CacheAnteroom} between current and
	 * previous  {@link #evaluateAdepts()} method call.
	 */
	private final AtomicReference<Map<Long, CacheRecordAdept>> nextAdeptsToEvaluate = new AtomicReference<>();

	public CacheEden(long maximalByteSize, int minimalUsageThreshold, long minimalSpaceToPerformanceRatio) {
		// let's assume single record will occupy 1KB
		this.theCache = new ConcurrentHashMap<>(Math.toIntExact(maximalByteSize / 10_000L));
		this.maximalByteSize = maximalByteSize;
		this.minimalUsageThreshold = minimalUsageThreshold;
		this.minimalSpaceToPerformanceRatio = minimalSpaceToPerformanceRatio;
	}

	/**
	 * Returns the {@link Formula} with memoized cached result for passed `computationalObject` and its `recordHash` providing
	 * that the cached formula has the same has of used transactional ids as the current computational object. There is small
	 * chance, that returned formula hasn't yet contain serialized computed result. When the formula enters the eden
	 * its empty and when used for the first time the result is memoized. In such case, original `computationalObject` is cloned
	 * to the form, that once result is computed updates its cached counterpart.
	 */
	@Nullable
	public <T extends TransactionalDataRelatedStructure, S> S getCachedRecord(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String catalogName,
		@Nonnull Serializable entityType,
		@Nonnull T computationalObject,
		@Nonnull Class<S> expectedClass,
		long recordHash
	) {
		final CachedRecord cachedRecord = theCache.get(recordHash);
		final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();
		if (cachedRecord != null) {
			if (cachedRecord.isInitialized()) {
				// check whether cached formula is valid for current transaction id
				if (cachedRecord.getTransactionalIdHash() == computationalObject.getTransactionalIdHash()) {
					// track hit
					hits.incrementAndGet();
					if (computationalObject instanceof final EntityComputationalObjectAdapter entityWrapper) {
						return enrichCachedEntityIfNecessary(recordHash, cachedRecord, entityWrapper);
					} else {
						// return payload
						return cachedRecord.getPayload(expectedClass);
					}
				} else {
					// track - miss, formula found but not valid for current input formula regarding used transactional data
					misses.incrementAndGet();
					return null;
				}
			} else {
				// formula found but not yet initialized
				misses.incrementAndGet();
				// set up initialization lambda to cloned input computational object
				if (computationalObject instanceof final CacheableFormula inputFormula) {
					return alterToResultRecordingFormula(recordHash, cachedRecord, hashFunction, inputFormula);
				} else if (computationalObject instanceof final CacheableEvitaResponseExtraResultComputer<?> inputComputer) {
					return alterToResultRecordingComputer(recordHash, cachedRecord, hashFunction, inputComputer);
				} else if (computationalObject instanceof final CacheableSorter sortedRecordsProvider) {
					return alterToSortedRecordsProvider(recordHash, cachedRecord, hashFunction, sortedRecordsProvider);
				} else if (computationalObject instanceof final EntityComputationalObjectAdapter entityWrapper) {
					return fetchAndCacheEntity(recordHash, cachedRecord, hashFunction, entityWrapper);
				} else {
					throw new GenericEvitaInternalError("Unexpected object in cache `" + computationalObject.getClass() + "`!");
				}
			}
		}
		// formula not found record miss
		misses.incrementAndGet();
		return null;
	}

	/**
	 * Returns estimate of current cache size in Bytes.
	 */
	public long getByteSizeUsedByCache() {
		return usedByteSize.get();
	}

	/**
	 * Returns precise count of records stored in the cache.
	 */
	public int getCacheRecordCount() {
		return cacheSize.get();
	}

	/**
	 * Stores collection of {@link CacheRecordAdept} that are required to be evaluated by {@link #evaluateAdepts()}.
	 * This method can be actually called multiple times within single {@link #evaluateAdepts()} interval, if there
	 * is pressure on the evitaDB. In such case intermediate calls are ignored and their contents are garbage collected
	 * without even being analyzed and only last call will be processed.
	 */
	public void setNextAdeptsToEvaluate(@Nonnull Map<Long, CacheRecordAdept> adepts) {
		final Map<Long, CacheRecordAdept> alreadyWaitingAdepts;
		final boolean adeptsEmpty = adepts.isEmpty();
		if (adeptsEmpty) {
			alreadyWaitingAdepts = this.nextAdeptsToEvaluate.compareAndExchange(null, adepts);
		} else {
			alreadyWaitingAdepts = this.nextAdeptsToEvaluate.getAndSet(adepts);
		}
		if (!adeptsEmpty && alreadyWaitingAdepts != null && !alreadyWaitingAdepts.isEmpty()) {
			// log excessive pressure on the cache
			log.warn("Evita cache refresh doesn't keep up with cache adepts ingress (discarded " + alreadyWaitingAdepts.size() + " adepts)!");
		}
	}

	/**
	 * Returns true if there are adepts waiting for evaluation.
	 */
	public boolean isAdeptsWaitingForEvaluation() {
		return this.nextAdeptsToEvaluate.get() != null;
	}

	/**
	 * The second most important method of this class beside {@link #getCachedRecord(EvitaSessionContract, String, Serializable, TransactionalDataRelatedStructure, Class, long)}.
	 * This method evaluates all {@link CacheRecordAdept} recorded by {@link #setNextAdeptsToEvaluate(Map)}
	 * along with currently held {@link CachedRecord} records. It sorts them all by their value (performance ratio)
	 * and registers the most precious of them into the cache. It also evicts currently cached records if they become
	 * cool enough.
	 */
	public void evaluateAdepts() {
		try {
			// this method is allowed to run in one thread only
			if (lock.tryLock() || lock.tryLock(1, TimeUnit.SECONDS)) {
				try {
					// retrieve adepts to evaluate
					final Map<Long, CacheRecordAdept> adepts = nextAdeptsToEvaluate.getAndSet(null);
					if (adepts != null) {
						// copy all adepts into a new array that will be sorted
						final EvaluationCacheFormulaAdeptSource evaluationSource = mergeAdeptsWithExistingEntriesForEvaluation(adepts);
						final CacheAdeptKeyWithValue[] evaluation = evaluationSource.evaluation();
						// now sort all entries by space to performance ratio in descending order
						Arrays.sort(
							evaluation, 0, evaluationSource.peek(),
							(o1, o2) -> Long.compare(o2.spaceToPerformanceRatio(), o1.spaceToPerformanceRatio())
						);
						// now find the delimiter for entries that will be newly accepted to cache
						int threshold = -1; // index of the last entry that will be accepted to the new cache
						long occupiedMemorySize = 0; // contains memory size in bytes going to be occupied by flattened formula entries
						for (int i = 0; i < evaluationSource.peek(); i++) {
							final CacheAdeptKeyWithValue adept = evaluation[i];
							final int adeptSizeInBytes = adept.estimatedSizeInBytes();
							// increase counters for bitmaps and occupied memory size
							occupiedMemorySize += adeptSizeInBytes;
							// if the expected memory consumption is greater than allowed
							if (occupiedMemorySize > maximalByteSize) {
								// stop iterating - we found our threshold, last counter increase must be reverted
								// currently examined item will not be part of the cache
								occupiedMemorySize -= adeptSizeInBytes;
								break;
							}
							threshold = i;
						}

						// we need first to free the memory to avoid peek
						// evict all cold cached formulas
						final OfLong expiredItemsIt = evaluationSource.expiredItems().iterator();
						while (expiredItemsIt.hasNext()) {
							final long expiredFormulaHash = expiredItemsIt.next();
							theCache.remove(expiredFormulaHash);
						}
						// evict all cached formulas after the found threshold
						for (int i = threshold + 1; i < evaluationSource.peek(); i++) {
							final CacheAdeptKeyWithValue adept = evaluation[i];
							theCache.remove(adept.recordHash());
						}

						// cache all non-cached formulas before the found threshold
						// now we can allocate new memory
						for (int i = 0; i <= threshold; i++) {
							final CacheAdeptKeyWithValue adept = evaluation[i];
							// init the cached formula, final reference will be initialized with first additional request
							Optional.ofNullable(adepts.get(adept.recordHash()))
								.ifPresent(it -> {
									theCache.putIfAbsent(adept.recordHash(), it.toCachedRecord());
								});
						}

						// TOBEDONE JNO #18 - replace this with metrics
						log.debug(
							"Cache re-evaluation: " +
								"count " + theCache.size() +
								", size " + StringUtils.formatByteSize(occupiedMemorySize) +
								", hits " + this.hits.get() +
								", misses " + this.misses.get() +
								", enrichments " + this.enrichments.get() +
								", ratio " + ((float) this.hits.get() / (float) (this.misses.get() + this.hits.get())) * 100f + "%" +
								", average complexity: " + ((float) Arrays.stream(evaluation).limit(evaluationSource.peek()).mapToLong(CacheAdeptKeyWithValue::spaceToPerformanceRatio).sum() / (float) evaluationSource.peek()) +
								", adept count: " + evaluationSource.peek()

						);

						// finally, set occupied memory size according to expectations
						this.usedByteSize.set(occupiedMemorySize);
						this.cacheSize.set(theCache.size());
						this.hits.set(0);
						this.misses.set(0);
						this.enrichments.set(0);
					}
				} finally {
					lock.unlock();
				}
			}
		} catch (InterruptedException e) {
			log.warn("Failed to acquire lock for cache re-evaluation!");
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Combines collection of {@link CacheRecordAdept} with entire contents of {@link #theCache} - i.e. already
	 * {@link CachedRecord} into the single object for price evaluation. During the process the {@link CachedRecord}
	 * that hasn't been used for a long time (has cooled off) are marked for discarding.
	 */
	@Nonnull
	private EvaluationCacheFormulaAdeptSource mergeAdeptsWithExistingEntriesForEvaluation(@Nonnull Map<Long, CacheRecordAdept> adepts) {
		final CacheAdeptKeyWithValue[] evaluation = new CacheAdeptKeyWithValue[adepts.size() + cacheSize.get()];
		final CompositeLongArray expiredItems = new CompositeLongArray();
		int index = 0;
		// first fill in all waiting adepts
		final Iterator<Entry<Long, CacheRecordAdept>> adeptIt = adepts.entrySet().iterator();
		while (adeptIt.hasNext() && index < evaluation.length) {
			final Entry<Long, CacheRecordAdept> adeptEntry = adeptIt.next();
			final CacheRecordAdept adept = adeptEntry.getValue();
			final long spaceToPerformanceRatio = adept.getSpaceToPerformanceRatio(minimalUsageThreshold);
			final int estimatedSizeInBytes = CachedRecord.computeSizeInBytes(adept);
			if (estimatedSizeInBytes < MAX_BUFFER_SIZE && spaceToPerformanceRatio > minimalSpaceToPerformanceRatio) {
				evaluation[index++] = new CacheAdeptKeyWithValue(adeptEntry.getKey(), estimatedSizeInBytes, spaceToPerformanceRatio);
			}
		}
		// next fill in all existing entries in cache - we need re-evaluate even them
		final Iterator<Entry<Long, CachedRecord>> cacheIt = theCache.entrySet().iterator();
		while (cacheIt.hasNext() && index < evaluation.length) {
			final Entry<Long, CachedRecord> cachedRecordEntry = cacheIt.next();
			final CachedRecord cachedRecord = cachedRecordEntry.getValue();
			if (cachedRecord.reset() <= COOL_ENOUGH) {
				evaluation[index++] = new CacheAdeptKeyWithValue(
					cachedRecordEntry.getKey(),
					cachedRecord.getSizeInBytes(),
					cachedRecord.getSpaceToPerformanceRatio(minimalUsageThreshold)
				);
			} else {
				expiredItems.add(cachedRecordEntry.getKey());
			}
		}
		return new EvaluationCacheFormulaAdeptSource(evaluation, index, expiredItems);
	}

	/**
	 * Method will replace the `inputFormula` with a clone that allows capturing the computational result and store it
	 * to the eden cache for future requests.
	 */
	@Nonnull
	private <S> S alterToResultRecordingFormula(
		long recordHash,
		@Nonnull CachedRecord cachedRecord,
		@Nonnull LongHashFunction hashFunction,
		@Nonnull CacheableFormula inputFormula
	) {
		// otherwise, clone input formula and add logic, that will store the computed result to the cache
		//noinspection unchecked
		return (S) inputFormula.getCloneWithComputationCallback(
			cacheableFormula -> {
				final CachePayloadHeader payload = inputFormula.toSerializableFormula(recordHash, hashFunction);
				theCache.put(
					recordHash,
					new CachedRecord(
						cachedRecord.getRecordHash(),
						cachedRecord.getCostToPerformanceRatio(),
						cachedRecord.getTimesUsed(),
						cachedRecord.getSizeInBytes(),
						inputFormula.getTransactionalIdHash(),
						payload
					)
				);
			},
			inputFormula.getInnerFormulas()
		);
	}

	/**
	 * Method will replace the `inputComputer` with a clone that allows capturing the computational result and store it
	 * to the eden cache for future requests.
	 */
	@Nonnull
	private <S> S alterToResultRecordingComputer(long recordHash, @Nonnull CachedRecord cachedRecord, @Nonnull LongHashFunction hashFunction, @Nonnull CacheableEvitaResponseExtraResultComputer<?> inputComputer) {
		// otherwise, clone input computer and add logic, that will store the computed result to the cache
		//noinspection unchecked
		return (S) inputComputer.getCloneWithComputationCallback(
			cacheableFormula -> {
				final CachePayloadHeader payload = inputComputer.toSerializableResult(recordHash, hashFunction);
				theCache.put(
					recordHash,
					new CachedRecord(
						cachedRecord.getRecordHash(),
						cachedRecord.getCostToPerformanceRatio(),
						cachedRecord.getTimesUsed(),
						cachedRecord.getSizeInBytes(),
						inputComputer.getTransactionalIdHash(),
						payload
					)
				);
			}
		);
	}

	/**
	 * Method will replace the `sorter` with a clone that captures the result and store it to the eden
	 * cache for future requests.
	 */
	@Nonnull
	private <S> S alterToSortedRecordsProvider(long recordHash, @Nonnull CachedRecord cachedRecord, @Nonnull LongHashFunction hashFunction, @Nonnull CacheableSorter sorter) {
		// otherwise, clone input computer and add logic, that will store the computed result to the cache
		//noinspection unchecked
		return (S) sorter.getCloneWithComputationCallback(
			cacheableFormula -> {
				final CachePayloadHeader payload = sorter.toSerializableResult(recordHash, hashFunction);
				theCache.put(
					recordHash,
					new CachedRecord(
						cachedRecord.getRecordHash(),
						cachedRecord.getCostToPerformanceRatio(),
						cachedRecord.getTimesUsed(),
						cachedRecord.getSizeInBytes(),
						sorter.getTransactionalIdHash(),
						payload
					)
				);
			}
		);
	}

	/**
	 * Method will fetch entity from the datastore and stores it to the eden cache for future requests.
	 */
	@Nullable
	private <S> S fetchAndCacheEntity(long recordHash, @Nonnull CachedRecord cachedRecord, @Nonnull LongHashFunction hashFunction, @Nonnull EntityComputationalObjectAdapter entityWrapper) {
		final EntityDecorator entityToCache = entityWrapper.fetchEntity();
		if (entityToCache != null && entityToCache.exists()) {
			theCache.put(
				recordHash,
				new CachedRecord(
					cachedRecord.getRecordHash(),
					cachedRecord.getCostToPerformanceRatio(),
					cachedRecord.getTimesUsed(),
					cachedRecord.getSizeInBytes(),
					entityWrapper.getTransactionalIdHash(),
					new EntityPayload(
						entityToCache.getDelegate(),
						entityToCache.getLocalePredicate(),
						entityToCache.getHierarchyPredicate(),
						entityToCache.getAttributePredicate(),
						entityToCache.getAssociatedDataPredicate(),
						entityToCache.getReferencePredicate(),
						entityToCache.getPricePredicate()
					)
				)
			);
		}
		//noinspection unchecked
		return (S) entityToCache;
	}

	/**
	 * Method will check whether the cached entity is rich enough to satisfy the input query and if not, the entity is
	 * lazily enriched of additional data and the cached object is replaced with this richer entity for future use.
	 */
	private <S> S enrichCachedEntityIfNecessary(long recordHash, @Nonnull CachedRecord cachedRecord, @Nonnull EntityComputationalObjectAdapter entityWrapper) {
		final EntityPayload cachedPayload = cachedRecord.getPayload(EntityPayload.class);
		final EntityDecorator cachedEntity = new EntityDecorator(
			cachedPayload.entity(),
			entityWrapper.getEntitySchema(),
			null,
			cachedPayload.localePredicate(),
			cachedPayload.hierarchyPredicate(),
			cachedPayload.attributePredicate(),
			cachedPayload.associatedDataPredicate(),
			cachedPayload.referencePredicate(),
			cachedPayload.pricePredicate(),
			entityWrapper.getAlignedNow()
		);
		final EntityDecorator enrichedEntity = entityWrapper.enrichEntity(cachedEntity);
		if (enrichedEntity != cachedEntity) {
			theCache.put(
				recordHash,
				new CachedRecord(
					cachedRecord.getRecordHash(),
					cachedRecord.getCostToPerformanceRatio(),
					cachedRecord.getTimesUsed(),
					cachedRecord.getSizeInBytes(),
					cachedRecord.getTransactionalIdHash(),
					new EntityPayload(
						enrichedEntity.getDelegate(),
						enrichedEntity.getLocalePredicate(),
						enrichedEntity.getHierarchyPredicate(),
						enrichedEntity.getAttributePredicate(),
						enrichedEntity.getAssociatedDataPredicate(),
						enrichedEntity.getReferencePredicate(),
						enrichedEntity.getPricePredicate()
					)
				)
			);
			enrichments.incrementAndGet();
			//noinspection unchecked
			return (S) enrichedEntity;
		} else {
			//noinspection unchecked
			return (S) cachedEntity;
		}
	}

	/**
	 * DTO that collects all adepts for price evaluation and storing into the cache along with identification of those
	 * that are marked for eviction.
	 */

	private record EvaluationCacheFormulaAdeptSource(
		@Nonnull CacheAdeptKeyWithValue[] evaluation,
		int peek,
		@Nonnull CompositeLongArray expiredItems
	) {
	}

	/**
	 * The adept value allowing to propagate most prospective adepts / existing cache records to the new version of
	 * the cache.
	 *
	 * @param recordHash              the hash that uniquely represents the cached value
	 * @param estimatedSizeInBytes    estimated size of the record in the memory
	 * @param spaceToPerformanceRatio worthiness of the cached record computed as a ration between his size and efficiency
	 */
	private record CacheAdeptKeyWithValue(
		long recordHash,
		int estimatedSizeInBytes,
		long spaceToPerformanceRatio
	) {

	}

}
