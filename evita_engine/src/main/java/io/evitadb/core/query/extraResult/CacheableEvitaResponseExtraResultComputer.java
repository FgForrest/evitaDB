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

package io.evitadb.core.query.extraResult;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.utils.MemoryMeasuringConstants;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * Interface must be implemented by classes that provide "computational" logic for {@link EvitaResponseExtraResult}
 * objects and that can be subject to caching logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CacheableEvitaResponseExtraResultComputer<T> extends TransactionalDataRelatedStructure, EvitaResponseExtraResultComputer<T> {

	/**
	 * Method is responsible for creating object containing all crucial information this implementation is computing.
	 * The created {@link CachePayloadHeader} must be 100% interchangeable with this instance in terms of query evaluation.
	 * It must keep the result of {@link EvitaResponseExtraResultComputer#compute()} method and must implement this
	 * interface
	 */
	<U extends CachePayloadHeader & EvitaResponseExtraResultComputer<T>> U toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction);

	/**
	 * Method returns gross estimation of the in-memory size of {@link #toSerializableResult(long, LongHashFunction)}
	 * instance. The estimation is expected not to be a precise one. Please use constants from {@link MemoryMeasuringConstants}
	 * for size computation.
	 */
	int getSerializableResultSizeEstimate();

	/**
	 * Returns copy of this computer with same configuration but new method handle that references callback that
	 * will be called when {@link #compute()} method is first executed and memoized result is available.
	 */
	@Nonnull
	CacheableEvitaResponseExtraResultComputer<T> getCloneWithComputationCallback(@Nonnull Consumer<CacheableEvitaResponseExtraResultComputer<T>> selfOperator);

}
