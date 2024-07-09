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
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sorter implementation allows to convert and slice result of the {@link Formula#compute()} to final slice of data
 * ({@link java.util.List}) that respects pagination settings.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Sorter {

	/**
	 * Creates clone of this sorter instance.
	 */
	@Nonnull
	Sorter cloneInstance();

	/**
	 * Method allows creating combined sorter from this instance and passed instance.
	 * Creates new sorter that first sorts by this instance order and for rest records, that cannot be sorted by this
	 * sorter, the passed sorter will be used.
	 */
	@Nonnull
	Sorter andThen(Sorter sorterForUnknownRecords);

	/**
	 * Method returns next sorter in the sort chain. I.e. the sorter that will be applied on entity keys, that couldn't
	 * have been sorted by this sorter (due to lack of information).
	 */
	@Nullable
	Sorter getNextSorter();

	/**
	 * Method sorts output of the {@link AbstractFormula} input and extracts slice of the result data between `startIndex` (inclusive)
	 * and `endIndex` (exclusive).
	 */
	int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak
	);

}
