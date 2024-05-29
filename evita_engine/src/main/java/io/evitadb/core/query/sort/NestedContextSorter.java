/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This class is a wrapper for {@link Sorter} along with correct nested {@link QueryContext} initialized for proper
 * query and entity type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class NestedContextSorter {
	private final QueryContext context;
	private final Sorter sorter;

	/**
	 * Method sorts output of the {@link AbstractFormula} input and extracts slice of the result data between `startIndex` (inclusive)
	 * and `endIndex` (exclusive).
	 */
	public int sortAndSlice(
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak
	) {
		return sorter.sortAndSlice(context, input, startIndex, endIndex, result, peak);
	}

}
