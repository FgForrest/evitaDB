/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.query.algebra;

import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.utils.MemoryMeasuringConstants;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * This interface marks formulas which can be cached with computed data via {@link CacheSupervisor}.
 * Not all formulas are suitable for caching - select those formulas that may contain expensive computation and produce
 * limited result that fits into the memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CacheableFormula extends Formula {

	/**
	 * Method is responsible for creating formula containing all crucial information this formula is computing.
	 * The created {@link CachePayloadHeader} must be 100% interchangeable with this instance in terms of query evaluation.
	 * It must keep the result of {@link Formula#compute()} method, as well other side computed data such as
	 * {@link FilteredPriceRecordAccessor#getFilteredPriceRecords()} which are required for sorting and so on.
	 */
	CachePayloadHeader toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction);

	/**
	 * Method returns gross estimation of the in-memory size of {@link #toSerializableFormula(long, LongHashFunction)}
	 * instance. The estimation is expected not to be a precise one. Please use constants from {@link MemoryMeasuringConstants}
	 * for size computation.
	 */
	int getSerializableFormulaSizeEstimate();

	/**
	 * Returns copy of this formula with same inner formulas but new method handle that references callback that
	 * will be called when {@link #compute()} method is first executed and memoized result is available.
	 */
	@Nonnull
	CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas);

}
