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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * DTO that is connected to {@link PriceTerminationFormula} allowing to optimize formula tree in the such way,
 * that terminating formula with same price evaluation context will be replaced by single instance - taking advantage
 * of result memoization.
 *
 * @param targetPriceIndexes Set of price list identifications that control which price indexes will the formula target. Retrieved from
 *                           the local scope of the input query.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record PriceEvaluationContext(@Nonnull PriceIndexKey... targetPriceIndexes) implements Serializable {
	@Serial private static final long serialVersionUID = -2132423408087460595L;

	public PriceEvaluationContext {
		Assert.isPremiseValid(
			!ArrayUtils.isEmpty(targetPriceIndexes),
			"Expected at least one target price index identification!"
		);
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.ARRAY_BASE_SIZE +
			targetPriceIndexes.length * (MemoryMeasuringConstants.REFERENCE_SIZE + PriceIndexKey.MEMORY_SIZE);
	}

	@Override
	public String toString() {
		return Arrays.toString(targetPriceIndexes);
	}

	/**
	 * Method computes unique hash for this particular instance, this hash has much better collision rate than common
	 * {@link #hashCode()} and is targeted to be used in cache key.
	 */
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
					Arrays.stream(targetPriceIndexes)
						.mapToLong(it -> hashFunction.hashChars(it.toString()))
						.toArray()
				);
	}
}
