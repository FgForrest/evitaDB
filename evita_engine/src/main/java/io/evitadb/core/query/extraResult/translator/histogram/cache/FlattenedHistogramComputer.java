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

package io.evitadb.core.query.extraResult.translator.histogram.cache;

import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Flattened formula represents a memoized form of original Histogram.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FlattenedHistogramComputer extends CachePayloadHeader implements TransactionalDataRelatedStructure, EvitaResponseExtraResultComputer<CacheableHistogramContract> {
	@Serial private static final long serialVersionUID = 4049228240087093145L;
	/**
	 * Contains originally computed histogram.
	 */
	private final CacheableHistogramContract histogram;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(@Nonnull long[] transactionalIds, @Nonnull CacheableHistogramContract histogram) {
		return CachePayloadHeader.estimateSize(transactionalIds) +
			histogram.estimateSize();
	}

	public FlattenedHistogramComputer(long recordHash, long transactionalIdHash, @Nonnull long[] transactionalIds, @Nonnull CacheableHistogramContract histogram) {
		super(recordHash, transactionalIdHash, transactionalIds);
		this.histogram = histogram;
	}

	@Nonnull
	@Override
	public CacheableHistogramContract compute() {
		return this.histogram;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {

	}

	@Override
	public long getHash() {
		return this.recordHash;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.transactionalDataIds;
	}

	@Override
	public long getEstimatedCost() {
		return 0;
	}

	@Override
	public long getCost() {
		return 0;
	}

	@Override
	public long getOperationCost() {
		return 0;
	}

	@Override
	public long getCostToPerformanceRatio() {
		return Long.MAX_VALUE;
	}

}
