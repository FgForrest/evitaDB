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

package io.evitadb.core.cache.payload;

import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Flattened formula represents a memoized form of original formula that contains already computed bitmap of results.
 * This particular class contains only header of the memoized formula, the really cached ones extends this class.
 * This class is used only as a temporary substitute until the results are known.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class CachePayloadHeader implements Serializable {
	@Serial private static final long serialVersionUID = -6019603728193692003L;
	/**
	 * Hash that uniquely identifies the serialized computational object. It needs to be computed with low collision
	 * hashing function.
	 */
	@Getter protected final long recordHash;
	/**
	 * Transactional id hash is a has computed from the output of {@link #transactionalDataIds}. The hash must be
	 * computed from distinct, sorted transactional ids of all transactional data sources (bitmaps / indexes). The hash
	 * must be same for two formula/computer instances that build from same sources and must differ for formula instance
	 * that has at least single difference in the array contents (we are going to rely on some hash function with low
	 * rate of collisions).
	 */
	@Getter protected final long transactionalIdHash;
	/**
	 * Primary keys ({@link TransactionalLayerProducer#getId()} exactly) of transactional data structures that affect
	 * the computed result of this memoized bitmap.
	 */
	@Getter protected final long[] transactionalDataIds;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(@Nonnull long[] transactionalIds) {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.LONG_SIZE +
			transactionalIds.length * MemoryMeasuringConstants.LONG_SIZE;
	}

	protected CachePayloadHeader(long recordHash, long transactionalIdHash, long[] transactionalDataIds) {
		this.recordHash = recordHash;
		this.transactionalIdHash = transactionalIdHash;
		this.transactionalDataIds = transactionalDataIds;
	}

}
