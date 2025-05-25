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

import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.utils.MemoryMeasuringConstants.*;

/**
 * Data object that is used to track computational object usage for the sake of the caching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CacheRecordAdept {
	/**
	 * Estimated {@link CacheRecordAdept} size on Java heap in bytes.
	 */
	protected static final int ADEPT_SIZE = OBJECT_HEADER_SIZE + 2 * INT_SIZE + LONG_SIZE + 3 * REFERENCE_SIZE + ARRAY_BASE_SIZE;
	/**
	 * Type of the record in payload referring to {@link CacheRecordType#getOffset()}
	 */
	@Getter protected final byte recordType;
	/**
	 * Hash that uniquely identifies the serialized computational object. It needs to be computed with low collision
	 * hashing function.
	 */
	@Getter protected final long recordHash;
	/**
	 * Contains result of {@link TransactionalDataRelatedStructure#getCostToPerformanceRatio()} stored for the first
	 * time computational object result was computed.
	 */
	@Getter protected final long costToPerformanceRatio;
	/**
	 * Contains estimated cached record size in bytes. It contains the fix costs from {@link #ADEPT_SIZE} and size
	 * of the complete serialized record results into the binary form.
	 */
	@Getter protected final int sizeInBytes;
	/**
	 * Contains the count of cases when the exactly same computation was encountered in Evita search query.
	 */
	protected final AtomicInteger timesUsed;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(int payloadSize) {
		return CacheRecordAdept.ADEPT_SIZE + payloadSize;
	}

	public CacheRecordAdept(byte recordType, long recordHash, long costToPerformanceRatio, int timesUsed, int sizeInBytes) {
		this.recordType = recordType;
		this.recordHash = recordHash;
		this.timesUsed = new AtomicInteger(timesUsed);
		this.costToPerformanceRatio = costToPerformanceRatio;
		this.sizeInBytes = sizeInBytes;
	}
	public CacheRecordAdept(@Nonnull CacheRecordType recordType, long recordHash, long costToPerformanceRatio, int timesUsed, int sizeInBytes) {
		this.recordType = recordType.getOffset();
		this.recordHash = recordHash;
		this.timesUsed = new AtomicInteger(timesUsed);
		this.costToPerformanceRatio = costToPerformanceRatio;
		this.sizeInBytes = sizeInBytes;
	}

	/**
	 * Records usage of this record. More frequently records have higher chance to occupy the cache.
	 */
	public void used() {
		this.timesUsed.incrementAndGet();
	}

	/**
	 * Computes effectivity of this cache adept considering its {@link #costToPerformanceRatio} and the memory size
	 * it would consume.
	 */
	public long getSpaceToPerformanceRatio(int minimalUsageThreshold) {
		try {
			return Math.multiplyExact(this.costToPerformanceRatio, Math.max(0, this.timesUsed.get() - minimalUsageThreshold)) / this.sizeInBytes;
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Returns count of usages of this particular record identified by
	 * {@link TransactionalDataRelatedStructure#getTransactionalIdHash()}
	 */
	public int getTimesUsed() {
		return this.timesUsed.get();
	}

	/**
	 * Creates {@link CachedRecord} from this adept. This means that the adept was promoted to finally cached record.
	 */
	public CachedRecord toCachedRecord() {
		return new CachedRecord(this.recordType, this.recordHash, this.costToPerformanceRatio, 0, this.sizeInBytes);
	}
}
