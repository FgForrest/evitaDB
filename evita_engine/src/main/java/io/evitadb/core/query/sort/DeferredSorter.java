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

package io.evitadb.core.query.sort;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * This sorter implementation delegates sorting logic to the `sorter` instance, but provides a wrapper around it in
 * the form of `executionWrapper` allowing to envelope the sort call with custom logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class DeferredSorter implements Sorter {
	@Nonnull private final Sorter sorter;
	@Nonnull private final ToIntFunction<IntSupplier> executionWrapper;

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new DeferredSorter(sorter.andThen(sorterForUnknownRecords), executionWrapper);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new DeferredSorter(sorter.cloneInstance(), executionWrapper);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return sorter.getNextSorter();
	}

	@Override
	public int sortAndSlice(@Nonnull QueryExecutionContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		return executionWrapper.applyAsInt(
			() -> ConditionalSorter.getFirstApplicableSorter(queryContext, sorter)
				.sortAndSlice(queryContext, input, startIndex, endIndex, result, peak)
		);
	}
}
