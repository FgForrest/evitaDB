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

package io.evitadb.core.cache.model;

import io.evitadb.core.cache.CacheEden;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.utils.MemoryMeasuringConstants.INT_SIZE;
import static io.evitadb.utils.MemoryMeasuringConstants.LONG_SIZE;
import static io.evitadb.utils.MemoryMeasuringConstants.REFERENCE_SIZE;

/**
 * Data object that represents really cached computational object in {@link CacheEden}, usually also with binary form
 * of serialized {@link CachePayloadHeader} or other type of serialized computational object, that can be used
 * to instantly access the computed result without really computing it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CachedRecord extends CacheRecordAdept {
	/**
	 * Estimated {@link CachedRecord} size on Java heap in bytes.
	 */
	private static final int BASE_SIZE = ADEPT_SIZE + 2 * REFERENCE_SIZE + INT_SIZE + LONG_SIZE;
	/**
	 * Contains the counter of {@link CacheEden#evaluateAdepts()} executions this record was observed
	 * as never used (in that particular interval).
	 */
	private final AtomicInteger cooling = new AtomicInteger();
	/**
	 * Contains hash of all transactional ids computed by
	 * {@link TransactionalDataRelatedStructure#getTransactionalIdHash()}. This is hash of all
	 * ids of transactional data structures that this cached values relates to. The hash must exactly match the hash
	 * of the input computational object if the cached result can be used.
	 *
	 * TOBEDONE JNO #37 - this is unnecessary for extra result computers and formulas, but necessary for entities. This should
	 * be moved to payload of those entities and validated somehow there, also we need to add integration tests for
	 * the cache!!!
	 **/
	private final long transactionalIdHash;
	/**
	 * Contains the object that is being cached.
	 */
	private final Object payload;

	/**
	 * Computes estimated size in Bytes of the {@link CachedRecord} in case the {@link CacheRecordAdept} is converted
	 * to it.
	 */
	public static int computeSizeInBytes(@Nonnull CacheRecordAdept adept) {
		if (adept instanceof CachedRecord) {
			return adept.getSizeInBytes();
		} else {
			return BASE_SIZE + adept.getSizeInBytes();
		}
	}

	public CachedRecord(
		byte recordType,
		long recordHash,
		long costToPerformanceRatio,
		int timesUsed,
		int sizeInBytes
	) {
		super(recordType, recordHash, costToPerformanceRatio, timesUsed, Math.toIntExact((long)sizeInBytes + (long)BASE_SIZE));
		this.transactionalIdHash = -1L;
		this.payload = null;
	}

	public CachedRecord(
		byte recordType,
		long recordHash,
		long costToPerformanceRatio,
		int timesUsed,
		int sizeInBytes,
		long transactionalIdHash,
		@Nonnull Object payload
	) {
		super(recordType, recordHash, costToPerformanceRatio, timesUsed, sizeInBytes);
		this.transactionalIdHash = transactionalIdHash;
		this.payload = payload;
	}

	/**
	 * Returns true if this instance contains the payload.
	 */
	public boolean isInitialized() {
		return this.payload != null;
	}


	/**
	 * Returns the count of {@link CacheEden#evaluateAdepts()} executions this computational object was observed as
	 * never used (in that particular interval). After reaching {@link CacheEden#COOL_ENOUGH} threshold
	 * the computational object must be removed from the cache.
	 */
	public int getCooling() {
		return this.cooling.get();
	}

	/**
	 * Returns payload of this cached record.
	 */
	public <T> T getPayload(Class<T> expectedClass) {
		Assert.isTrue(isInitialized(), "Cache record is not initialized!");
		Assert.isTrue(expectedClass.isInstance(this.payload), "Cache record contains " + this.payload.getClass() + " but expected is " + expectedClass);
		used();
		//noinspection unchecked
		return (T) this.payload;
	}

	/**
	 * Returns hash of all transactional ids computed by
	 * {@link TransactionalDataRelatedStructure#getTransactionalIdHash()}. This is hash of all
	 * ids of transactional data structures that this cached values relates to. The hash must exactly match the hash
	 * of the input computational object if the cached result can be used.
	 */
	public long getTransactionalIdHash() {
		Assert.isTrue(isInitialized(), "Cache record is not initialized!");
		return this.transactionalIdHash;
	}

	/**
	 * Method resets the {@link #timesUsed} counter. If the cached computational object was never used (the counter is
	 * still zero) {@link #cooling} counter is increased otherwise cooling counter is reset to zero.
	 */
	public int reset() {
		final int usedTimes = this.timesUsed.getAndSet(0);
		if (usedTimes == 0) {
			return this.cooling.incrementAndGet();
		} else {
			this.cooling.set(0);
			return 0;
		}
	}
}
